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

import com.android.sdklib.deviceprovisioner.DeviceActionDisabledException
import com.android.sdklib.deviceprovisioner.DeviceActionException
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.sdklib.deviceprovisioner.DeviceTemplate
import com.android.sdklib.deviceprovisioner.TemplateActivationAction
import com.android.tools.idea.concurrency.createChildScope
import com.google.gct.directaccess.DirectAccessService
import com.google.services.firebase.directaccess.client.findOrCreateReservation
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
       * Creates a [DirectAccessDeviceHandle] with [Disconnected] state.
       *
       * This method first finds or create a [Reservation] that matches its [deviceInfo]. Then a
       * [DirectAccessDeviceHandle] is created with a [DirectAccessConnection] to the [Reservation].
       * Connection is not started until the activationAction of device handle get called. The
       * device handle is added to the devices flow of the provisioner and will be removed after
       * [Reservation] closed. This method is disabled when a device handle is activating or
       * activated. At most one device is available for each template.
       *
       * TODO (b/246171065): activating multiple devices.
       */
      override suspend fun activate(duration: Duration?): DeviceHandle {
        // Disable further activate actions to avoid multiple devices.
        if (!isActivationEnabled.compareAndSet(expect = true, update = false)) {
          throw DeviceActionDisabledException(this)
        }

        val reservationManager =
          project.service<DirectAccessService>().reservationManager
            ?: throw DeviceActionException("Unable to access ReservationManager.")

        val reservationName =
          try {
            reservationManager
              .findOrCreateReservation(deviceInfo.codename, deviceInfo.api.toString())
              .name
          } catch (e: Exception) {
            // Pass the underlying gRPC exception as a cause
            // TODO: Perhaps extract more detail if we can get it.
            throw DeviceActionException("Unable to reserve device.", e)
          }

        val deviceProperties = deviceInfo.toDeviceProperties()
        val deviceScope = scope.createChildScope(isSupervisor = true)
        // Notify provisioner plugin of the new device.
        return DirectAccessDeviceHandle(
            project,
            deviceScope,
            this@DirectAccessDeviceTemplate,
            DeviceState.Disconnected(deviceProperties),
            reservationName
          )
          .also { activeDevice = it }
      }

      override val label: String = "Acquire"
      override val isEnabled: StateFlow<Boolean> = isActivationEnabled
    }

  override val editAction = null
}
