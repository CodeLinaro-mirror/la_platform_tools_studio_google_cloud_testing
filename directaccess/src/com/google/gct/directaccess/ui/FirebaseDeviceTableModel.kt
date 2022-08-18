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

import com.android.tools.idea.devicemanager.Device
import com.google.gct.directaccess.FirebaseDevice
import com.google.services.firebase.directaccess.client.DeviceInfo
import com.intellij.openapi.project.Project
import icons.StudioIcons
import javax.swing.Icon
import javax.swing.table.AbstractTableModel

const val DEVICE_MODEL_COLUMN_INDEX = 0
const val API_MODEL_COLUMN_INDEX = 1
const val ACTIONS_COLUMN_INDEX = 2

class FirebaseDeviceTableModel(devices: List<DeviceInfo>, val project: Project) :
  AbstractTableModel() {
  val devices = devices.map { FirebaseDevice(it) }.toMutableList()

  override fun getRowCount(): Int {
    return devices.size
  }

  override fun getColumnCount(): Int {
    return 3
  }

  override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
    return when (columnIndex) {
      DEVICE_MODEL_COLUMN_INDEX -> devices[rowIndex]
      API_MODEL_COLUMN_INDEX -> devices[rowIndex].androidVersion
      ACTIONS_COLUMN_INDEX -> StudioIcons.Avd.RUN
      else -> ""
    }
  }

  override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
    return columnIndex == ACTIONS_COLUMN_INDEX
  }

  override fun getColumnName(modelColumnIndex: Int): String {
    return when (modelColumnIndex) {
      DEVICE_MODEL_COLUMN_INDEX -> "Device"
      API_MODEL_COLUMN_INDEX -> "API"
      ACTIONS_COLUMN_INDEX -> "Actions"
      else -> ""
    }
  }

  override fun getColumnClass(columnIndex: Int): Class<*> {
    return when (columnIndex) {
      DEVICE_MODEL_COLUMN_INDEX -> Device::class.java
      ACTIONS_COLUMN_INDEX -> Icon::class.java
      else -> super.getColumnClass(columnIndex)
    }
  }
}
