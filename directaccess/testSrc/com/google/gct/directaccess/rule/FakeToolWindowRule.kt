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
package com.google.gct.directaccess.rule

import com.android.tools.idea.streaming.RUNNING_DEVICES_TOOL_WINDOW_ID
import com.android.tools.idea.testing.disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.replaceService
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.containers.ContainerUtil.createLockFreeCopyOnWriteList
import org.junit.rules.ExternalResource
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class FakeToolWindowRule(private val projectRule: ProjectRule) : ExternalResource() {

  private val project: Project
    get() = projectRule.project

  lateinit var fakeToolWindow: ToolWindow

  override fun before() {
    fakeToolWindow = FakeToolWindow(project)
    project.replaceService(
      ToolWindowManager::class.java,
      FakeToolWindowManager(projectRule.project, fakeToolWindow),
      projectRule.disposable,
    )
  }
}

private class FakeToolWindowManager(project: Project, private val fakeRunningDevicesWindow: ToolWindow) :
  ToolWindowHeadlessManagerImpl(project) {
  override fun getToolWindow(id: String?): ToolWindow? {
    return if (id == RUNNING_DEVICES_TOOL_WINDOW_ID) fakeRunningDevicesWindow else super.getToolWindow(id)
  }
}

class FakeToolWindow(project: Project) : ToolWindowHeadlessManagerImpl.MockToolWindow(project) {
  private val contentManager = mock<ContentManager>()
  private val contents = mutableListOf<Content>()
  private val listeners = createLockFreeCopyOnWriteList<ContentManagerListener>()
  private var selectedContent: Content? = null
    set(newValue) {
      val oldValue = field
      field = newValue
      notifyListeners(oldValue, newValue)
    }

  init {
    whenever(contentManager.contents).thenAnswer { contents.toTypedArray() }
    whenever(contentManager.addContent(any())).thenAnswer {
      // Event for content about to be unselected
      val content = it.arguments[0] as Content
      contents.add(content)
      selectedContent = content
      // then requires a return value that is not provided by the setter above
      Any()
    }

    whenever(contentManager.setSelectedContent(any())).thenAnswer {
      selectedContent = it.arguments[0] as Content
      // then requires a return value that is not provided by the setter above
      Any()
    }
    whenever(contentManager.selectedContent).thenAnswer { selectedContent }
  }

  override fun getContentManager() = contentManager

  override fun addContentManagerListener(listener: ContentManagerListener) {
    listeners.add(listener)
  }

  override fun getId() = RUNNING_DEVICES_TOOL_WINDOW_ID

  override fun show() {
    project.messageBus.syncPublisher(ToolWindowManagerListener.TOPIC).toolWindowShown(this)
  }

  private fun notifyListeners(oldContent: Content?, newContent: Content?) {
    // Event for content that was unselected
    oldContent?.let { notify(it) }
    // Event for content that was selected
    newContent?.let { notify(it) }
  }

  private fun notify(content: Content) =
    listeners.forEach { listener ->
      listener.selectionChanged(ContentManagerEvent(Any(), content, 0, ContentManagerEvent.ContentOperation.undefined))
    }
}
