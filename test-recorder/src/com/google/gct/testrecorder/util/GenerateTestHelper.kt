/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.gct.testrecorder.util

import com.android.SdkConstants
import com.android.tools.idea.projectsystem.getModuleSystem
import com.android.tools.idea.projectsystem.getProjectSystem
import com.google.gct.testrecorder.codegen.TestCodeGenerator
import com.google.gct.testrecorder.event.ElementAction
import com.intellij.ide.fileTemplates.FileTemplate
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.ide.fileTemplates.FileTemplateUtil
import com.intellij.ide.fileTemplates.JavaTemplateUtil
import com.intellij.ide.fileTemplates.actions.CreateFromTemplateActionBase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.util.endOffset
import com.intellij.util.IncorrectOperationException
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.swing.JPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.android.dom.manifest.Manifest
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.kotlin.psi.KtFile

const val KOTLIN_LANGUAGE_NAME = "Kotlin"
const val NOTIFICATION_TITLE = "Espresso test recorder"

// Timeout in minutes for waiting for indexing and generating ETR tests. This value is chosen based
// on heuristics.
private const val DEFAULT_GENERATE_TEST_TIMEOUT_MINUTES = 5L

@Throws(Exception::class)
fun generateTest(
  project: Project,
  testClassName: String,
  testClassParent: PsiDirectory,
  selectedLanguage: String,
  facet: AndroidFacet,
  rootPanel: JPanel,
  allModelActions: List<ElementAction>,
  launchedActivityName: String,
  wasEverPaused: Boolean,
  progressIndicator: ProgressIndicator,
): String {
  val application = ApplicationManager.getApplication()
  val isKotlinClass = (KOTLIN_LANGUAGE_NAME == selectedLanguage)
  val latch = CountDownLatch(1)
  CoroutineScope(Dispatchers.EDT).launch {
    withContext(Dispatchers.IO) { DumbService.getInstance(project).waitForSmartMode() }
    progressIndicator.checkCanceled()
    val testClass =
      withContext(Dispatchers.EDT) {
        createClassFromTemplate(project, testClassName, testClassParent, isKotlinClass)
      }

    // Compute resource package name and application id before the potential Gradle confusion.
    val testClassModule = facet.module
    var resourcePackageName =
      withContext(Dispatchers.IO) {
        application.runReadAction<String?> {
          AndroidFacet.getInstance(testClassModule)?.let { testClassFacet ->
            Manifest.getMainManifest(testClassFacet)?.let { manifest ->
              manifest.getPackage().stringValue
            }
          }
        }
      } ?: ""

    val applicationId = getApplicationId(facet, resourcePackageName)

    if (resourcePackageName.isEmpty()) {
      if (applicationId.isEmpty()) {
        throw RuntimeException("Error getting package name for new test class")
      }
      // Fallback to application ID as the app's package name.
      resourcePackageName = applicationId
    }

    val projectSystem = project.getProjectSystem()
    val usesAndroidxDependency =
      EspressoSetupToken.EP_NAME.extensionList
        .firstOrNull { it.isApplicable(projectSystem) }
        ?.ensureSetup(
          projectSystem,
          testClassModule,
          facet,
          rootPanel,
          allModelActions.toMutableList(),
        ) ?: false
    application.invokeAndWait {
      TestCodeGenerator(
          resourcePackageName,
          applicationId,
          testClassModule,
          testClass,
          allModelActions,
          launchedActivityName,
          wasEverPaused,
          isKotlinClass,
          usesAndroidxDependency,
        )
        .generate()
    }

    progressIndicator.checkCanceled()
    // Show created test file in editor
    withContext(Dispatchers.EDT) {
      FileEditorManager.getInstance(project)
        .openTextEditor(
          OpenFileDescriptor(project, testClass.containingFile.virtualFile, testClass.endOffset),
          true,
        )
    }
    latch.countDown()
  }

  try {
    latch.await(DEFAULT_GENERATE_TEST_TIMEOUT_MINUTES, TimeUnit.MINUTES)
  } catch (e: Exception) {
    throw TimeoutException("Timeout creating new test file")
  }
  return ""
}

@Throws(Exception::class)
private suspend fun createClassFromTemplate(
  project: Project,
  testClassName: String,
  testClassParent: PsiDirectory,
  isKotlinClass: Boolean,
): PsiClass {
  val (className, templateName) =
    if (isKotlinClass) {
      Pair(testClassName + SdkConstants.DOT_KT, "Kotlin Class")
    } else {
      Pair(testClassName + SdkConstants.DOT_JAVA, JavaTemplateUtil.INTERNAL_CLASS_TEMPLATE_NAME)
    }
  val fileTemplate = FileTemplateManager.getInstance(project).getInternalTemplate(templateName)
  fileTemplate.isReformatCode = false

  val defaultProperties = FileTemplateManager.getInstance(project).defaultProperties
  val properties = Properties(defaultProperties)
  properties.setProperty(FileTemplate.ATTRIBUTE_NAME, testClassName)

  val element =
    ApplicationManager.getApplication().runWriteAction<PsiElement> {
      FileTemplateUtil.createFromTemplate(fileTemplate, className, properties, testClassParent)
    }
  val file = element.containingFile
  val testClass =
    withContext(Dispatchers.IO) {
      ApplicationManager.getApplication().runReadAction<PsiClass> {
        if (isKotlinClass) {
          (file as KtFile).classes.firstOrNull()
        } else {
          (file as PsiJavaFile).classes.firstOrNull()
        } ?: throw IncorrectOperationException("Failed to create a test class from a template")
      }
    }

  if (fileTemplate.isLiveTemplateEnabled && file.viewProvider.document != null) {
    ApplicationManager.getApplication().invokeAndWait {
      CreateFromTemplateActionBase.startLiveTemplate(file)
    }
  }
  return testClass
}

// defaultId is either an empty string or package name from manifest of the test module.
fun getApplicationId(facet: AndroidFacet, defaultId: String): String {
  return try {
    facet.getModuleSystem().getApplicationIdProvider().getPackageName()
  } catch (e: Exception) {
    Logger.getInstance(NOTIFICATION_TITLE).error(e)
    defaultId
  }
}
