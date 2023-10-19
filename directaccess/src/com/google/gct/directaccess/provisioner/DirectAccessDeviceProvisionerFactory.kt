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
package com.google.gct.directaccess.provisioner

import com.android.sdklib.deviceprovisioner.DeviceProvisionerPlugin
import com.android.tools.idea.deviceprovisioner.DeviceProvisionerFactory
import com.android.tools.idea.flags.ExternalSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

class DirectAccessDeviceProvisionerFactory : DeviceProvisionerFactory {
  override val isEnabled: Boolean
    get() = service<ExternalSettings>().enableDeviceStreaming

  override fun create(coroutineScope: CoroutineScope, project: Project): DeviceProvisionerPlugin =
    DirectAccessDeviceProvisionerPlugin(coroutineScope, project)
}
