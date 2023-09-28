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
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.serverflags.ServerFlagService
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
              // TODO(b/292642744): Remove isUnfilteredDevices when a more robust solution is
              //                    implemented.
              (isUxr202309Device(model, perVersionInfo) ||
                isUnfilteredDevices() ||
                perVersionInfo.directAccessVersionInfo?.directAccessSupported == true &&
                  BuildNumber.fromString(
                      perVersionInfo.directAccessVersionInfo.minimumAndroidStudioVersion
                    )
                    .let { catalogBuildNumber ->
                      catalogBuildNumber == null ||
                        catalogBuildNumber <= ApplicationInfo.getInstance().build
                    })
          }
          ?.mapNotNull {
            try {
              model.createDeviceInfo(it.versionId.toInt())
            } catch (e: Exception) {
              logger.info("Could not create DeviceInfo for model: $model", e)
              null
            }
          } ?: listOf()
      }
  }

  private fun isUxr202309Device(model: AndroidModel, perVersionInfo: PerAndroidVersionInfo) =
    StudioFlags.USE_UXR_202309_FILTER.get() &&
      uxr202309Filter[model.codename]?.contains(perVersionInfo.versionId) == true

  private val uxr202309Filter =
    (ServerFlagService.instance.getString("directaccess/uxr202309Filter") ?: "")
      .split(",")
      .filterNot { it.isEmpty() }
      .groupBy({ it.substringBefore("/") }) { it.substringAfter("/") }

  private fun isUnfilteredDevices(): Boolean =
    System.getProperty("da_unfiltered_devices").toBoolean()

  private fun AndroidModel.createDeviceInfo(api: Int): DeviceInfo {
    val type =
      when (get("formFactor")) {
        // TODO(b/258705520) Move "TABLET" to a separate branch when DeviceType supports
        //                   tablets
        "PHONE",
        "TABLET" -> DeviceType.PHONE
        "WEARABLE" -> DeviceType.WEAR_OS
        else -> DeviceType.PHONE
      }
    return DeviceInfo(
      id,
      brand,
      name,
      manufacturer,
      codename,
      api,
      type,
      screenX,
      screenY,
      screenDensity
    )
  }
}

class NotLoggedInException : Exception("Not logged in")
