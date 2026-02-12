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
import com.android.tools.idea.deviceprovisioner.launchCatchingDeviceActionException
import com.android.tools.idea.deviceprovisioner.runCatchingDeviceActionException
import com.android.tools.idea.streaming.core.DevicePanel
import com.google.services.firebase.directaccess.client.deviceAddress
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.EditorNotificationPanel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
  get() = service<NotificationGroupManager>().getNotificationGroup("Direct Access")

/** Creates sticky notifications that require the user to interact with it inorder to dismiss it */
private val stickyNotificationGroup: NotificationGroup
  get() = service<NotificationGroupManager>().getNotificationGroup("Direct Access Sticky")

private val RESERVATION_EXPIRING_SECONDS = TimeUnit.MINUTES.toSeconds(5)

const val RESERVATION_EXPIRING_BANNER_TITLE = "Reservation ending in less than 5 mins"

@OptIn(ExperimentalCoroutinesApi::class)
class DirectAccessNotificationManager(private val project: Project, private val deviceHandle: DirectAccessDeviceHandle) {
  private var deviceDisconnectedNotification: Notification? = null
  private val reservationExpiringNotification = ReservationExpiringNotification(project, deviceHandle)

  private val deviceName: String
    get() = deviceHandle.sourceTemplate.properties.title

