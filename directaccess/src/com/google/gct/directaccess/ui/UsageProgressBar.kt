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

import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.ImageUtil
import java.awt.AlphaComposite
import java.awt.AlphaComposite.SRC_IN
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.JPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.annotations.VisibleForTesting

private const val DEFAULT_HEIGHT = 30
private const val DEFAULT_GAP = 3

/**
 * A thicker progress bar with circle borders on its left and right sides.
 *
 * The left circle border is filled with active color when [percentage] has a nonnull value.
 */
class UsageProgressBar(
  scope: CoroutineScope,
  @VisibleForTesting val percentage: StateFlow<Double?>,
) : JPanel() {

  override fun paintComponent(g: Graphics?) {
    val buffImg = ImageUtil.createImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g2d = buffImg.createGraphics()
    g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    val gap = JBUIScale.scale(DEFAULT_GAP)
    val diameter = height - 2 * gap
    val radius = diameter / 2
    val barWidth = width - 2 * (gap + radius)
    g2d.color = JBColor.WHITE
    g2d.fillOval(gap, gap, diameter, diameter)
    g2d.fillRect(radius + gap, gap, barWidth, diameter)
    g2d.fillOval(width - diameter - gap, gap, diameter, diameter)
    g2d.composite = AlphaComposite.getInstance(SRC_IN)
    g2d.color = JBColor.GRAY
    g2d.fillRect(0, 0, width, height)
    val p = percentage.value
    val effectiveBarWidth = width - 2 * gap - radius
    val activeBarWidth = p?.let { gap + radius + p * effectiveBarWidth }?.toInt() ?: 0
    g2d.color = JBColor.BLUE
    g2d.fillRect(0, 0, activeBarWidth, height)
    g2d.color = background
    g2d.fillRect(activeBarWidth.coerceAtLeast(gap + radius), 0, gap, height)
    (g as Graphics2D).drawImage(buffImg, 0, 0, width, height, null)
  }

  init {
    preferredSize = Dimension(0, JBUIScale.scale(DEFAULT_HEIGHT))
    scope.launch { percentage.collect { repaint() } }
  }
}
