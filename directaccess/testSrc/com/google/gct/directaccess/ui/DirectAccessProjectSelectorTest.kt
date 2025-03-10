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
import com.android.testutils.delayUntilCondition
import com.google.common.truth.Truth.assertThat
import com.google.services.firebase.FirebaseProjectClientRule
import com.intellij.testFramework.ProjectRule
import com.intellij.util.ui.NamedColorUtil
import java.awt.Color
import javax.swing.JTextField
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DirectAccessProjectSelectorTest {

  @get:Rule val projectRule = ProjectRule()
  @get:Rule val firebaseProjectClientRule = FirebaseProjectClientRule()
  private val scope = CoroutineScope(EmptyCoroutineContext)
  private lateinit var selector: DirectAccessProjectSelectorImpl
  private lateinit var projectList: MutableList<String>

  @Before
  fun setup() {
    projectList = firebaseProjectClientRule.setupFirebaseClient().toMutableList()
  }

  @Test
  fun testLoadingShownWhenLoadingFirebaseProjects() {
    // Pass a cancelled scope so that projects don't refresh allowing
    // the test to assert preferred project is displayed
    scope.cancel()
    selector = DirectAccessProjectSelectorImpl(projectRule.project, projectList.last(), true, scope)

    assertThat(selector.comboBox.model.size).isEqualTo(1)
    assertThat(selector.comboBox.model.selectedItem).isEqualTo("Loading...")
    assertThat(selector.comboBox.isEnabled).isFalse()
    selector.assertDisabledTextColor(NamedColorUtil.getInactiveTextColor())
  }

  @Test
  fun testProjectsLoadedInSelector() = runBlockingWithTimeout {
    selector = DirectAccessProjectSelectorImpl(projectRule.project, projectList.last(), true, scope)

    yieldUntil { selector.comboBox.model.size != 1 }

    assertThat(selector.comboBox.model.size).isEqualTo(projectList.size)
    yieldUntil { selector.comboBox.model.selectedItem == projectList.last() }
    assertThat(selector.components[0]).isEqualTo(selector.comboBox)
  }

  @Test
  fun testSelectedItemDefaultWhenPreferredProjectNotInList() = runBlockingWithTimeout {
    selector =
      DirectAccessProjectSelectorImpl(projectRule.project, "nonExistentProject", true, scope)

    yieldUntil { selector.comboBox.model.size != 1 }

    assertThat(selector.comboBox.model.size).isEqualTo(projectList.size)
    yieldUntil { selector.comboBox.model.selectedItem == projectList[0] }
    assertThat(selector.components[0]).isEqualTo(selector.comboBox)
  }

  @Test
  fun testErrorWhileFetchingFirebaseProjectDisablesSelector() = runBlockingWithTimeout {
    projectList = firebaseProjectClientRule.setupFirebaseClient(true).toMutableList()
    selector = DirectAccessProjectSelectorImpl(projectRule.project, "preferredProject", true, scope)

    yieldUntil { scope.coroutineContext.job.children.toList().isEmpty() }

    assertThat(selector.comboBox.isVisible).isTrue()
    assertThat(selector.comboBox.model.size).isEqualTo(1)
    assertThat(selector.comboBox.model.selectedItem).isEqualTo("Error fetching firebase projects")
    assertThat(selector.comboBox.isEnabled).isFalse()
    selector.assertDisabledTextColor(NamedColorUtil.getErrorForeground())
  }

  @Test
  fun testEmptyProjectListShowsLink() = runBlockingWithTimeout {
    projectList = firebaseProjectClientRule.setupFirebaseClient(numProjects = 0).toMutableList()
    selector = DirectAccessProjectSelectorImpl(projectRule.project, "preferredProject", true, scope)

    yieldUntil { /*scope.coroutineContext.job.children.toList().isEmpty()*/
      selector.createProjectHyperlink.isVisible
    }

    assertThat(selector.createProjectHyperlink.isVisible).isTrue()
    assertThat(selector.comboBox.isVisible).isFalse()
  }

  @Test
  fun testSelectorDisabledIfShouldEnableIsFalse() = runBlockingWithTimeout {
    selector =
      DirectAccessProjectSelectorImpl(projectRule.project, projectList.last(), false, scope)

    yieldUntil { selector.comboBox.model.size != 1 }

    assertThat(selector.comboBox.model.size).isEqualTo(projectList.size)
    yieldUntil { selector.comboBox.model.selectedItem == projectList.last() }
    selector.assertDisabledTextColor(NamedColorUtil.getInactiveTextColor())
  }

  @Test
  fun testComboBoxGetsEnabled() = runBlockingWithTimeout {
    selector = DirectAccessProjectSelectorImpl(projectRule.project, "preferredProject", true, scope)
    delayUntilCondition(1000L) { selector.comboBox.isEnabled }
    assertThat(selector.comboBox.isEnabled).isTrue()
  }

  @Test
  fun testFirstProjectEmptyWhenPreferredProjectNotSet() = runBlockingWithTimeout {
    projectList = firebaseProjectClientRule.setupFirebaseClient().toMutableList()
    selector = DirectAccessProjectSelectorImpl(projectRule.project, "", true, scope)

    yieldUntil { selector.comboBox.model.size != 1 }

    assertThat(selector.comboBox.model.size).isEqualTo(projectList.size + 1)
    yieldUntil { selector.comboBox.model.selectedItem == "" }
  }
}

private fun DirectAccessProjectSelectorImpl.assertDisabledTextColor(color: Color) {
  val textField = comboBox.editor.editorComponent as JTextField
  assertThat(textField.disabledTextColor).isEqualTo(color)
}
