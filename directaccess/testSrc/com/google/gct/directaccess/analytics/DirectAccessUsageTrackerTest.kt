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

import com.android.adblib.DeviceSelector
import com.android.adblib.testing.FakeAdbSession
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.sdklib.deviceprovisioner.DeviceActionException
import com.android.sdklib.deviceprovisioner.DeviceProvisioner
import com.android.testutils.MockitoKt.any
import com.android.testutils.MockitoKt.mock
import com.android.testutils.MockitoKt.whenever
import com.android.testutils.VirtualTimeScheduler
import com.android.tools.adbbridge.Reservation
import com.android.tools.analytics.TestUsageTracker
import com.android.tools.analytics.UsageTracker
import com.android.tools.idea.testing.disposable
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.TestUtils
import com.google.gct.directaccess.TestUtils.connectionState
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
import com.google.services.firebase.directaccess.client.deviceAddress
import com.google.services.firebase.directaccess.client.isClosed
import com.google.services.firebase.directaccess.client.waitUntilActive
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType.CONNECT_DEVICE
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType.DISCONNECT_DEVICE
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType.END_RESERVATION
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType.EXTEND_RESERVATION
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType.RESERVE_DEVICE
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.EndReservationDetails.EndReservationType.ERROR
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.EndReservationDetails.EndReservationType.EXPIRE
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.EndReservationDetails.EndReservationType.FORCE_CHECK_IN
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.ExtendReservationDetails.ExtendReservationDuration.SIXTY_MINUTES
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.ExtendReservationDetails.ExtendReservationDuration.THIRTY_MINUTES
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.FailureReason.UNKNOWN_FAILURE
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.replaceService
import com.studiogrpc.testutils.GrpcConnectionRule
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito

class DirectAccessUsageTrackerTest {

  private val service = FakeDirectAccessGrpcService()
  @get:Rule val projectRule = ProjectRule()
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
  fun setUp() = runBlockingWithTimeout {
    mockGoogleLogin = mock()
    Mockito.doReturn(true).whenever(mockGoogleLogin).isLoggedIn
    ApplicationManager.getApplication()
      .replaceService(GoogleLogin::class.java, mockGoogleLogin, projectRule.disposable)
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
    yieldUntil { provisioner.templates.value.isNotEmpty() }
  }

  @After
  fun tearDown() = runBlockingWithTimeout {
    scope.cancel()
    session.close()
  }

  private fun setupConnection(
    createConnection: (String, CoroutineScope) -> FakeDirectAccessConnection
  ) {
    val mockDirectAccessService = mock<DirectAccessService>()
    whenever(mockDirectAccessService.reservationManager).thenReturn(directAccessReservationManager)
    whenever(mockDirectAccessService.connectToReservation(any(), any())).thenAnswer {
      val reservationName = it.arguments[0] as String
      val deviceScope = it.arguments[1] as CoroutineScope
      createConnection(reservationName, deviceScope).also { conn ->
        fakeConnection = conn
        session.deviceServices.configureShellV2Command(
          DeviceSelector.fromSerialNumber("localhost:${fakeConnection.port}"),
          "getprop",
          "Foo"
        )
        session.deviceServices.configureShellCommand(
          DeviceSelector.fromSerialNumber("localhost:${fakeConnection.port}"),
          "wm size",
          "Physical size: 1080x2400"
        )
      }
    }
    projectRule.project.replaceService(
      DirectAccessService::class.java,
      mockDirectAccessService,
      projectRule.disposable
    )
  }

  @Test
  fun trackReservationSuccessMetricsWhenSuccessReservingDevice() = runBlockingWithTimeout {
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    template.activationAction.activate()

    val studioEvent = findUsageEvent(RESERVE_DEVICE)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(RESERVE_DEVICE)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()

    val reserveDeviceDetails = directAccessEvent.reserveDeviceDetails
    assertThat(reserveDeviceDetails.success).isTrue()
    assertThat(reserveDeviceDetails.reserveTimeMs).isNotNull()
    assertThat(reserveDeviceDetails.reserveTimeMs).isNotEqualTo(0)
  }

