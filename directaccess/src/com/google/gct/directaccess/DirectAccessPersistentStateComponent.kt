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
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.gct.directaccess.provisioner.DeviceSelection
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@State(
  name = "DeviceStreaming",
  storages = [Storage("caches/deviceStreaming.xml", roamingType = RoamingType.DISABLED)],
)
@Service(Service.Level.PROJECT)
class DirectAccessPersistentStateComponent(val project: Project) :
  SimplePersistentStateComponent<DirectAccessPersistentStateComponent.State>(State()) {
  class State : BaseState() {
    var selectedCloudProject by string("")
    var deviceSelectionList by list<PersistentDeviceSelectionData>()
  }

  val compatibleSelectedCloudProject: String
    get() =
      state.selectedCloudProject.takeIf { it?.isNotEmpty() == true }
        ?: project.service<DeprecatedDirectAccessPersistentStateComponent>().state.let {
          stateFromDeprecatedFile ->
          val result = stateFromDeprecatedFile.selectedCloudProject
          if (result?.isNotEmpty() == true) {
            stateFromDeprecatedFile.selectedCloudProject = ""
            result
          } else ""
        }

  val compatibleDeviceSelectionList: MutableList<PersistentDeviceSelectionData>
    get() =
      state.deviceSelectionList.takeIf { it.isNotEmpty() }
        ?: project.service<DeprecatedDirectAccessPersistentStateComponent>().state.let {
          stateFromDeprecatedFile ->
          val resultFromDeprecatedFile = stateFromDeprecatedFile.deviceSelectionList.toMutableList()
          if (resultFromDeprecatedFile.isNotEmpty()) {
            stateFromDeprecatedFile.deviceSelectionList = mutableListOf()
            resultFromDeprecatedFile
          } else mutableListOf()
        }
}

enum class DeviceType {
  PHONE,
  TV,
  WEAR_OS,
  AUTOMOTIVE;

  fun toProvisionerDeviceType() =
    when (this) {
      PHONE -> ProvisionerDeviceType.HANDHELD
      TV -> ProvisionerDeviceType.TV
      WEAR_OS -> ProvisionerDeviceType.WEAR
      AUTOMOTIVE -> ProvisionerDeviceType.AUTOMOTIVE
    }
}

fun ProvisionerDeviceType.toSerializationDeviceType(): DeviceType =
  when (this) {
    ProvisionerDeviceType.HANDHELD -> DeviceType.PHONE
    ProvisionerDeviceType.WEAR -> DeviceType.WEAR_OS
    ProvisionerDeviceType.TV -> DeviceType.TV
    ProvisionerDeviceType.AUTOMOTIVE -> DeviceType.AUTOMOTIVE
    ProvisionerDeviceType.DESKTOP -> DeviceType.PHONE
  }

data class PersistentDeviceSelectionData(
  var isSelected: Boolean = false,
  var id: String = "",
  var brand: String = "",
  var name: String = "",
  var manufacturer: String = "",
  var codename: String = "",
  var api: Int = 0,
  var type: DeviceType = DeviceType.PHONE,
  var screenX: Int = 0,
  var screenY: Int = 0,
  var screenDensity: Int = 0,
) {
  fun createDeviceSelection() =
    DeviceSelection(
      isSelected,
      DeviceInfo(
        id,
        brand,
        name,
        manufacturer,
        codename,
        api,
        type.toProvisionerDeviceType(),
        screenX,
        screenY,
        screenDensity,
        null,
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
    manufacturer,
    codename,
    api,
    type.toSerializationDeviceType(),
    screenX,
    screenY,
    screenDensity,
  )
