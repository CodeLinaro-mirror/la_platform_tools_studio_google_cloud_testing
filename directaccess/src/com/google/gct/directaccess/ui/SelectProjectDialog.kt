/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.adblib.utils.createChildScope
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.tools.adtui.TreeWalker
import com.android.tools.idea.concurrency.AndroidDispatchers
import com.android.tools.idea.deviceprovisioner.DeviceProvisionerService
import com.google.gct.directaccess.DirectAccessPermissionStatus
import com.google.gct.directaccess.DirectAccessPersistentStateComponent
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.FULL_PERMISSIONS_SET
import com.google.gct.directaccess.directAccessCloudProjectManager
import com.google.gct.directaccess.provisioner.DirectAccessDeviceHandle
import com.google.gct.login2.GoogleLoginService
import com.google.gct.login2.LoginFeature
import com.google.services.firebase.FirebaseLoginFeature
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.HelpTooltip
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.EmptySpacingConfiguration
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.applyIf
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import icons.StudioIcons
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.awt.CardLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CLOUD_TEST_API_ENABLE_LINK =
  "https://console.developers.google.com/apis/api/testing.googleapis.com/overview?project="

class SelectProjectDialog(private val project: Project) : DialogWrapper(false) {
  val scope = project.service<DirectAccessService>().scope.createChildScope(true)

  private val uiDispatcher: CoroutineDispatcher
    get() = AndroidDispatchers.uiThread(ModalityState.any())

  private val viewPricingDetailsHyperlink =
    HyperlinkLabel("View Pricing Details").apply { setHyperlinkTarget(VIEW_PRICING_DETAILS_LINK) }

  private val isDirectAccessEnabled =
    service<GoogleLoginService>()
      .activeUserFlow
      .map { LoginFeature.feature<FirebaseLoginFeature>().isLoggedIn() }
      .stateIn(
        scope,
        SharingStarted.Eagerly,
        LoginFeature.feature<FirebaseLoginFeature>().isLoggedIn(),
      )

  init {
    setOKButtonText("Done")
    title = "Configure Device Streaming"
    init()
  }

  override fun createCenterPanel(): JComponent {
    val layout = CardLayout()
    val panel = JPanel(layout)
    val loginKey = "Login"
    val projectSelectionKey = "Project Selection"
    panel.add(createLoginPanel(), loginKey)

    val updateActivePanel: (Boolean) -> Unit = { isEnabled ->
      if (isEnabled) {
        panel.add(createProjectSelectionPanel(), projectSelectionKey)
        layout.show(panel, projectSelectionKey)
      } else {
        layout.show(panel, loginKey)
      }
    }
    var isNowEnabled = isDirectAccessEnabled.value
    updateActivePanel(isNowEnabled)

    TreeWalker(panel).descendantStream().forEach { it.background = null }
    scope.launch {
      isDirectAccessEnabled.collect { isEnabled ->
        if (isNowEnabled != isEnabled) {
          isNowEnabled = isEnabled
          updateActivePanel(isEnabled)
        }
      }
    }
    return panel
  }

  private fun createTitleLabel(text: String) =
    JBLabel(text, JBLabel.LEFT).apply { font = JBFont.h2() }

  private fun createLoginPanel(): JPanel {
    val topPanel = JPanel(VerticalLayout(5))
    topPanel.add(createTitleLabel("Android Device Streaming"))
    topPanel.add(
      panel {
        customizeSpacingConfiguration(EmptySpacingConfiguration()) {
          row {
            // TODO (b/364673782): update text with UX requirements.
            text(
                "Android Device Streaming, powered by Firebase, provides secure direct ADB access to a wide range of Android devices," +
                  " which you can use to debug and interact with your app.  <br>" +
                  "Android Device Streaming is a Beta service and may encounter service disruptions or issues as performance improves." +
                  " Select a Firebase Spark plan project for limited access at no cost," +
                  " or select a Blaze project for pay-as-you-go access that’s billed monthly. " +
                  "<a href=https://d.android.com/r/studio-ui/device-streaming/help>Learn more</a>"
              )
              .apply { align(Align.FILL) }
          }
        }
      }
    )
    topPanel.add(TitledSeparator())
    val panel = JPanel(VerticalLayout(5))
    panel.add(topPanel)
    val bottomPanel = JPanel(HorizontalLayout(0))
    val button =
      JButton().apply {
        action =
          object : AbstractAction("Login and enable Device Streaming") {
            override fun actionPerformed(e: ActionEvent) {
              LoginFeature.feature<FirebaseLoginFeature>()
                .logInAsync(parentComponent = this@SelectProjectDialog.rootPane)
            }
          }
      }
    bottomPanel.add(button)
    panel.add(bottomPanel)
    return panel
  }

