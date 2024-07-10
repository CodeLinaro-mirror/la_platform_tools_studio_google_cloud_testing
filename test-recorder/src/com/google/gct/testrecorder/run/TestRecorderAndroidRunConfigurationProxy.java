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
package com.google.gct.testrecorder.run;

import com.android.tools.idea.projectsystem.AndroidProjectSystem;
import com.android.tools.idea.projectsystem.ProjectSystemUtil;
import com.android.tools.idea.run.AndroidRunConfiguration;
import com.android.tools.idea.run.activity.launch.DefaultActivityLaunch;
import com.android.tools.idea.run.activity.launch.LaunchOptionState;
import com.android.tools.idea.run.activity.launch.SpecificActivityLaunch;
import com.google.gct.testrecorder.util.EspressoSetupToken;
import com.intellij.openapi.module.Module;

public class TestRecorderAndroidRunConfigurationProxy implements TestRecorderRunConfigurationProxy {

  private final AndroidRunConfiguration myBaseConfiguration;

  public TestRecorderAndroidRunConfigurationProxy(AndroidRunConfiguration baseConfiguration) {
    myBaseConfiguration = baseConfiguration;
  }

  @Override
  public boolean isNativeProject() {
    AndroidProjectSystem projectSystem = ProjectSystemUtil.getProjectSystem(myBaseConfiguration.getProject());
    EspressoSetupToken token = EspressoSetupToken.EP_NAME.getExtensionList().stream()
      .filter((it) -> it.isApplicable(projectSystem))
      .findFirst().orElse(null);
    if (token != null) {
      return token.isNativeProject(projectSystem, getModule());
    }
    return false;
  }

  @Override
  public Module getModule() {
    return myBaseConfiguration.getConfigurationModule().getModule();
  }

  @Override
  public boolean isLaunchActivitySupported() {
    LaunchOptionState activityLaunchOptionState = myBaseConfiguration.getLaunchOptionState(myBaseConfiguration.MODE);

    // Supported launch activities are Default and Specified.
    return activityLaunchOptionState instanceof DefaultActivityLaunch.State || activityLaunchOptionState instanceof SpecificActivityLaunch.State;
  }
}
