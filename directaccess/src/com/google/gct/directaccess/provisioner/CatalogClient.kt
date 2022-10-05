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
    return catalog.models.filter { it.form == "PHYSICAL" }.flatMap { model ->
      model.supportedVersionIds
        ?.filter { versionId -> versionId?.toIntOrNull()?.let { it >= 26 } == true }
        ?.map {
          DeviceInfo(model.brand, model.name, model.manufacturer, model.codename, it.toInt())
        }
        ?: listOf()
    }
  }
}

class NotLoggedInException : Exception("Not logged in")
