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
package com.google.gct.directaccess.ui

import com.android.adblib.testing.FakeAdbSession
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.sdklib.deviceprovisioner.DeviceProvisioner
import com.android.testutils.MockitoKt.mock
import com.android.testutils.MockitoKt.whenever
import com.android.tools.idea.concurrency.coroutineScope
import com.android.tools.idea.deviceprovisioner.DeviceProvisionerService
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth.assertThat
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.TestUtils.deviceInfoListProvider
import com.google.gct.directaccess.provisioner.FirebaseDeviceProvisioner
import com.google.services.firebase.directaccess.client.FakeDirectAccessConnection
import com.intellij.util.concurrency.EdtExecutorService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doReturn

class FirebaseItemManagerTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory()
  private val session = FakeAdbSession()
  private lateinit var firebaseDeviceTableModel: FirebaseDeviceTableModel
  private lateinit var uiDispatcher: CoroutineDispatcher
  private lateinit var plugin: FirebaseDeviceProvisioner
  private lateinit var provisioner: DeviceProvisioner

  @Before
  fun setUp() = runBlockingWithTimeout {
    uiDispatcher = EdtExecutorService.getInstance().asCoroutineDispatcher()
    val fakeConnection = FakeDirectAccessConnection()
    val mockDirectAccessService = projectRule.mockProjectService(DirectAccessService::class.java)
    doReturn(fakeConnection)
      .whenever(mockDirectAccessService)
      .reserveConnection(anyString(), anyString())
    plugin = FirebaseDeviceProvisioner(projectRule.project, deviceInfoListProvider)
    provisioner = DeviceProvisioner.create(session, listOf(plugin))
    firebaseDeviceTableModel = mock()
    val mockDeviceProvisionerService =
      projectRule.mockProjectService(DeviceProvisionerService::class.java)
    whenever(mockDeviceProvisionerService.deviceProvisioner).thenReturn(provisioner)
    yieldUntil { provisioner.templates.value.isNotEmpty() }
  }

  @After
  fun tearDown() {
    uiDispatcher.cancel()
    session.close()
  }

  @Test
  fun testItemCount() = runBlockingWithTimeout {
    // Setup
    val firebaseItemManager =
      FirebaseItemManager(
        projectRule.project,
        firebaseDeviceTableModel,
        projectRule.project.coroutineScope,
        uiDispatcher
      )
    // Wait
    yieldUntil { firebaseItemManager.itemCount != 0 }

    // Assert
    assertThat(firebaseItemManager.itemCount).isEqualTo(deviceInfoListProvider().size)

    // Update templates
    (plugin.templates as MutableStateFlow).update { deviceTemplates ->
      deviceTemplates + deviceTemplates
    }
    // Wait
    yieldUntil { firebaseItemManager.itemCount != deviceInfoListProvider().size }

    // Assert
    assertThat(firebaseItemManager.itemCount).isEqualTo(2 * deviceInfoListProvider().size)
  }

  @Test
  fun testGetItem() = runBlockingWithTimeout {
    // Setup
    val firebaseItemManager =
      FirebaseItemManager(
        projectRule.project,
        firebaseDeviceTableModel,
        projectRule.project.coroutineScope,
        uiDispatcher
      )
    // Wait
    yieldUntil { firebaseItemManager.itemCount != 0 }

    // Assert
    assertThat(firebaseItemManager.getItem(0)).isInstanceOf(FirebaseDeviceTemplateItem::class.java)

    // Start the 1st device
    firebaseItemManager.getItem(0).startAction()
    // Wait till the device is claimed
    yieldUntil { plugin.devices.value.isNotEmpty() }
    // wait till the item is active
    yieldUntil { firebaseItemManager.getItem(0).isActive }

    // Assert
    yieldUntil { firebaseItemManager.getItem(0) is FirebaseDeviceItem }

    // Stop the 1st device
    firebaseItemManager.getItem(0).startAction()
    // Wait till the device is released
    yieldUntil { plugin.devices.value.isEmpty() }
    // Wait till the item becomes an active template
    yieldUntil { (firebaseItemManager.getItem(0) as? FirebaseDeviceTemplateItem)?.isActive == true }
  }
}
