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

import com.google.gct.directaccess.DirectAccessCloudProjectManager
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.HelpTooltip
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.applyIf
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import icons.StudioIcons
import java.awt.CardLayout
import javax.swing.JLabel
import javax.swing.JPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jdesktop.swingx.VerticalLayout

private val SPARK_PLAN_KEY = "Spark"
private val BLAZE_PLAN_KEY = "Blaze"

class ProjectInformationPanel(
  scope: CoroutineScope,
  cloudProjectManagerFlow: StateFlow<DirectAccessCloudProjectManager?>,
) : JPanel(VerticalLayout()) {
  private val planLabel =
    JBLabel().apply {
      horizontalTextPosition = JBLabel.LEFT
      font = JBFont.medium().asBold()
    }
  private val planHelpIcon = JBLabel(AllIcons.General.ContextHelp)
  private val planPanel =
    JPanel(HorizontalLayout(5)).apply {
      border = JBUI.Borders.empty(1, 0)
      add(planLabel)
      add(planHelpIcon)
    }

  private val grayLabelFactory: (String) -> JBLabel = { text ->
    JBLabel(text).apply { foreground = UIUtil.getLabelInfoForeground() }
  }

  private val sparkUsedMinutesLabel = JBLabel()
  private val sparkRemainingMinutesLabel = grayLabelFactory("")
  private val blazeUsedMinutesLabel = JBLabel().apply { font = JBFont.h2().asBold() }
  private val blazeUsedMinutesUnitLabel = JBLabel("mins used")
  private val blazePricingInfoLabel = grayLabelFactory("Blaze Plan may incur charges")

  private val usageFlow = MutableStateFlow<Double?>(null)
  private val usageProgressBar = UsageProgressBar(scope, usageFlow)

  private val informationLabel =
    grayLabelFactory("Estimated minutes based on usage across all Firebase project members.")
  private val instructionPanel =
    JPanel(HorizontalLayout(0)).apply {
      foreground = UIUtil.getLabelInfoForeground()
      add(grayLabelFactory("Click "))
      add(
        grayLabelFactory("dropdown in device manager to add new devices.").apply {
          icon = StudioIcons.Common.ADD
        }
      )
    }

  private val planCardLayout = CardLayout()
  private val usagePanel = JPanel(planCardLayout)

  private val viewPricingDetailsHyperlink =
    HyperlinkLabel("View Pricing Details").apply { setHyperlinkTarget(VIEW_PRICING_DETAILS_LINK) }

  init {
    val sparkUsagePanel =
      JPanel(VerticalLayout(5)).apply {
        add(
          JPanel(HorizontalLayout(5)).apply {
            add(sparkUsedMinutesLabel)
            add(sparkRemainingMinutesLabel)
            add(viewPricingDetailsHyperlink)
          }
        )
        add(usageProgressBar)
      }
    val blazeUsagePanel =
      JPanel(HorizontalLayout(5)).apply {
        add(blazeUsedMinutesLabel.apply { verticalAlignment = JLabel.BOTTOM })
        add(blazeUsedMinutesUnitLabel.apply { verticalAlignment = JLabel.BOTTOM })
        add(blazePricingInfoLabel.apply { verticalAlignment = JLabel.BOTTOM })
        // Add a bottom border to place the text component in the middle.
        val gap = (sparkUsagePanel.preferredSize.height - preferredSize.height) / 2
        border = JBUI.Borders.emptyBottom(JBUI.unscale(gap))
      }
    usagePanel.add(sparkUsagePanel, SPARK_PLAN_KEY)
    usagePanel.add(blazeUsagePanel, BLAZE_PLAN_KEY)
    planCardLayout.show(usagePanel, SPARK_PLAN_KEY)

    add(planPanel)
    add(usagePanel)
    add(informationLabel)
    add(instructionPanel)

    updatePlanInformation(null)
    scope.launch(Dispatchers.Default) {
      cloudProjectManagerFlow.collectLatest { cloudProjectManager ->
        onProjectChanged(cloudProjectManager)
      }
    }
  }

  private suspend fun onProjectChanged(cloudProjectManager: DirectAccessCloudProjectManager?) {
    if (cloudProjectManager == null) {
      updatePlanInformation(null)
      return
    }
    cloudProjectManager.isBillingEnabledFlow.stateFlow.collectLatest { isBillingEnabled ->
      updatePlanInformation(cloudProjectManager.usageQuota, isBillingEnabled)
    }
  }

  private fun updatePlanInformation(quota: Pair<Long, Long>?, isBillingEnabled: Boolean? = null) {
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
      .installOn(planHelpIcon)

    val quotaUsage = quota?.first
    val usedMinutesText = quotaUsage?.toString() ?: "--"

    if (isBillingEnabled == true) {
      blazeUsedMinutesLabel.text = usedMinutesText
      planCardLayout.show(usagePanel, BLAZE_PLAN_KEY)
    } else {
      val quotaLimit = quota?.second
      usageFlow.value =
        quotaUsage?.let { usage -> quotaLimit?.let { limit -> usage.toDouble() / limit } }
      sparkUsedMinutesLabel.text = "$usedMinutesText mins used"
      val remainingMinutes = quotaUsage?.let { usage -> quotaLimit?.let { limit -> limit - usage } }
      val remainingText =
        when {
          remainingMinutes == null -> "--"
          remainingMinutes <= 0 -> "0"
          remainingMinutes < 15 -> "less than 15"
          else -> remainingMinutes.toString()
        }
      sparkRemainingMinutesLabel.text = "$remainingText mins remaining"
      planCardLayout.show(usagePanel, SPARK_PLAN_KEY)
    }
  }
}
