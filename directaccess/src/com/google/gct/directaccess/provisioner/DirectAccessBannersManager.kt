/*
 * Copyright (C) 2025 The Android Open Source Project
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

import com.android.tools.idea.flags.StudioFlags
import com.google.common.annotations.VisibleForTesting
import com.google.gct.directaccess.DirectAccessDeprecationState
import com.google.gct.directaccess.ui.DirectAccessDeprecationBanner
import com.google.gct.directaccess.ui.DirectAccessIncidentBanner
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorNotificationPanel
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val INCIDENT_URL = "https://status.firebase.google.com/incidents.json"
private const val SERVICE_KEY = "XAmF3juu1qZ8jNAVhv29"
@VisibleForTesting val FETCH_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(20)

class DirectAccessBannersManager(
  project: Project,
  scope: CoroutineScope,
  enableBanner: Flow<Boolean>,
  outageJsonText: () -> String = { URL(INCIDENT_URL).readText() },
) {

  val banners = MutableStateFlow(listOf<EditorNotificationPanel>())
  private val mutex = Mutex(false)
  private var deprecationBanner: DirectAccessDeprecationBanner? = null
  private var incidentBanner: DirectAccessIncidentBanner? = null

  init {
    scope.launch {
      enableBanner.distinctUntilChanged().collectLatest { enabled ->
        if (enabled) {
          // Update banners from deprecation data.
          launch {
            service<DirectAccessDeprecationState>().serviceDeprecationData.collect { data ->
              mutex.withLock {
                deprecationBanner =
                  if (data.isSupported()) null else DirectAccessDeprecationBanner(project, data)
                banners.value =
                  listOfNotNull(deprecationBanner, incidentBanner.takeIf { !data.isUnsupported() })
              }
            }
          }
          // Update banners from INCIDENT_URL.
          if (StudioFlags.DIRECT_ACCESS_SHOW_OUTAGE_NOTIFICATIONS.get()) {
            launch {
              while (coroutineContext.isActive) {
                try {
                  val incidents =
                    Gson().fromJson(outageJsonText(), JsonArray::class.java).filter { json ->
                      json is JsonObject &&
                        json["service_key"]?.asString == SERVICE_KEY &&
                        !json.has("end")
                    }
                  mutex.withLock {
                    incidentBanner =
                      if (incidents.isEmpty()) null else DirectAccessIncidentBanner(incidents)
                    banners.value = listOfNotNull(deprecationBanner, incidentBanner)
                  }
                } catch (e: IOException) {
                  thisLogger().warn(e)
                }
                delay(FETCH_INTERVAL_MILLIS)
              }
            }
          }
        } else {
          mutex.withLock {
            deprecationBanner = null
            incidentBanner = null
            banners.value = listOf()
          }
        }
      }
    }
  }
}
