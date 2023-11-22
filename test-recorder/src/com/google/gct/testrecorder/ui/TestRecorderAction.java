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
package com.google.gct.testrecorder.ui;

import com.android.annotations.VisibleForTesting;
import com.android.ide.common.repository.GradleCoordinate;
import com.android.tools.analytics.UsageTracker;
import com.android.tools.analytics.UsageTrackerUtils;
import com.android.tools.idea.projectsystem.AndroidModuleSystem;
import com.android.tools.idea.projectsystem.ProjectSystemUtil;
import com.android.tools.idea.run.deployment.DeviceAndSnapshotComboBoxTargetProvider;
import com.google.common.collect.Lists;
import com.google.gct.testrecorder.run.TestRecorderInfo;
import com.google.gct.testrecorder.run.TestRecorderRunConfigurationProxy;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventCategory;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventKind;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunManagerEx;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.popup.list.ListPopupImpl;
import icons.StudioIcons;
import java.util.List;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;

public class TestRecorderAction extends AnAction {
  // TODO: This is a temporary workaround to deal with the launch schedule conflicts.
  // If this JVM option is present and set to true, enable Robo script recording.
  private static final String ENABLE_ROBO_SCRIPT_RECORDING_FLAG = "enable.robo.script.recording";

  private final static String RECORD_TEST_ACTION_TEXT = "Record Espresso Test";
  public static final Icon TEST_RECORDER_ICON = StudioIcons.Test.RECORD_ESPRESSO_TEST;
  public static final Icon SCRIPT_RECORDER_ICON = IconLoader.getIcon("robo_dot.png", TestRecorderAction.class);
  public static final Key<TestRecorderInfo> KEY = Key.create("test.recorder.launch");

