/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.google.gct.directaccess.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class DirectAccessSettingsPage : SearchableConfigurable, Configurable.NoScroll {
  private lateinit var isDeviceStreamingEnabledCheckBox: JBCheckBox

  private val state = service<DirectAccessConfiguration>().state

  override fun createComponent(): JComponent = panel {
    row {
      isDeviceStreamingEnabledCheckBox =
        checkBox("Enable Device Streaming")
          .comment(
            "Enable only if you are enrolled in the Device Streaming Alpha program. " +
              "<a href=\"https://services.google.com/fb/forms/androiddevicestreaming\">Click here</a>" +
              " to sign up"
          )
          .bindSelected(state::isEnabled)
          .component
    }
  }

  override fun isModified(): Boolean =
    state.isEnabled != isDeviceStreamingEnabledCheckBox.isSelected

  override fun apply() {
    val app = ApplicationManager.getApplication()
    if (app.isUnitTestMode) {
      state.isEnabled = isDeviceStreamingEnabledCheckBox.isSelected
      return
    }

    val okText = if (app.isRestartCapable) "Restart" else "Exit"
    val message =
      "A restart of Android Studio is required to apply changes related to Device Streaming.\n\n" +
        "Do you want to proceed?"
    val result: Int =
      Messages.showOkCancelDialog(message, "Restart", okText, "Cancel", Messages.getQuestionIcon())

    if (result == Messages.OK) {
      state.isEnabled = isDeviceStreamingEnabledCheckBox.isSelected
      app.exit(false, true, true)
    }
  }

  override fun reset() {
    isDeviceStreamingEnabledCheckBox.isSelected = state.isEnabled
  }

  override fun getDisplayName(): String = "Device Streaming"

  override fun getId(): String = "device.streaming.options"
}
