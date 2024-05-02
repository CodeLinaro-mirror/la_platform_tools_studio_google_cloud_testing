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

import com.android.tools.idea.deviceprovisioner.DeviceProvisionerService
import com.android.tools.idea.flags.StudioFlags
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.provisioner.DirectAccessDeviceTemplate
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
  AnAction(
    "Configure Device Streaming Project",
    "Open the Device Streaming dialog to select Firebase project and devices",
    FirebaseIcons.ACTION_ICON,
  ) {
  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    if (e.project == null) {
      e.presentation.isVisible = false
      return
    }
    // An exception will be caught only when all selected templates are not disabled.
    val templates =
      e.project
        ?.service<DeviceProvisionerService>()
        ?.deviceProvisioner
        ?.templates
        ?.value
        ?.filterIsInstance<DirectAccessDeviceTemplate>() ?: listOf()
    val accessibleDevices =
      e.project
        ?.service<DirectAccessService>()
        ?.cloudProjectManager
        ?.value
        ?.accessibleDeviceInfoListFlow
        ?.stateFlow
        ?.value
        ?.map { it.key }
        ?.toSet() ?: setOf()
    e.presentation.icon =
      if (templates.isNotEmpty() && templates.none { it.deviceInfo.key in accessibleDevices })
        firebaseIconWithErrors
      else FirebaseIcons.ACTION_ICON
    e.presentation.isVisible = StudioFlags.DIRECT_ACCESS.get()
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project =
      e.project ?: throw IllegalArgumentException("Project required to invoke this action")
    SelectDeviceDialog(project).show()
  }
}