  private fun createProjectSelectionPanel(): JPanel {
    val panel = JPanel(VerticalLayout(5)).apply { border = JBUI.Borders.empty(5, 10) }
    panel.add(createTitleLabel("Project Information"))

    val planLabel =
      JBLabel().apply {
        horizontalTextPosition = JBLabel.LEFT
        font = JBFont.medium().asBold()
      }
    val planHelpIcon = JBLabel(AllIcons.General.ContextHelp)
    val planPanel =
      JPanel(HorizontalLayout(5)).apply {
        border = JBUI.Borders.empty(1, 0)
        add(planLabel)
        add(planHelpIcon)
      }
    val usedMinutesLabel = JBLabel()
    val remainingMinutesLabel = JBLabel().apply { foreground = UIUtil.getLabelInfoForeground() }
    val usageFlow = MutableStateFlow<Double>(0.0)
    val usageProgressBar = UsageProgressBar(scope, usageFlow)
    val grayLabelFactory: (String) -> JBLabel = { text ->
      JBLabel(text).apply { foreground = UIUtil.getLabelInfoForeground() }
    }
    val informationLabel =
      grayLabelFactory("Estimated minutes based on usage across all Firebase project members.")
    val instructionPanel =
      JPanel(HorizontalLayout(0)).apply {
        foreground = UIUtil.getLabelInfoForeground()
        add(grayLabelFactory("Click "))
        add(
          grayLabelFactory("dropdown in device manager to add new devices.").apply {
            icon = StudioIcons.Common.ADD
          }
        )
      }
    updateRemainingQuota(usedMinutesLabel, remainingMinutesLabel, usageFlow, null)
    updatePlan(planLabel, planHelpIcon, null)

    val preferredProject =
      project.service<DirectAccessService>().cloudProjectManager.value?.cloudProject?.name
        ?: project.service<DirectAccessPersistentStateComponent>().compatibleSelectedCloudProject
    val chooseProjectPanel = JPanel(HorizontalLayout(5))
    val selector =
      DirectAccessProjectSelectorImpl2(
        project,
        preferredProject,
        project
          .service<DeviceProvisionerService>()
          .deviceProvisioner
          .devices
          .value
          .filterIsInstance<DirectAccessDeviceHandle>()
          .none {
            // Disable the selector if there are connecting or connected devices.
            it.state is DeviceState.Connected ||
              (it.state is DeviceState.Disconnected && it.state.isTransitioning)
          },
        scope,
      )
    chooseProjectPanel.add(selector.component)
    val errorIcon =
      JBLabel().apply {
        icon = StudioIcons.Common.ERROR
        isVisible = false
      }
    chooseProjectPanel.add(errorIcon)
    scope.launch {
      selector.isReady.takeWhile { !it }.collect()
      selector.selectedProject.collect {
        onProjectChanged(
          project,
          it,
          panel,
          errorIcon,
          planLabel,
          planHelpIcon,
          usedMinutesLabel,
          remainingMinutesLabel,
          usageFlow,
        )
      }
    }

    val usagePanel =
      JPanel(HorizontalLayout(5)).apply {
        add(usedMinutesLabel)
        add(remainingMinutesLabel)
        add(viewPricingDetailsHyperlink)
      }
    panel.add(chooseProjectPanel)
    panel.add(planPanel)
    panel.add(usagePanel)
    panel.add(usageProgressBar)
    panel.add(informationLabel)
    panel.add(instructionPanel)
    return panel
  }

