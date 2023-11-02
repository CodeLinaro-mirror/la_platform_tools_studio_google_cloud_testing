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
package com.google.gct.directaccess.ui.actions

import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceProvisioner
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.testutils.MockitoKt.mock
import com.android.testutils.MockitoKt.whenever
import com.android.tools.adbbridge.Reservation
import com.android.tools.adtui.swing.findAllDescendants
import com.android.tools.adtui.swing.popup.JBPopupRule
import com.android.tools.idea.deviceprovisioner.DeviceProvisionerService
import com.android.tools.idea.testing.disposable
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import com.google.gct.directaccess.CloudProjectEntry
import com.google.gct.directaccess.DirectAccessCloudProjectManager
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.RefreshableStateFlow
import com.google.gct.directaccess.TestUtils
import com.google.gct.directaccess.provisioner.DirectAccessDeviceHandle
import com.google.gct.directaccess.ui.DirectAccessProjectSelector
import com.google.gct.directaccess.ui.ERROR_FETCHING_FIREBASE_PROJECT
import com.google.gct.directaccess.ui.NO_PROJECTS_AVAILABLE
import com.google.gct.login.GoogleLogin
import com.google.gct.login.LoginStateRule
import com.google.gct.login.LoginStatus
import com.google.services.firebase.directaccess.client.FakeDirectAccessReservationManager
import com.intellij.ide.ui.customization.CustomActionsSchema
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.replaceService
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.AnActionLink
import com.intellij.ui.components.JBLabel
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn

const val SELECT_PROJECT_ID = "SelectProjectAction"

class SelectProjectActionTest {
  private val projectRule = ProjectRule()
  private val popupRule = JBPopupRule()
  private val loginStateRule = LoginStateRule(LoginStatus.LoggedIn("test@gmail.com"))
  // Simulate the fake properties component using a map
  private val fakePropertiesComponent = mutableMapOf<Project, String>()
  @get:Rule
  val ruleChain = RuleChain.outerRule(projectRule).around(popupRule).around(loginStateRule)!!

