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
import com.android.sdklib.deviceprovisioner.ActivationAction
import com.android.sdklib.deviceprovisioner.ActivationParams
import com.android.sdklib.deviceprovisioner.Connected
import com.android.sdklib.deviceprovisioner.DeactivationAction
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceProperties
import com.android.sdklib.deviceprovisioner.DeviceProvisionerPlugin
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.sdklib.deviceprovisioner.DeviceTemplate
import com.android.sdklib.deviceprovisioner.Disconnected
import com.android.sdklib.deviceprovisioner.invokeOnDisconnection
import com.android.tools.idea.concurrency.coroutineScope
import com.android.tools.idea.concurrency.executeOnPooledThread
import com.android.tools.idea.flags.StudioFlags
import com.google.gct.directaccess.DirectAccessService
import com.google.services.firebase.directaccess.client.DeviceInfo
import com.google.services.firebase.directaccess.client.catalog.FirebaseDirectAccessClient
import com.google.services.firebase.directaccess.client.device.directaccess.DirectAccessClient
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Provides access to physical devices run by Firebase. Supports configuring Firebase device
 * templates and activating / deactivating them.
 */
class FirebaseDeviceProvisioner(val project: Project) : DeviceProvisionerPlugin {
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
        try {
          FirebaseDirectAccessClient.getAvailableDevices(
              "https://${StudioFlags.DIRECT_ACCESS_ENDPOINT.get()}/"
            )
            .map { info -> FirebaseDeviceTemplate(project, info, devices, project.coroutineScope) }
            .let { result -> _templates.emit(result) }
        } catch (e: Exception) {
          logger.warn(e)
        }
        delay(TimeUnit.MINUTES.toMillis(5))
      }
    }
  }

  override suspend fun claim(device: ConnectedDevice): Boolean {
    val sn = device.deviceInfoFlow.value.serialNumber
    if (sn.matches(Regex("^localhost:\\d+$"))) {
      val port = sn.substringAfter(':').toInt()
      val service = project.service<DirectAccessService>()

      val client = service.deviceClients[port]
      if (client != null) {
        val properties = device.deviceProperties().allReadonly()
        val deviceProperties =
          DirectAccessDeviceProperties.build { readCommonProperties(properties) }
        val stateFlow = MutableStateFlow<DeviceState>(Connected(deviceProperties, device))
        val handle = DirectAccessDeviceHandle(stateFlow, client)
        _devices.update { it + handle }
        device.invokeOnDisconnection {
          stateFlow.value = Disconnected(deviceProperties)
          _devices.update { it - handle }
        }
        return true
      }
    }
    return false
  }
}

class FirebaseDeviceTemplate(
  private val project: Project,
  private val info: DeviceInfo,
  devices: StateFlow<List<DeviceHandle>>,
  private val scope: CoroutineScope
) : DeviceTemplate {
  override val displayName: String = "${info.manufacturer} ${info.name}"

  val claimedDevices =
    devices.map { deviceList ->
      deviceList.filterIsInstance<DirectAccessDeviceHandle>().filter { handle ->
        val properties = handle.stateFlow.value.properties
        apiLevel == properties.androidVersion?.apiLevel &&
          properties.manufacturer == info.manufacturer &&
          properties.model == info.name
      }
    }

  override val activationAction: ActivationAction =
    object : ActivationAction {
      /**
       * Ideally [activationAction] should always be enabled. However, as the current UI only
       * supports one device per template, [activationAction] is disabled intentionally when there
       * is a device activating or running.
       *
       * TODO (b/246171065): activating multiple devices
       */
      val _isEnabled =
        MutableStateFlow(true).apply {
          scope.launch {
            // Enable [activationAction] when all claimed devices are removed.
            claimedDevices.distinctUntilChanged().collect {
              if (it.isEmpty()) {
                value = true
              }
            }
          }
        }

      override suspend fun activate(params: ActivationParams) {
        if (_isEnabled.value) {
          // Disable further activate actions to avoid multiple devices.
          _isEnabled.value = false
          executeOnPooledThread {
            project
              .service<DirectAccessService>()
              .acquireAndConnect(info.codename, info.api.toString())
          }
        }
      }

      override val label: String = "Acquire"
      override val isEnabled: StateFlow<Boolean> = _isEnabled
    }

  override val editAction = null
  val apiLevel = info.api
  val targetName = info.codename
}

class DirectAccessDeviceHandle(
  override val stateFlow: StateFlow<DeviceState>,
  private val client: DirectAccessClient
) : DeviceHandle {
  override val deactivationAction =
    object : DeactivationAction {
      private val _isEnabled = MutableStateFlow(true)

      override suspend fun deactivate() {
        // Disable further deactivate actions for the device.
        if (_isEnabled.value) {
          _isEnabled.value = false
          executeOnPooledThread { client.close() }
        }
      }

      override val label: String
        get() = "Disconnect"
      override val isEnabled: StateFlow<Boolean> = _isEnabled
    }
}

class DirectAccessDeviceProperties(base: DeviceProperties) : DeviceProperties by base {
  class Builder : DeviceProperties.Builder()
  companion object {
    fun build(block: Builder.() -> Unit) =
      Builder().apply(block).run { DirectAccessDeviceProperties(buildBase()) }
  }
}
