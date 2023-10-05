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

import com.android.tools.idea.execution.common.AndroidConfigurationExecutor
import com.android.tools.idea.project.FacetBasedApplicationProjectContext
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.AndroidRunConfigurationExecutor
import com.android.tools.idea.run.DeviceFutures
import com.android.tools.idea.run.activity.launch.SpecificActivityLaunch
import com.android.tools.idea.run.configuration.execution.getApplicationIdAndDevices
import com.android.tools.idea.util.androidFacet
import com.google.gct.testrecorder.ui.TestRecorderAction
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.progress.runBlockingCancellable


// TODO: move to core module where AndroidRunConfiguration lives, when test-recorder module detached from core
class AndroidRunConfigurationTestRecorderExecutorProvider : AndroidConfigurationExecutor.Provider {
  override fun createAndroidConfigurationExecutor(env: ExecutionEnvironment): AndroidConfigurationExecutor? {
    val configuration = env.runProfile
    if (configuration !is AndroidRunConfiguration) return null

    if (env.getCopyableUserData(TestRecorderAction.KEY) != true) return null

    val deviceFutures = env.getCopyableUserData(DeviceFutures.KEY)

    return configuration.run {
      val applicationIdProvider = applicationIdProvider ?: throw RuntimeException("Cannot get ApplicationIdProvider")
      val apkProvider = apkProvider ?: throw RuntimeException("Cannot get ApkProvider")
      val baseExecutor = AndroidRunConfigurationExecutor(
        applicationIdProvider,
        FacetBasedApplicationProjectContext(
          applicationIdProvider.packageName,
          configuration.configurationModule.module?.androidFacet ?: throw RuntimeException("Cannot get AndroidFacet")
        ),
        env,
        deviceFutures,
        apkProvider
      )
      val activityName = (configuration.getLaunchOptionState(configuration.MODE) as? SpecificActivityLaunch.State)?.ACTIVITY_CLASS ?: ""

      return TestRecorderExecutor(env, baseExecutor, activityName, baseExecutor.facet) { indicator ->
        runBlockingCancellable { getApplicationIdAndDevices(env, deviceFutures, applicationIdProvider, indicator) }
      }
    }
  }
}