  @NotNull
  @Override
  public ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }


  @Override
  public void update(final AnActionEvent event) {
    final Presentation presentation = event.getPresentation();
    if (isRecordingTestAction(presentation)) {
      presentation.setIcon(TEST_RECORDER_ICON);
    } else {
      presentation.setIcon(SCRIPT_RECORDER_ICON);
      // TODO: A temporary hack to hide Robo script recording option unless the local flag is specified.
      presentation.setVisible(Boolean.getBoolean(ENABLE_ROBO_SCRIPT_RECORDING_FLAG));
    }

    final Project project = event.getProject();

    if (project == null || !project.isInitialized() || project.isDisposed() || DumbService.getInstance(project).isDumb()) {
      presentation.setEnabled(false);
      return;
    }

    // Disable Espresso Test Recorder if multiple target devices are selected.
    DeviceAndSnapshotComboBoxTargetProvider targetProvider = DeviceAndSnapshotComboBoxTargetProvider.getInstance();
    if (targetProvider.getNumberOfSelectedDevices(project) > 1) {
      presentation.setEnabled(false);
      return;
    }

    // Disable Espresso Test Recorder for Compose projects, since Espresso Testing Framework does not support Compose.
    TestRecorderRunConfigurationProxy testRecorderConfigurationProxy = TestRecorderRunConfigurationProxy.getInstance(getSuitableRunConfigurations(project).get(0));
    if (ProjectSystemUtil.getModuleSystem(testRecorderConfigurationProxy.getModule()).getUsesCompose()) {
      presentation.setEnabled(false);
      return;
    }

    presentation.setEnabled(true);
  }

  @Override
  public void actionPerformed(AnActionEvent event) {

    final Project project = event.getProject();
    if (project == null || project.isDisposed()) {
      return;
    }
    UsageTracker.log(UsageTrackerUtils.withProjectId(
      AndroidStudioEvent.newBuilder()
       .setCategory(EventCategory.TEST_RECORDER)
       .setKind(EventKind.TEST_RECORDER_LAUNCH),
      project));

    launchTestRecorder(project, isRecordingTestAction(event.getPresentation()));
  }

  public static void launchTestRecorder(Project project, boolean isRecordingTest) {
    List<RunConfiguration> suitableRunConfigurations = getSuitableRunConfigurations(project);
    if (suitableRunConfigurations.isEmpty()) {
      String message = "Please create an Android Application or Blaze Command Run configuration with a valid module and Default or Specified launch activity.";
      Messages.showDialog(project, message, "No suitable run configuration found", new String[]{"OK"}, 0, null);
      return;
    }

    if (suitableRunConfigurations.size() == 1) {
      // If only one configuration is suitable, use it.
      launchTestRecorderOnConfiguration(project, suitableRunConfigurations.get(0), isRecordingTest);
    } else {
      RunnerAndConfigurationSettings selectedConfiguration = RunManagerEx.getInstanceEx(project).getSelectedConfiguration();
      if (selectedConfiguration != null && suitableRunConfigurations.contains(selectedConfiguration.getConfiguration())) {
        // If currently selected configuration is suitable, use it.
        launchTestRecorderOnConfiguration(project, selectedConfiguration.getConfiguration(), isRecordingTest);
      } else {
        // If there is more than one possible choice, ask the user to pick a configuration.
        ListPopupImpl configurationPickerPopup = new ListPopupImpl(
          new BaseListPopupStep<RunConfiguration>("Pick configuration to launch", suitableRunConfigurations) {
            @NotNull
            @Override
            public String getTextFor(RunConfiguration runConfiguration) {
              return runConfiguration.getName();
            }

            @Override
            public PopupStep onChosen(RunConfiguration runConfiguration, boolean finalChoice) {
              return doFinalStep(() -> launchTestRecorderOnConfiguration(project, runConfiguration, isRecordingTest));
            }
          });

        configurationPickerPopup.showCenteredInCurrentWindow(project);
      }
    }
  }

  private static void launchTestRecorderOnConfiguration(Project project, RunConfiguration configurationBase, boolean isRecordingTest) {
    try {
      attemptLaunchTestRecorderOnConfiguration(project, configurationBase, isRecordingTest);
    }
    catch (ExecutionException e) {
      String message = StringUtil.isEmpty(e.getMessage()) ? "Unknown error" : e.getMessage();
      Messages.showDialog(project, message, "Could not launch Espresso Test Recorder", new String[]{"OK"}, 0, null);
    }
  }

  private static void attemptLaunchTestRecorderOnConfiguration(Project project, RunConfiguration configurationBase, boolean isRecordingTest)
    throws ExecutionException {
    TestRecorderRunConfigurationProxy testRecorderConfigurationProxy = TestRecorderRunConfigurationProxy.getInstance(configurationBase);
    if (testRecorderConfigurationProxy == null) {
      throw new RuntimeException("Could not obtain an instance of TestRecorderRunConfigurationProxy");
    }

    // Do not launch Espresso Test Recorder for projects that include native C code because they are not fully supported.
    if (testRecorderConfigurationProxy.isNativeProject()) {
      String message = "Espresso Test Recorder does not support projects with native C code.";
      Messages.showDialog(project, message, "Espresso test cannot be recorded", new String[]{"OK"}, 0, null);
      return;
    }

    RunnerAndConfigurationSettings settings = RunManager.getInstance(project).findSettings(configurationBase);

    if (settings == null) {
      throw new RuntimeException("Could not find runner and configuration settings");
    }

    ExecutionEnvironmentBuilder builder =
      ExecutionEnvironmentBuilder.createOrNull(DefaultDebugExecutor.getDebugExecutorInstance(), settings);
    if (builder == null) {
      throw new RuntimeException("Could not create execution environment builder");
    }

    ExecutionEnvironment environment = builder.build();

    environment.putCopyableUserData(KEY, new TestRecorderInfo(isRecordingTest));

    ProgramRunnerUtil.executeConfiguration(environment, false, true);
  }

  @VisibleForTesting
  public static List<RunConfiguration> getSuitableRunConfigurations(Project project) {
    List<RunConfiguration> suitableRunConfigurations = Lists.newLinkedList();

    for (RunConfiguration runConfiguration : RunManagerEx.getInstanceEx(project).getAllConfigurationsList()) {
      TestRecorderRunConfigurationProxy runConfigurationProxy = TestRecorderRunConfigurationProxy.getInstance(runConfiguration);
      if (runConfigurationProxy != null && runConfigurationProxy.getModule() != null && runConfigurationProxy.isLaunchActivitySupported()) {
        suitableRunConfigurations.add(runConfiguration);
      }
    }

    return suitableRunConfigurations;
  }

  private boolean isRecordingTestAction(Presentation presentation) {
    return RECORD_TEST_ACTION_TEXT.equals(presentation.getText());
  }

}
