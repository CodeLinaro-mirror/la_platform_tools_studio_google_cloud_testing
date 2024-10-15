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
import com.google.gct.directaccess.DirectAccessOnboardingService
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.login2.LoginFeature
import com.google.services.firebase.FirebaseLoginFeature
import com.google.services.firebase.FirebaseProjectClient
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.AnActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.EmptySpacingConfiguration
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import java.awt.CardLayout
import java.awt.Color
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.VisibleForTesting

internal const val ERROR_FETCHING_FIREBASE_PROJECT = "Error fetching firebase projects"
internal const val NO_PROJECTS_AVAILABLE = "No project available"

/** Manages a [component] to select a cloud project for [DeviceProvisionerService]. */
interface DirectAccessProjectSelector {

  val component: JComponent

  val selectedProject: StateFlow<String>

  /** Returns true if the [selectedProject] is ready to emit values. */
  val isReady: StateFlow<Boolean>
}

/**
 * A [DirectAccessProjectSelector] that returns a combo box of available projects.
 *
 * TODO (b/366306650): remove this component once the new device catalog is enabled.
 *
 * @param preferredProject the project to select initially if available
 * @param shouldEnable true if project selection is enabled
 */
class DirectAccessProjectSelectorImpl(
  private val project: Project,
  private var preferredProject: String,
  private val shouldEnable: Boolean,
  scope: CoroutineScope,
) : DirectAccessProjectSelector, JPanel(CardLayout()) {

  private val noProjectsCard = "no projects"
  private val projectSelectorCard = "project selector"
  private val newProjectCreatedCard = "project created"

  override val component: JComponent
    get() = this

  override val selectedProject = MutableStateFlow("")

  override val isReady = MutableStateFlow(false)

  @VisibleForTesting internal val comboBox = MyComboBox(scope)
  @VisibleForTesting
  internal val createProjectHyperlink =
    HyperlinkLabel("Create a Firebase project...").apply {
      setHyperlinkTarget(userSpecificFirebaseConsoleLink)
    }

  private val projectCreatingLabel = JBLabel()

  private val uiDispatcher: CoroutineDispatcher
    get() = AndroidDispatchers.uiThread(ModalityState.any())

  init {
    add(comboBox, projectSelectorCard)
    add(createProjectHyperlink, noProjectsCard)
    add(projectCreatingLabel, newProjectCreatedCard)
    scope.refreshProjects()
  }

  private fun showCard(card: String) = (layout as CardLayout).show(this, card)

  private fun CoroutineScope.refreshProjects() = launch {
    val task = service<DirectAccessOnboardingService>().taskFlow.value
    if (task?.isPending == true) {
      val projectName = task.cloudProject.name
      projectCreatingLabel.text = "Creating project $projectName"
      withContext(uiDispatcher) { showCard(newProjectCreatedCard) }
      launch {
        // Show [projectSelectorCard] after the created project is selected.
        project
          .service<DirectAccessService>()
          .cloudProjectManager
          .takeWhile { it?.cloudProject?.name != projectName }
          .collect()
        preferredProject = projectName
        withContext(uiDispatcher) {
          showCard(projectSelectorCard)
          comboBox.updateProjects(getProjects())
        }
      }
    } else {
      val projects = getProjects()
      val card = if (projects?.isEmpty() == true) noProjectsCard else projectSelectorCard

      if (projects?.size == 1) {
        preferredProject = projects.first()
      }

      withContext(uiDispatcher) {
        showCard(card)
        comboBox.updateProjects(projects)
      }
    }
  }

  private suspend fun getProjects() =
    try {
      withContext(Dispatchers.IO) {
        FirebaseProjectClient.listFirebaseProjects().mapNotNull { it.projectId }
      }
    } catch (e: Exception) {
      null
    }

  inner class MyComboBox(scope: CoroutineScope) : ComboBox<String>() {
    private var isPreferredProjectApplied = false

    init {
      isEditable = false
      renderer = DirectAccessProjectSelectorRenderer
      model = CollectionComboBoxModel(listOf("Loading..."))
      isEnabled = false
      setDisabledTextColor(NamedColorUtil.getInactiveTextColor())
      if (!shouldEnable) {
        toolTipText = "Return all devices to change projects"
      }
      preferredSize = null
      addItemListener { scope.launch(uiDispatcher) { updateSelectedItem(it.item as String) } }
    }

    fun updateProjects(projects: List<String>?) {
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
          // Append an empty project if one is not selected initially.
          // This prevents calling the APIs of the first project in the list.
          val finalProjects = if (preferredProject.isEmpty()) listOf("") + projects else projects
          model = CollectionComboBoxModel(finalProjects)
          updateSelectedItem(preferredProject)
          isEnabled = shouldEnable
        }
      }
    }

    private fun setDisabledTextColor(color: Color) {
      val textField = editor.editorComponent as JTextField
      textField.disabledTextColor = color
    }

    private fun updateSelectedItem(item: String) {
      if (!isPreferredProjectApplied) {
        selectedItem = item
      }
      selectedProject.value = selectedItem as String
      // Set isReady to true after preferred project applied.
      if (!isPreferredProjectApplied) {
        isReady.value = true
        isPreferredProjectApplied = true
      }
    }
  }
}

