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
import com.android.sdklib.deviceprovisioner.DeviceActionDisabledException
import com.android.sdklib.deviceprovisioner.DeviceActionException
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceProperties
import com.android.sdklib.deviceprovisioner.DeviceProvisionerPlugin
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.sdklib.deviceprovisioner.DeviceState.Connected
import com.android.sdklib.deviceprovisioner.DeviceState.Disconnected
import com.android.sdklib.deviceprovisioner.DeviceTemplate
import com.android.sdklib.deviceprovisioner.ReservationAction
import com.android.sdklib.deviceprovisioner.ReservationState
import com.android.sdklib.deviceprovisioner.TemplateActivationAction
import com.android.sdklib.deviceprovisioner.asMap
import com.android.sdklib.deviceprovisioner.invokeOnDisconnection
import com.android.tools.adbbridge.Reservation
import com.android.tools.idea.concurrency.createChildScope
import com.android.tools.idea.devicemanager.DeviceType
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.run.DeviceHeadsUpListener
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.login.LoginState
import com.google.services.firebase.directaccess.client.DirectAccessConnection
import com.google.services.firebase.directaccess.client.DirectAccessReservationManager
import com.google.services.firebase.directaccess.client.findOrCreateReservation
import com.google.services.firebase.directaccess.client.isClosed
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private val defaultDeviceInfoProvider = {
  CatalogClient.getAvailableDevices("https://${StudioFlags.DIRECT_ACCESS_ENDPOINT.get()}/")
}

private val EXTENSION_TIMEOUT = Duration.ofSeconds(10)

/**
 * Provides access to physical devices run by Firebase. Supports configuring Firebase device
 * templates and activating / deactivating them.
 */
