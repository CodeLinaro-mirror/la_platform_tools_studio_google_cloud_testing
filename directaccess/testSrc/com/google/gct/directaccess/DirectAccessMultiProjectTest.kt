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
import com.android.sdklib.deviceprovisioner.DeviceProvisioner
import com.android.sdklib.deviceprovisioner.testing.testDeviceIcons
import com.android.testutils.MockitoKt.any
import com.android.testutils.MockitoKt.mock
import com.android.testutils.MockitoKt.whenever
import com.android.tools.adbbridge.Reservation.SessionState
import com.android.tools.idea.adblib.AdbLibApplicationService
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.testing.NamedExternalResource
import com.android.tools.idea.testing.disposable
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import com.google.gct.directaccess.TestUtils.refreshReservations
import com.google.gct.directaccess.TestUtils.showAllTemplates
import com.google.gct.directaccess.provisioner.DirectAccessDeviceHandle
import com.google.gct.directaccess.provisioner.DirectAccessDeviceProvisionerPlugin
import com.google.gct.directaccess.provisioner.DirectAccessDeviceTemplate
import com.google.gct.login.CredentialedUser
import com.google.gct.login.GoogleLogin
import com.google.gct.login.LoginState
import com.google.gct.login.LoginStatus
import com.google.gct.login2.LoginFeature
import com.google.gct.login2.LoginUsersRule
import com.google.services.firebase.FirebaseLoginFeature
import com.google.services.firebase.directaccess.client.FakeDirectAccessGrpcService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.replaceService
import com.studiogrpc.testutils.GrpcConnectionRule
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.mockito.Mockito.doReturn

@Ignore("b/324482600")
class DirectAccessMultiProjectTest {
  private class GoogleLoginServiceRule(val disposable: () -> Disposable) : NamedExternalResource() {
    val loginState = MutableStateFlow<LoginStatus>(LoginStatus.LoggedOut)

    override fun before(description: Description) {
      ApplicationManager.getApplication()
        .replaceService(LoginState::class.java, LoginState(loginState), disposable())
      val mockGoogleLogin = mock<GoogleLogin>()
      doReturn(true).whenever(mockGoogleLogin).isLoggedIn
      doReturn(CredentialedUser("test@google.com")).whenever(mockGoogleLogin).activeUser
      doReturn("test@google.com").whenever(mockGoogleLogin).getEmail()
      ApplicationManager.getApplication()
        .replaceService(GoogleLogin::class.java, mockGoogleLogin, disposable())
    }

    override fun after(description: Description) {}
  }

  private val service = FakeDirectAccessGrpcService()
  private val projectRule1 = ProjectRule()
  private val projectRule2 = ProjectRule()
  private val oldLoginRule = GoogleLoginServiceRule { projectRule1.disposable }
  private val grpcConnectionRule = GrpcConnectionRule(listOf(service))

  @get:Rule
  val chain =
    RuleChain.outerRule(FlagRule(StudioFlags.ENABLE_SETTINGS_ACCOUNT_UI, false))
      .around(projectRule1)
      .around(projectRule2)
      .around(oldLoginRule)
      .around(grpcConnectionRule)

  private val project1: Project
    get() = projectRule1.project

  private val project2: Project
    get() = projectRule2.project

  private val session = FakeAdbSession()
  private lateinit var plugin1: DirectAccessDeviceProvisionerPlugin
  private lateinit var plugin2: DirectAccessDeviceProvisionerPlugin
  private lateinit var provisioner1: DeviceProvisioner
  private lateinit var provisioner2: DeviceProvisioner
  private lateinit var scope: CoroutineScope

  @Before
  fun setUp() = runBlockingWithTimeout {
    scope = CoroutineScope(MoreExecutors.directExecutor().asCoroutineDispatcher())

    val mockAdbLibApplicationService = mock<AdbLibApplicationService>()
    doReturn(session).whenever(mockAdbLibApplicationService).session
    ApplicationManager.getApplication()
      .replaceService(
        AdbLibApplicationService::class.java,
        mockAdbLibApplicationService,
        projectRule1.disposable,
      )

    val mockDirectAccessServiceSetup = mock<DirectAccessServiceSetup>()
    doReturn(TestUtils.deviceInfoListProvider())
      .whenever(mockDirectAccessServiceSetup)
      .getAccessibleDeviceInfoList(any())
    doReturn(grpcConnectionRule.channel).whenever(mockDirectAccessServiceSetup).channel
    doReturn("testToken").whenever(mockDirectAccessServiceSetup).fetchAccessToken()
    ApplicationManager.getApplication()
      .replaceService(
        DirectAccessServiceSetup::class.java,
        mockDirectAccessServiceSetup,
        projectRule1.disposable,
      )

    plugin1 = DirectAccessDeviceProvisionerPlugin(session.scope, project1)
    plugin2 = DirectAccessDeviceProvisionerPlugin(session.scope, project2)
    provisioner1 = DeviceProvisioner.create(session, listOf(plugin1), testDeviceIcons)
    provisioner2 = DeviceProvisioner.create(session, listOf(plugin2), testDeviceIcons)
    oldLoginRule.loginState.value = LoginStatus.LoggedIn("test@google.com")

    yieldUntil { project1.service<DirectAccessService>().cloudProjectManager.value != null }
    yieldUntil { project2.service<DirectAccessService>().cloudProjectManager.value != null }
    project1.service<DirectAccessService>().selectCloudProject("test-project")
    project2.service<DirectAccessService>().selectCloudProject("test-project")
    project1.showAllTemplates()
    project2.showAllTemplates()

    yieldUntil { provisioner1.templates.value.isNotEmpty() }
    yieldUntil { provisioner2.templates.value.isNotEmpty() }
  }

