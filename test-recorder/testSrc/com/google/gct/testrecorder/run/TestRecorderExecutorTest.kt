package com.google.gct.testrecorder.run

import com.android.ddmlib.IDevice
import com.android.testutils.MockitoKt.mock
import com.android.tools.analytics.UsageTrackerRule
import com.android.tools.idea.execution.common.AndroidConfigurationExecutor
import com.android.tools.idea.execution.common.assertTaskPresentedInStats
import com.android.tools.idea.execution.common.stats.RunStats
import com.android.tools.idea.run.AndroidRunConfigurationType
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.util.androidFacet
import com.google.common.truth.Truth.assertThat
import com.google.gct.testrecorder.ui.TestRecorderAction
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import org.junit.Rule
import org.junit.Test

class TestRecorderExecutorTest  {


  @get:Rule
  val projectRule = AndroidProjectRule.inMemory()
  @get:Rule
  val usageTrackerRule = UsageTrackerRule()

  @Test
  fun runDebugAndCleanStorage() {
    val settings = RunManager.getInstance(projectRule.project).createConfiguration("app", AndroidRunConfigurationType.getInstance().factory)

    val env = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build()
    env.putCopyableUserData(TestRecorderAction.KEY, TestRecorderInfo(true))

    val runStats = RunStats(projectRule.project)
    env.putUserData(RunStats.KEY, runStats)

    var debugInvoked = false

    val baseExecutor = object : AndroidConfigurationExecutor {
      override val configuration = settings.configuration

      override fun run(indicator: ProgressIndicator): RunContentDescriptor {
        throw RuntimeException("Shouldn't invoke")
      }

      override fun debug(indicator: ProgressIndicator): RunContentDescriptor {
        debugInvoked = true
        return mock<RunContentDescriptor>()
      }

      override fun applyChanges(indicator: ProgressIndicator): RunContentDescriptor {
        throw RuntimeException("Shouldn't invoke")
      }

      override fun applyCodeChanges(indicator: ProgressIndicator): RunContentDescriptor {
        throw RuntimeException("Shouldn't invoke")
      }

    }

    val device = mock<IDevice>()

    val executor = TestRecorderExecutor(env, baseExecutor, "", projectRule.module.androidFacet!!, true) {
      Pair("appId", listOf(device))
    }

    executor.debug(EmptyProgressIndicator())
    runStats.success()

    assertThat(debugInvoked).isTrue()
    assertTaskPresentedInStats(usageTrackerRule.usages, "CLEAR_APP_STORAGE_TASK")
  }
}