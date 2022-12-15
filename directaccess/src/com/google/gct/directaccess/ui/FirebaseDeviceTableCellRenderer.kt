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

import com.android.sdklib.deviceprovisioner.Connected
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.tools.idea.concurrency.AndroidDispatchers
import com.android.tools.idea.devicemanager.DeviceTableCellRenderer
import com.google.gct.directaccess.FirebaseDevice
import com.intellij.ui.AnimatedIcon
import icons.StudioIcons
import javax.swing.Icon
import javax.swing.JTable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FirebaseDeviceTableCellRenderer :
  DeviceTableCellRenderer<FirebaseDevice>(FirebaseDevice::class.java) {

  private val spinner = AnimatedIcon.Default()
  override fun getStateIcon(device: FirebaseDevice): Icon? =
    when {
      device.state.isTransitioning -> spinner
      device.state is Connected -> StudioIcons.Avd.STATUS_DECORATOR_ONLINE
      else -> null
    }

  fun startRepainterIfNeeded(value: FirebaseDeviceItem, table: JTable, row: Int, column: Int) {
    val runningSpinners =
      (table.getClientProperty("SpinnerHandles") as? MutableSet<DeviceHandle>)
        ?: mutableSetOf<DeviceHandle>().also { table.putClientProperty("SpinnerHandles", it) }
    val handle = value.handle
    if (!handle.state.isTransitioning || runningSpinners.contains(handle)) return
    runningSpinners.add(handle)
    value.scope.launch(AndroidDispatchers.uiThread) {
      while (true) {
        delay(100)
        if (!handle.state.isTransitioning) {
          runningSpinners.remove(handle)
          return@launch
        }
        table.repaint(table.getCellRect(row, column, false))
      }
    }
  }
}
