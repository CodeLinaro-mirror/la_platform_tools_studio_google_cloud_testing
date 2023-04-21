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

import com.android.ddmlib.IDevice;
import com.android.tools.idea.projectsystem.ProjectSystemUtil;
import com.android.tools.idea.run.AndroidRunConfiguration;
import com.android.tools.idea.run.ApkProvider;
import com.android.tools.idea.run.ApkProvisionException;
import com.android.tools.idea.run.configuration.execution.ExecutionUtils;
import com.android.tools.idea.run.tasks.AppLaunchTask;
import com.android.tools.idea.run.tasks.LaunchContext;
import com.android.tools.idea.run.tasks.LaunchTask;
import com.google.gct.testrecorder.settings.TestRecorderSettings;
import com.intellij.execution.ExecutionException;
import com.intellij.openapi.diagnostic.Logger;
import org.jdom.Element;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class TestRecorderAndroidRunConfiguration extends AndroidRunConfiguration {
  private static final Logger LOGGER = Logger.getInstance(TestRecorderAndroidRunConfiguration.class);


  public TestRecorderAndroidRunConfiguration(AndroidRunConfiguration baseConfiguration) {
    super(baseConfiguration.getProject(), baseConfiguration.getFactory());
    setName("TestRecorder" + baseConfiguration.getName());
    Element element = new Element(TO_CLONE_ELEMENT_NAME);
    try {
      baseConfiguration.writeExternal(element);
      this.readExternal(element);
    } catch (Exception e) {
      LOGGER.error(e);
    }

    // Set before run tasks explicitly as they are not written out externally.
    this.setBeforeRunTasks(baseConfiguration.getBeforeRunTasks());
  }

  @Nullable
  @Override
  public AppLaunchTask getApplicationLaunchTask(@NotNull String packageName,
                                                @NotNull AndroidFacet facet,
                                                @NotNull String contributorsAmStartOptions,
                                                boolean waitForDebugger,
                                                @NotNull ApkProvider apkProvider,
                                                @NotNull IDevice device) throws ExecutionException {
    LaunchTask launchTask = super.getApplicationLaunchTask(packageName, facet, contributorsAmStartOptions,
                                                           waitForDebugger, apkProvider, device);
    return launchTask == null ? null : new TestRecorderLaunchTask(launchTask, facet);
  }

  private static class TestRecorderLaunchTask extends AppLaunchTask {
    private static final String ID = "TEST_RECORDER";

    private final LaunchTask myDefaultLaunchTask;
    private final AndroidFacet myFacet;

    TestRecorderLaunchTask(@NotNull LaunchTask defaultLaunchTask, AndroidFacet facet) {
      myDefaultLaunchTask = defaultLaunchTask;
      myFacet = facet;
    }

    @NotNull
    @Override
    public String getDescription() {
      return myDefaultLaunchTask.getDescription();
    }

    @Override
    public int getDuration() {
      return myDefaultLaunchTask.getDuration();
    }

    @Override
    public void run(@NotNull LaunchContext launchContext) throws ExecutionException {
      if (TestRecorderSettings.getInstance().CLEAN_BEFORE_START) {
        String command;
        try {
          command = "pm clear " + ProjectSystemUtil.getModuleSystem(myFacet).getApplicationIdProvider().getPackageName();
        }
        catch (ApkProvisionException e) {
          throw new ExecutionException(e);
        }
        ExecutionUtils.executeShellCommand(launchContext.getDevice(), command, launchContext.getConsoleView(),
                                           launchContext.getProgressIndicator());
      }
      myDefaultLaunchTask.run(launchContext);
    }

    @NotNull
    @Override
    public String getId() {
      return ID;
    }
  }
}
