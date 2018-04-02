/*
 * Copyright (C) 2016 The Android Open Source Project
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

import com.google.gct.testrecorder.util.ResourceHelper;
import com.intellij.codeInsight.actions.OptimizeImportsProcessor;
import com.intellij.codeInsight.actions.ReformatCodeProcessor;
import com.intellij.ide.fileTemplates.JavaTemplateUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import org.apache.commons.io.FileUtils;
import org.jetbrains.android.AndroidTestCase;

import java.io.File;

import static com.google.gct.testrecorder.util.EventsCreator.createEvents;

public class TestCodeGeneratorTest extends AndroidTestCase {

  public void testJavaCodeGeneration() throws Exception {
    PsiClass testClass = createTestClass(false);

    TestCodeGenerator testCodeGenerator =
      new TestCodeGenerator("resourcePackage", "applicationId", myFacet.getModule(), testClass, createEvents(System.currentTimeMillis()),
                            "p1.p2.MyActivity", false, false);

    String testFilePath = testClass.getContainingFile().getVirtualFile().getPath();
    VirtualFile testVirtualFile = LocalFileSystem.getInstance().findFileByPath(testFilePath);

    testCodeGenerator.writeCode(testFilePath, testVirtualFile);
    Project project = myModule.getProject();

    testVirtualFile.refresh(false, true, () -> {
      PsiDocumentManager.getInstance(project).commitAllDocuments();

      new OptimizeImportsProcessor(project, testClass.getContainingFile()).run();
      new ReformatCodeProcessor(project, testClass.getContainingFile(), null, false).run();

      String actualTestClassContent = FileDocumentManager.getInstance().getDocument(testVirtualFile).getText();
      assertEquals(getExpectedTestClassContent(false), actualTestClassContent);
    });
  }

  public void testKotlinCodeGeneration() throws Exception {
    PsiClass testClass = createTestClass(true);

    TestCodeGenerator testCodeGenerator =
      new TestCodeGenerator("resourcePackage", "applicationId", myFacet.getModule(), testClass, createEvents(System.currentTimeMillis()),
                            "p1.p2.MyActivity", false, true);

    String testFilePath = testClass.getContainingFile().getVirtualFile().getPath();
    VirtualFile testVirtualFile = LocalFileSystem.getInstance().findFileByPath(testFilePath);

    testCodeGenerator.writeCode(testFilePath, testVirtualFile);
    Project project = myModule.getProject();

    testVirtualFile.refresh(false, true, () -> {
      PsiDocumentManager.getInstance(project).commitAllDocuments();

      // Do not apply import optimizer and code reformatter as they do not handle Kotlin code in test mode.

      String actualTestClassContent = FileDocumentManager.getInstance().getDocument(testVirtualFile).getText();
      assertEquals(getExpectedTestClassContent(true).replaceAll("\\s", ""), actualTestClassContent.replaceAll("\\s", ""));
    });
  }

  private PsiClass createTestClass(boolean isKotlinTestClass) {
    PsiDirectory containingDirectory = PsiManager.getInstance(myModule.getProject()).findDirectory(myModule.getProject().getBaseDir());

    PsiClass testClass = ApplicationManager.getApplication().runWriteAction(new Computable<PsiClass>() {
      @Override
      public PsiClass compute() {
        PsiClass testClass = JavaDirectoryService.getInstance()
          .createClass(containingDirectory, "MyTest", JavaTemplateUtil.INTERNAL_CLASS_TEMPLATE_NAME, false);

        if (isKotlinTestClass) {
          testClass.getContainingFile().setName("MyTest.kt");
        }

        // To avoid concurrent modification warning which will break the test with a NullPointerException.
        PsiManager.getInstance(myModule.getProject()).reloadFromDisk(testClass.getContainingFile());

        return testClass;
      }
    });

    if (testClass == null) {
      throw new RuntimeException("Failed to create the test class!");
    }

    return testClass;
  }

  private String getExpectedTestClassContent(boolean isKotlinTestClass) {
    String expectedFileName = isKotlinTestClass ? "ExpectedKotlinTestClass.txt" : "ExpectedJavaTestClass.txt";
    File expectedTestClass = ResourceHelper.getFileForResource(this, expectedFileName, "expected_test_class_", "txt");
    try {
      return FileUtils.readFileToString(expectedTestClass).replace("\r", ""); // Fix Windows line terminators
    } catch (Exception e) {
      throw new RuntimeException("Failed to read the expected test class content " + expectedTestClass.getAbsolutePath(), e);
    }
  }

}
