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

import com.google.gct.testrecorder.event.TestRecorderEvent;
import com.google.gct.testrecorder.util.ResourceHelper;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import junit.framework.TestCase;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import static com.google.gct.testrecorder.util.EventsCreator.createEvents;

public class RecordingDialogTest extends TestCase {

  public void testJsonGeneration() {
    String jsonForEvents = RecordingDialog.getJsonForEvents(createEvents(1484950638081l));
    assertEquals(getExpectedJsonText(), jsonForEvents);

    Type dataType = new TypeToken<ArrayList<TestRecorderEvent>>() {}.getType();
    List<TestRecorderEvent> fromJson = (ArrayList<TestRecorderEvent>) new Gson().fromJson(jsonForEvents, dataType);
    assertEquals(12, fromJson.size());
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
