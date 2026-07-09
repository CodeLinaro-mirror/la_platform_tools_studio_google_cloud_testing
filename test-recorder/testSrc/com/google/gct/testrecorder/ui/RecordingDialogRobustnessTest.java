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
import com.google.gct.testrecorder.util.UiAutomatorNodeHelper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RecordingDialogRobustnessTest {

  @Rule
  public AndroidProjectRule myProjectRule = AndroidProjectRule.inMemory();

  @Test
  public void uiAutomatorNodeHelper_handlesNulls() {
    assertThat(UiAutomatorNodeHelper.getRotation(null)).isEqualTo(0);
    assertThat(UiAutomatorNodeHelper.createElementLevelMap(null)).isEmpty();
    assertThat(UiAutomatorNodeHelper.isTextView(null)).isFalse();
    assertThat(UiAutomatorNodeHelper.getClassName(null)).isEqualTo("");
    assertThat(UiAutomatorNodeHelper.getResourceId(null)).isEqualTo("");
    assertThat(UiAutomatorNodeHelper.getText(null)).isEqualTo("");
    assertThat(UiAutomatorNodeHelper.getContentDescription(null)).isEqualTo("");
    assertThat(UiAutomatorNodeHelper.getViewGroupChildPosition(null)).isEqualTo(-1);
    assertThat(UiAutomatorNodeHelper.getAppPackageName(null)).isEqualTo("");
  }

  @Test
  public void uiAutomatorNodeHelper_getAppPackageName_handlesEmptyRoot() {
    com.android.uiautomator.tree.BasicTreeNode root = new com.android.uiautomator.tree.BasicTreeNode();
    assertThat(UiAutomatorNodeHelper.getAppPackageName(root)).isEqualTo("");
  }
}
