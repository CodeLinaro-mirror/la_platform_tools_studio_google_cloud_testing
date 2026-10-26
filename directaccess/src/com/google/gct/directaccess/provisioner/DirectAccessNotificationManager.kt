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
import com.google.services.firebase.directaccess.client.DirectAccessConnection.ConnectionState
import com.google.services.firebase.directaccess.client.deviceAddress
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

  init {
    deviceHandle.scope.launch {
      deviceHandle.stateFlow.collect {
        if (!it.shouldShowDisconnectedNotification()) {
          expireDeviceDisconnectedNotification()
        }
      }
    }
    deviceHandle.scope.launch {
      // The warning is only scheduled while the device is connected. In particular, it is not shown when the reservation is shortened to
      // the grace period after the user disconnects: the connection is closed before that happens.
      deviceHandle.connection.state
        .map { if (it.connection is ConnectionState.Connected) it.reservation.expireTime.seconds else null }
        .distinctUntilChanged { a, b ->
          // sometimes we get a small update to the expiration time soon after a reservation starts
          a == b || (a != null && b != null && abs(a - b) < 60)
        }
        .mapLatest { endTime ->
          expireReservationExpiringNotification()
          if (endTime == null) return@mapLatest
          // Shows the warning right away when connecting to a reservation that ends in less than RESERVATION_EXPIRING_SECONDS.
          delay((endTime - Instant.now().epochSecond - RESERVATION_EXPIRING_SECONDS).seconds)
          showReservationExpiringNotification()
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

  /**
   * Runs [block] on the EDT, including while a modal dialog is shown. All notification state and UI is only accessed on the EDT, which
   * serializes it with the UI callbacks that also access it.
   */
  private suspend fun <T> onEdt(block: () -> T): T = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { block() }

  private suspend fun showReservationExpiringNotification() = onEdt {
    if (!project.isDisposed) reservationExpiringNotification.show()
  }

  suspend fun showDeviceDisconnectedNotification(reservationExpireTime: Long?) {
    val deviceName = deviceHandle.sourceTemplate.properties.title
    val message =
      reservationExpireTime?.let {
        val phrase = getDeviceDisconnectedNotificationPhrase(it) ?: return
        getDeviceDisconnectedNotificationMessage(deviceName, phrase)
      } ?: ""
    onEdt {
      if (project.isDisposed) return@onEdt
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

  private suspend fun expireDeviceDisconnectedNotification() = onEdt {
    deviceDisconnectedNotification?.expire()
    deviceDisconnectedNotification = null
  }

  private suspend fun expireReservationExpiringNotification() = onEdt { reservationExpiringNotification.expire() }

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

    /** [DevicePanel] that [bannerNotification] was added to, if any */
    private var attachedPanel: DevicePanel<*>? = null

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
        // Device is visible. Show the banner notification in RDW panel, replacing the balloon
        // notification if it is showing (e.g. it was shown before the device panel was selected).
        isDeviceVisible -> {
          dismissBalloon()
          // Reuse the existing banner so that repeated calls (e.g. each time RDW is shown) don't
          // stack multiple banners in the panel.
          val banner = bannerNotification ?: createEditorNotificationPanel().also { bannerNotification = it }
          attachBanner(banner)
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
                      // Not when the balloon was dismissed in favor of the banner.
                      if (balloonNotification === this@apply) expire()
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
        attachedPanel?.removeNotification(it)
        attachedPanel = null
        bannerNotification = null
      }
      dismissBalloon()
      notificationVisible = false
    }

    private fun createEditorNotificationPanel() =
      EditorNotificationPanel().apply {
        text = RESERVATION_EXPIRING_BANNER_TITLE
        icon(deviceHandle.icon)
        createActionLabel("Extend 15 mins") { extendReservation() }
        setCloseAction { expire() }
      }

    /**
     * Adds [banner] to the current [devicePanel] unless it was already added there. If the banner was added to a different panel (e.g. one
     * from before the device was reconnected), it is moved.
     */
    private fun attachBanner(banner: EditorNotificationPanel) {
      val panel = devicePanel ?: return
      if (panel === attachedPanel) return
      attachedPanel?.removeNotification(banner)
      panel.addNotification(banner)
      attachedPanel = panel
    }

    private fun createBalloonNotification(): Notification {
      val notification =
        notificationGroup
          .createNotification(
            RESERVATION_EXPIRING_BANNER_TITLE,
            "${deviceHandle.sourceTemplate.properties.title} will disconnect in less than 5 mins. Extend reservation to continue access to the device.",
            NotificationType.INFORMATION,
          )
          .addAction(NotificationAction.createExpiring("Extend 15 mins") { _, _ -> extendReservation() })
          .setIcon(deviceHandle.icon)
      // Not when the balloon was dismissed in favor of the banner.
      return notification.whenExpired { if (balloonNotification === notification) expire() }
    }

    /** Expires the balloon notification, without removing the banner notification that replaces it. */
    private fun dismissBalloon() {
      val balloon = balloonNotification ?: return
      balloonNotification = null
      balloon.expire()
    }

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
