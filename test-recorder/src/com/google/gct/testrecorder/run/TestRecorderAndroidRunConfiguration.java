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
import com.android.ddmlib.NullOutputReceiver;
import com.android.tools.idea.run.AndroidRunConfiguration;
import com.android.tools.idea.run.ApkProviderUtil;
import com.android.tools.idea.run.ApplicationIdProvider;
import com.android.tools.idea.run.ConsolePrinter;
import com.android.tools.idea.run.tasks.LaunchTask;
import com.android.tools.idea.run.util.LaunchStatus;
import com.google.gct.testrecorder.settings.TestRecorderSettings;
import com.intellij.openapi.diagnostic.Logger;
import org.jdom.Element;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;

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

  @Override
  public boolean supportsInstantRun() {
    return false;
  }

  @Nullable
  @Override
  protected LaunchTask getApplicationLaunchTask(@NotNull ApplicationIdProvider applicationIdProvider,
                                                @NotNull AndroidFacet facet,
                                                @NotNull String contributorsAmStartOptions,
                                                boolean waitForDebugger,
                                                @NotNull LaunchStatus launchStatus) {
    LaunchTask launchTask = super.getApplicationLaunchTask(applicationIdProvider, facet, contributorsAmStartOptions,
                                                           waitForDebugger, launchStatus);
    return launchTask == null ? null : new TestRecorderLaunchTask(launchTask, facet);
  }

  private static class TestRecorderLaunchTask implements LaunchTask {
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
    public boolean perform(@NotNull IDevice device, @NotNull LaunchStatus launchStatus, @NotNull ConsolePrinter printer) {
      if (TestRecorderSettings.getInstance().CLEAN_BEFORE_START) {
        try {
          // Clear the app data such that the test recording starts from the initial app state.
          String command = "pm clear " + ApkProviderUtil.computePackageName(myFacet);
          printer.stdout("$ adb shell " + command);
          device.executeShellCommand(command, new NullOutputReceiver(), 5, TimeUnit.SECONDS);
        } catch (Exception e) {
          // It is unfortunate that the command to clear the app data might have failed, but it is not a blocker, so proceed.
          LOGGER.warn("Exception clearing app data", e);
        }
      }

      return myDefaultLaunchTask.perform(device, launchStatus, printer);
    }
  }
}
