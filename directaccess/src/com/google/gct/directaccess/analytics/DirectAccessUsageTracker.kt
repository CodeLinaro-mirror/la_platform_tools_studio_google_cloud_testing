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
import com.android.tools.idea.stats.AndroidStudioUsageTracker
import com.android.tools.idea.stats.AnonymizerUtil
import com.google.services.firebase.directaccess.client.DirectAccessConnectionMetrics
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import com.google.wireless.android.sdk.stats.DevServiceDeprecationInfo.DeliveryType
import com.google.wireless.android.sdk.stats.DevServiceDeprecationInfo.DeprecationStatus
import com.google.wireless.android.sdk.stats.DeviceInfo as MetricsDeviceInfo
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType.CONNECT_DEVICE
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType.DISCONNECT_DEVICE
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType.END_RESERVATION
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType.EXTEND_RESERVATION
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType.RESERVE_DEVICE
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.EndReservationDetails.EndReservationType
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.ExtendReservationDetails.ExtendReservationDuration
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.time.Duration
import kotlinx.coroutines.CoroutineScope

@Service
class DirectAccessUsageTracker(val scope: CoroutineScope) {

  fun trackReserveDevice(
    wasSuccessful: Boolean,
    timeToReserveMs: Long?,
    deviceSession: String?,
    deviceInfo: MetricsDeviceInfo,
    failReason: DirectAccessUsageEvent.FailureReason? = null,
    deviceStreamingApi: Boolean = false,
  ) {
    val event =
      createDirectAccessUsageEvent(deviceSession, failReason) {
        type = RESERVE_DEVICE
        reserveDeviceDetails =
          reserveDeviceDetailsBuilder
            .apply {
              success = wasSuccessful
              timeToReserveMs?.let { reserveTimeMs = it.toInt() }
              this.devicestreamingApi = deviceStreamingApi
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
    failReason: DirectAccessUsageEvent.FailureReason? = null,
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

  fun trackDisconnectDevice(
    wasSuccessful: Boolean,
    wasUserDisconnected: Boolean,
    deviceSession: String?,
    deviceInfo: MetricsDeviceInfo,
    failReason: DirectAccessUsageEvent.FailureReason? = null,
  ) {
    val event =
      createDirectAccessUsageEvent(deviceSession, failReason) {
        type = DISCONNECT_DEVICE
        disconnectDeviceDetails =
          disconnectDeviceDetailsBuilder
            .apply {
              success = wasSuccessful
              userDisconnected = wasUserDisconnected
            }
            .build()
      }
    track(deviceInfo, event)
  }

  fun trackEndReservation(
    wasSuccessful: Boolean,
    endType: EndReservationType,
    reservationTimeSec: Long,
    latencyMetrics: DirectAccessConnectionMetrics,
    deviceSession: String?,
    deviceInfo: MetricsDeviceInfo,
    failReason: DirectAccessUsageEvent.FailureReason? = null,
  ) {
    val event =
      createDirectAccessUsageEvent(deviceSession, failReason) {
        type = END_RESERVATION
        endReservationDetails =
          endReservationDetailsBuilder
            .apply {
              success = wasSuccessful
              endReservationType = endType
              totalReservationTimeSec = reservationTimeSec.toInt()
              connectionMetrics =
                connectionMetricsBuilder
                  .apply {
                    maxLatencyMs = latencyMetrics.maxLatency
                    p50LatencyMs = latencyMetrics.p50Latency
                    p90LatencyMs = latencyMetrics.p90Latency
                  }
                  .build()
            }
            .build()
      }
    track(deviceInfo, event)
  }

  fun trackExtendReservation(
    wasSuccessful: Boolean,
    extendDuration: Duration,
    deviceSession: String?,
    deviceInfo: MetricsDeviceInfo,
    failReason: DirectAccessUsageEvent.FailureReason? = null,
  ) {
    val event =
      createDirectAccessUsageEvent(deviceSession, failReason) {
        type = EXTEND_RESERVATION
        extendReservationDetails =
          extendReservationDetailsBuilder
            .apply {
              success = wasSuccessful
              extendReservationDuration =
                if (extendDuration.toMinutes() <= 15L) {
                  ExtendReservationDuration.FIFTEEN_MINUTES
                } else {
                  ExtendReservationDuration.THIRTY_MINUTES
                }
              failReason?.let { failureReason = it }
            }
            .build()
      }
    track(deviceInfo, event)
  }

  fun trackServiceDeprecation(
    userNotified: Boolean? = null,
    moreInfoClicked: Boolean? = null,
    updateClicked: Boolean? = null,
  ) {
    UsageTracker.log(
      AndroidStudioEvent.newBuilder().apply {
        kind = AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT
        directAccessUsageEventBuilder.apply {
          type = DirectAccessUsageEvent.DirectAccessUsageEventType.SERVICE_DEPRECATION
          devServiceDeprecationInfoBuilder.apply {
            deprecationStatus = DeprecationStatus.UNSUPPORTED
            deliveryType = DeliveryType.BANNER
            userNotified?.let { this.userNotified = it }
            moreInfoClicked?.let { this.moreInfoClicked = it }
            updateClicked?.let { this.updateClicked = it }
          }
        }
        productDetails = AndroidStudioUsageTracker.productDetails
      }
    )
  }

  private fun track(deviceInformation: MetricsDeviceInfo, usageEvent: DirectAccessUsageEvent) {
    UsageTracker.log(
      AndroidStudioEvent.newBuilder().apply {
        kind = AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT
        deviceInfo = deviceInformation
        directAccessUsageEvent = usageEvent
        productDetails = AndroidStudioUsageTracker.productDetails
      }
    )
  }

  private fun createDirectAccessUsageEvent(
    deviceSession: String?,
    failReason: DirectAccessUsageEvent.FailureReason? = null,
    eventBuilder: DirectAccessUsageEvent.Builder.() -> Unit,
  ) =
    DirectAccessUsageEvent.newBuilder()
      .apply {
        deviceSession?.let { deviceSessionId = AnonymizerUtil.anonymizeUtf8(it) }
        eventBuilder()
        failReason?.let { failureReason = it }
      }
      .build()

  companion object {
    fun getInstance(): DirectAccessUsageTracker = service()
  }
}
