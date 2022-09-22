/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.google.gct.testing.android;

import com.android.tools.idea.run.ValidationError;
import com.google.common.collect.Lists;
import com.google.gct.testing.CloudConfigurationHelper;
import com.google.gct.testing.CloudConfigurationImpl;
import com.google.gct.testing.CloudPersistentConfiguration;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static com.google.gct.testing.launcher.CloudAuthenticator.isUserLoggedIn;

/**
 * Utility methods for cloud target choosers.
 */
public final class CloudTargetUtil {
  private CloudTargetUtil() {} // Not instantiable.

  @NotNull
  public static List<ValidationError> validate(@NotNull AndroidFacet facet, @NotNull CloudConfiguration.Kind kind,
                                               @NotNull String cloudProjectId, int cloudConfigurationId) {
    List<ValidationError> errors = Lists.newArrayList();
    if (!isUserLoggedIn()) {
      errors.add(ValidationError.fatal("Not signed in with Google."));
      // Can't continue.
      return errors;
    }

    if (cloudProjectId.isEmpty()) {
      errors.add(ValidationError.fatal("Cloud project not specified."));
    }

    if (cloudConfigurationId == CloudConfigurationImpl.DEFAULT_MATRIX_CONFIGURATION_ID
        || cloudConfigurationId == CloudConfigurationImpl.DEFAULT_FREE_TIER_MATRIX_CONFIGURATION_ID) {
      // Pre-configured non-editable configurations should always be well-formed.
      return errors;
    }

    CloudPersistentConfiguration selectedConfig = null;
    for (CloudPersistentConfiguration config : CloudConfigurationHelper.getPersistentConfigurations(facet, kind)) {
      if (config.id == cloudConfigurationId) {
        selectedConfig = config;
      }
    }

    if (selectedConfig == null) {
      errors.add(ValidationError.fatal("Matrix configuration not specified."));
      // Can't continue.
      return errors;
    }

    if (selectedConfig.devices.isEmpty() || selectedConfig.apiLevels.isEmpty()
        || selectedConfig.languages.isEmpty() || selectedConfig.orientations.isEmpty()) {
      errors.add(ValidationError.fatal("Selected matrix configuration is empty."));
    }

    return errors;
  }
}
