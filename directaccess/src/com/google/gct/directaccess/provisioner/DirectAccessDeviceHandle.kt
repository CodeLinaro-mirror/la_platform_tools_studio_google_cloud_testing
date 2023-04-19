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
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
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
private val notificationGroup: NotificationGroup
  get() = NotificationGroup.findRegisteredGroup("Direct Access")!!

class DirectAccessDeviceHandle(
  private val project: Project,
  override val scope: CoroutineScope,
  override val sourceTemplate: DeviceTemplate,
  initialState: DeviceState,
  private val reservationName: String
) : DeviceHandle {

  private val reservationManager: DirectAccessReservationManager =
    project.service<DirectAccessService>().reservationManager
      ?: throw RuntimeException("Not logged in.")

  val connection: DirectAccessConnection =
    project.service<DirectAccessService>().connectToReservation(reservationName, scope)
      ?: throw RuntimeException("Not logged in.")

  override val stateFlow: MutableStateFlow<DeviceState>

  init {
    val reservationFlow = reservationManager.fetchReservationFlow(reservationName)

    stateFlow =
      MutableStateFlow(initialState.withReservation(mapReservation(reservationFlow.value)))

    scope.launch {
      reservationFlow.map(this@DirectAccessDeviceHandle::mapReservation).collect { reservation ->
        stateFlow.update { state -> state.withReservation(reservation) }
      }
    }
  }

  /** Map Reservation to its device provisioner format. */
  private fun mapReservation(
    reservation: Reservation
  ): com.android.sdklib.deviceprovisioner.Reservation {
    val reservationState =
      when (reservation.sessionState) {
        Reservation.SessionState.REQUESTED,
        Reservation.SessionState.PENDING -> ReservationState.PENDING
        Reservation.SessionState.ACTIVE -> ReservationState.ACTIVE
        Reservation.SessionState.FINISHED -> ReservationState.COMPLETE
        else -> ReservationState.ERROR
      }
    return com.android.sdklib.deviceprovisioner.Reservation(
      reservationState,
      "",
      Instant.ofEpochSecond(reservation.createTime.seconds),
      Instant.ofEpochSecond(reservation.expireTime.seconds)
    )
  }

  private fun DeviceState.withReservation(
    reservation: com.android.sdklib.deviceprovisioner.Reservation
  ): DeviceState =
    when (this) {
      is DeviceState.Connected -> copy(reservation = reservation)
      is DeviceState.Disconnected -> {
        val newStatus =
          when {
            !isTransitioning -> status
            reservation.state == ReservationState.ACTIVE -> "Connecting to device..."
            else -> "Reserving a device..."
          }
        copy(status = newStatus, reservation = reservation)
      }
    }

  override val activationAction =
    object : ActivationAction {
      /** Starts connection to the remote device. */
      override suspend fun activate(params: ActivationParams) {
        withContext(scope.coroutineContext) {
          stateFlow.update {
            val reservation = it.reservation ?: return@withContext
            DeviceState.Disconnected(it.properties)
              .copy(isTransitioning = true)
              .withReservation(reservation)
          }
          connection.connect()
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
        withContext(scope.coroutineContext + NonCancellable) {
          connection.endReservation(withGracePeriod = true)
          val reservationExpireTime =
            reservationManager.fetchReservationFlow(reservationName).value.expireTime.seconds
          val timeRemaining =
            Instant.now().until(Instant.ofEpochSecond(reservationExpireTime), ChronoUnit.SECONDS)
          val message =
            when {
              timeRemaining <= 0 -> return@withContext
              timeRemaining <= 60 -> getNotificationMessage("less than 1 minute")
              timeRemaining in 60..90 -> getNotificationMessage("1 minute")
              else -> getNotificationMessage("${timeRemaining.div(60F).roundToInt()} minutes")
            }
          notificationGroup
            .createNotification("Firebase device stopped", message, NotificationType.INFORMATION)
            .addAction(
              NotificationAction.createExpiring("Reconnect to Device") { _, _ ->
                scope.launch { activationAction.activate() }
              }
            )
            .addAction(
              NotificationAction.createExpiring("Force check-in device") { _, _ ->
                scope.launch { withContext(NonCancellable) { connection.endReservation() } }
              }
            )
            .notify(project)
        }

      override val label: String
        get() = "Disconnect"
      override val isEnabled: StateFlow<Boolean> =
        connection.state
          .map { !it.reservation.sessionState.isClosed() }
          .stateIn(scope, SharingStarted.Eagerly, true)

      private fun getNotificationMessage(phrase: String) =
        "You can reconnect to the same device for up to $phrase before the device is wiped"
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
