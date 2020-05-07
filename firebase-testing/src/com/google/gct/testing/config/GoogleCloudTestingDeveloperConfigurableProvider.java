/*
 * Copyright (C) 2020 The Android Open Source Project
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

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurableProvider;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

public class GoogleCloudTestingDeveloperConfigurableProvider extends ConfigurableProvider {
  private final static String SHOW_GOOGLE_CLOUD_TESTING_SETTINGS = "show.google.cloud.testing.settings";

  private final Project myProject;

  public GoogleCloudTestingDeveloperConfigurableProvider(Project project) {
    myProject = project;
  }

  @Override
  public boolean canCreateConfigurable() {
    return Boolean.getBoolean(SHOW_GOOGLE_CLOUD_TESTING_SETTINGS);
  }

  @Nullable
  @Override
  public Configurable createConfigurable() {
    if (myProject == null) {
      return null;
    }

    return new GoogleCloudTestingDeveloperConfigurable(myProject);
  }
}
