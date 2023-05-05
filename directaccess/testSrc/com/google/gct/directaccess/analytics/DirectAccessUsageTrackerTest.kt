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

import com.android.adblib.testing.FakeAdbSession
import com.android.adblib.testingutils.CoroutineTestUtils
import com.android.sdklib.deviceprovisioner.DeviceActionException
import com.android.sdklib.deviceprovisioner.DeviceProvisioner
import com.android.testutils.MockitoKt
import com.android.testutils.MockitoKt.whenever
import com.android.testutils.VirtualTimeScheduler
import com.android.tools.analytics.TestUsageTracker
import com.android.tools.analytics.UsageTracker
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth
import com.google.common.util.concurrent.MoreExecutors
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.TestUtils
import com.google.gct.directaccess.TestUtils.connectionState
import com.google.gct.directaccess.TestUtils.getNotifications
import com.google.gct.directaccess.TestUtils.reservation
import com.google.gct.directaccess.provisioner.DirectAccessDeviceHandle
import com.google.gct.directaccess.provisioner.DirectAccessDeviceProvisionerPlugin
import com.google.gct.directaccess.provisioner.DirectAccessDeviceTemplate
import com.google.gct.login.GoogleLogin
import com.google.gct.login.LoginState
import com.google.services.firebase.directaccess.client.DirectAccessConnection
import com.google.services.firebase.directaccess.client.DirectAccessReservationManager
import com.google.services.firebase.directaccess.client.FakeDirectAccessConnection
import com.google.services.firebase.directaccess.client.FakeDirectAccessGrpcService
import com.google.services.firebase.directaccess.client.isClosed
import com.google.services.firebase.directaccess.client.waitUntilActive
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent
import com.intellij.notification.NotificationAction
import com.studiogrpc.testutils.GrpcConnectionRule
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito

class DirectAccessUsageTrackerTest {

  private val service = FakeDirectAccessGrpcService()
  @get:Rule val projectRule = AndroidProjectRule.inMemory()
  @get:Rule val grpcConnectionRule = GrpcConnectionRule(listOf(service))

  private val session = FakeAdbSession()
  private lateinit var plugin: DirectAccessDeviceProvisionerPlugin
  private lateinit var provisioner: DeviceProvisioner
  private lateinit var directAccessReservationManager: DirectAccessReservationManager
  private lateinit var fakeConnection: FakeDirectAccessConnection
  private lateinit var scope: CoroutineScope
  private lateinit var tracker: TestUsageTracker
  private lateinit var mockGoogleLogin: GoogleLogin

  @Before
  fun setUp() =
    CoroutineTestUtils.runBlockingWithTimeout {
      mockGoogleLogin = projectRule.mockService(GoogleLogin::class.java)
      Mockito.doReturn(true).whenever(mockGoogleLogin).isLoggedIn
      (LoginState.loggedIn as MutableStateFlow<Boolean>).value = true
      scope = CoroutineScope(MoreExecutors.directExecutor().asCoroutineDispatcher())
      directAccessReservationManager =
        DirectAccessReservationManager("testProject", scope, grpcConnectionRule.channel) {
          "testToken"
        }
      setupConnection { reservationName, deviceScope ->
        FakeDirectAccessConnection(directAccessReservationManager, reservationName, deviceScope)
      }
      tracker = TestUsageTracker(VirtualTimeScheduler())
      UsageTracker.setWriterForTest(tracker)
      plugin =
        DirectAccessDeviceProvisionerPlugin(
          session.scope,
          projectRule.project,
          TestUtils.deviceInfoListProvider
        )
      provisioner = DeviceProvisioner.create(session, listOf(plugin))
      CoroutineTestUtils.yieldUntil { provisioner.templates.value.isNotEmpty() }
    }

  @After
  fun tearDown() =
    CoroutineTestUtils.runBlockingWithTimeout {
      scope.cancel()
      session.close()
    }

  private fun setupConnection(
    createConnection: (String, CoroutineScope) -> FakeDirectAccessConnection
  ) {
    val mockDirectAccessService = projectRule.mockProjectService(DirectAccessService::class.java)
    whenever(mockDirectAccessService.reservationManager).thenReturn(directAccessReservationManager)
    whenever(mockDirectAccessService.connectToReservation(MockitoKt.any(), MockitoKt.any()))
      .thenAnswer {
        val reservationName = it.arguments[0] as String
        val deviceScope = it.arguments[1] as CoroutineScope
        createConnection(reservationName, deviceScope).also { conn -> fakeConnection = conn }
      }
  }

