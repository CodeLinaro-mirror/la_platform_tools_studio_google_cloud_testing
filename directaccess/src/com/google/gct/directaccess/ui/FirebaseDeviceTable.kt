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

import com.intellij.openapi.Disposable
import com.intellij.ui.table.JBTable
import java.util.function.Function
import javax.swing.table.TableModel
import javax.swing.table.TableRowSorter

class FirebaseDeviceTable(
  model: FirebaseDeviceTableModel,
) : JBTable(model), Disposable {

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
        Comparator.comparing(
          Function<FirebaseItem, String> {
            when (it) {
              is FirebaseDeviceItem -> it.device.name
              is FirebaseDeviceTemplateItem -> it.template.displayName
              else -> ""
            }
          }
        )
      )
      setComparator(API_MODEL_COLUMN_INDEX, Comparator.naturalOrder<Int>().reversed())
    }
  override fun dispose() {}
}
