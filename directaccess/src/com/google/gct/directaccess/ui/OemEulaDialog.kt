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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.tools.adtui.compose.LocalProject
import com.android.tools.adtui.compose.StudioComposePanel
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import java.net.URI
import javax.swing.Action
import org.jetbrains.jewel.ui.component.ExternalLink
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Typography

class OemEulaDialog(val labs: List<String>, val project: Project) : DialogWrapper(project, true) {
  init {
    init()
    myOKAction.putValue(Action.NAME, "Done")
  }

  override fun createCenterPanel() =
    StudioComposePanel {
        CompositionLocalProvider(LocalProject provides project) { ComposeContent() }
      }
      .apply {
        preferredSize = JBUI.size(480, 260)
        minimumSize = JBUI.size(480, 260)
      }

  @Composable
  fun ComposeContent() {
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
      Text("Required partner lab$plural: ${labs.joinToString(", ")}")
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
      // TODO: check permission before showing button
      OutlinedButton(
        onClick = {
          // TODO: start listener for redirect back from pantheon and include port
          BrowserUtil.browse(
            URI(
              "https://console.cloud.google.com/omnilab/partner-lab;dlAction=AndroidStudioPartnerLabEnablement" +
                // TODO: remove experiment param
                "?e=OmnilabLaunch::OmnilabEnabled"
            )
          )
        }
      ) {
        Text("Go to Google Cloud Console")
      }
    }
  }

  override fun createActions() = arrayOf(myOKAction)
}
