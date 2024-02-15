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

import com.google.gct.login.LoginState
import com.google.gct.login.LoginStatus
import com.google.gct.login2.GoogleLoginService
import com.google.gct.login2.LoginFeature
import com.google.services.firebase.FirebaseLoginFeature
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.util.application
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class DirectAccessService(val project: Project, val scope: CoroutineScope) : Disposable {

  private val _cloudProjectManager = MutableStateFlow<DirectAccessCloudProjectManager?>(null)
  val cloudProjectManager: StateFlow<DirectAccessCloudProjectManager?> = _cloudProjectManager

  /** A flow of devices with selected states. */
  val deviceSelectionListFlow =
    MutableStateFlow(
      project.service<DirectAccessPersistentStateComponent>().state.deviceSelectionList.map {
        it.createDeviceSelection()
      }
    )
  /** Connection to application message bus to listen to project closing events */
  private val messageBusConnection: MessageBusConnection
  /** Tracks studio project closing */
  var isProjectClosing: Boolean = false
    private set

  @Synchronized
  fun selectCloudProject(cloudProject: String?) {
    val cloudProjectEntry =
      when (cloudProject) {
        null,
        "" -> null
        else -> {
          // Stores the last non-null cloud project in PropertiesComponent.
          project.service<DirectAccessPersistentStateComponent>().state.selectedCloudProject =
            cloudProject
          getCloudProject(cloudProject)
        }
      }
    _cloudProjectManager.value =
      service<DirectAccessApplicationService>().registerCloudProject(project, cloudProjectEntry)
  }

  init {
    scope.launch {
      val loginService = service<GoogleLoginService>()
      if (loginService.useOldVersion) {
        service<LoginState>().loginStatus.collect {
          if (it is LoginStatus.LoggedIn) {
            selectCloudProject(
              project.service<DirectAccessPersistentStateComponent>().state.selectedCloudProject
            )
          } else {
            selectCloudProject(null)
          }
        }
      } else {
        loginService.activeUserFlow.collect {
          if (it?.isLoggedIn(LoginFeature.feature<FirebaseLoginFeature>()) == true) {
            selectCloudProject(
              project.service<DirectAccessPersistentStateComponent>().state.selectedCloudProject
            )
          } else {
            selectCloudProject(null)
          }
        }
      }
    }
    scope.launch {
      deviceSelectionListFlow.collect { deviceSelectionList ->
        project.service<DirectAccessPersistentStateComponent>().state.deviceSelectionList =
          deviceSelectionList.map { it.toPersistentDeviceSelectionData() }.toMutableList()
      }
    }

    messageBusConnection =
      application.messageBus.connect(this).apply {
        subscribe(
          ProjectManager.TOPIC,
          object : ProjectManagerListener {
            override fun projectClosing(closingProject: Project) {
              if (closingProject != project) return
              // We don't close device connections from here since the devices might be connected in
              // another studio project using the same ConnectionManager
              isProjectClosing = true
            }
          },
        )
      }
  }

  override fun dispose() {
    messageBusConnection.disconnect()
    selectCloudProject(null)
  }

  private fun getCloudProject(name: String): CloudProjectEntry? {
    val user = service<GoogleLoginService>().getEmail() ?: return null
    return CloudProjectEntry(user, name)
  }
}

internal val Project.directAccessCloudProjectManager
  get() = service<DirectAccessService>().cloudProjectManager.value
