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
package com.google.gct.directaccess

import com.android.adblib.testing.FakeAdbSession
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.flags.junit.FlagRule
import com.android.sdklib.deviceprovisioner.DeviceType as ProvisionerDeviceType
import com.android.tools.idea.adblib.AdbLibApplicationService
import com.android.tools.idea.adddevicedialog.FormFactors
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.testing.disposable
import com.google.common.truth.Truth.assertThat
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.gct.directaccess.provisioner.DeviceSelection
import com.google.gct.login2.LoginUsersRule
import com.google.services.firebase.directaccess.client.FakeDirectAccessGrpcService
import com.google.services.firebase.directaccess.client.GrpcConnectionRule
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.replaceService
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

private const val CLOUD_PROJECT_NAME = "testProject"

@RunsInEdt
class DirectAccessPersistentStateComponentTest {

  private val service = FakeDirectAccessGrpcService()
  private val projectRule = ProjectRule()
  private val grpcConnectionRule = GrpcConnectionRule(listOf(service))
  private val loginUsersRule = LoginUsersRule()

  @get:Rule
  val ruleChain =
    RuleChain(
      projectRule,
      loginUsersRule,
      EdtRule(),
      grpcConnectionRule,
      FlagRule(StudioFlags.DIRECT_ACCESS_SETTINGS_PAGE, true),
    )

  private val session = FakeAdbSession()
  private val deviceInfo =
    DeviceInfo(
      "id1",
      "Google",
      "Pixel 5",
      "google",
      "Google",
      "codename1",
      31,
      ProvisionerDeviceType.HANDHELD,
      FormFactors.PHONE,
      100,
      200,
      300,
      null,
      isDefault = true,
    )

  private val persistentDeviceSelectionData =
    PersistentDeviceSelectionData(
      isSelected = true,
      id = "id1",
      brand = "Google",
      name = "Pixel 5",
      labId = "google",
      manufacturer = "Google",
      codename = "codename1",
      api = 31,
      DeviceType.PHONE,
      FormFactors.PHONE,
      screenX = 100,
      screenY = 200,
      screenDensity = 300,
      isDefault = true,
    )

  @Before
  fun setUp() = runBlockingWithTimeout {
    val mockAdbLibApplicationService = mock<AdbLibApplicationService>()
    whenever(mockAdbLibApplicationService.session).thenReturn(session)
    ApplicationManager.getApplication()
      .replaceService(
        AdbLibApplicationService::class.java,
        mockAdbLibApplicationService,
        projectRule.disposable,
      )

    val mockDirectAccessServiceSetup = mock<DirectAccessServiceSetup>()
    whenever(mockDirectAccessServiceSetup.getAccessibleDeviceInfoList(any()))
      .thenReturn(listOf(deviceInfo))
    whenever(mockDirectAccessServiceSetup.channel(any())).thenReturn(grpcConnectionRule.channel)
    whenever(mockDirectAccessServiceSetup.fetchAccessToken()).thenReturn("testToken")
    ApplicationManager.getApplication()
      .replaceService(
        DirectAccessServiceSetup::class.java,
        mockDirectAccessServiceSetup,
        projectRule.disposable,
      )
    loginUsersRule.setActiveUser("test@google.com")
  }

  @After
  fun tearDown() {
    projectRule.project.service<DirectAccessPersistentStateComponent>().state.apply {
      selectedCloudProject = null
      deviceSelectionList = mutableListOf()
    }
  }

  @Test
  fun testPersistentStateComponentApplied() = runBlockingWithTimeout {
    val state = projectRule.project.service<DirectAccessPersistentStateComponent>().state
    state.selectedCloudProject = CLOUD_PROJECT_NAME
    state.deviceSelectionList = mutableListOf(persistentDeviceSelectionData)
    val deviceSelectionList =
      projectRule.project.service<DirectAccessService>().deviceSelectionListFlow.value
    assertThat(deviceSelectionList.size).isEqualTo(1)
    assertThat(deviceSelectionList[0].isSelected)
      .isEqualTo(persistentDeviceSelectionData.isSelected)
    assertThat(deviceSelectionList[0].deviceInfo).isEqualTo(deviceInfo)
    yieldUntil {
      projectRule.project
        .service<DirectAccessService>()
        .cloudProjectManager
        .value
        ?.cloudProject
        ?.name == CLOUD_PROJECT_NAME
    }
    yieldUntil {
      projectRule.project
        .service<DirectAccessService>()
        .deviceSelectionListFlow
        .value[0]
        .deviceInfo == deviceInfo
    }
  }

  @Test
  fun testPersistentStateComponentUpdated() = runBlockingWithTimeout {
    val state = projectRule.project.service<DirectAccessPersistentStateComponent>().state
    yieldUntil { state.selectedCloudProject == "" }
    projectRule.project.service<DirectAccessService>().selectCloudProject(CLOUD_PROJECT_NAME)
    projectRule.project.service<DirectAccessService>().deviceSelectionListFlow.value =
      listOf(DeviceSelection(true, deviceInfo))
    yieldUntil { state.selectedCloudProject == CLOUD_PROJECT_NAME }
    yieldUntil { state.deviceSelectionList.size == 1 }
    assertThat(state.deviceSelectionList[0]).isEqualTo(persistentDeviceSelectionData)
  }
}