  @Test
  fun trackReservationFailMetricWhenErrorReservingDevice() = runBlockingWithTimeout {
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate
    // Shutdown FakeDirectAccessGrpcService to get error when reserving device.
    grpcConnectionRule.channel.shutdownNow()

    // Activate device
    try {
      template.activationAction.activate()
    } catch (ignore: DeviceActionException) {
      // This is an expected exception.
    }

    val studioEvent = findUsageEvent(RESERVE_DEVICE)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(RESERVE_DEVICE)
    assertThat(directAccessEvent.hasDeviceSessionId()).isFalse()
    assertThat(directAccessEvent.failureReason).isEqualTo(UNKNOWN_FAILURE)

    val reserveDeviceDetails = directAccessEvent.reserveDeviceDetails
    assertThat(reserveDeviceDetails.success).isFalse()
    assertThat(reserveDeviceDetails.hasReserveTimeMs()).isFalse()
  }

  @Test
  fun trackConnectionSuccessfulMetricWhenSuccessConnectingDevice() = runBlockingWithTimeout {
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    template.activationAction.activate()
    yieldUntil {
      template.activeDevice?.connection?.state?.value?.connection ==
        DirectAccessConnection.ConnectionState.CONNECTED
    }

    val studioEvent = findUsageEvent(CONNECT_DEVICE)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(CONNECT_DEVICE)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()

    val connectDeviceDetails = directAccessEvent.connectDeviceDetails
    assertThat(connectDeviceDetails.success).isTrue()
    assertThat(connectDeviceDetails.reconnect).isFalse()
    assertThat(connectDeviceDetails.connectTimeMs).isNotNull()
  }

  @Ignore
  @Test
  fun trackConnectionFailMetricWhenErrorConnectingDevice() = runBlockingWithTimeout {
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

    val studioEvent = findUsageEvent(CONNECT_DEVICE)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(CONNECT_DEVICE)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()
    assertThat(directAccessEvent.failureReason).isEqualTo(UNKNOWN_FAILURE)

    val connectDeviceDetails = directAccessEvent.connectDeviceDetails
    assertThat(connectDeviceDetails.success).isFalse()
    assertThat(connectDeviceDetails.reconnect).isFalse()
    assertThat(connectDeviceDetails.hasConnectTimeMs()).isFalse()
  }

  @Test
  fun trackExtendSuccessfulMetricWhenReservationExtendSucceeds() = runBlockingWithTimeout {
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    template.activationAction.activate()
    yieldUntil {
      template.activeDevice?.connection?.state?.value?.connection ==
        DirectAccessConnection.ConnectionState.CONNECTED
    }

    template.activeDevice?.reservationAction?.reserve(Duration.ofMinutes(30))

    val studioEvent = findUsageEvent(EXTEND_RESERVATION)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(EXTEND_RESERVATION)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()

    val extendReservationDetails = directAccessEvent.extendReservationDetails
    assertThat(extendReservationDetails.success).isTrue()
    assertThat(extendReservationDetails.extendReservationDuration).isEqualTo(THIRTY_MINUTES)

    tracker.usages.clear()

    template.activeDevice?.reservationAction?.reserve(Duration.ofMinutes(60))
    val sixtyMinuteEvent = findUsageEvent(EXTEND_RESERVATION)
    assertThat(
        sixtyMinuteEvent.directAccessUsageEvent.extendReservationDetails.extendReservationDuration
      )
      .isEqualTo(SIXTY_MINUTES)
  }

  @Ignore
  @Test
  fun trackExtendFailMetricWhenErrorExtendingReservation() = runBlockingWithTimeout {
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
    yieldUntil {
      template.activeDevice?.connection?.state?.value?.connection ==
        DirectAccessConnection.ConnectionState.CONNECTED
    }

    try {
      template.activeDevice?.reservationAction?.reserve(Duration.ofMinutes(30))
    } catch (e: Exception) {
      // This is an expected exception.
    }

    val studioEvent = findUsageEvent(EXTEND_RESERVATION)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(EXTEND_RESERVATION)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()
    assertThat(directAccessEvent.failureReason).isEqualTo(UNKNOWN_FAILURE)

    val extendReservationDetails = directAccessEvent.extendReservationDetails
    assertThat(extendReservationDetails.success).isFalse()
    assertThat(extendReservationDetails.extendReservationDuration).isEqualTo(THIRTY_MINUTES)
  }