  private val formattedEndTime: String
    get() =
      DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(deviceHandle.state.reservation?.endTime)

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
      reservationExpiringNotification.show()
    }

  suspend fun showDeviceDisconnectedNotification(reservationExpireTime: Long?) =
    mutex.withLock {
      val deviceName = deviceHandle.sourceTemplate.properties.title
      val message =
        reservationExpireTime?.let {
          val phrase = getDeviceDisconnectedNotificationPhrase(it) ?: return
          getDeviceDisconnectedNotificationMessage(deviceName, phrase)
        } ?: ""
      deviceDisconnectedNotification =
        notificationGroup
          .createNotification("$deviceName on Firebase stopped", message, NotificationType.INFORMATION)
          .addAction(
            NotificationAction.createExpiring("Reconnect to Device") { _, _ ->
              deviceHandle.launchCatchingDeviceActionException(project = project) { activationAction.activate() }
            }
          )
          .addAction(
            NotificationAction.createExpiring("Return and erase device") { _, _ ->
              deviceHandle.launchCatchingDeviceActionException(project = project) { reservationAction.endReservation() }
            }
          )
          .setIcon(deviceHandle.icon)
          .takeIf { deviceHandle.stateFlow.value.shouldShowDisconnectedNotification() }
          ?.apply { notify(project) }
    }

  /** Handles panel visibility changes */
  fun onDevicePanelVisibilityChanged() {
    if (reservationExpiringNotification.notificationVisible) reservationExpiringNotification.show()
  }

  fun showReservationExpiredNotification() =
    stickyNotificationGroup
      .createNotification(
        "$deviceName session ended",
        "Your device session ended at $formattedEndTime. The device was returned and erased.",
        NotificationType.INFORMATION,
      )
      .setIcon(deviceHandle.icon)
      .addAction(
        NotificationAction.createSimpleExpiring("Reserve new device") {
          // deviceHandle's scope will be cancelled and cannot be used here.
          CoroutineScope(EmptyCoroutineContext).launch {
            runCatchingDeviceActionException(project, deviceHandle.state.properties.title) {
              deviceHandle.sourceTemplate.activationAction.activate()
            }
          }
        }
      )
      .notify(project)

  fun showReservationLostNotification() =
    stickyNotificationGroup
      .createNotification(
        "$deviceName session lost",
        // TODO (b/353573777): Remove "login again" content once Android Studio could logout
        // automatically.
        "Android Studio can not access session status right now. " + "Please check your network connection and login again.",
        NotificationType.INFORMATION,
      )
      .setIcon(deviceHandle.icon)
      .notify(project)

  private fun getDeviceDisconnectedNotificationPhrase(reservationExpireTime: Long): String? {
    val timeRemaining = Instant.now().until(Instant.ofEpochSecond(reservationExpireTime), ChronoUnit.SECONDS)
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

  private suspend fun expireReservationExpiringNotification() = mutex.withLock { reservationExpiringNotification.expire() }

  private fun DeviceState.shouldShowDisconnectedNotification() =
    this is DeviceState.Disconnected && !isTransitioning && reservation?.state?.isClosed() == false

  /** Show reservation expiring notification depending on user visible content */
  private class ReservationExpiringNotification(private val project: Project, private val deviceHandle: DirectAccessDeviceHandle) {

    /** Panel for this [DirectAccessDeviceHandle] */
    private val devicePanel: DevicePanel<*>?
      get() = getRunningDeviceWindow(project)?.devicePanel

    /** True if the current visible panel in RDW is for this [DirectAccessDeviceHandle]; false otherwise */
    private val isDeviceVisible: Boolean
      get() = devicePanel?.let { it.deviceSerialNumber == getRunningDeviceWindow(project)?.visibleDevicePanel?.deviceSerialNumber } ?: false

    /** [EditorNotificationPanel] that is being shown in RDW */
    private var bannerNotification: EditorNotificationPanel? = null

    /** [Notification] that is being shown in studio when device panel is not visible */
    private var balloonNotification: Notification? = null

    /** Keep track of notification visibility */
    var notificationVisible = false

    /**
     * Shows the notification in appropriate location.
     *
     * Show banner notification if device panel is visible else show balloon notification.
     */
    fun show() {
      when {
        // Device is visible and balloon notification is not showing.
        // Show the banner notification in RDW panel
        isDeviceVisible && balloonNotification == null -> {
          bannerNotification =
            createEditorNotificationPanel().also {
              // Content in the devicePanel may not have been created by the time addNotification is
              // called. Call it in invokeLater to ensure content is created
              invokeLater { devicePanel?.addNotification(it) }
            }
        }
        // Device is not visible and the notification was not shown on the panel
        // so show the balloon notification
        !isDeviceVisible && bannerNotification == null -> {
          if (balloonNotification?.isExpired == false) return
          balloonNotification =
            createBalloonNotification().apply {
              notify(project)
              // notify() calls invokeLater internally to create the balloon
              // Queue an event after that to get the balloon and add a listener to it
              // This listener expires the notification when user clicks on the close icon.
              invokeLater {
                balloon?.addListener(
                  object : JBPopupListener {
                    override fun onClosed(event: LightweightWindowEvent) {
                      super.onClosed(event)
                      expire()
                    }
                  }
                )
              }
            }
        }
        // Device is not visible any longer but banner notification was visible
        // when the device was visible.
        // Cleanup the banner notification from RDW
        !isDeviceVisible && balloonNotification == null -> {
          expire()
        }
        else -> return
      }
      notificationVisible = true
    }

    /** Removes the notifications */
    fun expire() {
      bannerNotification?.let {
        devicePanel?.removeNotification(it)
        bannerNotification = null
      }
      balloonNotification?.let {
        it.expire()
        balloonNotification = null
      }
      notificationVisible = false
    }

    private fun createEditorNotificationPanel() =
      EditorNotificationPanel().apply {
        text = RESERVATION_EXPIRING_BANNER_TITLE
        icon(deviceHandle.icon)
        createActionLabel("Extend 15 mins") { extendReservation() }
        setCloseAction {
          devicePanel?.removeNotification(this)
          expire()
        }
      }

    private fun createBalloonNotification() =
      notificationGroup
        .createNotification(
          RESERVATION_EXPIRING_BANNER_TITLE,
          "${deviceHandle.sourceTemplate.properties.title} will disconnect in less than 5 mins. Extend reservation to continue access to the device.",
          NotificationType.INFORMATION,
        )
        .addAction(NotificationAction.createExpiring("Extend 15 mins") { _, _ -> extendReservation() })
        .setIcon(deviceHandle.icon)
        .whenExpired { expire() }

    /** Extends the reservation and expires notification */
    private fun extendReservation() =
      deviceHandle.launchCatchingDeviceActionException(project = project) {
        reservationAction.reserve(Duration.ofMinutes(15))
        expire()
      }

    /** Gets current visible panel in RDW */
    private val ToolWindow.visibleDevicePanel: DevicePanel<*>?
      get() = contentManager.selectedContent?.component as? DevicePanel<*>

    /** Gets the panel for this [DirectAccessDeviceHandle] from RDW */
    private val ToolWindow.devicePanel: DevicePanel<*>?
      get() =
        contentManager.contents
          .mapNotNull { it.component as? DevicePanel<*> }
          .firstOrNull { it.deviceSerialNumber == deviceHandle.connection.deviceAddress()?.address }
  }
}
