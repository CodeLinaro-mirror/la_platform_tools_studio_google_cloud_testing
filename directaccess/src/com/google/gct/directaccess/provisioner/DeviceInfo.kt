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

import com.android.sdklib.deviceprovisioner.DeviceType
import javax.swing.Icon

data class DeviceInfo(
  val id: String,
  val brand: String,
  val name: String,
  val labId: String,
  val manufacturer: String,
  val codename: String,
  val api: Int,
  val type: DeviceType,
  val formFactor: String,
  val screenX: Int,
  val screenY: Int,
  val screenDensity: Int,
  val deviceAvailabilityEstimateSeconds: Long?,
  val isDefault: Boolean = false,
  val isInCatalog: Boolean = true,
  val accessStatus: List<String> = listOf(),
  val tags: List<String> = listOf(),
) {
  /** A string key to distinguish itself from other [DeviceInfo]s. */
  val key = "$id/$api"
}

data class DeviceSelection(var isSelected: Boolean, val deviceInfo: DeviceInfo)

internal val DeviceInfo.icon: Icon
  get() {
    val iconType =
      when (type) {
        DeviceType.HANDHELD -> OemLabsAssetsRegistry.IconType.PHONE
        DeviceType.TV -> OemLabsAssetsRegistry.IconType.TV
        DeviceType.WEAR -> OemLabsAssetsRegistry.IconType.WEAR
        DeviceType.AUTOMOTIVE -> OemLabsAssetsRegistry.IconType.CAR
        DeviceType.XR_HEADSET -> OemLabsAssetsRegistry.IconType.XR_HEADSET
        DeviceType.AI_GLASSES -> OemLabsAssetsRegistry.IconType.AI_GLASSES
        else -> OemLabsAssetsRegistry.IconType.PHONE
      }

    return OemLabsAssetsRegistry.getInstance().retrieveIcon(labId, iconType).getIcon()
  }