/**
 * A [DirectAccessProjectSelector] that returns a combo box of available projects.
 *
 * @param preferredProject the project to select initially if available
 * @param shouldEnable true if project selection is enabled
 */
class DirectAccessProjectSelectorImpl2(
  private val project: Project,
  private var preferredProject: String,
  private val shouldEnable: Boolean,
  scope: CoroutineScope,
) : DirectAccessProjectSelector, JPanel(CardLayout()) {

  private val noProjectsCard = "no projects"
  private val projectSelectorCard = "project selector"
  private val newProjectCreatedCard = "project created"
  private val createProjectMessagePanel = panel {
    customizeSpacingConfiguration(EmptySpacingConfiguration()) {
      // TODO (b/364673782): update text with UX requirements.
      row { text("Create a free spark plan to have free minutes").apply { align(Align.FILL) } }
    }
  }

  override val selectedProject = MutableStateFlow("")

  override val isReady = MutableStateFlow(false)

  @VisibleForTesting internal val comboBox = MyComboBox(scope)
  @VisibleForTesting
  internal val createProjectActionLink =
    AnActionLink(
      "Create a Spark Plan Project...",
      object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
          LoginFeature.feature<FirebaseLoginFeature>()
            .logInAsync(parentComponent = this@DirectAccessProjectSelectorImpl2)
        }
      },
    )

  private val projectCreatingLabel = JBLabel()

  override val component =
    JPanel(VerticalLayout(5)).apply {
      border = JBUI.Borders.empty(5, 10)
      val chooseProjectPanel = JPanel(HorizontalLayout(5))

      chooseProjectPanel.add(JBLabel("Project:"))
      chooseProjectPanel.add(this@DirectAccessProjectSelectorImpl2)
      add(chooseProjectPanel)
      add(createProjectMessagePanel.apply { isVisible = false })
    }

  private val uiDispatcher: CoroutineDispatcher
    get() = AndroidDispatchers.uiThread(ModalityState.any())

  init {
    add(comboBox, projectSelectorCard)
    add(createProjectActionLink, noProjectsCard)
    add(projectCreatingLabel, newProjectCreatedCard)
    scope.refreshProjects()
  }

  private fun showCard(card: String) {
    (layout as CardLayout).show(this, card)
    createProjectMessagePanel.isVisible = card == noProjectsCard
  }

  private fun CoroutineScope.refreshProjects() = launch {
    val task = service<DirectAccessOnboardingService>().taskFlow.value
    if (task?.isPending == true) {
      val projectName = task.cloudProject.name
      projectCreatingLabel.text = "Creating project $projectName"
      withContext(uiDispatcher) { showCard(newProjectCreatedCard) }
      launch {
        // Show [projectSelectorCard] after the created project is selected.
        project
          .service<DirectAccessService>()
          .cloudProjectManager
          .takeWhile { it?.cloudProject?.name != projectName }
          .collect()
        preferredProject = projectName
        withContext(uiDispatcher) {
          showCard(projectSelectorCard)
          comboBox.updateProjects(getProjects())
        }
      }
    } else {
      val projects = getProjects()
      val card = if (projects?.isEmpty() == true) noProjectsCard else projectSelectorCard

      if (projects?.size == 1) {
        preferredProject = projects.first()
      }

      withContext(uiDispatcher) {
        showCard(card)
        comboBox.updateProjects(projects)
      }
    }
  }

  private suspend fun getProjects() =
    try {
      withContext(Dispatchers.IO) {
        FirebaseProjectClient.listFirebaseProjects().mapNotNull { it.projectId }
      }
    } catch (e: Exception) {
      null
    }

  inner class MyComboBox(scope: CoroutineScope) : ComboBox<String>() {
    private var isPreferredProjectApplied = false

    init {
      isEditable = false
      renderer = DirectAccessProjectSelectorRenderer
      model = CollectionComboBoxModel(listOf("Loading..."))
      isEnabled = false
      setDisabledTextColor(NamedColorUtil.getInactiveTextColor())
      if (!shouldEnable) {
        toolTipText = "Return all devices to change projects"
      }
      preferredSize = null
      addItemListener { scope.launch(uiDispatcher) { updateSelectedItem(it.item as String) } }
    }

    fun updateProjects(projects: List<String>?) {
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
          // Append an empty project if one is not selected initially.
          // This prevents calling the APIs of the first project in the list.
          val finalProjects = if (preferredProject.isEmpty()) listOf("") + projects else projects
          model = CollectionComboBoxModel(finalProjects)
          updateSelectedItem(preferredProject)
          isEnabled = shouldEnable
        }
      }
    }

    private fun setDisabledTextColor(color: Color) {
      val textField = editor.editorComponent as JTextField
      textField.disabledTextColor = color
    }

    private fun updateSelectedItem(item: String) {
      if (!isPreferredProjectApplied) {
        selectedItem = item
      }
      selectedProject.value = selectedItem as String
      // Set isReady to true after preferred project applied.
      if (!isPreferredProjectApplied) {
        isReady.value = true
        isPreferredProjectApplied = true
      }
    }
  }
}
