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

import com.android.tools.idea.devicemanager.IconButtonTableCellEditor
import com.android.tools.idea.devicemanager.PopUpMenuValue
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.ui.Messages
import com.intellij.ui.PopupMenuListenerAdapter
import java.awt.Component
import java.awt.event.ActionEvent
import java.time.Duration
import javax.swing.JTable
import javax.swing.event.PopupMenuEvent
import kotlinx.coroutines.launch

object FirebaseDevicePopUpMenuButtonTableCellEditor :
  IconButtonTableCellEditor(PopUpMenuValue.INSTANCE, AllIcons.Actions.More) {

  private var firebaseDeviceItem: FirebaseDeviceItem? = null
  private val menu =
    JBPopupMenu()
      .apply {
        add(getExtendReservationItem(Duration.ofMinutes(30)))
        add(getExtendReservationItem(Duration.ofMinutes(60)))
        add(
          getMenuItem("Force check-in device") {
            firebaseDeviceItem?.let { deviceItem ->
              if (
                Messages.showYesNoDialog(
                  null,
                  "Check in the device immediately?\nAll data will be wiped.",
                  "Confirm Check-In",
                  null
                ) == Messages.YES
              ) {
                deviceItem.scope.launch { deviceItem.handle.connection.endReservation() }
              }
            }
          }
        )

        addPopupMenuListener(
          object : PopupMenuListenerAdapter() {
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {
              fireEditingCanceled()
            }
          }
        )
      }
      .also {
        // Add the menu into the window somewhere. This is only needed so that fakeUi can find it in
        // tests.
        myButton.add(it)
      }

  init {
    myButton.addActionListener { menu.show(myButton, 0, myButton.height) }
  }

  private fun getExtendReservationItem(duration: Duration) =
    getMenuItem("Extend session by ${duration.toMinutes()} minutes") {
      firebaseDeviceItem?.scope?.launch {
        firebaseDeviceItem?.handle?.connection?.extendReservation(duration)
      }
    }

  private fun getMenuItem(text: String, actionListener: (ActionEvent) -> Unit) =
    JBMenuItem(text).apply { addActionListener { actionListener(it) } }

  override fun getTableCellEditorComponent(
    table: JTable,
    value: Any,
    selected: Boolean,
    viewRowIndex: Int,
    viewColumnIndex: Int
  ): Component {
    super.getTableCellEditorComponent(table, value, selected, viewRowIndex, viewColumnIndex)
    val item = (table as FirebaseDeviceTable).getItemAt(viewRowIndex)
    firebaseDeviceItem = item as? FirebaseDeviceItem
    return myButton
  }
}
