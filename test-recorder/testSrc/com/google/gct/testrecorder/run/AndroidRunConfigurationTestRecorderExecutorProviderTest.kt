package com.google.gct.testrecorder.run

import com.android.ddmlib.IDevice
import com.android.sdklib.AndroidVersion
import com.android.sdklib.devices.Abi
import com.android.tools.idea.execution.common.AndroidConfigurationExecutorRunProfileState
import com.android.tools.idea.gradle.project.sync.snapshots.AndroidCoreTestProject
import com.android.tools.idea.run.AndroidDevice
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.AndroidRunConfigurationType
import com.android.tools.idea.run.FakeAndroidDevice
import com.android.tools.idea.run.editor.DeployTarget
import com.android.tools.idea.run.editor.DeployTargetState
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.executeMakeBeforeRunStepInTest
import com.android.tools.idea.testing.mockDeviceFor
import com.google.common.truth.Truth
import com.google.gct.testrecorder.ui.TestRecorderAction
import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.project.Project
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock

class AndroidRunConfigurationTestRecorderExecutorProviderTest {

  @get:Rule val projectRule = AndroidProjectRule.testProject(AndroidCoreTestProject.SIMPLE_APPLICATION)

  @Test
  @Ignore("b/531716132")
  fun produceCorrectExecutor() {
    val config =
      object : AndroidRunConfiguration(projectRule.project, AndroidRunConfigurationType.getInstance().factory) {
        override fun getDeployTarget(): DeployTarget {
          return object : DeployTarget {
            override fun hasCustomRunProfileState(executor: Executor) = false

            override fun getRunProfileState(executor: Executor, env: ExecutionEnvironment, state: DeployTargetState) = null

            override fun launchDevices(project: Project) = FakeAndroidDevice.forDevices(listOf(mock<IDevice>()))

            override fun getAndroidDevices(project: Project): List<AndroidDevice> = listOf(FakeAndroidDevice(mock<IDevice>()))
          }
        }
      }
    val settings =
      RunManager.getInstance(projectRule.project).createConfiguration(config, AndroidRunConfigurationType.getInstance().factory)
    config.setModule(projectRule.module)
    val device = mockDeviceFor(AndroidVersion(AndroidVersion.VersionCodes.R), listOf(Abi.X86_64, Abi.X86))

    config.executeMakeBeforeRunStepInTest(device)

    val env = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build()
    env.putCopyableUserData(TestRecorderAction.KEY, TestRecorderInfo(false))

    val state = config.getState(DefaultDebugExecutor.getDebugExecutorInstance(), env) as AndroidConfigurationExecutorRunProfileState
    Truth.assertThat(state.executor).isInstanceOf(TestRecorderExecutor::class.java)
    Truth.assertThat((state.executor as TestRecorderExecutor).isRecordingTest).isFalse()
  }
}
