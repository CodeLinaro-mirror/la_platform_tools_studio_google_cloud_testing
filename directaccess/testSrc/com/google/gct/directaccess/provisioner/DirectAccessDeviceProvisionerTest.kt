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

import com.android.adblib.DeviceList
import com.android.adblib.DevicePropertyNames
import com.android.adblib.DeviceSelector
import com.android.adblib.DeviceState
import com.android.adblib.scope
import com.android.adblib.testing.FakeAdbSession
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.sdklib.deviceprovisioner.DeviceActionException
import com.android.sdklib.deviceprovisioner.DeviceProvisioner
import com.android.sdklib.deviceprovisioner.DeviceState.Connected
import com.android.sdklib.deviceprovisioner.DeviceState.Disconnected
import com.android.sdklib.deviceprovisioner.ReservationState
import com.android.testutils.MockitoKt.any
import com.android.testutils.MockitoKt.mock
import com.android.testutils.MockitoKt.whenever
import com.android.testutils.VirtualTimeScheduler
import com.android.tools.adbbridge.Reservation
import com.android.tools.analytics.TestUsageTracker
import com.android.tools.analytics.UsageTracker
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.TestUtils.deviceInfoListProvider
import com.google.gct.login.GoogleLogin
import com.google.gct.login.LoginState
import com.google.services.firebase.directaccess.client.DirectAccessConnection
import com.google.services.firebase.directaccess.client.DirectAccessReservationManager
import com.google.services.firebase.directaccess.client.FakeDirectAccessConnection
import com.google.services.firebase.directaccess.client.FakeDirectAccessGrpcService
import com.google.services.firebase.directaccess.client.deviceAddress
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType.CONNECT_DEVICE
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType.RESERVE_DEVICE
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationsManager
import com.studiogrpc.testutils.GrpcConnectionRule
import icons.StudioIcons
import java.lang.RuntimeException
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.doReturn

class DirectAccessDeviceProvisionerTest {

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
  private var isOAuthTokenAvailable: Boolean = false

