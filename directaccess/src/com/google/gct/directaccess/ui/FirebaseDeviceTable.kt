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

import com.android.tools.idea.devicemanager.ActivateDeviceFileExplorerWindowValue
import com.android.tools.idea.devicemanager.DeviceType
import com.android.tools.idea.devicemanager.IconButtonTableCellRenderer
import com.android.tools.idea.devicemanager.MergedTableColumn
import com.android.tools.idea.devicemanager.PopUpMenuValue
import com.android.tools.idea.devicemanager.Tables
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.scale.JBUIScale.scale
import com.intellij.ui.table.JBTable
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.table.DefaultTableColumnModel
import javax.swing.table.JTableHeader
import javax.swing.table.TableModel
import javax.swing.table.TableRowSorter

class FirebaseDeviceTable(
  project: Project,
  model: FirebaseDeviceTableModel,
) : JBTable(model), Disposable {

  companion object {
    private val PROPERTIES_COMPONENT_COLUMN_KEY = "${this::class.java}.COLUMN"
    private val PROPERTIES_COMPONENT_ORDER_KEY = "${this::class.java}.ORDER"
  }

  init {
    setDefaultRenderer(DeviceType::class.java, FirebaseDeviceIconButtonTableCellRenderer)
    setDefaultRenderer(FirebaseItem::class.java, FirebaseItemTableCellRenderer)
    setDefaultRenderer(Boolean::class.java, LaunchOrStopButtonTableCellRenderer)
    setDefaultEditor(Boolean::class.java, LaunchOrStopButtonTableCellEditor)
    setDefaultRenderer(
      ActivateDeviceFileExplorerWindowValue::class.java,
      FirebaseDeviceActionsColumnIconRenderer()
    )
    setDefaultEditor(
      ActivateDeviceFileExplorerWindowValue::class.java,
      ActivateDeviceFileExplorerWindowButtonTableCellEditor(project)
    )
    setDefaultRenderer(PopUpMenuValue::class.java, FirebaseDeviceActionsColumnIconRenderer())
    setDefaultEditor(PopUpMenuValue::class.java, FirebaseDevicePopUpMenuButtonTableCellEditor)

    rowSorter = newRowSorter(dataModel)
    setShowGrid(false)
  }

  fun getItemAt(viewRowIndex: Int): FirebaseItem {
    val columnIndex = convertColumnIndexToView(DEVICE_MODEL_COLUMN_INDEX)
    return getValueAt(viewRowIndex, (columnIndex)) as FirebaseItem
  }

  private fun newRowSorter(tableModel: TableModel) =
    TableRowSorter(tableModel).apply {
      setComparator(
        DEVICE_MODEL_COLUMN_INDEX,
        Comparator.comparing { item: FirebaseItem ->
          when (item) {
            is FirebaseDeviceItem -> item.device.name
            is FirebaseDeviceTemplateItem -> item.template.properties.title
            else -> ""
          }
        }
      )
      setComparator(API_MODEL_COLUMN_INDEX, Comparator.naturalOrder<Int>())
      setSortable(DEVICE_ICON_COLUMN_INDEX, false)
      setSortable(LAUNCH_OR_STOP_MODEL_COLUMN_INDEX, false)
      setSortable(ACTIVATE_DEVICE_FILE_EXPLORER_WINDOW_MODEL_COLUMN_INDEX, false)
      setSortable(POP_UP_MENU_MODEL_COLUMN_INDEX, false)
      val columnList = PropertiesComponent.getInstance().getList(PROPERTIES_COMPONENT_COLUMN_KEY)
      val orderList = PropertiesComponent.getInstance().getList(PROPERTIES_COMPONENT_ORDER_KEY)
      sortKeys =
        if (columnList?.isNotEmpty() == true && orderList?.isNotEmpty() == true) {
          columnList.zip(orderList).map {
            RowSorter.SortKey(it.first.toInt(), SortOrder.valueOf(it.second))
          }
        } else {
          // Default sort order
          listOf(
            RowSorter.SortKey(DEVICE_ICON_COLUMN_INDEX, SortOrder.UNSORTED),
            RowSorter.SortKey(DEVICE_MODEL_COLUMN_INDEX, SortOrder.ASCENDING),
            RowSorter.SortKey(API_MODEL_COLUMN_INDEX, SortOrder.DESCENDING),
          )
        }
      addRowSorterListener {
        if (sortKeys.isNotEmpty()) {
          PropertiesComponent.getInstance()
            .setList(PROPERTIES_COMPONENT_COLUMN_KEY, sortKeys.map { it.column.toString() })
          PropertiesComponent.getInstance()
            .setList(PROPERTIES_COMPONENT_ORDER_KEY, sortKeys.map { it.sortOrder.toString() })
        }
      }
    }

  override fun doLayout() {
    Tables.setWidths(
      columnModel.getColumn(getDeviceIconColumnIndex()),
      IconButtonTableCellRenderer.getPreferredWidth(this, DeviceType::class.java)
    )
    columnModel.getColumn(getDeviceModelColumnIndex()).minWidth = scale(200)

    Tables.setWidths(
      columnModel.getColumn(getApiColumnIndex()),
      Tables.getPreferredColumnWidth(this, getApiColumnIndex(), scale(65)),
      scale(20)
    )
    Tables.setWidths(
      columnModel.getColumn(getLaunchOrStopColumnIndex()),
      IconButtonTableCellRenderer.getPreferredWidth(this, Boolean::class.java)
    )

    Tables.setWidths(
      columnModel.getColumn(getActivateDeviceFileExplorerWindowViewColumnIndex()),
      IconButtonTableCellRenderer.getPreferredWidth(
        this,
        ActivateDeviceFileExplorerWindowValue::class.java
      )
    )

    Tables.setWidths(
      columnModel.getColumn(getPopUpMenuModelColumnIndex()),
      IconButtonTableCellRenderer.getPreferredWidth(this, PopUpMenuValue::class.java)
    )
    super.doLayout()
  }

  override fun createDefaultTableHeader(): JTableHeader {
    val tableColumnModel = DefaultTableColumnModel()

    tableColumnModel.addColumn(columnModel.getColumn(getDeviceIconColumnIndex()))
    tableColumnModel.addColumn(columnModel.getColumn(getDeviceModelColumnIndex()))
    tableColumnModel.addColumn(columnModel.getColumn(getApiColumnIndex()))

    val columns =
      listOf(
        columnModel.getColumn(getLaunchOrStopColumnIndex()),
        columnModel.getColumn(getActivateDeviceFileExplorerWindowViewColumnIndex()),
        columnModel.getColumn(getPopUpMenuModelColumnIndex())
      )

    val actionsColumn = MergedTableColumn(columns)
    actionsColumn.headerValue = "Actions"

    tableColumnModel.addColumn(actionsColumn)

    val header = super.createDefaultTableHeader()
    header.columnModel = tableColumnModel
    header.reorderingAllowed = false
    header.resizingAllowed = false
    return header
  }

  private fun getDeviceIconColumnIndex() = convertColumnIndexToView(DEVICE_ICON_COLUMN_INDEX)
  private fun getDeviceModelColumnIndex() = convertColumnIndexToView(DEVICE_MODEL_COLUMN_INDEX)
  private fun getApiColumnIndex() = convertColumnIndexToView(API_MODEL_COLUMN_INDEX)
  private fun getLaunchOrStopColumnIndex() =
    convertColumnIndexToView(LAUNCH_OR_STOP_MODEL_COLUMN_INDEX)
  private fun getActivateDeviceFileExplorerWindowViewColumnIndex() =
    convertColumnIndexToView(ACTIVATE_DEVICE_FILE_EXPLORER_WINDOW_MODEL_COLUMN_INDEX)
  private fun getPopUpMenuModelColumnIndex() =
    convertColumnIndexToView(POP_UP_MENU_MODEL_COLUMN_INDEX)
  override fun dispose() {}
}
