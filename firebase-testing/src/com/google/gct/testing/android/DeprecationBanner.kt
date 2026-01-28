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
package com.google.gct.testing.android

import com.intellij.ide.BrowserUtil
import com.intellij.ui.EditorNotificationPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.SwingConstants

class DeprecationBanner : EditorNotificationPanel(Status.Warning) {
  init {
    text =
      "<html>" +
        "Targeting Firebase Test Lab devices from a run configuration is now deprecated,<br>" +
        "and this functionality will be removed in a future release." +
        "</html>"

    createActionLabel(
      "More info",
      { BrowserUtil.browse("https://developer.android.com/studio/services/deprecated#narwhal-feature-drop") },
      false,
    )

    // Move the action labels to the south of the banner.
    if (myLinksPanel.parent.layout is BorderLayout) {
      myLabel.verticalTextPosition = SwingConstants.TOP
      myLinksPanel.parent.add(myLinksPanel, BorderLayout.SOUTH)
      // Align firstActionLabel vertically with myLabel.
      myLinksPanel.border = JBUI.Borders.empty(2, myLabel.icon.iconWidth + myLabel.iconTextGap - 2, 0, 0)
    }
  }
}
