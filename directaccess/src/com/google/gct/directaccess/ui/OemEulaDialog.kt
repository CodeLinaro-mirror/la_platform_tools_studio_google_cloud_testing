/*
 * Copyright (C) 2025 The Android Open Source Project
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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.tools.adtui.compose.LocalProject
import com.android.tools.adtui.compose.StudioComposePanel
import com.android.tools.adtui.stdui.StandardColors
import com.android.tools.idea.concurrency.createCoroutineScope
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.checkPermissions
import com.google.gct.directaccess.provisioner.OemLabsAssetsRegistry
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.TaskCancellation
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.util.ui.JBUI
import java.net.Socket
import java.net.URI
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.swing.Action
import javax.swing.JComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.jetbrains.jewel.bridge.icon.fromPlatformIcon
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.ExternalLink
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Typography
import org.jetbrains.jewel.ui.icon.IntelliJIconKey
import org.mortbay.jetty.Server
import org.mortbay.jetty.handler.AbstractHandler

class OemEulaDialog(val labs: List<String>, val project: Project) : DialogWrapper(project, true) {
  private lateinit var centerPanel: JComponent

  init {
    init()
    myOKAction.putValue(Action.NAME, "Done")
  }

  override fun createCenterPanel(): JComponent {
    var hasPermission: Boolean? by mutableStateOf(null)
    disposable.createCoroutineScope().launch(Dispatchers.IO) {
      val cloudProject =
        project.service<DirectAccessService>().cloudProjectManager.value?.cloudProject
          ?: return@launch
      hasPermission =
        !checkPermissions(setOf("resourcemanager.projects.update"), cloudProject)
          ?.permissions
          .isNullOrEmpty()
    }
    return StudioComposePanel {
        CompositionLocalProvider(LocalProject provides project) {
          ComposeContent(hasPermission) { centerPanel }
        }
      }
      .apply {
        preferredSize = JBUI.size(480, 260)
        minimumSize = JBUI.size(480, 260)
        centerPanel = this
      }
  }

  @Composable
  fun ComposeContent(hasPermission: Boolean?, component: () -> JComponent) {
    Column {
      val plural = if (labs.size > 1) "s" else ""
      Text(
        "Enable Partner OEM Device Labs",
        style = Typography.h1TextStyle(),
        modifier = Modifier.padding(vertical = 10.dp),
      )
      Text(
        "One or more of the selected devices is hosted by a Partner OEM Device Lab. An Owner or Editor of " +
          "your Firebase project needs to enable the partner device lab$plural in Google Cloud Console."
      )
      Spacer(Modifier.size(20.dp))
      val labNames = labs.map { OemLabsAssetsRegistry.getInstance().retrieveName(it) }.distinct()
      Text("Required partner lab$plural: ${labNames.joinToString(", ")}")
      Spacer(Modifier.size(20.dp))

      Text(
        "Standard quota and pricing for Android Device Streaming also apply when using devices from a partner device lab.",
        Modifier.padding(bottom = 4.dp),
      )
      ExternalLink(
        "Learn more",
        onClick = {
          BrowserUtil.browse(
            "https://firebase.google.com/docs/test-lab/usage-quotas-pricing#device-streaming"
          )
        },
        Modifier.padding(2.dp),
      )
      Spacer(Modifier.size(20.dp))

      Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
          enabled = hasPermission == true,
          onClick = {
            runWithModalProgressBlocking(
              ModalTaskOwner.component(component()),
              "Continue in Cloud Console...",
              TaskCancellation.cancellable(),
            ) {
              startServerAndAwaitFirstCallback()
            }
          },
        ) {
          Text("Go to Google Cloud Console")
        }
        if (hasPermission == null) {
          CircularProgressIndicator(modifier = Modifier.padding(horizontal = 5.dp))
          Text(
            "Checking permissions...",
            color = StandardColors.DISABLED_TEXT_COLOR.toComposeColor(),
          )
        } else if (!hasPermission) {
          Icon(
            IntelliJIconKey.fromPlatformIcon(AllIcons.General.Warning),
            "Lab inaccessible",
            Modifier.padding(horizontal = 4.dp),
          )
          Text("Contact project administrator for access.")
        }
      }
    }
  }

  override fun createActions() = arrayOf(myOKAction)

  suspend fun startServerAndAwaitFirstCallback() {
    var server: Server? = null
    Disposer.register(disposable) { server?.stop() }
    var lock: Mutex? = Mutex(true)

    val port =
      Socket().use { s ->
        s.bind(null)
        s.localPort
      }

    val handler =
      object : AbstractHandler() {
        override fun handle(
          target: String?,
          request: HttpServletRequest,
          response: HttpServletResponse,
          dispatch: Int,
        ) {
          if (target == "/CALLBACK_Cloud_PartnerLab") {
            disposable.createCoroutineScope().launch {
              project
                .service<DirectAccessService>()
                .cloudProjectManager
                .value
                ?.accessibleDeviceInfoListFlow
                ?.refresh()
            }

            lock?.unlock()
            lock = null
            response.status = 200
          }
        }
      }

    // not sure why this is needed, it's obviously read by the disposable lambda above.
    @Suppress("AssignedValueIsNeverRead")
    server =
      Server(port).apply {
        for (c in connectors) {
          c.host = "localhost"
        }
        addHandler(handler)
        start()
      }
    BrowserUtil.browse(
      // If we're getting here the project should always be set, since otherwise you won't be seeing
      // unselected OEM lab devices.
      URI(
        "https://console.cloud.google.com/omnilab/partner-lab;localPort=$port;dlAction=AndroidStudioPartnerLabEnablement" +
          "?project=${project.service<DirectAccessService>().cloudProjectManager.value?.cloudProject?.name}"
      )
    )
    lock?.lock()
  }
}
