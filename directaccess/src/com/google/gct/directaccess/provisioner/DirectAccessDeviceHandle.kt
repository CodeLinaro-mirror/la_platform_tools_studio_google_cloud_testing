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
import com.android.adblib.serialNumber
import com.android.sdklib.deviceprovisioner.ActivationAction
import com.android.sdklib.deviceprovisioner.DeactivationAction
import com.android.sdklib.deviceprovisioner.DeviceAction
import com.android.sdklib.deviceprovisioner.DeviceActionException
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceId
import com.android.sdklib.deviceprovisioner.DeviceProperties
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.sdklib.deviceprovisioner.ReservationAction
import com.android.sdklib.deviceprovisioner.ReservationState
import com.android.sdklib.deviceprovisioner.Resolution
import com.android.sdklib.deviceprovisioner.asMap
import com.android.sdklib.deviceprovisioner.awaitDisconnection
import com.android.tools.adbbridge.Reservation
import com.android.tools.adbbridge.Reservation.SessionState
import com.android.tools.idea.run.DeviceHeadsUpListener
import com.android.tools.idea.streaming.RUNNING_DEVICES_TOOL_WINDOW_ID
import com.android.tools.idea.streaming.core.StreamingDevicePanel
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.analytics.DirectAccessFeatureSurveys
import com.google.gct.directaccess.analytics.DirectAccessUsageTracker
import com.google.gct.directaccess.directAccessCloudProjectManager
import com.google.gct.login2.GoogleLoginService
import com.google.gct.login2.LoginFeature
import com.google.services.firebase.FirebaseLoginFeature
import com.google.services.firebase.directaccess.client.DirectAccessConnection
import com.google.services.firebase.directaccess.client.DirectAccessConnection.ConnectionState
import com.google.services.firebase.directaccess.client.DirectAccessConnection.StateReason
import com.google.services.firebase.directaccess.client.deviceAddress
import com.google.services.firebase.directaccess.client.isClosed
import com.google.services.firebase.directaccess.client.waitUntilActive
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.EndReservationDetails.EndReservationType
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.FailureReason
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.platform.util.progress.reportProgress
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import icons.StudioIcons
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import kotlinx.coroutines.CoroutineExceptionHandler
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
// TODO - Remove once Reservation has a field for max duration
// go/da-table-row/9pyBLLoGz3pexgO3l2U4Ca
private val MAX_SESSION_DURATION = Duration.ofHours(3)

