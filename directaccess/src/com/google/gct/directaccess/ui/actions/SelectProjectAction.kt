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

import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.tools.adtui.TreeWalker
import com.android.tools.adtui.common.AdtUiUtils
import com.android.tools.adtui.common.secondaryPanelBackground
import com.android.tools.adtui.stdui.StandardColors
import com.android.tools.idea.concurrency.AndroidCoroutineScope
import com.android.tools.idea.concurrency.AndroidDispatchers
import com.android.tools.idea.deviceprovisioner.DeviceProvisionerService
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.io.grpc.Status
import com.android.tools.idea.io.grpc.StatusRuntimeException
import com.google.gct.directaccess.DirectAccessPermissionStatus
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.FULL_PERMISSIONS_SET
import com.google.gct.directaccess.directAccessCloudProjectManager
import com.google.gct.directaccess.provisioner.DirectAccessDeviceHandle
import com.google.gct.directaccess.settings.DirectAccessConfiguration
import com.google.gct.directaccess.ui.DirectAccessProjectSelector
import com.google.gct.directaccess.ui.DirectAccessProjectSelectorImpl
import com.google.gct.directaccess.ui.ERROR_FETCHING_FIREBASE_PROJECT
import com.google.gct.directaccess.ui.NO_PROJECTS_AVAILABLE
import com.google.gct.directaccess.ui.SelectDeviceDialog
import com.google.gct.login.GoogleLogin
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.AnActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.util.maximumWidth
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import icons.FirebaseIcons
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JTextPane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CLOUD_TEST_API_ENABLE_LINK =
  "https://console.developers.google.com/apis/api/testing.googleapis.com/overview?project="

private val loginLink =
  AnActionLink(
    "Log in",
    object : AnAction() {
      override fun actionPerformed(e: AnActionEvent) {
        GoogleLogin.instance.logIn(null, null)
      }
    },
  )

