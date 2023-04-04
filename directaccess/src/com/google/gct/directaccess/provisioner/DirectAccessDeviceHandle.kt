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
package com.google.gct.directaccess.provisioner

import com.android.adblib.ConnectedDevice
import com.android.adblib.deviceProperties
import com.android.sdklib.AndroidVersion
import com.android.sdklib.deviceprovisioner.ActivationAction
import com.android.sdklib.deviceprovisioner.ActivationParams
import com.android.sdklib.deviceprovisioner.DeactivationAction
import com.android.sdklib.deviceprovisioner.DeviceActionException
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceProperties
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.sdklib.deviceprovisioner.DeviceTemplate
import com.android.sdklib.deviceprovisioner.ReservationAction
import com.android.sdklib.deviceprovisioner.ReservationState
import com.android.sdklib.deviceprovisioner.asMap
import com.android.sdklib.deviceprovisioner.invokeOnDisconnection
import com.android.tools.adbbridge.Reservation
import com.android.tools.idea.devicemanager.DeviceType
import com.android.tools.idea.run.DeviceHeadsUpListener
import com.google.gct.directaccess.DirectAccessService
import com.google.services.firebase.directaccess.client.DirectAccessConnection
import com.google.services.firebase.directaccess.client.DirectAccessReservationManager
import com.google.services.firebase.directaccess.client.isClosed
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private val EXTENSION_TIMEOUT = Duration.ofSeconds(10)

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
              is DeviceState.Connected -> state.copy(reservation = reservation)
              is DeviceState.Disconnected -> state.copy(reservation = reservation)
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
    stateFlow.update { DeviceState.Connected(deviceProperties, device, it.reservation) }
    device.invokeOnDisconnection {
      stateFlow.update {
        DeviceState.Disconnected(deviceProperties, false, it.status, it.reservation)
      }
    }
    return true
  }

  class Activating(
    override val properties: DeviceProperties,
    reservation: com.android.sdklib.deviceprovisioner.Reservation?
  ) : DeviceState.Disconnected(properties, isTransitioning = true, "Connecting", reservation)
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
