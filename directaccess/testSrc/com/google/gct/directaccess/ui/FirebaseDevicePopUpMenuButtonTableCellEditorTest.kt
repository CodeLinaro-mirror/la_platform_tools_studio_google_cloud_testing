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

import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.testutils.MockitoKt.mock
import com.android.testutils.MockitoKt.whenever
import com.android.tools.adbbridge.Reservation
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.idea.concurrency.AndroidDispatchers
import com.android.tools.idea.concurrency.createChildScope
import com.android.tools.idea.devicemanager.DeviceType
import com.android.tools.idea.protobuf.Timestamp
import com.google.common.truth.Truth.assertThat
import com.google.gct.directaccess.FirebaseDevice
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.gct.directaccess.provisioner.DirectAccessDeviceHandle
import com.google.services.firebase.directaccess.client.DirectAccessConnection
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue
import com.intellij.testFramework.ProjectRule
import com.intellij.ui.JBColor
import java.awt.Dimension
import java.time.Duration
import javax.swing.JButton
import javax.swing.JRootPane
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class FirebaseDevicePopUpMenuButtonTableCellEditorTest {
  @get:Rule val projectRule = ProjectRule()

  @Test
  fun testActions() = runBlockingWithTimeout {
    try {
      val reservation =
        Reservation.newBuilder().setExpireTime(Timestamp.getDefaultInstance()).build()
      val remoteState = DirectAccessConnection.RemoteState(mock(), reservation)
      val deviceState = DeviceState.Connected(mock(), mock())
      val device =
        FirebaseDevice(
          DeviceInfo("myBrand", "myName", "myManufacturer", "myCodename", 31, DeviceType.PHONE),
          remoteState,
          deviceState
        )
      val connection: DirectAccessConnection = mock()
      var extend30Called = false
      var extend60Called = false
      var forceCheckinCalled = false
      whenever(connection.state).thenReturn(MutableStateFlow(remoteState))
      whenever(connection.extendReservation(Duration.ofMinutes(30))).then {
        extend30Called = true
        null
      }
      whenever(connection.extendReservation(Duration.ofMinutes(60))).then {
        extend60Called = true
        null
      }
      whenever(connection.endReservation()).then {
        forceCheckinCalled = true
        null
      }

      val child = createChildScope()
      val handle = DirectAccessDeviceHandle(projectRule.project, child, deviceState, connection)
      val item = FirebaseDeviceItem(mock(), device, handle, child, AndroidDispatchers.uiThread) {}

      val table: FirebaseDeviceTable = mock()
      whenever(table.getItemAt(2)).thenReturn(item)
      whenever(table.background).thenReturn(JBColor.BLACK)
      whenever(table.selectionBackground).thenReturn(JBColor.BLACK)
      whenever(table.selectionForeground).thenReturn(JBColor.BLACK)

      val component =
        FirebaseDevicePopUpMenuButtonTableCellEditor.getTableCellEditorComponent(
          table,
          Unit,
          false,
          2,
          2
        ) as JButton
      val fakeUi =
        FakeUi(
          JRootPane().apply {
            size = Dimension(100, 100)
            add(component)
          },
          createFakeWindow = true
        )

      fakeUi.clickOn(component)
      val popup: JBPopupMenu = fakeUi.findComponent()!!
      val (extend30, extend60, forceCheckIn) = popup.subElements.filterIsInstance<JBMenuItem>()

      assertThat(extend30.text).isEqualTo("Extend session by 30 minutes")
      extend30.doClick()
      yieldUntil { extend30Called }

      assertThat(extend60.text).isEqualTo("Extend session by 60 minutes")
      extend60.doClick()
      yieldUntil { extend60Called }

      assertThat(forceCheckIn.text).isEqualTo("Force check-in device")
      try {
        forceCheckIn.doClick()
      } catch (expected: Exception) {
        assertThat(expected.message)
          .isEqualTo("Check in the device immediately?\nAll data will be wiped.")
      }
      TestDialogManager.setTestDialog(TestDialog.YES)
      forceCheckIn.doClick()
      yieldUntil { forceCheckinCalled }

      child.cancel()
      ApplicationManager.getApplication().invokeAndWait { dispatchAllEventsInIdeEventQueue() }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }
}