class DirectAccessDeviceHandle(
  private val project: Project,
  override val scope: CoroutineScope,
  override val sourceTemplate: DirectAccessDeviceTemplate,
  initialState: DeviceState,
  private val reservationName: String,
) : DeviceHandle {

  override val id = DeviceId(PLUGIN_ID, false, "reservation=${reservationName}")

  private val reservationManager =
    project.directAccessCloudProjectManager?.reservationManager
      ?: throw RuntimeException("Reservation manager not available.")

  val connection: DirectAccessConnection =
    project.directAccessCloudProjectManager?.connectionManager?.create(reservationName)
      ?: throw RuntimeException("Failed to get connection.")

  private val connectionStateReason: StateReason
    get() = connection.state.value.connection.reason

  val icon: Icon
    get() = sourceTemplate.icon

  private val reservationFlow = reservationManager.fetchReservationFlow(reservationName)
  override val stateFlow =
    MutableStateFlow(initialState.withReservation(mapReservation(reservationFlow.value)))
  // [notificationManager] collects [stateFlow] and needs to be initiated after.
  val notificationManager = DirectAccessNotificationManager(project, this)

  /** Tracks number of connection attempts to the device */
  private var connectionAttempts = 0
  /** Tracks user involvement in disconnecting device */
  private var hasUserDisconnectedDevice = false
  /** Tracks device force check in */
  private var hasUserForceCheckedInDevice = false
  /** Tracks [Reservation] activated */
  private var hasReservationActivated = false
  /** Tracks reservation expired shown */
  private var hasShownReservationExpiredNotification = false
  /** [ContentManagerListener] that listens to panel changes in RDW */
  private var rdwPanelChangeListener: ContentManagerListener? = null

  init {
    scope.launch {
      reservationFlow.map(this@DirectAccessDeviceHandle::mapReservation).collect { reservation ->
        stateFlow.update { state -> state.withReservation(reservation) }
      }
    }

    scope
      .launch { reservationFlow.takeWhile { !it.sessionState.isClosed() }.collect() }
      .invokeOnCompletion { throwable ->
        val sessionState = reservationFlow.value.sessionState
        if (!shouldTrackEndReservation(throwable, sessionState)) {
          return@invokeOnCompletion
        }
        if (hasReservationActivated && state.connectedDevice != null) {
          showReservationExpiredNotification()
        }

        when (sessionState) {
          SessionState.EXPIRED -> trackEndReservation(true, EndReservationType.EXPIRE)
          SessionState.FINISHED -> trackEndReservation(true, EndReservationType.FORCE_CHECK_IN)
          SessionState.ERROR ->
            trackEndReservation(false, EndReservationType.ERROR, FailureReason.UNKNOWN_FAILURE)
          SessionState.UNAVAILABLE ->
            trackEndReservation(
              false,
              EndReservationType.ERROR,
              FailureReason.FAILED_TO_ALLOCATE_DEVICE,
            )
          else ->
            trackEndReservation(false, EndReservationType.UNKNOWN, FailureReason.UNKNOWN_FAILURE)
        }
      }
  }

  /**
   * End reservation should be tracked when the session is closed and the throwable is null or
   * [CancellationException] It should not be tracked if the session is not closed.
   */
  private fun shouldTrackEndReservation(throwable: Throwable?, sessionState: SessionState) =
    if (sessionState.isClosed()) {
      throwable?.let { it is CancellationException } ?: true
    } else {
      false
    }

  /** Map Reservation to its device provisioner format. */
  private fun mapReservation(
    reservation: Reservation
  ): com.android.sdklib.deviceprovisioner.Reservation {
    val reservationState =
      when (reservation.sessionState) {
        SessionState.REQUESTED,
        SessionState.PENDING -> ReservationState.PENDING
        SessionState.ACTIVE -> ReservationState.ACTIVE
        SessionState.EXPIRED,
        SessionState.FINISHED -> ReservationState.COMPLETE
        else -> ReservationState.ERROR
      }
    if (reservationState == ReservationState.ACTIVE) {
      hasReservationActivated = true
    }
    return com.android.sdklib.deviceprovisioner.Reservation(
      reservationState,
      "",
      Instant.ofEpochSecond(reservation.createTime.seconds),
      Instant.ofEpochSecond(reservation.expireTime.seconds),
      MAX_SESSION_DURATION,
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

  private fun showReservationExpiredNotification() =
    synchronized(this) {
      if (hasShownReservationExpiredNotification) return@synchronized
      notificationManager.showReservationExpiredNotification()
      hasShownReservationExpiredNotification = true
    }

  override val activationAction =
    object : ActivationAction {
      /** Starts connection to the remote device. */
      override suspend fun activate() {
        reportProgress { progressReporter ->
          var exception: Throwable? = null
          val job =
            scope.launch(CoroutineExceptionHandler { _, throwable -> exception = throwable }) {
              // Increment connectionAttempts outside update so that it is not incremented twice
              // in case update is run twice
              connectionAttempts++
              stateFlow.update {
                val reservation =
                  it.reservation
                    // This state should not be possible since the reservation is mapped on every
                    // update
                    ?: throw IllegalStateException(
                        "Reservation required to activate Device Streaming."
                      )
                      .also {
                        // TODO(b/277240160): Add correct failure reason
                        trackConnectMetrics(false, failureReason = FailureReason.UNKNOWN_FAILURE)
                      }
                // Properties obtained from device after the first connection are lost here.
                // The properties will be read again when device is claimed by the plugin.
                // This is fine since the connection event does not require those properties
                // to be from the device. Also, the initial event does not have those properties
                val properties = sourceTemplate.deviceInfo.toDeviceProperties(connectionAttempts)
                DeviceState.Disconnected(properties)
                  .copy(isTransitioning = true)
                  .withReservation(reservation)
              }
              scope.trackConnectTime()
              try {
                connection.connect { status: String, block: suspend CoroutineScope.() -> Unit ->
                  progressReporter.indeterminateStep(status, block)
                }
              } catch (e: Exception) {
                stateFlow.update {
                  val reservation =
                    it.reservation
                      // This state should not be possible since the reservation is mapped on every
                      // update
                      ?: throw IllegalStateException("Reservation required to connect to device.")
                  DeviceState.Disconnected(it.properties).withReservation(reservation)
                }
                val activationCancelled = connectionStateReason == StateReason.USER_INITIATED
                val failureReason =
                  if (activationCancelled) {
                    // User clicked stop before the session could activate and device could connect.
                    FailureReason.DISCONNECT_BEFORE_CONNECTED
                  } else {
                    FailureReason.UNKNOWN_FAILURE
                  }
                trackConnectMetrics(false, failureReason = failureReason)
                if (activationCancelled || e is CancellationException) {
                  // propagate the CancellationException up
                  exception = e
                  throw CancellationException("Device activation was cancelled")
                } else {
                  throw DeviceActionException("Failed to connect to device. Please try again.", e)
                }
              }
            }
          try {
            job.join()
            exception?.let { throw it }
          } catch (e: CancellationException) {
            job.cancel(e)
            throw e
          }
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
            connection.state.takeWhile { it.connection !is ConnectionState.Connected }.collect()
          }
          trackConnectMetrics(true, System.currentTimeMillis() - connectStartTime)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // TODO(b/277240160): Add correct failure reason
          trackConnectMetrics(false, failureReason = FailureReason.UNKNOWN_FAILURE)
        }
      }
    }

  override val deactivationAction =
    object : DeactivationAction {
      override suspend fun deactivate() =
        withContext(scope.coroutineContext + NonCancellable) {
          val reservationFlow = reservationManager.fetchReservationFlow(reservationName)
          // Check here if notification is needed. endReservation might change the sessionState
          val shouldShowNotification = reservationFlow.value.sessionState == SessionState.ACTIVE
          hasUserDisconnectedDevice = true
          try {
            connection.closeConnection(StateReason.USER_INITIATED)
          } catch (e: Exception) {
            // TODO(b/277240160): Add correct failure reason
            trackDisconnectMetric(false, FailureReason.UNKNOWN_FAILURE)
            throw DeviceActionException("Failed to disconnect from device. Please try again.", e)
          }
          stateFlow.update {
            when (it) {
              // Reset isTransitioning to false if the connection is not established yet.
              is DeviceState.Disconnected -> it.copy(isTransitioning = false)
              // Let it.connectedDevice update the state for disconnection.
              is DeviceState.Connected -> it
            }
          }
          // Reservation enters grace period. Don't track end reservation metric.
          connection.endReservation(withGracePeriod = true)
          if (shouldShowNotification) {
            notificationManager.showDeviceDisconnectedNotification(
              reservationFlow.value.expireTime.seconds
            )
          }
          service<DirectAccessFeatureSurveys>().trackDisconnection()
        }

      private val defaultPresentation =
        DeviceAction.Presentation("Disconnect", StudioIcons.Avd.STOP, true)

      override val presentation = MutableStateFlow(defaultPresentation).asStateFlow()
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
            "Failed to extend reservation within ${EXTENSION_TIMEOUT.seconds} seconds. Please try again."
          )
        } catch (e: CancellationException) {
          trackExtendReservation(false, duration, FailureReason.UNKNOWN_FAILURE)
          throw e
        } catch (e: Exception) {
          trackExtendReservation(false, duration, FailureReason.UNKNOWN_FAILURE)
          throw DeviceActionException("Failed to extend reservation. Please try again.", e)
        }
        return state.reservation?.endTime
          ?: throw DeviceActionException("Extended reservation end time not available.")
      }

      override suspend fun endReservation() =
        withContext(NonCancellable) {
          hasUserForceCheckedInDevice = true
          try {
            connection.endReservation()
          } catch (e: Exception) {
            hasUserForceCheckedInDevice = false
            trackEndReservation(
              false,
              EndReservationType.FORCE_CHECK_IN,
              FailureReason.UNKNOWN_FAILURE,
            )
            throw DeviceActionException("Failed to end reservation. Please try again.", e)
          }
          service<DirectAccessFeatureSurveys>().trackDisconnection()
        }

      /** [ReservationAction] is enabled through the lifecycle of the device handle. */
      override val presentation: StateFlow<DeviceAction.Presentation> =
        MutableStateFlow(DeviceAction.Presentation("Reserve", AllIcons.Actions.Resume, true))
    }

  /**
   * Returns true and changes state to [DeviceState.Connected] if [port] matches the [connection] of
   * handle.
   */
  suspend fun claim(port: Int, device: ConnectedDevice): Boolean {
    if (connection.port != port) {
      return false
    }
    // Show the device tab in running devices window.
    project.messageBus
      .syncPublisher(DeviceHeadsUpListener.TOPIC)
      .userInvolvementRequired(device.deviceInfoFlow.value.serialNumber, project)
    project.messageBus
      .connect(scope)
      .subscribe(
        ToolWindowManagerListener.TOPIC,
        object : ToolWindowManagerListener {
          override fun toolWindowShown(toolWindow: ToolWindow) {
            if (toolWindow.id == RUNNING_DEVICES_TOOL_WINDOW_ID) {
              val devicePanel =
                toolWindow.contentManager.selectedContent?.component as? StreamingDevicePanel
                  ?: return
              devicePanel.maybeShowBannerNotification()
            }
          }
        },
      )
    addContentManagerListener()
    val properties = device.deviceProperties().all().asMap()
    val deviceProperties = buildDirectAccessDeviceProperties {
      resolution = Resolution.readFromDevice(device)
      readCommonProperties(properties)
      // Override model and manufacturer as the info read from device
      // may be different from catalog
      manufacturer = sourceTemplate.properties.manufacturer
      model = sourceTemplate.properties.model
      // We use the debug.firebase.test.lab.session as wear pairing identifier as it should remain
      // constant for the time that the user is using the device. We can not just use the serial
      // number directly since the user might pick the same device in two different sessions,
      // and it might have been wiped out.
      wearPairingId = properties["debug.firebase.test.lab.session"] ?: properties["ro.serialno"]
      populateDeviceInfoProto(
        PLUGIN_ID,
        device.serialNumber,
        properties,
        connectionAttempts.toString(),
      )
      icon = this@DirectAccessDeviceHandle.icon
    }

    stateFlow.update {
      DeviceState.Connected(deviceProperties, device, reservation = it.reservation)
    }
    scope
      .launch {
        var exception: Exception? = null
        try {
          device.awaitDisconnection()
        } catch (e: Exception) {
          exception = e
          throw e
        } finally {
          DirectAccessUsageTracker.getInstance().scope.launch {
            trackDisconnectMetric(true, connectionStateReason.toFailureReason(exception))
          }
        }
      }
      .invokeOnCompletion {
        stateFlow.update {
          DeviceState.Disconnected(deviceProperties, false, it.status, it.reservation)
        }
        if (project.isDisposed) {
          return@invokeOnCompletion
        }
        // Remove content manager listener since device has disconnected
        removeContentManagerListener()

        // Show notification if the device disconnected without user action
        if (!hasUserDisconnectedDevice && !hasUserForceCheckedInDevice) {
          scope.launch {
            notificationManager.showDeviceDisconnectedNotification(
              state.reservation?.endTime?.epochSecond
            )
          }
          if (reservationFlow.value.sessionState.isClosed()) {
            showReservationExpiredNotification()
          }
        }
      }
    service<DirectAccessFeatureSurveys>().trackConnection()
    return true
  }

  private fun StateReason.toFailureReason(throwable: Throwable?): FailureReason? {
    return if (throwable != null) {
      getScopeCancelledReason()
    } else {
      when (this) {
        StateReason.UNKNOWN -> FailureReason.UNKNOWN_FAILURE
        StateReason.INIT -> FailureReason.DISCONNECT_BEFORE_CONNECTED
        StateReason.SESSION_ENDED -> FailureReason.SESSION_ENDED
        StateReason.CONNECTION_FAILED -> FailureReason.CONNECTION_FAILED
        StateReason.ADB_DISCONNECTED -> FailureReason.ADB_DISCONNECTED
        StateReason.LATENCY_DISCONNECT -> FailureReason.LATENCY_DISCONNECT
        StateReason.SCOPE_CANCELLED -> getScopeCancelledReason()
        else -> null
      }
    }
  }

  private fun getScopeCancelledReason() =
    when {
      reservationFlow.value.sessionState.isClosed() -> FailureReason.SESSION_ENDED
      !service<GoogleLoginService>().isLoggedIn(LoginFeature.feature<FirebaseLoginFeature>()) ->
        FailureReason.USER_LOGGED_OUT
      project.service<DirectAccessService>().isProjectClosing -> FailureReason.PROJECT_CLOSING
      else -> FailureReason.UNKNOWN_FAILURE
    }

  private fun trackConnectMetrics(
    wasSuccessful: Boolean,
    timeToConnectMs: Long? = null,
    failureReason: FailureReason? = null,
  ) =
    DirectAccessUsageTracker.getInstance()
      .trackConnectDevice(
        wasSuccessful,
        connectionAttempts >= 2,
        timeToConnectMs,
        reservationName,
        state.properties.deviceInfoProto,
        failureReason,
      )

  private fun trackExtendReservation(
    wasSuccessful: Boolean,
    duration: Duration,
    failReason: FailureReason? = null,
  ) =
    DirectAccessUsageTracker.getInstance()
      .trackExtendReservation(
        wasSuccessful,
        duration,
        reservationName,
        state.properties.deviceInfoProto,
        failReason,
      )

  private fun trackDisconnectMetric(wasSuccessful: Boolean, failureReason: FailureReason? = null) {
    DirectAccessUsageTracker.getInstance()
      .trackDisconnectDevice(
        wasSuccessful,
        hasUserDisconnectedDevice,
        reservationName,
        state.properties.deviceInfoProto,
        failureReason,
      )
    hasUserDisconnectedDevice = false
  }

  private fun trackEndReservation(
    wasSuccessful: Boolean,
    endType: EndReservationType,
    failureReason: FailureReason? = null,
  ) =
    DirectAccessUsageTracker.getInstance()
      .trackEndReservation(
        wasSuccessful,
        endType,
        getTotalReservationTime(),
        connection.latencyMetrics,
        reservationName,
        state.properties.deviceInfoProto,
        failureReason,
      )

  private fun getTotalReservationTime(): Long {
    val reservationStartTime =
      reservationManager.fetchReservationFlow(reservationName).value.createTime.seconds
    return Instant.now().epochSecond - reservationStartTime
  }

  /** Adds content manager listener to RDW for this handle. */
  private fun addContentManagerListener() {
    // Content manager listener already added.
    if (rdwPanelChangeListener != null) return

    getRunningDeviceWindow(project)
      ?.addContentManagerListener(
        object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
              val devicePanel = event.content.component as? StreamingDevicePanel ?: return
              devicePanel.maybeShowBannerNotification()
            }
          }
          .also { rdwPanelChangeListener = it }
      )
  }

  private fun StreamingDevicePanel.maybeShowBannerNotification() {
    if (id.serialNumber == connection.deviceAddress()?.address) {
      invokeLater { notificationManager.onDevicePanelVisibilityChanged() }
    }
  }

  /** Removes content manager listener from RDW */
  private fun removeContentManagerListener() {
    rdwPanelChangeListener?.let {
      getRunningDeviceWindow(project)?.contentManager?.removeContentManagerListener(it)
      rdwPanelChangeListener = null
    }
  }
}

inline fun buildDirectAccessDeviceProperties(block: DeviceProperties.Builder.() -> Unit) =
  DeviceProperties.build {
    // DirectAccess devices are always remote
    isRemote = true
    block()
  }

fun ReservationState.isClosed() =
  this == ReservationState.ERROR || this == ReservationState.COMPLETE

internal fun getRunningDeviceWindow(project: Project) =
  ToolWindowManager.getInstance(project).getToolWindow(RUNNING_DEVICES_TOOL_WINDOW_ID)
