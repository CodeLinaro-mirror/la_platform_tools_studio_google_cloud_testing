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

import com.android.sdklib.AndroidVersion
import com.android.sdklib.deviceprovisioner.DeviceAction
import com.android.sdklib.deviceprovisioner.DeviceActionDisabledException
import com.android.sdklib.deviceprovisioner.DeviceActionException
import com.android.sdklib.deviceprovisioner.DeviceError
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceId
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.sdklib.deviceprovisioner.DeviceTemplate
import com.android.sdklib.deviceprovisioner.Resolution
import com.android.sdklib.deviceprovisioner.TemplateActivationAction
import com.android.sdklib.deviceprovisioner.TemplateState
import com.android.tools.adbbridge.Reservation
import com.android.tools.idea.concurrency.AndroidDispatchers
import com.android.tools.idea.concurrency.createChildScope
import com.android.tools.idea.devicemanager.DeviceType
import com.android.tools.idea.flags.StudioFlags
import com.google.gct.directaccess.analytics.DirectAccessUsageTracker
import com.google.gct.directaccess.directAccessCloudProjectManager
import com.google.services.firebase.directaccess.client.findOrCreateReservation
import com.google.services.firebase.directaccess.client.waitUntilActive
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.FailureReason
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.messages.MessageDialog
import icons.StudioIcons
import java.time.Duration
import javax.swing.Icon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val SHORT_AWAITING_RESERVATION_READY_TIME_LIMIT: Duration = Duration.ofMinutes(1)
val LONG_AWAITING_RESERVATION_READY_TIME_LIMIT: Duration = Duration.ofMinutes(15)

internal const val UNKNOWN_DEVICE_DO_NOT_ASK = "device.streaming.unknown.do.not.ask"
internal const val SPARK_SINGLE_DEVICE_DO_NOT_ASK =
  "device.streaming.spark.single.device.do.not.ask"
internal const val SPARK_MULTI_DEVICE_DO_NOT_ASK = "device.streaming.spark.multi.device.do.not.ask"
internal const val BLAZE_SINGLE_DEVICE_DO_NOT_ASK =
  "device.streaming.blaze.single.device.do.not.ask"
internal const val BLAZE_MULTI_DEVICE_DO_NOT_ASK = "device.streaming.blaze.multi.device.do.not.ask"

private const val BLAZE_PRICE_LINK = "https://d.android.com/r/studio-ui/device-streaming/pricing"
private const val BLAZE_PRICE_LEARN_MORE_LINK = "<a href=$BLAZE_PRICE_LINK>Learn more↗</a>"

