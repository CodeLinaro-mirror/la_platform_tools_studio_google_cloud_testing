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
package com.google.gct.directaccess

import com.android.tools.idea.adblib.AdbLibApplicationService
import com.android.tools.idea.concurrency.AndroidCoroutineScope
import com.android.tools.idea.concurrency.createChildScope
import com.google.services.firebase.directaccess.client.DirectAccessConnectionManager
import com.google.services.firebase.directaccess.client.DirectAccessReservationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.jetbrains.rd.util.concurrentMapOf

/**
 * An application level service that maps a user project to its [DirectAccessReservationManager] and
 * [DirectAccessConnectionManager] so that different projects with the same cloud project share the
 * same managers.
 *
 * Each cloud project has its own access to device catalog and reservations. Different user projects
 * may use the same cloud project. Connecting to a reservation adds a connected device to the adb
 * device list. To avoid adding duplicate devices for the same reservation, we need to make
 * [DirectAccessReservationManager] and [DirectAccessConnectionManager] cloud project scoped and
 * shared across different user projects.
 */
@Service
class DirectAccessApplicationService : Disposable {

  private val scope = AndroidCoroutineScope(this)
  // TODO(b/296468326): Share reservation list across user projects with the same cloud project.
  /**
   * A mapping from a user project to its selected cloud project with plain format e.g. `foo`,
   * `ftl-direct-access`.
   */
  private val cloudProjectMap = concurrentMapOf<Project, String>()
  /** A mapping from a cloud project to its [DirectAccessReservationManager]. */
  private val reservationManagerMap = concurrentMapOf<String, DirectAccessReservationManager>()
  /** A mapping from a cloud project to its [DirectAccessConnectionManager]. */
  private val connectionManagerMap = concurrentMapOf<String, DirectAccessConnectionManager>()

  fun registerCloudProject(project: Project, cloudProject: String?) {
    val cloudProjectToRemove = cloudProjectMap[project]
    if (cloudProjectToRemove == cloudProject) {
      return
    }

    if (cloudProject != null) {
      cloudProjectMap[project] = cloudProject
    } else {
      cloudProjectMap.remove(project)
    }
    if (cloudProjectToRemove != null && cloudProjectToRemove !in cloudProjectMap.values) {
      reservationManagerMap.remove(cloudProjectToRemove)?.close()
      connectionManagerMap.remove(cloudProjectToRemove)?.close()
    }
  }

  fun getReservationManager(project: Project): DirectAccessReservationManager? {
    val cloudProject = cloudProjectMap.getValue(project) ?: return null
    return reservationManagerMap.computeIfAbsent(cloudProject) {
      DirectAccessReservationManager(
        it,
        scope.createChildScope(true),
        service<DirectAccessServiceSetup>().channel
      ) {
        service<DirectAccessServiceSetup>().fetchAccessToken()
      }
    }
  }

  fun getConnectionManager(project: Project): DirectAccessConnectionManager? {
    val cloudProject = cloudProjectMap[project] ?: return null
    val reservationManager = getReservationManager(project) ?: return null
    return connectionManagerMap.computeIfAbsent(cloudProject) {
      DirectAccessConnectionManager(
        scope.createChildScope(true),
        AdbLibApplicationService.instance.session,
        { service<DirectAccessServiceSetup>().fetchAccessToken() },
        service<DirectAccessServiceSetup>().channel,
        reservationManager,
      )
    }
  }

  override fun dispose() = Unit
}
