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

import com.android.testutils.MockitoKt.mock
import com.android.testutils.MockitoKt.whenever
import com.android.tools.idea.devicemanager.DeviceType
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.api.services.testing.model.AndroidDeviceCatalog
import com.google.common.truth.Truth.assertThat
import com.google.gct.directaccess.TestUtils.androidDeviceCatalog
import com.google.gct.directaccess.TestUtils.androidDeviceCatalogWithMissingFields
import com.google.gct.login.GoogleLogin
import com.google.gct.testing.launcher.CloudAuthenticator
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito

class CatalogClientTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory()
  private val mockCloudAuthenticator: CloudAuthenticator = mock()

  @Before
  fun setUp() {
    val mockGoogleLoginService = projectRule.mockService(GoogleLogin::class.java)
    whenever(mockGoogleLoginService.isLoggedIn).thenReturn(true)

    CloudAuthenticator.setInstance(mockCloudAuthenticator)
  }

  private fun setupCloudAuthenticator(deviceCatalog: AndroidDeviceCatalog) =
    whenever(
        mockCloudAuthenticator.getAndroidDeviceCatalogForEnvironment(
          Mockito.anyString(),
          Mockito.anyString()
        )
      )
      .thenReturn(deviceCatalog)

  @Test
  fun testCorrectDeviceTypeFromFormFactor() {
    setupCloudAuthenticator(androidDeviceCatalog)

    val devices = CatalogClient.getAvailableDevices("testEndpoint", "testProject")

    assertThat(devices.size).isEqualTo(2)
    assertThat(devices[0].name).isEqualTo("Phone")
    assertThat(devices[0].type).isEqualTo(DeviceType.PHONE)
    assertThat(devices[0].api).isGreaterThan(25)
    assertThat(devices[1].name).isEqualTo("Watch")
    assertThat(devices[1].type).isEqualTo(DeviceType.WEAR_OS)
    assertThat(devices[1].api).isGreaterThan(25)
  }

  @Test
  fun testModelWithMissingInfoFilteredOut() {
    setupCloudAuthenticator(androidDeviceCatalogWithMissingFields)

    val devices = CatalogClient.getAvailableDevices("testEndpoint", "testProject")

    assertThat(devices.size).isEqualTo(2)
    assertThat(devices[0].name).isEqualTo("Phone")
    assertThat(devices[0].type).isEqualTo(DeviceType.PHONE)
    assertThat(devices[1].name).isEqualTo("Watch")
    assertThat(devices[1].type).isEqualTo(DeviceType.WEAR_OS)
  }
}
