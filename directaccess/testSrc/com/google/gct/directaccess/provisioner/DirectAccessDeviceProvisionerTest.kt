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
import com.android.adblib.utils.createChildScope
import com.android.sdklib.deviceprovisioner.DeviceProvisioner
import com.android.sdklib.deviceprovisioner.DeviceState.Connected
import com.android.sdklib.deviceprovisioner.DeviceState.Disconnected
import com.android.sdklib.deviceprovisioner.ReservationState
import com.android.sdklib.deviceprovisioner.Resolution
import com.android.sdklib.deviceprovisioner.testing.testDeviceIcons
import com.android.testutils.MockitoKt.any
import com.android.testutils.MockitoKt.mock
import com.android.testutils.MockitoKt.whenever
import com.android.tools.adbbridge.Reservation
import com.android.tools.idea.testing.disposable
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import com.google.gct.directaccess.DirectAccessApplicationService
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.TestUtils.connectionState
import com.google.gct.directaccess.TestUtils.deviceInfoListProvider
import com.google.gct.directaccess.TestUtils.deviceName
import com.google.gct.directaccess.TestUtils.getNotifications
import com.google.gct.directaccess.TestUtils.reservation
import com.google.gct.directaccess.TestUtils.updateReservations
import com.google.gct.login.LoginState
import com.google.gct.login.LoginStateRule
import com.google.gct.login.LoginStatus
import com.google.services.firebase.directaccess.client.DirectAccessConnection
import com.google.services.firebase.directaccess.client.DirectAccessConnectionManager
import com.google.services.firebase.directaccess.client.DirectAccessReservationManager
import com.google.services.firebase.directaccess.client.FakeDirectAccessConnection
import com.google.services.firebase.directaccess.client.FakeDirectAccessGrpcService
import com.google.services.firebase.directaccess.client.deviceAddress
import com.google.services.firebase.directaccess.client.waitUntilActive
import com.google.wireless.android.sdk.stats.DeviceInfo
import com.intellij.icons.AllIcons
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.replaceService
import com.studiogrpc.testutils.GrpcConnectionRule
import icons.StudioIcons
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.swing.Icon
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
import org.mockito.Mockito
import org.mockito.Mockito.doReturn

class DirectAccessDeviceProvisionerTest {

  private val service = FakeDirectAccessGrpcService()
  private val projectRule = ProjectRule()
  private val grpcConnectionRule = GrpcConnectionRule(listOf(service))
  private val loginStateRule = LoginStateRule(LoginStatus.LoggedIn("test@gmail.com"))

  @get:Rule
  val ruleChain: RuleChain =
    RuleChain.outerRule(projectRule).around(grpcConnectionRule).around(loginStateRule)

  private val session = FakeAdbSession()
  private lateinit var plugin: DirectAccessDeviceProvisionerPlugin
  private lateinit var provisioner: DeviceProvisioner
  private lateinit var directAccessReservationManager: DirectAccessReservationManager
  private lateinit var fakeConnection: FakeDirectAccessConnection
  private lateinit var scope: CoroutineScope
  private var isOAuthTokenAvailable: Boolean = false

  @Before
  fun setUp() = runBlockingWithTimeout {
    scope = CoroutineScope(MoreExecutors.directExecutor().asCoroutineDispatcher())
    isOAuthTokenAvailable = true
    directAccessReservationManager =
      DirectAccessReservationManager("testProject", scope, grpcConnectionRule.channel) {
        if (!isOAuthTokenAvailable) throw RuntimeException()
        "testToken"
      }
    setupConnection { reservationName ->
      FakeDirectAccessConnection(
        directAccessReservationManager,
        reservationName,
        scope.createChildScope(true)
      )
    }
    plugin =
      DirectAccessDeviceProvisionerPlugin(
        session.scope,
        projectRule.project,
        deviceInfoListProvider
      )
    provisioner = DeviceProvisioner.create(session, listOf(plugin), testDeviceIcons)
    yieldUntil { provisioner.templates.value.isNotEmpty() }
  }

