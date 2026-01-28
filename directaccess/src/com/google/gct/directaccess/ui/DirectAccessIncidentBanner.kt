/*
 * Copyright (C) 2025 The Android Open Source Project
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

import com.google.gct.directaccess.provisioner.outageUrl
import com.google.gct.directaccess.provisioner.serviceKey
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.ide.BrowserUtil
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.util.preferredHeight
import com.intellij.ui.util.preferredWidth
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBUI.CurrentTheme.Banner
import java.awt.BorderLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.SwingConstants

/** Banner for showing the deprecated state. */
class DirectAccessIncidentBanner(incidents: List<JsonElement>) : EditorNotificationPanel(Status.Warning) {
  init {
    val multiIncidents = incidents.size > 1
    val incident = incidents.first() as JsonObject

    val description =
      if (multiIncidents) "There are ${incidents.size} incidents affecting Device Streaming."
      else incident.asJsonObject.get("external_desc").asString
    text = "<html>$description</html>"
    isOpaque = true

    createActionLabel("More info") {
      var url = "$outageUrl/products/$serviceKey/history"
      if (!multiIncidents && incident.has("uri")) {
        url = "$outageUrl/${incident.get("uri").asString}"
      }
      BrowserUtil.browse(url)
    }
    moveActionLabels()

    setCloseAction { isVisible = false }

    addComponentListener(
      object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) {
          this@DirectAccessIncidentBanner.preferredSize =
            JBDimension(this@DirectAccessIncidentBanner.preferredWidth, getCorrectedPreferredHeight())
        }
      }
    )
  }

  /** Calculates the height of text label, links panel and their respective insets. Adds an extra buffer to the height for spacing. */
  fun getCorrectedPreferredHeight() = myLabel.getPreferredFullHeight() + myLinksPanel.getPreferredFullHeight() + 20.scaled

  private fun JComponent.getPreferredFullHeight(): Int = preferredHeight + insets.top + insets.bottom

  /**
   * Move the action labels to the south of the banner.
   *
   * TODO (b/394364819) layout action labels with stable APIs
   */
  private fun moveActionLabels() {
    val parent = myLinksPanel.parent
    if (parent.layout is BorderLayout) {
      myLabel.verticalTextPosition = SwingConstants.TOP
      parent.add(myLinksPanel, BorderLayout.SOUTH)
      // Align firstActionLabel vertically with myLabel.
      myLinksPanel.border = JBUI.Borders.empty(2, myLabel.icon.iconWidth + myLabel.iconTextGap - 2, 0, 0)
    }
  }

  override fun paintBorder(g: Graphics) {
    super.paintBorder(g)
    with(g as Graphics2D) {
      val color = Banner.WARNING_BORDER_COLOR
      setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
      g.color = color
      drawRect((-5).scaled, 0, width + 10.scaled, height - 1.scaled)
    }
  }

  private val Int.scaled: Int
    get() = JBUI.scale(this)
}
