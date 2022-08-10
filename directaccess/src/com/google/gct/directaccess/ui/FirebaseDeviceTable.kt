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

import com.android.tools.idea.concurrency.executeOnPooledThread
import com.android.tools.idea.devicemanager.Device
import com.android.tools.idea.devicemanager.DeviceTable
import com.android.tools.idea.devicemanager.IconButtonTableCellEditor
import com.android.tools.idea.flags.StudioFlags
import com.google.gct.directaccess.FirebaseDevice
import com.google.services.firebase.directaccess.client.device.directaccess.testConnectDirectAccess
import com.intellij.openapi.Disposable
import com.intellij.util.concurrency.AppExecutorUtil
import icons.StudioIcons
import java.awt.Component
import javax.swing.Icon
import javax.swing.JTable

class FirebaseDeviceTable(model: FirebaseDeviceTableModel) :
  DeviceTable<FirebaseDevice>(model, FirebaseDevice::class.java), Disposable {

  init {
    setDefaultRenderer(Device::class.java, FirebaseDeviceTableCellRenderer())
    setDefaultEditor(Icon::class.java, LaunchButtonTableCellEditor(model))
  }

  override fun deviceViewColumnIndex() = convertColumnIndexToView(DEVICE_MODEL_COLUMN_INDEX)

  override fun dispose() {}
}

// TODO: replace this with better UI
class LaunchButtonTableCellEditor(model: FirebaseDeviceTableModel) :
  IconButtonTableCellEditor(StudioIcons.Avd.RUN, StudioIcons.Avd.RUN, "Launch") {

  private var row = 0

  init {
    myButton.addActionListener {
      executeOnPooledThread {
        testConnectDirectAccess(
          AppExecutorUtil.getAppExecutorService(),
          model.devices[row].target.substringAfter(' '),
          model.devices[row].androidVersion.apiString,
          StudioFlags.DIRECT_ACCESS_PROJECT.get()
        )
      }
    }
  }

  override fun getTableCellEditorComponent(
    table: JTable,
    value: Any,
    selected: Boolean,
    viewRowIndex: Int,
    viewColumnIndex: Int
  ): Component {
    super.getTableCellEditorComponent(table, value, selected, viewRowIndex, viewColumnIndex)
    row = viewRowIndex
    return myButton
  }
}