  @Test
  fun trackReservationSuccessMetricsWhenSuccessReservingDevice() =
    CoroutineTestUtils.runBlockingWithTimeout {
      val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

      // Activate device
      template.activationAction.activate()

      val studioEvent =
        findUsageEvent(DirectAccessUsageEvent.DirectAccessUsageEventType.RESERVE_DEVICE)
      Truth.assertThat(studioEvent.kind)
        .isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

      val directAccessEvent = studioEvent.directAccessUsageEvent
      Truth.assertThat(directAccessEvent.type)
        .isEqualTo(DirectAccessUsageEvent.DirectAccessUsageEventType.RESERVE_DEVICE)
      Truth.assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()

      val reserveDeviceDetails = directAccessEvent.reserveDeviceDetails
      Truth.assertThat(reserveDeviceDetails.success).isTrue()
      Truth.assertThat(reserveDeviceDetails.reserveTimeMs).isNotNull()
      Truth.assertThat(reserveDeviceDetails.reserveTimeMs).isNotEqualTo(0)
    }

  @Test
  fun trackReservationFailMetricWhenErrorReservingDevice() =
    CoroutineTestUtils.runBlockingWithTimeout {
      val template = plugin.templates.value[0] as DirectAccessDeviceTemplate
      // Shutdown FakeDirectAccessGrpcService to get error when reserving device.
      grpcConnectionRule.channel.shutdownNow()

      // Activate device
      try {
        template.activationAction.activate()
      } catch (ignore: DeviceActionException) {
        // This is an expected exception.
      }

      val studioEvent =
        findUsageEvent(DirectAccessUsageEvent.DirectAccessUsageEventType.RESERVE_DEVICE)
      Truth.assertThat(studioEvent.kind)
        .isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

      val directAccessEvent = studioEvent.directAccessUsageEvent
      Truth.assertThat(directAccessEvent.type)
        .isEqualTo(DirectAccessUsageEvent.DirectAccessUsageEventType.RESERVE_DEVICE)
      Truth.assertThat(directAccessEvent.hasDeviceSessionId()).isFalse()
      Truth.assertThat(directAccessEvent.failureReason)
        .isEqualTo(DirectAccessUsageEvent.FailureReason.UNKNOWN_FAILURE)

      val reserveDeviceDetails = directAccessEvent.reserveDeviceDetails
      Truth.assertThat(reserveDeviceDetails.success).isFalse()
      Truth.assertThat(reserveDeviceDetails.hasReserveTimeMs()).isFalse()
    }

  @Test
  fun trackConnectionSuccessfulMetricWhenSuccessConnectingDevice() =
    CoroutineTestUtils.runBlockingWithTimeout {
      val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

      // Activate device
      template.activationAction.activate()
      CoroutineTestUtils.yieldUntil {
        template.activeDevice?.connection?.state?.value?.connection ==
          DirectAccessConnection.ConnectionState.CONNECTED
      }

      val studioEvent =
        findUsageEvent(DirectAccessUsageEvent.DirectAccessUsageEventType.CONNECT_DEVICE)
      Truth.assertThat(studioEvent.kind)
        .isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

      val directAccessEvent = studioEvent.directAccessUsageEvent
      Truth.assertThat(directAccessEvent.type)
        .isEqualTo(DirectAccessUsageEvent.DirectAccessUsageEventType.CONNECT_DEVICE)
      Truth.assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()

      val connectDeviceDetails = directAccessEvent.connectDeviceDetails
      Truth.assertThat(connectDeviceDetails.success).isTrue()
      Truth.assertThat(connectDeviceDetails.reconnect).isFalse()
      Truth.assertThat(connectDeviceDetails.connectTimeMs).isNotNull()
    }

  @Ignore
  @Test
  fun trackConnectionFailMetricWhenErrorConnectingDevice() =
    CoroutineTestUtils.runBlockingWithTimeout {
      // Override default connection setup
      setupConnection { reservationName, deviceScope ->
        object :
          FakeDirectAccessConnection(directAccessReservationManager, reservationName, deviceScope) {
          override suspend fun connect() = throw Exception()
        }
      }
      val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

      // Activate device
      try {
        template.activationAction.activate()
      } catch (ignore: Exception) {
        // This is an expected exception.
      }

      val studioEvent =
        findUsageEvent(DirectAccessUsageEvent.DirectAccessUsageEventType.CONNECT_DEVICE)
      Truth.assertThat(studioEvent.kind)
        .isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

      val directAccessEvent = studioEvent.directAccessUsageEvent
      Truth.assertThat(directAccessEvent.type)
        .isEqualTo(DirectAccessUsageEvent.DirectAccessUsageEventType.CONNECT_DEVICE)
      Truth.assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()
      Truth.assertThat(directAccessEvent.failureReason)
        .isEqualTo(DirectAccessUsageEvent.FailureReason.UNKNOWN_FAILURE)

      val connectDeviceDetails = directAccessEvent.connectDeviceDetails
      Truth.assertThat(connectDeviceDetails.success).isFalse()
      Truth.assertThat(connectDeviceDetails.reconnect).isFalse()
      Truth.assertThat(connectDeviceDetails.hasConnectTimeMs()).isFalse()
    }

