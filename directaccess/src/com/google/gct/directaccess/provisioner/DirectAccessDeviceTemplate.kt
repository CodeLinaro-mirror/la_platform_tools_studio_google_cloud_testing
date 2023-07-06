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
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.sdklib.deviceprovisioner.DeviceTemplate
import com.android.sdklib.deviceprovisioner.Resolution
import com.android.sdklib.deviceprovisioner.TemplateActivationAction
import com.android.tools.adbbridge.Reservation
import com.android.tools.idea.concurrency.createChildScope
import com.android.tools.idea.devicemanager.DeviceType
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.analytics.DirectAccessUsageTracker
import com.google.gct.directaccess.analytics.toMetricsDeviceInfo
import com.google.services.firebase.directaccess.client.findOrCreateReservation
import com.google.services.firebase.directaccess.client.waitUntilActive
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import icons.StudioIcons
import java.time.Duration
import javax.swing.Icon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DirectAccessDeviceTemplate(
  private val project: Project,
  val deviceInfo: DeviceInfo,
  private val devices: MutableStateFlow<List<DeviceHandle>>,
  private val scope: CoroutineScope
) : DeviceTemplate {
  override val properties = deviceInfo.toDeviceProperties()

  private val isActivationEnabled = MutableStateFlow(true)

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
        if (!isActivationEnabled.compareAndSet(expect = true, update = false)) {
          throw DeviceActionDisabledException(this)
        }

        try {
          return createDeviceHandle().also { it.activationAction?.activate() }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          isActivationEnabled.value = true
          throw DeviceActionException("Unable to reserve device.", e)
        }
      }

      private val defaultPresentation =
        DeviceAction.Presentation("Acquire", StudioIcons.Avd.RUN, false)
      override val presentation: StateFlow<DeviceAction.Presentation> =
        isActivationEnabled
          .map { enabled -> defaultPresentation.copy(enabled = enabled) }
          .stateIn(scope, SharingStarted.Eagerly, defaultPresentation)
    }

  override val editAction = null

  /**
   * Creates a [DirectAccessDeviceHandle] with Disconnected state.
   *
   * The returned device handle prioritizes connecting to an existing reservation over requesting a
   * new one. This method is disabled when a device handle is active or being created. At most one
   * device is available for each template.
   *
   * TODO (b/246171065): activating multiple devices.
   */
  fun createDeviceHandleIfAbsent() {
    if (isActivationEnabled.compareAndSet(expect = true, update = false)) {
      createDeviceHandle()
    }
  }

  /**
   * Creates a [DirectAccessDeviceHandle] matching the [deviceInfo].
   *
   * A direct access device is represented by a reservation, which can be created by other means
   * with the same user and project. If there is an existing reservation when calling this method,
   * the existing reservation will be reused to create a [DeviceHandle]. Otherwise, a new
   * reservation will be requested with a wiped device.
   */
  private fun createDeviceHandle(): DeviceHandle {
    val reservationManager =
      project.service<DirectAccessService>().reservationManager
        ?: throw RuntimeException("Unable to access ReservationManager.")

    val reservationResult =
      try {
        reservationManager.findOrCreateReservation(deviceInfo.codename, deviceInfo.api.toString())
      } catch (e: Exception) {
        // TODO(b/277240160): Add correct failure reason
        DirectAccessUsageTracker.trackReserveDevice(
          false,
          null,
          null,
          deviceInfo.toMetricsDeviceInfo(),
          DirectAccessUsageEvent.FailureReason.UNKNOWN_FAILURE
        )
        throw e
      }
    if (reservationResult.second != 0L) {
      scope.logReserveMetricWhenReservationActive(
        reservationManager.fetchReservationFlow(reservationResult.first),
        reservationResult.second
      )
    }

    val deviceScope = scope.createChildScope(isSupervisor = true)
    // Notify provisioner plugin of the new device.
    return DirectAccessDeviceHandle(
        project,
        deviceScope,
        this@DirectAccessDeviceTemplate,
        DeviceState.Disconnected(properties),
        reservationResult.first
      )
      .also { activeDevice = it }
  }

  private fun CoroutineScope.logReserveMetricWhenReservationActive(
    reservationFlow: StateFlow<Reservation>,
    reserveStartTime: Long
  ) = launch {
    reservationFlow.waitUntilActive()
    DirectAccessUsageTracker.trackReserveDevice(
      true,
      System.currentTimeMillis() - reserveStartTime,
      reservationFlow.value.name,
      deviceInfo.toMetricsDeviceInfo()
    )
  }
}

private fun DeviceInfo.toDeviceProperties(): DirectAccessDeviceProperties {
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
    icon =
      when (type) {
        DeviceType.WEAR_OS -> StudioIcons.DeviceExplorer.FIREBASE_DEVICE_WEAR
        DeviceType.TV -> StudioIcons.DeviceExplorer.FIREBASE_DEVICE_TV
        DeviceType.AUTOMOTIVE -> StudioIcons.DeviceExplorer.FIREBASE_DEVICE_CAR
        else -> StudioIcons.DeviceExplorer.FIREBASE_DEVICE_PHONE
      }
  }
}
