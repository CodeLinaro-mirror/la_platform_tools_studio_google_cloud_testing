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

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Manages [Notification]s linked to a [DirectAccessDeviceHandle].
 *
 * All notification should expire automatically when related actions are triggered.
 */
private val notificationGroup: NotificationGroup
  get() = NotificationGroup.findRegisteredGroup("Direct Access")!!

class DirectAccessNotificationManager(
  private val project: Project,
  private val deviceHandle: DirectAccessDeviceHandle
) {
  private var deviceDisconnectedNotification: Notification? = null

  fun showDeviceDisconnectedNotification(reservationExpireTime: Long?) {
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
        .apply { notify(project) }
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

  /** Expires all [Notification]s linked to the [DirectAccessDeviceHandle]. */
  fun expire() {
    deviceDisconnectedNotification?.expire()
  }
}
