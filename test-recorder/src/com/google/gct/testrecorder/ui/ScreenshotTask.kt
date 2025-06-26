/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.google.gct.testrecorder.ui

import com.android.tools.idea.ui.screenshot.ScreenshotImage
import com.android.tools.idea.ui.screenshot.ScreenshotProvider
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.ExceptionUtil
import kotlinx.coroutines.runBlocking

/** A task that captures a screenshot from a device. */
open class ScreenshotTask(
  project: Project,
  private val screenshotProvider: ScreenshotProvider,
) : Task.Modal(project, "Take Screenshot", true) {

  var screenshot: ScreenshotImage? = null
    private set
  var error: String? = null
    protected set

  override fun run(indicator: ProgressIndicator) {
    indicator.isIndeterminate = true
    indicator.text = "Obtaining screenshot from device\u2026"
    try {
      screenshot = runBlocking { screenshotProvider.captureScreenshot() }
    }
    catch (e: Exception) {
      error = ExceptionUtil.getMessage(e) ?: "Unexpected error while obtaining screenshot: ${e.javaClass}"
    }
    indicator.checkCanceled()
  }
}
