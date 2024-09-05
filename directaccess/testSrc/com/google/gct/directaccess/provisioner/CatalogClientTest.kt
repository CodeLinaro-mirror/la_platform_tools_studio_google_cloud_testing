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

import com.android.sdklib.deviceprovisioner.DeviceType
import com.android.testutils.MockitoKt.mock
import com.android.testutils.MockitoKt.whenever
import com.android.tools.idea.concurrency.AndroidCoroutineScope
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.api.client.auth.oauth2.Credential
import com.google.api.services.testing.model.AndroidDeviceCatalog
import com.google.common.truth.Truth.assertThat
import com.google.gct.directaccess.CloudClientService
import com.google.gct.directaccess.TestUtils.androidDeviceCatalog
import com.google.gct.directaccess.TestUtils.androidDeviceCatalogWithMissingFields
import com.google.gct.login2.LoginUsersRule
import com.google.services.firebase.directaccess.client.CloudClient
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.spy

class CatalogClientTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory()
  @get:Rule val loginRule = LoginUsersRule()

  @Before
  fun setUp() {
    loginRule.setActiveUser("test@google.com")
  }

  private fun setupCloudClient(deviceCatalog: AndroidDeviceCatalog) {
    val client: CloudClient =
      spy(
        CloudClient(
          MutableStateFlow<Credential>(mock()),
          AndroidCoroutineScope(projectRule.testRootDisposable),
        )
      )
    CloudClientService.instance().overrideClientForTest = client
    doCallRealMethod()
      .whenever(client)
      .getAvailableDevices(Mockito.anyString(), Mockito.anyString())
    doReturn(deviceCatalog)
      .whenever(client)
      .getAndroidDeviceCatalogForEnvironment(Mockito.anyString(), Mockito.anyString())
  }

  @After
  fun tearDown() {
    CloudClientService.instance().overrideClientForTest = null
  }

  @Test
  fun testCorrectDeviceTypeFromFormFactor() {
    setupCloudClient(androidDeviceCatalog)

    val devices = CatalogClient.getAvailableDevices("testEndpoint", "testProject")

    assertThat(devices.size).isEqualTo(2)
    assertThat(devices[0].name).isEqualTo("Phone")
    assertThat(devices[0].type).isEqualTo(DeviceType.HANDHELD)
    assertThat(devices[0].api).isGreaterThan(25)
    assertThat(devices[1].name).isEqualTo("Watch")
    assertThat(devices[1].type).isEqualTo(DeviceType.WEAR)
    assertThat(devices[1].api).isGreaterThan(25)
  }

  @Test
  fun testModelWithMissingInfoFilteredOut() {
    setupCloudClient(androidDeviceCatalogWithMissingFields)

    val devices = CatalogClient.getAvailableDevices("testEndpoint", "testProject")

    assertThat(devices.size).isEqualTo(2)
    assertThat(devices[0].name).isEqualTo("Phone")
    assertThat(devices[0].type).isEqualTo(DeviceType.HANDHELD)
    assertThat(devices[1].name).isEqualTo("Watch")
    assertThat(devices[1].type).isEqualTo(DeviceType.WEAR)
  }
}
