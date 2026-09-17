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
import com.google.gct.directaccess.CloudClientService
import com.google.services.firebase.directaccess.client.api.AndroidModel
import com.google.services.firebase.directaccess.client.api.LabInfo
import com.google.services.firebase.directaccess.client.api.PerAndroidVersionInfo
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.text.nullize
import kotlin.reflect.KMutableProperty0

object CatalogClient {

  private val logger = Logger.getInstance(CatalogClient::class.java)

  /** Returns devices available for streaming, filtered based on Studio version. */
  fun getAvailableDevices(endpoint: String, cloudProject: String?): List<DeviceInfo> =
    service<CloudClientService>().getAvailableDevices(endpoint, cloudProject).mapNotNull { (model, perVersionInfo) ->
      model.createDeviceInfo(perVersionInfo)
    }

  private fun AndroidModel.createDeviceInfo(perVersionInfo: PerAndroidVersionInfo): DeviceInfo? {
    fun <T> getOrLog(field: KMutableProperty0<T?>): T? {
      return field.get()
        ?: run {
          logger.warn("${field.name} is null for $this")
          return null
        }
    }

    val id = getOrLog(::id) ?: return null
    val brand = getOrLog(::brand) ?: return null
    val name = getOrLog(::name) ?: return null
    val manufacturer = getOrLog(::manufacturer) ?: return null
    val codename = getOrLog(::codename) ?: return null
    val screenX = getOrLog(::screenX) ?: return null
    val screenY = getOrLog(::screenY) ?: return null
    val screenDensity = getOrLog(::screenDensity) ?: return null
    val versionId =
      perVersionInfo.versionId?.toIntOrNull()
        ?: run {
          logger.warn("versionId is null or invalid for $this")
          return null
        }

    val type =
      when (formFactor) {
        // TODO(b/258705520) Move "TABLET" to a separate branch when DeviceType supports
        //                   tablets
        "PHONE",
        "TABLET" -> DeviceType.HANDHELD
        "WEARABLE" -> DeviceType.WEAR
        "TV" -> DeviceType.TV
        "AUTOMOTIVE" -> DeviceType.AUTOMOTIVE
        "DESKTOP" -> DeviceType.DESKTOP
        // TODO(b/433571712) provide more accurate device type for XR devices
        "XR" -> DeviceType.XR_HEADSET
        else -> DeviceType.HANDHELD
      }
    val provisionerFormFactor =
      when (formFactor) {
        "PHONE" -> FormFactors.PHONE
        "TABLET" -> FormFactors.TABLET
        "WEARABLE" -> FormFactors.WEAR
        "TV" -> FormFactors.TV
        "AUTOMOTIVE" -> FormFactors.AUTO
        "DESKTOP" -> FormFactors.DESKTOP
        "XR" -> FormFactors.XR
        else -> FormFactors.PHONE
      }
    val deviceAvailabilityEstimateSeconds = perVersionInfo.interactiveDeviceAvailabilityEstimate?.substringBefore("s")?.toLongOrNull()
    return DeviceInfo(
      id,
      brand,
      if (name.startsWith("$manufacturer ", true)) name.substring(manufacturer.length + 1) else name,
      labId =
        labInfo.getFormattedLabId().also {
          // We pre-populate here for the following usages
          OemLabsAssetsRegistry.getInstance().getAssetById(it)
        },
      manufacturer,
      codename,
      versionId,
      type,
      provisionerFormFactor,
      screenX,
      screenY,
      screenDensity,
      deviceAvailabilityEstimateSeconds,
      tags?.contains("dda-default") == true,
      true,
      accessDeniedReasons ?: listOf(),
      tags ?: listOf(),
    )
  }

  private fun LabInfo?.getFormattedLabId(): String {
    return (this?.name?.nullize(true) ?: "Google") // Fallback here means google owned labs (i.e. Direct Access).
      .lowercase()
      .replace(Regex("[ -]"), "_")
  }
}