class DirectAccessDeviceTemplate(
  private val project: Project,
  private val deviceInfoFlow: StateFlow<DeviceInfo>,
  private val devices: MutableStateFlow<List<DeviceHandle>>,
  private val scope: CoroutineScope,
  private val isAuthenticatorReady: Flow<Boolean>,
) : DeviceTemplate {
  val deviceInfo: DeviceInfo
    get() = deviceInfoFlow.value

  override val id = DeviceId(PLUGIN_ID, true, "model_id=${deviceInfo.key}")

  override val properties = deviceInfo.toDeviceProperties()

  override val stateFlow =
    isAuthenticatorReady
      .combine(deviceInfoFlow) { isReady, deviceInfo ->
        val waitTimeText =
          deviceInfo.deviceAvailabilityEstimateSeconds?.let { waitTimeText(it, "min") }

        TemplateState(
          if (isReady && waitTimeText != null)
            DirectAccessDeviceError(DeviceError.Severity.WARNING, "$waitTimeText")
          else null
        )
      }
      .stateIn(scope, SharingStarted.Eagerly, TemplateState(null))

  private val isActivationStarted = MutableStateFlow(false)

  /** Icon to show for the template and handle */
  val icon: Icon
    get() = properties.icon

  /**
   * Last device handle activated by the template.
   *
   * TODO (b/246171065): resolve potential race condition to support activating multiple devices
   */
  var activeDevice: DirectAccessDeviceHandle? = null
    private set(device) {
      field?.let {
        devices.update { list -> list - it }
        it.scope.cancel()
        isActivationStarted.value = false
      }
      field = device
      if (device != null) {
        devices.update { list -> list + device }
        device.scope.launch {
          device.stateFlow.collect {
            if (it.reservation?.state?.isClosed() == true) {
              field = null
              isActivationStarted.value = false
              devices.update { list -> list - device }
              device.scope.cancel()
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
       * Creates a [DirectAccessDeviceHandle] and starts a connection to it.
       *
       * The returned device handle prioritizes connecting to an existing reservation over
       * requesting a new one. This method is disabled when a device handle is active or being
       * created. At most one device is available for each template.
       *
       * TODO (b/246171065): activating multiple devices.
       */
      override suspend fun activate(duration: Duration?): DeviceHandle {
        // Disable further activate actions to avoid multiple devices.
        if (!isActivationStarted.compareAndSet(expect = false, update = true)) {
          throw DeviceActionDisabledException(this)
        }
        confirmWaitingTime()
        if (!confirmUsageMinutes()) {
          isActivationStarted.value = false
          throw CancellationException("User declined quota usage")
        }

        val reservationName =
          try {
            findOrCreateReservation()
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            isActivationStarted.value = false
            if (e.localizedMessage.contains("RESOURCE_EXHAUSTED")) {
              throw DeviceActionException(
                "All Spark plan minutes for the current period have been used. " +
                  "Upgrade to a Blaze plan to immediately continue using this service.",
                e,
              )
            }
            throw DeviceActionException("Failed to reserve a device. Please try again.", e)
          }

        try {
          return createDeviceHandle(reservationName).also { it.activationAction?.activate() }
        } catch (e: CancellationException) {
          isActivationStarted.value = false
          throw e
        } catch (e: DeviceActionException) {
          isActivationStarted.value = false
          // Re-throw exception to propagate the message present in the exception
          throw e
        } catch (e: Exception) {
          isActivationStarted.value = false
          throw DeviceActionException("Failed to connect to device. Please try again.", e)
        }
      }

      private suspend fun confirmUsageMinutes(): Boolean {
        // Don't prompt if monthly billing is not enabled
        if (!StudioFlags.DIRECT_ACCESS_MONTHLY_QUOTA.get()) {
          return true
        }
        val billingStatus = project.directAccessCloudProjectManager?.isBillingEnabledFlow?.value
        val isMultiDevice = devices.value.filterIsInstance<DirectAccessDeviceHandle>().isNotEmpty()
        val persistenceKey = getPersistenceKeyForDoNoAsk(billingStatus, isMultiDevice)
        val (title, message) = getDialogTitleAndMessage(billingStatus)

        return PropertiesComponent.getInstance(project).getBoolean(persistenceKey, false) ||
          withContext(AndroidDispatchers.uiThread) {
            MessageDialogBuilder.yesNo(title, message)
              .doNotAsk(
                object : DoNotAskOption.Adapter() {
                  override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                    if (exitCode == MessageDialog.OK_EXIT_CODE) {
                      PropertiesComponent.getInstance(project).setValue(persistenceKey, isSelected)
                    }
                  }
                }
              )
              .ask(project)
          }
      }

      private fun getPersistenceKeyForDoNoAsk(billingStatus: Boolean?, isMultiDevice: Boolean) =
        when (billingStatus) {
          null -> UNKNOWN_DEVICE_DO_NOT_ASK
          false ->
            if (isMultiDevice) SPARK_MULTI_DEVICE_DO_NOT_ASK else SPARK_SINGLE_DEVICE_DO_NOT_ASK
          true ->
            if (isMultiDevice) BLAZE_MULTI_DEVICE_DO_NOT_ASK else BLAZE_SINGLE_DEVICE_DO_NOT_ASK
        }

      private fun getDialogTitleAndMessage(billingStatus: Boolean?): Pair<String, String> {
        val devices = devices.value.filterIsInstance<DirectAccessDeviceHandle>()

        val defaultReservationMinutes = 15
        return if (devices.isEmpty()) {
          when (billingStatus) {
            null ->
              Pair(
                "Connect to ${properties.title}",
                "Devices are reserved for $defaultReservationMinutes minutes. Unused minutes will be returned. " +
                  "If your project is a Blaze plan you may incur charges. $BLAZE_PRICE_LEARN_MORE_LINK",
              )
            false ->
              Pair(
                "Connect to ${properties.title}",
                "Devices are reserved for $defaultReservationMinutes minutes and count toward your Spark Plan free minutes. When you end your session unused time is refunded.",
              )
            true ->
              Pair(
                "Connect to ${properties.title}",
                "You are currently using a Firebase project on the Blaze plan. " +
                  "This session may incur billed usage. $BLAZE_PRICE_LEARN_MORE_LINK",
              )
          }
        } else {
          when (billingStatus) {
            null ->
              Pair(
                "Reserving Multiple Streaming Devices",
                "Devices are reserved for $defaultReservationMinutes minutes. Unused minutes will be returned. " +
                  "If your project is a Blaze plan you may incur charges. $BLAZE_PRICE_LEARN_MORE_LINK",
              )
            false ->
              Pair(
                "Reserving Multiple Streaming Devices",
                "You have another streaming device reserved. The new device will be reserved and count towards your Spark Plan free minutes.",
              )
            true ->
              Pair(
                "Reserving Multiple Streaming Devices",
                "You have another device streaming session. You are currently using a Firebase project on the Blaze plan. " +
                  "This session may incur billed usage. $BLAZE_PRICE_LEARN_MORE_LINK",
              )
          }
        }
      }

      private suspend fun confirmWaitingTime() {
        deviceInfo.deviceAvailabilityEstimateSeconds
          ?.let { seconds -> waitTimeText(seconds, "minutes") }
          ?.let { waitingTimeText ->
            val title = "Reserve ${properties.title}"
            val message =
              "The ${properties.title} will be available in $waitingTimeText.\n" +
                "You will not be billed for this duration. $BLAZE_PRICE_LEARN_MORE_LINK"
            val result =
              withContext(AndroidDispatchers.uiThread) {
                Messages.showOkCancelDialog(
                  message,
                  title,
                  "Reserve",
                  "Cancel",
                  Messages.getQuestionIcon(),
                )
              }
            if (result != Messages.OK) {
              isActivationStarted.value = false
              throw CancellationException("Device reservation cancelled.")
            }
          }
      }

      private fun findOrCreateReservation(): String {
        val reservationManager =
          project.directAccessCloudProjectManager?.reservationManager
            ?: throw RuntimeException("Unable to access ReservationManager.")

        val (reservationName, startTime) =
          try {
            reservationManager.findOrCreateReservation(
              deviceInfo.codename,
              deviceInfo.api.toString(),
            )
          } catch (e: Exception) {
            // TODO(b/277240160): Add correct failure reason
            trackReserveDevice(false, failureReason = FailureReason.UNKNOWN_FAILURE)
            throw e
          }
        if (startTime != 0L) {
          scope.logReserveMetricWhenReservationActive(
            reservationManager.fetchReservationFlow(reservationName),
            startTime,
          )
        }
        scope.launch {
          project.directAccessCloudProjectManager?.reservationListFlowWithException?.refresh()
        }
        return reservationName
      }

      private val defaultPresentation =
        DeviceAction.Presentation("Acquire", StudioIcons.Avd.RUN, false)

      override val presentation: StateFlow<DeviceAction.Presentation> =
        isActivationStarted
          .combine(isAuthenticatorReady) { started, authenticatorReady ->
            !started && authenticatorReady
          }
          .combine(deviceInfoFlow) { enabled, deviceInfo ->
            // TODO(b/314857500): Improve user experience with null
            // deviceAvailabilityEstimateSeconds.
            val icon =
              if (
                deviceInfo.deviceAvailabilityEstimateSeconds?.let {
                  it >= SHORT_AWAITING_RESERVATION_READY_TIME_LIMIT.seconds
                } == true
              )
                StudioIcons.Avd.START_RESERVATION
              else StudioIcons.Avd.RUN
            defaultPresentation.copy(
              icon = icon,
              enabled = enabled,
              detail =
                if (enabled) null
                else "Device unavailable: click the Firebase action to address issues",
            )
          }
          .stateIn(scope, SharingStarted.Eagerly, defaultPresentation)
    }

  override val editAction = null

  /**
   * Creates a [DirectAccessDeviceHandle] with Disconnected state for [reservationName] if one is
   * not present already.
   *
   * The returned device handle prioritizes connecting to an existing reservation over requesting a
   * new one. This method is disabled when a device handle is active or being created. At most one
   * device is available for each template.
   *
   * TODO (b/246171065): activating multiple devices.
   */
  fun createDeviceHandleIfAbsent(reservationName: String): DeviceHandle? {
    if (isActivationStarted.compareAndSet(expect = false, update = true)) {
      try {
        return createDeviceHandle(reservationName)
      } catch (e: Exception) {
        isActivationStarted.value = false
        throw e
      }
    }
    return null
  }

  /**
   * Creates a [DirectAccessDeviceHandle] for the given [reservationName].
   *
   * Reservation corresponding to [reservationName] can be a new reservation requested by the user
   * that is inactive, or it can be an active reservation created elsewhere.
   */
  private fun createDeviceHandle(reservationName: String): DeviceHandle {
    val deviceScope = scope.createChildScope(isSupervisor = true)
    // Notify provisioner plugin of the new device.
    return DirectAccessDeviceHandle(
        project,
        deviceScope,
        this@DirectAccessDeviceTemplate,
        DeviceState.Disconnected(properties),
        reservationName,
      )
      .also { activeDevice = it }
  }

  init {
    scope.launch {
      isAuthenticatorReady.distinctUntilChanged().collect { isReady ->
        if (!isReady) {
          activeDevice = null
        }
      }
    }
  }

  private fun CoroutineScope.logReserveMetricWhenReservationActive(
    reservationFlow: StateFlow<Reservation>,
    reserveStartTime: Long,
  ) = launch {
    reservationFlow.waitUntilActive()
    trackReserveDevice(
      true,
      System.currentTimeMillis() - reserveStartTime,
      reservationFlow.value.name,
    )
  }

  private fun trackReserveDevice(
    wasSuccessful: Boolean,
    timeToReserve: Long? = null,
    reservationName: String? = null,
    failureReason: FailureReason? = null,
  ) {
    DirectAccessUsageTracker.getInstance()
      .trackReserveDevice(
        wasSuccessful,
        timeToReserve,
        reservationName,
        properties.deviceInfoProto,
        failureReason,
      )
  }

  private fun waitTimeText(seconds: Long, minuteSuffix: String): String? =
    "${LONG_AWAITING_RESERVATION_READY_TIME_LIMIT.toMinutes()} $minuteSuffix"
      .let { suffix ->
        when {
          seconds < SHORT_AWAITING_RESERVATION_READY_TIME_LIMIT.seconds -> null
          seconds < LONG_AWAITING_RESERVATION_READY_TIME_LIMIT.seconds -> "less than $suffix"
          else -> "more than $suffix"
        }
      }

  private data class DirectAccessDeviceError(
    override val severity: DeviceError.Severity = DeviceError.Severity.WARNING,
    override val message: String,
  ) : DeviceError
}

internal fun DeviceInfo.toDeviceProperties(connectionCount: Int = 0): DirectAccessDeviceProperties {
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
    icon = info.icon
    populateDeviceInfoProto(PLUGIN_ID, null, emptyMap(), connectionCount.toString())
  }
}
