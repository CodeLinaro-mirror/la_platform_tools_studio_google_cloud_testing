/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.google.gct.testing.config;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import org.jetbrains.plugins.groovy.util.SdkHomeBean;
import org.jetbrains.plugins.groovy.util.SdkHomeSettings;

@State(
    name = "GsutilSettings",
    storages = @Storage("gsutil_config.xml")
)
public class GsutilSettings extends SdkHomeSettings {
  public GsutilSettings(Project project) {
    super(project);
  }

  public static GsutilSettings getInstance(Project project) {
    return project.getService(GsutilSettings.class);
  }

  public static String getGsutilExecutable(Project project) {
    SdkHomeBean state = getInstance(project).getState();
    return state == null || state.getSdkHome().isEmpty() ? "" : state.getSdkHome() + "/gsutil";
  }
}
