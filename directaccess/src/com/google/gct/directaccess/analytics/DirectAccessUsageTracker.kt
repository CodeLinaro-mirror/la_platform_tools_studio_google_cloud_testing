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

object DirectAccessUsageTracker {
  fun trackReserveDevice(
    wasSuccessful: Boolean,
    timeToReserveMs: Long?,
    deviceSession: String?,
    deviceInfo: MetricsDeviceInfo,
    failReason: DirectAccessUsageEvent.FailureReason? = null
  ) {
    val event =
      DirectAccessUsageEvent.newBuilder()
        .apply {
          type = DirectAccessUsageEvent.DirectAccessUsageEventType.RESERVE_DEVICE
          deviceSession?.let { deviceSessionId = AnonymizerUtil.anonymizeUtf8(it) }
          reserveDeviceDetails =
            reserveDeviceDetailsBuilder
              .apply {
                success = wasSuccessful
                timeToReserveMs?.let { reserveTimeMs = it.toInt() }
              }
              .build()
          failReason?.let { failureReason = failReason }
        }
        .build()
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
