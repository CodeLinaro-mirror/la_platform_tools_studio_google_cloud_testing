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
package com.google.gct.directaccess.ui

import com.android.tools.idea.concurrency.AndroidCoroutineScope
import com.android.tools.idea.devicemanager.DeviceManagerTab
import com.android.tools.idea.devicemanager.DevicePanel
import com.google.gct.directaccess.provisioner.NotLoggedInException
import com.google.gct.login.GoogleLogin
import com.google.gct.login.LoginState
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.StatusText
import icons.DirectAccessIcons
import java.awt.Graphics
import javax.swing.JComponent
import javax.swing.JPanel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class FirebaseDeviceTab : DeviceManagerTab {

  override fun isApplicable() = false

  override fun getName() = "Firebase"

  override fun getPanel(project: Project, parentDisposable: Disposable): DevicePanel {
    if (!GoogleLogin.instance.isLoggedIn) {
      throw NotLoggedInException()
    }

    return FirebaseDevicePanel(project, parentDisposable)
  }

  override fun getErrorComponent(throwable: Throwable): JComponent {
    if (throwable is NotLoggedInException) {
      return createNotLoggedInComponent()
    }
    return super.getErrorComponent(throwable)
  }

  private fun createNotLoggedInComponent(): JComponent {
    val loggedOutText =
      object : StatusText() {
          override fun isStatusVisible() = true
        }
        .apply {
          appendLine(
            DirectAccessIcons.FIREBASE_LOGO,
            "",
            SimpleTextAttributes.REGULAR_ATTRIBUTES,
            null
          )
          appendLine("")
          appendLine(
            "Log in to Firebase Test Lab for direct access",
            SimpleTextAttributes.REGULAR_ATTRIBUTES,
            null
          )
          @Suppress("DialogTitleCapitalization")
          appendLine(
            "to a variety of Android physical devices.",
            SimpleTextAttributes.REGULAR_ATTRIBUTES,
            null
          )
          appendLine("Log in", SimpleTextAttributes.LINK_ATTRIBUTES) {
            GoogleLogin.instance.logIn()
          }
          appendLine("")
          @Suppress("DialogTitleCapitalization")
          appendLine(
            AllIcons.General.ContextHelp,
            "Learn about Firebase Test Lab",
            SimpleTextAttributes.LINK_ATTRIBUTES
          ) {
            BrowserUtil.browse("https://firebase.google.com/docs/test-lab")
          }
        }

    return object : JPanel() {
      init {
        loggedOutText.attachTo(this)
      }

      override fun paint(g: Graphics?) {
        super.paint(g)
        loggedOutText.paint(this, g)
      }
    }
  }

  override fun setRecreateCallback(r: Runnable, disposable: Disposable) {
    AndroidCoroutineScope(disposable).launch { LoginState.loggedIn.collect { r.run() } }
  }
}
