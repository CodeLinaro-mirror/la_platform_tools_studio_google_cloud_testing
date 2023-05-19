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
package com.google.gct.directaccess.ui

import com.google.gct.testing.android.CloudConfiguration
import com.google.gct.testing.android.CloudProjectSelector
import javax.swing.JComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface DirectAccessProjectSelector {

  val component: JComponent

  val selectedProject: StateFlow<String>
}

class DirectAccessProjectSelectorImpl(private val preferredProject: String) :
  DirectAccessProjectSelector {

  override val selectedProject = MutableStateFlow("")

  override val component: JComponent
    get() = projectSelector

  private var isPreferredProjectApplied = false

  // TODO (b/283017002): show preferredProject while refresh projects.
  private val projectSelector =
    CloudProjectSelector(CloudConfiguration.Kind.SINGLE_DEVICE).apply {
      addItemListener {
        if (!isPreferredProjectApplied) {
          isPreferredProjectApplied = true
          selectedItem = preferredProject
        } else {
          selectedProject.value = selectedItem as String
        }
      }
      refreshCloudProjects()
    }
}
