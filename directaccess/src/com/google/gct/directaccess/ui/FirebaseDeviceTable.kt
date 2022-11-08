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

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.ui.table.JBTable
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.table.TableModel
import javax.swing.table.TableRowSorter

class FirebaseDeviceTable(
  model: FirebaseDeviceTableModel,
) : JBTable(model), Disposable {

  companion object {
    private val PROPERTIES_COMPONENT_COLUMN_KEY = "${this::class.java}.COLUMN"
    private val PROPERTIES_COMPONENT_ORDER_KEY = "${this::class.java}.ORDER"
  }

  init {
    setDefaultRenderer(FirebaseItem::class.java, FirebaseItemTableCellRenderer)
    setDefaultRenderer(Boolean::class.java, LaunchOrStopButtonTableCellRenderer)
    setDefaultEditor(Boolean::class.java, LaunchOrStopButtonTableCellEditor)
    rowSorter = newRowSorter(dataModel)
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
            is FirebaseDeviceTemplateItem -> item.template.displayName
            else -> ""
          }
        }
      )
      setComparator(API_MODEL_COLUMN_INDEX, Comparator.naturalOrder<Int>())
      setSortable(ACTIONS_COLUMN_INDEX, false)
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
            RowSorter.SortKey(DEVICE_MODEL_COLUMN_INDEX, SortOrder.ASCENDING),
            RowSorter.SortKey(API_MODEL_COLUMN_INDEX, SortOrder.DESCENDING)
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

  override fun dispose() {}
}
