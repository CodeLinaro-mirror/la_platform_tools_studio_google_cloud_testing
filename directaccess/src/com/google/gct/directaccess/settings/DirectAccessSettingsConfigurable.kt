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

import com.android.tools.idea.flags.ExperimentalConfigurable
import com.android.tools.idea.flags.ExperimentalConfigurable.ApplyState
import com.intellij.openapi.components.service
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class DirectAccessSettingsConfigurable : ExperimentalConfigurable {
  private var isDeviceStreamingEnabledCheckBox: JBCheckBox? = null

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
    state.isEnabled != isDeviceStreamingEnabledCheckBox?.isSelected

  override fun preApplyCallback(): ApplyState {
    return when {
      isModified() -> ApplyState.RESTART
      else -> ApplyState.OK
    }
  }

  override fun apply() {
    isDeviceStreamingEnabledCheckBox?.let { state.isEnabled = it.isSelected }
  }

  override fun reset() {
    isDeviceStreamingEnabledCheckBox?.isSelected = state.isEnabled
  }
}