  @RunsInEdt
  @Test
  fun testSelectProjectAction() = runBlocking {
    val scope = CoroutineScope(MoreExecutors.directExecutor().asCoroutineDispatcher())
    var isLoggedIn = false
    val mockGoogleLogin = mock<GoogleLogin>()
    doAnswer { isLoggedIn }.whenever(mockGoogleLogin).isLoggedIn
    doAnswer {
        isLoggedIn = true
        true
      }
      .whenever(mockGoogleLogin)
      .logIn()
    ApplicationManager.getApplication()
      .replaceService(GoogleLogin::class.java, mockGoogleLogin, projectRule.disposable)

    val devices = MutableStateFlow(listOf<DeviceHandle>())
    val mockProvisioner = mock<DeviceProvisioner>()
    val mockDeviceProvisionerService = mock<DeviceProvisionerService>()
    doReturn(devices).whenever(mockProvisioner).devices
    doReturn(mockProvisioner).whenever(mockDeviceProvisionerService).deviceProvisioner
    projectRule.project.replaceService(
      DeviceProvisionerService::class.java,
      mockDeviceProvisionerService,
      projectRule.disposable
    )

    val unsupportedProjectName = "unsupportedTestProject"
    val supportedProjectName = "supportedTestProject"

    val mockDirectAccessService = mock<DirectAccessService>()
    val cloudProjectManagerFlow = MutableStateFlow<DirectAccessCloudProjectManager?>(null)
    doReturn(cloudProjectManagerFlow).whenever(mockDirectAccessService).cloudProjectManager
    doAnswer {
        val cloudProjectName = it.arguments[0] as? String
        cloudProjectName?.let { name -> fakePropertiesComponent[projectRule.project] = name }
        cloudProjectManagerFlow.value =
          createCloudProjectManager(
            scope,
            cloudProjectName,
            cloudProjectName == supportedProjectName
          )
        Unit
      }
      .whenever(mockDirectAccessService)
      .selectCloudProject(any())

    projectRule.project.replaceService(
      DirectAccessService::class.java,
      mockDirectAccessService,
      projectRule.disposable
    )

    assertThat(CustomActionsSchema.getInstance().getCorrectedAction(SELECT_PROJECT_ID))
      .isInstanceOf(SelectProjectAction::class.java)

    val selectProjectAction = SelectProjectAction { _, isEnabled, _ ->
      FakeDirectAccessProjectSelector(isEnabled)
    }

    // Click the project selection button.
    val mouseEvent = MouseEvent(JPanel(), MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, 1, true, 0)
    val event =
      TestActionEvent.createTestEvent(
        selectProjectAction,
        {
          when (it) {
            CommonDataKeys.PROJECT.name -> projectRule.project
            else -> null
          }
        },
        mouseEvent
      )

    // Start select action before login.
    selectProjectAction.actionPerformed(event)
    val loginBalloon = popupRule.fakePopupFactory.getNextBalloon()
    Disposer.register(projectRule.disposable, loginBalloon)
    val action = loginBalloon.component.findAllDescendants<AnActionLink>().first()
    assertThat(action.text).isEqualTo("Log in")
    action.doClick()

    // Start select action after login.
    selectProjectAction.actionPerformed(event)
    val selectBalloon = popupRule.fakePopupFactory.getNextBalloon()
    Disposer.register(projectRule.disposable, selectBalloon)
    // Select a project that does not support direct access.
    val textField = selectBalloon.component.findAllDescendants<JTextField>().first()
    assertThat(textField.isEnabled).isTrue()
    textField.text = unsupportedProjectName
    yieldUntil { cloudProjectManagerFlow.value?.cloudProject?.name == unsupportedProjectName }

    val errorPanel = selectBalloon.component.findAllDescendants<JTextArea>().first()
    yieldUntil { errorPanel.isVisible }
    assertThat(errorPanel.text)
      .isEqualTo(
        "$unsupportedProjectName does not have access to Device Streaming. Select a different project."
      )
    val remainingMinutesLabel =
      selectBalloon.component
        .findAllDescendants<JBLabel>()
        .filter { it.text.endsWith("mins") }
        .first()
    assertThat(remainingMinutesLabel.text).isEqualTo("-- mins")
    assertThat(fakePropertiesComponent[projectRule.project]).isEqualTo(unsupportedProjectName)

    textField.text = ERROR_FETCHING_FIREBASE_PROJECT
    assertThat(fakePropertiesComponent[projectRule.project])
      .isNotEqualTo(ERROR_FETCHING_FIREBASE_PROJECT)
    assertThat(fakePropertiesComponent[projectRule.project]).isEqualTo(unsupportedProjectName)

    textField.text = NO_PROJECTS_AVAILABLE
    yieldUntil { cloudProjectManagerFlow.value == null }
    assertThat(fakePropertiesComponent[projectRule.project]).isEqualTo(unsupportedProjectName)

    // Select a project that supports direct access.
    textField.text = supportedProjectName
    yieldUntil { cloudProjectManagerFlow.value?.cloudProject?.name == supportedProjectName }
    yieldUntil { !errorPanel.isVisible }
    assertThat(fakePropertiesComponent[projectRule.project]).isEqualTo(supportedProjectName)

    yieldUntil { remainingMinutesLabel.text == "60 mins" }

    // Start a device and the selector will be disabled.
    val mockDeviceHandle = mock<DirectAccessDeviceHandle>()
    doReturn(mock<DeviceState.Connected>()).whenever(mockDeviceHandle).state
    devices.value = listOf(mockDeviceHandle)
    selectProjectAction.actionPerformed(event)
    val disabledBalloon = popupRule.fakePopupFactory.getNextBalloon()
    Disposer.register(projectRule.disposable, disabledBalloon)
    val disabledTextField = disabledBalloon.component.findAllDescendants<JTextField>().first()
    assertThat(disabledTextField.isEnabled).isFalse()

    selectProjectAction.actionPerformed(event)
    val errorListingProjectBalloon = popupRule.fakePopupFactory.getNextBalloon()
    Disposer.register(projectRule.disposable, errorListingProjectBalloon)
    val textField2 = errorListingProjectBalloon.component.findAllDescendants<JTextField>().first()
    textField2.text = ERROR_FETCHING_FIREBASE_PROJECT
    assertThat(fakePropertiesComponent[projectRule.project])
      .isNotEqualTo(ERROR_FETCHING_FIREBASE_PROJECT)
    assertThat(fakePropertiesComponent[projectRule.project]).isEqualTo(supportedProjectName)
  }

  private fun createCloudProjectManager(
    scope: CoroutineScope,
    name: String?,
    isAuthorized: Boolean
  ): DirectAccessCloudProjectManager? {
    if (name == null) {
      return null
    }
    val mockCloudProjectManager = mock<DirectAccessCloudProjectManager>()
    doReturn(CloudProjectEntry("", name)).whenever(mockCloudProjectManager).cloudProject
    val directAccessReservationManager =
      object : FakeDirectAccessReservationManager() {
        override fun listReservations(): List<Reservation> {
          if (isAuthorized) return listOf()
          throw RuntimeException("unauthorized")
        }
      }
    doReturn(directAccessReservationManager).whenever(mockCloudProjectManager).reservationManager

    val reservationListFlow =
      RefreshableStateFlow(scope, Long.MAX_VALUE) {
        if (isAuthorized) directAccessReservationManager.listReservations() else null
      }
    doReturn(reservationListFlow).whenever(mockCloudProjectManager).reservationListFlow
    doReturn(60L).whenever(mockCloudProjectManager).remainingMinutes

    val accessibleDeviceInfoListFlow =
      RefreshableStateFlow(scope, Long.MAX_VALUE) {
        if (isAuthorized) TestUtils.deviceInfoListProvider() else listOf()
      }
    doReturn(accessibleDeviceInfoListFlow)
      .whenever(mockCloudProjectManager)
      .accessibleDeviceInfoListFlow

    return mockCloudProjectManager
  }
}

class FakeDirectAccessProjectSelector(isEnabled: Boolean) : DirectAccessProjectSelector {
  override val component =
    JTextField("").apply {
      this.isEnabled = isEnabled
      document.addDocumentListener(
        object : DocumentAdapter() {
          override fun textChanged(e: DocumentEvent) {
            selectedProject.value = text
          }
        }
      )
    }
  override val selectedProject = MutableStateFlow("")
  override val isReady = MutableStateFlow(true)
}
