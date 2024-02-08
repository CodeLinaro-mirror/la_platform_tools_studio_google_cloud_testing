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
import com.android.flags.junit.FlagRule
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceProvisioner
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.testutils.MockitoKt.mock
import com.android.testutils.MockitoKt.whenever
import com.android.testutils.waitForCondition
import com.android.tools.adbbridge.Reservation
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.createModalDialogAndInteractWithIt
import com.android.tools.adtui.swing.findAllDescendants
import com.android.tools.adtui.swing.popup.JBPopupRule
import com.android.tools.idea.concurrency.AndroidDispatchers
import com.android.tools.idea.devicemanager.DeviceType
import com.android.tools.idea.deviceprovisioner.DeviceProvisionerService
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.io.grpc.Status
import com.android.tools.idea.io.grpc.StatusRuntimeException
import com.android.tools.idea.testing.disposable
import com.google.common.truth.Truth.assertThat
import com.google.gct.directaccess.CloudProjectEntry
import com.google.gct.directaccess.DirectAccessCloudProjectManager
import com.google.gct.directaccess.DirectAccessPermissionStatus.Companion.parseFrom
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.FULL_PERMISSIONS_SET
import com.google.gct.directaccess.RefreshableStateFlow
import com.google.gct.directaccess.SERVICES_USE
import com.google.gct.directaccess.TestUtils
import com.google.gct.directaccess.VIEWER_PERMISSIONS_SET
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.gct.directaccess.provisioner.DeviceSelection
import com.google.gct.directaccess.provisioner.DirectAccessDeviceHandle
import com.google.gct.directaccess.ui.ERROR_FETCHING_FIREBASE_PROJECT
import com.google.gct.directaccess.ui.NO_PROJECTS_AVAILABLE
import com.google.gct.directaccess.ui.ONBOARDING_WORKFLOW_KEY
import com.google.gct.directaccess.ui.SelectDeviceDialog
import com.google.gct.login2.LoginFeature
import com.google.gct.login2.LoginUsersRule
import com.google.services.firebase.FirebaseLoginFeature
import com.google.services.firebase.FirebaseProjectClientRule
import com.google.services.firebase.directaccess.client.FakeDirectAccessReservationManager
import com.intellij.ide.ui.customization.CustomActionsSchema
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.replaceService
import com.intellij.ui.components.AnActionLink
import com.intellij.ui.components.JBLabel
import icons.StudioIcons
import java.awt.event.MouseEvent
import javax.swing.JPanel
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn

const val SELECT_PROJECT_ID = "SelectProjectAction"
private val TIMEOUT = 100.seconds

class SelectProjectActionTest {
  private val apiDisabledProject = "apiDisabledProject"
  private val unsupportedTestProjectWithServiceUse = "unsupportedTestProjectWithServiceUse"
  private val unsupportedTestProjectWithoutServiceUse = "unsupportedTestProjectWithoutServiceUse"
  private val viewerTestProject = "viewerTestProject"
  private val unknownPermissionTestProject = "unknownPermissionTestProject"
  private val supportedProjectName = "supportedTestProject"

  private val projectRule = ProjectRule()
  private val popupRule = JBPopupRule()
  private val loginUsersRule = LoginUsersRule()
  private val firebaseProjectClientRule =
    FirebaseProjectClientRule().apply {
      setupFirebaseClient(
        throwErrorOnExecute = false,
        returnMalformedJson = false,
        projectList =
          listOf(
            apiDisabledProject,
            unknownPermissionTestProject,
            unsupportedTestProjectWithoutServiceUse,
            viewerTestProject,
            unknownPermissionTestProject,
            supportedProjectName,
          ),
      )
    }

  // Simulate the fake properties component using a map
  private val fakePropertiesComponent = mutableMapOf<Project, String>()
  @get:Rule
  val ruleChain =
    RuleChain.outerRule(FlagRule(StudioFlags.ENABLE_SETTINGS_ACCOUNT_UI, true))
      .around(projectRule)
      .around(HeadlessDialogRule())
      .around(popupRule)
      .around(loginUsersRule)
      .around(firebaseProjectClientRule)!!

