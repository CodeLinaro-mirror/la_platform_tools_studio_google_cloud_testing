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
package com.google.gct.directaccess.ui

import com.android.tools.idea.devicemanager.IconButtonTableCellRenderer
import com.android.tools.idea.flags.StudioFlags
import com.intellij.icons.AllIcons
import java.awt.Component
import javax.swing.JTable

class FirebaseDeviceActionsColumnIconRenderer : IconButtonTableCellRenderer() {

  override fun getTableCellRendererComponent(
    table: JTable,
    value: Any,
    selected: Boolean,
    focused: Boolean,
    viewRowIndex: Int,
    viewColumnIndex: Int
  ): Component {
    super.getTableCellRendererComponent(
      table,
      value,
      selected,
      focused,
      viewRowIndex,
      viewColumnIndex
    )
    val item = (table as FirebaseDeviceTable).getItemAt(viewRowIndex)
    when (viewColumnIndex) {
      ACTIVATE_DEVICE_FILE_EXPLORER_WINDOW_MODEL_COLUMN_INDEX -> {
        myButton.icon = AllIcons.Actions.MenuOpen
        myButton.toolTipText =
          if (
            StudioFlags.MERGED_DEVICE_FILE_EXPLORER_AND_DEVICE_MONITOR_TOOL_WINDOW_ENABLED.get()
          ) {
            "Open this device in the Device Explorer."
          } else {
            "Open this device in the Device File Explorer."
          }
      }
      POP_UP_MENU_MODEL_COLUMN_INDEX -> {
        myButton.icon = AllIcons.Actions.More
        myButton.toolTipText = "More Actions"
      }
      else -> assert(false) { viewColumnIndex }
    }
    myButton.isEnabled = item.isOnline
    return myButton
  }
}
