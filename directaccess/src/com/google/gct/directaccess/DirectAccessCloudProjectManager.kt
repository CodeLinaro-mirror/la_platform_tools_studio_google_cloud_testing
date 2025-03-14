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
package com.google.gct.directaccess

import com.android.tools.idea.adblib.AdbLibApplicationService
import com.android.tools.idea.concurrency.createChildScope
import com.android.tools.idea.flags.StudioFlags
import com.google.gct.directaccess.DirectAccessPermissionStatus.Companion.checkDirectAccessPermission
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.services.firebase.directaccess.client.DirectAccessConnectionManager
import com.google.services.firebase.directaccess.client.DirectAccessReservationManager
import com.google.services.firebase.directaccess.client.isActive
import com.google.services.firebase.directaccess.client.isClosed
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import io.grpc.StatusRuntimeException
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.time.withTimeout

/**
 * The data class representing a cloud project.
 *
 * @param user name of the logged-in user that accesses the cloud project
 * @param name name of the cloud project with plain format e.g. `foo` and `ftl-direct-access`
 */
data class CloudProjectEntry(val user: String, val name: String)

/**
 * Manages and shares common information of the same cloud project.
 *
 * Each cloud project has its own access to device catalog and reservations. Different user projects
 * may use the same cloud project. Connecting to a reservation adds a connected device to the adb
 * device list. To avoid adding duplicate devices for the same reservation, we need to make
 * [DirectAccessReservationManager] and [DirectAccessConnectionManager] cloud project scoped and
 * shared across different user projects.
 */
class DirectAccessCloudProjectManager(
  val cloudProject: CloudProjectEntry,
  private val scope: CoroutineScope,
) : AutoCloseable {

  /**
   * True if the new device streaming API is enabled for [cloudProject].
   *
   * TODO(b/403595323) remove the check once FTL direct access API gets disabled.
   */
  val isDefaultApiEnabled =
    try {
      service<CloudClientService>()
        .client
        .isDeviceStreamingServiceEnabled(
          cloudProject.name,
          StudioFlags.DEVICE_STREAMING_ENDPOINT.get(),
        )
    } catch (_: Exception) {
      thisLogger()
        .info("DeviceStreaming API not enabled, fallback to ${StudioFlags.DIRECT_ACCESS_ENDPOINT}")
      false
    }

  /** A pair of usage and limit numbers of quota in minutes. */
  val usageQuota: Pair<Long, Long>?
    get() {
      val endpoint = "https://${StudioFlags.DIRECT_ACCESS_MONITORING_ENDPOINT.get()}"
      val serviceFilter = StudioFlags.DIRECT_ACCESS_ENDPOINT.get()
      val project = "projects/${cloudProject.name}"
      return service<CloudClientService>()
        .client
        .getQuotaUsageAndLimit(endpoint, serviceFilter, project)
    }

  val reservationManager: DirectAccessReservationManager =
    DirectAccessReservationManager(
      cloudProject.name,
      scope.createChildScope(true),
      isDefaultApiEnabled,
      service<DirectAccessServiceSetup>().channel(isDefaultApiEnabled),
    ) {
      service<DirectAccessServiceSetup>().fetchAccessToken()
    }

  val connectionManager: DirectAccessConnectionManager =
    DirectAccessConnectionManager(
      scope.createChildScope(true),
      service<AdbLibApplicationService>().session,
      isDefaultApiEnabled,
      { service<DirectAccessServiceSetup>().fetchAccessToken() },
      service<DirectAccessServiceSetup>().channel(isDefaultApiEnabled),
      reservationManager,
    )

  val accessibleDeviceInfoListFlow: RefreshableStateFlow<List<DeviceInfo>> =
    RefreshableStateFlow(scope, TimeUnit.MINUTES.toMillis(5)) {
      try {
        service<DirectAccessServiceSetup>().getAccessibleDeviceInfoList(cloudProject.name)
      } catch (e: Exception) {
        listOf()
      }
    }

  val reservationListFlowWithException =
    RefreshableStateFlow(scope, TimeUnit.MINUTES.toMillis(1)) {
      try {
        Pair(reservationManager.listReservations(), null)
      } catch (e: Exception) {
        Pair(null, e)
      }
    }

  val permissionFlow: RefreshableStateFlow<DirectAccessPermissionStatus> =
    RefreshableStateFlow(scope, TimeUnit.MINUTES.toMillis(5)) {
      try {
        checkDirectAccessPermission(cloudProject)
      } catch (_: Exception) {
        DirectAccessPermissionStatus.Unknown(FULL_PERMISSIONS_SET)
      }
    }

  val isBillingEnabledFlow: RefreshableStateFlow<Boolean?> =
    RefreshableStateFlow(scope, TimeUnit.MINUTES.toMillis(30)) {
      try {
        service<CloudClientService>().client.isBillingEnabled(cloudProject.name)
      } catch (e: Exception) {
        null
      }
    }

  override fun close() {
    // Put reservations in grace period when the last studio project
    // using this cloud project manager is closed.
    runBlocking {
      withTimeout(Duration.ofSeconds(2)) {
        connectionManager.connections.values
          .map {
            scope.launch {
              try {
                it.endReservation(true)
              } catch (e: StatusRuntimeException) {
                thisLogger().warn(e)
              }
            }
          }
          .joinAll()
      }
    }

    runBlocking {
      withTimeout(Duration.ofSeconds(2)) {
        reservationListFlowWithException.value.first
          ?.mapNotNull {
            if (!it.state.isClosed() && !it.isActive()) {
              scope.launch {
                try {
                  reservationManager.cancelReservation(it.name)
                } catch (e: StatusRuntimeException) {
                  thisLogger().warn(e)
                }
              }
            } else {
              null
            }
          }
          ?.joinAll()
      }
    }
    scope.cancel()
  }
}
