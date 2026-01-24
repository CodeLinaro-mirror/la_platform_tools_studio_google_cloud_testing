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
package com.google.gct.testrecorder.run

import com.android.ddmlib.IDevice
import com.android.tools.idea.execution.common.AndroidConfigurationExecutor
import com.android.tools.idea.execution.common.clearAppStorage
import com.android.tools.idea.execution.common.stats.RunStats
import com.android.tools.idea.run.AndroidRunConfiguration
import com.google.gct.testrecorder.debugger.TestRecorderDebugProcessListener
import com.google.gct.testrecorder.settings.TestRecorderSettings
import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.annotations.VisibleForTesting

class TestRecorderExecutor(
  private val env: ExecutionEnvironment,
  private val baseExecutor: AndroidConfigurationExecutor,
  private val specificActivityName: String?,
  private val facet: AndroidFacet,
  @VisibleForTesting val isRecordingTest: Boolean,
  private val getApplicationIdAndDevices:
    (indicator: ProgressIndicator) -> Pair<String, List<IDevice>>,
) : AndroidConfigurationExecutor {

  private val LOG = Logger.getInstance(this::class.java)

  override fun debug(indicator: ProgressIndicator): RunContentDescriptor {
    LOG.info("Start test recording session")

    val (packageName, devices) = getApplicationIdAndDevices(indicator)

    if (devices.size != 1) {
      throw ExecutionException("Test recording can run only on one device")
    }

    val device = devices.single()

    if (TestRecorderSettings.getInstance().CLEAN_BEFORE_START) {
      clearAppStorage(env.project, device, packageName, RunStats.from(env))
    }

    // Launching ETR is not supported when dual debugging windows are opened. If debugger type is
    // configured to "Detect Automatically",
    // temporarily set it to "Java only".
    val startingDebuggerType =
      (env.runProfile as AndroidRunConfiguration).androidDebuggerContext.debuggerType
    if (startingDebuggerType == "Auto") {
      (env.runProfile as AndroidRunConfiguration).androidDebuggerContext.debuggerType = "Java"
    }
    val session =
      object : DebuggerManagerListener {
        override fun sessionCreated(session: DebuggerSession) {
          session.process.addDebugProcessListener(
            TestRecorderDebugProcessListener(
              facet,
              env,
              device,
              packageName,
              isRecordingTest,
              specificActivityName,
              session,
            )
          )
        }
      }

    val busConnection = env.project.messageBus.connect()
    busConnection.subscribe(DebuggerManagerListener.TOPIC, session)

    try {
      return baseExecutor.debug(indicator)
    } finally {
      busConnection.disconnect()
      (env.runProfile as AndroidRunConfiguration).androidDebuggerContext.debuggerType =
        startingDebuggerType
    }
  }

  override val configuration =
    env.runProfile as? RunConfiguration
      ?: throw RuntimeException("Test recorder should only be run for RunConfiguration")

  override fun run(indicator: ProgressIndicator): RunContentDescriptor {
    throw RuntimeException(
      "TestRecorderAndroidRunConfigurationExecutor should always run in debug mode"
    )
  }

  override fun applyChanges(indicator: ProgressIndicator): RunContentDescriptor {
    throw RuntimeException(
      "TestRecorderAndroidRunConfigurationExecutor should always run in debug mode"
    )
  }

  override fun applyCodeChanges(indicator: ProgressIndicator): RunContentDescriptor {
    throw RuntimeException(
      "TestRecorderAndroidRunConfigurationExecutor should always run in debug mode"
    )
  }
}
