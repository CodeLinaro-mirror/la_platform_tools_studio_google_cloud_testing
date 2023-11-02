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
import com.android.tools.idea.flags.ExternalSettings
import com.android.tools.idea.flags.StudioFlags
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.directAccessCloudProjectManager
import com.google.gct.directaccess.provisioner.DirectAccessDeviceHandle
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
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.AnActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import icons.FirebaseIcons
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val loginLink =
  AnActionLink(
    "Log in",
    object : AnAction() {
      override fun actionPerformed(e: AnActionEvent) {
        GoogleLogin.instance.logIn()
      }
    }
  )

class SelectProjectAction(
  private val builder: (String, Boolean, CoroutineScope) -> DirectAccessProjectSelector =
    { preferredProject, isEnabled, scope ->
      DirectAccessProjectSelectorImpl(preferredProject, isEnabled, scope).apply {
        isEditable = true
      }
    }
) : AnAction("Configure Device Streaming Project", "text", FirebaseIcons.ACTION_ICON) {
  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    e.presentation.isVisible = service<ExternalSettings>().enableDeviceStreaming
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
      val errorTextPane =
        JBTextArea().apply {
          rows = 2
          foreground = JBColor.RED
          lineWrap = true
          wrapStyleWord = true
          isEditable = false
          isVisible = false
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
                scope
              )
            add(selector.component)
            scope.launch {
              // Wait until fetching all cloud projects to resize [balloon].
              selector.isReady.takeWhile { !it }.collect()
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
    errorTextPane: JBTextArea,
    remainingMinutesLabel: JBLabel
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
        val reservations = project.directAccessCloudProjectManager?.reservationListFlow?.refresh()
        if (reservations == null) {
          // TODO (b/283882413): show different reasons for project without access.
          errorTextPane.text =
            "$cloudProject does not have access to Device Streaming. Select a different project."
          errorTextPane.isVisible = true
          updateRemainingQuota(remainingMinutesLabel, -1)
        } else {
          errorTextPane.isVisible = false
          errorTextPane.text = ""
          launch {
            updateRemainingQuota(
              remainingMinutesLabel,
              withContext(Dispatchers.IO) {
                project.directAccessCloudProjectManager?.remainingMinutes ?: -1
              }
            )
            balloon.revalidate()
          }
        }
        balloon.revalidate()
      }
    }
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
