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

import com.android.tools.idea.concurrency.AndroidDispatchers
import com.android.tools.idea.deviceprovisioner.DeviceProvisionerService
import com.google.services.firebase.FirebaseProjectClient
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.util.ui.NamedColorUtil
import java.awt.Color
import javax.swing.JComponent
import javax.swing.JTextField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val ERROR_FETCHING_FIREBASE_PROJECT = "Error fetching firebase projects"
internal const val NO_PROJECTS_AVAILABLE = "No project available"

/** Manages a [component] to select a cloud project for [DeviceProvisionerService]. */
interface DirectAccessProjectSelector {

  val component: JComponent

  val selectedProject: StateFlow<String>

  val isReady: StateFlow<Boolean>
}

/**
 * A [DirectAccessProjectSelector] that returns a combo box of available projects.
 *
 * @param preferredProject the project to select initially if available
 * @param shouldEnable true if project selection is enabled
 */
class DirectAccessProjectSelectorImpl(
  private val preferredProject: String,
  private val shouldEnable: Boolean,
  scope: CoroutineScope,
) : DirectAccessProjectSelector, ComboBox<String>() {

  override val component: JComponent
    get() = this

  override val selectedProject = MutableStateFlow("")

  override val isReady = MutableStateFlow(false)

  private var isPreferredProjectApplied = false

  init {
    renderer = DirectAccessProjectSelectorRenderer
    model = CollectionComboBoxModel(listOf("Loading..."))
    isEnabled = false
    setDisabledTextColor(NamedColorUtil.getInactiveTextColor())
    if (!shouldEnable) {
      toolTipText = "Stop reservations to change projects"
    }
    preferredSize = null
    scope.refreshProjects()
    addItemListener { scope.launch { updateSelectedItem(it.item as String) } }
  }

  private fun CoroutineScope.refreshProjects() = launch {
    val projects =
      try {
        FirebaseProjectClient.listFirebaseProjects().mapNotNull { it.projectId }
      } catch (e: Exception) {
        null
      }
    withContext(AndroidDispatchers.uiThread) {
      when {
        projects == null -> {
          model = CollectionComboBoxModel(listOf(ERROR_FETCHING_FIREBASE_PROJECT))
          updateSelectedItem(ERROR_FETCHING_FIREBASE_PROJECT)
          isEnabled = false
          setDisabledTextColor(NamedColorUtil.getErrorForeground())
        }
        projects.isEmpty() -> {
          model = CollectionComboBoxModel(listOf(NO_PROJECTS_AVAILABLE))
          updateSelectedItem(NO_PROJECTS_AVAILABLE)
          isEnabled = false
        }
        else -> {
          model = CollectionComboBoxModel(projects)
          updateSelectedItem(preferredProject)
          isEnabled = shouldEnable
        }
      }
    }
  }

  private fun setDisabledTextColor(color: Color) {
    val textField = editor.editorComponent as JTextField
    textField.disabledTextColor = color
  }

  private fun updateSelectedItem(item: String) {
    if (!isPreferredProjectApplied) {
      isReady.value = true
      isPreferredProjectApplied = true
      selectedItem = item
    }
    selectedProject.value = selectedItem as String
  }
}
