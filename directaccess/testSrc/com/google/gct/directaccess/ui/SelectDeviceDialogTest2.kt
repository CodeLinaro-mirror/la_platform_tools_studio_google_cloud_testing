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

import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.tools.adtui.compose.utils.StudioComposeTestRule.Companion.createStudioComposeTestRule
import com.android.tools.idea.adddevicedialog.FormFactors
import com.google.common.truth.Truth.assertThat
import com.google.gct.directaccess.TestUtils.extendedDeviceInfoListProvider
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.gct.directaccess.provisioner.DeviceSelection
import com.intellij.openapi.project.Project
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@RunsInEdt
class SelectDeviceDialogTest2 {
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
    deviceSelectionListFlow =
      MutableStateFlow(extendedDeviceInfoListProvider().map { DeviceSelection(false, it) })
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
}
