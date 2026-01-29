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

import com.android.tools.idea.concurrency.createChildScope
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

/**
 * An application level service that maps a user project to its [DirectAccessCloudProjectManager] so that different projects with the same
 * cloud project share the same manager.
 */
@Service
class DirectAccessApplicationService(private val scope: CoroutineScope) {

  /** A mapping from a user project to its selected cloud project. */
  private val cloudProjectMap = mutableMapOf<Project, CloudProjectEntry>()
  /** A mapping from a cloud project to its [DirectAccessCloudProjectManager]. */
  private val cloudProjectManagerMap = mutableMapOf<CloudProjectEntry, DirectAccessCloudProjectManager>()

  @Synchronized
  fun registerCloudProject(project: Project, cloudProject: CloudProjectEntry?): DirectAccessCloudProjectManager? {
    val existingCloudProject = cloudProjectMap[project]
    if (existingCloudProject == cloudProject) {
      return getCloudProjectManager(cloudProject)
    }

    if (cloudProject != null) {
      cloudProjectMap[project] = cloudProject
    } else {
      cloudProjectMap.remove(project)
    }
    removeUnusedCloudProjectManager(existingCloudProject)
    return getCloudProjectManager(cloudProject)
  }

  fun removeUnusedCloudProjectManager(cloudProject: CloudProjectEntry?) {
    if (cloudProject != null && cloudProject !in cloudProjectMap.values) {
      cloudProjectManagerMap.remove(cloudProject)?.close()
    }
  }

  /**
   * Returns a unique [DirectAccessCloudProjectManager] from [cloudProject]. Do not dispose the returned [DirectAccessCloudProjectManager]
   * manually, use [removeUnusedCloudProjectManager] instead to potentially reuse it elsewhere.
   */
  @Synchronized
  fun getCloudProjectManager(cloudProject: CloudProjectEntry?): DirectAccessCloudProjectManager? {
    if (cloudProject == null) return null
    return cloudProjectManagerMap.computeIfAbsent(cloudProject) { DirectAccessCloudProjectManager(it, scope.createChildScope(true)) }
  }
}