  @Test
  fun trackExtendSuccessfulMetricWhenReservationExtendSucceeds() =
    CoroutineTestUtils.runBlockingWithTimeout {
      val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

      // Activate device
      template.activationAction.activate()
      CoroutineTestUtils.yieldUntil {
        template.activeDevice?.connection?.state?.value?.connection ==
          DirectAccessConnection.ConnectionState.CONNECTED
      }

      template.activeDevice?.reservationAction?.reserve(Duration.ofMinutes(30))

      val studioEvent =
        findUsageEvent(DirectAccessUsageEvent.DirectAccessUsageEventType.EXTEND_RESERVATION)
      Truth.assertThat(studioEvent.kind)
        .isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

      val directAccessEvent = studioEvent.directAccessUsageEvent
      Truth.assertThat(directAccessEvent.type)
        .isEqualTo(DirectAccessUsageEvent.DirectAccessUsageEventType.EXTEND_RESERVATION)
      Truth.assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()

      val extendReservationDetails = directAccessEvent.extendReservationDetails
      Truth.assertThat(extendReservationDetails.success).isTrue()
      Truth.assertThat(extendReservationDetails.extendReservationDuration)
        .isEqualTo(
          DirectAccessUsageEvent.ExtendReservationDetails.ExtendReservationDuration.THIRTY_MINUTES
        )

      tracker.usages.clear()

      template.activeDevice?.reservationAction?.reserve(Duration.ofMinutes(60))
      val sixtyMinuteEvent =
        findUsageEvent(DirectAccessUsageEvent.DirectAccessUsageEventType.EXTEND_RESERVATION)
      Truth.assertThat(
          sixtyMinuteEvent.directAccessUsageEvent.extendReservationDetails.extendReservationDuration
        )
        .isEqualTo(
          DirectAccessUsageEvent.ExtendReservationDetails.ExtendReservationDuration.SIXTY_MINUTES
        )
    }

  @Ignore
  @Test
  fun trackExtendFailMetricWhenErrorExtendingReservation() =
    CoroutineTestUtils.runBlockingWithTimeout {
      // Override default connection setup
      setupConnection { reservationName, deviceScope ->
        object :
          FakeDirectAccessConnection(directAccessReservationManager, reservationName, deviceScope) {
          override suspend fun extendReservation(duration: Duration) = throw Exception()
        }
      }
      val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

      // Activate device
      template.activationAction.activate()
      CoroutineTestUtils.yieldUntil {
        template.activeDevice?.connection?.state?.value?.connection ==
          DirectAccessConnection.ConnectionState.CONNECTED
      }

      try {
        template.activeDevice?.reservationAction?.reserve(Duration.ofMinutes(30))
      } catch (e: Exception) {
        // This is an expected exception.
      }

      val studioEvent =
        findUsageEvent(DirectAccessUsageEvent.DirectAccessUsageEventType.EXTEND_RESERVATION)
      Truth.assertThat(studioEvent.kind)
        .isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

      val directAccessEvent = studioEvent.directAccessUsageEvent
      Truth.assertThat(directAccessEvent.type)
        .isEqualTo(DirectAccessUsageEvent.DirectAccessUsageEventType.EXTEND_RESERVATION)
      Truth.assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()
      Truth.assertThat(directAccessEvent.failureReason)
        .isEqualTo(DirectAccessUsageEvent.FailureReason.UNKNOWN_FAILURE)

      val extendReservationDetails = directAccessEvent.extendReservationDetails
      Truth.assertThat(extendReservationDetails.success).isFalse()
      Truth.assertThat(extendReservationDetails.extendReservationDuration)
        .isEqualTo(
          DirectAccessUsageEvent.ExtendReservationDetails.ExtendReservationDuration.THIRTY_MINUTES
        )
    }