class SelectProjectAction(
  private val builder: (String, Boolean, CoroutineScope) -> DirectAccessProjectSelector =
    { preferredProject, isEnabled, scope ->
      DirectAccessProjectSelectorImpl(preferredProject, isEnabled, scope)
    }
) : AnAction("Configure Device Streaming Project", "text", FirebaseIcons.ACTION_ICON) {
  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    e.presentation.isVisible = service<DirectAccessConfiguration>().isEnabled
  }

  override fun actionPerformed(e: AnActionEvent) {
    val mainPanel = JPanel(VerticalLayout(2))
    val balloon =
      JBPopupFactory.getInstance()
        .createBalloonBuilder(mainPanel)
        .setShadow(true)
        .setHideOnAction(false)
        .setBlockClicksThroughBalloon(true)
        .setAnimationCycle(200)
        .setBorderColor(secondaryPanelBackground)
        .setFillColor(secondaryPanelBackground)
        .createBalloon()

    val scope = AndroidCoroutineScope(balloon)
    mainPanel.apply {
      val project = e.project ?: return
      val devices = project.service<DeviceProvisionerService>().deviceProvisioner.devices
      // TODO (b/283017110): use project from google-services.json if it exists.
      val preferredProject = project.directAccessCloudProjectManager?.cloudProject?.name ?: ""
      val errorTextPane: JTextPane =
        object : JTextPane() {
          init {
            foreground = JBColor.RED
            isEditable = false
            isVisible = false
            editorKit = HTMLEditorKitBuilder.simple()
            contentType = "text/html"
            font = UIUtil.getLabelFont()
            addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
            addComponentListener(
              object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                  super.componentResized(e)
                  scope.launch { withContext(AndroidDispatchers.uiThread) { balloon.revalidate() } }
                }
              }
            )
          }

          override fun updateUI() {
            super.updateUI()
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
          }
        }
      val remainingMinutesLabel =
        JBLabel().apply {
          foreground = StandardColors.DISABLED_TEXT_COLOR
          updateRemainingQuota(this, -1)
        }
      add(
        JBLabel("Device Streaming", JBLabel.LEFT).apply {
          font = AdtUiUtils.DEFAULT_FONT.biggerOn(7f)
        }
      )
      add(JBLabel("Early Access Program").apply { foreground = StandardColors.DISABLED_TEXT_COLOR })
      add(
        JPanel(HorizontalLayout(2)).apply {
          add(JBLabel("Project: "))
          if (GoogleLogin.instance.isLoggedIn) {
            val selector =
              builder(
                preferredProject,
                devices.value.filterIsInstance<DirectAccessDeviceHandle>().none {
                  // Disable the selector if there are connected devices.
                  it.state is DeviceState.Connected
                },
                scope,
              )
            add(selector.component)
            scope.launch {
              // Wait until fetching all cloud projects to resize [balloon].
              selector.isReady.takeWhile { !it }.collect()
              errorTextPane.maximumWidth = selector.component.width
              withContext(AndroidDispatchers.uiThread) { balloon.revalidate() }
              selector.selectedProject.collect {
                onProjectChanged(project, it, balloon, errorTextPane, remainingMinutesLabel)
              }
            }
          } else {
            add(loginLink)
          }
          border = JBUI.Borders.empty(5, 0)
          isOpaque = false
        }
      )
      add(errorTextPane)
      add(
        JPanel(HorizontalLayout(3)).apply {
          add(JBLabel("Remaining project time:"))
          add(remainingMinutesLabel)
          isOpaque = false
        }
      )
      if (StudioFlags.DIRECT_ACCESS_ADD_DEVICE.get()) {
        add(JSeparator())
        add(
          ActionLink("Show/hide devices in Device Manager...") {
            balloon.hide()
            SelectDeviceDialog(project).show()
          }
        )
      }
      TreeWalker(this).descendantStream().forEach { it.background = secondaryPanelBackground }
      border = JBUI.Borders.empty(4)
    }

    val component = e.inputEvent!!.component as JComponent
    balloon.show(RelativePoint.getSouthOf(component), Balloon.Position.below)
  }

  private suspend fun onProjectChanged(
    project: Project,
    cloudProject: String,
    balloon: Balloon,
    errorTextPane: JTextPane,
    remainingMinutesLabel: JBLabel,
  ) {
    if (cloudProject.isEmpty() || cloudProject == ERROR_FETCHING_FIREBASE_PROJECT) {
      withContext(AndroidDispatchers.uiThread) { balloon.revalidate() }
      return
    } else if (cloudProject == NO_PROJECTS_AVAILABLE) {
      project.service<DirectAccessService>().selectCloudProject(null)
      return
    }
    project.service<DirectAccessService>().selectCloudProject(cloudProject)
    withContext(AndroidDispatchers.uiThread) {
      balloon.revalidate()
      launch {
        val permission =
          project.directAccessCloudProjectManager?.permissionFlow?.value
            ?: throw RuntimeException("Unable to retrieve permission")
        val reservationListException =
          project.directAccessCloudProjectManager?.reservationListFlowWithException?.value?.second
        val errorMessage = getErrorMessage(cloudProject, permission, reservationListException)
        if (errorMessage != null) {
          errorTextPane.text = errorMessage
          errorTextPane.isVisible = true
          updateRemainingQuota(remainingMinutesLabel, -1)
        } else {
          errorTextPane.text = ""
          errorTextPane.isVisible = false
          launch {
            updateRemainingQuota(
              remainingMinutesLabel,
              withContext(Dispatchers.IO) {
                project.directAccessCloudProjectManager?.remainingMinutes ?: -1
              },
            )
            balloon.revalidate()
          }
        }
        balloon.revalidate()
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
          "Cloud Testing API is not enabled in your project $cloudProject. Enable it by visiting <a href=\"$CLOUD_TEST_API_ENABLE_LINK$cloudProject\">Google Cloud console</a>."
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

  private fun updateRemainingQuota(remainingMinutesLabel: JBLabel, remainingMinutes: Long) {
    val text =
      when {
        remainingMinutes < 0 -> "--"
        remainingMinutes < 30 -> "less than 30"
        else -> remainingMinutes.toString()
      }
    remainingMinutesLabel.text = "$text mins"
  }
}
