/*
 * Copyright (C) 2025 The Android Open Source Project
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

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.android.tools.adtui.compose.utils.StudioComposeTestRule.Companion.createStudioComposeTestRule
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.idea.concurrency.createCoroutineScope
import com.android.tools.idea.testing.disposable
import com.google.gct.directaccess.CloudProjectEntry
import com.google.gct.directaccess.DirectAccessCloudProjectManager
import com.google.gct.directaccess.DirectAccessService
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.replaceService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.jetbrains.jewel.bridge.LocalComponent
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalJewelApi::class, ExperimentalTestApi::class)
class OemEulaDialogTest {

  val projectRule = ProjectRule()

  val composeRule = createStudioComposeTestRule()

  @get:Rule val chain = RuleChain(projectRule, EdtRule(), HeadlessDialogRule(), composeRule)

  @Test
  @RunsInEdt
  fun testPermissionCheck() = runTest {
    val mockService: DirectAccessService = mock()
    val mockProjectManager: DirectAccessCloudProjectManager = mock()
    whenever(mockService.cloudProjectManager)
      .thenReturn(flowOf(mockProjectManager).stateIn(projectRule.disposable.createCoroutineScope()))
    whenever(mockProjectManager.cloudProject).thenReturn(CloudProjectEntry("myUser", "myProject"))
    projectRule.project.replaceService(
      DirectAccessService::class.java,
      mockService,
      projectRule.disposable,
    )
    val checkLatch = Mutex(true)
    val inCheckLatch = Mutex(true)

    fun createDialog(result: Future<Boolean>) =
      OemEulaContent(listOf("myLab", "myLab2"), projectRule.disposable, projectRule.project) {
        inCheckLatch.unlock()
        checkLatch.lock()
        result.get()
      }

    // Check case with access
    composeRule.setContent {
      CompositionLocalProvider(LocalComponent provides mock()) {
        createDialog(CompletableFuture.completedFuture(true)).ComposeContent()
      }
    }
    composeRule.waitForIdle()
    inCheckLatch.lock()

    composeRule.waitUntilExactlyOneExists(hasText("Checking permissions..."))
    composeRule.onNodeWithContentDescription("Lab inaccessible").assertDoesNotExist()
    composeRule.onNodeWithText("Contact project administrator for access.").assertDoesNotExist()
    checkLatch.unlock()
    composeRule.waitUntilDoesNotExist(hasText("Checking permissions..."))
    composeRule.onNodeWithContentDescription("Lab inaccessible").assertDoesNotExist()
    composeRule.onNodeWithText("Contact project administrator for access.").assertDoesNotExist()

    // Check case without access
    composeRule.setContent {
      CompositionLocalProvider(LocalComponent provides mock()) {
        createDialog(CompletableFuture.completedFuture(false)).ComposeContent()
      }
    }
    composeRule.waitForIdle()
    inCheckLatch.lock()

    composeRule.waitUntilExactlyOneExists(hasText("Checking permissions..."))
    composeRule.onNodeWithContentDescription("Lab inaccessible").assertDoesNotExist()
    composeRule.onNodeWithText("Contact project administrator for access.").assertDoesNotExist()
    checkLatch.unlock()
    composeRule.waitUntilDoesNotExist(hasText("Checking permissions..."))
    composeRule.onNodeWithContentDescription("Lab inaccessible").assertExists()
    composeRule.onNodeWithText("Contact project administrator for access.").assertExists()

    // Check error case
    composeRule.setContent {
      CompositionLocalProvider(LocalComponent provides mock()) {
        createDialog(CompletableFuture.failedFuture(RuntimeException("failed"))).ComposeContent()
      }
    }
    composeRule.waitForIdle()
    inCheckLatch.lock()
    // same as "with access" case
    composeRule.waitUntilExactlyOneExists(hasText("Checking permissions..."))
    composeRule.onNodeWithContentDescription("Lab inaccessible").assertDoesNotExist()
    composeRule.onNodeWithText("Contact project administrator for access.").assertDoesNotExist()
    checkLatch.unlock()
    composeRule.waitUntilDoesNotExist(hasText("Checking permissions..."))
    composeRule.onNodeWithContentDescription("Lab inaccessible").assertDoesNotExist()
    composeRule.onNodeWithText("Contact project administrator for access.").assertDoesNotExist()
  }
}
