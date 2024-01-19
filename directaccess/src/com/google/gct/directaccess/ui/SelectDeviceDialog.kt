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
import com.google.gct.directaccess.provisioner.DeviceSelection
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import javax.swing.JComponent
import kotlinx.coroutines.flow.update
import org.jetbrains.annotations.VisibleForTesting

class SelectDeviceDialog(private val project: Project) : DialogWrapper(false) {

  @VisibleForTesting
  val deviceTable =
    CategoryTable(
      SelectDeviceTableColumns.columns,
      coroutineDispatcher = AndroidDispatchers.uiThread,
      rowDataProvider = { _, device -> device },
    )

  private val deviceRowDataList: List<SelectDeviceRowData> = run {
    val accessibleDeviceInfoSet =
      project.directAccessCloudProjectManager
        ?.accessibleDeviceInfoListFlow
        ?.stateFlow
        ?.value
        ?.toSet() ?: listOf()
    project
      .service<DirectAccessService>()
      .deviceSelectionListFlow
      .value
      .filter { it.isSelected || it.deviceInfo in accessibleDeviceInfoSet }
      .map { SelectDeviceRowData(it.isSelected, it.deviceInfo) }
  }

  init {
    title = "Select Devices"
    init()
  }

  override fun createCenterPanel(): JComponent {
    deviceRowDataList.forEach { deviceTable.addOrUpdateRow(it) }
    deviceTable.categoryIndent = 0
    val scrollPane = JBScrollPane()
    deviceTable.addToScrollPane(scrollPane)
    return scrollPane
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
}
