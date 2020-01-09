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

import com.android.tools.idea.run.DeviceCount;
import com.android.tools.idea.run.DeviceFutures;
import com.android.tools.idea.run.TargetSelectionMode;
import com.android.tools.idea.run.ValidationError;
import com.android.tools.idea.run.deployment.DeviceAndSnapshotComboBoxTargetProvider;
import com.android.tools.idea.run.editor.DeployTarget;
import com.android.tools.idea.run.editor.DeployTargetConfigurable;
import com.android.tools.idea.run.editor.DeployTargetConfigurableContext;
import com.android.tools.idea.run.editor.DeployTargetProvider;
import com.android.tools.idea.run.editor.DeployTargetState;
import com.android.tools.idea.testartifacts.instrumented.AndroidTestRunConfiguration;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import java.util.List;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CloudTestMatrixTargetProvider extends DeployTargetProvider {
  public static final class State extends DeployTargetState {
    public int SELECTED_CLOUD_MATRIX_CONFIGURATION_ID = -1;
    public String SELECTED_CLOUD_MATRIX_PROJECT_ID = "";

    @NotNull
    @Override
    public List<ValidationError> validate(@NotNull AndroidFacet facet) {
      return CloudTargetUtil.validate(facet, CloudConfiguration.Kind.MATRIX, SELECTED_CLOUD_MATRIX_PROJECT_ID,
                                      SELECTED_CLOUD_MATRIX_CONFIGURATION_ID);
    }
  }

  @NotNull
  @Override
  public String getId() {
    return TargetSelectionMode.FIREBASE_DEVICE_MATRIX.name();
  }

  @NotNull
  @Override
  public String getDisplayName() {
    return "Firebase Test Lab Device Matrix";
  }

  @NotNull
  @Override
  public DeployTargetState createState() {
    return new State();
  }

  @Override
  public DeployTargetConfigurable createConfigurable(@NotNull Project project, @NotNull Disposable parentDisposable,
                                                     @NotNull DeployTargetConfigurableContext context) {
    return new CloudTestMatrixTargetConfigurable(project, parentDisposable, context);
  }

  @Override
  public boolean showInDevicePicker(@NotNull Executor executor) {
    return !(executor instanceof DefaultDebugExecutor);
  }

  @Override
  protected boolean isApplicable(boolean testConfiguration) {
    return testConfiguration;
  }

  @Override
  public DeployTarget getDeployTarget() {
    return new DeployTarget() {
      @Override
      public boolean hasCustomRunProfileState(@NotNull Executor executor) {
        return !(executor instanceof DefaultDebugExecutor);
      }

      @Override
      public RunProfileState getRunProfileState(@NotNull Executor executor,
                                                @NotNull ExecutionEnvironment env,
                                                @NotNull DeployTargetState state) throws ExecutionException {
        RunProfile runProfile = env.getRunProfile();
        // It is expected to be invoked for test run configurations only.
        if (!(runProfile instanceof AndroidTestRunConfiguration)) {
          return null;
        }

        AndroidTestRunConfiguration runConfiguration = (AndroidTestRunConfiguration) runProfile;
        AndroidFacet facet = AndroidFacet.getInstance(runConfiguration.getConfigurationModule().getModule());
        CloudTestMatrixTargetProvider.State cloudTargetState = (CloudTestMatrixTargetProvider.State) state;

        return new CloudMatrixTestRunningState(env, facet, runConfiguration, cloudTargetState.SELECTED_CLOUD_MATRIX_CONFIGURATION_ID,
                                               cloudTargetState.SELECTED_CLOUD_MATRIX_PROJECT_ID);
      }

      @Nullable
      @Override
      public DeviceFutures getDevices(@NotNull DeployTargetState state,
                                      @NotNull AndroidFacet facet,
                                      @NotNull DeviceCount count,
                                      boolean debug,
                                      int id) {
        // This runs when a developer debugs (not runs) an Android instrumented test. Use the device selected in the drop down.
        DeviceAndSnapshotComboBoxTargetProvider provider = new DeviceAndSnapshotComboBoxTargetProvider();
        Project project = facet.getModule().getProject();

        DeployTarget<DeviceAndSnapshotComboBoxTargetProvider.State> target =
          provider.requiresRuntimePrompt(project) ? provider.showPrompt(facet) : provider.getDeployTarget(project);

        if (target == null) {
          return null;
        }

        return target.getDevices(provider.createState(), facet, count, debug, id);
      }
    };
  }
}
