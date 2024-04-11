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
import com.android.adblib.utils.createChildScope
import com.android.flags.junit.FlagRule
import com.android.sdklib.deviceprovisioner.DeviceActionException
import com.android.sdklib.deviceprovisioner.DeviceProvisioner
import com.android.sdklib.deviceprovisioner.testing.testDeviceIcons
import com.android.testutils.MockitoKt.any
import com.android.testutils.MockitoKt.mock
import com.android.testutils.MockitoKt.whenever
import com.android.testutils.VirtualTimeScheduler
import com.android.tools.adbbridge.Reservation
import com.android.tools.analytics.TestUsageTracker
import com.android.tools.analytics.UsageTracker
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.testing.disposable
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import com.google.gct.directaccess.CloudProjectEntry
import com.google.gct.directaccess.DirectAccessCloudProjectManager
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.DirectAccessServiceSetup
import com.google.gct.directaccess.RefreshableStateFlow
import com.google.gct.directaccess.TestUtils
import com.google.gct.directaccess.TestUtils.connectionState
import com.google.gct.directaccess.TestUtils.deviceInfoListProvider
import com.google.gct.directaccess.TestUtils.reservation
import com.google.gct.directaccess.TestUtils.showAllTemplates
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.gct.directaccess.provisioner.DeviceSelection
import com.google.gct.directaccess.provisioner.DirectAccessDeviceHandle
import com.google.gct.directaccess.provisioner.DirectAccessDeviceProvisionerPlugin
import com.google.gct.directaccess.provisioner.DirectAccessDeviceTemplate
import com.google.gct.directaccess.provisioner.PLUGIN_ID
import com.google.gct.directaccess.rule.CleanUpNotificationRule
import com.google.gct.directaccess.rule.PropertiesComponentRule
import com.google.gct.login2.GoogleLoginService
import com.google.gct.login2.LoginUsersRule
import com.google.services.firebase.directaccess.client.DirectAccessConnection.ConnectionState
import com.google.services.firebase.directaccess.client.DirectAccessConnection.StateReason
import com.google.services.firebase.directaccess.client.DirectAccessConnectionManager
import com.google.services.firebase.directaccess.client.DirectAccessReservationManager
import com.google.services.firebase.directaccess.client.FakeDirectAccessConnection
import com.google.services.firebase.directaccess.client.FakeDirectAccessGrpcService
import com.google.services.firebase.directaccess.client.deviceAddress
import com.google.services.firebase.directaccess.client.isClosed
import com.google.services.firebase.directaccess.client.waitUntilActive
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import com.google.wireless.android.sdk.stats.DeviceInfo.DeviceType
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType.CONNECT_DEVICE
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType.DISCONNECT_DEVICE
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType.END_RESERVATION
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType.EXTEND_RESERVATION
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType.RESERVE_DEVICE
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.EndReservationDetails.EndReservationType.ERROR
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.EndReservationDetails.EndReservationType.EXPIRE
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.EndReservationDetails.EndReservationType.FORCE_CHECK_IN
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.ExtendReservationDetails.ExtendReservationDuration.FIFTEEN_MINUTES
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.ExtendReservationDetails.ExtendReservationDuration.THIRTY_MINUTES
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.FailureReason
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.FailureReason.FAILED_TO_ALLOCATE_DEVICE
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.FailureReason.PROJECT_CLOSING
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.FailureReason.UNKNOWN_FAILURE
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.replaceService
import com.intellij.util.application
import com.studiogrpc.testutils.GrpcConnectionRule
import java.time.Duration
import junit.framework.TestCase.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn

class DirectAccessUsageTrackerTest {

  private val service = FakeDirectAccessGrpcService()
  private val projectRule = ProjectRule()
  private val grpcConnectionRule = GrpcConnectionRule(listOf(service))
  private val loginUsersRule = LoginUsersRule()
  private val cleanUpNotificationRule = CleanUpNotificationRule(projectRule)
  private val propertiesComponentRule = PropertiesComponentRule(projectRule)

