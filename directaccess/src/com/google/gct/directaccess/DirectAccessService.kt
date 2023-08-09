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
import com.google.gct.login.LoginState
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class DirectAccessService(val project: Project) : Disposable {
  private val scope = AndroidCoroutineScope(this)
  private val _cloudProjectFlow = MutableStateFlow<String?>(null)
  val cloudProjectFlow: StateFlow<String?> = _cloudProjectFlow

  @Synchronized
  fun selectCloudProject(cloudProject: String?) {
    cloudProject?.let {
      PropertiesComponent.getInstance(project).setValue("direct.access.project", it)
    }
    service<DirectAccessApplicationService>().registerCloudProject(project, cloudProject)
    _cloudProjectFlow.value = cloudProject
  }

  init {
    selectCloudProject(PropertiesComponent.getInstance(project).getValue("direct.access.project"))
    scope.launch { LoginState.loggedIn.filter { !it }.collect { selectCloudProject(null) } }
  }

  override fun dispose() {
    selectCloudProject(null)
  }
}
