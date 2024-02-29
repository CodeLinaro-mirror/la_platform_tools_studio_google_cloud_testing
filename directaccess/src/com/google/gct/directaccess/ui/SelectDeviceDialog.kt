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
package com.google.gct.directaccess.ui

import com.android.adblib.utils.createChildScope
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.tools.adtui.TreeWalker
import com.android.tools.adtui.categorytable.CategoryTable
import com.android.tools.idea.concurrency.AndroidDispatchers
import com.android.tools.idea.deviceprovisioner.DeviceProvisionerService
import com.android.tools.idea.io.grpc.Status
import com.android.tools.idea.io.grpc.StatusRuntimeException
import com.google.gct.directaccess.DirectAccessPermissionStatus
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.FULL_PERMISSIONS_SET
import com.google.gct.directaccess.directAccessCloudProjectManager
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.gct.directaccess.provisioner.DeviceSelection
import com.google.gct.directaccess.provisioner.DirectAccessDeviceHandle
import com.google.gct.login.LoginState
import com.google.gct.login2.GoogleLoginService
import com.google.gct.login2.LoginFeature
import com.google.services.firebase.FirebaseLoginFeature
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.HelpTooltip
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.SearchTextField
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.AnActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.util.maximumHeight
import com.intellij.ui.util.maximumWidth
import com.intellij.ui.util.minimumHeight
import com.intellij.ui.util.preferredHeight
import com.intellij.ui.util.preferredWidth
import com.intellij.util.applyIf
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import icons.StudioIcons
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.event.DocumentEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.VisibleForTesting

private const val CLOUD_TEST_API_ENABLE_LINK =
  "https://console.developers.google.com/apis/api/testing.googleapis.com/overview?project="
private const val SELECTION_TABLE_MINIMUM_HEIGHT = 385

const val ONBOARDING_WORKFLOW_KEY = "direct.access.onboarding"

private val PRESELECTED_DEVICE_KEY_SET = setOf("shiba/34", "felix/33", "b0q/33", "gts8uwifi/33")

val userSpecificFirebaseConsoleLink: String
  get() = "https://console.firebase.google.com?authuser=${service<GoogleLoginService>().getEmail()}"

class SelectDeviceDialog(private val project: Project) : DialogWrapper(false) {
  val scope = project.service<DirectAccessService>().scope.createChildScope(true)

  private val uiDispatcher: CoroutineDispatcher
    get() = AndroidDispatchers.uiThread(ModalityState.any())

