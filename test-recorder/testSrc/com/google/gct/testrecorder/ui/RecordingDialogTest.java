/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.google.gct.testrecorder.util.ActionsCreator.createActions;

import com.google.gct.testrecorder.util.ResourceHelper;
import java.io.File;
import org.apache.commons.io.FileUtils;
import org.jetbrains.android.AndroidTestCase;

public class RecordingDialogTest extends AndroidTestCase {

  public void testJsonGeneration() {
    String jsonForActions = RecordingDialog.getJsonForActions(myModule.getProject(), createActions(1484950638081l));
    assertEquals(getExpectedJsonText(), jsonForActions);
  }

  private String getExpectedJsonText() {
    File expectedJsonTextFile = ResourceHelper.getFileForResource(this, "expected.json", "expected_json", "txt");
    try {
      // Remove the trailing empty line, which is always added to a file saved inside IntelliJ, and remove windows line terminator
      return FileUtils.readFileToString(expectedJsonTextFile).trim().replace("\r", "");
    } catch (Exception e) {
      throw new RuntimeException("Failed to read the expected JSON text " + expectedJsonTextFile.getAbsolutePath(), e);
    }
  }

}
