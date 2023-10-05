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
import com.google.common.annotations.VisibleForTesting
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.directAccessCloudProjectManager
import com.google.services.firebase.directaccess.client.isClosed
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val PLUGIN_ID = "FirebaseDirectAccess"

/**
 * Provides direct access to physical devices run by Firebase. Supports configuring direct access
 * device templates and activating / deactivating them.
 */
class DirectAccessDeviceProvisionerPlugin(
  private val scope: CoroutineScope,
  private val project: Project
) : DeviceProvisionerPlugin {
  // TODO: find a proper priority
  override val priority: Int = 120

  private val _devices = MutableStateFlow(emptyList<DeviceHandle>())
  override val devices: StateFlow<List<DeviceHandle>> = _devices
  private val _templates = MutableStateFlow(emptyList<DirectAccessDeviceTemplate>())
  override val templates: StateFlow<List<DeviceTemplate>> = _templates

  private val reservationsFlow = MutableStateFlow<List<Reservation>?>(null)
  // A flow of map for devices that are accessible with the current login state and cloud project.
  // The mapping is from a string of device id to its full device information.
  private val accessibleDeviceInfoMapFlow = MutableStateFlow(mapOf<String, DeviceInfo>())
  private val cachedTemplatesMap = mutableMapOf<String, DirectAccessDeviceTemplate>()

  init {
    // Clean up remaining templates when scope is cancelled.
    scope.coroutineContext.job.invokeOnCompletion { _templates.update { listOf() } }
    scope.launch {
      // Create a flow of valid gcp projects.
      project.service<DirectAccessService>().cloudProjectManager.collectLatest { cloudProjectManager
        ->
        if (cloudProjectManager == null) {
          accessibleDeviceInfoMapFlow.value = mapOf()
          reservationsFlow.value = null
        } else {
          launch {
            cloudProjectManager.accessibleDeviceInfoListFlow.stateFlow.collect {
              newAccessibleDeviceInfoList ->
              accessibleDeviceInfoMapFlow.value =
                newAccessibleDeviceInfoList.groupBy { it.id }.mapValues { it.value.first() }
              project.service<DirectAccessService>().deviceSelectionListFlow.update {
                oldDeviceSelectionList ->
                // Add devices not presented in the oldDeviceSelectionList.
                val oldDeviceInfoSet =
                  oldDeviceSelectionList.map { selection -> selection.deviceInfo }.toSet()
                val addedSelectionList =
                  (newAccessibleDeviceInfoList - oldDeviceInfoSet).map { DeviceSelection(true, it) }
                addedSelectionList + oldDeviceSelectionList
              }
            }
          }
          launch {
            cloudProjectManager.reservationListFlowWithException.stateFlow.collect { pair ->
              val newReservations = pair.first
              reservationsFlow.value = newReservations
              if (newReservations != null) {
                matchReservations(_templates.value, newReservations)
              }
            }
          }
        }
      }
    }

    // Update templates with enabledDevicesFlow.
    scope.launch {
      project.service<DirectAccessService>().deviceSelectionListFlow.collect { deviceSelectionList
        ->
        val newTemplates =
          _templates.updateAndGet {
            val existingDeviceInfoMap = it.groupBy { template -> template.deviceInfo }
            deviceSelectionList
              .filter { selection -> selection.isSelected }
              .map { selection -> selection.deviceInfo }
              .map { deviceInfo ->
                existingDeviceInfoMap[deviceInfo]?.firstOrNull()
                  ?: cachedTemplatesMap.computeIfAbsent(deviceInfo.id) {
                    val templateScope = scope.createChildScope(isSupervisor = true)
                    val deviceInfoFlow =
                      accessibleDeviceInfoMapFlow
                        .mapNotNull { deviceMap -> deviceMap[deviceInfo.id] }
                        .stateIn(templateScope, SharingStarted.Eagerly, deviceInfo)
                    DirectAccessDeviceTemplate(
                      project,
                      deviceInfoFlow,
                      _devices,
                      templateScope,
                      reservationsFlow.combine(accessibleDeviceInfoMapFlow) {
                        reservations,
                        deviceInfoSet ->
                        reservations != null && deviceInfoSet[deviceInfo.id] != null
                      }
                    )
                  }
              }
          }
        matchReservations(newTemplates, reservationsFlow.value)
      }
    }
  }

  @VisibleForTesting
  suspend fun matchReservations(
    templates: List<DirectAccessDeviceTemplate>,
    reservations: List<Reservation>?
  ): Unit =
    withContext(NonCancellable) {
      if (reservations == null) {
        return@withContext
      }
      // Applies a NonCancellable job to the coroutine context so that if this method is cancelled
      // and called again from an outside `collectLatest` block, `createDeviceHandleIfAbsent` will
      // not be called concurrently for the same template.
      val templateMap =
        templates.groupBy { template ->
          template.deviceInfo.let { it.codename to it.api.toString() }
        }

      reservations
        .filter { reservation ->
          !reservation.sessionState.isClosed() && reservation.hasAndroidDevice()
        }
        .mapNotNull { reservation ->
          val key = reservation.androidDevice.let { it.androidModelId to it.androidVersionId }
          templateMap[key]?.firstOrNull()?.let { template ->
            // Create handle without blocking iteration
            launch { template.createDeviceHandleIfAbsent(reservation.name) }
          }
        }
        .joinAll()
    }

  override suspend fun claim(device: ConnectedDevice): DeviceHandle? {
    val sn = device.deviceInfoFlow.value.serialNumber
    if (sn.matches(Regex("^localhost:\\d+$"))) {
      val port = sn.substringAfter(':').toIntOrNull() ?: return null
      return devices.value.filterIsInstance<DirectAccessDeviceHandle>().firstOrNull {
        it.claim(port, device)
      }
        ?: project.directAccessCloudProjectManager
          ?.connectionManager
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