  @Test
  fun trackDisconnectDeviceSuccessMetricWhenSuccessDisconnectingDevice() = runBlockingWithTimeout {
    // Override default connection setup
    setupConnection { reservationName, deviceScope ->
      getSuccessFulDisconnectTestConnection(reservationName, deviceScope)
    }
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()
    yieldUntil {
      template.activeDevice?.connection?.state?.value?.connection ==
        DirectAccessConnection.ConnectionState.CONNECTED
    }
    findUsageEvent(CONNECT_DEVICE)

    handle.deactivationAction.deactivate()
    yieldUntil { handle.connectionState == DirectAccessConnection.ConnectionState.DISCONNECTED }

    val studioEvent = findUsageEvent(DISCONNECT_DEVICE)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(DISCONNECT_DEVICE)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()

    val disconnectDeviceDetails = directAccessEvent.disconnectDeviceDetails
    assertThat(disconnectDeviceDetails.success).isTrue()
    assertThat(disconnectDeviceDetails.userDisconnected).isTrue()
  }

  @Ignore
  @Test
  fun trackDisconnectDeviceSuccessWhenDeviceIsConnectedWhenReservationExpires() =
    runBlockingWithTimeout {
      // Override default connection setup
      setupConnection { reservationName, deviceScope ->
        getSuccessFulDisconnectTestConnection(reservationName, deviceScope)
      }
      val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

      // Activate device
      val handle = template.activationAction.activate() as DirectAccessDeviceHandle
      directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()
      yieldUntil {
        template.activeDevice?.connection?.state?.value?.connection ==
          DirectAccessConnection.ConnectionState.CONNECTED
      }
      findUsageEvent(CONNECT_DEVICE)

      // Simulate reservation end
      directAccessReservationManager.cancelReservation(handle.reservation.name)
      yieldUntil { handle.connectionState == DirectAccessConnection.ConnectionState.DISCONNECTED }

      val studioEvent = findUsageEvent(DISCONNECT_DEVICE)
      assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

      val directAccessEvent = studioEvent.directAccessUsageEvent
      assertThat(directAccessEvent.type).isEqualTo(DISCONNECT_DEVICE)
      assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()

      val disconnectDeviceDetails = directAccessEvent.disconnectDeviceDetails
      assertThat(disconnectDeviceDetails.success).isTrue()
      assertThat(disconnectDeviceDetails.userDisconnected).isFalse()
    }

  @Test
  fun trackDisconnectDeviceFailureMetricWhenErrorDisconnectingDevice() = runBlockingWithTimeout {
    // Override default connection setup
    setupConnection { reservationName, deviceScope ->
      object :
        FakeDirectAccessConnection(directAccessReservationManager, reservationName, deviceScope) {
        override suspend fun closeConnection() = throw Exception()
      }
    }
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()
    yieldUntil {
      template.activeDevice?.connection?.state?.value?.connection ==
        DirectAccessConnection.ConnectionState.CONNECTED
    }
    findUsageEvent(CONNECT_DEVICE)

    try {
      handle.deactivationAction.deactivate()
    } catch (e: Exception) {
      // This is an expected exception.
    }

    val studioEvent = findUsageEvent(DISCONNECT_DEVICE)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(DISCONNECT_DEVICE)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()
    assertThat(directAccessEvent.failureReason).isEqualTo(UNKNOWN_FAILURE)

    val disconnectDeviceDetails = directAccessEvent.disconnectDeviceDetails
    assertThat(disconnectDeviceDetails.success).isFalse()
    assertThat(disconnectDeviceDetails.userDisconnected).isTrue()
  }

  @Test
  fun trackEndReservationSuccessMetricWhenSuccessEndingReservation() = runBlockingWithTimeout {
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    yieldUntil {
      template.activeDevice?.connection?.state?.value?.connection ==
        DirectAccessConnection.ConnectionState.CONNECTED
    }
    handle.reservationAction.endReservation()
    yieldUntil { handle.connectionState == DirectAccessConnection.ConnectionState.DISCONNECTED }

    val studioEvent = findUsageEvent(END_RESERVATION)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(END_RESERVATION)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()

    val endReservationDetails = directAccessEvent.endReservationDetails
    assertThat(endReservationDetails.success).isTrue()
    assertThat(endReservationDetails.averageConnectionLatencyMs).isEqualTo(100)
    assertThat(endReservationDetails.endReservationType).isEqualTo(FORCE_CHECK_IN)
  }