  @After
  fun tearDown() = runBlockingWithTimeout {
    scope.cancel()
    session.close()
  }

  @Test
  fun shareConnectionsBetweenProjects() = runBlocking {
    val template1 = provisioner1.templates.value[0] as DirectAccessDeviceTemplate
    val template2 = provisioner2.templates.value[0] as DirectAccessDeviceTemplate
    assertThat(template1.deviceInfo).isEqualTo(template2.deviceInfo)
    // Create a reservation from project1.
    val reservationManager =
      project1.service<DirectAccessService>().cloudProjectManager.value!!.reservationManager
    reservationManager.createReservation(
      template1.deviceInfo.codename,
      template1.deviceInfo.api.toString(),
    )
    project1.refreshReservations()
    plugin1.devices.takeWhile { it.isEmpty() }.collect()
    val device1 = plugin1.devices.value[0] as DirectAccessDeviceHandle
    // Project2 creates a handle with the same device info immediately.
    withTimeout(TimeUnit.SECONDS.toMillis(2)) {
      plugin2.devices.takeWhile { it.isEmpty() }.collect()
    }
    val device2 = plugin2.devices.value[0] as DirectAccessDeviceHandle
    assertThat(device1.connection).isEqualTo(device2.connection)
  }

  @Test
  fun testReservationNotEndedWhenOneProjectClosed() = runBlockingWithTimeout {
    val template1 = provisioner1.templates.value[0] as DirectAccessDeviceTemplate
    val template2 = provisioner2.templates.value[0] as DirectAccessDeviceTemplate
    assertThat(template1.deviceInfo).isEqualTo(template2.deviceInfo)
    // Create a reservation from project1.
    val reservationManager =
      project1.service<DirectAccessService>().cloudProjectManager.value!!.reservationManager
    reservationManager.createReservation(
      template1.deviceInfo.codename,
      template1.deviceInfo.api.toString(),
    )

    project1.refreshReservations()
    assertThat(reservationManager.listReservations().size).isEqualTo(1)

    // Close the first project
    service<DirectAccessApplicationService>().registerCloudProject(project1, null)

    assertThat(reservationManager.listReservations().size).isEqualTo(1)
    assertThat(reservationManager.listReservations()[0].sessionState)
      .isEqualTo(SessionState.REQUESTED)

    // Close the second project
    service<DirectAccessApplicationService>().registerCloudProject(project2, null)

    val reservationList = reservationManager.listReservations()
    assertThat(reservationList.size).isEqualTo(1)
    assertThat(reservationList[0].sessionState).isEqualTo(SessionState.FINISHED)
  }
}

class DirectAccessMultiProjectWithLogin2Test {
  private val service = FakeDirectAccessGrpcService()
  private val projectRule1 = ProjectRule()
  private val projectRule2 = ProjectRule()
  private val grpcConnectionRule = GrpcConnectionRule(listOf(service))
  private val loginUsersRule = LoginUsersRule()

  @get:Rule
  val chain =
    RuleChain.outerRule(FlagRule(StudioFlags.ENABLE_SETTINGS_ACCOUNT_UI, true))
      .around(projectRule1)
      .around(projectRule2)
      .around(loginUsersRule)
      .around(grpcConnectionRule)

  private val project1: Project
    get() = projectRule1.project

  private val project2: Project
    get() = projectRule2.project

  private val session = FakeAdbSession()
  private lateinit var plugin1: DirectAccessDeviceProvisionerPlugin
  private lateinit var plugin2: DirectAccessDeviceProvisionerPlugin
  private lateinit var provisioner1: DeviceProvisioner
  private lateinit var provisioner2: DeviceProvisioner
  private lateinit var scope: CoroutineScope

