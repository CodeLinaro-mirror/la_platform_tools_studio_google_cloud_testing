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
package com.google.gct.directaccess.analytics

import com.android.tools.analytics.UsageTracker
import com.android.tools.idea.stats.AnonymizerUtil
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import com.google.wireless.android.sdk.stats.DeviceInfo as MetricsDeviceInfo
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType.CONNECT_DEVICE
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType.RESERVE_DEVICE

object DirectAccessUsageTracker {
  fun trackReserveDevice(
    wasSuccessful: Boolean,
    timeToReserveMs: Long?,
    deviceSession: String?,
    deviceInfo: MetricsDeviceInfo,
    failReason: DirectAccessUsageEvent.FailureReason? = null
  ) {
    val event =
      createDirectAccessUsageEvent(deviceSession, failReason) {
        type = RESERVE_DEVICE
        reserveDeviceDetails =
          reserveDeviceDetailsBuilder
            .apply {
              success = wasSuccessful
              timeToReserveMs?.let { reserveTimeMs = it.toInt() }
            }
            .build()
      }
    track(deviceInfo, event)
  }

  fun trackConnectDevice(
    wasSuccessful: Boolean,
    wasReconnect: Boolean,
    timeToConnectMs: Long?,
    deviceSession: String?,
    deviceInfo: MetricsDeviceInfo,
    failReason: DirectAccessUsageEvent.FailureReason? = null
  ) {
    val event =
      createDirectAccessUsageEvent(deviceSession, failReason) {
        type = CONNECT_DEVICE
        connectDeviceDetails =
          connectDeviceDetailsBuilder
            .apply {
              success = wasSuccessful
              reconnect = wasReconnect
              timeToConnectMs?.let { connectTimeMs = it.toInt() }
            }
            .build()
      }

    track(deviceInfo, event)
  }

  private fun track(deviceInformation: MetricsDeviceInfo, usageEvent: DirectAccessUsageEvent) {
    UsageTracker.log(
      AndroidStudioEvent.newBuilder().apply {
        kind = AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT
        deviceInfo = deviceInformation
        directAccessUsageEvent = usageEvent
      }
    )
  }

  private fun createDirectAccessUsageEvent(
    deviceSession: String?,
    failReason: DirectAccessUsageEvent.FailureReason? = null,
    eventBuilder: DirectAccessUsageEvent.Builder.() -> Unit
  ) =
    DirectAccessUsageEvent.newBuilder()
      .apply {
        deviceSession?.let { deviceSessionId = AnonymizerUtil.anonymizeUtf8(it) }
        eventBuilder()
        failReason?.let { failureReason = it }
      }
      .build()
}

fun DeviceInfo.toMetricsDeviceInfo(): MetricsDeviceInfo =
  MetricsDeviceInfo.newBuilder()
    .apply {
      deviceType = MetricsDeviceInfo.DeviceType.CLOUD_PHYSICAL
      manufacturer = this@toMetricsDeviceInfo.manufacturer
      model = this@toMetricsDeviceInfo.name
      buildApiLevelFull = this@toMetricsDeviceInfo.api.toString()
    }
    .build()
