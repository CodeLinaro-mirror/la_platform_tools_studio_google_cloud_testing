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

import com.android.annotations.Nullable;
import com.android.ddmlib.IDevice;
import com.android.tools.idea.gradle.dsl.api.GradleBuildModel;
import com.android.tools.idea.gradle.dsl.api.ext.GradlePropertyModel;
import com.android.tools.idea.run.AndroidRunConfiguration;
import com.android.tools.idea.run.DeviceFutures;
import com.android.tools.idea.run.activity.launch.DefaultActivityLaunch;
import com.android.tools.idea.run.activity.launch.LaunchOptionState;
import com.android.tools.idea.run.activity.launch.SpecificActivityLaunch;
import com.google.common.util.concurrent.ListenableFuture;
import com.intellij.execution.configurations.LocatableConfigurationBase;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.module.Module;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public class TestRecorderAndroidRunConfigurationProxy implements TestRecorderRunConfigurationProxy {

  private final AndroidRunConfiguration myBaseConfiguration;

  public TestRecorderAndroidRunConfigurationProxy(AndroidRunConfiguration baseConfiguration) {
    myBaseConfiguration = baseConfiguration;
  }

  @Override
  public boolean isNativeProject() {
    Module module = getModule();
    // TODO(b/294274926): Do not use DSL models to detect Gradle native projects.
    return GradleBuildModel.get(module) != null
           && GradleBuildModel.get(module).android().externalNativeBuild().cmake().version().getValueType()
              != GradlePropertyModel.ValueType.NONE;
  }

  @NotNull
  @Override
  public LocatableConfigurationBase getTestRecorderRunConfiguration() {
    return new TestRecorderAndroidRunConfiguration(myBaseConfiguration);
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

  @Override
  public String getLaunchActivityClass() {
    LaunchOptionState activityLaunchOptionState = myBaseConfiguration.getLaunchOptionState(myBaseConfiguration.MODE);

    if (activityLaunchOptionState instanceof SpecificActivityLaunch.State) {
      return ((SpecificActivityLaunch.State)activityLaunchOptionState).ACTIVITY_CLASS;
    }

    return "";
  }

  @Nullable
  @Override
  public List<ListenableFuture<IDevice>> getDeviceFutures(ExecutionEnvironment environment) {
    DeviceFutures deviceFutures = environment.getCopyableUserData(DeviceFutures.KEY);
    return deviceFutures == null ? null : deviceFutures.get();
  }
}
