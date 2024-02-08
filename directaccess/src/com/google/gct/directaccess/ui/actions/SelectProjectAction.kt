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
package com.google.gct.directaccess.ui.actions

import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.settings.DirectAccessConfiguration
import com.google.gct.directaccess.ui.SelectDeviceDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.ui.LayeredIcon.Companion.layeredIcon
import icons.FirebaseIcons
import icons.StudioIcons
import org.jetbrains.annotations.VisibleForTesting

@VisibleForTesting
val firebaseIconWithErrors = layeredIcon {
  arrayOf(FirebaseIcons.ACTION_ICON, StudioIcons.Emulator.Snapshots.INVALID_SNAPSHOT_DECORATOR)
}

class SelectProjectAction :
  AnAction("Configure Device Streaming Project", "text", FirebaseIcons.ACTION_ICON) {
  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    // An exception will be caught here only when a project is selected and its cloudProjectManager
    // fails to fetch reservations.
    val hasErrorAfterProjectSelection =
      e.project
        ?.service<DirectAccessService>()
        ?.cloudProjectManager
        ?.value
        ?.reservationListFlowWithException
        ?.value
        ?.second != null
    e.presentation.icon =
      if (hasErrorAfterProjectSelection) firebaseIconWithErrors else FirebaseIcons.ACTION_ICON
    e.presentation.isVisible = service<DirectAccessConfiguration>().isEnabled
  }

  override fun actionPerformed(e: AnActionEvent) {
    SelectDeviceDialog(e.project!!).show()
  }
}
