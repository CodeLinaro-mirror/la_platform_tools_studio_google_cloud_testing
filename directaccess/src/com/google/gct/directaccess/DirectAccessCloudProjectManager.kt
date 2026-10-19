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

import com.android.flags.Flag
import com.android.tools.idea.adblib.AdbLibApplicationService
import com.android.tools.idea.concurrency.createChildScope
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.serverflags.DynamicServerFlagService
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.time.withTimeout

private val testingQuotaMetricFilters =
  listOf(
    "testing.googleapis.com/device_streaming/monthly_blaze_physical_minutes",
    "testing.googleapis.com/device_streaming/monthly_spark_physical_minutes",
  )

private val deviceStreamingQuotaMetricFilters =
  listOf(
    "devicestreaming.googleapis.com/monthly_billable_physical_minutes",
    "devicestreaming.googleapis.com/monthly_no_charge_physical_minutes",
  )

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
 * Each cloud project has its own access to device catalog and reservations. Different user projects may use the same cloud project.
 * Connecting to a reservation adds a connected device to the adb device list. To avoid adding duplicate devices for the same reservation,
 * we need to make [DirectAccessReservationManager] and [DirectAccessConnectionManager] cloud project scoped and shared across different
 * user projects.
 */
class DirectAccessCloudProjectManager(val cloudProject: CloudProjectEntry, private val scope: CoroutineScope) : AutoCloseable {

  val isDeviceStreamingApiEnabledFlow: RefreshableStateFlow<Boolean?> =
    RefreshableStateFlow(scope, TimeUnit.MINUTES.toMillis(5)) {
      try {
        val endPoint = StudioFlags.DEVICE_STREAMING_ENDPOINT.get()
        endPoint.isNotEmpty() && service<CloudClientService>().client.isDeviceStreamingServiceEnabled(cloudProject.name, endPoint)
      } catch (_: Exception) {
        null
      }
    }

  /**
   * True if the new device streaming API is enabled for [cloudProject].
   *
   * TODO(b/403595323) remove the check once FTL direct access API gets disabled.
   */
  val isDefaultApiEnabled =
    StudioFlags.DIRECT_ACCESS_MIGRATE_TO_DDP.get() ||
      try {
        isDeviceStreamingApiEnabledFlow.value == true &&
          // Fallback to old API if permissions are not full.
          checkDirectAccessPermission(cloudProject, true).missingPermissions.isEmpty()
      } catch (_: Exception) {
        thisLogger().info("DeviceStreaming API not enabled, fallback to ${StudioFlags.DIRECT_ACCESS_ENDPOINT.get()}")
        false
      }

  /** A pair of usage and limit numbers of quota in minutes. */
  val usageQuota: Pair<Long, Long>?
    get() {
      val endpoint = "https://${StudioFlags.DIRECT_ACCESS_MONITORING_ENDPOINT.get()}"
      val (serviceFilter, quotaMetricFilters) =
        if (StudioFlags.DIRECT_ACCESS_QUOTA_SWITCH.getLatest()) {
          StudioFlags.DEVICE_STREAMING_ENDPOINT.get() to deviceStreamingQuotaMetricFilters
        } else {
          StudioFlags.DIRECT_ACCESS_ENDPOINT.get() to testingQuotaMetricFilters
        }
      return service<CloudClientService>().client.getQuotaUsageAndLimit(endpoint, serviceFilter, quotaMetricFilters, cloudProject.name)
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

  val rawPermissionFlow: RefreshableStateFlow<DirectAccessPermissionStatus> =
    RefreshableStateFlow(scope, TimeUnit.MINUTES.toMillis(5)) {
      try {
        checkDirectAccessPermission(cloudProject, isDefaultApiEnabled)
      } catch (_: Exception) {
        val fullPermissions =
          if (isDefaultApiEnabled) {
            NEW_FULL_PERMISSIONS_SET
          } else {
            FULL_PERMISSIONS_SET
          }
        DirectAccessPermissionStatus.Unknown(fullPermissions)
      }
    }

  val permissionFlow: StateFlow<DirectAccessPermissionStatus> =
    combine(rawPermissionFlow.stateFlow, isDeviceStreamingApiEnabledFlow.stateFlow, ::calculatePermissionStatus)
      .stateIn(scope, SharingStarted.Eagerly, calculatePermissionStatus(rawPermissionFlow.value, isDeviceStreamingApiEnabledFlow.value))

  private fun calculatePermissionStatus(permission: DirectAccessPermissionStatus, isApiEnabled: Boolean?): DirectAccessPermissionStatus =
    if (isDefaultApiEnabled && isApiEnabled == false) {
      DirectAccessPermissionStatus.ApiNotEnabled
    } else {
      permission
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

private fun Flag<Boolean>.getLatest(): Boolean {
  return DynamicServerFlagService.instance.getBoolean("studio_flags/$id") ?: get()
}
