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

import com.android.tools.idea.devicemanager.ActivateDeviceFileExplorerWindowValue
import com.android.tools.idea.devicemanager.DeviceType
import com.android.tools.idea.devicemanager.PopUpMenuValue
import com.intellij.openapi.project.Project
import javax.swing.table.AbstractTableModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

const val DEVICE_ICON_COLUMN_INDEX = 0
const val DEVICE_MODEL_COLUMN_INDEX = 1
const val API_MODEL_COLUMN_INDEX = 2
const val LAUNCH_OR_STOP_MODEL_COLUMN_INDEX = 3
const val ACTIVATE_DEVICE_FILE_EXPLORER_WINDOW_MODEL_COLUMN_INDEX = 4
const val POP_UP_MENU_MODEL_COLUMN_INDEX = 5

class FirebaseDeviceTableModel(
  val project: Project,
  scope: CoroutineScope,
  uiDispatcher: CoroutineDispatcher
) : AbstractTableModel() {
  private val itemManager = FirebaseItemManager(project, this, scope, uiDispatcher)

  override fun getRowCount(): Int {
    return itemManager.itemCount
  }

  override fun getColumnCount() = 6

  override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
    return when (columnIndex) {
      DEVICE_ICON_COLUMN_INDEX -> itemManager.getItem(rowIndex).deviceType
      DEVICE_MODEL_COLUMN_INDEX -> itemManager.getItem(rowIndex)
      API_MODEL_COLUMN_INDEX -> itemManager.getItem(rowIndex).apiLevel
      LAUNCH_OR_STOP_MODEL_COLUMN_INDEX -> itemManager.getItem(rowIndex).isActive
      ACTIVATE_DEVICE_FILE_EXPLORER_WINDOW_MODEL_COLUMN_INDEX ->
        ActivateDeviceFileExplorerWindowValue.INSTANCE
      POP_UP_MENU_MODEL_COLUMN_INDEX -> PopUpMenuValue.INSTANCE
      else -> ""
    }
  }

  override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
    return when (columnIndex) {
      DEVICE_ICON_COLUMN_INDEX,
      DEVICE_MODEL_COLUMN_INDEX,
      API_MODEL_COLUMN_INDEX -> false
      LAUNCH_OR_STOP_MODEL_COLUMN_INDEX -> itemManager.getItem(rowIndex).isActive
      ACTIVATE_DEVICE_FILE_EXPLORER_WINDOW_MODEL_COLUMN_INDEX,
      POP_UP_MENU_MODEL_COLUMN_INDEX -> itemManager.getItem(rowIndex).isOnline
      else -> false
    }
  }

  override fun getColumnName(modelColumnIndex: Int): String {
    return when (modelColumnIndex) {
      DEVICE_MODEL_COLUMN_INDEX -> "Device"
      API_MODEL_COLUMN_INDEX -> "API"
      else -> ""
    }
  }

  override fun getColumnClass(columnIndex: Int): Class<*> {
    return when (columnIndex) {
      DEVICE_ICON_COLUMN_INDEX -> DeviceType::class.java
      DEVICE_MODEL_COLUMN_INDEX -> FirebaseItem::class.java
      LAUNCH_OR_STOP_MODEL_COLUMN_INDEX -> Boolean::class.java
      ACTIVATE_DEVICE_FILE_EXPLORER_WINDOW_MODEL_COLUMN_INDEX ->
        ActivateDeviceFileExplorerWindowValue::class.java
      POP_UP_MENU_MODEL_COLUMN_INDEX -> PopUpMenuValue::class.java
      else -> super.getColumnClass(columnIndex)
    }
  }

  override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
    if (columnIndex == LAUNCH_OR_STOP_MODEL_COLUMN_INDEX) {
      val item = itemManager.getItem(rowIndex)
      if (item.isActive && value == false) {
        item.startAction()
      }
    }
  }
}
