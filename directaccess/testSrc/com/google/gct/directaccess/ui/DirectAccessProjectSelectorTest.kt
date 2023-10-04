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
import com.google.common.truth.Truth.assertThat
import com.google.services.firebase.FirebaseProjectClientRule
import com.intellij.testFramework.ApplicationRule
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DirectAccessProjectSelectorTest {

  @get:Rule val applicationRule = ApplicationRule()
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
    selector = DirectAccessProjectSelectorImpl(projectList.last(), true, scope)

    assertThat(selector.model.size).isEqualTo(1)
    assertThat(selector.model.selectedItem).isEqualTo("Loading...")
  }

  @Test
  fun testProjectsLoadedInSelector() = runBlockingWithTimeout {
    selector = DirectAccessProjectSelectorImpl(projectList.last(), true, scope)

    yieldUntil { selector.model.size != 1 }

    assertThat(selector.model.size).isEqualTo(projectList.size)
    yieldUntil { selector.model.selectedItem == projectList.last() }
  }

  @Test
  fun testSelectedItemDefaultWhenPreferredProjectNotInList() = runBlockingWithTimeout {
    selector = DirectAccessProjectSelectorImpl("nonExistentProject", true, scope)

    yieldUntil { selector.model.size != 1 }

    assertThat(selector.model.size).isEqualTo(projectList.size)
    yieldUntil { selector.model.selectedItem == projectList[0] }
  }

  @Test
  fun testErrorWhileFetchingFirebaseProjectDisablesSelector() = runBlockingWithTimeout {
    projectList = firebaseProjectClientRule.setupFirebaseClient(true).toMutableList()
    selector = DirectAccessProjectSelectorImpl("preferredProject", true, scope)

    yieldUntil { scope.coroutineContext.job.children.toList().isEmpty() }

    assertThat(selector.model.size).isEqualTo(1)
    assertThat(selector.model.selectedItem).isEqualTo("Error fetching firebase projects")
    assertThat(selector.isEnabled).isFalse()
  }
}
