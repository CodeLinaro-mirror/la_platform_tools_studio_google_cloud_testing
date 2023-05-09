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
import com.android.tools.adtui.swing.findAllDescendants
import com.android.tools.adtui.swing.popup.JBPopupRule
import com.google.common.truth.Truth.assertThat
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.ui.DirectAccessProjectSelector
import com.intellij.ide.ui.customization.CustomActionsSchema
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.DocumentAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

const val SELECT_PROJECT_ID = "SelectProjectAction"

class SelectProjectActionTest {
  private val projectRule = ProjectRule()
  private val popupRule = JBPopupRule()
  @get:Rule val ruleChain = RuleChain.outerRule(projectRule).around(popupRule)!!
  @get:Rule val disposableRule = DisposableRule()

  @RunsInEdt
  @Test
  fun testSelectProjectAction() = runBlocking {
    val service = projectRule.project.service<DirectAccessService>()
    val targetProjectName = "testProject"
    val projectFlow = MutableStateFlow("")
    service.gcpProjectListeners.add { projectFlow.value = service.gcpProject!! }

    assertThat(CustomActionsSchema.getInstance().getCorrectedAction(SELECT_PROJECT_ID))
      .isInstanceOf(SelectProjectAction::class.java)

    val selectProjectAction = SelectProjectAction { FakeDirectAccessProjectSelector() }

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
    Disposer.register(disposableRule.disposable, balloon)
    val textField = balloon.component.findAllDescendants<JTextField>().first()
    textField.text = targetProjectName
    yieldUntil { projectFlow.value == targetProjectName }
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
