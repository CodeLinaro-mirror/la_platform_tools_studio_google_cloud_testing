/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.tools.idea.devicemanager.IconButtonTableCellEditor
import java.awt.Component
import javax.swing.JTable

object LaunchOrStopButtonTableCellEditor : IconButtonTableCellEditor() {
  init {
    myButton.addActionListener {
      myValue = false
      fireEditingStopped()
    }
  }

  override fun getTableCellEditorComponent(
    table: JTable,
    value: Any,
    selected: Boolean,
    viewRowIndex: Int,
    viewColumnIndex: Int
  ): Component {
    val item = (table as FirebaseDeviceTable).getItemAt(viewRowIndex)

    myButton.setDefaultIcon(item.icon)
    return super.getTableCellEditorComponent(table, value, selected, viewRowIndex, viewColumnIndex)
  }
}
