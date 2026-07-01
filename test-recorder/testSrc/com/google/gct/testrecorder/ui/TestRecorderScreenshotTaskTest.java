/*
 * Copyright (C) 2026 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.ddmlib.IDevice;
import com.android.tools.idea.adblib.AdbLibService;
import com.android.tools.idea.ui.screenshot.ScreenshotImage;
import com.android.tools.idea.ui.screenshot.ScreenshotProvider;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import java.lang.reflect.Field;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TestRecorderScreenshotTaskTest {

  @Test
  public void testPathHardeningAndCleanup() throws Exception {
    Project project = mock(Project.class);
    AdbLibService adbLibService = mock(AdbLibService.class);
    when(project.getService(AdbLibService.class)).thenReturn(adbLibService);

    IDevice device = mock(IDevice.class);
    when(device.getSerialNumber()).thenReturn("12345678");
    ScreenshotCallback callback = mock(ScreenshotCallback.class);

    TestRecorderScreenshotTask task = new TestRecorderScreenshotTask(project, device, "com.example.app", callback);

    // Bypass/mock the device screenshot extraction using a secured/stubbed ScreenshotProvider
    ScreenshotProvider mockProvider = new ScreenshotProvider() {
      @Override
      public Object captureScreenshot(@NotNull Continuation<? super ScreenshotImage> continuation) {
        return mock(ScreenshotImage.class);
      }

      @Override
      public void dispose() {
      }
    };

    // Inject our mock provider into the parent class using reflection
    Field providerField = ScreenshotTask.class.getDeclaredField("screenshotProvider");
    providerField.setAccessible(true);
    Disposable originalProvider = (Disposable) providerField.get(task);
    providerField.set(task, mockProvider);
    if (originalProvider != null) {
      Disposer.dispose(originalProvider);
    }

    task.run(new EmptyProgressIndicator());

    // Verify that uiautomator dump command targets /data/local/tmp and a randomized file, and not /sdcard
    verify(device, atLeastOnce()).executeShellCommand(
        argThat(cmd -> cmd.contains("uiautomator dump /data/local/tmp/testrecorder_ui_hierarchy_")),
        any(),
        anyLong(),
        any()
    );

    // Verify that the remote file is cleaned up via rm -f
    verify(device, atLeastOnce()).executeShellCommand(
        argThat(cmd -> cmd.contains("rm -f /data/local/tmp/testrecorder_ui_hierarchy_")),
        any(),
        anyLong(),
        any()
    );
  }
}