  @get:Rule
  val ruleChain: RuleChain =
    RuleChain.outerRule(FlagRule(StudioFlags.ENABLE_SETTINGS_ACCOUNT_UI, true))
      .around(projectRule)
      .around(grpcConnectionRule)
      .around(loginUsersRule)
      .around(cleanUpNotificationRule)
      .around(propertiesComponentRule)

  private val session = FakeAdbSession()
  private lateinit var plugin: DirectAccessDeviceProvisionerPlugin
  private lateinit var provisioner: DeviceProvisioner
  private lateinit var directAccessReservationManager: DirectAccessReservationManager
  private lateinit var fakeConnection: FakeDirectAccessConnection
  private lateinit var scope: CoroutineScope
  private lateinit var tracker: TestUsageTracker

  @Before
  fun setUp() = runBlockingWithTimeout {
    loginUsersRule.setActiveUser("test@google.com")
    scope = CoroutineScope(MoreExecutors.directExecutor().asCoroutineDispatcher())
    directAccessReservationManager =
      DirectAccessReservationManager("test-project", scope, grpcConnectionRule.channel) {
        "testToken"
      }
    setupConnection { reservationName ->
      FakeDirectAccessConnection(
        directAccessReservationManager,
        reservationName,
        scope.createChildScope(true),
      )
    }
    tracker = TestUsageTracker(VirtualTimeScheduler())
    UsageTracker.setWriterForTest(tracker)
    plugin = DirectAccessDeviceProvisionerPlugin(session.scope, projectRule.project)
    provisioner = DeviceProvisioner.create(session, listOf(plugin), testDeviceIcons)
    projectRule.project.showAllTemplates()
    yieldUntil { provisioner.templates.value.isNotEmpty() }
  }

  @After
  fun tearDown() = runBlockingWithTimeout {
    scope.cancel()
    session.close()
  }

  private fun setupConnection(createConnection: (String) -> FakeDirectAccessConnection) {
    val mockDirectAccessServiceSetup = mock<DirectAccessServiceSetup>()
    doReturn(listOf<DeviceInfo>())
      .whenever(mockDirectAccessServiceSetup)
      .getAccessibleDeviceInfoList(null)
    ApplicationManager.getApplication()
      .replaceService(
        DirectAccessServiceSetup::class.java,
        mockDirectAccessServiceSetup,
        projectRule.disposable,
      )
    // Sets up mock project service.
    val mockDirectAccessService = mock<DirectAccessService>()
    val cloudProjectName = "test-project"
    val cloudProjectManagerFlow = MutableStateFlow<DirectAccessCloudProjectManager?>(null)
    val mockCloudProjectManager = mock<DirectAccessCloudProjectManager>()
    doReturn(CloudProjectEntry("", cloudProjectName)).whenever(mockCloudProjectManager).cloudProject
    val deviceSelectionListFlow = MutableStateFlow<List<DeviceSelection>>(listOf())
    var isProjectClosing = false
    doReturn(deviceSelectionListFlow).whenever(mockDirectAccessService).deviceSelectionListFlow
    doReturn(cloudProjectManagerFlow).whenever(mockDirectAccessService).cloudProjectManager
    doAnswer { isProjectClosing }.whenever(mockDirectAccessService).isProjectClosing
    application.messageBus
      .connect(projectRule.disposable)
      .subscribe(
        ProjectManager.TOPIC,
        object : ProjectManagerListener {
          override fun projectClosing(project: Project) {
            if (project != projectRule.project) return
            isProjectClosing = true
          }
        },
      )

    projectRule.project.replaceService(
      DirectAccessService::class.java,
      mockDirectAccessService,
      projectRule.disposable,
    )

    // Sets up deviceSelectionListFlow.
    scope.launch {
      service<GoogleLoginService>().activeUserFlow.collect {
        cloudProjectManagerFlow.value = if (it == null) null else mockCloudProjectManager
      }
    }

    // Sets up APIs in cloudProjectManager.
    val reservationListFlow =
      RefreshableStateFlow(scope, Long.MAX_VALUE) {
        if (service<GoogleLoginService>().isLoggedIn())
          Pair(directAccessReservationManager.listReservations(), null)
        else Pair(null, Exception())
      }
    doReturn(reservationListFlow).whenever(mockCloudProjectManager).reservationListFlowWithException

    val accessibleDeviceInfoListFlow =
      RefreshableStateFlow(scope, Long.MAX_VALUE) { TestUtils.deviceInfoListProvider() }
    doReturn(accessibleDeviceInfoListFlow)
      .whenever(mockCloudProjectManager)
      .accessibleDeviceInfoListFlow
    doReturn(directAccessReservationManager).whenever(mockCloudProjectManager).reservationManager

    val mockDirectAccessConnectionManager = mock<DirectAccessConnectionManager>()
    doReturn(mockDirectAccessConnectionManager).whenever(mockCloudProjectManager).connectionManager

    // Sets up connectionManager.
    doAnswer {
        val reservationName = it.arguments[0] as String
        createConnection(reservationName).also { conn ->
          fakeConnection = conn
          session.deviceServices.configureShellV2Command(
            DeviceSelector.fromSerialNumber("localhost:${fakeConnection.port}"),
            "getprop",
            "Foo",
          )
          session.deviceServices.configureShellCommand(
            DeviceSelector.fromSerialNumber("localhost:${fakeConnection.port}"),
            "wm size",
            "Physical size: 1080x2400",
          )
        }
      }
      .whenever(mockDirectAccessConnectionManager)
      .create(any())
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
      fail("Expected an exception to be thrown")
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
      template.activeDevice?.connection?.state?.value?.connection is ConnectionState.Connected
    }

