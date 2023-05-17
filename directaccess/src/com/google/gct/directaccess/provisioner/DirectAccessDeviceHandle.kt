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
import com.android.sdklib.deviceprovisioner.DeactivationAction
import com.android.sdklib.deviceprovisioner.DeviceAction
import com.android.sdklib.deviceprovisioner.DeviceActionException
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceProperties
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.sdklib.deviceprovisioner.ReservationAction
import com.android.sdklib.deviceprovisioner.ReservationState
import com.android.sdklib.deviceprovisioner.Resolution
import com.android.sdklib.deviceprovisioner.asMap
import com.android.sdklib.deviceprovisioner.invokeOnDisconnection
import com.android.tools.adbbridge.Reservation
import com.android.tools.idea.devicemanager.DeviceType
import com.android.tools.idea.run.DeviceHeadsUpListener
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.analytics.DirectAccessUsageTracker
import com.google.gct.directaccess.analytics.toMetricsDeviceInfo
import com.google.services.firebase.directaccess.client.DirectAccessConnection
import com.google.services.firebase.directaccess.client.DirectAccessReservationManager
import com.google.services.firebase.directaccess.client.isClosed
import com.google.services.firebase.directaccess.client.waitUntilActive
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.FailureReason
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import icons.StudioIcons
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
  override val sourceTemplate: DirectAccessDeviceTemplate,
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

  /** Tracks reconnect to device */
  private var hasConnectedToDeviceOnce = false
  /** Tracks reservation ended by user */
  private var hasUserEndedReservation = false

  init {
    val reservationFlow = reservationManager.fetchReservationFlow(reservationName)

    stateFlow =
      MutableStateFlow(initialState.withReservation(mapReservation(reservationFlow.value)))

    scope.launch {
      reservationFlow.map(this@DirectAccessDeviceHandle::mapReservation).collect { reservation ->
        stateFlow.update { state -> state.withReservation(reservation) }
      }
    }
    scope
      .launch { reservationFlow.takeWhile { !it.sessionState.isClosed() }.collect() }
      .invokeOnCompletion { throwable ->
        if ((throwable == null || throwable is CancellationException) && !hasUserEndedReservation) {
          trackEndReservation(true)
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
      override suspend fun activate() {
        withContext(scope.coroutineContext) {
          stateFlow.update {
            val reservation = it.reservation ?: return@withContext
            DeviceState.Disconnected(it.properties)
              .copy(isTransitioning = true)
              .withReservation(reservation)
          }
          scope.trackConnectTime()
          try {
            connection.connect()
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            // TODO(b/277240160): Add correct failure reason
            trackConnectMetrics(false, failureReason = FailureReason.UNKNOWN_FAILURE)
          }
          stateFlow.update {
            val reservation = it.reservation ?: return@withContext
            DeviceState.Disconnected(it.properties)
              .copy(isTransitioning = true)
              .withReservation(reservation)
          }
          // Reservation end time restored after connecting to device again.
          hasUserEndedReservation = false
        }
      }

      private val defaultPresentation =
        DeviceAction.Presentation("Connect", AllIcons.Actions.Resume, false)

      override val presentation: StateFlow<DeviceAction.Presentation> =
        stateFlow
          .map {
            defaultPresentation.copy(
              enabled = it is DeviceState.Disconnected && !it.isTransitioning
            )
          }
          .stateIn(scope, SharingStarted.Eagerly, defaultPresentation)

      private fun CoroutineScope.trackConnectTime() = launch {
        reservationManager.fetchReservationFlow(reservationName).waitUntilActive()
        val connectStartTime = System.currentTimeMillis()
        try {
          withTimeout(TimeUnit.SECONDS.toMillis(20)) {
            connection.state
              .takeWhile { it.connection != DirectAccessConnection.ConnectionState.CONNECTED }
              .collect()
          }
          trackConnectMetrics(true, System.currentTimeMillis() - connectStartTime)
          hasConnectedToDeviceOnce = true
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // TODO(b/277240160): Add correct failure reason
          trackConnectMetrics(false, failureReason = FailureReason.UNKNOWN_FAILURE)
        }
      }

      private fun trackConnectMetrics(
        wasSuccessful: Boolean,
        timeToConnectMs: Long? = null,
        failureReason: FailureReason? = null
      ) =
        DirectAccessUsageTracker.trackConnectDevice(
          wasSuccessful,
          hasConnectedToDeviceOnce,
          timeToConnectMs,
          reservationName,
          sourceTemplate.deviceInfo.toMetricsDeviceInfo(),
          failureReason
        )
    }

  override val deactivationAction =
    object : DeactivationAction {
      override suspend fun deactivate() =
        withContext(scope.coroutineContext + NonCancellable) {
          hasUserEndedReservation = true
          val reservationFlow = reservationManager.fetchReservationFlow(reservationName)
          // Check here if notification is needed. endReservation might change the sessionState
          val shouldShowNotification =
            reservationFlow.value.sessionState == Reservation.SessionState.ACTIVE
          try {
            connection.endReservation(withGracePeriod = true)
          } catch (e: Exception) {
            trackEndReservation(false)
            throw e
          }
          stateFlow.update {
            when (it) {
              // Reset isTransitioning to false if the connection is not established yet.
              is DeviceState.Disconnected -> it.copy(isTransitioning = false)
              // Let it.connectedDevice update the state for disconnection.
              is DeviceState.Connected -> it
            }
          }
          if (shouldShowNotification) {
            getNotificationPhrase(reservationFlow.value.expireTime.seconds)?.let {
              showNotification(sourceTemplate.properties.title, it)
            }
          }
        }

      private fun getNotificationPhrase(reservationExpireTime: Long): String? {
        val timeRemaining =
          Instant.now().until(Instant.ofEpochSecond(reservationExpireTime), ChronoUnit.SECONDS)
        return when {
          timeRemaining <= 0 -> null
          timeRemaining < 60 -> "less than 1 minute"
          timeRemaining < 120 -> "up to 2 minutes"
          else -> "up to ${timeRemaining.div(60F).roundToInt()} minutes"
        }
      }

      private fun showNotification(deviceName: String, phrase: String) =
        notificationGroup
          .createNotification(
            "$deviceName on Firebase stopped",
            getNotificationMessage(deviceName, phrase),
            NotificationType.INFORMATION
          )
          .addAction(
            NotificationAction.createExpiring("Reconnect to Device") { _, _ ->
              scope.launch { activationAction.activate() }
            }
          )
          .addAction(
            NotificationAction.createExpiring("Force check-in device") { _, _ ->
              scope.launch {
                withContext(NonCancellable) {
                  try {
                    reservationAction.endReservation()
                  } catch (e: Exception) {
                    trackEndReservation(false)
                    throw DeviceActionException("Could not end reservation", e)
                  }
                }
              }
            }
          )
          .notify(project)

      private val defaultPresentation =
        DeviceAction.Presentation("Disconnect", StudioIcons.Avd.STOP, true)

      override val presentation = MutableStateFlow(defaultPresentation).asStateFlow()

      private fun getNotificationMessage(deviceName: String, phrase: String) =
        "You can reconnect to the same $deviceName for $phrase before the device is wiped"
    }

  override val reservationAction: ReservationAction =
    object : ReservationAction {
      override suspend fun reserve(duration: Duration): Instant {
        val reservation =
          state.reservation ?: throw DeviceActionException("Reservation not available.")
        val endTime =
          reservation.endTime ?: throw DeviceActionException("Reservation end time not available.")
        try {
          connection.extendReservation(duration)
          // Wait until reservation updates.
          withTimeout(EXTENSION_TIMEOUT.toMillis()) {
            stateFlow
              .takeWhile { it.reservation?.endTime?.toEpochMilli() == endTime.toEpochMilli() }
              .collect()
          }
          trackExtendReservation(true, duration)
        } catch (e: TimeoutCancellationException) {
          // TODO(b/277240160): Add correct failure reason here as well as below
          trackExtendReservation(false, duration, FailureReason.UNKNOWN_FAILURE)
          throw DeviceActionException(
            "Reservation not extended within ${EXTENSION_TIMEOUT.seconds} seconds"
          )
        } catch (e: CancellationException) {
          trackExtendReservation(false, duration, FailureReason.UNKNOWN_FAILURE)
          throw e
        } catch (e: Exception) {
          trackExtendReservation(false, duration, FailureReason.UNKNOWN_FAILURE)
          throw DeviceActionException("Could not extend reservation", e)
        }
        return state.reservation?.endTime
          ?: throw DeviceActionException("Extended reservation end time not available.")
      }

      override suspend fun endReservation() {
        hasUserEndedReservation = true
        connection.endReservation()
        trackEndReservation(true)
      }

      /** [ReservationAction] is enabled through the lifecycle of the device handle. */
      override val presentation: StateFlow<DeviceAction.Presentation> =
        MutableStateFlow(DeviceAction.Presentation("Reserve", AllIcons.Actions.Resume, true))

      private fun trackExtendReservation(
        wasSuccessful: Boolean,
        duration: Duration,
        failReason: FailureReason? = null
      ) =
        DirectAccessUsageTracker.trackExtendReservation(
          wasSuccessful,
          duration,
          reservationName,
          sourceTemplate.deviceInfo.toMetricsDeviceInfo(),
          failReason
        )
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
    val deviceProperties = DirectAccessDeviceProperties.build { readCommonProperties(properties) }
    stateFlow.update { DeviceState.Connected(deviceProperties, device, it.reservation) }
    device.invokeOnDisconnection {
      stateFlow.update {
        DeviceState.Disconnected(deviceProperties, false, it.status, it.reservation)
      }
    }
    return true
  }

  private fun trackEndReservation(wasSuccessful: Boolean) =
    DirectAccessUsageTracker.trackEndReservation(
      wasSuccessful,
      hasUserEndedReservation,
      getTotalReservationTime(),
      connection.averageLatency.toInt(),
      reservationName,
      sourceTemplate.deviceInfo.toMetricsDeviceInfo()
    )

  private fun getTotalReservationTime(): Long {
    val reservationStartTime =
      reservationManager.fetchReservationFlow(reservationName).value.createTime.seconds
    return Instant.now().epochSecond - reservationStartTime
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
    resolution = Resolution(info.screenX, info.screenY)
    density = info.screenDensity
  }
}

fun ReservationState.isClosed() =
  this == ReservationState.ERROR || this == ReservationState.COMPLETE
