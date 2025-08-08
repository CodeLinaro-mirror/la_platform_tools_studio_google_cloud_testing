/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.google.gct.directaccess

import com.android.sdklib.deviceprovisioner.DeviceType as ProvisionerDeviceType
import com.android.tools.idea.adddevicedialog.FormFactors
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.gct.directaccess.provisioner.DeviceSelection
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@State(
  name = "DeviceStreaming",
  storages = [Storage("caches/deviceStreaming.xml", roamingType = RoamingType.DISABLED)],
)
@Service(Service.Level.PROJECT)
class DirectAccessPersistentStateComponent(val project: Project) :
  SimplePersistentStateComponent<DirectAccessPersistentStateComponent.State>(State()) {
  class State : BaseState() {
    var defaultDeviceApplied by property(false)
    var selectedCloudProject by string("")
    var deviceSelectionList by list<PersistentDeviceSelectionData>()
  }

  val selectedCloudProject: String
    get() = state.selectedCloudProject ?: ""

  val deviceSelectionList: MutableList<PersistentDeviceSelectionData>
    get() = state.deviceSelectionList
}

enum class DeviceType {
  PHONE,
  TV,
  WEAR_OS,
  AUTOMOTIVE,
  XR_HEADSET,
  XR_GLASSES;

  fun toProvisionerDeviceType() =
    when (this) {
      PHONE -> ProvisionerDeviceType.HANDHELD
      TV -> ProvisionerDeviceType.TV
      WEAR_OS -> ProvisionerDeviceType.WEAR
      AUTOMOTIVE -> ProvisionerDeviceType.AUTOMOTIVE
      XR_HEADSET -> ProvisionerDeviceType.XR_HEADSET
      XR_GLASSES -> ProvisionerDeviceType.XR_GLASSES
    }
}

fun ProvisionerDeviceType.toSerializationDeviceType(): DeviceType =
  when (this) {
    ProvisionerDeviceType.HANDHELD -> DeviceType.PHONE
    ProvisionerDeviceType.WEAR -> DeviceType.WEAR_OS
    ProvisionerDeviceType.TV -> DeviceType.TV
    ProvisionerDeviceType.AUTOMOTIVE -> DeviceType.AUTOMOTIVE
    ProvisionerDeviceType.DESKTOP -> DeviceType.PHONE
    ProvisionerDeviceType.XR_HEADSET -> DeviceType.XR_HEADSET
    ProvisionerDeviceType.XR_GLASSES -> DeviceType.XR_GLASSES
  }

data class PersistentDeviceSelectionData(
  var isSelected: Boolean = false,
  var id: String = "",
  var brand: String = "",
  var name: String = "",
  var labId: String = "",
  var manufacturer: String = "",
  var codename: String = "",
  var api: Int = 0,
  var type: DeviceType = DeviceType.PHONE,
  var formFactor: String = FormFactors.PHONE,
  var screenX: Int = 0,
  var screenY: Int = 0,
  var screenDensity: Int = 0,
  var isDefault: Boolean = false,
  var accessStatus: List<String> = listOf(),
) {
  fun createDeviceSelection() =
    DeviceSelection(
      isSelected,
      DeviceInfo(
        id,
        brand,
        name,
        labId,
        manufacturer,
        codename,
        api,
        type.toProvisionerDeviceType(),
        formFactor,
        screenX,
        screenY,
        screenDensity,
        null,
        isDefault,
        true,
        accessStatus,
      ),
    )
}

fun DeviceSelection.toPersistentDeviceSelectionData() =
  deviceInfo.createPersistentDeviceSelectionData(isSelected)

fun DeviceInfo.createPersistentDeviceSelectionData(isSelected: Boolean) =
  PersistentDeviceSelectionData(
    isSelected,
    id,
    brand,
    name,
    labId,
    manufacturer,
    codename,
    api,
    type.toSerializationDeviceType(),
    formFactor,
    screenX,
    screenY,
    screenDensity,
    isDefault,
    accessStatus,
  )
