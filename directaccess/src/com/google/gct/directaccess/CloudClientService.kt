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

import com.google.gct.login2.GoogleLoginService
import com.google.gct.login2.LoginFeature
import com.google.services.firebase.FirebaseLoginFeature
import com.google.services.firebase.directaccess.client.CloudClient
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.util.BuildNumber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Service
class CloudClientService(scope: CoroutineScope) {
  private val credentialFlow =
    GoogleLoginService.instance.activeUserFlow
      .map { getCredential() }
      .stateIn(scope, SharingStarted.Lazily, getCredential())

  internal var overrideClientForTest: CloudClient? = null
  val client = CloudClient(credentialFlow, scope)
    get() = overrideClientForTest ?: field

  private fun getCredential() = LoginFeature.feature<FirebaseLoginFeature>().credential()

  init {
    scope.launch {
      client.errorFlow.collect {
        // TODO
      }
    }
  }

  /** Returns devices available for streaming, filtered based on Studio version. */
  fun getAvailableDevices(endpoint: String, cloudProject: String?) =
    client.getAvailableDevices(endpoint, cloudProject).filter { (_, perVersionInfo) ->
      BuildNumber.fromString(perVersionInfo.directAccessVersionInfo?.minimumAndroidStudioVersion)
        .let { catalogBuildNumber ->
          catalogBuildNumber == null || catalogBuildNumber <= ApplicationInfo.getInstance().build
        }
    }

  companion object {
    fun instance() = service<CloudClientService>()
  }
}
