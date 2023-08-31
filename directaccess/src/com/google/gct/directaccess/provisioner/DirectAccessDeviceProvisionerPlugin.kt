/*
 * Copyright (C) 2022 The Android Open Source Project
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
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceProvisionerPlugin
import com.android.sdklib.deviceprovisioner.DeviceTemplate
import com.android.tools.adbbridge.Reservation
import com.android.tools.idea.concurrency.createChildScope
import com.android.tools.idea.flags.StudioFlags
import com.google.common.annotations.VisibleForTesting
import com.google.gct.directaccess.DirectAccessApplicationService
import com.google.gct.directaccess.DirectAccessService
import com.google.services.firebase.directaccess.client.isClosed
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Provides direct access to physical devices run by Firebase. Supports configuring direct access
 * device templates and activating / deactivating them.
 */
class DirectAccessDeviceProvisionerPlugin(
  private val scope: CoroutineScope,
  private val project: Project,
  private val deviceInfoProvider: () -> List<DeviceInfo> = {
    CatalogClient.getAvailableDevices(
      "https://${StudioFlags.DIRECT_ACCESS_ENDPOINT.get()}/",
      project.service<DirectAccessService>().cloudProjectFlow.value
    )
  }
) : DeviceProvisionerPlugin {
  private val logger = Logger.getInstance(DirectAccessDeviceProvisionerPlugin::class.java)

  // TODO: find a proper priority
  override val priority: Int = 120

  private val _devices = MutableStateFlow(emptyList<DeviceHandle>())
  override val devices: StateFlow<List<DeviceHandle>> = _devices
  private val _templates = MutableStateFlow(emptyList<DirectAccessDeviceTemplate>())
  override val templates: StateFlow<List<DeviceTemplate>> = _templates

  private val reservationsFlow = MutableStateFlow<List<Reservation>?>(null)
  private val activeDeviceInfoFlow = MutableStateFlow(setOf<DeviceInfo>())

  init {
    // Clean up remaining templates when scope is cancelled.
    scope.coroutineContext.job.invokeOnCompletion { _templates.update { listOf() } }

    // Maintain a state flow of gcp projects from directAccessService.
    val directAccessService = project.service<DirectAccessService>()

    scope.launch {
      // Create a flow of valid gcp projects.
      @OptIn(ExperimentalCoroutinesApi::class)
      directAccessService.cloudProjectFlow
        .transformLatest { gcpProject ->
          // Verify the same non-null project every 5 minutes.
          emit(gcpProject)
          if (gcpProject != null) {
            while (true) {
              delay(TimeUnit.MINUTES.toMillis(5))
              emit(gcpProject)
            }
          }
        }
        .collectLatest { gcpProject ->
          val deviceInfoList =
            gcpProject?.let {
              try {
                deviceInfoProvider()
              } catch (_: Exception) {
                null
              }
            }
              ?: listOf()
          activeDeviceInfoFlow.value = deviceInfoList.toSet()

          _templates.value +=
            deviceInfoList.minus(_templates.value.map { it.deviceInfo }.toSet()).map { deviceInfo ->
              DirectAccessDeviceTemplate(
                project,
                deviceInfo,
                _devices,
                scope.createChildScope(isSupervisor = true),
                reservationsFlow.combine(activeDeviceInfoFlow) { reservations, deviceInfoSet ->
                  reservations != null && deviceInfoSet.contains(deviceInfo)
                }
              )
            }

          // Refresh reservations every minute to verify current templates and discover devices that
          // were reserved elsewhere
          while (true) {
            fetchReservations()?.let { matchReservations(_templates.value, it) }
            delay(TimeUnit.MINUTES.toMillis(1))
          }
        }
    }
  }

  @VisibleForTesting
  suspend fun matchReservations(
    templates: List<DirectAccessDeviceTemplate>,
    reservations: List<Reservation>
  ) {
    val templateMap =
      templates
        .filter { activeDeviceInfoFlow.value.contains(it.deviceInfo) }
        .groupBy { template -> template.deviceInfo.let { "${it.codename} ${it.api}" } }

    reservations
      .filter { reservation ->
        !reservation.sessionState.isClosed() && reservation.hasAndroidDevice()
      }
      .mapNotNull { reservation ->
        val key = reservation.androidDevice.let { "${it.androidModelId} ${it.androidVersionId}" }
        templateMap[key]?.firstOrNull()?.let { template ->
          // Create handle without blocking iteration
          scope.launch { template.createDeviceHandleIfAbsent(reservation.name) }
        }
      }
      .joinAll()
  }

  /**
   * Returns a list of reservations from [DirectAccessService], or null if authentication failed.
   */
  @VisibleForTesting
  fun fetchReservations(): List<Reservation>? {
    reservationsFlow.value =
      try {
        service<DirectAccessApplicationService>().getReservationManager(project)?.listReservations()
      } catch (e: Exception) {
        logger.warn("Fetching reservations failed", e)
        null
      }
    return reservationsFlow.value
  }

  override suspend fun claim(device: ConnectedDevice): DeviceHandle? {
    val sn = device.deviceInfoFlow.value.serialNumber
    if (sn.matches(Regex("^localhost:\\d+$"))) {
      val port = sn.substringAfter(':').toIntOrNull() ?: return null
      return devices.value.filterIsInstance<DirectAccessDeviceHandle>().firstOrNull {
        it.claim(port, device)
      }
      // TODO(b/296468326): Share reservation list across user projects with the same cloud project.
      ?: service<DirectAccessApplicationService>()
          .getConnectionManager(project)
          ?.connections
          ?.get(port)
          ?.let { connection ->
            // Create a handle with the ConnectedDevice if its port is managed by the
            // DirectAccessConnectionManager.
            val reservation = connection.state.value.reservation
            val androidDevice = reservation.androidDevice
            if (!reservation.sessionState.isClosed() && reservation.hasAndroidDevice()) {
              _templates.value
                .firstOrNull {
                  it.deviceInfo.codename == androidDevice.androidModelId &&
                    it.deviceInfo.api.toString() == androidDevice.androidVersionId
                }
                ?.createDeviceHandleIfAbsent(reservation.name)
            } else null
          }
    }
    return null
  }
}
