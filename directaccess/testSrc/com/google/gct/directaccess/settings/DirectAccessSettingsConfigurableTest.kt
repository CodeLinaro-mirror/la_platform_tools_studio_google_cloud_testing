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
package com.google.gct.directaccess.settings

import com.android.flags.junit.FlagRule
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.idea.flags.ExperimentalConfigurable.ApplyState
import com.android.tools.idea.flags.StudioFlags
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.components.service
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import javax.swing.JCheckBox
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@RunsInEdt
class DirectAccessSettingsConfigurableTest {

  private val projectRule = ProjectRule()
  @get:Rule
  val ruleChain = RuleChain(projectRule, EdtRule(), FlagRule(StudioFlags.DIRECT_ACCESS, true))

  @Before
  fun setUp() = service<DirectAccessConfiguration>().loadState(DirectAccessConfiguration.State())

  @After
  fun tearDown() = service<DirectAccessConfiguration>().loadState(DirectAccessConfiguration.State())

  @Test
  fun testSettingsPage() {
    // DirectAccessConfiguration is loaded the same value as DIRECT_ACCESS flag.
    assertThat(service<DirectAccessConfiguration>().isEnabled).isTrue()

    val contributor = DirectAccessConfigurableContributor()
    assertThat(contributor.getName()).isEqualTo("Device Streaming")
    val configurable = contributor.createConfigurable(projectRule.project)
    val component = configurable.createComponent()!!

    val ui = FakeUi(component)
    val isDeviceStreamingEnabledCheckBox =
      ui.getComponent<JCheckBox> { it.text == "Enable Device Streaming in Android Studio" }

    assertThat(configurable.isModified).isFalse()
    assertThat(configurable.preApplyCallback()).isEqualTo(ApplyState.OK)
    assertThat(isDeviceStreamingEnabledCheckBox.isEnabled).isTrue()
    assertThat(isDeviceStreamingEnabledCheckBox.isSelected).isTrue()

    isDeviceStreamingEnabledCheckBox.doClick()
    assertThat(isDeviceStreamingEnabledCheckBox.isSelected).isFalse()
    assertThat(configurable.isModified).isTrue()
    assertThat(configurable.preApplyCallback()).isEqualTo(ApplyState.RESTART)

    isDeviceStreamingEnabledCheckBox.doClick()
    configurable.apply()
    assertThat(service<DirectAccessConfiguration>().isEnabled).isTrue()
    assertThat(configurable.isModified).isFalse()
    assertThat(configurable.preApplyCallback()).isEqualTo(ApplyState.OK)

    isDeviceStreamingEnabledCheckBox.isSelected = false
    assertThat(configurable.isModified).isTrue()
    assertThat(configurable.preApplyCallback()).isEqualTo(ApplyState.RESTART)
    configurable.reset()
    assertThat(isDeviceStreamingEnabledCheckBox.isSelected).isTrue()
  }
}
