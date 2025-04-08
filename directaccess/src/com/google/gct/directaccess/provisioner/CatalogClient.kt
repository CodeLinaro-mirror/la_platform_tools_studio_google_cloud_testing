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
import com.android.tools.idea.adddevicedialog.FormFactors
import com.google.api.services.testing.model.AndroidModel
import com.google.api.services.testing.model.LabInfo
import com.google.api.services.testing.model.PerAndroidVersionInfo
import com.google.gct.directaccess.CloudClientService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.text.nullize

object CatalogClient {

  private val logger = Logger.getInstance(CatalogClient::class.java)

  /** Returns devices available for streaming, filtered based on Studio version. */
  fun getAvailableDevices(endpoint: String, cloudProject: String?): List<DeviceInfo> =
    service<CloudClientService>().getAvailableDevices(endpoint, cloudProject).mapNotNull {
      (model, perVersionInfo) ->
      model.createDeviceInfo(perVersionInfo)
    }

  private fun AndroidModel.createDeviceInfo(perVersionInfo: PerAndroidVersionInfo): DeviceInfo? {
    if (isAnyCriticalDeviceInfoValueNull()) return null
    val type =
      when (formFactor) {
        // TODO(b/258705520) Move "TABLET" to a separate branch when DeviceType supports
        //                   tablets
        "PHONE",
        "TABLET" -> DeviceType.HANDHELD
        "WEARABLE" -> DeviceType.WEAR
        "XR" -> DeviceType.XR
        else -> DeviceType.HANDHELD
      }
    val provisionerFormFactor =
      when (formFactor) {
        "PHONE" -> FormFactors.PHONE
        "TABLET" -> FormFactors.TABLET
        "WEARABLE" -> FormFactors.WEAR
        "TV" -> FormFactors.TV
        "XR" -> FormFactors.XR
        else -> FormFactors.PHONE
      }
    val deviceAvailabilityEstimateSeconds =
      perVersionInfo.interactiveDeviceAvailabilityEstimate?.substringBefore("s")?.toLong()
    return DeviceInfo(
      id,
      brand,
      if (name.startsWith("$manufacturer ", true)) name.substring(manufacturer.length + 1)
      else name,
      labId =
        labInfo.getFormattedLabId().also {
          // We pre-populate here for the following usages
          OemLabsAssetsRegistry.getInstance().getAssetById(it)
        },
      manufacturer,
      codename,
      perVersionInfo.versionId.toInt(),
      type,
      provisionerFormFactor,
      screenX,
      screenY,
      screenDensity,
      deviceAvailabilityEstimateSeconds,
      tags?.contains("dda-default") == true,
      true,
      accessDeniedReasons ?: listOf(),
    )
  }

  private fun LabInfo?.getFormattedLabId(): String {
    return (this?.name?.nullize(true)
        ?: "Google") // Fallback here means google owned labs (i.e. Direct Access).
      .lowercase()
      .replace(Regex("[ -]"), "_")
  }

  private fun AndroidModel.isAnyCriticalDeviceInfoValueNull(): Boolean {
    val nullValue =
      when {
        id == null -> "id"
        brand == null -> "brand"
        name == null -> "name"
        manufacturer == null -> "manufacturer"
        codename == null -> "codename"
        screenX == null -> "screenX"
        screenY == null -> "screenY"
        screenDensity == null -> "screenDensity"
        else -> null
      }

    return if (nullValue == null) {
      false
    } else {
      logger.warn("$nullValue is null for $this")
      true
    }
  }
}
