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
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.provisioner.DirectAccessDeviceHandle
import com.google.gct.directaccess.ui.DirectAccessProjectSelector
import com.google.gct.directaccess.ui.DirectAccessProjectSelectorImpl
import com.google.gct.login.GoogleLogin
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.AnActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import icons.FirebaseIcons
import javax.swing.JComponent
import javax.swing.JPanel
import kotlinx.coroutines.Dispatchers
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
  private val builder: (String, Boolean) -> DirectAccessProjectSelector =
    { preferredProject, isEnabled ->
      DirectAccessProjectSelectorImpl(preferredProject, isEnabled)
    }
) : AnAction("Configure Direct Access Project", "text", FirebaseIcons.ACTION_ICON) {
  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    e.presentation.isVisible = StudioFlags.DIRECT_ACCESS.get()
  }

  override fun actionPerformed(e: AnActionEvent) {
    val mainPanel = JPanel(VerticalLayout(2))
    val balloon =
      JBPopupFactory.getInstance()
        .createBalloonBuilder(mainPanel)
        .setShadow(true)
        .setHideOnAction(true)
        .setBlockClicksThroughBalloon(true)
        .setAnimationCycle(200)
        .setBorderColor(secondaryPanelBackground)
        .setFillColor(secondaryPanelBackground)
        .createBalloon()

    val scope = AndroidCoroutineScope(balloon)
    mainPanel.apply {
      val service = e.project?.service<DirectAccessService>() ?: return
      val devices =
        e.project?.service<DeviceProvisionerService>()?.deviceProvisioner?.devices ?: return
      // TODO (b/283017110): use project from google-services.json if it exists.
      val preferredProject = service.gcpProject?.takeIf { it.isNotEmpty() } ?: ""
      val errorTextPane =
        JBTextArea().apply {
          rows = 2
          foreground = JBColor.RED
          lineWrap = true
          wrapStyleWord = true
          isEditable = false
          isVisible = false
        }
      add(
        JBLabel("Firebase Direct Access", JBLabel.LEFT).apply {
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
                }
              )
            add(selector.component)
            scope.launch {
              selector.selectedProject.collect {
                onProjectChanged(it, service, balloon, errorTextPane)
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
          add(JBLabel("-- mins").apply { foreground = StandardColors.DISABLED_TEXT_COLOR })
          isOpaque = false
        }
      )
      TreeWalker(this).descendantStream().forEach { it.background = secondaryPanelBackground }
      border = JBUI.Borders.empty(4)
    }

    val component = e.inputEvent!!.component as JComponent
    balloon.show(RelativePoint.getSouthOf(component), Balloon.Position.below)
  }

  private suspend fun onProjectChanged(
    project: String,
    service: DirectAccessService,
    balloon: Balloon,
    errorTextPane: JBTextArea
  ) {
    if (project.isEmpty()) {
      return
    }
    service.gcpProject = project
    withContext(AndroidDispatchers.uiThread) {
      balloon.revalidate()
      launch {
        var errorMessage: String? = null
        try {
          withContext(Dispatchers.IO) { service.reservationManager?.listReservations() }
        } catch (_: Exception) {
          // TODO (b/283882413): show different reasons for project without access.
          errorMessage =
            "$project does not have access to Direct Access. Select a different project."
        }
        if (errorMessage != null) {
          errorTextPane.text = errorMessage
          errorTextPane.isVisible = true
        } else {
          errorTextPane.isVisible = false
          errorTextPane.text = ""
        }
        balloon.revalidate()
      }
    }
  }
}
