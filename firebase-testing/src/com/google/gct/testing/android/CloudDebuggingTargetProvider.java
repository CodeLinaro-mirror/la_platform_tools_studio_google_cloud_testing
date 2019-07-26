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

import com.android.ddmlib.IDevice;
import com.android.tools.idea.run.DeviceCount;
import com.android.tools.idea.run.DeviceFutures;
import com.android.tools.idea.run.DeviceSelectionUtils;
import com.android.tools.idea.run.TargetDeviceFilter;
import com.android.tools.idea.run.TargetSelectionMode;
import com.android.tools.idea.run.ValidationError;
import com.android.tools.idea.run.editor.DeployTarget;
import com.android.tools.idea.run.editor.DeployTargetConfigurable;
import com.android.tools.idea.run.editor.DeployTargetConfigurableContext;
import com.android.tools.idea.run.editor.DeployTargetProvider;
import com.android.tools.idea.run.editor.DeployTargetState;
import com.google.common.collect.Lists;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import java.util.List;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CloudDebuggingTargetProvider extends DeployTargetProvider {
  private String myCloudDeviceSerialNumber;


  public static final class State extends DeployTargetState {

    @NotNull
    @Override
    public List<ValidationError> validate(@NotNull AndroidFacet facet) {
      return Lists.newArrayList();
    }
  }

  @NotNull
  @Override
  public String getId() {
    return TargetSelectionMode.FIREBASE_DEVICE_DEBUGGING.name();
  }

  @NotNull
  @Override
  public String getDisplayName() {
    return "Firebase Test Lab Debugging Device";
  }

  @NotNull
  @Override
  public DeployTargetState createState() {
    return new State();
  }

  @Override
  public DeployTargetConfigurable createConfigurable(@NotNull Project project, @NotNull Disposable parentDisposable,
                                                     @NotNull DeployTargetConfigurableContext context) {
    return new CloudDebuggingTargetConfigurable();
  }

  @Override
  public DeployTarget getDeployTarget() {
    return new DeployTarget() {
      @Override
      public boolean hasCustomRunProfileState(@NotNull Executor executor) {
        return false;
      }

      @Override
      public RunProfileState getRunProfileState(@NotNull Executor executor,
                                                @NotNull ExecutionEnvironment env,
                                                @NotNull DeployTargetState state) throws ExecutionException {
        return null;
      }

      @Nullable
      @Override
      public DeviceFutures getDevices(@NotNull DeployTargetState state,
                                      @NotNull AndroidFacet facet,
                                      @NotNull DeviceCount deviceCount,
                                      boolean debug,
                                      int runConfigId) {
        return DeviceFutures.forDevices(DeviceSelectionUtils.getAllCompatibleDevices(new TargetDeviceFilter() {
          @Override
          public boolean matchesDevice(@NotNull IDevice device) {
            if (device.isEmulator()) {
              return false;
            }
            return device.getSerialNumber().equals(myCloudDeviceSerialNumber);
          }
        }));
      }
    };
  }

  @Override
  protected boolean isApplicable(boolean testConfiguration) {
    return false;
  }

  public void setCloudDeviceSerialNumber(String cloudDeviceSerialNumber) {
    myCloudDeviceSerialNumber = cloudDeviceSerialNumber;
  }

}
