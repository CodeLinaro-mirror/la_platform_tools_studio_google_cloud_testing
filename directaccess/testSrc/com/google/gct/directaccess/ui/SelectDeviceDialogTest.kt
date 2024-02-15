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

import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.testutils.MockitoKt.mock
import com.android.testutils.MockitoKt.whenever
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.createModalDialogAndInteractWithIt
import com.android.tools.adtui.swing.enableHeadlessDialogs
import com.android.tools.adtui.swing.findAllDescendants
import com.android.tools.idea.testing.disposable
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import com.google.gct.directaccess.DirectAccessCloudProjectManager
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.RefreshableStateFlow
import com.google.gct.directaccess.TestUtils.extendedDeviceInfoListProvider
import com.google.gct.directaccess.provisioner.DeviceSelection
import com.google.gct.directaccess.ui.SelectDeviceDialog
import com.google.gct.login2.GoogleLoginService
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.replaceService
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.SearchTextField
import com.intellij.util.application
import javax.swing.JCheckBox
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doAnswer

@RunsInEdt
class SelectDeviceDialogTest {

  @get:Rule val projectRule = ProjectRule()
  @get:Rule val edtRule = EdtRule()

  private val scope = CoroutineScope(MoreExecutors.directExecutor().asCoroutineDispatcher())
  private val project: Project
    get() = projectRule.project

  private lateinit var deviceSelectionListFlow: MutableStateFlow<List<DeviceSelection>>

  @Before
  fun setUp() = runBlockingWithTimeout {
    enableHeadlessDialogs(projectRule.disposable)
    val mockDirectAccessService = mock<DirectAccessService>()
    val mockCloudProjectManager = mock<DirectAccessCloudProjectManager>()
    val cloudProjectManagerFlow =
      MutableStateFlow<DirectAccessCloudProjectManager?>(mockCloudProjectManager)
    val accessibleDeviceInfoFlow =
      RefreshableStateFlow(scope, Long.MAX_VALUE) { extendedDeviceInfoListProvider() }
    deviceSelectionListFlow =
      MutableStateFlow(extendedDeviceInfoListProvider().map { DeviceSelection(true, it) })
    doAnswer { accessibleDeviceInfoFlow }
      .whenever(mockCloudProjectManager)
      .accessibleDeviceInfoListFlow
    doAnswer { cloudProjectManagerFlow }.whenever(mockDirectAccessService).cloudProjectManager
    doAnswer { deviceSelectionListFlow }.whenever(mockDirectAccessService).deviceSelectionListFlow
    doAnswer { scope }.whenever(mockDirectAccessService).scope
    project.replaceService(
      DirectAccessService::class.java,
      mockDirectAccessService,
      projectRule.disposable,
    )

    val mockGoogleLoginService = mock<GoogleLoginService>()
    doAnswer { "test@gmail.com" }.whenever(mockGoogleLoginService).getEmail()
    application.replaceService(
      GoogleLoginService::class.java,
      mockGoogleLoginService,
      projectRule.disposable,
    )
  }

  @After
  fun tearDown() {
    scope.cancel()
  }

  @Test
  fun testSelectDeviceDialogSearchTest() = runBlockingWithTimeout {
    val dialog = SelectDeviceDialog(project)
    createModalDialogAndInteractWithIt({ dialog.show() }) {
      assertThat(dialog.deviceTable.componentCount).isEqualTo(6)
      val searchTextField = dialog.contentPanel.findAllDescendants<SearchTextField>().first()
      // Case-insensitive search
      searchTextField.text = "GoOgLe       WaTcH"
      assertThat(dialog.deviceTable.componentCount).isEqualTo(2)
      assertThat(dialog.deviceTable.values[0].deviceInfo.name).isEqualTo("Pixel Watch")
      assertThat(dialog.deviceTable.values[0].deviceInfo.api).isEqualTo(33)
      assertThat(dialog.deviceTable.values[1].deviceInfo.name).isEqualTo("Pixel Watch")
      assertThat(dialog.deviceTable.values[1].deviceInfo.api).isEqualTo(34)

      // Search for devices with 6 in their name
      searchTextField.text = "6"
      assertThat(dialog.deviceTable.componentCount).isEqualTo(2)
      assertThat(dialog.deviceTable.values[0].deviceInfo.name).isEqualTo("Pixel 6")
      assertThat(dialog.deviceTable.values[1].deviceInfo.name).isEqualTo("Pixel 6 Pro")

      // Search for api 33
      searchTextField.text = "33"
      assertThat(dialog.deviceTable.componentCount).isEqualTo(3)
      assertThat(dialog.deviceTable.values[0].deviceInfo.name).isEqualTo("Pixel 6 Pro")
      assertThat(dialog.deviceTable.values[1].deviceInfo.name).isEqualTo("Pixel Watch")
      assertThat(dialog.deviceTable.values[2].deviceInfo.name).isEqualTo("SomeName")

      // Search matching no device
      searchTextField.text = "no match search"
      assertThat(dialog.deviceTable.componentCount).isEqualTo(0)
    }
  }

