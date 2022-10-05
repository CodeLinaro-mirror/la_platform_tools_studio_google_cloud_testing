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

import com.android.adblib.testing.FakeAdbSession
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.sdklib.deviceprovisioner.DeviceProvisionerPlugin
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.ProjectRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class FirebaseDeviceProvisionerTest {

  @get:Rule val projectRule = ProjectRule()

  private var fakeAdbSession = FakeAdbSession()
  private lateinit var firebaseDeviceProvisioner: DeviceProvisionerPlugin
  private var deviceInfoListProvider = {
    listOf(
      DeviceInfo("Google", "Pixel 5", "Google", "codename1", 31),
      DeviceInfo("Google", "Pixel 6", "Google", "codename2", 32),
      DeviceInfo("Google", "Pixel 6 Pro", "Google", "codename3", 33)
    )
  }

  @Before
  fun setup() {
    fakeAdbSession = FakeAdbSession()
    firebaseDeviceProvisioner =
      FirebaseDeviceProvisioner(projectRule.project, deviceInfoListProvider)
  }

  @After
  fun tearDown() {
    fakeAdbSession.close()
  }

  @Test
  fun testSuccessfulGetAvailableTemplates() = runBlockingWithTimeout {
    // getAvailableDevices() is called in the init block of FirebaseDeviceProvisioner
    // Wait for setup to complete
    yieldUntil { firebaseDeviceProvisioner.templates.value.size == 3 }

    // Assert
    assertThat(firebaseDeviceProvisioner.templates.value[0].displayName).isEqualTo("Google Pixel 5")
    assertThat(firebaseDeviceProvisioner.templates.value[1].displayName).isEqualTo("Google Pixel 6")
    assertThat(firebaseDeviceProvisioner.templates.value[2].displayName)
      .isEqualTo("Google Pixel 6 Pro")
  }
}
