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

import com.google.gct.testing.launcher.CloudAuthenticator
import com.google.services.firebase.directaccess.client.DeviceInfo

object CatalogClient {

  /**
   * Returns available devices to be access directly.
   *
   * TODO: apply real filters when available
   */
  fun getAvailableDevices(endpoint: String): List<DeviceInfo> =
    (CloudAuthenticator.getInstance().getAndroidDeviceCatalogForEnvironment(endpoint)
        ?: throw Exception("Error fetching catalog.")).models
      .filter {
        it.form == "PHYSICAL" && (it["supportedAbis"] as? List<*>)?.contains("arm64-v8a") == true
      }
      .flatMap { model ->
        model.supportedVersionIds
          ?.filter { versionId -> versionId?.toIntOrNull()?.let { it >= 29 } == true }
          ?.map {
            DeviceInfo(model.brand, model.name, model.manufacturer, model.codename, it.toInt())
          }
          ?: listOf()
      }
}
