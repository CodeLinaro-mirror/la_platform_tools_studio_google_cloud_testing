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
import com.android.testutils.MockitoKt.any
import com.android.testutils.MockitoKt.whenever
import com.android.testutils.VirtualTimeScheduler
import com.android.tools.analytics.TestUsageTracker
import com.android.tools.analytics.UsageTracker
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.TestUtils.deviceInfoListProvider
import com.google.gct.login.GoogleLogin
import com.google.gct.login.LoginState
import com.google.services.firebase.directaccess.client.DirectAccessReservationManager
import com.google.services.firebase.directaccess.client.FakeDirectAccessConnection
import com.google.services.firebase.directaccess.client.FakeDirectAccessGrpcService
import com.google.services.firebase.directaccess.client.deviceAddress
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent
import com.studiogrpc.testutils.GrpcConnectionRule
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
import org.mockito.Mockito.doAnswer
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

  @Before
  fun setUp() = runBlockingWithTimeout {
    mockGoogleLogin = projectRule.mockService(GoogleLogin::class.java)
    doReturn(true).whenever(mockGoogleLogin).isLoggedIn
    (LoginState.loggedIn as MutableStateFlow<Boolean>).value = true
    scope = CoroutineScope(MoreExecutors.directExecutor().asCoroutineDispatcher())
    directAccessReservationManager =
      DirectAccessReservationManager("testProject", scope, grpcConnectionRule.channel) {
        "testToken"
      }
    val mockDirectAccessService = projectRule.mockProjectService(DirectAccessService::class.java)
    doReturn(directAccessReservationManager).whenever(mockDirectAccessService).reservationManager
    doAnswer {
        val reservationName = it.arguments[0] as String
        val deviceScope = it.arguments[1] as CoroutineScope
        fakeConnection =
          FakeDirectAccessConnection(directAccessReservationManager, reservationName, deviceScope)
        fakeConnection
      }
      .whenever(mockDirectAccessService)
      .connectToReservation(any(), any())
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
  fun tearDown() {
    scope.cancel()
    session.close()
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
    val properties = state.value.properties
    assertThat(properties.androidVersion!!.apiLevel).isEqualTo(deviceInfo.api)
    assertThat(properties.model).isEqualTo(deviceInfo.name)
    assertThat(properties.manufacturer).isEqualTo(deviceInfo.manufacturer)

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
    yieldUntil { template.activationAction.isEnabled.value }
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
    scope.launch { updateReservations(projectRule.project, plugin.templates) }
    yieldUntil { provisioner.devices.value.isNotEmpty() }
  }

  @Test
  fun extendReservationFromDeviceHandle() = runBlockingWithTimeout {
    val template = plugin.templates.value[0]

    // Activate device
    template.activationAction.activate()
    yieldUntil { provisioner.devices.value.isNotEmpty() }
    assertThat(provisioner.devices.value.size).isEqualTo(1)

    val handle = provisioner.devices.value[0]
    yieldUntil { handle.state.reservation != null }
    val newEndTime = handle.reservationAction?.reserve(Duration.ofSeconds(100))
    assertThat(newEndTime?.epochSecond).isEqualTo(1100)
    assertThat(handle.state.reservation?.endTime?.epochSecond).isEqualTo(1100)
  }

  @Test
  fun logReservationSuccessMetricsWhenSuccessReservingDevice() = runBlockingWithTimeout {
    val template = plugin.templates.value[0] as DirectAccessDeviceTemplate

    // Activate device
    template.activationAction.activate()
    yieldUntil { tracker.usages.isNotEmpty() }

    val studioEvent = tracker.usages[0].studioEvent
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type)
      .isEqualTo(DirectAccessUsageEvent.DirectAccessUsageEventType.RESERVE_DEVICE)
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
    yieldUntil { tracker.usages.isNotEmpty() }

    val studioEvent = tracker.usages[0].studioEvent
    assertThat(studioEvent.kind).isEqualTo(AndroidStudioEvent.EventKind.DIRECT_ACCESS_USAGE_EVENT)

    val directAccessEvent = studioEvent.directAccessUsageEvent
    assertThat(directAccessEvent.type)
      .isEqualTo(DirectAccessUsageEvent.DirectAccessUsageEventType.RESERVE_DEVICE)
    assertThat(directAccessEvent.hasDeviceSessionId()).isFalse()
    assertThat(directAccessEvent.failureReason)
      .isEqualTo(DirectAccessUsageEvent.FailureReason.UNKNOWN_FAILURE)

    val reserveDeviceDetails = directAccessEvent.reserveDeviceDetails
    assertThat(reserveDeviceDetails.success).isFalse()
    assertThat(reserveDeviceDetails.hasReserveTimeMs()).isFalse()
  }
}
