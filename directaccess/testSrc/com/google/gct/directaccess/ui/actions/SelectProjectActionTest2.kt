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

import com.android.adblib.testingutils.CoroutineTestUtils
import com.android.flags.junit.FlagRule
import com.android.sdklib.deviceprovisioner.DeviceError
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceProvisioner
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.sdklib.deviceprovisioner.DeviceType
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.createModalDialogAndInteractWithIt
import com.android.tools.adtui.swing.findAllDescendants
import com.android.tools.adtui.swing.popup.JBPopupRule
import com.android.tools.idea.concurrency.AndroidDispatchers
import com.android.tools.idea.concurrency.createChildScope
import com.android.tools.idea.deviceprovisioner.DeviceProvisionerService
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.testing.disposable
import com.google.common.truth.Truth.assertThat
import com.google.devtools.testing.v1.DeviceSession
import com.google.gct.directaccess.CloudProjectEntry
import com.google.gct.directaccess.DEFAULT_DEVICE_LIST_KEY
import com.google.gct.directaccess.DirectAccessCloudProjectManager
import com.google.gct.directaccess.DirectAccessOnboardingService
import com.google.gct.directaccess.DirectAccessPermissionStatus
import com.google.gct.directaccess.DirectAccessPersistentStateComponent
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.DirectAccessServiceSetup
import com.google.gct.directaccess.FULL_PERMISSIONS_SET
import com.google.gct.directaccess.RefreshableStateFlow
import com.google.gct.directaccess.SERVICES_USE
import com.google.gct.directaccess.TestUtils
import com.google.gct.directaccess.VIEWER_PERMISSIONS_SET
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.gct.directaccess.provisioner.DeviceSelection
import com.google.gct.directaccess.provisioner.DirectAccessDeviceHandle
import com.google.gct.directaccess.provisioner.DirectAccessDeviceProvisionerPlugin
import com.google.gct.directaccess.provisioner.DirectAccessDeviceSource
import com.google.gct.directaccess.ui.DirectAccessProjectSelectorImpl2
import com.google.gct.directaccess.ui.ERROR_FETCHING_FIREBASE_PROJECT
import com.google.gct.directaccess.ui.NO_PROJECTS_AVAILABLE
import com.google.gct.directaccess.ui.SelectProjectDialog
import com.google.gct.directaccess.ui.UsageProgressBar
import com.google.gct.login2.LoginFeature
import com.google.gct.login2.LoginUsersRule
import com.google.services.firebase.FirebaseLoginFeature
import com.google.services.firebase.FirebaseProjectClientRule
import com.google.services.firebase.directaccess.client.FakeDirectAccessReservationManager
import com.intellij.icons.AllIcons
import com.intellij.ide.HelpTooltip
import com.intellij.ide.ui.customization.CustomActionsSchema
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.replaceService
import com.intellij.ui.components.JBLabel
import icons.FirebaseIcons
import icons.StudioIcons
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

private const val SELECT_PROJECT_ID = "SelectProjectAction"
private val TIMEOUT = 100.seconds

private fun waitForCondition(condition: () -> Boolean) = waitForCondition(TIMEOUT, condition)

private fun JBLabel.getHelpToolTipText(): String {
  if (!isVisible) return ""
  val tooltip = HelpTooltip.getTooltipFor(this) ?: return ""
  val tooltipPanel = tooltip.createTipPanel()
  val text = buildString { tooltipPanel.findAllDescendants<JLabel>().forEach { append(it.text) } }
  return text.replace(Regex("<[^>]*>"), "").replace("\n", "").replace(Regex(" +"), " ").trim()
}

class SelectProjectActionTest2 {
  private val apiDisabledProject = "apiDisabledProject"
  private val unsupportedTestProjectWithServiceUse = "unsupportedTestProjectWithServiceUse"
  private val unsupportedTestProjectWithoutServiceUse = "unsupportedTestProjectWithoutServiceUse"
  private val viewerTestProject = "viewerTestProject"
  private val unknownPermissionTestProject = "unknownPermissionTestProject"
  private val supportedProjectName = "supportedTestProject"
  private val noQuotaProjectName = "noQuotaTestProject"
  private val blazeProjectName = "blazeTestProject"
  private val createdProject = "createdProject"