class FirebaseDeviceProvisioner(
  private val scope: CoroutineScope,
  private val project: Project,
  private val deviceInfoProvider: () -> List<DeviceInfo> = defaultDeviceInfoProvider
) : DeviceProvisionerPlugin {
  private val logger = Logger.getInstance(FirebaseDeviceProvisioner::class.java)

  // TODO: find a proper priority
  override val priority: Int = 120

  private val _devices = MutableStateFlow(emptyList<DeviceHandle>())
  override val devices: StateFlow<List<DeviceHandle>> = _devices
  private val _templates = MutableStateFlow(emptyList<DeviceTemplate>())
  override val templates: StateFlow<List<DeviceTemplate>> = _templates

  init {
    // This scope will not be cancelled on login changes. Only the inner child scope will be
    // cancelled.
    scope.launch {
      var childScope: CoroutineScope? = null
      LoginState.loggedIn.distinctUntilChanged().collect { isLoggedIn ->
        // This cancellation will cause all child scopes created from this childScope to be
        // cancelled.
        // This includes cancellation of scopes in template, handle, connection.
        childScope?.cancel()
        childScope = scope.createChildScope(isSupervisor = true)
        if (isLoggedIn) {
          childScope?.launch { periodicUpdateReservation() }
          childScope?.launch {
            // Fetch reservations with new templates.
            templates.collect { updateReservations(project, templates) }
          }
          childScope?.launch { periodicUpdateTemplates(this) }
        }
      }
    }
  }

  // Update templates every 5 minutes.
  private suspend fun periodicUpdateTemplates(parentScope: CoroutineScope) {
    while (true) {
      val oldTemplates =
        templates.value
          .groupBy { (it as FirebaseDeviceTemplate).deviceInfo }
          .mapValues { it.value[0] }
      try {
        deviceInfoProvider()
          .map { info ->
            // Create a child scope for every template to isolate them in terms of scope.
            // This helps avoid any issues with a given template from propagating to other
            // templates.
            oldTemplates[info]
              ?: FirebaseDeviceTemplate(
                project,
                info,
                _devices,
                parentScope.createChildScope(isSupervisor = true)
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

  // Fetch reservations periodically in case a Reservation is created elsewhere.
  private suspend fun periodicUpdateReservation() {
    while (true) {
      updateReservations(project, templates)
      delay(TimeUnit.MINUTES.toMillis(1))
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
  private val devices: MutableStateFlow<List<DeviceHandle>>,
  private val scope: CoroutineScope
) : DeviceTemplate {
  override val properties = deviceInfo.toDeviceProperties()

  private val isActivationEnabled = MutableStateFlow(true)

  /**
   * Last device handle activated by the template.
   *
   * TODO (b/246171065): resolve potential race condition to support activating multiple devices
   */
  var activeDevice: DirectAccessDeviceHandle? = null
    private set(device) {
      field = device
      if (device != null) {
        devices.update { list -> list + device }
        device.scope.launch {
          device.stateFlow.collect {
            if (it.reservation?.state?.isClosed() == true) {
              field = null
              isActivationEnabled.value = true
              devices.update { list -> list - device }
            }
          }
        }
      }
    }

  override val activationAction: TemplateActivationAction =
    object : TemplateActivationAction {
      // TODO: Pass duration through to the DirectAccessConnectionManager.
      override val durationUsed = false

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
      override suspend fun activate(duration: Duration?): DeviceHandle {
        // Disable further activate actions to avoid multiple devices.
        if (!isActivationEnabled.compareAndSet(expect = true, update = false)) {
          throw DeviceActionDisabledException(this)
        }

        val reservationManager =
          project.service<DirectAccessService>().reservationManager
            ?: throw DeviceActionException("Unable to access ReservationManager.")

        val reservationName =
          try {
            reservationManager
              .findOrCreateReservation(deviceInfo.codename, deviceInfo.api.toString())
              .name
          } catch (e: Exception) {
            // Pass the underlying gRPC exception as a cause
            // TODO: Perhaps extract more detail if we can get it.
            throw DeviceActionException("Unable to reserve device.", e)
          }

        val deviceProperties = deviceInfo.toDeviceProperties()
        val deviceScope = scope.createChildScope(isSupervisor = true)
        // Notify provisioner plugin of the new device.
        return DirectAccessDeviceHandle(
            project,
            deviceScope,
            this@FirebaseDeviceTemplate,
            Disconnected(deviceProperties),
            reservationName
          )
          .also { activeDevice = it }
      }

      override val label: String = "Acquire"
      override val isEnabled: StateFlow<Boolean> = isActivationEnabled
    }

  override val editAction = null
}

class DirectAccessDeviceHandle(
  private val project: Project,
  override val scope: CoroutineScope,
  override val sourceTemplate: DeviceTemplate,
  initialState: DeviceState,
  reservationName: String,
) : DeviceHandle {

  private val reservationManager: DirectAccessReservationManager =
    project.service<DirectAccessService>().reservationManager
      ?: throw RuntimeException("Not logged in.")

  val connection: DirectAccessConnection =
    project.service<DirectAccessService>().connectToReservation(reservationName, scope)
      ?: throw RuntimeException("Not logged in.")

  override val stateFlow = MutableStateFlow(initialState)

  init {
    scope.launch {
      // Map Reservation to its device provisioner format.
      reservationManager
        .fetchReservationFlow(reservationName)
        .map {
          val reservationState =
            when (it.sessionState) {
              Reservation.SessionState.REQUESTED,
              Reservation.SessionState.PENDING -> ReservationState.PENDING
              Reservation.SessionState.ACTIVE -> ReservationState.ACTIVE
              Reservation.SessionState.FINISHED -> ReservationState.COMPLETE
              else -> ReservationState.ERROR
            }
          com.android.sdklib.deviceprovisioner.Reservation(
            reservationState,
            "None",
            Instant.ofEpochSecond(it.createTime.seconds),
            Instant.ofEpochSecond(it.expireTime.seconds)
          )
        }
        .collect { reservation ->
          stateFlow.update { state ->
            when (state) {
              is Connected -> state.copy(reservation = reservation)
              is Disconnected -> state.copy(reservation = reservation)
              else -> state
            }
          }
        }
    }
  }

  override val activationAction =
    object : ActivationAction {
      /** Starts connection to the remote device. */
      override suspend fun activate(params: ActivationParams) {
        withContext(scope.coroutineContext) {
          stateFlow.update { Activating(it.properties, it.reservation) }
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
              },
              it.reservation
            )
          }
        }
      }

      override val label: String = "Connect"
      override val isEnabled: StateFlow<Boolean> =
        connection.state
          .map { it.connection == DirectAccessConnection.ConnectionState.DISCONNECTED }
          .stateIn(scope, SharingStarted.Eagerly, true)
    }

  override val deactivationAction =
    object : DeactivationAction {
      override suspend fun deactivate() =
        withContext(scope.coroutineContext + NonCancellable) { connection.endReservation() }

      override val label: String
        get() = "Disconnect"
      override val isEnabled: StateFlow<Boolean> =
        connection.state
          .map { !it.reservation.sessionState.isClosed() }
          .stateIn(scope, SharingStarted.Eagerly, true)
    }

  override val reservationAction: ReservationAction =
    object : ReservationAction {
      override suspend fun reserve(duration: Duration): Instant {
        val reservation =
          state.reservation ?: throw DeviceActionException("Reservation not available.")
        val endTime =
          reservation.endTime ?: throw DeviceActionException("Reservation end time not available.")
        connection.extendReservation(duration)
        // Wait until reservation updates.
        try {
          withTimeout(EXTENSION_TIMEOUT.toMillis()) {
            stateFlow
              .takeWhile { it.reservation?.endTime?.toEpochMilli() == endTime.toEpochMilli() }
              .collect()
          }
        } catch (e: TimeoutCancellationException) {
          throw DeviceActionException(
            "Reservation not extended within ${EXTENSION_TIMEOUT.seconds} seconds"
          )
        }
        return state.reservation?.endTime
          ?: throw DeviceActionException("Extended reservation end time not available.")
      }

      override val label: String = "Reserve"

      /** [ReservationAction] is enabled through the lifecycle of the device handle. */
      override val isEnabled: StateFlow<Boolean> = MutableStateFlow(true)
    }

  /** Returns true and changes state to [Connected] if [port] matches the [connection] of handle. */
  suspend fun claim(port: Int, device: ConnectedDevice): Boolean {
    if (connection.port != port) {
      return false
    }
    // Show the device tab in running devices window.
    project.messageBus
      .syncPublisher(DeviceHeadsUpListener.TOPIC)
      .userInvolvementRequired(device.deviceInfoFlow.value.serialNumber, project)
    val properties = device.deviceProperties().all().asMap()
    val deviceProperties =
      DirectAccessDeviceProperties.build {
        readCommonProperties(properties)
        // TODO(b/260153322): Remove once device manager moves to device provisioner framework
        disambiguator = "${connection.port}"
      }
    stateFlow.update { Connected(deviceProperties, device, it.reservation) }
    device.invokeOnDisconnection {
      stateFlow.update { Disconnected(deviceProperties, false, it.status, it.reservation) }
    }
    return true
  }

  class Activating(
    override val properties: DeviceProperties,
    reservation: com.android.sdklib.deviceprovisioner.Reservation?
  ) : Disconnected(properties, isTransitioning = true, "Connecting", reservation)
}

class DirectAccessDeviceProperties(base: DeviceProperties) : DeviceProperties by base {
  class Builder : DeviceProperties.Builder()
  companion object {
    fun build(block: Builder.() -> Unit) =
      Builder().apply(block).run { DirectAccessDeviceProperties(buildBase()) }
  }
}

fun DeviceInfo.toDeviceProperties(): DirectAccessDeviceProperties {
  val info = this
  return DirectAccessDeviceProperties.build {
    manufacturer = info.manufacturer
    model = info.name
    androidVersion = AndroidVersion(info.api)
    deviceType =
      when (info.type) {
        DeviceType.PHONE -> com.android.sdklib.deviceprovisioner.DeviceType.HANDHELD
        DeviceType.TV -> com.android.sdklib.deviceprovisioner.DeviceType.TV
        DeviceType.WEAR_OS -> com.android.sdklib.deviceprovisioner.DeviceType.WEAR
        DeviceType.AUTOMOTIVE -> com.android.sdklib.deviceprovisioner.DeviceType.AUTOMOTIVE
      }
  }
}

fun ReservationState.isClosed() =
  this == ReservationState.ERROR || this == ReservationState.COMPLETE
