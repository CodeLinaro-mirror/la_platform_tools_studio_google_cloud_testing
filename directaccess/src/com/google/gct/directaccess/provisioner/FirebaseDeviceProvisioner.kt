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
package com.google.gct.directaccess.provisioner

import com.android.adblib.ConnectedDevice
import com.android.adblib.deviceProperties
import com.android.sdklib.AndroidVersion
import com.android.sdklib.deviceprovisioner.Activating
import com.android.sdklib.deviceprovisioner.ActivationAction
import com.android.sdklib.deviceprovisioner.ActivationParams
import com.android.sdklib.deviceprovisioner.Connected
import com.android.sdklib.deviceprovisioner.ConnectionType
import com.android.sdklib.deviceprovisioner.DeactivationAction
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceProperties
import com.android.sdklib.deviceprovisioner.DeviceProvisionerPlugin
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.sdklib.deviceprovisioner.DeviceTemplate
import com.android.sdklib.deviceprovisioner.Disconnected
import com.android.sdklib.deviceprovisioner.PhysicalDeviceProperties
import com.android.tools.idea.concurrency.AndroidDispatchers
import com.android.tools.idea.concurrency.coroutineScope
import com.android.tools.idea.concurrency.createChildScope
import com.android.tools.idea.emulator.RUNNING_DEVICES_TOOL_WINDOW_ID
import com.android.tools.idea.flags.StudioFlags
import com.google.gct.directaccess.DirectAccessService
import com.google.services.firebase.directaccess.client.DirectAccessConnection
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val defaultDeviceInfoProvider = {
  CatalogClient.getAvailableDevices("https://${StudioFlags.DIRECT_ACCESS_ENDPOINT.get()}/")
}

/**
 * Provides access to physical devices run by Firebase. Supports configuring Firebase device
 * templates and activating / deactivating them.
 */
class FirebaseDeviceProvisioner(
  project: Project,
  deviceInfoProvider: () -> List<DeviceInfo> = defaultDeviceInfoProvider
) : DeviceProvisionerPlugin {
  private val logger = Logger.getInstance(FirebaseDeviceProvisioner::class.java)

  // TODO: find a proper priority
  override val priority: Int = 120

  private val _devices = MutableStateFlow(emptyList<DeviceHandle>())
  override val devices: StateFlow<List<DeviceHandle>> = _devices
  private val _templates = MutableStateFlow(emptyList<DeviceTemplate>())
  override val templates: StateFlow<List<DeviceTemplate>> = _templates

  init {
    project.coroutineScope.launch {
      while (true) {
        val oldTemplates =
          _templates.value.groupBy { (it as FirebaseDeviceTemplate).info }.mapValues { it.value[0] }
        try {
          deviceInfoProvider()
            .map { info ->
              oldTemplates[info]
                ?: FirebaseDeviceTemplate(project, info, _devices, createChildScope(true))
            }
            .let { result -> _templates.emit(result) }
        } catch (ignore: NotLoggedInException) {
          // do nothing
        } catch (e: Exception) {
          logger.warn(e)
        }
        delay(TimeUnit.MINUTES.toMillis(5))
      }
    }
  }

  override suspend fun claim(device: ConnectedDevice): DeviceHandle? {
    val sn = device.deviceInfoFlow.value.serialNumber
    if (sn.matches(Regex("^localhost:\\d+$"))) {
      val port = sn.substringAfter(':').toIntOrNull() ?: return null
      return devices.value.filterIsInstance<DirectAccessDeviceHandle>().firstOrNull {
        it.claim(port, device)
      }
    }
    return null
  }
}

class FirebaseDeviceTemplate(
  private val project: Project,
  val info: DeviceInfo,
  devices: MutableStateFlow<List<DeviceHandle>>,
  private val scope: CoroutineScope
) : DeviceTemplate {
  override val displayName: String = "${info.manufacturer} ${info.name}"

  /**
   * Last device handle activated by the template.
   *
   * TODO (b/246171065): resolve potential race condition to support activating multiple devices
   */
  var latestActivatingDevice: DirectAccessDeviceHandle? = null
    private set

  override val activationAction: ActivationAction =
    object : ActivationAction {
      /**
       * Ideally [activationAction] should always be enabled. However, as the current UI only
       * supports one device per template, [activationAction] is disabled intentionally when there
       * is a device activating or running.
       *
       * TODO (b/246171065): activating multiple devices
       */
      private val _isEnabled = MutableStateFlow(true)

      override suspend fun activate(params: ActivationParams) {
        // Open running devices window if not already open
        ToolWindowManager.getInstance(project).getToolWindow(RUNNING_DEVICES_TOOL_WINDOW_ID)?.let {
          toolWindow ->
          withContext(AndroidDispatchers.uiThread) {
            if (!toolWindow.isVisible) {
              toolWindow.show()
            }
            toolWindow.activate(null)
          }
        }
        if (_isEnabled.value) { // Disable further activate actions to avoid multiple devices.
          _isEnabled.value = false
          val connection =
            project
              .service<DirectAccessService>()
              .reserveConnection(info.codename, info.api.toString())
              ?: return

          scope.launch(Dispatchers.IO) {
            connection.waitUntilReservationActive()
            connection.connect()
          }

          val deviceProperties =
            PhysicalDeviceProperties.build {
              manufacturer = info.manufacturer
              androidVersion = AndroidVersion(info.api)
              model = info.name
              connectionType = ConnectionType.USB
            }
          // Notify provisioner plugin of the new device.
          latestActivatingDevice =
            DirectAccessDeviceHandle(
                scope.createChildScope(true),
                Activating(deviceProperties),
                connection
              )
              .also { device ->
                scope.launch {
                  devices.update { list -> list + device }
                  device.stateFlow.collect {
                    if (it is Disconnected) {
                      devices.update { list -> list - device }
                      _isEnabled.value = true
                    }
                  }
                }
              }
        }
      }

      override val label: String = "Acquire"
      override val isEnabled: StateFlow<Boolean> = _isEnabled
    }

  override val editAction = null
}

class DirectAccessDeviceHandle(
  scope: CoroutineScope,
  state: DeviceState,
  val connection: DirectAccessConnection
) : DeviceHandle {
  private val _stateFlow = MutableStateFlow(state)
  override val stateFlow: StateFlow<DeviceState> = _stateFlow

  override val deactivationAction =
    object : DeactivationAction {
      private val _isEnabled = MutableStateFlow(true)

      override suspend fun deactivate() {
        // Disable further deactivate actions for the device.
        if (_isEnabled.value) {
          _isEnabled.value = false
          scope.launch { connection.endSession() }
          _stateFlow.value = Disconnected(_stateFlow.value.properties)
        }
      }

      override val label: String
        get() = "Disconnect"
      override val isEnabled: StateFlow<Boolean> = _isEnabled
    }

  /** Returns true and changes state to [Connected] if [port] matches the [connection] of handle. */
  suspend fun claim(port: Int, device: ConnectedDevice): Boolean {
    if (connection.port != port) {
      return false
    }
    val properties = device.deviceProperties().allReadonly()
    val deviceProperties = DirectAccessDeviceProperties.build { readCommonProperties(properties) }
    _stateFlow.value = Connected(deviceProperties, device)
    return true
  }
}

class DirectAccessDeviceProperties(base: DeviceProperties) : DeviceProperties by base {
  class Builder : DeviceProperties.Builder()
  companion object {
    fun build(block: Builder.() -> Unit) =
      Builder().apply(block).run { DirectAccessDeviceProperties(buildBase()) }
  }
}
