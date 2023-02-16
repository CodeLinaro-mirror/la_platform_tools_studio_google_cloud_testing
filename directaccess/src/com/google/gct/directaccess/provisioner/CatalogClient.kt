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
import com.google.gct.login.GoogleLogin
import com.google.gct.testing.launcher.CloudAuthenticator

object CatalogClient {

  /**
   * Returns available devices to be access directly.
   *
   * TODO: apply real filters when available
   */
  fun getAvailableDevices(endpoint: String): List<DeviceInfo> {
    if (!GoogleLogin.instance.isLoggedIn) {
      throw NotLoggedInException()
    }
    val catalog =
      (CloudAuthenticator.getInstance().getAndroidDeviceCatalogForEnvironment(endpoint)
        ?: throw Exception("Error fetching catalog."))

    val eapFilter =
      StudioFlags.DIRECT_ACCESS_DEVICE_FILTER.get()
        .split(",")
        .filterNot { it.isEmpty() }
        .groupBy({ it.substringBefore("/") }) { it.substringAfter("/") }

    return catalog.models
      .filter { it.form == "PHYSICAL" }
      .flatMap { model ->
        model.supportedVersionIds
          ?.filter { versionId -> versionId?.toIntOrNull()?.let { it >= 26 } == true }
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
          ?.filter {
            eapFilter.isEmpty() || eapFilter[it.codename]?.contains(it.api.toString()) == true
          }
          ?: listOf()
      }
  }
}

class NotLoggedInException : Exception("Not logged in")
