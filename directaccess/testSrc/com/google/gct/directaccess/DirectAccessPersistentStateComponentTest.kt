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
import com.android.testutils.MockitoKt.any
import com.android.testutils.MockitoKt.mock
import com.android.testutils.MockitoKt.whenever
import com.android.tools.idea.adblib.AdbLibApplicationService
import com.android.tools.idea.devicemanager.DeviceType
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.testing.disposable
import com.google.common.truth.Truth.assertThat
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.gct.directaccess.provisioner.DeviceSelection
import com.google.gct.login.GoogleLogin
import com.google.gct.login.LoginStateRule
import com.google.gct.login.LoginStatus
import com.google.services.firebase.directaccess.client.FakeDirectAccessGrpcService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.replaceService
import com.studiogrpc.testutils.GrpcConnectionRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.doReturn

private const val CLOUD_PROJECT_NAME = "testProject"

@RunsInEdt
class DirectAccessPersistentStateComponentTest {

  private val service = FakeDirectAccessGrpcService()
  private val projectRule = ProjectRule()
  private val loginStateRule = LoginStateRule(LoginStatus.LoggedIn("test@gmail.com"))
  private val grpcConnectionRule = GrpcConnectionRule(listOf(service))
  private lateinit var mockGoogleLogin: GoogleLogin

  @get:Rule
  val ruleChain =
    RuleChain(
      projectRule,
      EdtRule(),
      loginStateRule,
      grpcConnectionRule,
      FlagRule(StudioFlags.DIRECT_ACCESS_SETTINGS_PAGE, true),
    )

  private val session = FakeAdbSession()
  private val deviceInfo =
    DeviceInfo(
      "id1",
      "Google",
      "Pixel 5",
      "Google",
      "codename1",
      31,
      DeviceType.PHONE,
      100,
      200,
      300,
      null,
    )

  private val persistentDeviceSelectionData =
    PersistentDeviceSelectionData(
      true,
      "id1",
      "Google",
      "Pixel 5",
      "Google",
      "codename1",
      31,
      DeviceType.PHONE,
      100,
      200,
      300,
    )

  @Before
  fun setUp() = runBlockingWithTimeout {
    val mockAdbLibApplicationService = mock<AdbLibApplicationService>()
    doReturn(session).whenever(mockAdbLibApplicationService).session
    ApplicationManager.getApplication()
      .replaceService(
        AdbLibApplicationService::class.java,
        mockAdbLibApplicationService,
        projectRule.disposable,
      )

    mockGoogleLogin = mock()
    doReturn(true).whenever(mockGoogleLogin).isLoggedIn
    ApplicationManager.getApplication()
      .replaceService(GoogleLogin::class.java, mockGoogleLogin, projectRule.disposable)

    val mockDirectAccessServiceSetup = mock<DirectAccessServiceSetup>()
    doReturn(listOf(deviceInfo))
      .whenever(mockDirectAccessServiceSetup)
      .getAccessibleDeviceInfoList(any())
    doReturn(grpcConnectionRule.channel).whenever(mockDirectAccessServiceSetup).channel
    doReturn("testToken").whenever(mockDirectAccessServiceSetup).fetchAccessToken()
    ApplicationManager.getApplication()
      .replaceService(
        DirectAccessServiceSetup::class.java,
        mockDirectAccessServiceSetup,
        projectRule.disposable,
      )
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
    projectRule.project.service<DirectAccessService>().selectCloudProject(CLOUD_PROJECT_NAME)
    projectRule.project.service<DirectAccessService>().deviceSelectionListFlow.value =
      listOf(DeviceSelection(true, deviceInfo))
    val state = projectRule.project.service<DirectAccessPersistentStateComponent>().state
    yieldUntil { state.selectedCloudProject == CLOUD_PROJECT_NAME }
    yieldUntil { state.deviceSelectionList.size == 1 }
    assertThat(state.deviceSelectionList[0]).isEqualTo(persistentDeviceSelectionData)
  }
}
