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
import com.android.sdklib.deviceprovisioner.Activating
import com.android.sdklib.deviceprovisioner.Connected
import com.android.sdklib.deviceprovisioner.DeviceProvisioner
import com.android.sdklib.deviceprovisioner.Disconnected
import com.android.testutils.MockitoKt.mock
import com.android.testutils.MockitoKt.whenever
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth.assertThat
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.TestUtils.Companion.deviceInfoListProvider
import com.google.services.firebase.directaccess.client.DirectAccessConnection
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class FirebaseDeviceProvisionerTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  private val session = FakeAdbSession()
  lateinit var plugin: FirebaseDeviceProvisioner
  lateinit var provisioner: DeviceProvisioner

  @Before
  fun setUp() = runBlockingWithTimeout {
    plugin = FirebaseDeviceProvisioner(projectRule.project, deviceInfoListProvider)
    provisioner = DeviceProvisioner.create(session, listOf(plugin))
    yieldUntil { provisioner.templates.value.isNotEmpty() }
  }

  @After
  fun tearDown() {
    session.close()
  }

  @Test
  fun testTemplates() = runBlockingWithTimeout {
    // getAvailableDevices() is called in the init block of FirebaseDeviceProvisioner
    // Wait for setup to complete
    yieldUntil { provisioner.templates.value.size == 3 }

    // Assert
    assertThat(provisioner.templates.value[0].displayName).isEqualTo("Google Pixel 5")
    assertThat(provisioner.templates.value[1].displayName).isEqualTo("Google Pixel 6")
    assertThat(provisioner.templates.value[2].displayName).isEqualTo("Google Pixel 6 Pro")
  }

  @Test
  fun activateAndDeactivateDevice() = runBlockingWithTimeout {
    val deviceInfo = deviceInfoListProvider()[0]
    val template = plugin.templates.value[0]

    // Activate a new device before it becomes online
    val port = 1233
    val connection = mock<DirectAccessConnection>()
    whenever(connection.port).thenReturn(port)
    val mockDirectAccessService = projectRule.mockProjectService(DirectAccessService::class.java)
    whenever(
        mockDirectAccessService.reserveConnection(deviceInfo.codename, deviceInfo.api.toString())
      )
      .thenReturn(connection)
    template.activationAction.activate()
    yieldUntil { provisioner.devices.value.isNotEmpty() }
    val devices = provisioner.devices.value
    assertThat(devices.size).isEqualTo(1)
    val device = devices[0]
    val state = device.stateFlow
    assertThat(state.value).isInstanceOf(Activating::class.java)
    val properties = state.value.properties
    assertThat(properties.androidVersion!!.apiLevel).isEqualTo(deviceInfo.api)
    assertThat(properties.model).isEqualTo(deviceInfo.name)
    assertThat(properties.manufacturer).isEqualTo(deviceInfo.manufacturer)

    // Bring the device online by claiming a matched connected device.
    val serialNumber = "localhost:$port"
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
    session.hostServices.devices = DeviceList(listOf(), listOf())
    yieldUntil { plugin.devices.value.isEmpty() }
    yieldUntil { template.activationAction.isEnabled.value }
    assertThat(state.value).isInstanceOf(Disconnected::class.java)
  }
}