  @Test
  fun trackEndReservationSuccessMetricWhenSuccessEndingReservation() =
    CoroutineTestUtils.runBlockingWithTimeout {
      val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

      // Activate device
      val handle = template.activationAction.activate() as DirectAccessDeviceHandle
      CoroutineTestUtils.yieldUntil {
        template.activeDevice?.connection?.state?.value?.connection ==
          DirectAccessConnection.ConnectionState.CONNECTED
      }
      handle.deactivationAction.deactivate()
      CoroutineTestUtils.yieldUntil {
        handle.connectionState == DirectAccessConnection.ConnectionState.DISCONNECTED
      }

      val notifications = getNotifications(projectRule.project)
      Truth.assertThat(notifications.size).isEqualTo(1)

      (notifications[0].actions[1] as NotificationAction).actionPerformed(
        MockitoKt.mock(),
        notifications[0]
      )

      val studioEvent =
        findUsageEvent(DirectAccessUsageEvent.DirectAccessUsageEventType.END_RESERVATION)
      Truth.assertThat(studioEvent.kind)
        .isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

      val directAccessEvent = studioEvent.directAccessUsageEvent
      Truth.assertThat(directAccessEvent.type)
        .isEqualTo(DirectAccessUsageEvent.DirectAccessUsageEventType.END_RESERVATION)
      Truth.assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()

      val endReservationDetails = directAccessEvent.endReservationDetails
      Truth.assertThat(endReservationDetails.success).isTrue()
      Truth.assertThat(endReservationDetails.userEnded).isTrue()
      Truth.assertThat(endReservationDetails.averageConnectionLatencyMs).isEqualTo(100)
    }

  @Test
  fun trackEndReservationSuccessMetricWhenAutoEndReservation() =
    CoroutineTestUtils.runBlockingWithTimeout {
      val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

      // Activate device
      val handle = template.activationAction.activate() as DirectAccessDeviceHandle
      CoroutineTestUtils.yieldUntil {
        template.activeDevice?.connection?.state?.value?.connection ==
          DirectAccessConnection.ConnectionState.CONNECTED
      }
      directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()
      // Cancel reservation to simulate reservation expiry
      directAccessReservationManager.cancelReservation(handle.reservation.name)
      CoroutineTestUtils.yieldUntil {
        directAccessReservationManager
          .fetchReservationFlow(handle.reservation.name)
          .value
          .sessionState
          .isClosed()
      }

      val studioEvent =
        findUsageEvent(DirectAccessUsageEvent.DirectAccessUsageEventType.END_RESERVATION)
      Truth.assertThat(studioEvent.kind)
        .isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

      val directAccessEvent = studioEvent.directAccessUsageEvent
      Truth.assertThat(directAccessEvent.type)
        .isEqualTo(DirectAccessUsageEvent.DirectAccessUsageEventType.END_RESERVATION)
      Truth.assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()

      val endReservationDetails = directAccessEvent.endReservationDetails
      Truth.assertThat(endReservationDetails.success).isTrue()
      Truth.assertThat(endReservationDetails.userEnded).isFalse()
      Truth.assertThat(endReservationDetails.averageConnectionLatencyMs).isEqualTo(100)
    }

  @Ignore
  @Test
  fun trackEndReservationFailMetricWhenErrorEndingReservation() =
    CoroutineTestUtils.runBlockingWithTimeout {
      // Override default connection setup
      setupConnection { reservationName, deviceScope ->
        object :
          FakeDirectAccessConnection(directAccessReservationManager, reservationName, deviceScope) {
          override suspend fun endReservation(withGracePeriod: Boolean) = throw Exception()
        }
      }
      val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

      // Activate device
      val handle = template.activationAction.activate() as DirectAccessDeviceHandle
      CoroutineTestUtils.yieldUntil {
        template.activeDevice?.connection?.state?.value?.connection ==
          DirectAccessConnection.ConnectionState.CONNECTED
      }
      directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()

      try {
        handle.deactivationAction.deactivate()
      } catch (ignore: Exception) {
        // This is an expected exception.
      }

      val studioEvent =
        findUsageEvent(DirectAccessUsageEvent.DirectAccessUsageEventType.END_RESERVATION)
      Truth.assertThat(studioEvent.kind)
        .isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

      val directAccessEvent = studioEvent.directAccessUsageEvent
      Truth.assertThat(directAccessEvent.type)
        .isEqualTo(DirectAccessUsageEvent.DirectAccessUsageEventType.END_RESERVATION)
      Truth.assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()

      val endReservationDetails = directAccessEvent.endReservationDetails
      Truth.assertThat(endReservationDetails.success).isFalse()
      Truth.assertThat(endReservationDetails.userEnded).isTrue()
      Truth.assertThat(endReservationDetails.averageConnectionLatencyMs).isEqualTo(100)
    }

  private suspend fun findUsageEvent(
    type: DirectAccessUsageEvent.DirectAccessUsageEventType
  ): AndroidStudioEvent {
    CoroutineTestUtils.yieldUntil { tracker.usages.any { it.studioEvent.isEventOfType(type) } }
    return tracker.usages.first { it.studioEvent.isEventOfType(type) }.studioEvent
  }

  private fun AndroidStudioEvent.isEventOfType(
    type: DirectAccessUsageEvent.DirectAccessUsageEventType
  ) = directAccessUsageEvent.type == type
}
