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
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.tools.idea.concurrency.AndroidDispatchers
import com.android.tools.idea.devicemanager.DeviceType
import com.android.tools.idea.deviceprovisioner.DeviceProvisionerService
import com.android.tools.idea.flags.StudioFlags
import com.google.gct.directaccess.FirebaseDevice
import com.google.gct.directaccess.provisioner.DirectAccessDeviceHandle
import com.google.gct.directaccess.provisioner.DirectAccessDeviceTemplate
import com.google.gct.directaccess.provisioner.isClosed
import com.google.services.firebase.directaccess.client.DirectAccessConnection.ConnectionState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import icons.StudioIcons
import javax.swing.Icon
import javax.swing.table.AbstractTableModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
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

  /** Returns true if the device is online. */
  val isOnline: Boolean
}

class FirebaseDeviceItem(
  private val itemManager: FirebaseItemManager,
  val device: FirebaseDevice,
  val handle: DirectAccessDeviceHandle,
  val scope: CoroutineScope,
  private val uiDispatcher: CoroutineDispatcher,
  override val onUpdate: () -> Unit
) : FirebaseItem {
  override val apiLevel = device.androidVersion.apiLevel

  override val icon: Icon
    get() =
      if (handle.activationAction.isEnabled.value) StudioIcons.Avd.RUN else StudioIcons.Avd.STOP
  override val tooltipText: String
    get() =
      when {
        handle.activationAction.isEnabled.value -> "Connect to a firebase device"
        isActive -> "Disconnect this firebase device"
        else -> "Firebase device disconnecting"
      }

  override val isActive: Boolean
    get() = handle.connection.state.value.connection != ConnectionState.DISCONNECTING

  override val deviceType: DeviceType
    get() = device.type

  override val isOnline: Boolean
    get() = device.isOnline

  init {
    scope.launch { handle.connection.state.collect { withContext(uiDispatcher) { onUpdate() } } }
  }

  override fun startAction() {
    scope.launch {
      when (handle.connection.state.value.connection) {
        ConnectionState.DISCONNECTED -> {
          if (itemManager.resetAllOtherDevices(handle)) handle.activationAction.activate()
        }
        ConnectionState.CONNECTING,
        ConnectionState.CONNECTED -> handle.deactivationAction.deactivate()
        ConnectionState.DISCONNECTING -> {}
      }
    }
  }
}

class FirebaseDeviceTemplateItem(
  private val itemManager: FirebaseItemManager,
  val template: DirectAccessDeviceTemplate,
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

  override val tooltipText: String = "Connect to a firebase device"

  override val deviceType: DeviceType
    get() = template.deviceInfo.type

  // Template item can never be online.
  override val isOnline = false

  override fun startAction() {
    scope.launch {
      if (itemManager.resetAllOtherDevices()) {
        template.activationAction.activate()
        template.activeDevice?.activationAction?.activate()
      }
    }
  }

  override val apiLevel = template.deviceInfo.api

  suspend fun updateActiveItem() {
    withContext(uiDispatcher) {
      val oldDevice = deviceItem?.handle
      val newDevice = template.activeDevice
      if (newDevice != oldDevice) {
        newDevice?.let { newDeviceHandle ->
          scope.launch {
            newDeviceHandle.connection.state
              .combine(newDeviceHandle.stateFlow) { remoteState, deviceState ->
                if (deviceState.reservation?.state?.isClosed() == true) {
                  deviceItem = null
                  coroutineContext.cancel()
                } else {
                  deviceItem =
                    FirebaseDeviceItem(
                      itemManager,
                      FirebaseDevice(template.deviceInfo, remoteState, deviceState),
                      newDeviceHandle,
                      scope,
                      uiDispatcher,
                      onUpdate
                    )
                }
                onUpdate()
              }
              .collect()
          }
        }
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

  private val provisionerPlugin = project.service<DeviceProvisionerService>().deviceProvisioner

  init {
    scope.launch {
      provisionerPlugin.templates
        .map { it.filterIsInstance<DirectAccessDeviceTemplate>() }
        .distinctUntilChanged()
        .collect { newTemplates -> refreshTemplates(newTemplates) }
    }
    scope.launch {
      provisionerPlugin.devices
        .map { it.filterIsInstance<DirectAccessDeviceHandle>() }
        .distinctUntilChanged()
        .collect { templateItems.forEach { it.updateActiveItem() } }
    }
  }

  private suspend fun refreshTemplates(newTemplates: List<DirectAccessDeviceTemplate>) {
    withContext(uiDispatcher) {
      val existingMap = templateItems.associateBy { it.template.deviceInfo }
      templateItems =
        newTemplates.map { template ->
          existingMap[template.deviceInfo]
            ?: FirebaseDeviceTemplateItem(this@FirebaseItemManager, template, scope, uiDispatcher) {
                val index = templateItems.indexOfFirst { item -> item.template == template }
                if (index != -1) {
                  model.fireTableRowsUpdated(index, index)
                }
              }
              .also { it.updateActiveItem() }
        }
      model.fireTableDataChanged()
    }
  }

  suspend fun resetAllOtherDevices(device: DeviceHandle? = null): Boolean {
    if (StudioFlags.DIRECT_ACCESS_MULTIPLE_DEVICES.get()) {
      return true
    }
    provisionerPlugin.devices.value
      .filterIsInstance<DirectAccessDeviceHandle>()
      .filter { it != device }
      .forEach {
        val isConfirmed =
          withContext(AndroidDispatchers.uiThread) {
            MessageDialogBuilder.okCancel(
                "Confirm Device check-in",
                "${it.state.properties.title} will be disconnected and checked-in " +
                  "before connecting to a new device. All user data will be wiped."
              )
              .ask(project)
          }
        if (isConfirmed) {
          it.deactivationAction.deactivate()
        } else {
          return false
        }
      }
    return true
  }
}
