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

import com.android.tools.idea.devicemanager.DeviceType
import com.google.api.services.testing.model.AndroidModel
import com.google.api.services.testing.model.PerAndroidVersionInfo
import com.google.gct.login.GoogleLogin
import com.google.gct.testing.launcher.CloudAuthenticator
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.BuildNumber

object CatalogClient {

  private val logger = Logger.getInstance(CatalogClient::class.java)

  /** Returns available devices to be accessed directly. */
  fun getAvailableDevices(endpoint: String, cloudProject: String?): List<DeviceInfo> {
    if (!GoogleLogin.instance.isLoggedIn) {
      throw NotLoggedInException()
    }

    val catalog =
      CloudAuthenticator.getInstance().getAndroidDeviceCatalogForEnvironment(endpoint, cloudProject)

    return catalog.models
      .filter { it.form == "PHYSICAL" }
      .flatMap { model ->
        model.perVersionInfo
          ?.filter { perVersionInfo ->
            perVersionInfo.versionId?.toIntOrNull()?.let { it >= 26 } == true &&
              (isUnfilteredDevices() ||
                perVersionInfo.directAccessVersionInfo?.directAccessSupported == true &&
                  perVersionInfo.deviceCapacity != "DEVICE_CAPACITY_NONE" &&
                  BuildNumber.fromString(
                      perVersionInfo.directAccessVersionInfo.minimumAndroidStudioVersion
                    )
                    .let { catalogBuildNumber ->
                      catalogBuildNumber == null ||
                        catalogBuildNumber <= ApplicationInfo.getInstance().build
                    })
          }
          ?.mapNotNull { model.createDeviceInfo(it) } ?: listOf()
      }
  }

  private fun isUnfilteredDevices(): Boolean =
    System.getProperty("da_unfiltered_devices").toBoolean()

  private fun AndroidModel.createDeviceInfo(perVersionInfo: PerAndroidVersionInfo): DeviceInfo? {
    if (isAnyCriticalDeviceInfoValueNull()) return null
    val type =
      when (get("formFactor")) {
        // TODO(b/258705520) Move "TABLET" to a separate branch when DeviceType supports
        //                   tablets
        "PHONE",
        "TABLET" -> DeviceType.PHONE
        "WEARABLE" -> DeviceType.WEAR_OS
        else -> DeviceType.PHONE
      }
    val deviceAvailabilityEstimateSeconds =
      perVersionInfo.interactiveDeviceAvailabilityEstimate?.substringBefore("s")?.toLong()
    return DeviceInfo(
      id,
      brand,
      name,
      manufacturer,
      codename,
      perVersionInfo.versionId.toInt(),
      type,
      screenX,
      screenY,
      screenDensity,
      deviceAvailabilityEstimateSeconds,
    )
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

class NotLoggedInException : Exception("Not logged in")
