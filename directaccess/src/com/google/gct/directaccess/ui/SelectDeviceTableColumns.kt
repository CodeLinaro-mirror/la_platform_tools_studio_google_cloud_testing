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

import com.android.tools.adtui.categorytable.Attribute
import com.android.tools.adtui.categorytable.Attribute.Companion.stringAttribute
import com.android.tools.adtui.categorytable.Column
import com.android.tools.adtui.categorytable.LabelColumn
import com.android.tools.adtui.common.ColoredIconGenerator
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.gct.directaccess.provisioner.icon
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale

data class SelectDeviceRowData(
  val isEnabled: Boolean,
  var isSelected: Boolean,
  val deviceInfo: DeviceInfo,
)

internal object SelectDeviceTableColumns {

  object Selected : Column<SelectDeviceRowData, Boolean, JBCheckBox> {
    override val name = ""
    override val widthConstraint =
      Column.SizeConstraint(min = JBUIScale.scale(20), preferred = JBUIScale.scale(20))
    override val attribute =
      object : Attribute<SelectDeviceRowData, Boolean> {
        override val sorter = Comparator.naturalOrder<Boolean>()
        override val isGroupable = false

        override fun value(t: SelectDeviceRowData) = t.isSelected
      }

    override fun createUi(rowValue: SelectDeviceRowData) =
      JBCheckBox().apply {
        isSelected = rowValue.isSelected
        addItemListener { rowValue.isSelected = isSelected }
      }

    override fun updateValue(rowValue: SelectDeviceRowData, component: JBCheckBox, value: Boolean) =
      Unit
  }

  object DeviceIcon : Column<SelectDeviceRowData, String, JBLabel> {
    override val name = ""
    override val widthConstraint =
      Column.SizeConstraint(min = JBUIScale.scale(20), preferred = JBUIScale.scale(20))
    override val attribute = stringAttribute<SelectDeviceRowData> { it.deviceInfo.type.toString() }

    override fun createUi(rowValue: SelectDeviceRowData): JBLabel {
      val baseIcon = rowValue.deviceInfo.icon
      val icon =
        if (!rowValue.isEnabled) ColoredIconGenerator.generateDeEmphasizedIcon(baseIcon)
        else baseIcon
      return JBLabel(icon)
    }

    override fun updateValue(rowValue: SelectDeviceRowData, component: JBLabel, value: String) =
      Unit
  }

  object Manufacturer :
    LabelColumn<SelectDeviceRowData>(
      "Manufacturer",
      Column.SizeConstraint(min = 100, preferred = 200),
      stringAttribute { it.deviceInfo.manufacturer },
    )

  object Name :
    LabelColumn<SelectDeviceRowData>(
      "Name",
      Column.SizeConstraint(min = 150, preferred = 300),
      stringAttribute { it.deviceInfo.name },
    )

  object Api :
    LabelColumn<SelectDeviceRowData>(
      "API",
      Column.SizeConstraint(min = 20, max = 65),
      stringAttribute { it.deviceInfo.api.toString() },
    )

  object Width :
    LabelColumn<SelectDeviceRowData>(
      "Width",
      Column.SizeConstraint(min = 40, max = 85),
      stringAttribute { it.deviceInfo.screenX.toString() },
    )

  object Height :
    LabelColumn<SelectDeviceRowData>(
      "Height",
      Column.SizeConstraint(min = 40, max = 85),
      stringAttribute { it.deviceInfo.screenY.toString() },
    )

  object Dpi :
    LabelColumn<SelectDeviceRowData>(
      "dpi",
      Column.SizeConstraint(min = 30, max = 85),
      stringAttribute { it.deviceInfo.screenDensity.toString() },
    )

  val columns = listOf(Selected, DeviceIcon, Manufacturer, Name, Api, Width, Height, Dpi)
}
