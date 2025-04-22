/*
 * Copyright (C) 2024 The Android Open Source Project
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

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onSiblings
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.tools.adtui.compose.utils.StudioComposeTestRule.Companion.createStudioComposeTestRule
import com.android.tools.idea.adddevicedialog.FormFactors
import com.android.tools.idea.testing.disposable
import com.google.common.truth.Truth.assertThat
import com.google.gct.directaccess.TestUtils.deviceInfoListProvider
import com.google.gct.directaccess.TestUtils.extendedDeviceInfoListProvider
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.gct.directaccess.provisioner.DeviceSelection
import com.google.gct.directaccess.provisioner.OemLabsAssetsRegistry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.replaceService
import java.text.Collator
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(Parameterized::class)
@RunsInEdt
class SelectDeviceDialogTest(private val deviceListProvider: () -> List<DeviceInfo>) {

  companion object {
    @JvmStatic
    @Parameters(name = "{0}")
    fun deviceLists() =
      listOf(arrayOf(extendedDeviceInfoListProvider), arrayOf(deviceInfoListProvider))
  }

  @get:Rule val edtRule = EdtRule()
  @get:Rule val projectRule = ProjectRule()
  @get:Rule val composeTestRule = createStudioComposeTestRule()

  private val project: Project
    get() = projectRule.project

  private lateinit var dialog: AddDirectAccessDeviceDialog
  private lateinit var deviceSelectionListFlow: MutableStateFlow<List<DeviceSelection>>
  private lateinit var phones: List<DeviceInfo>
  private lateinit var watches: List<DeviceInfo>

  @Before
  fun setUp() {
    val mockAssets = mock<OemLabsAssetsRegistry>()
    whenever(mockAssets.retrieveName(any())).thenAnswer { "${it.arguments[0]} Lab" }
    whenever(mockAssets.retrieveIcon(any(), any())).thenReturn(mock())

    ApplicationManager.getApplication()
      .replaceService(OemLabsAssetsRegistry::class.java, mockAssets, projectRule.disposable)

    deviceSelectionListFlow =
      MutableStateFlow(deviceListProvider().map { DeviceSelection(false, it) })
    phones =
      deviceSelectionListFlow.value
        .map { it.deviceInfo }
        .filter { it.formFactor == FormFactors.PHONE }
    watches =
      deviceSelectionListFlow.value
        .map { it.deviceInfo }
        .filter { it.formFactor == FormFactors.WEAR }
    dialog = AddDirectAccessDeviceDialog(project, deviceSelectionListFlow)
    composeTestRule.setContent { dialog.ComposeContent() }
  }

  @After
  fun tearDown() {
    dialog.disposeIfNeeded()
  }

  @Test
  fun tableContents() {
    for (device in phones) {
      val node = composeTestRule.onNodeWithText(device.codename)
      node.assertExists()
      node.assertTextContains(device.name)
      node.assertTextContains(device.api.toString())
    }
    for (device in watches) {
      composeTestRule.onNodeWithText(device.codename).assertDoesNotExist()
    }
    // In the extended device list, there are multiple labs and so the column is visible,
    // otherwise not.
    composeTestRule.onNodeWithText("Lab").apply {
      if (deviceListProvider().distinctBy { it.labId }.size == 1) assertDoesNotExist()
      else assertExists()
    }
  }

  @Test
  fun textSearch() {
    composeTestRule.onNode(hasSetTextAction()).performTextReplacement("Pro")
    for (device in phones) {
      if (device.name.contains("Pro")) {
        composeTestRule.onNodeWithText(device.codename).assertExists()
      } else {
        composeTestRule.onNodeWithText(device.codename).assertDoesNotExist()
      }
    }
  }

  @Test
  fun textSearchByCodename() {
    composeTestRule.onNode(hasSetTextAction()).performTextReplacement("codename5")
    for (device in phones) {
      if (device.codename.contains("codename5")) {
        composeTestRule.onAllNodesWithText(device.codename).assertCountEquals(2)
      } else {
        composeTestRule.onNodeWithText(device.codename).assertDoesNotExist()
      }
    }
  }

  @Test
  fun labFilter() {
    val labIds: List<String> =
      deviceListProvider().map { it.labId }.distinct().sortedWith(Collator.getInstance())

    if (labIds.size > 1) {
      val positions =
        labIds.map {
          composeTestRule
            .onNodeWithText("Device Lab")
            .onSiblings()
            .filterToOne(hasText("$it Lab"))
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        }
      assertThat(positions).isStrictlyOrdered()
    } else {
      composeTestRule.onNodeWithText("Device Lab").assertDoesNotExist()
    }
  }

  @Test
  fun formFactor() {
    composeTestRule
      .onNode(hasText("Phone") and hasAnySibling(hasText("Form Factor")))
      .performClick()
    composeTestRule.onNodeWithText("Wear OS").performClick()
    composeTestRule.waitForIdle()
    for (device in watches) {
      val node = composeTestRule.onNodeWithText(device.api.toString())
      node.assertExists()
      node.assertTextContains(device.name)
    }
    for (device in phones) {
      composeTestRule.onNodeWithText(device.codename).assertDoesNotExist()
    }
  }

  @Test
  fun confirmSelection(): Unit = runBlockingWithTimeout {
    val deviceToSelect = phones[2]
    composeTestRule
      .onNodeWithText(deviceToSelect.codename)
      .onChild()
      .assertIsToggleable()
      .performClick()
    composeTestRule.onNodeWithText("Confirm").performClick()
    composeTestRule.waitForIdle()
    assertThat(
        deviceSelectionListFlow.value.firstOrNull { it.isSelected }?.deviceInfo?.codename ==
          deviceToSelect.codename
      )
      .isTrue()
  }

  @Test
  fun cancelSelection() {
    val deviceToSelect = phones[2]
    composeTestRule
      .onNodeWithText(deviceToSelect.codename)
      .onChild()
      .assertIsToggleable()
      .performClick()
    composeTestRule.onNodeWithText("Cancel").performClick()
    composeTestRule.waitForIdle()
    assertThat(deviceSelectionListFlow.value.none { it.isSelected }).isTrue()
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun keyboard() {
    // Click to select phones[0].
    composeTestRule.onNodeWithText(phones[0].codename).performClick()
    composeTestRule
      .onNodeWithText(phones[0].codename)
      .onChild()
      .assertIsToggleable()
      .assertIsFocused()

    // Arrow down to phone[1].
    composeTestRule.onRoot().performKeyInput { keyPress(Key.DirectionDown) }
    composeTestRule.waitForIdle()
    composeTestRule
      .onNodeWithText(phones[1].codename)
      .onChild()
      .assertIsToggleable()
      .assertIsFocused()

    // Tab to phone[2] and press space to select.
    composeTestRule.onRoot().performKeyInput { keyPress(Key.Tab) }
    composeTestRule.waitForIdle()
    composeTestRule
      .onNodeWithText(phones[2].codename)
      .onChild()
      .assertIsToggleable()
      .assertIsFocused()
    composeTestRule.onRoot().performKeyInput { keyPress(Key.Spacebar) }

    // Up to phone[1].
    composeTestRule.onRoot().performKeyInput { keyPress(Key.DirectionUp) }
    composeTestRule.waitForIdle()
    composeTestRule
      .onNodeWithText(phones[1].codename)
      .onChild()
      .assertIsToggleable()
      .assertIsFocused()

    // Shift tab to phone[0].
    composeTestRule.onRoot().performKeyInput {
      keyDown(Key.ShiftLeft)
      keyDown(Key.Tab)
      keyUp(Key.Tab)
      keyUp(Key.ShiftLeft)
    }
    composeTestRule.waitForIdle()
    composeTestRule
      .onNodeWithText(phones[0].codename)
      .onChild()
      .assertIsToggleable()
      .assertIsFocused()

    // Tab to actions.
    for (index in 1 until phones.size) {
      composeTestRule.onRoot().performKeyInput { keyPress(Key.Tab) }
      composeTestRule.waitForIdle()
      composeTestRule
        .onNodeWithText(phones[index].codename)
        .onChild()
        .assertIsToggleable()
        .assertIsFocused()
    }

    composeTestRule.onRoot().performKeyInput { keyPress(Key.Tab) }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Cancel").assertIsFocused()

    composeTestRule.onRoot().performKeyInput { keyPress(Key.Tab) }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Confirm").assertIsFocused().performClick()

    // Verify selected device.
    assertThat(
        deviceSelectionListFlow.value.firstOrNull { it.isSelected }?.deviceInfo?.codename ==
          phones[2].codename
      )
      .isTrue()
  }
}

private fun KeyInjectionScope.keyPress(key: Key) {
  keyDown(key)
  keyUp(key)
}
