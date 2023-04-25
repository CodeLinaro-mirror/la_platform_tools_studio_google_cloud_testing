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
import com.android.tools.idea.concurrency.createChildScope
import com.android.tools.idea.flags.StudioFlags
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.login.LoginState
import com.google.services.firebase.directaccess.client.isClosed
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.assertj.core.util.VisibleForTesting

private val defaultDeviceInfoProvider = {
  CatalogClient.getAvailableDevices("https://${StudioFlags.DIRECT_ACCESS_ENDPOINT.get()}/")
}

/**
 * Provides direct access to physical devices run by Firebase. Supports configuring direct access
 * device templates and activating / deactivating them.
 */
class DirectAccessDeviceProvisionerPlugin(
  private val scope: CoroutineScope,
  private val project: Project,
  private val deviceInfoProvider: () -> List<DeviceInfo> = defaultDeviceInfoProvider
) : DeviceProvisionerPlugin {
  private val logger = Logger.getInstance(DirectAccessDeviceProvisionerPlugin::class.java)

  // TODO: find a proper priority
  override val priority: Int = 120

  private val _devices = MutableStateFlow(emptyList<DeviceHandle>())
  override val devices: StateFlow<List<DeviceHandle>> = _devices
  private val _templates = MutableStateFlow(emptyList<DeviceTemplate>())
  override val templates: StateFlow<List<DeviceTemplate>> = _templates

  init {
    // This scope will not be cancelled on login changes. Only the inner child scope will be
    // cancelled.
    scope.launch {
      var childScope: CoroutineScope? = null
      LoginState.loggedIn.distinctUntilChanged().collect { isLoggedIn ->
        // This cancellation will cause all child scopes created from this childScope to be
        // cancelled.
        // This includes cancellation of scopes in template, handle, connection.
        childScope?.cancel()
        childScope = scope.createChildScope(isSupervisor = true)
        if (isLoggedIn) {
          childScope?.launch { periodicUpdateReservation() }
          childScope?.launch {
            // Fetch reservations with new templates.
            templates.collect { updateReservations() }
          }
          childScope?.launch { periodicUpdateTemplates(this) }
        } else {
          _templates.value = listOf()
        }
      }
    }
  }

  private fun fetchReservations(): List<com.android.tools.adbbridge.Reservation>? =
    try {
      project.service<DirectAccessService>().reservationManager?.listReservations()
    } catch (e: Exception) {
      logger.warn("Fetching reservations failed", e)
      null
    }

  // Update templates every 5 minutes.
  private suspend fun periodicUpdateTemplates(parentScope: CoroutineScope) {
    while (true) {
      updateTemplates(parentScope)
      delay(TimeUnit.MINUTES.toMillis(5))
    }
  }

  @VisibleForTesting
  fun updateTemplates(parentScope: CoroutineScope) {
    // Start a reservation query to determine if the user has access.
    if (fetchReservations() == null) {
      _templates.value = listOf()
      return
    }

    val oldTemplates =
      templates.value
        .groupBy { (it as DirectAccessDeviceTemplate).deviceInfo }
        .mapValues { it.value[0] }
    try {
      deviceInfoProvider()
        .map { info ->
          // Create a child scope for every template to isolate them in terms of scope.
          // This helps avoid any issues with a given template from propagating to other
          // templates.
          oldTemplates[info]
            ?: DirectAccessDeviceTemplate(
              project,
              info,
              _devices,
              parentScope.createChildScope(isSupervisor = true)
            )
        }
        .let { result -> _templates.value = result }
    } catch (ignore: NotLoggedInException) {
      // do nothing
    } catch (e: Exception) {
      logger.warn(e)
    }
  }

  /** Fetch reservations periodically in case a Reservation is created elsewhere. */
  private suspend fun periodicUpdateReservation() {
    while (true) {
      updateReservations()
      delay(TimeUnit.MINUTES.toMillis(1))
    }
  }

  @VisibleForTesting
  fun updateReservations() {
    val templateMap =
      templates.value.filterIsInstance<DirectAccessDeviceTemplate>().groupBy { template ->
        template.deviceInfo.let { "${it.codename} ${it.api}" }
      }

    fetchReservations()
      ?.filter { reservation ->
        !reservation.sessionState.isClosed() &&
          reservation.androidDeviceList.androidDevicesList.isNotEmpty()
      }
      ?.forEach { reservation ->
        val key =
          reservation.androidDeviceList.androidDevicesList[0].let {
            "${it.androidModelId} ${it.androidVersionId}"
          }
        templateMap[key]?.firstOrNull()?.createDeviceHandleIfAbsent()
      }
  }

  override suspend fun claim(device: ConnectedDevice): DeviceHandle? {
    val sn = device.deviceInfoFlow.value.serialNumber
    if (sn.matches(Regex("^localhost:\\d+$"))) {
      val port = sn.substringAfter(':').toIntOrNull() ?: return null
      return devices.value.filterIsInstance<DirectAccessDeviceHandle>().firstOrNull {
        it.claim(port, device)
      }
    }
    return null
  }
}
