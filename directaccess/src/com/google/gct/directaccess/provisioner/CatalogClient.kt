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
import com.google.gct.login.GoogleLogin
import com.google.gct.testing.launcher.CloudAuthenticator
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.util.BuildNumber

object CatalogClient {

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
        model.supportedVersionIds
          ?.filter { versionId ->
            versionId?.toIntOrNull()?.let { it >= 26 } == true &&
              model.perVersionInfo?.any {
                it.versionId == versionId &&
                  it.directAccessVersionInfo?.directAccessSupported == true &&
                  BuildNumber.fromString(it.directAccessVersionInfo.minimumAndroidStudioVersion)
                    .let { catalogBuildNumber ->
                      catalogBuildNumber == null ||
                        catalogBuildNumber <= ApplicationInfo.getInstance().build
                    }
              }
                ?: false
          }
          ?.map {
            val type =
              when (model["formFactor"]) {
                // TODO(b/258705520) Move "TABLET" to a separate branch when DeviceType supports
                // tablets
                "PHONE",
                "TABLET" -> DeviceType.PHONE
                "WEARABLE" -> DeviceType.WEAR_OS
                else -> DeviceType.PHONE
              }
            DeviceInfo(
              model.brand,
              model.name,
              model.manufacturer,
              model.codename,
              it.toInt(),
              type
            )
          }
          ?: listOf()
      }
  }
}

class NotLoggedInException : Exception("Not logged in")
