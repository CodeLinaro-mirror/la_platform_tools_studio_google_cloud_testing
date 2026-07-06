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
package com.google.gct.directaccess

import com.android.tools.idea.flags.StudioFlags
import com.google.gct.login2.LoginFeature
import com.google.services.firebase.FirebaseLoginFeature
import com.google.services.firebase.directaccess.client.CloudClient
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.util.BuildNumber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Service
class CloudClientService(scope: CoroutineScope) {
  internal var overrideClientForTest: CloudClient? = null
  val client = CloudClient({ getToken() })
    get() = overrideClientForTest ?: field

  private fun getToken() = LoginFeature.feature<FirebaseLoginFeature>().oAuthToken()

  init {
    scope.launch {
      client.errorFlow.collect {
        // TODO
      }
    }
  }

  /** Returns devices available for streaming, filtered based on Studio version. */
  fun getAvailableDevices(endpoint: String, cloudProject: String?) =
    client
      .getAvailableDevices(endpoint, cloudProject)
      .filter { (androidModel, _) ->
        val accessDeniedReasons = androidModel.accessDeniedReasons
        (StudioFlags.SHOW_OEM_LAB_DEVICES.get() || androidModel.labInfo == null) &&
          (accessDeniedReasons.isNullOrEmpty() ||
            // Right now the only supported reason is that the 2p lab eula isn't accepted.
            // Filter out devices with any other denied reason.
            accessDeniedReasons.singleOrNull() == "EULA_NOT_ACCEPTED")
      }
      .filter { (_, perVersionInfo) ->
        BuildNumber.fromString(perVersionInfo.directAccessVersionInfo?.minimumAndroidStudioVersion).let { catalogBuildNumber ->
          catalogBuildNumber == null || catalogBuildNumber <= ApplicationInfo.getInstance().build
        }
      }

  companion object {
    fun instance() = service<CloudClientService>()
  }
}
