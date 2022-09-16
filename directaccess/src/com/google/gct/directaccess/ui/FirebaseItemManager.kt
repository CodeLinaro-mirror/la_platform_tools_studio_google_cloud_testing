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

import com.android.adblib.scope
import com.android.annotations.concurrency.UiThread
import com.android.tools.idea.concurrency.createChildScope
import com.android.tools.idea.deviceprovisioner.DeviceProvisionerService
import com.google.gct.directaccess.FirebaseDevice
import com.google.gct.directaccess.provisioner.DirectAccessDeviceHandle
import com.google.gct.directaccess.provisioner.FirebaseDeviceTemplate
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
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

  val icon: Icon

  val tooltipText: String
}

class FirebaseDeviceItem(
  val device: FirebaseDevice,
  private val handle: DirectAccessDeviceHandle,
  private val uiDispatcher: CoroutineDispatcher,
  parent: Disposable,
  override val onUpdate: () -> Unit
) : FirebaseItem, Disposable {

  override val apiLevel = device.androidVersion.apiLevel

  override val icon: Icon = StudioIcons.Avd.STOP
  override val tooltipText: String =
    if (isActive) "Disconnect this firebase device" else "Firebase device disconnecting"

  override val isActive: Boolean
    get() = handle.deactivationAction.isEnabled.value

  private val scope = handle.stateFlow.value.connectedDevice?.scope

  override fun startAction() {
    // We do not need to set isDeactivating back to false
    // because the device will be removed from the model after getting deactivated.
    scope?.launch { handle.deactivationAction.deactivate() }
  }

  init {
    Disposer.register(parent, this)
    scope?.launch {
      handle.deactivationAction.isEnabled.collect { withContext(uiDispatcher) { onUpdate() } }
    }
  }

  override fun dispose() = Unit
}

class FirebaseDeviceTemplateItem(
  val template: FirebaseDeviceTemplate,
  parentScope: CoroutineScope,
  private val uiDispatcher: CoroutineDispatcher,
  parent: Disposable,
  override val onUpdate: () -> Unit
) : FirebaseItem, Disposable {
  var activeItem: FirebaseItem = this
    private set

  override val isActive: Boolean
    get() = template.activationAction.isEnabled.value

  override val icon: Icon = StudioIcons.Avd.RUN
  override val tooltipText: String =
    if (isActive) "Connect to a new firebase device" else "Firebase device connecting"

  private val scope = parentScope.createChildScope(isSupervisor = true, parentDisposable = this)

  override fun startAction() {
    scope.launch { template.activationAction.activate() }
  }

  override val apiLevel = template.apiLevel

  init {
    Disposer.register(parent, this)
    scope.launch {
      template.activationAction.isEnabled.collect { withContext(uiDispatcher) { onUpdate() } }
    }

    scope.launch {
      template.claimedDevices.distinctUntilChanged().collect { devices ->
        withContext(uiDispatcher) {
          activeItem =
            if (devices.isEmpty()) {
              (activeItem as? FirebaseDeviceItem)?.let { Disposer.dispose(it) }
              this@FirebaseDeviceTemplateItem
            } else {
              // TODO (b/246171065): activating multiple devices
              assert(devices.size == 1)
              FirebaseDeviceItem(
                FirebaseDevice(devices[0]),
                devices[0],
                uiDispatcher,
                this@FirebaseDeviceTemplateItem,
                onUpdate
              )
            }
          onUpdate()
        }
      }
    }
  }

  override fun dispose() = Unit
}

class FirebaseItemManager(
  val project: Project,
  private val model: AbstractTableModel,
  private val scope: CoroutineScope,
  uiDispatcher: CoroutineDispatcher,
  parent: Disposable
) {
  private var templateItems: List<FirebaseDeviceTemplateItem> = emptyList()

  fun getItem(index: Int) = templateItems[index].activeItem

  val itemCount
    get() = templateItems.size

  init {
    val templatesFlow = project.service<DeviceProvisionerService>().deviceProvisioner.templates
    scope.launch {
      templatesFlow
        .map { it.filterIsInstance<FirebaseDeviceTemplate>() }
        .distinctUntilChanged()
        .collect { newTemplates ->
          val newTemplateSet = newTemplates.toSet()
          templateItems.filter { it.template !in newTemplateSet }.forEach { Disposer.dispose(it) }
          withContext(uiDispatcher) {
            val existingMap = templateItems.associateBy { it.template }
            templateItems =
              newTemplates.map { template ->
                existingMap[template]
                  ?: FirebaseDeviceTemplateItem(template, scope, uiDispatcher, parent) {
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
  }
}