  @Test
  fun testSelectDeviceDialogWhenNoAvailableDevicesToSelect() = runBlockingWithTimeout {
    (project.service<DirectAccessService>().cloudProjectManager as MutableStateFlow).value = null
    project.service<DirectAccessService>().deviceSelectionListFlow.value = emptyList()

    val dialog = SelectDeviceDialog(project)
    createModalDialogAndInteractWithIt({ dialog.show() }) {
      assertThat(dialog.deviceTable.componentCount).isEqualTo(0)
    }
  }

  @Test
  fun testCheckUncheckWhenDevicesFilteredBySearch() = runBlockingWithTimeout {
    val dialog = SelectDeviceDialog(project)
    createModalDialogAndInteractWithIt({ dialog.show() }) {
      assertThat(dialog.deviceTable.componentCount).isEqualTo(6)
      val searchTextField = dialog.contentPanel.findAllDescendants<SearchTextField>().first()
      searchTextField.text = "Google"
      assertThat(dialog.deviceTable.componentCount).isEqualTo(5)
      val lastCheckBox = dialog.deviceTable.findAllDescendants<JCheckBox>().first()
      lastCheckBox.doClick()
      assertThat(lastCheckBox.isSelected).isFalse()
      dialog.clickDefaultButton()
    }

    yieldUntil { deviceSelectionListFlow.value.filter { it.isSelected }.size == 5 }
    val selectedDevices =
      deviceSelectionListFlow.value.filter { it.isSelected }.map { it.deviceInfo.name }
    assertThat(selectedDevices).doesNotContain("Pixel 5")
    assertThat(selectedDevices)
      .containsExactly("Pixel 6", "Pixel 6 Pro", "Pixel Watch", "Pixel Watch", "SomeName")
      .inOrder()
  }

  @Test
  fun testCheckBoxRetainStateAfterSearch() = runBlockingWithTimeout {
    val dialog = SelectDeviceDialog(project)
    createModalDialogAndInteractWithIt({ dialog.show() }) {
      assertThat(dialog.deviceTable.componentCount).isEqualTo(6)
      val checkBoxes = dialog.deviceTable.findAllDescendants<JCheckBox>().toList()
      checkBoxes.forEach { assertThat(it.isSelected).isTrue() }
      val searchTextField = dialog.contentPanel.findAllDescendants<SearchTextField>().first()
      searchTextField.text = "SomeName"
      assertThat(dialog.deviceTable.componentCount).isEqualTo(1)
      val searchCheckBoxes = dialog.deviceTable.findAllDescendants<JCheckBox>().toList()
      assertThat(searchCheckBoxes.size).isEqualTo(1)
      assertThat(searchCheckBoxes[0].isSelected).isTrue()
      searchTextField.text = ""
      val checkBoxList = dialog.deviceTable.findAllDescendants<JCheckBox>().toList()
      checkBoxList.forEach { assertThat(it.isSelected).isTrue() }
      checkBoxList[0].doClick()
      assertThat(checkBoxList[0].isSelected).isFalse()
      dialog.clickDefaultButton()
    }

    yieldUntil { deviceSelectionListFlow.value.filter { it.isSelected }.size == 5 }
    val selectedDevices =
      deviceSelectionListFlow.value.filter { it.isSelected }.map { it.deviceInfo.name }
    assertThat(selectedDevices).doesNotContain("Pixel 5")
    assertThat(selectedDevices)
      .containsExactly("Pixel 6", "Pixel 6 Pro", "Pixel Watch", "Pixel Watch", "SomeName")
      .inOrder()
  }

  @Test
  fun testFirebaseLinkContainsUserEmail() {
    Mockito.mockStatic(BrowserUtil::class.java).use { mockBrowserUtil ->
      val dialog = SelectDeviceDialog(project)
      createModalDialogAndInteractWithIt({ dialog.show() }) {
        var url = ""
        mockBrowserUtil
          .whenever<String> { BrowserUtil.browse(anyString()) }
          .thenAnswer {
            url = it.getArgument(0) as String
            Unit
          }
        val allProjectLink = dialog.contentPanel.findAllDescendants<HyperlinkLabel>().first()
        assertThat(allProjectLink.text).isEqualTo("View All Projects")
        allProjectLink.doClick()
        waitForCondition(1.seconds) { url != "" }
        assertThat(url).isEqualTo("https://console.firebase.google.com?authuser=test@gmail.com")
      }
    }
  }
}