  private val loginLink =
    AnActionLink(
      if (service<GoogleLoginService>().isLoggedIn()) "Authorize Firebase" else "Log in to Google",
      object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
          LoginFeature.feature<FirebaseLoginFeature>()
            .logInAsync(
              parentComponent =
                e.dataContext.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT) as? JComponent
            )
        }
      },
    )

  private val viewAllProjectsHyperlink =
    HyperlinkLabel("View All Projects").apply {
      setHyperlinkTarget(userSpecificFirebaseConsoleLink)
    }

  private val isDirectAccessEnabled =
    (if (service<GoogleLoginService>().useOldVersion) service<LoginState>().loginStatus
      else service<GoogleLoginService>().activeUserFlow)
      .map { LoginFeature.feature<FirebaseLoginFeature>().isLoggedIn() }
      .stateIn(
        scope,
        SharingStarted.Eagerly,
        LoginFeature.feature<FirebaseLoginFeature>().isLoggedIn(),
      )

  @VisibleForTesting
  val deviceTable =
    CategoryTable(SelectDeviceTableColumns.columns, primaryKey = { it.deviceInfo.key })

  private val searchTextField =
    SearchTextField(false).apply {
      // If the table is empty and the user decides to resize the dialog,
      // searchTextField gets resized. Set max height to preferred height to avoid that.
      maximumHeight = preferredHeight
      addDocumentListener(
        object : DocumentAdapter() {
          override fun textChanged(e: DocumentEvent) {
            updateTable()
          }
        }
      )
    }

  private val searchText: String
    get() = searchTextField.text

  private var deviceRowDataList: List<SelectDeviceRowData> = emptyList()

  private fun updateDeviceRowDataList(wasDeviceRowDataListUpdated: Boolean = true) {
    val accessibleDeviceInfoSet =
      project.directAccessCloudProjectManager
        ?.accessibleDeviceInfoListFlow
        ?.stateFlow
        ?.value
        ?.map { it.key }
        ?.toSet() ?: setOf()
    val oldSelectedDeviceInfoSet =
      deviceRowDataList.filter { it.isSelected }.map { it.deviceInfo.key }.toSet()
    deviceRowDataList =
      project
        .service<DirectAccessService>()
        .deviceSelectionListFlow
        .value
        .map {
          SelectDeviceRowData(
            it.deviceInfo.key in accessibleDeviceInfoSet,
            if (wasDeviceRowDataListUpdated) it.deviceInfo.key in oldSelectedDeviceInfoSet
            else it.isSelected,
            it.deviceInfo,
          )
        }
        .sortedBy { it.deviceInfo.title }

    if (
      !PropertiesComponent.getInstance().getBoolean(ONBOARDING_WORKFLOW_KEY, false) &&
        deviceRowDataList.isNotEmpty() &&
        deviceRowDataList.count { it.isSelected } == 0
    ) {
      var count = 0
      deviceRowDataList.forEach {
        if (it.isEnabled && it.deviceInfo.key in PRESELECTED_DEVICE_KEY_SET) {
          it.isSelected = true
          ++count
        }
      }
      if (count > 0) {
        PropertiesComponent.getInstance().setValue(ONBOARDING_WORKFLOW_KEY, true)
      }
    }
  }

  private fun updateTable() {
    deviceTable.values.forEach { deviceTable.removeRow(it) }
    deviceRowDataList.filter { it.applySearchFilter() }.forEach { deviceTable.addOrUpdateRow(it) }
  }

  /** Updates the device list followed by updating the table */
  private fun refreshTableData() {
    updateDeviceRowDataList()
    updateTable()
  }

  init {
    title = "Select Devices"
    updateDeviceRowDataList(false)
    init()
  }

  private fun SelectDeviceRowData.applySearchFilter(): Boolean {
    if (searchText.isEmpty()) return true
    val words = searchText.split(Regex(" +"))
    return words.all {
      with(deviceInfo) {
        // Check in title string in place of checking separately in manufacturer and name for cases
        // where user types "Google Pixel"
        title.contains(it, true) ||
          // Match exact for numeric columns.
          api.toString() == it ||
          screenX.toString() == it ||
          screenY.toString() == it ||
          screenDensity.toString() == it
      }
    }
  }

  override fun dispose() {
    super.dispose()
    scope.cancel()
  }

  override fun createCenterPanel(): JComponent {
    val topPanel = JPanel(VerticalLayout(5))
    topPanel.add(createTitleLabel("Device Streaming in Android Studio"))
    topPanel.add(
      createTextPane(topPanel).apply {
        text =
          "Device Streaming in Android Studio provides secure direct ADB access to a wide range of Android devices hosted by Firebase," +
            " which you can use to debug and interact with your app. <br>" +
            "${if (isDirectAccessEnabled.value) "Select" else "Log in and select"} a Firebase Spark plan project for limited access at no cost," +
            " or select a Blaze project for pay-as-you-go access that’s billed monthly. " +
            "<a href=https://d.android.com/r/studio-ui/device-streaming/help>Learn more</a>↗"
      }
    )
    topPanel.add(TitledSeparator("Firebase Project Information"))
    topPanel.add(createSelectProjectComponent())
    topPanel.add(TitledSeparator("Select Devices"))
    topPanel.add(
      createTextPane(topPanel).apply {
        text =
          "Select the devices you want to access. The devices you select are added to the Device Manager " +
            "and deploy target dropdown menu in the main toolbar."
      }
    )

    val panel = JPanel(BorderLayout(0, 5))
    panel.add(topPanel, BorderLayout.NORTH)
    panel.add(createTableComponent(), BorderLayout.CENTER)

    // TODO(b/323428328) configure colors properly.
    TreeWalker(panel).descendantStream().forEach { it.background = null }
    return panel
  }

  private fun createTextPane(container: JPanel): JTextPane =
    object : JTextPane() {
      init {
        isEditable = false
        editorKit = HTMLEditorKitBuilder.simple()
        contentType = "text/html"
        maximumWidth = deviceTable.preferredWidth
        size
        font = UIUtil.getLabelFont()
        addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
        addComponentListener(
          object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
              super.componentResized(e)
              scope.launch { withContext(uiDispatcher) { container.revalidate() } }
            }
          }
        )
      }

      override fun updateUI() {
        super.updateUI()
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
      }

      override fun getPreferredSize(): Dimension {
        return ui.getPreferredSize(container)
      }
    }

  private fun createTitleLabel(text: String) =
    JBLabel(text, JBLabel.LEFT).apply { font = JBFont.h2() }

  private fun createSelectProjectComponent(): JPanel {
    val panel = JPanel(VerticalLayout(5)).apply { border = JBUI.Borders.empty(5, 10) }
    val chooseProjectPanel = JPanel(HorizontalLayout(5))

    chooseProjectPanel.add(JBLabel("Choose Project:"))
    val selectorLayout = CardLayout()
    val selectorPanel = JPanel(selectorLayout)

    val planLabel =
      JBLabel().apply {
        horizontalTextPosition = JBLabel.LEFT
        icon = AllIcons.General.ContextHelp
        font = JBFont.medium().asBold()
      }
    val usedMinutesLabel = JBLabel()
    val remainingMinutesLabel = JBLabel().apply { foreground = UIUtil.getLabelInfoForeground() }
    updateRemainingQuota(usedMinutesLabel, remainingMinutesLabel, null)
    updatePlan(planLabel, null)
    val updateSelector: (Boolean) -> Unit = { enabled ->
      selectorPanel.add(
        if (enabled) {
          val component = JPanel(HorizontalLayout(5))
          val selector =
            DirectAccessProjectSelectorImpl(
              project.service<DirectAccessService>().cloudProjectManager.value?.cloudProject?.name
                ?: "",
              project
                .service<DeviceProvisionerService>()
                .deviceProvisioner
                .devices
                .value
                .filterIsInstance<DirectAccessDeviceHandle>()
                .none {
                  // Disable the selector if there are connected devices.
                  it.state is DeviceState.Connected
                },
              scope,
            )
          component.add(selector.component)
          val errorIcon =
            JBLabel().apply {
              icon = StudioIcons.Common.ERROR
              isVisible = false
            }
          component.add(errorIcon)
          scope.launch {
            selector.isReady.takeWhile { !it }.collect()
            selector.selectedProject.collect {
              onProjectChanged(
                project,
                it,
                panel,
                errorIcon,
                planLabel,
                usedMinutesLabel,
                remainingMinutesLabel,
              )
              withContext(uiDispatcher) {
                refreshTableData()
                panel.revalidate()
                panel.repaint()
              }
            }
          }
          component
        } else {
          loginLink
        },
        enabled.toString(),
      )
      selectorLayout.show(selectorPanel, enabled.toString())
    }
    var isNowEnabled = isDirectAccessEnabled.value
    updateSelector(isDirectAccessEnabled.value)

    val usagePanel =
      JPanel(HorizontalLayout(5)).apply {
        add(usedMinutesLabel)
        add(remainingMinutesLabel)
      }
    val viewAllProjectsPanel =
      JPanel(HorizontalLayout(0)).apply {
        add(viewAllProjectsHyperlink, HorizontalLayout.LEFT)
        isVisible = isDirectAccessEnabled.value
      }
    scope.launch {
      isDirectAccessEnabled.collect {
        if (it != isNowEnabled) {
          isNowEnabled = it
          updateSelector(isNowEnabled)
          viewAllProjectsPanel.isVisible = isNowEnabled
        }
      }
    }
    chooseProjectPanel.add(selectorPanel)
    panel.add(chooseProjectPanel)
    panel.add(viewAllProjectsPanel)
    panel.add(planLabel)
    panel.add(usagePanel)
    return panel
  }

  private fun createTableComponent() =
    JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      add(searchTextField)
      deviceRowDataList.forEach { deviceTable.addOrUpdateRow(it) }
      add(JBScrollPane().apply { deviceTable.addToScrollPane(this) })
      searchTextField.preferredWidth = preferredWidth
      // Show a smaller window for an appropriate size of dialog.
      // Showing all devices causes the dialog to be very tall.
      minimumHeight = SELECTION_TABLE_MINIMUM_HEIGHT
      preferredHeight = minimumHeight

      scope.launch(uiDispatcher) {
        project.service<DirectAccessService>().deviceSelectionListFlow.collect {
          refreshTableData()
        }
      }
    }

  private suspend fun onProjectChanged(
    project: Project,
    cloudProject: String,
    parent: JPanel,
    errorIcon: JBLabel,
    planLabel: JBLabel,
    usedMinutesLabel: JBLabel,
    remainingMinutesLabel: JBLabel,
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
          updateRemainingQuota(usedMinutesLabel, remainingMinutesLabel, null)
          updatePlan(planLabel, null)
        } else {
          errorIcon.toolTipText = ""
          errorIcon.isVisible = false
          launch {
            updateRemainingQuota(
              usedMinutesLabel,
              remainingMinutesLabel,
              withContext(Dispatchers.IO) {
                try {
                  project.directAccessCloudProjectManager?.usageQuota
                } catch (e: Exception) {
                  null
                }
              },
            )
            updatePlan(
              planLabel,
              project.directAccessCloudProjectManager?.isBillingEnabledFlow?.value,
            )
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
    quota: Pair<Long, Long>?,
  ) {
    usedMinutesLabel.text = "${quota?.first?.toString() ?: "--" } mins used"
    val remainingText =
      quota?.let {
        val remainingMinutes = it.second - it.first
        when {
          remainingMinutes < 0 -> "--"
          remainingMinutes < 30 -> "less than 30"
          else -> remainingMinutes.toString()
        }
      } ?: "--"
    remainingMinutesLabel.text = "$remainingText mins remaining"
  }

  private fun updatePlan(planLabel: JBLabel, isBillingEnabled: Boolean?) {
    planLabel.text = isBillingEnabled?.let { if (it) "Blaze Plan" else "Spark Plan" } ?: "Plan: -"

    val description =
      when (isBillingEnabled) {
        true -> "This project is on the Blaze plan."
        false -> "Spark plans provide limited usage at no cost."
        null -> "Billing information not available."
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
            true -> "https://d.android.com/r/studio-ui/device-streaming/pricing"
            false -> "https://d.android.com/r/studio-ui/device-streaming/firebase-plans"
          }
        setLink(linkText) { BrowserUtil.browse(link) }
      }
      .installOn(planLabel)
  }

  override fun doOKAction() {
    super.doOKAction()
    val selectedDeviceInfoSet =
      deviceRowDataList.filter { it.isSelected }.map { it.deviceInfo }.toSet()
    project.service<DirectAccessService>().deviceSelectionListFlow.update {
      it.map { deviceSelection ->
        DeviceSelection(
          deviceSelection.deviceInfo in selectedDeviceInfoSet,
          deviceSelection.deviceInfo,
        )
      }
    }
  }

  private val DeviceInfo.title: String
    get() = "$manufacturer $name"
}