  @After
  fun tearDown() = runBlockingWithTimeout {
    scope.cancel()
    session.close()
  }

  private fun setupConnection(createConnection: (String) -> FakeDirectAccessConnection) {
    val mockDirectAccessApplicationService = mock<DirectAccessApplicationService>()
    val mockDirectAccessService = mock<DirectAccessService>()
    val cloudProjectName = "test-project"
    val cloudProjectFlow = MutableStateFlow<String?>(cloudProjectName)
    scope.launch {
      service<LoginState>().loginStatus.collect {
        cloudProjectFlow.value = if (it is LoginStatus.LoggedIn) cloudProjectName else null
      }
    }
    doReturn(cloudProjectFlow).whenever(mockDirectAccessService).cloudProjectFlow
    val mockDirectAccessConnectionManager = mock<DirectAccessConnectionManager>()
    doReturn(directAccessReservationManager)
      .whenever(mockDirectAccessApplicationService)
      .getReservationManager(any())
    doReturn(mockDirectAccessConnectionManager)
      .whenever(mockDirectAccessApplicationService)
      .getConnectionManager(any())
    Mockito.doAnswer {
        val reservationName = it.arguments[0] as String
        createConnection(reservationName).also { conn ->
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
      .whenever(mockDirectAccessConnectionManager)
      .create(any())
    projectRule.project.replaceService(
      DirectAccessService::class.java,
      mockDirectAccessService,
      projectRule.disposable
    )
    ApplicationManager.getApplication()
      .replaceService(
        DirectAccessApplicationService::class.java,
        mockDirectAccessApplicationService,
        projectRule.disposable
      )
  }

  @Test
  fun testTemplates() = runBlockingWithTimeout {
    // getAvailableDevices() is called in the init block of FirebaseDeviceProvisioner
    // Wait for setup to complete
    yieldUntil { provisioner.templates.value.isNotEmpty() }
    yieldUntil {
      provisioner.templates.value.all { it.activationAction.presentation.value.enabled }
    }

    // Assert
    assertThat(provisioner.templates.value[0].properties.title).isEqualTo("Google Pixel 5")
    assertThat(provisioner.templates.value[0].properties.resolution).isEqualTo(Resolution(100, 200))
    assertThat(provisioner.templates.value[0].properties.density).isEqualTo(300)
    assertThat(provisioner.templates.value[0].properties.icon)
      .isEqualTo(StudioIcons.DeviceExplorer.FIREBASE_DEVICE_PHONE)
    assertThat(provisioner.templates.value[0].properties.isRemote).isTrue()
    assertThat(provisioner.templates.value[1].properties.title).isEqualTo("Google Pixel 6")
    assertThat(provisioner.templates.value[1].properties.resolution).isEqualTo(Resolution(200, 300))
    assertThat(provisioner.templates.value[1].properties.density).isEqualTo(400)
    assertThat(provisioner.templates.value[1].properties.isRemote).isTrue()
    assertThat(provisioner.templates.value[2].properties.title).isEqualTo("Google Pixel 6 Pro")
    assertThat(provisioner.templates.value[2].properties.resolution).isEqualTo(Resolution(300, 400))
    assertThat(provisioner.templates.value[2].properties.density).isEqualTo(500)
    assertThat(provisioner.templates.value[2].properties.isRemote).isTrue()
    assertThat(provisioner.templates.value[3].properties.title).isEqualTo("Google Pixel Watch")
    assertThat(provisioner.templates.value[3].properties.resolution).isEqualTo(Resolution(50, 100))
    assertThat(provisioner.templates.value[3].properties.density).isEqualTo(150)
    assertThat(provisioner.templates.value[3].properties.isRemote).isTrue()

    // Log out
    loginStateRule.state.value = LoginStatus.LoggedOut
    yieldUntil {
      provisioner.templates.value.all { !it.activationAction.presentation.value.enabled }
    }

    // Login without access.
    isOAuthTokenAvailable = false
    loginStateRule.state.value = LoginStatus.LoggedIn("test@gmail.com")
    yieldUntil {
      provisioner.templates.value.all { !it.activationAction.presentation.value.enabled }
    }
    // Login with access.
    isOAuthTokenAvailable = true
    loginStateRule.state.value = LoginStatus.LoggedIn("test2@gmail.com")
    yieldUntil {
      provisioner.templates.value.all { it.activationAction.presentation.value.enabled }
    }
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
      assertThat(it.icon).isEqualTo(StudioIcons.DeviceExplorer.FIREBASE_DEVICE_PHONE)
      assertThat(it.androidVersion!!.apiLevel).isEqualTo(deviceInfo.api)
      assertThat(it.model).isEqualTo(deviceInfo.name + suffix)
      assertThat(it.manufacturer).isEqualTo(deviceInfo.manufacturer + suffix)
      assertThat(it.resolution?.height).isEqualTo(2400)
      assertThat(it.resolution?.width).isEqualTo(1080)
      assertThat(it.isRemote).isTrue()

      it.deviceInfoProto.apply {
        assertThat(model).isEqualTo(deviceInfo.name + suffix)
        assertThat(manufacturer).isEqualTo(deviceInfo.manufacturer + suffix)
        assertThat(deviceType).isEqualTo(DeviceInfo.DeviceType.CLOUD_PHYSICAL)
        assertThat(deviceProvisionerId).isEqualTo(PLUGIN_ID)
        assertThat(anonymizedSerialNumber).isNotEmpty()
      }
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

    // Disconnect after reservation become active.
    yieldUntil { state.value.reservation?.state == ReservationState.ACTIVE }
    device.deactivationAction!!.deactivate()
    assertThat(state.value.isTransitioning).isFalse()
    assertThat(state.value).isInstanceOf(Disconnected::class.java)
    val activationPresentation = device.activationAction!!.presentation
    yieldUntil { activationPresentation.value.enabled }
  }

  @Test
  fun connectionFailed() = runBlockingWithTimeout {
    setupConnection { reservationName ->
      object :
        FakeDirectAccessConnection(
          directAccessReservationManager,
          reservationName,
          scope.createChildScope(true)
        ) {
        override suspend fun connect() {
          throw RuntimeException("Failed connection.")
        }
      }
    }

    val template = plugin.templates.value[0]
    // Activate a new device from template.
    template.activationAction.activate()
    yieldUntil { provisioner.devices.value.isNotEmpty() }
    val device = provisioner.devices.value[0]
    // Device disconnected with an exception thrown from DirectAccessConnection.
    val state = device.stateFlow
    assertThat(state.value.isTransitioning).isFalse()
    assertThat(state.value).isInstanceOf(Disconnected::class.java)
    yieldUntil { device.activationAction?.presentation?.value?.enabled == true }
  }

  @Test
  fun deactivateBeforeReservationActive() = runBlockingWithTimeout {
    val template = plugin.templates.value[0]

    // Activate a new device before it becomes online
    template.activationAction.activate()
    yieldUntil { provisioner.devices.value.isNotEmpty() }
    val device = provisioner.devices.value[0]
    val state = device.stateFlow
    assertThat(state.value.isTransitioning).isTrue()
    assertThat(state.value).isInstanceOf(Disconnected::class.java)

    // Disconnect before reservation become active.
    device.deactivationAction!!.deactivate()
    assertThat(state.value.isTransitioning).isFalse()
    assertThat(state.value).isInstanceOf(Disconnected::class.java)
    yieldUntil { provisioner.devices.value.isEmpty() }
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
    yieldUntil { plugin.fetchReservations()?.isNotEmpty() == true }
    plugin.updateReservations()
    yieldUntil { provisioner.devices.value.isNotEmpty() }
  }

  @Test
  fun deviceUpdatedWithLoginState() = runBlockingWithTimeout {
    val deviceInfo = deviceInfoListProvider()[0]
    directAccessReservationManager.createReservation(deviceInfo.codename, deviceInfo.api.toString())
    yieldUntil { plugin.fetchReservations()?.isNotEmpty() == true }
    plugin.updateReservations()
    yieldUntil { provisioner.devices.value.isNotEmpty() }

    // Device removed after logout.
    loginStateRule.state.value = LoginStatus.LoggedOut
    yieldUntil {
      provisioner.templates.value.all { !it.activationAction.presentation.value.enabled }
    }
    yieldUntil { provisioner.devices.value.isEmpty() }

    // Login again to re-discover the device.
    loginStateRule.state.value = LoginStatus.LoggedIn("test@gmail.com")
    yieldUntil {
      provisioner.templates.value.any { (it as DirectAccessDeviceTemplate).activeDevice != null }
    }
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
    assertThat(newEndTime?.epochSecond).isEqualTo(oldEndTime?.plusSeconds(100)?.epochSecond)
    assertThat(handle.state.reservation?.endTime?.epochSecond)
      .isEqualTo(oldEndTime?.plusSeconds(100)?.epochSecond)
  }

  @Test
  fun testActionsInNotificationOnDisconnectDevice() = runBlockingWithTimeout {
    val template = plugin.templates.value[0]

    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    yieldUntil { provisioner.devices.value.isNotEmpty() }

    directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()

    handle.deactivationAction.deactivate()
    yieldUntil { handle.connectionState == DirectAccessConnection.ConnectionState.DISCONNECTED }

    val firstNotificationsList = getNotifications(projectRule.project)
    assertThat(firstNotificationsList.size).isEqualTo(1)

    firstNotificationsList[0].assertDeviceDisconnectedNotification(handle) {
      val reconnectAction = it.actions[0] as NotificationAction
      reconnectAction.actionPerformed(mock(), it)
      yieldUntil { handle.connectionState != DirectAccessConnection.ConnectionState.DISCONNECTED }
      assertThat(handle.connectionState).isEqualTo(DirectAccessConnection.ConnectionState.CONNECTED)
    }

    // Expiring a notification does not guarantee it is no longer visible. Wait for the notification
    // to be cleared.
    yieldUntil { getNotifications(projectRule.project).isEmpty() }

    // Device will reconnect after previous action. Disconnect again to show notification for force
    // check-in
    handle.deactivationAction.deactivate()

    val secondNotificationsList = getNotifications(projectRule.project)
    assertThat(secondNotificationsList.size).isEqualTo(1)

    secondNotificationsList[0].assertDeviceDisconnectedNotification(handle) {
      val forceCheckInAction = it.actions[1] as NotificationAction
      forceCheckInAction.actionPerformed(mock(), it)
      yieldUntil { handle.reservation.sessionState != Reservation.SessionState.ACTIVE }
      assertThat(handle.connectionState)
        .isEqualTo(DirectAccessConnection.ConnectionState.DISCONNECTED)
      yieldUntil { plugin.devices.value.isEmpty() }
    }
  }

  @Test
  fun testActionsInNotificationOnExpiringReservation() = runBlockingWithTimeout {
    val deviceInfo = deviceInfoListProvider()[0]
    val template = plugin.templates.value[0]

    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    yieldUntil { provisioner.devices.value.isNotEmpty() }
    // Bring the device online by claiming a matched connected device.
    val serialNumber = fakeConnection.deviceAddress()!!.address
    session.deviceServices.configureDeviceProperties(
      DeviceSelector.fromSerialNumber(serialNumber),
      mapOf(
        "ro.serialno" to "physicaldevice",
        DevicePropertyNames.RO_BUILD_VERSION_SDK to deviceInfo.api.toString(),
        DevicePropertyNames.RO_PRODUCT_MANUFACTURER to deviceInfo.manufacturer,
        DevicePropertyNames.RO_PRODUCT_MODEL to deviceInfo.name,
      )
    )
    session.hostServices.devices =
      DeviceList(listOf(com.android.adblib.DeviceInfo(serialNumber, DeviceState.ONLINE)), listOf())

    directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()

    directAccessReservationManager.extendReservation(
      handle.reservation.name,
      Duration.ofMinutes(5).plus(Duration.ofSeconds(10)),
      DirectAccessReservationManager.ReservationExtendType.TTL
    )

    yieldUntil { getNotifications(projectRule.project).isNotEmpty() }
    val firstNotificationsList = getNotifications(projectRule.project)
    assertThat(firstNotificationsList.size).isEqualTo(1)

    firstNotificationsList[0].assertReservationExpiringNotification(handle) {
      val extendAction = it.actions[0] as NotificationAction
      extendAction.actionPerformed(mock(), it)
      yieldUntil {
        handle.reservation.expireTime.seconds ==
          service.instant.epochSecond + TimeUnit.MINUTES.toSeconds(35) + 10
      }
    }

    // Expiring a notification does not guarantee it is no longer visible. Wait for the notification
    // to be cleared.
    yieldUntil { getNotifications(projectRule.project).isEmpty() }
  }

  @Test
  fun testNotificationOnUnexpectedDeviceDisconnection() = runBlockingWithTimeout {
    val template = plugin.templates.value[0]
    template.activationAction.activate()
    yieldUntil { provisioner.devices.value.isNotEmpty() }

    val handle = (template as DirectAccessDeviceTemplate).activeDevice
    assertThat(handle).isNotNull()

    handle?.reservation?.let {
      directAccessReservationManager.fetchReservationFlow(it.name).waitUntilActive()
    }
    session.hostServices.connect(handle!!.connection.deviceAddress()!!)
    yieldUntil { handle.state is Connected }

    session.hostServices.disconnect(handle.connection.deviceAddress()!!)
    yieldUntil { getNotifications(projectRule.project).size == 1 }
    assertThat(getNotifications(projectRule.project)[0].content)
      .matches("You can reconnect to the same Google Pixel 5 .* before the device is wiped")
  }

  @Test
  fun testNotificationExpiringOnDisconnectDevice() = runBlockingWithTimeout {
    val template = plugin.templates.value[0]
    template.activationAction.activate()
    yieldUntil { provisioner.devices.value.isNotEmpty() }
    val handle = (template as DirectAccessDeviceTemplate).activeDevice
    handle?.reservation?.let {
      directAccessReservationManager.fetchReservationFlow(it.name).waitUntilActive()
    }

    handle?.deactivationAction?.deactivate()
    yieldUntil { handle?.connectionState == DirectAccessConnection.ConnectionState.DISCONNECTED }

    val firstNotificationsList = getNotifications(projectRule.project)
    assertThat(firstNotificationsList.size).isEqualTo(1)
    handle?.activationAction?.activate()

    yieldUntil { firstNotificationsList[0].isExpired }
    // Expiring a notification does not guarantee it is no longer visible. Wait for the notification
    // to be cleared.
    yieldUntil { getNotifications(projectRule.project).isEmpty() }

    // Device will reconnect after previous action. Disconnect again to show notification for force
    // check-in
    handle?.deactivationAction?.deactivate()

    val secondNotificationsList = getNotifications(projectRule.project)
    assertThat(secondNotificationsList.size).isEqualTo(1)

    handle?.reservationAction?.endReservation()
    yieldUntil { getNotifications(projectRule.project).isEmpty() }
  }

  @Test
  fun testActionPresentationsWithReconnection() = runBlockingWithTimeout {
    val deviceInfo = deviceInfoListProvider()[0]
    val reservation =
      directAccessReservationManager.createReservation(
        deviceInfo.codename,
        deviceInfo.api.toString()
      )
    directAccessReservationManager.fetchReservationFlow(reservation.name).waitUntilActive()
    plugin.updateReservations()
    yieldUntil { provisioner.devices.value.isNotEmpty() }

    val handle = provisioner.devices.value[0] as DirectAccessDeviceHandle
    assertThat(handle).isNotNull()

    val activationPresentation = handle.activationAction.presentation
    val deactivationPresentation = handle.deactivationAction.presentation
    yieldUntil { activationPresentation.value.enabled }
    activationPresentation.value.let {
      assertThat(it.label).isEqualTo("Connect")
      assertThat(it.icon).isEqualTo(AllIcons.Actions.Resume)
    }

    deactivationPresentation.value.let {
      assertThat(it.label).isEqualTo("Disconnect")
      assertThat(it.enabled).isTrue()
      assertThat(it.icon).isEqualTo(StudioIcons.Avd.STOP)
    }

    handle.activationAction.activate()
    yieldUntil { !activationPresentation.value.enabled }
    assertThat(deactivationPresentation.value.enabled).isTrue()

    // Bring the device online by claiming a matched connected device.
    val serialNumber = handle.connection.deviceAddress()!!.address
    // We intentionally add a suffix to verify if the properties have been updated.
    session.deviceServices.configureDeviceProperties(
      DeviceSelector.fromSerialNumber(serialNumber),
      mapOf(
        "ro.serialno" to "physicaldevice",
        DevicePropertyNames.RO_BUILD_VERSION_SDK to deviceInfo.api.toString(),
        DevicePropertyNames.RO_PRODUCT_MANUFACTURER to deviceInfo.manufacturer,
        DevicePropertyNames.RO_PRODUCT_MODEL to deviceInfo.name,
      )
    )
    session.hostServices.devices =
      DeviceList(listOf(com.android.adblib.DeviceInfo(serialNumber, DeviceState.ONLINE)), listOf())
    yieldUntil { handle.state is Connected }
    assertThat(activationPresentation.value.enabled).isFalse()
    assertThat(deactivationPresentation.value.enabled).isTrue()

    handle.deactivationAction.deactivate()
    session.hostServices.devices = DeviceList(listOf(), listOf())
    yieldUntil { handle.state is Disconnected }

    yieldUntil { activationPresentation.value.enabled }
  }

  @Test
  fun testNoNotificationWhenReservationCancelledBeforeActive() = runBlockingWithTimeout {
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    assertThat(handle.reservation.sessionState).isEqualTo(Reservation.SessionState.REQUESTED)

    handle.deactivationAction.deactivate()
    yieldUntil { handle.reservation.sessionState == Reservation.SessionState.FINISHED }
    yieldUntil { plugin.devices.value.isEmpty() }

    assertThat(getNotifications(projectRule.project).size).isEqualTo(0)
  }

  @Test
  fun testStateChangesToCompleteOnReservationExpiry() = runBlockingWithTimeout {
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    val flow = directAccessReservationManager.fetchReservationFlow(handle.reservation.name)
    flow.waitUntilActive()

    (flow as MutableStateFlow).update {
      it.toBuilder().apply { sessionState = Reservation.SessionState.EXPIRED }.build()
    }
    yieldUntil { flow.value.sessionState == Reservation.SessionState.EXPIRED }

    yieldUntil { handle.stateFlow.value.reservation?.state == ReservationState.COMPLETE }
  }

  @Test
  fun testNoNotificationOnForceCheckIn() = runBlockingWithTimeout {
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    val flow = directAccessReservationManager.fetchReservationFlow(handle.reservation.name)
    flow.waitUntilActive()

    session.hostServices.connect(handle.connection.deviceAddress()!!)
    yieldUntil { handle.stateFlow.value.connectedDevice != null }

    handle.reservationAction.endReservation()
    yieldUntil { handle.stateFlow.value is Disconnected }

    val notifications = getNotifications(projectRule.project)
    assertThat(notifications.size).isEqualTo(0)
  }

  @Test
  fun testNoNotificationOnForceCheckInWhenReservationEndDelayed() = runBlockingWithTimeout {
    setupConnection { reservationName ->
      object :
        FakeDirectAccessConnection(
          directAccessReservationManager,
          reservationName,
          scope.createChildScope(true)
        ) {
        override suspend fun endReservation(withGracePeriod: Boolean) {
          closeConnection()
          // Do not end reservation to simulate delayed/failed end reservation
        }
      }
    }

    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    val flow = directAccessReservationManager.fetchReservationFlow(handle.reservation.name)
    flow.waitUntilActive()

    session.hostServices.connect(handle.connection.deviceAddress()!!)
    yieldUntil { handle.stateFlow.value.connectedDevice != null }

    // User force checks in the device
    handle.reservationAction.endReservation()
    session.hostServices.disconnect(handle.connection.deviceAddress()!!)
    yieldUntil { handle.stateFlow.value is Disconnected }

    val notifications = getNotifications(projectRule.project)
    assertThat(notifications.size).isEqualTo(0)
  }

  @Test
  fun testCorrectIconForPhone() = runBlockingWithTimeout {
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate
    testCorrectIcon(template, StudioIcons.DeviceExplorer.FIREBASE_DEVICE_PHONE)
  }

  @Test
  fun testCorrectIconForWatch() = runBlockingWithTimeout {
    val template = plugin.templates.value[3] as DirectAccessDeviceTemplate
    testCorrectIcon(template, StudioIcons.DeviceExplorer.FIREBASE_DEVICE_WEAR)
  }

  private suspend fun testCorrectIcon(template: DirectAccessDeviceTemplate, icon: Icon) {
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    session.hostServices.connect(handle.connection.deviceAddress()!!)
    yieldUntil { handle.state is Connected }

    assertThat(template.icon).isEqualTo(icon)
    assertThat(handle.icon).isEqualTo(icon)
    assertThat(handle.state.properties.icon).isEqualTo(icon)
  }

  private suspend fun Notification.assertReservationExpiringNotification(
    handle: DirectAccessDeviceHandle,
    actionAssertBlock: suspend (Notification) -> Unit
  ) =
    assertDeviceNotification(
      "Reservation ending in 5 mins",
      "${handle.deviceName} will disconnect in 5 mins. Extend reservation to continue access to the device.",
      handle.icon,
      listOf("Extend 30 mins"),
      actionAssertBlock
    )

  private suspend fun Notification.assertDeviceDisconnectedNotification(
    handle: DirectAccessDeviceHandle,
    actionAssertBlock: suspend (Notification) -> Unit
  ) =
    assertDeviceNotification(
      "${handle.deviceName} on Firebase stopped",
      "You can reconnect to the same ${handle.deviceName} for up to 5 minutes before the device is wiped",
      handle.icon,
      listOf("Reconnect to Device", "Force check-in device"),
      actionAssertBlock
    )

  private suspend fun Notification.assertDeviceNotification(
    title: String,
    content: String,
    deviceIcon: Icon,
    actionTitles: List<String>,
    actionAssertBlock: suspend (Notification) -> Unit
  ) {
    assertThat(groupId).isEqualTo("Direct Access")
    assertThat(type).isEqualTo(NotificationType.INFORMATION)
    assertThat(title).isEqualTo(title)
    assertThat(content).isEqualTo(content)
    assertThat(icon).isEqualTo(deviceIcon)
    assertThat(actions.size).isEqualTo(actionTitles.size)
    assertThat(isExpired).isFalse()
    actionTitles.indices.forEach { index ->
      assertThat(actions[index].templateText).isEqualTo(actionTitles[index])
    }
    actionAssertBlock(this)
    // Make sure the notification expires as both actions expire it.
    yieldUntil { isExpired }
  }
}
