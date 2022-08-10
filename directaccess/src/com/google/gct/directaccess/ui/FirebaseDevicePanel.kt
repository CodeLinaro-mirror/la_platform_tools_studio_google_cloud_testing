/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.tools.adtui.stdui.CommonButton
import com.android.tools.idea.devicemanager.DetailsPanel
import com.android.tools.idea.devicemanager.DevicePanel
import com.google.services.firebase.directaccess.client.catalog.FirebaseDirectAccessClient
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBDimension
import javax.swing.GroupLayout
import javax.swing.JButton
import javax.swing.JSeparator
import javax.swing.JTable
import javax.swing.SwingConstants

class FirebaseDevicePanel(project: Project, parent: Disposable) : DevicePanel(project) {
  private val table =
    FirebaseDeviceTable(FirebaseDeviceTableModel(FirebaseDirectAccessClient.availableDevices))

  private val createButton = JButton("Add Device")
  private val separator: JSeparator =
    JSeparator(SwingConstants.VERTICAL).apply {
      preferredSize = JBDimension(3, 20)
      maximumSize = preferredSize
    }
  private val reloadButton = CommonButton(AllIcons.Actions.Refresh)
  private val helpButton = CommonButton(AllIcons.Actions.Help)

  init {
    initTable()
    initScrollPane()
    initDetailsPanelPanel()

    layOut()
    Disposer.register(parent, this)
  }

  override fun newTable(): JTable {
    return table
  }

  override fun newDetailsPanel(): DetailsPanel {
    return FirebaseDeviceDetailsPanel(myProject!!)
  }

  private fun layOut() {
    val layout = GroupLayout(this)
    val horizontalGroup: GroupLayout.Group =
      layout
        .createParallelGroup()
        .addGroup(
          layout
            .createSequentialGroup()
            .addGap(JBUIScale.scale(5))
            .addComponent(createButton)
            .addGap(JBUIScale.scale(4))
            .addComponent(separator)
            .addComponent(reloadButton)
            .addComponent(helpButton)
        )
        .addComponent(myDetailsPanelPanel)
    val verticalGroup: GroupLayout.Group =
      layout
        .createSequentialGroup()
        .addGroup(
          layout
            .createParallelGroup(GroupLayout.Alignment.CENTER)
            .addComponent(createButton)
            .addComponent(separator)
            .addComponent(reloadButton)
            .addComponent(helpButton)
        )
        .addComponent(myDetailsPanelPanel)
    layout.setHorizontalGroup(horizontalGroup)
    layout.setVerticalGroup(verticalGroup)
    setLayout(layout)
  }
}