  private val scope = CoroutineScope(Dispatchers.IO)
  private val cloudProjectManagerFlow = MutableStateFlow<DirectAccessCloudProjectManager?>(null)
  private val permissionFlow =
    RefreshableStateFlow(scope, Long.MAX_VALUE) {
      when (cloudProjectManagerFlow.value?.cloudProject?.name) {
        unsupportedTestProjectWithServiceUse -> parseFrom(setOf(SERVICES_USE))
        unsupportedTestProjectWithoutServiceUse -> parseFrom(FULL_PERMISSIONS_SET - SERVICES_USE)
        supportedProjectName -> parseFrom(FULL_PERMISSIONS_SET)
        viewerTestProject -> parseFrom(VIEWER_PERMISSIONS_SET)
        unknownPermissionTestProject ->
          parseFrom(FULL_PERMISSIONS_SET - VIEWER_PERMISSIONS_SET + SERVICES_USE)
        else -> parseFrom(emptySet())
      }
    }
  private var exceptionToThrow: StatusRuntimeException? = null

  private val preselectedDeviceInfo =
    DeviceInfo(
      id = "shiba",
      brand = "google",
      name = "Pixel 8",
      manufacturer = "Google",
      codename = "shiba",
      api = 34,
      type = DeviceType.PHONE,
      screenX = 1080,
      screenY = 2400,
      screenDensity = 420,
      deviceAvailabilityEstimateSeconds = 30,
    )

  @Before
  fun setUp() {
    PropertiesComponent.getInstance().setValue(ONBOARDING_WORKFLOW_KEY, false)
  }

  @After
  fun tearDown() {
    PropertiesComponent.getInstance().setValue(ONBOARDING_WORKFLOW_KEY, false)
  }

