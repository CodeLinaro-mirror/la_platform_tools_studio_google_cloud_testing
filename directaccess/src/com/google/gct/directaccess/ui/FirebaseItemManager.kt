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

import com.android.annotations.concurrency.UiThread
import com.android.sdklib.deviceprovisioner.Disconnected
import com.android.tools.idea.devicemanager.DeviceType
import com.android.tools.idea.deviceprovisioner.DeviceProvisionerService
import com.google.gct.directaccess.FirebaseDevice
import com.google.gct.directaccess.provisioner.DirectAccessDeviceHandle
import com.google.gct.directaccess.provisioner.FirebaseDeviceTemplate
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import icons.StudioIcons
import javax.swing.Icon
import javax.swing.table.AbstractTableModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An item class representing either a template or a device. A template item has the action to start
 * a new session and connect the device. A device item has the action to cancel the session and
 * disconnect the device.
 */
@UiThread
interface FirebaseItem {
  /** Returns true if the main action is active. */
  val isActive: Boolean

  /** Starts the main action. */
  fun startAction()

  /** A callback that fires when the item has been changed. */
  val onUpdate: () -> Unit

  val apiLevel: Int

  /** Action icon associated with the item. */
  val icon: Icon

  val deviceType: DeviceType

  val tooltipText: String
}

class FirebaseDeviceItem(
  val device: FirebaseDevice,
  val handle: DirectAccessDeviceHandle,
  private val scope: CoroutineScope,
  private val uiDispatcher: CoroutineDispatcher,
  override val onUpdate: () -> Unit
) : FirebaseItem {
  override val apiLevel = device.androidVersion.apiLevel

  override val icon: Icon = StudioIcons.Avd.STOP
  override val tooltipText: String =
    if (isActive) "Disconnect this firebase device" else "Firebase device disconnecting"

  override val isActive: Boolean
    get() = handle.deactivationAction.isEnabled.value

  override val deviceType: DeviceType
    get() = device.type

  init {
    scope.launch { handle.stateFlow.collect { withContext(uiDispatcher) { onUpdate() } } }
  }

  override fun startAction() {
    scope.launch { handle.deactivationAction.deactivate() }
  }
}

class FirebaseDeviceTemplateItem(
  val template: FirebaseDeviceTemplate,
  private val scope: CoroutineScope,
  private val uiDispatcher: CoroutineDispatcher,
  override val onUpdate: () -> Unit
) : FirebaseItem {

  val activeItem: FirebaseItem
    get() = deviceItem ?: this

  private var deviceItem: FirebaseDeviceItem? = null

  override val isActive: Boolean
    get() = template.activationAction.isEnabled.value

  override val icon: Icon = StudioIcons.Avd.RUN

  override val tooltipText: String =
    if (isActive) "Connect to a new firebase device" else "Firebase device connecting"

  override val deviceType: DeviceType
    get() = template.info.type

  override fun startAction() {
    scope.launch { template.activationAction.activate() }
  }

  override val apiLevel = template.info.api

  suspend fun updateActiveItem() {
    withContext(uiDispatcher) {
      val oldDevice = deviceItem?.handle
      val newDevice = template.latestActivatingDevice.takeIf { it?.state !is Disconnected }
      if (newDevice != oldDevice) {
        deviceItem =
          newDevice?.let {
            FirebaseDeviceItem(FirebaseDevice(template.info), it, scope, uiDispatcher, onUpdate)
          }
        onUpdate()
      }
    }
  }
}

class FirebaseItemManager(
  val project: Project,
  private val model: AbstractTableModel,
  private val scope: CoroutineScope,
  private val uiDispatcher: CoroutineDispatcher
) {
  private var templateItems = emptyList<FirebaseDeviceTemplateItem>()

  fun getItem(index: Int) = templateItems[index].activeItem

  val itemCount
    get() = templateItems.size

  init {
    val provisionerPlugin = project.service<DeviceProvisionerService>().deviceProvisioner
    scope.launch {
      provisionerPlugin
        .templates
        .map { it.filterIsInstance<FirebaseDeviceTemplate>() }
        .distinctUntilChanged()
        .collect { newTemplates -> refreshTemplates(newTemplates) }
    }
    scope.launch {
      provisionerPlugin
        .devices
        .map { it.filterIsInstance<DirectAccessDeviceHandle>() }
        .distinctUntilChanged()
        .collect { templateItems.forEach { it.updateActiveItem() } }
    }
  }

  private suspend fun refreshTemplates(newTemplates: List<FirebaseDeviceTemplate>) {
    withContext(uiDispatcher) {
      val existingMap = templateItems.associateBy { it.template.info }
      templateItems =
        newTemplates.map { template ->
          existingMap[template.info]
            ?: FirebaseDeviceTemplateItem(template, scope, uiDispatcher) {
              val index = templateItems.indexOfFirst { item -> item.template == template }
              if (index != -1) {
                model.fireTableRowsUpdated(index, index)
              }
            }
        }
      model.fireTableDataChanged()
    }
  }
}
