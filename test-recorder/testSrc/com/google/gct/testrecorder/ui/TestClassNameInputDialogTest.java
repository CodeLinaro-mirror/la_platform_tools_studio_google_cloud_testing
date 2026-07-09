/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.google.gct.testrecorder.ui;

import static com.google.common.truth.Truth.assertThat;

import com.android.tools.idea.testing.AndroidProjectRule;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TestClassNameInputDialogTest {

  @Rule
  public AndroidProjectRule myProjectRule = AndroidProjectRule.inMemory();

  private CodeInsightTestFixture myFixture;

  @Before
  public void setUp() {
    myFixture = myProjectRule.getFixture();
  }

  @Test
  public void isGenerated_onEdt_pathBased() {
    VirtualFile generatedFile = myFixture.getTempDirFixture().createFile("build/generated/Source.java");
    EdtTestUtil.runInEdtAndWait(() -> {
      assertThat(TestClassNameInputDialog.isGenerated(generatedFile, myProjectRule.getProject())).isTrue();
    });
  }

  @Test
  public void isAndroidTest_onEdt_pathBased() {
    VirtualFile androidTestFile = myFixture.getTempDirFixture().createFile("src/androidTest/java/Test.java");
    EdtTestUtil.runInEdtAndWait(() -> {
      assertThat(TestClassNameInputDialog.isAndroidTest(androidTestFile, myProjectRule.getModule())).isTrue();
    });
  }

  @Test
  public void getOrCreateSubdirectoryOnEDT_createsMissingDirs() {
    VirtualFile root = myFixture.getTempDirFixture().getFile(".");
    String[] path = {"a", "b", "c"};

    EdtTestUtil.runInEdtAndWait(() -> {
      VirtualFile created = TestClassNameInputDialog.getOrCreateSubdirectoryOnEDT(root, path, true);
      assertThat(created.getPath()).endsWith("/a/b/c");
      assertThat(root.findFileByRelativePath("a/b/c")).isEqualTo(created);
    });
  }
}
