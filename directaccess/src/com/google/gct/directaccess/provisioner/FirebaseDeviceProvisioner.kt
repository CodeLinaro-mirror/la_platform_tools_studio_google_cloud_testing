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
import com.android.sdklib.deviceprovisioner.ActivationAction
import com.android.sdklib.deviceprovisioner.ActivationParams
import com.android.sdklib.deviceprovisioner.DeactivationAction
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceProperties
import com.android.sdklib.deviceprovisioner.DeviceProvisionerPlugin
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.sdklib.deviceprovisioner.DeviceState.Connected
import com.android.sdklib.deviceprovisioner.DeviceState.Disconnected
import com.android.sdklib.deviceprovisioner.DeviceTemplate
import com.android.sdklib.deviceprovisioner.invokeOnDisconnection
import com.android.tools.adbbridge.Reservation
import com.android.tools.idea.concurrency.createChildScope
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.run.DeviceHeadsUpListener
import com.google.gct.directaccess.DirectAccessService
import com.google.services.firebase.directaccess.client.DirectAccessConnection
import com.google.services.firebase.directaccess.client.isClosed
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
  scope: CoroutineScope,
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
    // Update templates every 5 minutes.
    scope.launch {
      while (true) {
        val oldTemplates =
          templates.value.groupBy { (it as FirebaseDeviceTemplate).deviceInfo }.mapValues {
            it.value[0]
          }
        try {
          deviceInfoProvider()
            .map { info ->
              oldTemplates[info]
                ?: FirebaseDeviceTemplate(
                  project,
                  info,
                  _devices,
                  createChildScope(isSupervisor = true)
                )
            }
            .let { result -> _templates.value = result }
        } catch (ignore: NotLoggedInException) {
          // do nothing
        } catch (e: Exception) {
          logger.warn(e)
        }
        delay(TimeUnit.MINUTES.toMillis(5))
      }
    }

    // Fetch reservations with new templates.
    scope.launch { templates.collect { updateReservations(project, templates) } }
    // Fetch reservations periodically in case a Reservation is created elsewhere.
    scope.launch {
      while (true) {
        updateReservations(project, templates)
        delay(TimeUnit.MINUTES.toMillis(1))
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

suspend fun updateReservations(project: Project, templates: StateFlow<List<DeviceTemplate>>) {
  val templateMap =
    templates.value.filterIsInstance<FirebaseDeviceTemplate>().groupBy { template ->
      template.deviceInfo.let { "${it.codename} ${it.api}" }
    }
  project
    .service<DirectAccessService>()
    .reservationManager
    ?.listReservations()
    ?.filter { reservation ->
      !reservation.sessionState.isClosed() &&
        reservation.androidDeviceList.androidDevicesList.isNotEmpty()
    }
    ?.forEach { reservation ->
      val key =
        reservation.androidDeviceList.androidDevicesList[0].let {
          "${it.androidModelId} ${it.androidVersionId}"
        }
      templateMap[key]?.firstOrNull()?.activationAction?.activate()
    }
}

class FirebaseDeviceTemplate(
  private val project: Project,
  val deviceInfo: DeviceInfo,
  devices: MutableStateFlow<List<DeviceHandle>>,
  private val scope: CoroutineScope
) : DeviceTemplate {
  override val displayName: String = "${deviceInfo.manufacturer} ${deviceInfo.name}"

  /**
   * Last device handle activated by the template.
   *
   * TODO (b/246171065): resolve potential race condition to support activating multiple devices
   */
  var activeDevice: DirectAccessDeviceHandle? = null
    private set

  override val activationAction: ActivationAction =
    object : ActivationAction {
      private val _isEnabled = MutableStateFlow(true)

      /**
       * Creates a [DirectAccessDeviceHandle] with [Disconnected] state.
       *
       * This method first finds or create a [Reservation] that matches its [deviceInfo]. Then a
       * [DirectAccessDeviceHandle] is created with a [DirectAccessConnection] to the [Reservation].
       * Connection is not started until the activationAction of device handle get called. The
       * device handle is added to the devices flow of the provisioner and will be removed after
       * [Reservation] closed. This method is disabled when a device handle is activating or
       * activated. At most one device is available for each template.
       *
       * TODO (b/246171065): activating multiple devices.
       */
      override suspend fun activate(params: ActivationParams) {
        // Disable further activate actions to avoid multiple devices.
        if (_isEnabled.compareAndSet(expect = true, update = false)) {
          val connection =
            project
              .service<DirectAccessService>()
              .reserveConnection(deviceInfo.codename, deviceInfo.api.toString())
              ?: return

          val deviceProperties =
            DirectAccessDeviceProperties.build {
              manufacturer = deviceInfo.manufacturer
              androidVersion = AndroidVersion(deviceInfo.api)
              model = deviceInfo.name
            }
          val deviceScope = scope.createChildScope(isSupervisor = true)
          // Notify provisioner plugin of the new device.
          activeDevice =
            DirectAccessDeviceHandle(
                project,
                deviceScope,
                Disconnected(deviceProperties),
                connection
              )
              .also { device ->
                devices.update { list -> list + device }
                deviceScope.launch {
                  connection.state.collect {
                    if (it.reservation.sessionState.isClosed()) {
                      activeDevice = null
                      _isEnabled.value = true
                      devices.update { list -> list - device }
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
  private val project: Project,
  override val scope: CoroutineScope,
  state: DeviceState,
  val connection: DirectAccessConnection
) : DeviceHandle {

  override val stateFlow = MutableStateFlow(state)

  override val activationAction =
    object : ActivationAction {
      /** Starts connection to the remote device. */
      override suspend fun activate(params: ActivationParams) {
        withContext(scope.coroutineContext) {
          stateFlow.update { Activating(it.properties) }
          connection.connect()
          // Add disambiguator field that adds the port on which the device is connected to denote
          // this is a firebase device.
          // TODO(b/260153322): Remove once device manager moves to device provisioner framework
          stateFlow.update {
            Activating(
              DirectAccessDeviceProperties.build {
                manufacturer = it.properties.manufacturer
                androidVersion = it.properties.androidVersion
                model = it.properties.model
                disambiguator = "${connection.port}"
              }
            )
          }
        }
      }

      override val label: String = "Connect"
      override val isEnabled: StateFlow<Boolean> =
        connection
          .state
          .map { it.connection == DirectAccessConnection.ConnectionState.DISCONNECTED }
          .stateIn(scope, SharingStarted.Eagerly, true)
    }

  override val deactivationAction =
    object : DeactivationAction {
      override suspend fun deactivate() {
        withContext(scope.coroutineContext + NonCancellable) {
          connection.endReservation()
          stateFlow.value = Disconnected(stateFlow.value.properties)
        }
      }

      override val label: String
        get() = "Disconnect"
      override val isEnabled: StateFlow<Boolean> =
        connection
          .state
          .map { !it.reservation.sessionState.isClosed() }
          .stateIn(scope, SharingStarted.Eagerly, true)
    }

  /** Returns true and changes state to [Connected] if [port] matches the [connection] of handle. */
  suspend fun claim(port: Int, device: ConnectedDevice): Boolean {
    if (connection.port != port) {
      return false
    }
    // Show the device tab in running devices window.
    project
      .messageBus
      .syncPublisher(DeviceHeadsUpListener.TOPIC)
      .userInvolvementRequired(device.deviceInfoFlow.value.serialNumber, project)
    val properties = device.deviceProperties().allReadonly()
    val deviceProperties =
      DirectAccessDeviceProperties.build {
        readCommonProperties(properties)
        // TODO(b/260153322): Remove once device manager moves to device provisioner framework
        disambiguator = "${connection.port}"
      }
    stateFlow.value = Connected(deviceProperties, device)
    device.invokeOnDisconnection { stateFlow.value = Disconnected(deviceProperties) }
    return true
  }

  class Activating(override val properties: DeviceProperties) :
    Disconnected(properties, isTransitioning = true, "Connecting")
}

class DirectAccessDeviceProperties(base: DeviceProperties) : DeviceProperties by base {
  class Builder : DeviceProperties.Builder()
  companion object {
    fun build(block: Builder.() -> Unit) =
      Builder().apply(block).run { DirectAccessDeviceProperties(buildBase()) }
  }
}
