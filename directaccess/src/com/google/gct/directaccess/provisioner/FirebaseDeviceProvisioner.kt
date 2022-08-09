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

import com.android.adblib.ConnectedDevice
import com.android.sdklib.deviceprovisioner.ActivationAction
import com.android.sdklib.deviceprovisioner.ActivationParams
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceProvisionerPlugin
import com.android.sdklib.deviceprovisioner.DeviceTemplate
import com.android.sdklib.deviceprovisioner.EditTemplateAction
import com.google.services.firebase.directaccess.client.catalog.FirebaseDirectAccessClient
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Provides access to physical devices run by Firebase. Supports configuring Firebase device
 * templates and activating / deactivating them.
 */
class FirebaseDeviceProvisioner(val project: Project) : DeviceProvisionerPlugin {
  // TODO: find a proper priority
  override val priority: Int = 120
  override suspend fun claim(device: ConnectedDevice): Boolean {
    return false
  }

  private val _devices = MutableStateFlow(emptyList<DeviceHandle>())
  override val devices: StateFlow<List<DeviceHandle>> = _devices.asStateFlow()
  private val _templates = MutableStateFlow(emptyList<DeviceTemplate>())
  override val templates: StateFlow<List<DeviceTemplate>> = _templates.asStateFlow()

  init {
    _templates.value =
      FirebaseDirectAccessClient.availableDevices.map { info ->
        object : DeviceTemplate {
          override val displayName: String = "${info.manufacturer} ${info.codename}"
          override val activationAction: ActivationAction
            get() =
              object : ActivationAction {
                override suspend fun activate(params: ActivationParams) = Unit

                override val label: String = "Acquire"
                override val isEnabled: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
              }

          override val editAction: EditTemplateAction
            get() =
              object : EditTemplateAction {
                override suspend fun edit() = Unit
                override val label: String = "Edit"
                override val isEnabled: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
              }
        }
      }
  }
}