  @RunsInEdt
  @Test
  @Ignore(
    "b/324356628"
  ) // Test fails since error text is not set on component tooltip but on custom tooltip.
  fun testSelectProjectAction() = runBlocking {
    val devices = MutableStateFlow(listOf<DeviceHandle>())
    val mockProvisioner = mock<DeviceProvisioner>()
    val mockDeviceProvisionerService = mock<DeviceProvisionerService>()
    doReturn(devices).whenever(mockProvisioner).devices
    doReturn(mockProvisioner).whenever(mockDeviceProvisionerService).deviceProvisioner
    projectRule.project.replaceService(
      DeviceProvisionerService::class.java,
      mockDeviceProvisionerService,
      projectRule.disposable,
    )

    val mockDirectAccessService = mock<DirectAccessService>()
    doReturn(cloudProjectManagerFlow).whenever(mockDirectAccessService).cloudProjectManager
    doReturn(scope).whenever(mockDirectAccessService).scope
    val mockDeviceSelectionListFlow = MutableStateFlow(listOf<DeviceSelection>())
    doReturn(mockDeviceSelectionListFlow).whenever(mockDirectAccessService).deviceSelectionListFlow
    doAnswer {
        val cloudProjectName = it.arguments[0] as? String
        cloudProjectName?.let { name -> fakePropertiesComponent[projectRule.project] = name }
        cloudProjectManagerFlow.value =
          createCloudProjectManager(
            scope,
            cloudProjectName,
            cloudProjectName == supportedProjectName,
          )
        runBlocking { permissionFlow.refresh() }
        Unit
      }
      .whenever(mockDirectAccessService)
      .selectCloudProject(any())

    projectRule.project.replaceService(
      DirectAccessService::class.java,
      mockDirectAccessService,
      projectRule.disposable,
    )

    assertThat(CustomActionsSchema.getInstance().getCorrectedAction(SELECT_PROJECT_ID))
      .isInstanceOf(SelectProjectAction::class.java)

    val selectDeviceAction = SelectProjectAction()

    // Click the device selection button.
    val mouseEvent = MouseEvent(JPanel(), MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, 1, true, 0)
    val event =
      TestActionEvent.createTestEvent(
        selectDeviceAction,
        {
          when (it) {
            CommonDataKeys.PROJECT.name -> projectRule.project
            else -> null
          }
        },
        mouseEvent,
      )

    withContext(AndroidDispatchers.uiThread) {
      createModalDialogAndInteractWithIt({ selectDeviceAction.actionPerformed(event) }) {
        // Start select action before login.
        val dialog = it as SelectDeviceDialog
        val action = dialog.rootPane.findAllDescendants<AnActionLink>().first()
        assertThat(action.text).isEqualTo("Log in to Google")
        action.doClick()

        waitForCondition { loginUsersRule.loginService.isLoggedIn() }
        waitForCondition {
          dialog.rootPane.findAllDescendants<ComboBox<String>>().iterator().hasNext()
        }
        val comboBox = dialog.rootPane.findAllDescendants<ComboBox<String>>().first()
        waitForCondition { comboBox.model.size > 1 }

        // Select a project that does not support direct access.
        exceptionToThrow =
          Status.PERMISSION_DENIED.withDescription("Not authorized for project")
            .asRuntimeException()
        comboBox.model.selectedItem = unsupportedTestProjectWithServiceUse
        waitForCondition {
          cloudProjectManagerFlow.value?.cloudProject?.name == unsupportedTestProjectWithServiceUse
        }
        val label =
          dialog.rootPane.findAllDescendants<JBLabel>().first { label ->
            label.icon == StudioIcons.Common.ERROR
          }
        waitForCondition {
          label
            .getHtmlFilteredText()
            .contains(
              "You do not have access to Device Streaming in project $unsupportedTestProjectWithServiceUse."
            )
        }

        // Select a project with disabled Cloud Testing API
        exceptionToThrow =
          Status.PERMISSION_DENIED.withDescription(
              "Cloud Testing API has not been used in project $apiDisabledProject before or it is disabled."
            )
            .asRuntimeException()
        comboBox.model.selectedItem = apiDisabledProject
        waitForCondition { cloudProjectManagerFlow.value?.cloudProject?.name == apiDisabledProject }
        waitForCondition {
          label
            .getHtmlFilteredText()
            .contains(
              "Cloud Testing API is not enabled in your project $apiDisabledProject. Enable it by visiting Google Cloud console."
            )
        }

        // Select a project without service use permission
        exceptionToThrow =
          Status.PERMISSION_DENIED.withDescription(
              "Grant the caller the roles/serviceusage.serviceUsageConsumer role, or a custom role with the serviceusage.services.use permission"
            )
            .asRuntimeException()
        comboBox.model.selectedItem = unsupportedTestProjectWithoutServiceUse
        waitForCondition {
          cloudProjectManagerFlow.value?.cloudProject?.name ==
            unsupportedTestProjectWithoutServiceUse
        }
        waitForCondition {
          label
            .getHtmlFilteredText()
            .contains(
              "You do not have full access to Device Streaming in project $unsupportedTestProjectWithoutServiceUse. You are missing the following permissions:serviceusage.services.use"
            )
        }
        dialog.clickDefaultButton()
      }

      selectDeviceAction.update(event)
      assertThat(event.presentation.icon).isEqualTo(firebaseIconWithErrors)

      createModalDialogAndInteractWithIt({ selectDeviceAction.actionPerformed(event) }) {
        val dialog = it as SelectDeviceDialog
        waitForCondition {
          dialog.rootPane.findAllDescendants<ComboBox<String>>().iterator().hasNext()
        }
        val comboBox = dialog.rootPane.findAllDescendants<ComboBox<String>>().first()
        val label =
          dialog.rootPane.findAllDescendants<JBLabel>().first { label ->
            label.icon == StudioIcons.Common.ERROR
          }

        // Select a project with viewer permission
        exceptionToThrow = null
        comboBox.model.selectedItem = viewerTestProject
        waitForCondition { cloudProjectManagerFlow.value?.cloudProject?.name == viewerTestProject }
        waitForCondition {
          label
            .getHtmlFilteredText()
            .contains(
              "You do not have full access to Device Streaming in project $viewerTestProject. You are missing the following permissions:" +
                permissionFlow.value.missingPermissions.joinToString("")
            )
        }

        // Select a project with a mix of permission
        comboBox.model.selectedItem = unknownPermissionTestProject
        waitForCondition {
          cloudProjectManagerFlow.value?.cloudProject?.name == unknownPermissionTestProject
        }
        waitForCondition {
          label
            .getHtmlFilteredText()
            .contains(
              "You do not have full access to Device Streaming in project $unknownPermissionTestProject. You are missing the following permissions:" +
                permissionFlow.value.missingPermissions.joinToString("")
            )
        }

        val usedMinutesLabel =
          dialog.rootPane.findAllDescendants<JBLabel>().first { usedLabel ->
            usedLabel.text.endsWith("mins used")
          }
        val remainingMinutesLabel =
          dialog.rootPane.findAllDescendants<JBLabel>().first { usedLabel ->
            usedLabel.text.endsWith("mins remaining")
          }

        assertThat(usedMinutesLabel.text).isEqualTo("-- mins used")
        assertThat(remainingMinutesLabel.text).isEqualTo("-- mins remaining")
        assertThat(fakePropertiesComponent[projectRule.project])
          .isEqualTo(unknownPermissionTestProject)

        comboBox.model.selectedItem = ERROR_FETCHING_FIREBASE_PROJECT
        assertThat(fakePropertiesComponent[projectRule.project])
          .isNotEqualTo(ERROR_FETCHING_FIREBASE_PROJECT)
        assertThat(fakePropertiesComponent[projectRule.project])
          .isEqualTo(unknownPermissionTestProject)

        comboBox.model.selectedItem = NO_PROJECTS_AVAILABLE
        waitForCondition { cloudProjectManagerFlow.value == null }
        assertThat(fakePropertiesComponent[projectRule.project])
          .isEqualTo(unknownPermissionTestProject)

        // Select a project that supports direct access.
        comboBox.model.selectedItem = supportedProjectName
        waitForCondition {
          cloudProjectManagerFlow.value?.cloudProject?.name == supportedProjectName
        }
        assertThat(fakePropertiesComponent[projectRule.project]).isEqualTo(supportedProjectName)
        assertThat(label.getHtmlFilteredText()).isEqualTo("")

        waitForCondition { usedMinutesLabel.text == "60 mins used" }
        waitForCondition { remainingMinutesLabel.text == "less than 30 mins remaining" }

        mockDeviceSelectionListFlow.value =
          (TestUtils.deviceInfoListProvider() + preselectedDeviceInfo).map {
            DeviceSelection(false, it)
          }
        waitForCondition {
          PropertiesComponent.getInstance().getBoolean(ONBOARDING_WORKFLOW_KEY, false)
        }
        dialog.clickDefaultButton()
      }

      yieldUntil { mockDeviceSelectionListFlow.value.any { it.isSelected } }
      val selectedDeviceInfo = mockDeviceSelectionListFlow.value.first { it.isSelected }.deviceInfo
      assertThat(selectedDeviceInfo).isEqualTo(preselectedDeviceInfo)
    }

    // Start a device and the selector will be disabled.
    val mockDeviceHandle = mock<DirectAccessDeviceHandle>()
    doReturn(mock<DeviceState.Connected>()).whenever(mockDeviceHandle).state
    devices.value = listOf(mockDeviceHandle)

    withContext(AndroidDispatchers.uiThread) {
      createModalDialogAndInteractWithIt({ selectDeviceAction.actionPerformed(event) }) { dialog ->
        val selector = dialog.rootPane.findAllDescendants<ComboBox<String>>().first()
        assertThat(selector.isEnabled).isFalse()
        assertThat(selector.toolTipText).isEqualTo("Stop reservations to change projects")
        dialog.clickDefaultButton()
      }
    }
  }