  private val projectRule = ProjectRule()
  private val popupRule = JBPopupRule()
  private val loginUsersRule = LoginUsersRule()
  private val firebaseProjectClientRule = FirebaseProjectClientRule()

  // Simulate the fake properties component using a map
  private val fakePropertiesComponent = mutableMapOf<Project, String>()
  @get:Rule
  val ruleChain =
    RuleChain.outerRule(FlagRule(StudioFlags.DIRECT_ACCESS_DEVICE_CATALOG_ENABLED, true))
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
        unsupportedTestProjectWithServiceUse ->
          DirectAccessPermissionStatus.parseFrom(setOf(SERVICES_USE))
        unsupportedTestProjectWithoutServiceUse ->
          DirectAccessPermissionStatus.parseFrom(FULL_PERMISSIONS_SET - SERVICES_USE)
        supportedProjectName,
        noQuotaProjectName,
        blazeProjectName,
        createdProject -> DirectAccessPermissionStatus.parseFrom(FULL_PERMISSIONS_SET)
        viewerTestProject -> DirectAccessPermissionStatus.parseFrom(VIEWER_PERMISSIONS_SET)
        unknownPermissionTestProject ->
          DirectAccessPermissionStatus.parseFrom(
            FULL_PERMISSIONS_SET - VIEWER_PERMISSIONS_SET + SERVICES_USE
          )
        else -> DirectAccessPermissionStatus.parseFrom(emptySet())
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
      type = DeviceType.HANDHELD,
      screenX = 1080,
      screenY = 2400,
      screenDensity = 420,
      deviceAvailabilityEstimateSeconds = 30,
    )

  @Before
  fun setUp() {
    PropertiesComponent.getInstance().setValue(DEFAULT_DEVICE_LIST_KEY, false)
    val mockDirectAccessServiceSetup = mock<DirectAccessServiceSetup>()
    Mockito.doReturn(TestUtils.deviceInfoListProvider())
      .whenever(mockDirectAccessServiceSetup)
      .getAccessibleDeviceInfoList(null)
    ApplicationManager.getApplication()
      .replaceService(
        DirectAccessServiceSetup::class.java,
        mockDirectAccessServiceSetup,
        projectRule.disposable,
      )
  }

  @After
  fun tearDown() {
    scope.cancel()
    PropertiesComponent.getInstance().setValue(DEFAULT_DEVICE_LIST_KEY, false)
  }

  @RunsInEdt
  @Test
  fun testSelectProjectAction() =
    CoroutineTestUtils.runBlockingWithTimeout {
      val devices = MutableStateFlow(listOf<DeviceHandle>())
      val mockProvisioner = mock<DeviceProvisioner>()
      val mockDeviceProvisionerService = mock<DeviceProvisionerService>()
      Mockito.doReturn(devices).whenever(mockProvisioner).devices
      Mockito.doReturn(mockProvisioner).whenever(mockDeviceProvisionerService).deviceProvisioner
      projectRule.project.replaceService(
        DeviceProvisionerService::class.java,
        mockDeviceProvisionerService,
        projectRule.disposable,
      )

      val mockDirectAccessService = mock<DirectAccessService>()
      Mockito.doReturn(cloudProjectManagerFlow)
        .whenever(mockDirectAccessService)
        .cloudProjectManager
      Mockito.doReturn(scope).whenever(mockDirectAccessService).scope
      val mockDeviceSelectionListFlow = MutableStateFlow(listOf<DeviceSelection>())
      Mockito.doReturn(mockDeviceSelectionListFlow)
        .whenever(mockDirectAccessService)
        .deviceSelectionListFlow
      Mockito.doAnswer {
          val cloudProjectName = it.arguments[0] as? String
          cloudProjectName?.let { name -> fakePropertiesComponent[projectRule.project] = name }
          cloudProjectManagerFlow.value =
            createCloudProjectManager(
              scope,
              cloudProjectName,
              cloudProjectName == supportedProjectName || cloudProjectName == noQuotaProjectName,
              cloudProjectName == noQuotaProjectName,
            )
          runBlocking { permissionFlow.refresh() }
          Unit
        }
        .whenever(mockDirectAccessService)
        .selectCloudProject(anyOrNull())

      projectRule.project.replaceService(
        DirectAccessService::class.java,
        mockDirectAccessService,
        projectRule.disposable,
      )

      assertThat(CustomActionsSchema.getInstance().getCorrectedAction(SELECT_PROJECT_ID))
        .isInstanceOf(SelectProjectAction::class.java)

      // Check if DirectAccessProjectSelector2 chooses the preferred project.
      firebaseProjectClientRule.setupFirebaseClient(
        throwErrorOnExecute = false,
        returnMalformedJson = false,
        projectList = listOf(apiDisabledProject, supportedProjectName),
      )
      val testSelector =
        DirectAccessProjectSelectorImpl2(projectRule.project, supportedProjectName, true, scope)
      testSelector.isReady.takeWhile { !it }.collect()
      assertThat(testSelector.selectedProject.value).isEqualTo(supportedProjectName)

      firebaseProjectClientRule.setupFirebaseClient(
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
            noQuotaProjectName,
          ),
      )
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
          val dialog = it as SelectProjectDialog
          val button = dialog.rootPane.findAllDescendants<JButton>().first()
          button.doClick()

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
            cloudProjectManagerFlow.value?.cloudProject?.name ==
              unsupportedTestProjectWithServiceUse
          }
          val label =
            dialog.rootPane.findAllDescendants<JBLabel>().first { label ->
              label.icon == StudioIcons.Common.ERROR
            }
          waitForCondition {
            label
              .getHelpToolTipText()
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
          waitForCondition {
            cloudProjectManagerFlow.value?.cloudProject?.name == apiDisabledProject
          }
          waitForCondition {
            label
              .getHelpToolTipText()
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
              .getHelpToolTipText()
              .contains(
                "You do not have full access to Device Streaming in project $unsupportedTestProjectWithoutServiceUse. You are missing the following permissions:serviceusage.services.use"
              )
          }
          dialog.clickDefaultButton()
        }

        val extraDeviceInfoList = TestUtils.deviceInfoListProvider() + preselectedDeviceInfo
        createModalDialogAndInteractWithIt({ selectDeviceAction.actionPerformed(event) }) {
          dialogWrapper ->
          val dialog = dialogWrapper as SelectProjectDialog
          waitForCondition {
            dialog.rootPane.findAllDescendants<ComboBox<String>>().iterator().hasNext()
          }
          val comboBox = dialog.rootPane.findAllDescendants<ComboBox<String>>().first()
          val errorLabel =
            dialog.rootPane.findAllDescendants<JBLabel>().first { label ->
              label.icon == StudioIcons.Common.ERROR
            }
          val planTooltipLabel =
            dialog.rootPane.findAllDescendants<JBLabel>().first { label ->
              label.icon == AllIcons.General.ContextHelp
            }
          val planLabel =
            dialog.rootPane.findAllDescendants<JBLabel>().first { label ->
              label.text?.startsWith("Plan:") == true
            }

          // Select a project with viewer permission
          exceptionToThrow = null
          comboBox.model.selectedItem = viewerTestProject
          waitForCondition {
            cloudProjectManagerFlow.value?.cloudProject?.name == viewerTestProject
          }
          waitForCondition {
            errorLabel
              .getHelpToolTipText()
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
            errorLabel
              .getHelpToolTipText()
              .contains(
                "You do not have full access to Device Streaming in project $unknownPermissionTestProject. You are missing the following permissions:" +
                  permissionFlow.value.missingPermissions.joinToString("")
              )
          }

          val usedMinutesLabel =
            dialog.rootPane.findAllDescendants<JBLabel>().first { usedLabel ->
              usedLabel.text?.endsWith("mins used") == true
            }
          val remainingMinutesLabel =
            dialog.rootPane.findAllDescendants<JBLabel>().first { usedLabel ->
              usedLabel.text?.endsWith("mins remaining") == true
            }

          val usageProgressBar = dialog.rootPane.findAllDescendants<UsageProgressBar>().first()

          assertThat(usageProgressBar.percentage.value).isNull()
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

          // Select a blaze project that supports direct access.
          comboBox.model.selectedItem = blazeProjectName
          waitForCondition { cloudProjectManagerFlow.value?.cloudProject?.name == blazeProjectName }
          waitForCondition { planLabel.text == "Blaze Plan" }
          waitForCondition {
            planTooltipLabel
              .getHelpToolTipText()
              .contains("Blaze plans allow extended usage and is billed monthly.")
          }

          // Select a spark project that supports direct access.
          comboBox.model.selectedItem = supportedProjectName
          waitForCondition {
            cloudProjectManagerFlow.value?.cloudProject?.name == supportedProjectName
          }
          waitForCondition { planLabel.text == "Spark Plan" }
          waitForCondition {
            planTooltipLabel
              .getHelpToolTipText()
              .contains("Spark plans provide limited usage at no cost.")
          }
          assertThat(fakePropertiesComponent[projectRule.project]).isEqualTo(supportedProjectName)
          assertThat(errorLabel.getHelpToolTipText()).isEqualTo("")

          waitForCondition { usedMinutesLabel.text == "60 mins used" }
          waitForCondition { remainingMinutesLabel.text == "less than 15 mins remaining" }
          assertThat(usageProgressBar.percentage.value).isEqualTo(60.0 / 70)

          // Select a spark project that's out of quota
          comboBox.model.selectedItem = noQuotaProjectName
          waitForCondition {
            cloudProjectManagerFlow.value?.cloudProject?.name == noQuotaProjectName
          }
          waitForCondition { usedMinutesLabel.text == "70 mins used" }
          waitForCondition { remainingMinutesLabel.text == "0 mins remaining" }
          assertThat(usageProgressBar.percentage.value).isEqualTo(1.0)

          // Select a blaze project that supports direct access with monthly quota.
          comboBox.model.selectedItem = blazeProjectName
          waitForCondition { cloudProjectManagerFlow.value?.cloudProject?.name == blazeProjectName }
          waitForCondition { planLabel.text == "Blaze Plan" }
          waitForCondition {
            planTooltipLabel
              .getHelpToolTipText()
              .contains("Blaze plans allow extended usage and is billed monthly.")
          }
          waitForCondition { usedMinutesLabel.text == "60 mins used" }
          waitForCondition { remainingMinutesLabel.text == "Blaze Plan may incur charges" }

          // Select a spark project that supports direct access with monthly quota.
          comboBox.model.selectedItem = supportedProjectName
          waitForCondition {
            cloudProjectManagerFlow.value?.cloudProject?.name == supportedProjectName
          }
          waitForCondition { planLabel.text == "Spark Plan" }
          waitForCondition {
            planTooltipLabel
              .getHelpToolTipText()
              .contains(
                "Spark plans provide limited usage at no cost. " +
                  "Switch to a Blaze plan with monthly billing to keep using the service after Spark minutes run out."
              )
          }
          assertThat(fakePropertiesComponent[projectRule.project]).isEqualTo(supportedProjectName)
          assertThat(errorLabel.getHelpToolTipText()).isEqualTo("")

          waitForCondition { usedMinutesLabel.text == "60 mins used" }
          waitForCondition { remainingMinutesLabel.text == "less than 15 mins remaining" }
          assertThat(usageProgressBar.percentage.value).isEqualTo(60.0 / 70)
          mockDeviceSelectionListFlow.value = extraDeviceInfoList.map { DeviceSelection(false, it) }
          dialog.clickDefaultButton()
        }

        // Verify DeviceSource after updating selection.
        val deviceSource = DirectAccessDeviceSource(projectRule.project)
        assertThat(deviceSource.profiles.first().valueOrNull()!!.map { it.name })
          .isEqualTo(extraDeviceInfoList.map { it.name })
      }

      // Start a device and the selector will be disabled with connecting state.
      val mockConnectingDeviceHandle = mock<DirectAccessDeviceHandle>()
      val mockState = mock<DeviceState.Disconnected>()
      Mockito.doReturn(true).whenever(mockState).isTransitioning
      Mockito.doReturn(mockState).whenever(mockConnectingDeviceHandle).state
      devices.value = listOf(mockConnectingDeviceHandle)

      // Start a device and the selector will be disabled with connected state.
      val mockDeviceHandle = mock<DirectAccessDeviceHandle>()
      Mockito.doReturn(mock<DeviceState.Connected>()).whenever(mockDeviceHandle).state
      devices.value = listOf(mockDeviceHandle)

      withContext(AndroidDispatchers.uiThread) {
        createModalDialogAndInteractWithIt({ selectDeviceAction.actionPerformed(event) }) { dialog
          ->
          val selector = dialog.rootPane.findAllDescendants<ComboBox<String>>().first()
          assertThat(selector.isEnabled).isFalse()
          assertThat(selector.toolTipText).isEqualTo("Return all devices to change projects")
          dialog.clickDefaultButton()
        }
      }

      selectDeviceAction.update(event)
      assertThat(selectDeviceAction.templatePresentation.icon).isEqualTo(FirebaseIcons.ACTION_ICON)
    }

  @RunsInEdt
  @Test
  fun testProjectCreation() =
    CoroutineTestUtils.runBlockingWithTimeout {
      // Make sure the [DirectAccessOnboardingService] is initialized after login service
      // replacement.
      ApplicationManager.getApplication()
        .replaceService(
          DirectAccessOnboardingService::class.java,
          DirectAccessOnboardingService(scope),
          projectRule.disposable,
        )

      val mockDirectAccessServiceSetup = mock<DirectAccessServiceSetup>()
      Mockito.doReturn(TestUtils.deviceInfoListProvider() + preselectedDeviceInfo)
        .whenever(mockDirectAccessServiceSetup)
        .getAccessibleDeviceInfoList(null)
      ApplicationManager.getApplication()
        .replaceService(
          DirectAccessServiceSetup::class.java,
          mockDirectAccessServiceSetup,
          projectRule.disposable,
        )
      val devices = MutableStateFlow(listOf<DeviceHandle>())
      val mockProvisioner = mock<DeviceProvisioner>()
      val mockDeviceProvisionerService = mock<DeviceProvisionerService>()
      Mockito.doReturn(devices).whenever(mockProvisioner).devices
      Mockito.doReturn(mockProvisioner).whenever(mockDeviceProvisionerService).deviceProvisioner
      projectRule.project.replaceService(
        DeviceProvisionerService::class.java,
        mockDeviceProvisionerService,
        projectRule.disposable,
      )

      val mockDirectAccessService = mock<DirectAccessService>()
      Mockito.doReturn(cloudProjectManagerFlow)
        .whenever(mockDirectAccessService)
        .cloudProjectManager
      Mockito.doReturn(scope).whenever(mockDirectAccessService).scope
      val mockDeviceSelectionListFlow = MutableStateFlow(listOf<DeviceSelection>())
      Mockito.doReturn(mockDeviceSelectionListFlow)
        .whenever(mockDirectAccessService)
        .deviceSelectionListFlow
      Mockito.doAnswer {
          val cloudProjectName = it.arguments[0] as? String
          cloudProjectName?.let { name -> fakePropertiesComponent[projectRule.project] = name }
          cloudProjectManagerFlow.value =
            createCloudProjectManager(
              scope,
              cloudProjectName,
              isAuthorized = true,
              outOfQuota = false,
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

      firebaseProjectClientRule.setupFirebaseClient(
        throwErrorOnExecute = false,
        returnMalformedJson = false,
        projectList = listOf(),
      )
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

      val handler = LoginFeature.feature<FirebaseLoginFeature>().handler!!
      (handler.latestCreatedFirebaseProject as MutableStateFlow<String>).update { createdProject }
      loginUsersRule.setActiveUser("test@google.com")

      val plugin =
        DirectAccessDeviceProvisionerPlugin(scope.createChildScope(true), projectRule.project)
      CoroutineTestUtils.yieldUntil {
        mockDeviceSelectionListFlow.value.count { it.isSelected } > 0
      }
      assertThat(
          mockDeviceSelectionListFlow.value.filter { it.isSelected }.map { it.deviceInfo.key }
        )
        .isEqualTo(listOf("shiba/34"))

      // Verify the created template before cloud project gets ready.
      CoroutineTestUtils.yieldUntil { plugin.templates.value.size == 1 }
      val template = plugin.templates.value.first()
      CoroutineTestUtils.yieldUntil { template.state.error?.severity == DeviceError.Severity.INFO }
      assertThat(template.state.error?.message).isEqualTo("Ready in a few minutes")
      CoroutineTestUtils.yieldUntil {
        template.activationAction.presentation.value.detail ==
          "Android Device Streaming is setting up and will be ready in a few minutes."
      }

      CoroutineTestUtils.yieldUntil {
        PropertiesComponent.getInstance().getBoolean(DEFAULT_DEVICE_LIST_KEY)
      }
      withContext(AndroidDispatchers.uiThread) {
        createModalDialogAndInteractWithIt({ selectDeviceAction.actionPerformed(event) }) {
          // Start select action before login.
          val dialog = it as SelectProjectDialog
          var projectCreatedLabel: JBLabel? = null

          waitForCondition {
            projectCreatedLabel =
              dialog.rootPane.findAllDescendants<JBLabel>().firstOrNull { label ->
                label.text?.startsWith("Creating project") == true
              }
            projectCreatedLabel != null
          }
          assertThat(projectCreatedLabel!!.text).isEqualTo("Creating project $createdProject")

          // Set up the created project.
          firebaseProjectClientRule.setupFirebaseClient(
            throwErrorOnExecute = false,
            returnMalformedJson = false,
            projectList = listOf(createdProject),
          )
          (service<DirectAccessOnboardingService>().taskFlow
              as MutableStateFlow<DirectAccessOnboardingService.Task?>)
            .update { task -> task?.copy(isPending = false) }

          // The created project should be selected.
          waitForCondition { cloudProjectManagerFlow.value?.cloudProject?.name == createdProject }
          waitForCondition {
            dialog.rootPane.findAllDescendants<ComboBox<String>>().iterator().hasNext()
          }
          val comboBox = dialog.rootPane.findAllDescendants<ComboBox<String>>().first()
          waitForCondition { comboBox.model.selectedItem == createdProject }
          dialog.clickDefaultButton()
        }
      }

      // Verify the created template after cloud project gets ready.
      CoroutineTestUtils.yieldUntil { template.state.error?.severity == null }
      CoroutineTestUtils.yieldUntil { template.activationAction.presentation.value.detail == null }
    }

  @RunsInEdt
  @Test
  fun testLoginPanel() =
    CoroutineTestUtils.runBlockingWithTimeout {
      // Log in as a user without the firebase feature
      loginUsersRule.setActiveUser("test@google.com", features = setOf())
      val selectDeviceAction = SelectProjectAction()
      projectRule.project
        .service<DirectAccessPersistentStateComponent>()
        .state
        .selectedCloudProject = supportedProjectName

      firebaseProjectClientRule.setupFirebaseClient(
        throwErrorOnExecute = false,
        returnMalformedJson = false,
        projectList = listOf(apiDisabledProject, supportedProjectName),
      )

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
          val dialog = it as SelectProjectDialog
          val action = dialog.rootPane.findAllDescendants<JButton>().first()
          assertThat(action.text).isEqualTo("Login and enable Device Streaming")
          action.doClick()

          waitForCondition { LoginFeature.feature<FirebaseLoginFeature>().isLoggedIn() }

          waitForCondition {
            val comboBox = dialog.rootPane.findAllDescendants<ComboBox<String>>().firstOrNull()
            comboBox?.model?.selectedItem == supportedProjectName
          }
        }
      }

      projectRule.project
        .service<DirectAccessPersistentStateComponent>()
        .state
        .selectedCloudProject = null
    }

  @Test
  fun testIconWhenCloudProjectManagerNull() =
    CoroutineTestUtils.runBlockingWithTimeout {
      val selectDeviceAction = SelectProjectAction()
      val event =
        TestActionEvent.createTestEvent {
          when (it) {
            CommonDataKeys.PROJECT.name -> projectRule.project
            else -> null
          }
        }
      selectDeviceAction.update(event)
      assertThat(event.presentation.icon).isEqualTo(FirebaseIcons.ACTION_ICON)
    }

  @Test
  fun testActionNotVisibleWhenProjectIsNull() =
    CoroutineTestUtils.runBlockingWithTimeout {
      val selectDeviceAction = SelectProjectAction()
      val event = TestActionEvent.createTestEvent { null }
      selectDeviceAction.update(event)
      assertThat(event.presentation.isVisible).isFalse()
    }

  @Test(expected = IllegalArgumentException::class)
  fun testActionPerformedThrowsExceptionWhenProjectIsNull() =
    CoroutineTestUtils.runBlockingWithTimeout {
      val selectDeviceAction = SelectProjectAction()
      val event = TestActionEvent.createTestEvent { null }
      selectDeviceAction.update(event)
      selectDeviceAction.actionPerformed(event)
    }

  @Test
  fun testDescription() {
    assertThat(SelectProjectAction().templatePresentation.description)
      .isEqualTo("Open the Device Streaming dialog to select Firebase project and devices")
  }

  private fun createCloudProjectManager(
    scope: CoroutineScope,
    name: String?,
    isAuthorized: Boolean,
    outOfQuota: Boolean,
  ): DirectAccessCloudProjectManager? {
    if (name == null) {
      return null
    }
    val mockCloudProjectManager = mock<DirectAccessCloudProjectManager>()
    Mockito.doReturn(CloudProjectEntry("", name)).whenever(mockCloudProjectManager).cloudProject
    val directAccessReservationManager =
      object : FakeDirectAccessReservationManager() {
        override fun listReservations(): List<DeviceSession> {
          if (isAuthorized) return listOf()
          throw RuntimeException("unauthorized")
        }
      }
    Mockito.doReturn(directAccessReservationManager)
      .whenever(mockCloudProjectManager)
      .reservationManager

    val reservationListFlow =
      RefreshableStateFlow(scope, Long.MAX_VALUE) {
        if (isAuthorized) Pair(directAccessReservationManager.listReservations(), null)
        else Pair(null, exceptionToThrow)
      }
    Mockito.doReturn(reservationListFlow)
      .whenever(mockCloudProjectManager)
      .reservationListFlowWithException
    Mockito.doReturn(Pair(if (outOfQuota) 70L else 60L, 70L))
      .whenever(mockCloudProjectManager)
      .usageQuota

    val accessibleDeviceInfoListFlow =
      RefreshableStateFlow(scope, Long.MAX_VALUE) {
        if (isAuthorized) TestUtils.deviceInfoListProvider() + preselectedDeviceInfo else listOf()
      }
    Mockito.doReturn(accessibleDeviceInfoListFlow)
      .whenever(mockCloudProjectManager)
      .accessibleDeviceInfoListFlow

    Mockito.doReturn(permissionFlow).whenever(mockCloudProjectManager).permissionFlow

    val isBillingEnabledFlow =
      RefreshableStateFlow(scope, Long.MAX_VALUE) {
        when (name) {
          supportedProjectName,
          noQuotaProjectName -> false
          blazeProjectName -> true
          else -> null
        }
      }
    Mockito.doReturn(isBillingEnabledFlow).whenever(mockCloudProjectManager).isBillingEnabledFlow
    return mockCloudProjectManager
  }
}
