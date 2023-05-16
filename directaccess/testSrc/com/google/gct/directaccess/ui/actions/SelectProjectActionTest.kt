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
package com.google.gct.directaccess.ui.actions

import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.testutils.MockitoKt.any
import com.android.testutils.MockitoKt.mock
import com.android.testutils.MockitoKt.whenever
import com.android.tools.adbbridge.Reservation
import com.android.tools.adtui.swing.findAllDescendants
import com.android.tools.adtui.swing.popup.JBPopupRule
import com.android.tools.idea.testing.disposable
import com.google.common.truth.Truth.assertThat
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.ui.DirectAccessProjectSelector
import com.google.services.firebase.directaccess.client.DirectAccessReservationManager
import com.intellij.ide.ui.customization.CustomActionsSchema
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.replaceService
import com.intellij.ui.DocumentAdapter
import java.awt.event.MouseEvent
import java.time.Duration
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn

const val SELECT_PROJECT_ID = "SelectProjectAction"

class SelectProjectActionTest {
  private val projectRule = ProjectRule()
  private val popupRule = JBPopupRule()
  @get:Rule val ruleChain = RuleChain.outerRule(projectRule).around(popupRule)!!

  @RunsInEdt
  @Test
  fun testSelectProjectAction() = runBlocking {
    val service = projectRule.project.service<DirectAccessService>()
    val unsupportedProjectName = "unsupportedTestProject"
    val supportedProjectName = "supportedTestProject"
    val projectFlow = MutableStateFlow("")

    var isProjectSupported = false
    val mockDirectAccessService = mock<DirectAccessService>()
    doAnswer {
        isProjectSupported = it.arguments[0] == supportedProjectName
        projectFlow.value = it.arguments[0] as String
        it.arguments[0]
      }
      .whenever(mockDirectAccessService)
      .gcpProject = any()

    doReturn(
        object : FakeDirectAccessReservationManager() {
          override fun listReservations(): List<Reservation> {
            if (!isProjectSupported) throw RuntimeException("unauthorized")
            return listOf()
          }
        }
      )
      .whenever(mockDirectAccessService)
      .reservationManager
    projectRule.project.replaceService(
      DirectAccessService::class.java,
      mockDirectAccessService,
      projectRule.disposable
    )
    service.gcpProjectListeners.add { projectFlow.value = service.gcpProject!! }
    assertThat(CustomActionsSchema.getInstance().getCorrectedAction(SELECT_PROJECT_ID))
      .isInstanceOf(SelectProjectAction::class.java)

    val selectProjectAction = SelectProjectAction { FakeDirectAccessProjectSelector() }

    // Click the project selection button.
    val mouseEvent = MouseEvent(JPanel(), MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, 1, true, 0)
    val event =
      TestActionEvent.createTestEvent(
        selectProjectAction,
        {
          when (it) {
            CommonDataKeys.PROJECT.name -> projectRule.project
            else -> null
          }
        },
        mouseEvent
      )
    selectProjectAction.actionPerformed(event)
    val balloon = popupRule.fakePopupFactory.getNextBalloon()
    Disposer.register(projectRule.disposable, balloon)

    // Select a project that does not support direct access.
    val textField = balloon.component.findAllDescendants<JTextField>().first()
    textField.text = unsupportedProjectName
    yieldUntil { projectFlow.value == unsupportedProjectName }

    val errorPanel = balloon.component.findAllDescendants<JTextArea>().first()
    yieldUntil { errorPanel.isVisible }
    assertThat(errorPanel.text)
      .isEqualTo(
        "$unsupportedProjectName does not have access to Direct Access. Select a different project."
      )

    // Select a project that supports direct access.
    textField.text = supportedProjectName
    yieldUntil { projectFlow.value == supportedProjectName }
    assertThat(isProjectSupported).isTrue()
    yieldUntil { !errorPanel.isVisible }
  }
}

class FakeDirectAccessProjectSelector : DirectAccessProjectSelector {
  override val component =
    JTextField("").apply {
      document.addDocumentListener(
        object : DocumentAdapter() {
          override fun textChanged(e: DocumentEvent) {
            selectedProject.value = text
          }
        }
      )
    }
  override val selectedProject = MutableStateFlow("")
}

open class FakeDirectAccessReservationManager : DirectAccessReservationManager {
  override fun createReservation(model: String, apiLevel: String): Reservation = notImplemented()
  override fun listReservations(): List<Reservation> = notImplemented()
  override fun cancelReservation(reservationName: String, withGracePeriod: Boolean) =
    notImplemented()
  override fun extendReservation(
    reservationName: String,
    duration: Duration,
    type: DirectAccessReservationManager.ReservationExtendType
  ) = notImplemented()
  override fun fetchReservationFlow(reservationName: String): StateFlow<Reservation> =
    notImplemented()
  override fun maybeRestoreExpireTimeOnReconnect(reservationName: String) = notImplemented()
  private fun notImplemented(): Nothing = error("Not yet implemented")
}
