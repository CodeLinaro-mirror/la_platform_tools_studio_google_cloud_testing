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
import com.intellij.openapi.project.Project
import javax.swing.table.AbstractTableModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

const val DEVICE_MODEL_COLUMN_INDEX = 0
const val API_MODEL_COLUMN_INDEX = 1
const val ACTIONS_COLUMN_INDEX = 2

class FirebaseDeviceTableModel(
  val project: Project,
  private val scope: CoroutineScope,
  uiDispatcher: CoroutineDispatcher,
  parent: Disposable
) : AbstractTableModel() {
  private val itemManager = FirebaseItemManager(project, this, scope, uiDispatcher, parent)

  override fun getRowCount(): Int {
    return itemManager.itemCount
  }

  override fun getColumnCount(): Int {
    return 3
  }

  override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
    return when (columnIndex) {
      DEVICE_MODEL_COLUMN_INDEX -> itemManager.getItem(rowIndex)
      API_MODEL_COLUMN_INDEX -> itemManager.getItem(rowIndex).apiLevel
      ACTIONS_COLUMN_INDEX -> itemManager.getItem(rowIndex).isActive
      else -> ""
    }
  }

  override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
    return columnIndex == ACTIONS_COLUMN_INDEX && itemManager.getItem(rowIndex).isActive
  }

  override fun getColumnName(modelColumnIndex: Int): String {
    return when (modelColumnIndex) {
      DEVICE_MODEL_COLUMN_INDEX -> "Device"
      API_MODEL_COLUMN_INDEX -> "API"
      ACTIONS_COLUMN_INDEX -> "Actions"
      else -> ""
    }
  }

  override fun getColumnClass(columnIndex: Int): Class<*> {
    return when (columnIndex) {
      DEVICE_MODEL_COLUMN_INDEX -> FirebaseItem::class.java
      ACTIONS_COLUMN_INDEX -> Boolean::class.java
      else -> super.getColumnClass(columnIndex)
    }
  }

  override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
    if (columnIndex == ACTIONS_COLUMN_INDEX) {
      val item = itemManager.getItem(rowIndex)
      if (item.isActive && value == false) {
        item.startAction()
      }
    }
  }
}