    val studioEvent = findUsageEvent(CONNECT_DEVICE)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)
    assertThat(studioEvent.deviceInfo.connectionId).isEqualTo("1")

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(CONNECT_DEVICE)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()

    val connectDeviceDetails = directAccessEvent.connectDeviceDetails
    assertThat(connectDeviceDetails.success).isTrue()
    assertThat(connectDeviceDetails.reconnect).isFalse()
    assertThat(connectDeviceDetails.connectTimeMs).isNotNull()
  }

  @Test
  fun trackConnectionFailMetricWhenErrorConnectingDevice() = runBlockingWithTimeout {
    // Override default connection setup
    setupConnection { reservationName ->
      object :
        FakeDirectAccessConnection(
          directAccessReservationManager,
          reservationName,
          scope.createChildScope(true),
        ) {
        override suspend fun connect() = throw Exception()
      }
    }
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    try {
      template.activationAction.activate()
      fail("Expected an exception to be thrown")
    } catch (ignore: Exception) {
      // This is an expected exception.
    }

    val studioEvent = findUsageEvent(CONNECT_DEVICE)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)
    assertThat(studioEvent.deviceInfo.connectionId).isEqualTo("1")

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
  fun testConnectionIdIncrementsIrrespectiveOfSuccess() = runBlockingWithTimeout {
    // Override default connection setup
    setupConnection { reservationName ->
      object :
        FakeDirectAccessConnection(
          directAccessReservationManager,
          reservationName,
          scope.createChildScope(true),
        ) {
        private var connectCount = 0

        override suspend fun connect() {
          if (++connectCount > 2) {
            // The real connect() will update its state on failure
            state.update { it.copy(ConnectionState.Disconnected(StateReason.CONNECTION_FAILED)) }
            throw Exception()
          }
          super.connect()
        }
      }
    }

    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    var exceptionCount = 0

    repeat(5) {
      if (it > 0) {
        try {
          handle.activationAction.activate()
        } catch (ignore: Exception) {
          exceptionCount++
        }
      }

      val studioEvent = findUsageEvent(CONNECT_DEVICE)
      assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)
      assertThat(studioEvent.deviceInfo.connectionId).isEqualTo("${it + 1}")

      handle.deactivationAction.deactivate()
      tracker.usages.clear()
    }
    assertThat(exceptionCount).isEqualTo(3)
  }

  @Test
  fun trackExtendSuccessfulMetricWhenReservationExtendSucceeds() = runBlockingWithTimeout {
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    template.activationAction.activate()
    yieldUntil {
      template.activeDevice?.connection?.state?.value?.connection is ConnectionState.Connected
    }

    template.activeDevice?.reservationAction?.reserve(Duration.ofMinutes(14))

    val studioEvent = findUsageEvent(EXTEND_RESERVATION)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(EXTEND_RESERVATION)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()

    val extendReservationDetails = directAccessEvent.extendReservationDetails
    assertThat(extendReservationDetails.success).isTrue()
    assertThat(extendReservationDetails.extendReservationDuration).isEqualTo(FIFTEEN_MINUTES)

    tracker.usages.clear()

    template.activeDevice?.reservationAction?.reserve(Duration.ofMinutes(30))
    val sixtyMinuteEvent = findUsageEvent(EXTEND_RESERVATION)
    assertThat(
        sixtyMinuteEvent.directAccessUsageEvent.extendReservationDetails.extendReservationDuration
      )
      .isEqualTo(THIRTY_MINUTES)
  }

  @Test
  fun trackExtendFailMetricWhenErrorExtendingReservation() = runBlockingWithTimeout {
    // Override default connection setup
    setupConnection { reservationName ->
      object :
        FakeDirectAccessConnection(
          directAccessReservationManager,
          reservationName,
          scope.createChildScope(true),
        ) {
        override suspend fun extendReservation(duration: Duration) = throw Exception()
      }
    }
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    template.activationAction.activate()
    yieldUntil {
      template.activeDevice?.connection?.state?.value?.connection is ConnectionState.Connected
    }

    try {
      template.activeDevice?.reservationAction?.reserve(Duration.ofMinutes(30))
      fail("Expected an exception to be thrown")
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
    setupConnection { reservationName ->
      getSuccessFulDisconnectTestConnection(reservationName, scope.createChildScope(true))
    }
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()
    yieldUntil {
      template.activeDevice?.connection?.state?.value?.connection is ConnectionState.Connected
    }
    findUsageEvent(CONNECT_DEVICE)

    handle.deactivationAction.deactivate()
    yieldUntil { handle.connectionState is ConnectionState.Disconnected }

    val studioEvent = findUsageEvent(DISCONNECT_DEVICE)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(DISCONNECT_DEVICE)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()
    assertThat(directAccessEvent.hasFailureReason()).isFalse()

    val disconnectDeviceDetails = directAccessEvent.disconnectDeviceDetails
    assertThat(disconnectDeviceDetails.success).isTrue()
    assertThat(disconnectDeviceDetails.userDisconnected).isTrue()
  }

  @Test
  fun trackDisconnectDeviceSuccessWhenDeviceIsConnectedWhenReservationExpires() =
    runBlockingWithTimeout {
      // Override default connection setup
      setupConnection { reservationName ->
        getSuccessFulDisconnectTestConnection(reservationName, scope.createChildScope(true))
      }
      val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

      // Activate device
      val handle = template.activationAction.activate() as DirectAccessDeviceHandle
      val reservationFlow =
        directAccessReservationManager.fetchReservationFlow(handle.reservation.name)
      reservationFlow.waitUntilActive()
      yieldUntil {
        template.activeDevice?.connection?.state?.value?.connection is ConnectionState.Connected
      }
      findUsageEvent(CONNECT_DEVICE)

      // Simulate reservation end
      (reservationFlow as MutableStateFlow).update {
        it.toBuilder().apply { sessionState = Reservation.SessionState.EXPIRED }.build()
      }
      yieldUntil { reservationFlow.value.sessionState.isClosed() }

      // Wait for the end reservation event.
      // This also ensures that the sticky notification for reservation end is shown
      // This notification is then cleaned by the rule
      findUsageEvent(END_RESERVATION)

      val studioEvent = findUsageEvent(DISCONNECT_DEVICE)
      assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

      val directAccessEvent = studioEvent.directAccessUsageEvent
      assertThat(directAccessEvent.type).isEqualTo(DISCONNECT_DEVICE)
      assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()
      assertThat(directAccessEvent.failureReason).isEqualTo(FailureReason.SESSION_ENDED)

      val disconnectDeviceDetails = directAccessEvent.disconnectDeviceDetails
      assertThat(disconnectDeviceDetails.success).isTrue()
      assertThat(disconnectDeviceDetails.userDisconnected).isFalse()
    }

  @Test
  fun trackDisconnectDeviceSuccessWhenUserLogsOut() = runBlockingWithTimeout {
    setupConnection { reservationName ->
      getSuccessFulDisconnectTestConnection(reservationName, scope.createChildScope(true))
    }
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()
    yieldUntil {
      template.activeDevice?.connection?.state?.value?.connection is ConnectionState.Connected
    }

    loginUsersRule.logOut("test@google.com")

    val studioEvent = findUsageEvent(DISCONNECT_DEVICE)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(DISCONNECT_DEVICE)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()
    assertThat(directAccessEvent.failureReason).isEqualTo(FailureReason.USER_LOGGED_OUT)

    val disconnectDeviceDetails = directAccessEvent.disconnectDeviceDetails
    assertThat(disconnectDeviceDetails.success).isTrue()
    assertThat(disconnectDeviceDetails.userDisconnected).isFalse()
  }

  @Test
  fun trackDisconnectDeviceSuccessWhenProjectClosing() = runBlockingWithTimeout {
    setupConnection { reservationName ->
      getSuccessFulDisconnectTestConnection(reservationName, scope.createChildScope(true))
    }

    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()
    yieldUntil { handle.connection.state.value.connection is ConnectionState.Connected }

    // Simulate project closing.
    application.messageBus.syncPublisher(ProjectManager.TOPIC).projectClosing(projectRule.project)
    scope.cancel()

    val studioEvent = findUsageEvent(DISCONNECT_DEVICE)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(DISCONNECT_DEVICE)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()
    assertThat(directAccessEvent.failureReason).isEqualTo(PROJECT_CLOSING)

    val disconnectDeviceDetails = directAccessEvent.disconnectDeviceDetails
    assertThat(disconnectDeviceDetails.success).isTrue()
    assertThat(disconnectDeviceDetails.userDisconnected).isFalse()
  }

  @Test
  fun trackDisconnectDeviceFailureMetricWhenErrorDisconnectingDevice() = runBlockingWithTimeout {
    // Override default connection setup
    setupConnection { reservationName ->
      object :
        FakeDirectAccessConnection(
          directAccessReservationManager,
          reservationName,
          scope.createChildScope(true),
        ) {
        override suspend fun closeConnection(stateReason: StateReason) = throw Exception()
      }
    }
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()
    yieldUntil {
      template.activeDevice?.connection?.state?.value?.connection is ConnectionState.Connected
    }
    findUsageEvent(CONNECT_DEVICE)

    try {
      handle.deactivationAction.deactivate()
      fail("Expected an exception to be thrown")
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
      template.activeDevice?.connection?.state?.value?.connection is ConnectionState.Connected
    }
    handle.reservationAction.endReservation()
    yieldUntil { handle.connectionState is ConnectionState.Disconnected }

    val studioEvent = findUsageEvent(END_RESERVATION)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(END_RESERVATION)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()

    val endReservationDetails = directAccessEvent.endReservationDetails
    assertThat(endReservationDetails.success).isTrue()
    assertThat(endReservationDetails.connectionMetrics.maxLatencyMs).isEqualTo(100)
    assertThat(endReservationDetails.connectionMetrics.p90LatencyMs).isEqualTo(90)
    assertThat(endReservationDetails.connectionMetrics.p50LatencyMs).isEqualTo(50)
    assertThat(endReservationDetails.endReservationType).isEqualTo(FORCE_CHECK_IN)
  }

  @Test
  fun endReservationNotTrackedOnUserLogOut() = runBlockingWithTimeout {
    setupConnection { reservationName ->
      getSuccessFulDisconnectTestConnection(reservationName, scope.createChildScope(true))
    }
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()
    yieldUntil {
      template.activeDevice?.connection?.state?.value?.connection is ConnectionState.Connected
    }

    loginUsersRule.logOut("test@google.com")

    findUsageEvent(DISCONNECT_DEVICE)

    assertThat(tracker.usages.any { it.studioEvent.directAccessUsageEvent.type == END_RESERVATION })
      .isFalse()
  }

  @Test
  fun trackEndReservationSuccessMetricWhenAutoEndReservation() = runBlockingWithTimeout {
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    yieldUntil {
      template.activeDevice?.connection?.state?.value?.connection is ConnectionState.Connected
    }
    val reservationFlow =
      directAccessReservationManager.fetchReservationFlow(handle.reservation.name)
    reservationFlow.waitUntilActive()

    // Update the reservation to EXPIRED state
    (reservationFlow as MutableStateFlow).update {
      it.toBuilder().apply { sessionState = Reservation.SessionState.EXPIRED }.build()
    }
    yieldUntil { reservationFlow.value.sessionState.isClosed() }

    val studioEvent = findUsageEvent(END_RESERVATION)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(END_RESERVATION)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()

    val endReservationDetails = directAccessEvent.endReservationDetails
    assertThat(endReservationDetails.success).isTrue()
    assertThat(endReservationDetails.connectionMetrics.maxLatencyMs).isEqualTo(100)
    assertThat(endReservationDetails.connectionMetrics.p90LatencyMs).isEqualTo(90)
    assertThat(endReservationDetails.connectionMetrics.p50LatencyMs).isEqualTo(50)
    assertThat(endReservationDetails.endReservationType).isEqualTo(EXPIRE)
  }

  @Test
  fun trackEndReservationSuccessMetricWhenReservationStateFinished() = runBlockingWithTimeout {
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    yieldUntil {
      template.activeDevice?.connection?.state?.value?.connection is ConnectionState.Connected
    }
    val reservationFlow =
      directAccessReservationManager.fetchReservationFlow(handle.reservation.name)
    reservationFlow.waitUntilActive()

    // Simulates force check-in in other project.
    (reservationFlow as MutableStateFlow).update {
      it.toBuilder().apply { sessionState = Reservation.SessionState.FINISHED }.build()
    }
    yieldUntil { reservationFlow.value.sessionState.isClosed() }

    val studioEvent = findUsageEvent(END_RESERVATION)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(END_RESERVATION)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()

    val endReservationDetails = directAccessEvent.endReservationDetails
    assertThat(endReservationDetails.success).isTrue()
    assertThat(endReservationDetails.connectionMetrics.maxLatencyMs).isEqualTo(100)
    assertThat(endReservationDetails.connectionMetrics.p90LatencyMs).isEqualTo(90)
    assertThat(endReservationDetails.connectionMetrics.p50LatencyMs).isEqualTo(50)
    assertThat(endReservationDetails.endReservationType).isEqualTo(FORCE_CHECK_IN)
  }

  @Test
  fun trackEndReservationFailMetricWhenReservationEndsDueToError() = runBlockingWithTimeout {
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    yieldUntil {
      template.activeDevice?.connection?.state?.value?.connection is ConnectionState.Connected
    }
    val reservationFlow =
      directAccessReservationManager.fetchReservationFlow(handle.reservation.name)
    reservationFlow.waitUntilActive()
    (reservationFlow as MutableStateFlow).update {
      it.toBuilder().apply { sessionState = Reservation.SessionState.ERROR }.build()
    }
    yieldUntil { reservationFlow.value.sessionState == Reservation.SessionState.ERROR }

    val studioEvent = findUsageEvent(END_RESERVATION)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(END_RESERVATION)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()
    assertThat(directAccessEvent.failureReason).isEqualTo(UNKNOWN_FAILURE)

    val endReservationDetails = directAccessEvent.endReservationDetails
    assertThat(endReservationDetails.success).isFalse()
    assertThat(endReservationDetails.connectionMetrics.maxLatencyMs).isEqualTo(100)
    assertThat(endReservationDetails.connectionMetrics.p90LatencyMs).isEqualTo(90)
    assertThat(endReservationDetails.connectionMetrics.p50LatencyMs).isEqualTo(50)
    assertThat(endReservationDetails.endReservationType).isEqualTo(ERROR)
  }

  @Test
  fun testEndReservationFailMetricWhenDeviceNotAllocated() = runBlockingWithTimeout {
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    yieldUntil {
      template.activeDevice?.connection?.state?.value?.connection is ConnectionState.Connected
    }
    val reservationFlow =
      directAccessReservationManager.fetchReservationFlow(handle.reservation.name)
    reservationFlow.waitUntilActive()
    (reservationFlow as MutableStateFlow).update {
      it.toBuilder().apply { sessionState = Reservation.SessionState.UNAVAILABLE }.build()
    }
    yieldUntil { reservationFlow.value.sessionState == Reservation.SessionState.UNAVAILABLE }

    val studioEvent = findUsageEvent(END_RESERVATION)
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type).isEqualTo(END_RESERVATION)
    assertThat(directAccessEvent.hasDeviceSessionId()).isTrue()
    assertThat(directAccessEvent.failureReason).isEqualTo(FAILED_TO_ALLOCATE_DEVICE)

    val endReservationDetails = directAccessEvent.endReservationDetails
    assertThat(endReservationDetails.success).isFalse()
    assertThat(endReservationDetails.endReservationType).isEqualTo(ERROR)
  }

  @Test
  fun trackEndReservationFailMetricWhenErrorEndingReservation() = runBlockingWithTimeout {
    // Override default connection setup
    setupConnection { reservationName ->
      object :
        FakeDirectAccessConnection(
          directAccessReservationManager,
          reservationName,
          scope.createChildScope(true),
        ) {
        override suspend fun endReservation(withGracePeriod: Boolean) = throw Exception()
      }
    }
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    yieldUntil {
      template.activeDevice?.connection?.state?.value?.connection is ConnectionState.Connected
    }
    directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()

    try {
      handle.reservationAction.endReservation()
      fail("Expected an exception to be thrown")
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
    assertThat(endReservationDetails.connectionMetrics.maxLatencyMs).isEqualTo(100)
    assertThat(endReservationDetails.connectionMetrics.p90LatencyMs).isEqualTo(90)
    assertThat(endReservationDetails.connectionMetrics.p50LatencyMs).isEqualTo(50)
  }

  private suspend fun findUsageEvent(type: DirectAccessUsageEventType): AndroidStudioEvent {
    yieldUntil { tracker.usages.any { it.studioEvent.isEventOfType(type) } }
    return tracker.usages
      .first { it.studioEvent.isEventOfType(type) }
      .studioEvent
      .also {
        assertThat(it.deviceInfo.deviceProvisionerId).isEqualTo(PLUGIN_ID)
        assertThat(it.deviceInfo.deviceType).isEqualTo(DeviceType.CLOUD_PHYSICAL)
        assertThat(it.hasProductDetails()).isTrue()
        with(it.productDetails) {
          assertThat(hasProduct()).isTrue()
          assertThat(hasBuild()).isTrue()
          assertThat(hasVersion()).isTrue()
          assertThat(hasChannel()).isTrue()
        }
      }
  }

  private fun AndroidStudioEvent.isEventOfType(type: DirectAccessUsageEventType) =
    directAccessUsageEvent.type == type

  private fun getSuccessFulDisconnectTestConnection(
    reservationName: String,
    deviceScope: CoroutineScope,
  ) =
    object :
      FakeDirectAccessConnection(directAccessReservationManager, reservationName, deviceScope) {
      override suspend fun connect() {
        state.update {
          it.copy(connection = ConnectionState.Connecting(StateReason.USER_INITIATED))
        }
        session.hostServices.connect(deviceAddress()!!)
        super.connect()
      }

      override suspend fun closeConnection(stateReason: StateReason) {
        state.update { it.copy(connection = ConnectionState.Disconnecting(stateReason)) }
        session.hostServices.disconnect(deviceAddress()!!)
        super.closeConnection(stateReason)
      }
    }
}
