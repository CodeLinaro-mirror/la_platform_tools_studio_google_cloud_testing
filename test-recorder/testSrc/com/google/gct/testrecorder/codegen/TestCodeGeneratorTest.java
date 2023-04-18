/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.google.gct.testrecorder.codegen;

import static com.android.tools.idea.testing.TestProjectPaths.ETR_WITHOUT_ANDROIDX;
import static com.android.tools.idea.testing.TestProjectPaths.ETR_WITH_ANDROIDX;

import com.android.tools.idea.gradle.project.build.invoker.GradleInvocationResult;
import com.android.tools.idea.testing.AndroidGradleTestCase;
import com.google.gct.testrecorder.util.ActionsCreator;
import com.intellij.ide.fileTemplates.JavaTemplateUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaDirectoryService;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiManager;
import java.io.File;

public class TestCodeGeneratorTest extends AndroidGradleTestCase {

  private String ANDROIDX_PROJECT_NAME = "etrwithandroidx";
  private String WITHOUT_ANDROIDX_PROJECT_NAME = "etrtestproject";

  @Override
  protected boolean shouldRunTest() {
    // Do not run tests on Windows (see http://b.android.com/222904)
    return !SystemInfo.isWindows && super.shouldRunTest();
  }

  public void testJavaCodeGeneration() {
    performCodeGenerationTest(false, false);
  }

  public void testJavaAndroidxCodeGeneration() {
    performCodeGenerationTest(true, false);
  }

  public void testKotlinCodeGeneration() {
    performCodeGenerationTest(false, true);
  }

  public void testKotlinAndroidxCodeGeneration() {
    performCodeGenerationTest(true, true);
  }


  public void performCodeGenerationTest(boolean isAndroidx, boolean isKotlin) {
    loadProjectHelper(isAndroidx);
    PsiClass testClass = createTestClass(isAndroidx, isKotlin);

    TestCodeGenerator testCodeGenerator = getTestCodeGenerator(testClass, isAndroidx, isKotlin);

    String testFilePath = testClass.getContainingFile().getVirtualFile().getPath();
    VirtualFile testVirtualFile = LocalFileSystem.getInstance().findFileByPath(testFilePath);

    ApplicationManager.getApplication().runWriteAction(() -> testCodeGenerator.writeCode(testVirtualFile));
    Project project = getProject();

    testVirtualFile.refresh(false, true, () -> {
      // Do not apply import optimizer and code reformatter as they do not handle Kotlin code in test mode.
      PsiDocumentManager.getInstance(project).commitAllDocuments();
    });
    try {
      GradleInvocationResult result = invokeGradleTasks(getProject(), "assembleAndroidTest");
      if (result.isBuildSuccessful() != true) {
        fail("Test failed to build");
      }
    }
    catch (Exception e) {
      fail(e.getMessage());
    }
  }

  private PsiClass createTestClass(boolean isAndroidx, boolean isKotlinTestClass) {
    String androidTestFolderName = getProject().getBasePath() + "/app/src/androidTest/java/com/example/";
    if (isAndroidx) {
      androidTestFolderName += "etrwithandroidx";
    }
    else {
      androidTestFolderName += "etrtestproject";
    }
    VirtualFile androidTestFolder =
      VfsUtil.findFileByIoFile(new File(androidTestFolderName), false);
    if (androidTestFolder == null) {
      throw new RuntimeException("Failed to find androidTest folder, please check if the folder exists in test environment.");
    }
    PsiDirectory containingDirectory = PsiManager.getInstance(getProject()).findDirectory(androidTestFolder);

    PsiClass testClass = ApplicationManager.getApplication().runWriteAction(new Computable<>() {
      @Override
      public PsiClass compute() {
        PsiClass testClass = JavaDirectoryService.getInstance()
          .createClass(containingDirectory, "MyTest", JavaTemplateUtil.INTERNAL_CLASS_TEMPLATE_NAME, false);

        if (isKotlinTestClass) {
          testClass.getContainingFile().setName("MyTest.kt");
        }

        // To avoid concurrent modification warning which will break the test with a NullPointerException.
        PsiManager.getInstance(getProject()).reloadFromDisk(testClass.getContainingFile());

        return testClass;
      }
    });

    if (testClass == null) {
      throw new RuntimeException("Failed to create the test class!");
    }

    return testClass;
  }

  private TestCodeGenerator getTestCodeGenerator(PsiClass testClass, boolean isAndroidx, boolean isKotlin) {
    String resourcePackageName, applicationId, launchedActivityName;
    resourcePackageName = applicationId = launchedActivityName = "com.example.";
    if (isAndroidx) {
      resourcePackageName += ANDROIDX_PROJECT_NAME;
      applicationId += ANDROIDX_PROJECT_NAME;
      launchedActivityName += ANDROIDX_PROJECT_NAME;
    }
    else {
      resourcePackageName += WITHOUT_ANDROIDX_PROJECT_NAME;
      applicationId += WITHOUT_ANDROIDX_PROJECT_NAME;
      launchedActivityName += WITHOUT_ANDROIDX_PROJECT_NAME;
    }
    launchedActivityName += ".MainActivity";
    return new TestCodeGenerator(resourcePackageName, applicationId, getModule("app"), testClass,
                                 ActionsCreator.createActions(System.currentTimeMillis()),
                                 launchedActivityName, false, isKotlin, isAndroidx);
  }

  private void loadProjectHelper(boolean isAndroidx) {
    try {
      if (isAndroidx) {
        loadProject(ETR_WITH_ANDROIDX);
      }
      else {
        loadProject(ETR_WITHOUT_ANDROIDX);
      }
    }
    catch (Exception e) {
      e.printStackTrace(System.err);
      throw new RuntimeException(
        "Failed to load project, please check if all project dependencies are present in BUILD file: cause is\n" + e.getMessage());
    }
  }
}