  @Test
  fun trackEndReservationSuccessMetricWhenAutoEndReservation() = runBlockingWithTimeout {
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    yieldUntil {
      template.activeDevice?.connection?.state?.value?.connection ==
        DirectAccessConnection.ConnectionState.CONNECTED
    }
    // Cancel reservation to simulate reservation expiry
    directAccessReservationManager.cancelReservation(handle.reservation.name)
    yieldUntil {
      directAccessReservationManager
        .fetchReservationFlow(handle.reservation.name)
        .value
        .sessionState
        .isClosed()
    }

    val studioEvent = findUsageEvent(END_RESERVATION)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(END_RESERVATION)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()

    val endReservationDetails = directAccessEvent.endReservationDetails
    assertThat(endReservationDetails.success).isTrue()
    assertThat(endReservationDetails.averageConnectionLatencyMs).isEqualTo(100)
    assertThat(endReservationDetails.endReservationType).isEqualTo(EXPIRE)
  }

  @Test
  fun trackEndReservationFailMetricWhenReservationEndsDueToError() = runBlockingWithTimeout {
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    yieldUntil {
      template.activeDevice?.connection?.state?.value?.connection ==
        DirectAccessConnection.ConnectionState.CONNECTED
    }
    val reservationFlow =
      directAccessReservationManager.fetchReservationFlow(handle.reservation.name)
    (reservationFlow as MutableStateFlow).update {
      it.toBuilder().apply { sessionState = Reservation.SessionState.ERROR }.build()
    }

    val studioEvent = findUsageEvent(END_RESERVATION)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(END_RESERVATION)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()
    assertThat(directAccessEvent.failureReason).isEqualTo(UNKNOWN_FAILURE)

    val endReservationDetails = directAccessEvent.endReservationDetails
    assertThat(endReservationDetails.success).isFalse()
    assertThat(endReservationDetails.averageConnectionLatencyMs).isEqualTo(100)
    assertThat(endReservationDetails.endReservationType).isEqualTo(ERROR)
  }

  @Ignore
  @Test
  fun trackEndReservationFailMetricWhenErrorEndingReservation() = runBlockingWithTimeout {
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
    yieldUntil {
      template.activeDevice?.connection?.state?.value?.connection ==
        DirectAccessConnection.ConnectionState.CONNECTED
    }
    directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()

    try {
      handle.reservationAction.endReservation()
    } catch (ignore: Exception) {
      // This is an expected exception.
    }

    val studioEvent = findUsageEvent(END_RESERVATION)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(END_RESERVATION)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()
    assertThat(directAccessEvent.failureReason).isEqualTo(UNKNOWN_FAILURE)

    val endReservationDetails = directAccessEvent.endReservationDetails
    assertThat(endReservationDetails.success).isFalse()
    assertThat(endReservationDetails.averageConnectionLatencyMs).isEqualTo(100)
  }

  private suspend fun findUsageEvent(type: DirectAccessUsageEventType): AndroidStudioEvent {
    yieldUntil { tracker.usages.any { it.studioEvent.isEventOfType(type) } }
    return tracker.usages.first { it.studioEvent.isEventOfType(type) }.studioEvent
  }

  private fun AndroidStudioEvent.isEventOfType(type: DirectAccessUsageEventType) =
    directAccessUsageEvent.type == type

  private fun getSuccessFulDisconnectTestConnection(
    reservationName: String,
    deviceScope: CoroutineScope
  ) =
    object :
      FakeDirectAccessConnection(directAccessReservationManager, reservationName, deviceScope) {
      override suspend fun connect() {
        session.hostServices.connect(deviceAddress()!!)
        super.connect()
      }

      override suspend fun closeConnection() {
        session.hostServices.disconnect(deviceAddress()!!)
        super.closeConnection()
      }
    }
}