  private suspend fun onProjectChanged(
    project: Project,
    cloudProject: String,
    parent: JPanel,
    errorIcon: JBLabel,
    planLabel: JBLabel,
    planHelpIcon: JBLabel,
    usedMinutesLabel: JBLabel,
    remainingMinutesLabel: JBLabel,
    usageFlow: MutableStateFlow<Double>,
  ) {
    errorIcon.isVisible = false
    if (cloudProject == ERROR_FETCHING_FIREBASE_PROJECT) {
      withContext(AndroidDispatchers.uiThread) { parent.revalidate() }
      return
    } else if (cloudProject.isEmpty() || cloudProject == NO_PROJECTS_AVAILABLE) {
      project.service<DirectAccessService>().selectCloudProject(null)
      return
    }
    project.service<DirectAccessService>().selectCloudProject(cloudProject)
    withContext(uiDispatcher) {
      parent.revalidate()
      launch {
        val permission =
          project.directAccessCloudProjectManager?.permissionFlow?.value
            ?: throw RuntimeException("Unable to retrieve permission")
        val reservationListException =
          project.directAccessCloudProjectManager?.reservationListFlowWithException?.value?.second
        val errorMessage = getErrorMessage(cloudProject, permission, reservationListException)
        if (errorMessage != null) {
          val (linkText, link) =
            when {
              errorMessage.contains("Google Cloud console") ->
                Pair("Google Cloud console", "$CLOUD_TEST_API_ENABLE_LINK$cloudProject")
              else ->
                Pair(
                  "Learn More",
                  "http://d.android.com/r/studio-ui/device-streaming/help/permissions",
                )
            }
          HelpTooltip()
            .setDescription(errorMessage)
            .setLink(linkText) { BrowserUtil.browse(link) }
            .installOn(errorIcon)
          errorIcon.isVisible = true
          errorIcon.revalidate()
          errorIcon.repaint()
          updateRemainingQuota(usedMinutesLabel, remainingMinutesLabel, usageFlow, null)
          updatePlan(planLabel, planHelpIcon, null)
        } else {
          errorIcon.toolTipText = ""
          errorIcon.isVisible = false
          launch {
            val isBillingEnabled =
              project.directAccessCloudProjectManager?.isBillingEnabledFlow?.value
            updateRemainingQuota(
              usedMinutesLabel,
              remainingMinutesLabel,
              usageFlow,
              withContext(Dispatchers.IO) {
                try {
                  project.directAccessCloudProjectManager?.usageQuota
                } catch (e: Exception) {
                  null
                }
              },
              isBillingEnabled,
            )
            updatePlan(planLabel, planHelpIcon, isBillingEnabled)
            parent.revalidate()
          }
        }
        parent.revalidate()
      }
    }
  }

  private fun getErrorMessage(
    cloudProject: String,
    permission: DirectAccessPermissionStatus,
    exception: Exception?,
  ): String? {
    return if (exception != null) {
      getErrorMessageFromException(cloudProject, permission, exception)
    } else {
      when (permission) {
        is DirectAccessPermissionStatus.None ->
          "You do not have access to Device Streaming in project $cloudProject."
        is DirectAccessPermissionStatus.Viewer,
        is DirectAccessPermissionStatus.MissingServiceUse,
        is DirectAccessPermissionStatus.Unknown ->
          "You do not have full access to Device Streaming in project $cloudProject. You are missing the following permissions:<br>" +
            permission.missingPermissions.joinToString("<br>")
        is DirectAccessPermissionStatus.Full -> null
      }
    }
  }

  private fun getErrorMessageFromException(
    cloudProject: String,
    permission: DirectAccessPermissionStatus,
    exception: Exception,
  ) =
    if (exception is StatusRuntimeException) {
      getStatusCodeErrorMessage(cloudProject, permission, exception)
    } else {
      "An unknown error occurred when checking your permissions."
    }

