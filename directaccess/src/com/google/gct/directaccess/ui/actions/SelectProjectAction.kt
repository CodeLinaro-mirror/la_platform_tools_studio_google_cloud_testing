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

import com.android.tools.adtui.TreeWalker
import com.android.tools.adtui.common.AdtUiUtils
import com.android.tools.adtui.common.secondaryPanelBackground
import com.android.tools.adtui.stdui.StandardColors
import com.android.tools.idea.concurrency.AndroidCoroutineScope
import com.android.tools.idea.flags.StudioFlags
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.ui.DirectAccessProjectSelector
import com.google.gct.directaccess.ui.DirectAccessProjectSelectorImpl
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import icons.FirebaseIcons
import javax.swing.JComponent
import javax.swing.JPanel
import kotlinx.coroutines.launch

class SelectProjectAction(
  private val builder: (String) -> DirectAccessProjectSelector = {
    DirectAccessProjectSelectorImpl(it)
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
      val service = e.project?.getService(DirectAccessService::class.java) ?: return
      // TODO (b/283017110): use project from google-services.json if it exists.
      val preferredProject = service.gcpProject?.takeIf { it.isNotEmpty() } ?: ""
      add(
        JBLabel("Firebase Direct Access", JBLabel.LEFT).apply {
          font = AdtUiUtils.DEFAULT_FONT.biggerOn(7f)
        }
      )
      add(JBLabel("Early Access Program").apply { foreground = StandardColors.DISABLED_TEXT_COLOR })
      add(
        JPanel(HorizontalLayout(2)).apply {
          add(JBLabel("Project: "))
          val selector = builder(preferredProject)
          add(selector.component)
          scope.launch {
            selector.selectedProject.collect {
              if (it.isNotEmpty()) {
                service.gcpProject = it
                balloon.revalidate()
              }
            }
          }
          border = JBUI.Borders.empty(5, 0)
          isOpaque = false
        }
      )

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

    val component = e.inputEvent.component as JComponent
    balloon.show(RelativePoint.getSouthOf(component), Balloon.Position.below)
  }
}