  @Before
  fun setUp() = runBlockingWithTimeout {
    mockGoogleLogin = projectRule.mockService(GoogleLogin::class.java)
    doReturn(true).whenever(mockGoogleLogin).isLoggedIn
    (LoginState.loggedIn as MutableStateFlow<Boolean>).value = true
    scope = CoroutineScope(MoreExecutors.directExecutor().asCoroutineDispatcher())
    isOAuthTokenAvailable = true
    directAccessReservationManager =
      DirectAccessReservationManager("testProject", scope, grpcConnectionRule.channel) {
        if (!isOAuthTokenAvailable) throw RuntimeException()
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
        deviceInfoListProvider
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
    val mockDirectAccessService = projectRule.mockProjectService(DirectAccessService::class.java)
    whenever(mockDirectAccessService.reservationManager).thenReturn(directAccessReservationManager)
    whenever(mockDirectAccessService.connectToReservation(any(), any())).thenAnswer {
      val reservationName = it.arguments[0] as String
      val deviceScope = it.arguments[1] as CoroutineScope
      createConnection(reservationName, deviceScope).also { conn -> fakeConnection = conn }
    }
  }

  @Test
  fun testTemplates() = runBlockingWithTimeout {
    // getAvailableDevices() is called in the init block of FirebaseDeviceProvisioner
    // Wait for setup to complete
    yieldUntil { provisioner.templates.value.size == 3 }

    // Assert
    assertThat(provisioner.templates.value[0].properties.title).isEqualTo("Google Pixel 5")
    assertThat(provisioner.templates.value[1].properties.title).isEqualTo("Google Pixel 6")
    assertThat(provisioner.templates.value[2].properties.title).isEqualTo("Google Pixel 6 Pro")

    // Log out
    (LoginState.loggedIn as MutableStateFlow<Boolean>).value = false
    yieldUntil { provisioner.templates.value.isEmpty() }

    // Login again without access.
    isOAuthTokenAvailable = false
    (LoginState.loggedIn as MutableStateFlow<Boolean>).value = true
    plugin.updateTemplates(scope)
    yieldUntil { provisioner.templates.value.isEmpty() }
  }

  @Test
  fun activateAndDeactivateDevice() = runBlockingWithTimeout {
    val deviceInfo = deviceInfoListProvider()[0]
    val template = plugin.templates.value[0]

    // Activate a new device before it becomes online
    template.activationAction.activate()
    yieldUntil { provisioner.devices.value.isNotEmpty() }
    val devices = provisioner.devices.value
    assertThat(devices.size).isEqualTo(1)
    val device = devices[0]
    val state = device.stateFlow
    assertThat(device.sourceTemplate).isEqualTo(template)
    assertThat(state.value).isInstanceOf(Disconnected::class.java)
    assertThat(state.value.isTransitioning).isTrue()
    assertThat(state.value.status).isEqualTo("Reserving a device...")
    val properties = state.value.properties
    assertThat(properties.androidVersion!!.apiLevel).isEqualTo(deviceInfo.api)
    assertThat(properties.model).isEqualTo(deviceInfo.name)
    assertThat(properties.manufacturer).isEqualTo(deviceInfo.manufacturer)

    yieldUntil { state.value.reservation?.state == ReservationState.ACTIVE }
    assertThat(state.value.status).isEqualTo("Connecting to device...")

    // Bring the device online by claiming a matched connected device.
    val serialNumber = fakeConnection.deviceAddress()!!.address
    // We intentionally add a suffix to verify if the properties have been updated.
    val suffix = "-connected"
    session.deviceServices.configureDeviceProperties(
      DeviceSelector.fromSerialNumber(serialNumber),
      mapOf(
        "ro.serialno" to "physicaldevice",
        DevicePropertyNames.RO_BUILD_VERSION_SDK to deviceInfo.api.toString(),
        DevicePropertyNames.RO_PRODUCT_MANUFACTURER to deviceInfo.manufacturer + suffix,
        DevicePropertyNames.RO_PRODUCT_MODEL to deviceInfo.name + suffix,
      )
    )
    session.hostServices.devices =
      DeviceList(listOf(com.android.adblib.DeviceInfo(serialNumber, DeviceState.ONLINE)), listOf())
    yieldUntil { state.value.connectedDevice != null }
    assertThat(state.value).isInstanceOf(Connected::class.java)
    assertThat(state.value.reservation!!.stateMessage).isEmpty()
    state.value.properties.also {
      assertThat(it.androidVersion!!.apiLevel).isEqualTo(deviceInfo.api)
      assertThat(it.model).isEqualTo(deviceInfo.name + suffix)
      assertThat(it.manufacturer).isEqualTo(deviceInfo.manufacturer + suffix)
    }

    // Deactivate the device.
    state.value.connectedDevice!!.scope.cancel()
    device.deactivationAction!!.deactivate()
    // Cancel Reservation.
    fakeConnection.endReservation()
    yieldUntil { plugin.devices.value.isEmpty() }
    session.hostServices.devices = DeviceList(listOf(), listOf())
    yieldUntil { state.value is Disconnected }
    yieldUntil { template.activationAction.presentation.value.enabled }
  }

  @Test
  fun deactivateBeforeConnected() = runBlockingWithTimeout {
    val template = plugin.templates.value[0]

    // Activate a new device before it becomes online
    template.activationAction.activate()
    yieldUntil { provisioner.devices.value.isNotEmpty() }
    val device = provisioner.devices.value[0]
    val state = device.stateFlow
    assertThat(state.value.isTransitioning).isTrue()
    assertThat(state.value).isInstanceOf(Disconnected::class.java)

    device.deactivationAction!!.deactivate()
    assertThat(state.value.isTransitioning).isFalse()
    assertThat(state.value).isInstanceOf(Disconnected::class.java)
    val activationPresentation = device.activationAction!!.presentation
    assertThat(activationPresentation.value.enabled).isTrue()
    assertThat(activationPresentation.value.icon).isEqualTo(StudioIcons.Avd.RUN)
  }

  @Test
  fun deviceGoesAwayWhenReservationEnds() = runBlockingWithTimeout {
    val template = plugin.templates.value[0]
    template.activationAction.activate()
    yieldUntil { provisioner.devices.value.isNotEmpty() }

    val device = provisioner.devices.value.first()
    val job = device.scope.launch { device.stateFlow.collect {} }

    fakeConnection.endReservation()

    yieldUntil { provisioner.devices.value.isEmpty() }

    // Once the device is removed, the provisioner should cancel the job
    job.join()
    assertThat(job.isCancelled).isTrue()
  }

  @Test
  fun createDevicesFromExistingReservations() = runBlockingWithTimeout {
    val deviceInfo = deviceInfoListProvider()[0]
    directAccessReservationManager.createReservation(deviceInfo.codename, deviceInfo.api.toString())
    plugin.updateReservations()
    yieldUntil { provisioner.devices.value.isNotEmpty() }
  }

  @Test
  fun extendReservationFromDeviceHandle() = runBlockingWithTimeout {
    val template = plugin.templates.value[0]

    // Activate device
    template.activationAction.activate()
    yieldUntil { provisioner.devices.value.isNotEmpty() }
    assertThat(provisioner.devices.value.size).isEqualTo(1)

    val handle = (provisioner.devices.value[0])
    yieldUntil { handle.state.reservation != null }
    val oldEndTime = handle.state.reservation?.endTime
    val newEndTime = handle.reservationAction?.reserve(Duration.ofSeconds(100))
    assertThat(newEndTime?.epochSecond).isAtLeast(oldEndTime?.plusSeconds(100)?.epochSecond)
    assertThat(handle.state.reservation?.endTime?.epochSecond)
      .isAtLeast(oldEndTime?.plusSeconds(100)?.epochSecond)
  }

  @Test
  fun testActionsInNotificationOnDisconnectDevice() = runBlockingWithTimeout {
    val template = plugin.templates.value[0]

    template.activationAction.activate()
    yieldUntil { provisioner.devices.value.isNotEmpty() }

    val handle = (template as DirectAccessDeviceTemplate).activeDevice
    assertThat(handle).isNotNull()

    handle?.deactivationAction?.deactivate()
    yieldUntil { handle?.connectionState == DirectAccessConnection.ConnectionState.DISCONNECTED }

    val firstNotificationsList = getNotifications()
    assertThat(firstNotificationsList.size).isEqualTo(1)

    firstNotificationsList[0].assertNotification {
      val reconnectAction = it.actions[0] as NotificationAction
      reconnectAction.actionPerformed(mock(), it)
      yieldUntil { handle?.connectionState != DirectAccessConnection.ConnectionState.DISCONNECTED }
      assertThat(handle?.connectionState)
        .isEqualTo(DirectAccessConnection.ConnectionState.CONNECTED)
    }

    // Expiring a notification does not guarantee it is no longer visible. Wait for the notification
    // to be cleared.
    yieldUntil { getNotifications().isEmpty() }

    // Device will reconnect after previous action. Disconnect again to show notification for force
    // check-in
    handle?.deactivationAction?.deactivate()

    val secondNotificationsList = getNotifications()
    assertThat(secondNotificationsList.size).isEqualTo(1)

    secondNotificationsList[0].assertNotification {
      val forceCheckInAction = it.actions[1] as NotificationAction
      forceCheckInAction.actionPerformed(mock(), it)
      yieldUntil { handle?.reservation?.sessionState != Reservation.SessionState.ACTIVE }
      assertThat(handle?.connectionState)
        .isEqualTo(DirectAccessConnection.ConnectionState.DISCONNECTED)
      yieldUntil { plugin.devices.value.isEmpty() }
    }
  }

  @Test
  fun logReservationSuccessMetricsWhenSuccessReservingDevice() = runBlockingWithTimeout {
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
  fun logReservationFailMetricWhenErrorReservingDevice() = runBlockingWithTimeout {
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
    assertThat(directAccessEvent.failureReason)
      .isEqualTo(DirectAccessUsageEvent.FailureReason.UNKNOWN_FAILURE)

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
    assertThat(directAccessEvent.failureReason)
      .isEqualTo(DirectAccessUsageEvent.FailureReason.UNKNOWN_FAILURE)

    val connectDeviceDetails = directAccessEvent.connectDeviceDetails
    assertThat(connectDeviceDetails.success).isFalse()
    assertThat(connectDeviceDetails.reconnect).isFalse()
    assertThat(connectDeviceDetails.hasConnectTimeMs()).isFalse()
  }

  private suspend fun Notification.assertNotification(
    actionAssertBlock: suspend (Notification) -> Unit
  ) {
    assertThat(groupId).isEqualTo("Direct Access")
    assertThat(type).isEqualTo(NotificationType.INFORMATION)
    assertThat(title).isEqualTo("Firebase device stopped")
    assertThat(content)
      .isEqualTo(
        "You can reconnect to the same device for up to 5 minutes before the device is wiped"
      )
    assertThat(actions.size).isEqualTo(2)
    assertThat(isExpired).isFalse()
    assertThat(actions[0].templateText).isEqualTo("Reconnect to Device")
    assertThat(actions[1].templateText).isEqualTo("Force check-in device")
    actionAssertBlock(this)
    // Make sure the notification expires as both actions expire it.
    yieldUntil { isExpired }
  }
  private val DirectAccessDeviceHandle.connectionState: DirectAccessConnection.ConnectionState
    get() = connection.state.value.connection
  private val DirectAccessDeviceHandle.reservation: Reservation
    get() = connection.state.value.reservation

  private fun getNotifications() =
    NotificationsManager.getNotificationsManager()
      .getNotificationsOfType(Notification::class.java, projectRule.project)

  private suspend fun findUsageEvent(type: DirectAccessUsageEventType): AndroidStudioEvent {
    yieldUntil { tracker.usages.any { it.studioEvent.isEventOfType(type) } }
    return tracker.usages.first { it.studioEvent.isEventOfType(type) }.studioEvent
  }

  private fun AndroidStudioEvent.isEventOfType(type: DirectAccessUsageEventType) =
    directAccessUsageEvent.type == type
}