  @RunsInEdt
  @Test
  fun testAuthorizeLink() = runBlocking {
    // Log in as a user without the firebase feature
    loginUsersRule.setActiveUser("test@google.com", features = setOf())
    val selectDeviceAction = SelectProjectAction()

    // Click the device selection button.
    val mouseEvent = MouseEvent(JPanel(), MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, 1, true, 0)
    val event =
      TestActionEvent.createTestEvent(
        selectDeviceAction,
        {
          when (it) {
            CommonDataKeys.PROJECT.name -> projectRule.project
            else -> null
          }
        },
        mouseEvent,
      )

    withContext(AndroidDispatchers.uiThread) {
      createModalDialogAndInteractWithIt({ selectDeviceAction.actionPerformed(event) }) {
        val dialog = it as SelectDeviceDialog
        val action = dialog.rootPane.findAllDescendants<AnActionLink>().first()
        assertThat(action.text).isEqualTo("Authorize Firebase")
        action.doClick()

        waitForCondition { LoginFeature.feature<FirebaseLoginFeature>().isLoggedIn() }
      }
    }
  }

  private fun createCloudProjectManager(
    scope: CoroutineScope,
    name: String?,
    isAuthorized: Boolean,
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
        if (isAuthorized) Pair(directAccessReservationManager.listReservations(), null)
        else Pair(null, exceptionToThrow)
      }
    doReturn(reservationListFlow).whenever(mockCloudProjectManager).reservationListFlowWithException
    doReturn(Pair(60L, 70L)).whenever(mockCloudProjectManager).usageQuota

    val accessibleDeviceInfoListFlow =
      RefreshableStateFlow(scope, Long.MAX_VALUE) {
        if (isAuthorized) TestUtils.deviceInfoListProvider() + preselectedDeviceInfo else listOf()
      }
    doReturn(accessibleDeviceInfoListFlow)
      .whenever(mockCloudProjectManager)
      .accessibleDeviceInfoListFlow

    doReturn(permissionFlow).whenever(mockCloudProjectManager).permissionFlow
    return mockCloudProjectManager
  }
}

private fun waitForCondition(condition: () -> Boolean) = waitForCondition(TIMEOUT, condition)

private fun JBLabel.getHtmlFilteredText() =
  toolTipText.replace(Regex("<[^>]*>"), "").replace("\n", "").replace(Regex(" +"), " ").trim()
