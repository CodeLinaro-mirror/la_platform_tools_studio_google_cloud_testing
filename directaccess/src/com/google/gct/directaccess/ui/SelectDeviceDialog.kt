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

import com.android.tools.adtui.categorytable.CategoryTable
import com.android.tools.idea.concurrency.AndroidDispatchers
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.directAccessCloudProjectManager
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.gct.directaccess.provisioner.DeviceSelection
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.util.maximumHeight
import com.intellij.ui.util.preferredHeight
import com.intellij.ui.util.preferredWidth
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import kotlinx.coroutines.flow.update
import org.jetbrains.annotations.VisibleForTesting

class SelectDeviceDialog(private val project: Project) : DialogWrapper(false) {

  @VisibleForTesting
  val deviceTable =
    CategoryTable(
      SelectDeviceTableColumns.columns,
      coroutineDispatcher = AndroidDispatchers.uiThread,
    )

  private val searchTextField =
    SearchTextField().apply {
      // If the table is empty and the user decides to resize the dialog,
      // searchTextField gets resized. Set max height to preferred height to avoid that.
      maximumHeight = preferredHeight
      addDocumentListener(
        object : DocumentAdapter() {
          override fun textChanged(e: DocumentEvent) {
            updateDeviceRowDataList()
            deviceTable.values.forEach { deviceTable.removeRow(it) }
            deviceRowDataList.forEach { deviceTable.addOrUpdateRow(it) }
          }
        }
      )
    }

  private val searchText: String
    get() = searchTextField.text

  private var deviceRowDataList: List<SelectDeviceRowData> = emptyList()

  private fun updateDeviceRowDataList() {
    val accessibleDeviceInfoSet =
      project.directAccessCloudProjectManager
        ?.accessibleDeviceInfoListFlow
        ?.stateFlow
        ?.value
        ?.toSet() ?: setOf()
    deviceRowDataList =
      project
        .service<DirectAccessService>()
        .deviceSelectionListFlow
        .value
        .filter {
          it.applySearchFilter() && (it.isSelected || it.deviceInfo in accessibleDeviceInfoSet)
        }
        .map { SelectDeviceRowData(it.isSelected, it.deviceInfo) }
        .sortedBy { it.deviceInfo.title }
  }

  init {
    title = "Select Devices"
    updateDeviceRowDataList()
    init()
  }

  private fun DeviceSelection.applySearchFilter(): Boolean {
    if (searchText.isEmpty()) return true
    val words = searchText.split(Regex(" +"))
    return words.all {
      with(deviceInfo) {
        // Check in title string in place of checking separately in manufacturer and name for cases
        // where user types "Google Pixel"
        title.contains(it, true) ||
          // Match exact for numeric columns.
          api.toString() == it ||
          screenX.toString() == it ||
          screenY.toString() == it ||
          screenDensity.toString() == it
      }
    }
  }

  override fun createCenterPanel(): JComponent {
    if (deviceRowDataList.isEmpty()) {
      return JLabel("No devices to select").apply { preferredWidth = deviceTable.preferredWidth }
    }
    return JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      add(searchTextField)
      deviceRowDataList.forEach { deviceTable.addOrUpdateRow(it) }
      add(JBScrollPane().apply { deviceTable.addToScrollPane(this) })
      searchTextField.preferredWidth = preferredWidth
      // Show a smaller window for an appropriate size of dialog.
      // Showing all devices causes the dialog to be very tall.
      preferredHeight =
        deviceRowDataList.size.coerceIn(1, 13) *
          deviceTable.preferredHeight.div(deviceRowDataList.size)
    }
  }

  override fun doOKAction() {
    super.doOKAction()
    val selectedDeviceInfoSet =
      deviceRowDataList.filter { it.isSelected }.map { it.deviceInfo }.toSet()
    project.service<DirectAccessService>().deviceSelectionListFlow.update {
      it.map { deviceSelection ->
        DeviceSelection(
          deviceSelection.deviceInfo in selectedDeviceInfoSet,
          deviceSelection.deviceInfo,
        )
      }
    }
  }

  private val DeviceInfo.title: String
    get() = "$manufacturer $name"
}
