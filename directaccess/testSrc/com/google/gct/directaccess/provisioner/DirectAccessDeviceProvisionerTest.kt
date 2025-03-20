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
import com.android.adblib.connectedDevicesTracker
import com.android.adblib.scope
import com.android.adblib.testing.FakeAdbSession
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.adblib.utils.createChildScope
import com.android.sdklib.deviceprovisioner.DeviceActionException
import com.android.sdklib.deviceprovisioner.DeviceError
import com.android.sdklib.deviceprovisioner.DeviceProvisioner
import com.android.sdklib.deviceprovisioner.DeviceState.Connected
import com.android.sdklib.deviceprovisioner.DeviceState.Disconnected
import com.android.sdklib.deviceprovisioner.DeviceType
import com.android.sdklib.deviceprovisioner.ReservationState
import com.android.sdklib.deviceprovisioner.Resolution
import com.android.tools.adtui.swing.enableHeadlessDialogs
import com.android.tools.idea.adddevicedialog.FormFactors
import com.android.tools.idea.deviceprovisioner.launchCatchingDeviceActionException
import com.android.tools.idea.streaming.core.DeviceId
import com.android.tools.idea.streaming.core.StreamingDevicePanel
import com.android.tools.idea.testing.DebugLoggerRule
import com.android.tools.idea.testing.disposable
import com.google.cloud.devicestreaming.v1.DeviceSession as Reservation
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import com.google.gct.directaccess.CloudProjectEntry
import com.google.gct.directaccess.DirectAccessCloudProjectManager
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.DirectAccessServiceSetup
import com.google.gct.directaccess.RefreshableStateFlow
import com.google.gct.directaccess.TestUtils.connectionState
import com.google.gct.directaccess.TestUtils.deviceInfoListProvider
import com.google.gct.directaccess.TestUtils.deviceName
import com.google.gct.directaccess.TestUtils.getNotifications
import com.google.gct.directaccess.TestUtils.refreshReservations
import com.google.gct.directaccess.TestUtils.reservation
import com.google.gct.directaccess.TestUtils.showAllTemplates
import com.google.gct.directaccess.analytics.DirectAccessUsageTracker
import com.google.gct.directaccess.directAccessCloudProjectManager
import com.google.gct.directaccess.rule.CleanUpNotificationRule
import com.google.gct.directaccess.rule.FakeToolWindowRule
import com.google.gct.directaccess.rule.PropertiesComponentRule
import com.google.gct.login2.GoogleLoginService
import com.google.gct.login2.LoginUsersRule
import com.google.services.firebase.directaccess.client.DirectAccessConnection
import com.google.services.firebase.directaccess.client.DirectAccessConnection.ConnectionState
import com.google.services.firebase.directaccess.client.DirectAccessConnectionManager
import com.google.services.firebase.directaccess.client.DirectAccessReservationManager
import com.google.services.firebase.directaccess.client.FakeDirectAccessConnection
import com.google.services.firebase.directaccess.client.FakeDirectAccessGrpcService
import com.google.services.firebase.directaccess.client.GrpcConnectionRule
import com.google.services.firebase.directaccess.client.deviceAddress
import com.google.services.firebase.directaccess.client.isActive
import com.google.services.firebase.directaccess.client.waitUntilActive
import com.google.wireless.android.sdk.stats.DeviceInfo
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.idea.LoggerFactory
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.messages.MessageDialog
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.replaceService
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.content.Content
import icons.StudioIcons
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DirectAccessDeviceProvisionerTest {

  private val service = FakeDirectAccessGrpcService()
  private val projectRule = ProjectRule()
  private val grpcConnectionRule = GrpcConnectionRule(listOf(service))
  private val loginUsersRule = LoginUsersRule()
  private val fakeToolWindowRule = FakeToolWindowRule(projectRule)
  private val cleanUpNotificationRule = CleanUpNotificationRule(projectRule)
  private val propertiesComponentRule = PropertiesComponentRule(projectRule)

  // The default test logger throws after an error is logged, whereas the default JB
  // logger doesn't. This caused b/349020104 not to be found by tests.
  private val debugLoggerRule = DebugLoggerRule(LoggerFactory::class.java)

  @get:Rule
  val ruleChain: RuleChain =
    RuleChain.outerRule(debugLoggerRule)
      .around(projectRule)
      .around(grpcConnectionRule)
      .around(loginUsersRule)
      .around(fakeToolWindowRule)
      .around(cleanUpNotificationRule)
      .around(propertiesComponentRule)

  private val session = FakeAdbSession()
  private lateinit var plugin: DirectAccessDeviceProvisionerPlugin
  private lateinit var provisioner: DeviceProvisioner
  private lateinit var directAccessReservationManager: DirectAccessReservationManager
  private lateinit var fakeConnection: FakeDirectAccessConnection
  private lateinit var scope: CoroutineScope
  private var isOAuthTokenAvailable: Boolean = false

  @Before
  fun setUp() = runBlockingWithTimeout {
    loginUsersRule.setActiveUser("test@google.com")
    enableHeadlessDialogs(projectRule.disposable)
    TestDialogManager.setTestDialog(TestDialog.YES)
    scope = CoroutineScope(MoreExecutors.directExecutor().asCoroutineDispatcher())
    val usageTracker = mock<DirectAccessUsageTracker>()
    whenever(usageTracker.scope).thenReturn(scope)
    ApplicationManager.getApplication()
      .replaceService(DirectAccessUsageTracker::class.java, usageTracker, projectRule.disposable)

    val mockDirectAccessServiceSetup = mock<DirectAccessServiceSetup>()
    whenever(mockDirectAccessServiceSetup.getAccessibleDeviceInfoList(null)).thenReturn(listOf())
    ApplicationManager.getApplication()
      .replaceService(
        DirectAccessServiceSetup::class.java,
        mockDirectAccessServiceSetup,
        projectRule.disposable,
      )

    isOAuthTokenAvailable = true
    directAccessReservationManager =
      DirectAccessReservationManager(
        "testProject",
        scope,
        isDefaultApiEnabled = true,
        grpcConnectionRule.channel,
      ) {
        if (!isOAuthTokenAvailable) throw RuntimeException()
        "testToken"
      }
    setupConnection { reservationName ->
      FakeDirectAccessConnection(
        directAccessReservationManager,
        reservationName,
        scope.createChildScope(true),
      )
    }
    plugin = DirectAccessDeviceProvisionerPlugin(session.scope, projectRule.project)
    provisioner = DeviceProvisioner.create(session.scope, session, listOf(plugin))
    projectRule.project.showAllTemplates { selectionList ->
      // Templates are not added to the provisioner automatically after switching to a cloud project
      // with access to more devices.
      selectionList.forEach { assertThat(it.isSelected).isFalse() }
    }
    yieldUntil { provisioner.templates.value.isNotEmpty() }
  }

  @After
  fun tearDown() = runBlockingWithTimeout {
    TestDialogManager.setTestDialog(null)
    scope.coroutineContext.job.cancelAndJoin()
    session.close()
  }

  private fun setupConnection(createConnection: (String) -> FakeDirectAccessConnection) {
    // Sets up mock project service.
    val mockDirectAccessService = mock<DirectAccessService>()
    val cloudProjectName = "test-project"
    val cloudProjectManagerFlow = MutableStateFlow<DirectAccessCloudProjectManager?>(null)
    val mockCloudProjectManager = mock<DirectAccessCloudProjectManager>()
    whenever(mockCloudProjectManager.cloudProject)
      .thenReturn(CloudProjectEntry("", cloudProjectName))
    val deviceSelectionListFlow = MutableStateFlow<List<DeviceSelection>>(listOf())
    whenever(mockDirectAccessService.deviceSelectionListFlow).thenReturn(deviceSelectionListFlow)
    whenever(mockDirectAccessService.cloudProjectManager).thenReturn(cloudProjectManagerFlow)
    whenever(mockDirectAccessService.scope).thenReturn(scope)
    doAnswer { runBlocking { fakeConnection.endReservation(true) } }
      .whenever(mockDirectAccessService)
      .selectCloudProject(eq(null))
    projectRule.project.replaceService(
      DirectAccessService::class.java,
      mockDirectAccessService,
      projectRule.disposable,
    )

    // Sets up deviceSelectionListFlow.
    scope.launch {
      loginUsersRule.loginService.activeUserFlow.collect {
        cloudProjectManagerFlow.value = if (it != null) mockCloudProjectManager else null
      }
    }

    // Sets up APIs im cloudProjectManager
    val reservationListFlow =
      RefreshableStateFlow(scope, Long.MAX_VALUE) {
        if (loginUsersRule.loginService.isLoggedIn() && isOAuthTokenAvailable)
          Pair(directAccessReservationManager.listReservations(), null)
        else Pair(null, Exception())
      }
    whenever(mockCloudProjectManager.reservationListFlowWithException)
      .thenReturn(reservationListFlow)

    val accessibleDeviceInfoListFlow =
      RefreshableStateFlow(scope, Long.MAX_VALUE) { deviceInfoListProvider() }
    whenever(mockCloudProjectManager.accessibleDeviceInfoListFlow)
      .thenReturn(accessibleDeviceInfoListFlow)
    whenever(mockCloudProjectManager.reservationManager).thenReturn(directAccessReservationManager)

    val mockDirectAccessConnectionManager = mock<DirectAccessConnectionManager>()
    whenever(mockCloudProjectManager.connectionManager)
      .thenReturn(mockDirectAccessConnectionManager)

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
  fun testTemplates() = runBlockingWithTimeout {
    // getAvailableDevices() is called in the init block of FirebaseDeviceProvisioner
    // Wait for setup to complete
    yieldUntil { provisioner.templates.value.isNotEmpty() }
    yieldUntil {
      provisioner.templates.value.all { it.activationAction.presentation.value.enabled }
    }

    // Assert
    provisioner.templates.value[0].id.apply {
      assertThat(isTemplate).isTrue()
      assertThat(pluginId).isEqualTo(PLUGIN_ID)
      assertThat(identifier).isEqualTo("model_id=id1/31")
    }
    assertThat(provisioner.templates.value[0].properties.title).isEqualTo("Google Pixel 5")
    assertThat(provisioner.templates.value[0].properties.resolution).isEqualTo(Resolution(100, 200))
    assertThat(provisioner.templates.value[0].properties.density).isEqualTo(300)
    assertThat(
        (provisioner.templates.value[0].properties.icon as OemLabsAssetsRegistry.OemLabIcon)
          .description
      )
      .isEqualTo("google_phone")
    assertThat(provisioner.templates.value[0].properties.isRemote).isTrue()
    assertThat(provisioner.templates.value[1].properties.title).isEqualTo("Google Pixel 6")
    assertThat(provisioner.templates.value[1].properties.resolution).isEqualTo(Resolution(200, 300))
    assertThat(provisioner.templates.value[1].properties.density).isEqualTo(400)
    assertThat(provisioner.templates.value[1].properties.isRemote).isTrue()
    assertThat(
        (provisioner.templates.value[1] as DirectAccessDeviceTemplate)
          .deviceInfo
          .deviceAvailabilityEstimateSeconds
      )
      .isEqualTo(300)
    yieldUntil {
      provisioner.templates.value[1].state.error?.severity == DeviceError.Severity.WARNING &&
        provisioner.templates.value[1].state.error?.message == "less than 15 min"
    }
    assertThat(provisioner.templates.value[2].properties.title).isEqualTo("Google Pixel 6 Pro")
    assertThat(provisioner.templates.value[2].properties.resolution).isEqualTo(Resolution(300, 400))
    assertThat(provisioner.templates.value[2].properties.density).isEqualTo(500)
    assertThat(provisioner.templates.value[2].properties.isRemote).isTrue()
    assertThat(
        (provisioner.templates.value[2] as DirectAccessDeviceTemplate)
          .deviceInfo
          .deviceAvailabilityEstimateSeconds
      )
      .isEqualTo(3000)
    yieldUntil {
      provisioner.templates.value[2].state.error?.severity == DeviceError.Severity.WARNING &&
        provisioner.templates.value[2].state.error?.message == "more than 15 min"
    }
    assertThat(provisioner.templates.value[3].properties.title).isEqualTo("Google Pixel Watch")
    assertThat(provisioner.templates.value[3].properties.resolution).isEqualTo(Resolution(50, 100))
    assertThat(provisioner.templates.value[3].properties.density).isEqualTo(150)
    assertThat(provisioner.templates.value[3].properties.isRemote).isTrue()
    assertThat(provisioner.templates.value[4].properties.title).isEqualTo("Google Pixel Watch")
    assertThat(provisioner.templates.value[4].properties.resolution).isEqualTo(Resolution(50, 100))
    assertThat(provisioner.templates.value[4].properties.density).isEqualTo(150)
    assertThat(provisioner.templates.value[4].properties.isRemote).isTrue()

    // Log out
    loginUsersRule.logOut("test@google.com")
    yieldUntil {
      provisioner.templates.value.all { !it.activationAction.presentation.value.enabled }
    }

    // Login without access.
    isOAuthTokenAvailable = false
    loginUsersRule.setActiveUser("test@google.com")
    projectRule.project.refreshReservations()
    yieldUntil {
      provisioner.templates.value.all { !it.activationAction.presentation.value.enabled }
    }
    // Login with access.
    isOAuthTokenAvailable = true
    loginUsersRule.setActiveUser("test2@google.com")
    projectRule.project.refreshReservations()
    yieldUntil {
      provisioner.templates.value.all { it.activationAction.presentation.value.enabled }
    }
  }

  @Test
  fun testRemovedTemplates() = runBlockingWithTimeout {
    yieldUntil { provisioner.templates.value.isNotEmpty() }
    yieldUntil {
      provisioner.templates.value.all { it.activationAction.presentation.value.enabled }
    }
    val accessibleDevicesFlow =
      projectRule.project
        .service<DirectAccessService>()
        .cloudProjectManager
        .value
        ?.accessibleDeviceInfoListFlow
        ?.stateFlow
    (accessibleDevicesFlow as MutableStateFlow).value = listOf()
    yieldUntil {
      provisioner.templates.value.all {
        !it.activationAction.presentation.value.enabled &&
          it.activationAction.presentation.value.detail ==
            "${it.properties.title} removed from the Firebase Test Lab catalog." &&
          it.state.error?.severity == DeviceError.Severity.WARNING
        it.state.error?.message == "No longer available"
      }
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
    val device = devices[0] as DirectAccessDeviceHandle
    val state = device.stateFlow
    assertThat(device.sourceTemplate).isEqualTo(template)
    assertThat(device.id).isNotEqualTo(template.id)
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
    // We intentionally add a suffix to ensure updated property is not displayed
    val suffix = "-connected"
    session.deviceServices.configureDeviceProperties(
      DeviceSelector.fromSerialNumber(serialNumber),
      mapOf(
        "ro.serialno" to "physicaldevice",
        DevicePropertyNames.RO_BUILD_VERSION_SDK to deviceInfo.api.toString(),
        DevicePropertyNames.RO_PRODUCT_MANUFACTURER to deviceInfo.manufacturer + suffix,
        DevicePropertyNames.RO_PRODUCT_MODEL to deviceInfo.name + suffix,
      ),
    )
    session.hostServices.devices =
      DeviceList(listOf(com.android.adblib.DeviceInfo(serialNumber, DeviceState.ONLINE)), listOf())
    yieldUntil { state.value.connectedDevice != null }
    assertThat(state.value).isInstanceOf(Connected::class.java)
    assertThat(state.value.reservation!!.stateMessage).isEmpty()
    with(state.value.properties) {
      assertThat((icon as OemLabsAssetsRegistry.OemLabIcon).description).isEqualTo("google_phone")
      assertThat(androidVersion!!.apiLevel).isEqualTo(deviceInfo.api)
      assertThat(model).isEqualTo(deviceInfo.name)
      assertThat(manufacturer).isEqualTo(deviceInfo.manufacturer)
      assertThat(resolution?.height).isEqualTo(2400)
      assertThat(resolution?.width).isEqualTo(1080)
      assertThat(isRemote).isTrue()

      with(deviceInfoProto) {
        assertThat(model).isEqualTo(deviceInfo.name)
        assertThat(manufacturer).isEqualTo(deviceInfo.manufacturer)
        assertThat(deviceType).isEqualTo(DeviceInfo.DeviceType.CLOUD_PHYSICAL)
        assertThat(deviceProvisionerId).isEqualTo(PLUGIN_ID)
        assertThat(anonymizedSerialNumber).isNotEmpty()
      }
    }
    device.id.apply {
      assertThat(pluginId).isEqualTo(PLUGIN_ID)
      assertThat(isTemplate).isFalse()
      assertThat(identifier).isEqualTo("reservation=${device.reservation.name}")
    }

    // Deactivate the device.
    state.value.connectedDevice!!.scope.cancel()
    device.deactivationAction.deactivate()
    // Cancel Reservation.
    fakeConnection.endReservation()
    yieldUntil { plugin.devices.value.isEmpty() }
    session.hostServices.devices = DeviceList(listOf(), listOf())
    yieldUntil { state.value is Disconnected }
    yieldUntil { template.activationAction.presentation.value.enabled }
  }

  @Test
  fun waitUntilTemplatesBecomeAvailable() = runBlockingWithTimeout {
    val index =
      deviceInfoListProvider().indexOfFirst { it.deviceAvailabilityEstimateSeconds == 300L }
    val template = plugin.templates.value[index] as DirectAccessDeviceTemplate
    yieldUntil {
      template.activationAction.presentation.value.icon == StudioIcons.Avd.START_RESERVATION
    }

    // Reserve a device and cancel it from the waiting dialog.
    TestDialogManager.setTestDialog(TestDialog.NO)
    val cancelledJob = scope.launch { template.activationAction.activate() }
    cancelledJob.join()
    assertThat(cancelledJob.isCancelled).isTrue()
    yieldUntil { template.activationAction.presentation.value.enabled }

    // Reserve a device and proceed.
    TestDialogManager.setTestDialog(TestDialog.YES)
    val job = scope.launch { template.activationAction.activate() }
    job.join()
    assertThat(job.isCancelled).isFalse()
    assertThat(template.activationAction.presentation.value.enabled).isFalse()
    yieldUntil { template.activeDevice != null }
  }

  @Test
  fun waitingTimeNotAvailable() = runBlockingWithTimeout {
    val index =
      deviceInfoListProvider().indexOfFirst { it.deviceAvailabilityEstimateSeconds == null }
    val template = plugin.templates.value[index] as DirectAccessDeviceTemplate
    assertThat(template.activationAction.presentation.value.icon).isEqualTo(StudioIcons.Avd.RUN)

    // Dialog not triggered to cancel the activation job.
    TestDialogManager.setTestDialog(TestDialog.NO)
    val job = scope.launch { template.activationAction.activate() }
    job.join()
    assertThat(job.isCancelled).isFalse()
    assertThat(template.activationAction.presentation.value.enabled).isFalse()
    yieldUntil { template.activeDevice != null }
  }

  @Test
  fun deleteTemplate(): Unit = runBlockingWithTimeout {
    assertThat(plugin.templates.value.size).isEqualTo(5)

    val templates = plugin.templates.first()
    val template = templates.first() as DirectAccessDeviceTemplate
    // Create a finished reservation that will be fetched but not processed.
    val reservation =
      directAccessReservationManager.createReservation(
        template.deviceInfo.codename,
        template.deviceInfo.api.toString(),
      )
    directAccessReservationManager.cancelReservation(reservation.name)
    templates.first().deleteAction?.delete()
    yieldUntil {
      projectRule.project.directAccessCloudProjectManager!!
        .reservationListFlowWithException
        .value
        .first
        ?.firstOrNull()
        ?.name == reservation.name
    }

    yieldUntil { plugin.templates.value.size == 4 }

    assertThat(plugin.templates.value)
      .containsExactlyElementsIn(templates.subList(1, templates.size))
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
          scope.createChildScope(true),
        ) {
        override suspend fun connect(
          progressReporter: suspend (String, suspend CoroutineScope.() -> Unit) -> Unit
        ) {
          throw RuntimeException("Failed connection.")
        }
      }
    }

    val template = plugin.templates.value[0]
    // Activate a new device from template.
    try {
      template.activationAction.activate()
      fail("Expected an exception to be thrown")
    } catch (e: Exception) {
      // This is an expected exception.
      assertThat(e).isInstanceOf(DeviceActionException::class.java)
    }

    // After an error, there shouldn't be a handle, and the reservation (if present) should be
    // FINISHED.
    yieldUntil { provisioner.devices.value.isEmpty() }
    assertThat(
        directAccessReservationManager.listReservations().all {
          it.state == Reservation.SessionState.FINISHED
        }
      )
      .isTrue()
  }

  @Test
  fun activationCancelled() = runBlockingWithTimeout {
    val latch = CompletableDeferred<Unit>()

    setupConnection { reservationName ->
      object :
        FakeDirectAccessConnection(
          directAccessReservationManager,
          reservationName,
          scope.createChildScope(true),
        ) {
        private val connectionScope = scope.createChildScope(isSupervisor = true)

        override suspend fun connect(
          progressReporter: suspend (String, suspend CoroutineScope.() -> Unit) -> Unit
        ) {
          withContext(connectionScope.coroutineContext) { latch.await() }
        }

        override suspend fun closeConnection(stateReason: DirectAccessConnection.StateReason) {
          super.closeConnection(stateReason)
          connectionScope.cancel()
        }
      }
    }

    val template = plugin.templates.value[0]
    val activateJob = launch {
      // Activate a new device from template.
      try {
        template.activationAction.activate()
        fail("Expected cancellation")
      } catch (e: Exception) {
        assertThat(e).isInstanceOf(CancellationException::class.java)
      }
    }

    // Wait until the handle is created, then deactivate it
    yieldUntil { provisioner.devices.value.isNotEmpty() }
    val device = provisioner.devices.value[0]
    device.deactivationAction!!.deactivate()

    activateJob.join()
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

    // Once the device is removed, the plugin should cancel the job
    job.join()
    assertThat(job.isCancelled).isTrue()
  }

  @Test
  fun createDevicesFromExistingReservations() = runBlockingWithTimeout {
    val deviceInfo = deviceInfoListProvider()[0]
    directAccessReservationManager.createReservation(deviceInfo.codename, deviceInfo.api.toString())
    projectRule.project.refreshReservations()
    yieldUntil { provisioner.devices.value.isNotEmpty() }
  }

  @Test
  fun deviceUpdatedWithLoginState() = runBlockingWithTimeout {
    val deviceInfo = deviceInfoListProvider()[0]
    directAccessReservationManager.createReservation(deviceInfo.codename, deviceInfo.api.toString())
    projectRule.project.refreshReservations()
    yieldUntil { provisioner.devices.value.isNotEmpty() }

    // Device removed after logout.
    loginUsersRule.logOut("test@google.com")
    yieldUntil {
      provisioner.templates.value.all { (it as DirectAccessDeviceTemplate).activeDevice == null }
    }
    yieldUntil { provisioner.devices.value.isEmpty() }

    // Login again to re-discover the device.
    loginUsersRule.setActiveUser("test@google.com")
    yieldUntil {
      projectRule.project.service<DirectAccessService>().cloudProjectManager.value != null
    }
    projectRule.project.refreshReservations()
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
  fun extendReservationWhenOutOfQuotaFromNotification() = runBlockingWithTimeout {
    service.config = FakeDirectAccessGrpcService.Config(shouldExtendReservation = false)
    val template = plugin.templates.value[0]

    // Activate device
    template.activationAction.activate()
    yieldUntil { provisioner.devices.value.isNotEmpty() }
    assertThat(provisioner.devices.value.size).isEqualTo(1)

    val handle = (provisioner.devices.value[0])
    yieldUntil { handle.state.reservation != null }
    val dialogCountDown = CountDownLatch(1)
    TestDialogManager.setTestDialog { message ->
      assertThat(message)
        .isEqualTo(
          "All Spark plan minutes for the current period have been used. Upgrade to a Blaze plan to immediately continue using this service."
        )
      dialogCountDown.countDown()
      MessageDialog.OK_EXIT_CODE
    }
    handle.launchCatchingDeviceActionException {
      handle.reservationAction?.reserve(Duration.ofMinutes(1))
    }
    dialogCountDown.await()
  }

  @Test
  fun testActionsInNotificationOnDisconnectDevice() = runBlockingWithTimeout {
    val template = plugin.templates.value[0]

    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    yieldUntil { provisioner.devices.value.isNotEmpty() }

    directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()

    handle.deactivationAction.deactivate()
    yieldUntil { handle.connectionState is ConnectionState.Disconnected }

    val firstNotificationsList = getNotifications(projectRule.project)
    assertThat(firstNotificationsList.size).isEqualTo(1)

    firstNotificationsList[0].assertDeviceDisconnectedNotification(handle) {
      val reconnectAction = it.actions[0] as NotificationAction
      reconnectAction.actionPerformed(mock(), it)
      yieldUntil { handle.connectionState !is ConnectionState.Disconnected }
      assertThat(handle.connectionState).isInstanceOf(ConnectionState.Connected::class.java)
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
      yieldUntil { handle.reservation.state != Reservation.SessionState.ACTIVE }
      assertThat(handle.connectionState).isInstanceOf(ConnectionState.Disconnected::class.java)
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
      ),
    )
    session.hostServices.devices =
      DeviceList(listOf(com.android.adblib.DeviceInfo(serialNumber, DeviceState.ONLINE)), listOf())

    directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()

    directAccessReservationManager.extendReservation(
      handle.reservation.name,
      Duration.ofMinutes(5).plus(Duration.ofSeconds(10)),
      DirectAccessReservationManager.ReservationExtendType.TTL,
    )

    yieldUntil { getNotifications(projectRule.project).isNotEmpty() }
    val firstNotificationsList = getNotifications(projectRule.project)
    assertThat(firstNotificationsList.size).isEqualTo(1)

    firstNotificationsList[0].assertReservationExpiringNotification(handle, true) {
      val extendAction = it.actions[0] as NotificationAction
      extendAction.actionPerformed(mock(), it)
      yieldUntil {
        handle.reservation.expireTime.seconds ==
          handle.reservation.createTime.seconds + TimeUnit.MINUTES.toSeconds(20) + 10
      }
    }

    // Expiring a notification does not guarantee it is no longer visible. Wait for the notification
    // to be cleared.
    yieldUntil { getNotifications(projectRule.project).isEmpty() }
  }

  @Test
  fun noDuplicateNotificationsOnDisconnectDevice() = runBlockingWithTimeout {
    val template = plugin.templates.value[0]

    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    yieldUntil { provisioner.devices.value.isNotEmpty() }

    directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()

    handle.deactivationAction.deactivate()
    handle.deactivationAction.deactivate()
    yieldUntil { handle.connectionState is ConnectionState.Disconnected }

    val firstNotificationsList = getNotifications(projectRule.project)
    assertThat(firstNotificationsList.size).isEqualTo(1)
  }

  @Test
  fun testSessionLostNotification() = runBlockingWithTimeout {
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
      ),
    )
    session.hostServices.devices =
      DeviceList(listOf(com.android.adblib.DeviceInfo(serialNumber, DeviceState.ONLINE)), listOf())

    val stateFlow =
      directAccessReservationManager.fetchReservationFlow(handle.reservation.name)
        as MutableStateFlow
    stateFlow.waitUntilActive()

    service.config = FakeDirectAccessGrpcService.Config(authenticatedToGetReservation = false)
    stateFlow.update {
      it.toBuilder().setState(Reservation.SessionState.SESSION_STATE_UNSPECIFIED).build()
    }

    yieldUntil { getNotifications(projectRule.project).isNotEmpty() }
    val firstNotificationsList = getNotifications(projectRule.project)
    assertThat(firstNotificationsList.size).isEqualTo(1)

    firstNotificationsList[0].assertDeviceNotification(
      "Direct Access Sticky",
      "${handle.deviceName} session lost",
      "Android Studio can not access session status right now. " +
        "Please check your network connection and login again.",
      handle.icon,
      listOf(),
      true,
    ) {
      it.expire()
    }
    yieldUntil { (template as DirectAccessDeviceTemplate).activeDevice == null }
  }

  @Test
  fun testBannerNotificationForReservationExpiringNotification() = runBlockingWithTimeout {
    val bannerNotifications = mutableListOf<EditorNotificationPanel>()
    val handle = setupReservationExpiringTest()
    val mockContent = setupMockContentForRunningDevicePanel(bannerNotifications)
    val fakeToolWindow = fakeToolWindowRule.fakeToolWindow

    fakeToolWindow.contentManager.addContent(mockContent)

    directAccessReservationManager.extendReservation(
      handle.reservation.name,
      Duration.ofMinutes(5).plus(Duration.ofSeconds(10)),
      DirectAccessReservationManager.ReservationExtendType.TTL,
    )

    yieldUntil { bannerNotifications.isNotEmpty() }
    assertThat(bannerNotifications.size).isEqualTo(1)
    assertThat(bannerNotifications[0].text).isEqualTo(RESERVATION_EXPIRING_BANNER_TITLE)

    // Switch the panel in RDW
    fakeToolWindow.contentManager.addContent(mock())

    yieldUntil { bannerNotifications.isEmpty() }
    assertThat(getNotifications(projectRule.project).isEmpty()).isTrue()

    // Switch to original panel in RDW
    fakeToolWindow.contentManager.setSelectedContent(mockContent)

    yieldUntil { bannerNotifications.isNotEmpty() }
    assertThat(bannerNotifications.size).isEqualTo(1)
    assertThat(bannerNotifications[0].text).isEqualTo(RESERVATION_EXPIRING_BANNER_TITLE)

    session.hostServices.disconnect(handle.connection.deviceAddress()!!)
    yieldUntil { handle.stateFlow.value is Disconnected }
    verify(fakeToolWindow.contentManager).removeContentManagerListener(any())
  }

  @Test
  fun testBalloonNotificationForReservationExpiringNotification() = runBlockingWithTimeout {
    val bannerNotifications = mutableListOf<EditorNotificationPanel>()
    val handle = setupReservationExpiringTest()
    val mockContent = setupMockContentForRunningDevicePanel(bannerNotifications)
    val fakeToolWindow = fakeToolWindowRule.fakeToolWindow

    // Add mock content and a separate mock to simulate 2 devices with the required device
    // not visible in RDW
    fakeToolWindow.contentManager.addContent(mockContent)
    fakeToolWindow.contentManager.addContent(mock())

    directAccessReservationManager.extendReservation(
      handle.reservation.name,
      Duration.ofMinutes(5).plus(Duration.ofSeconds(10)),
      DirectAccessReservationManager.ReservationExtendType.TTL,
    )

    assertThat(bannerNotifications.isEmpty()).isTrue()
    yieldUntil { getNotifications(projectRule.project).isNotEmpty() }
    getNotifications(projectRule.project)[0].assertReservationExpiringNotification(handle, false) {}
  }

  @Test
  fun testBannerNotificationWhenRDWClosedAndReOpened() = runBlockingWithTimeout {
    val bannerNotifications = mutableListOf<EditorNotificationPanel>()
    val handle = setupReservationExpiringTest()
    val mockContent = setupMockContentForRunningDevicePanel(bannerNotifications)
    val fakeToolWindow = fakeToolWindowRule.fakeToolWindow

    fakeToolWindow.contentManager.addContent(mockContent)

    directAccessReservationManager.extendReservation(
      handle.reservation.name,
      Duration.ofMinutes(5).plus(Duration.ofSeconds(10)),
      DirectAccessReservationManager.ReservationExtendType.TTL,
    )

    yieldUntil { bannerNotifications.isNotEmpty() }
    assertThat(bannerNotifications.size).isEqualTo(1)
    assertThat(bannerNotifications[0].text).isEqualTo(RESERVATION_EXPIRING_BANNER_TITLE)

    fakeToolWindow.hide()
    // Actual tool window cleans everything when hidden and recreates when shown
    // We only clean the notifications in test
    bannerNotifications.clear()
    fakeToolWindow.show()

    yieldUntil { bannerNotifications.isNotEmpty() }
    assertThat(bannerNotifications.size).isEqualTo(1)
    assertThat(bannerNotifications[0].text).isEqualTo(RESERVATION_EXPIRING_BANNER_TITLE)
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
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    yieldUntil { provisioner.devices.value.isNotEmpty() }
    directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()

    handle.deactivationAction.deactivate()
    yieldUntil { handle.connectionState is ConnectionState.Disconnected }

    val firstNotificationsList = getNotifications(projectRule.project)
    assertThat(firstNotificationsList.size).isEqualTo(1)
    handle.activationAction.activate()

    yieldUntil { firstNotificationsList[0].isExpired }
    // Expiring a notification does not guarantee it is no longer visible. Wait for the notification
    // to be cleared.
    yieldUntil { getNotifications(projectRule.project).isEmpty() }

    // Device will reconnect after previous action. Disconnect again to show notification for force
    // check-in
    handle.deactivationAction.deactivate()

    val secondNotificationsList = getNotifications(projectRule.project)
    assertThat(secondNotificationsList.size).isEqualTo(1)

    handle.reservationAction.endReservation()
    yieldUntil { getNotifications(projectRule.project).isEmpty() }
  }

  @Test
  fun testActionPresentationsWithReconnection() = runBlockingWithTimeout {
    val deviceInfo = deviceInfoListProvider()[0]
    val reservation =
      directAccessReservationManager.createReservation(
        deviceInfo.codename,
        deviceInfo.api.toString(),
      )
    directAccessReservationManager.fetchReservationFlow(reservation.name).waitUntilActive()
    projectRule.project.refreshReservations()
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
      ),
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
    assertThat(handle.reservation.state).isEqualTo(Reservation.SessionState.REQUESTED)

    handle.deactivationAction.deactivate()
    yieldUntil { handle.reservation.state == Reservation.SessionState.FINISHED }
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
      it.toBuilder().apply { state = Reservation.SessionState.EXPIRED }.build()
    }
    yieldUntil { flow.value.state == Reservation.SessionState.EXPIRED }

    yieldUntil { handle.stateFlow.value.reservation?.state == ReservationState.COMPLETE }
  }

  @Test
  fun testNoNotificationOnForceCheckIn() = runBlockingWithTimeout {
    setupConnection { reservationName ->
      object : FakeDirectAccessConnection(directAccessReservationManager, reservationName, scope) {
        override suspend fun endReservation(withGracePeriod: Boolean) {
          session.hostServices.disconnect(deviceAddress()!!)
          yieldUntil { session.connectedDevicesTracker.connectedDevices.value.isEmpty() }
          super.endReservation(withGracePeriod)
        }
      }
    }
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
          scope.createChildScope(true),
        ) {
        override suspend fun endReservation(withGracePeriod: Boolean) {
          closeConnection(DirectAccessConnection.StateReason.USER_INITIATED)
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
    testCorrectIcon(template, "google_phone")
  }

  @Test
  fun testCorrectIconForWatch() = runBlockingWithTimeout {
    val template = plugin.templates.value[3] as DirectAccessDeviceTemplate
    testCorrectIcon(template, "google_wear")
  }

  @Test
  fun testStickyNotificationOnReservationExpiry() = runBlockingWithTimeout {
    setupConnection { reservationName ->
      object :
        FakeDirectAccessConnection(
          directAccessReservationManager,
          reservationName,
          scope.createChildScope(true),
        ) {
        override suspend fun closeConnection(stateReason: DirectAccessConnection.StateReason) {
          session.hostServices.disconnect(deviceAddress()!!)
          super.closeConnection(stateReason)
        }
      }
    }
    val deviceInfo = deviceInfoListProvider()[0]
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    val flow = directAccessReservationManager.fetchReservationFlow(handle.reservation.name)
    flow.waitUntilActive()

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
      ),
    )
    session.hostServices.devices =
      DeviceList(listOf(com.android.adblib.DeviceInfo(serialNumber, DeviceState.ONLINE)), listOf())
    yieldUntil { handle.state is Connected }
    yieldUntil { provisioner.devices.value.isNotEmpty() }

    directAccessReservationManager.cancelReservation(flow.value.name)

    yieldUntil { !flow.value.isActive() }
    yieldUntil { template.activeDevice == null }

    yieldUntil { getNotifications(projectRule.project).isNotEmpty() }
    val notificationsList = getNotifications(projectRule.project)
    assertThat(notificationsList.size).isEqualTo(1)

    val notification = notificationsList[0]
    assertThat(notification.title).isEqualTo("${template.properties.title} session ended")
    assertThat(notification.icon).isEqualTo(handle.icon)
    assertThat(notification.groupId).isEqualTo("Direct Access Sticky")
    val notificationGroup =
      service<NotificationGroupManager>().getNotificationGroup(notification.groupId)
    assertThat(notificationGroup.displayType).isEqualTo(NotificationDisplayType.STICKY_BALLOON)

    val actions = notification.actions
    assertThat(actions.size).isEqualTo(1)
    val action = actions[0] as NotificationAction
    action.actionPerformed(mock(), notification)
    yieldUntil { notification.isExpired }
    // New device was created from the template
    yieldUntil { template.activeDevice != null }
  }

  @Test
  fun testReservationInGracePeriodWhenProjectClosed() = runBlockingWithTimeout {
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    val reservation = handle.reservation
    directAccessReservationManager.fetchReservationFlow(reservation.name).waitUntilActive()

    // Simulate project closing
    projectRule.project.service<DirectAccessService>().selectCloudProject(null)

    yieldUntil { handle.connectionState is ConnectionState.Disconnected }
    yieldUntil { handle.reservation.expireTime.seconds != reservation.expireTime.seconds }
  }

  @Test
  fun testDevicesReturnedWhenUserLogsOut() = runBlockingWithTimeout {
    service<GoogleLoginService>().logIn()
    var port = 12345
    plugin.templates.value.forEach {
      setupConnection { reservationName ->
        FakeDirectAccessConnection(directAccessReservationManager, reservationName, scope, port++)
      }
      val handle = it.activationAction.activate() as DirectAccessDeviceHandle
      session.hostServices.connect(handle.connection.deviceAddress()!!)
      yieldUntil { handle.state is Connected }
    }
    yieldUntil { plugin.devices.value.size == plugin.templates.value.size }

    // Setup dialog such that user agrees to return devices while signing out
    TestDialogManager.setTestDialog { message ->
      assertThat(message)
        .isEqualTo(
          "Return and erase the devices to end the session?\nActive sessions consume quota after Android Studio is closed."
        )
      Messages.YES
    }
    service<GoogleLoginService>().logOutAllUsersAsync()

    yieldUntil { plugin.devices.value.isEmpty() }
  }

  @Test
  fun testDevicesNotReturnedWhenUserDeclinesLogOut() = runBlockingWithTimeout {
    var port = 12345
    service<GoogleLoginService>().logIn()
    plugin.templates.value.forEach {
      setupConnection { reservationName ->
        FakeDirectAccessConnection(directAccessReservationManager, reservationName, scope, port++)
      }
      val handle = it.activationAction.activate() as DirectAccessDeviceHandle
      session.hostServices.connect(handle.connection.deviceAddress()!!)
      yieldUntil { handle.state is Connected }
    }
    yieldUntil { plugin.devices.value.size == plugin.templates.value.size }

    // Setup dialog such that user declines to return devices while signing out
    TestDialogManager.setTestDialog { message ->
      assertThat(message)
        .isEqualTo(
          "Return and erase the devices to end the session?\nActive sessions consume quota after Android Studio is closed."
        )
      Messages.NO
    }
    service<GoogleLoginService>().logOutAllUsersAsync()

    assertThat(plugin.devices.value.size).isEqualTo(plugin.templates.value.size)
    assertThat(service<GoogleLoginService>().isLoggedIn()).isTrue()
  }

  @Test
  fun testReserveNewDeviceFromNotification() = runBlockingWithTimeout {
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    // Bring the device online by claiming a matched connected device.
    val serialNumber = fakeConnection.deviceAddress()!!.address
    session.deviceServices.configureDeviceProperties(
      DeviceSelector.fromSerialNumber(serialNumber),
      mapOf(
        "ro.serialno" to "physicaldevice",
        DevicePropertyNames.RO_BUILD_VERSION_SDK to template.deviceInfo.api.toString(),
        DevicePropertyNames.RO_PRODUCT_MANUFACTURER to template.deviceInfo.manufacturer,
        DevicePropertyNames.RO_PRODUCT_MODEL to template.deviceInfo.name,
      ),
    )
    session.hostServices.devices =
      DeviceList(listOf(com.android.adblib.DeviceInfo(serialNumber, DeviceState.ONLINE)), listOf())

    directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()

    directAccessReservationManager.cancelReservation(handle.reservation.name)
    yieldUntil { getNotifications(projectRule.project).isNotEmpty() }
    val notifications = getNotifications(projectRule.project)
    assertThat(notifications.size).isEqualTo(1)
    val notification = notifications[0]
    val formattedReservationExpireTime =
      DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(handle.state.reservation?.endTime)
    notification.assertDeviceNotification(
      "Direct Access Sticky",
      "${handle.sourceTemplate.properties.title} session ended",
      "Your device session ended at $formattedReservationExpireTime. The device was returned and erased.",
      handle.icon,
      listOf("Reserve new device"),
      false,
    ) {
      yieldUntil { template.activeDevice == null }
      val newReservationAction = it.actions[0] as NotificationAction
      newReservationAction.actionPerformed(mock(), it)
      yieldUntil { template.activeDevice != null }
    }
  }

  @Test
  fun testReserveNewDeviceWhenOutOfQuotaFromNotification() = runBlockingWithTimeout {
    service.config = FakeDirectAccessGrpcService.Config(maxReservations = 1)
    val deviceInfo =
      DeviceInfo(
        "max-one-reservation",
        "brand",
        "name",
        "lab_name",
        "manufacturer",
        "max-one-reservation",
        33,
        DeviceType.HANDHELD,
        FormFactors.PHONE,
        50,
        100,
        100,
        30,
      )
    val template =
      DirectAccessDeviceTemplate(
        projectRule.project,
        MutableStateFlow(deviceInfo).asStateFlow(),
        plugin.devices as MutableStateFlow,
        scope,
        flowOf(true),
      )
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    // Bring the device online by claiming a matched connected device.
    val serialNumber = fakeConnection.deviceAddress()!!.address
    session.deviceServices.configureDeviceProperties(
      DeviceSelector.fromSerialNumber(serialNumber),
      mapOf(
        "ro.serialno" to "physicaldevice",
        DevicePropertyNames.RO_BUILD_VERSION_SDK to template.deviceInfo.api.toString(),
        DevicePropertyNames.RO_PRODUCT_MANUFACTURER to template.deviceInfo.manufacturer,
        DevicePropertyNames.RO_PRODUCT_MODEL to template.deviceInfo.name,
      ),
    )
    session.hostServices.devices =
      DeviceList(listOf(com.android.adblib.DeviceInfo(serialNumber, DeviceState.ONLINE)), listOf())

    directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()

    directAccessReservationManager.cancelReservation(handle.reservation.name)
    yieldUntil { getNotifications(projectRule.project).isNotEmpty() }
    val notifications = getNotifications(projectRule.project)
    assertThat(notifications.size).isEqualTo(1)
    val notification = notifications[0]
    val formattedReservationExpireTime =
      DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(handle.state.reservation?.endTime)
    notification.assertDeviceNotification(
      "Direct Access Sticky",
      "${handle.sourceTemplate.properties.title} session ended",
      "Your device session ended at $formattedReservationExpireTime. The device was returned and erased.",
      handle.icon,
      listOf("Reserve new device"),
      false,
    ) {
      yieldUntil { template.activeDevice == null }
      val dialogCountDown = CountDownLatch(1)
      TestDialogManager.setTestDialog { message ->
        assertThat(message)
          .isEqualTo(
            "All Spark plan minutes for the current period have been used. Upgrade to a Blaze plan to immediately continue using this service."
          )
        dialogCountDown.countDown()
        MessageDialog.OK_EXIT_CODE
      }
      val newReservationAction = it.actions[0] as NotificationAction
      newReservationAction.actionPerformed(mock(), it)
      dialogCountDown.await()
    }
  }

  @Test
  fun testUnknownUsagePromptBeforeReservingDevice() =
    testUsagePromptBeforeReservingDevice(
      null,
      "Devices are reserved for 15 minutes. Unused minutes will be returned. If your project is a Blaze plan you may incur charges.",
      "Devices are reserved for 15 minutes. Unused minutes will be returned. If your project is a Blaze plan you may incur charges.",
      UNKNOWN_DEVICE_DO_NOT_ASK,
      UNKNOWN_DEVICE_DO_NOT_ASK,
    )

  @Test
  fun testSparkUsagePromptBeforeReservingDevice() =
    testUsagePromptBeforeReservingDevice(
      false,
      "Devices are reserved for 15 minutes and count toward your Spark Plan free minutes. When you end your session unused time is refunded.",
      "You have another streaming device reserved. The new device will be reserved and count towards your Spark Plan free minutes.",
      SPARK_SINGLE_DEVICE_DO_NOT_ASK,
      SPARK_MULTI_DEVICE_DO_NOT_ASK,
    )

  @Test
  fun testBlazeUsagePromptBeforeReservingDevice() =
    testUsagePromptBeforeReservingDevice(
      true,
      "You are currently using a Firebase project on the Blaze plan. This session may incur billed usage.",
      "You have another device streaming session. You are currently using a Firebase project on the Blaze plan. This session may incur billed usage.",
      BLAZE_SINGLE_DEVICE_DO_NOT_ASK,
      BLAZE_MULTI_DEVICE_DO_NOT_ASK,
    )

  private fun testUsagePromptBeforeReservingDevice(
    billing: Boolean?,
    singleMessage: String,
    multiMessage: String,
    singleKey: String,
    multiKey: String,
  ) = runBlockingWithTimeout {
    val countDownLatch = CountDownLatch(2)
    // Unset values set by PropertiesComponentRule
    PropertiesComponent.getInstance(projectRule.project).unsetValue(singleKey)
    PropertiesComponent.getInstance(projectRule.project).unsetValue(multiKey)
    val cloudProjectManager = projectRule.project.directAccessCloudProjectManager!!
    val billingEnabledFlow = RefreshableStateFlow(scope, TimeUnit.HOURS.toMillis(1)) { billing }
    doAnswer { billingEnabledFlow }.whenever(cloudProjectManager).isBillingEnabledFlow

    TestDialogManager.setTestDialog { message ->
      assertThat(message).startsWith(singleMessage)
      countDownLatch.countDown()
      Messages.YES
    }
    // Reserve a device
    plugin.templates.value[0].activationAction.activate()
    yieldUntil { plugin.devices.value.size == 1 }

    TestDialogManager.setTestDialog { message ->
      assertThat(message).startsWith(multiMessage)
      countDownLatch.countDown()
      Messages.YES
    }
    // Reserve another device for multi-device prompt
    plugin.templates.value[4].activationAction.activate()
    yieldUntil { plugin.devices.value.size == 2 }

    // Check the "do not ask again" box
    PropertiesComponent.getInstance(projectRule.project).setValue(singleKey, true)
    PropertiesComponent.getInstance(projectRule.project).setValue(multiKey, true)

    // Setup prompt to fail test if invoked
    TestDialogManager.setTestDialog {
      fail("Should not prompt")
      Messages.YES
    }

    plugin.devices.value.forEach { it.deactivationAction?.deactivate() }
    yieldUntil { plugin.devices.value.isEmpty() }

    // Reserve devices again
    yieldUntil { !plugin.templates.value[0].stateFlow.value.isActivating }
    plugin.templates.value[0].activationAction.activate()
    yieldUntil { plugin.devices.value.size == 1 }

    yieldUntil { !plugin.templates.value[4].stateFlow.value.isActivating }
    plugin.templates.value[4].activationAction.activate()
    yieldUntil { plugin.devices.value.size == 2 }
    assertThat(countDownLatch.count).isEqualTo(0)
  }

  private suspend fun testCorrectIcon(
    template: DirectAccessDeviceTemplate,
    iconDescription: String,
  ) {
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    session.hostServices.connect(handle.connection.deviceAddress()!!)
    yieldUntil { handle.state is Connected }

    assertThat((template.icon as OemLabsAssetsRegistry.OemLabIcon).description)
      .isEqualTo(iconDescription)
    assertThat((handle.icon as OemLabsAssetsRegistry.OemLabIcon).description)
      .isEqualTo(iconDescription)
    assertThat((handle.state.properties.icon as OemLabsAssetsRegistry.OemLabIcon).description)
      .isEqualTo(iconDescription)
  }

  private suspend fun Notification.assertReservationExpiringNotification(
    handle: DirectAccessDeviceHandle,
    waitForNotificationExpiry: Boolean,
    actionAssertBlock: suspend (Notification) -> Unit,
  ) =
    assertDeviceNotification(
      "Direct Access",
      RESERVATION_EXPIRING_BANNER_TITLE,
      "${handle.deviceName} will disconnect in less than 5 mins. Extend reservation to continue access to the device.",
      handle.icon,
      listOf("Extend 15 mins"),
      waitForNotificationExpiry,
      actionAssertBlock,
    )

  private suspend fun Notification.assertDeviceDisconnectedNotification(
    handle: DirectAccessDeviceHandle,
    actionAssertBlock: suspend (Notification) -> Unit,
  ) =
    assertDeviceNotification(
      "Direct Access",
      "${handle.deviceName} on Firebase stopped",
      "You can reconnect to the same ${handle.deviceName} for up to 5 minutes before the device is wiped",
      handle.icon,
      listOf("Reconnect to Device", "Return and erase device"),
      true,
      actionAssertBlock,
    )

  private suspend fun Notification.assertDeviceNotification(
    notificationGroupId: String = "Direct Access",
    title: String,
    content: String,
    deviceIcon: Icon,
    actionTitles: List<String>,
    waitForNotificationExpiry: Boolean,
    actionAssertBlock: suspend (Notification) -> Unit,
  ) {
    assertThat(groupId).isEqualTo(notificationGroupId)
    assertThat(type).isEqualTo(NotificationType.INFORMATION)
    assertThat(this.title).isEqualTo(title)
    assertThat(this.content).isEqualTo(content)
    assertThat(icon).isEqualTo(deviceIcon)
    assertThat(actions.size).isEqualTo(actionTitles.size)
    assertThat(isExpired).isFalse()
    actionTitles.indices.forEach { index ->
      assertThat(actions[index].templateText).isEqualTo(actionTitles[index])
    }
    actionAssertBlock(this)
    // Make sure the notification expires as both actions expire it.
    if (waitForNotificationExpiry) yieldUntil { isExpired }
  }

  private suspend fun setupReservationExpiringTest(): DirectAccessDeviceHandle {
    val deviceInfo = deviceInfoListProvider()[0]
    val template = plugin.templates.value[0]
    val handle = template.activationAction.activate() as DirectAccessDeviceHandle
    yieldUntil { provisioner.devices.value.isNotEmpty() }
    directAccessReservationManager.fetchReservationFlow(handle.reservation.name).waitUntilActive()

    // Bring the device online by claiming a matched connected device.
    val serialNumber = fakeConnection.deviceAddress()!!.address
    session.deviceServices.configureDeviceProperties(
      DeviceSelector.fromSerialNumber(serialNumber),
      mapOf(
        "ro.serialno" to "physicaldevice",
        DevicePropertyNames.RO_BUILD_VERSION_SDK to deviceInfo.api.toString(),
        DevicePropertyNames.RO_PRODUCT_MANUFACTURER to deviceInfo.manufacturer,
        DevicePropertyNames.RO_PRODUCT_MODEL to deviceInfo.name,
      ),
    )
    session.hostServices.connect(handle.connection.deviceAddress()!!)
    return handle
  }

  private fun setupMockContentForRunningDevicePanel(
    bannerNotificationHolder: MutableList<EditorNotificationPanel>
  ): Content {
    val mockStreamingDevicePanel = mock<StreamingDevicePanel>()
    whenever(mockStreamingDevicePanel.id)
      .thenReturn(DeviceId.ofPhysicalDevice("localhost:${fakeConnection.port}"))
    doAnswer { bannerNotificationHolder.add(it.arguments[0] as EditorNotificationPanel) }
      .whenever(mockStreamingDevicePanel)
      .addNotification(any())
    doAnswer { bannerNotificationHolder.remove(it.arguments[0] as EditorNotificationPanel) }
      .whenever(mockStreamingDevicePanel)
      .removeNotification(any())
    val mockContent = mock<Content>()
    doAnswer { mockStreamingDevicePanel }.whenever(mockContent).component
    return mockContent
  }
}
