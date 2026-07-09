/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.google.gct.testrecorder.run

import com.android.ddmlib.IDevice
import com.android.tools.idea.execution.common.AndroidConfigurationExecutorRunProfileState
import com.android.tools.idea.run.AndroidDevice
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.AndroidRunConfigurationType
import com.android.tools.idea.run.FakeAndroidDevice
import com.android.tools.idea.run.editor.DeployTarget
import com.android.tools.idea.run.editor.DeployTargetState
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth
import com.google.gct.testrecorder.ui.TestRecorderAction
import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AndroidRunConfigurationTestRecorderExecutorProviderTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  @Test
  fun produceCorrectExecutorByBypassingDeviceValidation() {
    projectRule.fixture.addFileToProject(
      "AndroidManifest.xml",
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
        package="com.example.test">
        <application />
      </manifest>
      """
        .trimIndent(),
    )

    val mockDevice = mock<IDevice>()
    whenever(mockDevice.getProperty(IDevice.PROP_BUILD_TYPE)).thenReturn("userdebug")

    val config =
      object : AndroidRunConfiguration(projectRule.project, AndroidRunConfigurationType.getInstance().factory) {
        override fun validateBeforeRun(executor: Executor, dataContext: DataContext) {
          // Do nothing to bypass unnecessary APK signing validation in this unit test.
        }

        override fun getDeployTarget(): DeployTarget {
          return object : DeployTarget {
            override fun hasCustomRunProfileState(executor: Executor) = false

            override fun getRunProfileState(executor: Executor, env: ExecutionEnvironment, state: DeployTargetState) = null

            override fun launchDevices(project: Project) = FakeAndroidDevice.forDevices(listOf(mockDevice))

            override fun getAndroidDevices(project: Project): List<AndroidDevice> = listOf(FakeAndroidDevice(mockDevice))
          }
        }
      }
    val settings =
      RunManager.getInstance(projectRule.project).createConfiguration(config, AndroidRunConfigurationType.getInstance().factory)
    config.setModule(projectRule.module)

    val env = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build()
    env.putCopyableUserData(TestRecorderAction.KEY, TestRecorderInfo(false))

    val state = config.getState(DefaultDebugExecutor.getDebugExecutorInstance(), env) as AndroidConfigurationExecutorRunProfileState
    Truth.assertThat(state.executor).isInstanceOf(TestRecorderExecutor::class.java)
    Truth.assertThat((state.executor as TestRecorderExecutor).isRecordingTest).isFalse()
  }
}