  private fun getStatusCodeErrorMessage(
    cloudProject: String,
    permission: DirectAccessPermissionStatus,
    exception: StatusRuntimeException,
  ) =
    if (exception.status.code == Status.Code.PERMISSION_DENIED) {
      val description = exception.status.description
      val apiDisabledString =
        "Cloud Testing API has not been used in project $cloudProject before or it is disabled."
      val serviceUsageMissing = "Grant the caller the roles/serviceusage.serviceUsageConsumer role"
      when {
        description?.contains(apiDisabledString, true) == true ->
          "Cloud Testing API is not enabled in your project $cloudProject. Enable it by visiting Google Cloud console."
        description?.contains(serviceUsageMissing, true) == true -> {
          if (permission.missingPermissions == FULL_PERMISSIONS_SET) {
            "You do not have access to Device Streaming in project $cloudProject."
          } else {
            "You do not have full access to Device Streaming in project $cloudProject. You are missing the following permissions:<br>" +
              permission.missingPermissions.joinToString("<br>")
          }
        }
        else -> "You do not have access to Device Streaming in project $cloudProject."
      }
    } else {
      "An unknown error occurred when checking your permissions."
    }

  private fun updateRemainingQuota(
    usedMinutesLabel: JBLabel,
    remainingMinutesLabel: JBLabel,
    usageFlow: MutableStateFlow<Double>,
    quota: Pair<Long, Long>?,
    isBillingEnabled: Boolean? = null,
  ) {
    usageFlow.value = quota?.let { it.first.toDouble() / it.second } ?: 0.0

    usedMinutesLabel.text = "${quota?.first?.toString() ?: "--" } mins used"

    if (isBillingEnabled == true) {
      remainingMinutesLabel.text = "Blaze Plan may incur charges"
    } else {
      val remainingText =
        quota?.let {
          val remainingMinutes = it.second - it.first
          when {
            remainingMinutes <= 0 -> "0"
            remainingMinutes < 15 -> "less than 15"
            else -> remainingMinutes.toString()
          }
        } ?: "--"
      remainingMinutesLabel.text = "$remainingText mins remaining"
    }
  }

  private fun updatePlan(planLabel: JBLabel, helpIcon: JBLabel, isBillingEnabled: Boolean?) {
    planLabel.text = isBillingEnabled?.let { if (it) "Blaze Plan" else "Spark Plan" } ?: "Plan: -"

    var description =
      when (isBillingEnabled) {
        true -> "This project is on the Blaze plan."
        false -> "Spark plans provide limited usage at no cost."
        null -> "Billing information not available."
      }

    when (isBillingEnabled) {
      true -> description = "Blaze plans allow extended usage and is billed monthly."
      false ->
        description +=
          " Switch to a Blaze plan with monthly billing to keep using the service after Spark minutes run out."
      else -> {}
    }

    HelpTooltip()
      .setDescription(description)
      .applyIf(isBillingEnabled != null) {
        val linkText =
          when (isBillingEnabled!!) {
            true -> "View Pricing"
            false -> "Learn More..."
          }
        val link =
          when (isBillingEnabled) {
            true -> VIEW_PRICING_DETAILS_LINK
            false -> "https://d.android.com/r/studio-ui/device-streaming/firebase-plans"
          }
        setLink(linkText) { BrowserUtil.browse(link) }
      }
      .installOn(helpIcon)
  }

  override fun doOKAction() {
    super.doOKAction()
    val directAccessService = project.service<DirectAccessService>()
    // Apply default devices if the selected project has a nonempty device catalog.
    if (
      directAccessService.cloudProjectManager.value
        ?.accessibleDeviceInfoListFlow
        ?.stateFlow
        ?.value
        ?.isNotEmpty() == true
    ) {
      directAccessService.maybeApplyDefaultDevices()
    }
  }

  /** This dialog only shows the OK action that does nothing. */
  override fun createActions(): Array<Action> {
    return arrayOf(okAction)
  }
}
