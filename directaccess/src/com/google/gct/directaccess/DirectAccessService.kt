/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.tools.idea.concurrency.AndroidCoroutineScope
import com.google.gct.directaccess.provisioner.DeviceSelection
import com.google.gct.login.LoginState
import com.google.gct.login.LoginStatus
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class DirectAccessService(val project: Project) : Disposable {
  private val scope = AndroidCoroutineScope(this)

  private val _cloudProjectManager = MutableStateFlow<DirectAccessCloudProjectManager?>(null)
  val cloudProjectManager: StateFlow<DirectAccessCloudProjectManager?> = _cloudProjectManager
  val cloudProject: String?
    get() = cloudProjectManager.value?.cloudProject?.name

  /** A flow of devices with selected states. */
  val deviceSelectionListFlow = MutableStateFlow(listOf<DeviceSelection>())

  @Synchronized
  fun selectCloudProject(cloudProject: String?) {
    val cloudProjectEntry =
      cloudProject?.let {
        // Stores the last non-null cloud project in PropertiesComponent.
        PropertiesComponent.getInstance(project).setValue("direct.access.project", it)
        getCloudProject(cloudProject)
      }
    _cloudProjectManager.value =
      service<DirectAccessApplicationService>().registerCloudProject(project, cloudProjectEntry)
  }

  init {
    scope.launch {
      service<LoginState>().loginStatus.collect {
        if (it is LoginStatus.LoggedIn) {
          selectCloudProject(
            PropertiesComponent.getInstance(project).getValue("direct.access.project")
          )
        } else {
          selectCloudProject(null)
        }
      }
    }
  }

  override fun dispose() {
    selectCloudProject(null)
  }

  private fun getCloudProject(name: String): CloudProjectEntry? {
    val user =
      (service<LoginState>().loginStatus.value as? LoginStatus.LoggedIn)?.email ?: return null
    return CloudProjectEntry(user, name)
  }
}

internal val Project.directAccessCloudProjectManager
  get() = service<DirectAccessService>().cloudProjectManager.value