  @Before
  fun setUp() = runBlockingWithTimeout {
    scope = CoroutineScope(MoreExecutors.directExecutor().asCoroutineDispatcher())

    loginUsersRule.setActiveUser(
      "test@google.com",
      features = setOf(LoginFeature.feature<FirebaseLoginFeature>()),
    )

    val mockAdbLibApplicationService = mock<AdbLibApplicationService>()
    doReturn(session).whenever(mockAdbLibApplicationService).session
    ApplicationManager.getApplication()
      .replaceService(
        AdbLibApplicationService::class.java,
        mockAdbLibApplicationService,
        projectRule1.disposable,
      )

    val mockDirectAccessServiceSetup = mock<DirectAccessServiceSetup>()
    doReturn(TestUtils.deviceInfoListProvider())
      .whenever(mockDirectAccessServiceSetup)
      .getAccessibleDeviceInfoList(any())
    doReturn(grpcConnectionRule.channel).whenever(mockDirectAccessServiceSetup).channel
    doReturn("testToken").whenever(mockDirectAccessServiceSetup).fetchAccessToken()
    ApplicationManager.getApplication()
      .replaceService(
        DirectAccessServiceSetup::class.java,
        mockDirectAccessServiceSetup,
        projectRule1.disposable,
      )

    val mockPersistentService = mock<DirectAccessPersistentStateComponent>()
    val fakePersistentState =
      DirectAccessPersistentStateComponent.State().apply { selectedCloudProject = "testProject" }
    doReturn(fakePersistentState).whenever(mockPersistentService).state

    project1.replaceService(
      DirectAccessPersistentStateComponent::class.java,
      mockPersistentService,
      projectRule1.disposable,
    )
    project2.replaceService(
      DirectAccessPersistentStateComponent::class.java,
      mockPersistentService,
      projectRule2.disposable,
    )

    plugin1 = DirectAccessDeviceProvisionerPlugin(session.scope, project1)
    plugin2 = DirectAccessDeviceProvisionerPlugin(session.scope, project2)
    provisioner1 = DeviceProvisioner.create(session, listOf(plugin1), testDeviceIcons)
    provisioner2 = DeviceProvisioner.create(session, listOf(plugin2), testDeviceIcons)

    project1.showAllTemplates()
    project2.showAllTemplates()

    yieldUntil { provisioner1.templates.value.isNotEmpty() }
    yieldUntil { provisioner2.templates.value.isNotEmpty() }
  }

  @After
  fun tearDown() = runBlockingWithTimeout {
    scope.cancel()
    session.close()
  }

  @Test
  fun shareConnectionsBetweenProjects() = runBlocking {
    val template1 = provisioner1.templates.value[0] as DirectAccessDeviceTemplate
    val template2 = provisioner2.templates.value[0] as DirectAccessDeviceTemplate
    assertThat(template1.deviceInfo).isEqualTo(template2.deviceInfo)
    // Create a reservation from project1.
    val reservationManager =
      project1.service<DirectAccessService>().cloudProjectManager.value!!.reservationManager
    reservationManager.createReservation(
      template1.deviceInfo.codename,
      template1.deviceInfo.api.toString(),
    )
    project1.refreshReservations()
    plugin1.devices.takeWhile { it.isEmpty() }.collect()
    val device1 = plugin1.devices.value[0] as DirectAccessDeviceHandle
    // Project2 creates a handle with the same device info immediately.
    withTimeout(TimeUnit.SECONDS.toMillis(2)) {
      plugin2.devices.takeWhile { it.isEmpty() }.collect()
    }
    val device2 = plugin2.devices.value[0] as DirectAccessDeviceHandle
    assertThat(device1.connection).isEqualTo(device2.connection)
  }

  @Test
  fun testReservationNotEndedWhenOneProjectClosed() = runBlockingWithTimeout {
    val template1 = provisioner1.templates.value[0] as DirectAccessDeviceTemplate
    val template2 = provisioner2.templates.value[0] as DirectAccessDeviceTemplate
    assertThat(template1.deviceInfo).isEqualTo(template2.deviceInfo)
    // Create a reservation from project1.
    val reservationManager =
      project1.service<DirectAccessService>().cloudProjectManager.value!!.reservationManager
    reservationManager.createReservation(
      template1.deviceInfo.codename,
      template1.deviceInfo.api.toString(),
    )

    project1.refreshReservations()
    assertThat(reservationManager.listReservations().size).isEqualTo(1)

    // Close the first project
    service<DirectAccessApplicationService>().registerCloudProject(project1, null)

    assertThat(reservationManager.listReservations().size).isEqualTo(1)
    assertThat(reservationManager.listReservations()[0].sessionState)
      .isEqualTo(SessionState.REQUESTED)

    // Close the second project
    service<DirectAccessApplicationService>().registerCloudProject(project2, null)

    val reservationList = reservationManager.listReservations()
    assertThat(reservationList.size).isEqualTo(1)
    assertThat(reservationList[0].sessionState).isEqualTo(SessionState.FINISHED)
  }
}
