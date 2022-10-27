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

import com.android.tools.idea.devicemanager.Tables
import com.google.gct.directaccess.provisioner.FirebaseDeviceTemplate
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.UIUtil
import java.awt.Component
import javax.swing.GroupLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.LayoutStyle.ComponentPlacement
import javax.swing.table.TableCellRenderer

/**
 * A TableCellRenderer for [FirebaseDeviceTemplateItem] implemented by simplifying
 * DeviceTableCellRenderer.
 */
class FirebaseTemplateTableCellRenderer : TableCellRenderer {
  private val nameLabel: JLabel
  private val stateLabel: JLabel
  private val line2Label: JLabel
  private val myPanel: JComponent

  init {
    nameLabel = JBLabel()
    stateLabel = JBLabel()
    line2Label = JBLabel()
    myPanel = JBPanel<JBPanel<*>>(null)
    val layout = GroupLayout(myPanel)
    val horizontalGroup: GroupLayout.Group =
      layout
        .createSequentialGroup()
        .addPreferredGap(ComponentPlacement.RELATED)
        .addGroup(
          layout
            .createParallelGroup()
            .addGroup(
              layout
                .createSequentialGroup()
                .addComponent(nameLabel, 0, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(ComponentPlacement.RELATED)
                .addComponent(stateLabel)
            )
            .addComponent(line2Label, 0, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
        )
        .addPreferredGap(
          ComponentPlacement.RELATED,
          GroupLayout.DEFAULT_SIZE,
          Short.MAX_VALUE.toInt()
        )
        .addGap(JBUIScale.scale(4))
    val verticalGroup: GroupLayout.Group =
      layout
        .createParallelGroup(GroupLayout.Alignment.CENTER)
        .addGroup(
          layout
            .createSequentialGroup()
            .addContainerGap(GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE.toInt())
            .addGroup(
              layout
                .createParallelGroup(GroupLayout.Alignment.CENTER)
                .addComponent(nameLabel)
                .addComponent(stateLabel)
            )
            .addComponent(line2Label)
            .addContainerGap(GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE.toInt())
        )
    layout.setHorizontalGroup(horizontalGroup)
    layout.setVerticalGroup(verticalGroup)
    myPanel.setLayout(layout)
  }

  override fun getTableCellRendererComponent(
    table: JTable,
    value: Any,
    selected: Boolean,
    focused: Boolean,
    viewRowIndex: Int,
    viewColumnIndex: Int
  ): Component {
    val template = value as FirebaseDeviceTemplate
    val foreground = Tables.getForeground(table, selected)
    nameLabel.foreground = foreground
    nameLabel.text = template.displayName
    stateLabel.foreground = foreground
    line2Label.font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
    line2Label.foreground = foreground.brighter()
    line2Label.text = template.info.codename
    myPanel.background = Tables.getBackground(table, selected)
    myPanel.border = Tables.getBorder(selected, focused)
    return myPanel
  }
}
