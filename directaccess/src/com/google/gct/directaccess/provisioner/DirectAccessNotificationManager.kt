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

import com.android.sdklib.deviceprovisioner.DeviceState
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages [Notification]s linked to a [DirectAccessDeviceHandle].
 *
 * All notification should expire automatically when related actions are triggered.
 */
private val notificationGroup: NotificationGroup
  get() = NotificationGroup.findRegisteredGroup("Direct Access")!!

private val RESERVATION_EXPIRING_SECONDS = TimeUnit.MINUTES.toSeconds(5)

@OptIn(ExperimentalCoroutinesApi::class)
class DirectAccessNotificationManager(
  private val project: Project,
  private val deviceHandle: DirectAccessDeviceHandle
) {
  private var deviceDisconnectedNotification: Notification? = null
  private var reservationExpiringNotification: Notification? = null

  private val mutex = Mutex()

  init {
    deviceHandle.scope.launch {
      deviceHandle.stateFlow
        .mapNotNull {
          if (!it.shouldShowDisconnectedNotification()) {
            expireDeviceDisconnectedNotification()
          }
          it.reservation?.endTime?.epochSecond
        }
        .distinctUntilChanged()
        .mapLatest {
          expireReservationExpiringNotification()
          val timeLeft = it - Instant.now().epochSecond
          // Do not show expiring notifications when a device is in grace period, which is implied
          // here as the durations of devices in grace period are less than
          // RESERVATION_EXPIRING_SECONDS.
          // TODO (b/290674109): access grace period status from device handle.
          if (timeLeft > RESERVATION_EXPIRING_SECONDS) {
            delay(TimeUnit.SECONDS.toMillis(timeLeft - RESERVATION_EXPIRING_SECONDS))
            showReservationExpiringNotification()
          }
        }
        .collect()
    }
    deviceHandle.scope.coroutineContext.job.invokeOnCompletion {
      CoroutineScope(EmptyCoroutineContext).launch {
        expireDeviceDisconnectedNotification()
        expireReservationExpiringNotification()
      }
    }
  }

  private suspend fun showReservationExpiringNotification() =
    mutex.withLock {
      // Do not show reservation expiring notification if the device is in grace period.
      if (deviceDisconnectedNotification?.isExpired == false) return
      val deviceName = deviceHandle.sourceTemplate.properties.title
      reservationExpiringNotification =
        notificationGroup
          .createNotification(
            "Reservation ending in 5 mins",
            "$deviceName will disconnect in 5 mins. Extend reservation to continue access to the device.",
            NotificationType.INFORMATION
          )
          .addAction(
            NotificationAction.createExpiring("Extend 30 mins") { _, _ ->
              deviceHandle.scope.launch {
                deviceHandle.reservationAction.reserve(Duration.ofMinutes(30))
              }
            }
          )
          .apply { notify(project) }
    }

  suspend fun showDeviceDisconnectedNotification(reservationExpireTime: Long?) =
    mutex.withLock {
      val deviceName = deviceHandle.sourceTemplate.properties.title
      val message =
        reservationExpireTime?.let {
          val phrase = getDeviceDisconnectedNotificationPhrase(it) ?: return
          getDeviceDisconnectedNotificationMessage(deviceName, phrase)
        }
          ?: ""
      deviceDisconnectedNotification =
        notificationGroup
          .createNotification(
            "$deviceName on Firebase stopped",
            message,
            NotificationType.INFORMATION
          )
          .addAction(
            NotificationAction.createExpiring("Reconnect to Device") { _, _ ->
              deviceHandle.scope.launch { deviceHandle.activationAction.activate() }
            }
          )
          .addAction(
            NotificationAction.createExpiring("Force check-in device") { _, _ ->
              deviceHandle.scope.launch { deviceHandle.reservationAction.endReservation() }
            }
          )
          .takeIf { deviceHandle.stateFlow.value.shouldShowDisconnectedNotification() }
          ?.apply { notify(project) }
    }

  private fun getDeviceDisconnectedNotificationPhrase(reservationExpireTime: Long): String? {
    val timeRemaining =
      Instant.now().until(Instant.ofEpochSecond(reservationExpireTime), ChronoUnit.SECONDS)
    return when {
      timeRemaining <= 0 -> null
      timeRemaining < 60 -> "less than 1 minute"
      timeRemaining < 120 -> "up to 2 minutes"
      else -> "up to ${timeRemaining.div(60F).roundToInt()} minutes"
    }
  }

  private fun getDeviceDisconnectedNotificationMessage(deviceName: String, phrase: String) =
    "You can reconnect to the same $deviceName for $phrase before the device is wiped"

  private suspend fun expireDeviceDisconnectedNotification() =
    mutex.withLock {
      deviceDisconnectedNotification?.expire()
      deviceDisconnectedNotification = null
    }

  private suspend fun expireReservationExpiringNotification() =
    mutex.withLock {
      reservationExpiringNotification?.expire()
      reservationExpiringNotification = null
    }

  private fun DeviceState.shouldShowDisconnectedNotification() =
    this is DeviceState.Disconnected && !isTransitioning && reservation?.state?.isClosed() == false
}
