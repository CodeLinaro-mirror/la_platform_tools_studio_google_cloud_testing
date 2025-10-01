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
package com.google.gct.directaccess.ui

import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.flags.junit.FlagRule
import com.android.tools.idea.flags.StudioFlags
import com.google.common.truth.Truth.assertThat
import com.google.gct.directaccess.provisioner.DirectAccessBannersManager
import com.google.gct.directaccess.provisioner.FETCH_INTERVAL_MILLIS
import com.google.gct.directaccess.provisioner.serviceKey
import com.intellij.testFramework.ProjectRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class DirectAccessBannersManagerTest {
  @get:Rule val projectRule = ProjectRule()
  @get:Rule val flagRule = FlagRule(StudioFlags.DIRECT_ACCESS_SHOW_OUTAGE_NOTIFICATIONS, true)

  private val enableBanner = MutableStateFlow(false)
  private val outageJson = MutableStateFlow("")
  private lateinit var bannersManager: DirectAccessBannersManager

  private val ignoredIncident =
    """
      {
        "id" : "myId1",
        "begin" : "2025-09-08T20:00:00+00:00",
        "end" : "2025-07-20T17:50:00+00:00",
        "created" : "2025-09-08T21:02:51+00:00",
        "external_desc" : "Firebase Test Lab is experiencing elevated retry rate and error rate.",
        "severity" : "medium",
        "service_key" : "XAmF3juu1qZ8jNAVhv29",
        "service_name" : "Test Lab",
        "uri" : "incidents/N98oujPhm8JBUfeKyGi8"
      }
    """
      .trimIndent()

  private val incident1 =
    """
      {
        "id" : "myId1",
        "begin" : "2025-09-08T20:00:00+00:00",
        "created" : "2025-09-08T21:02:51+00:00",
        "external_desc" : "Firebase Test Lab is experiencing elevated retry rate and error rate.",
        "severity" : "medium",
        "service_key" : "$serviceKey",
        "service_name" : "Test Lab",
        "uri" : "incidents/uri1"
      }
    """
      .trimIndent()

  private val incident2 =
    """
      {
        "id" : "myId2",
        "begin" : "2025-09-08T20:00:00+00:00",
        "created" : "2025-09-08T21:02:51+00:00",
        "external_desc" : "Firebase test lab is experiencing service disruptions due to issues in downstream services.",
        "severity" : "severe",
        "service_key" : "$serviceKey",
        "service_name" : "Test Lab",
        "uri" : "incidents/uri2"
      }
    """
      .trimIndent()

  @Test
  fun enablesSingleBanner() = runTest {
    outageJson.value = "[$ignoredIncident, $incident1]"
    enableBanner.value = true
    bannersManager =
      DirectAccessBannersManager(projectRule.project, backgroundScope, enableBanner) {
        outageJson.value
      }
    yieldUntil { bannersManager.banners.value.size == 1 }
    assertThat(bannersManager.banners.value.first().text)
      .contains("Firebase Test Lab is experiencing elevated retry rate and error rate.")
  }

  @Test
  fun enablesBanners() = runTest {
    outageJson.value = "[$incident1, $incident2]"
    enableBanner.value = true
    bannersManager =
      DirectAccessBannersManager(projectRule.project, backgroundScope, enableBanner) {
        outageJson.value
      }
    yieldUntil { bannersManager.banners.value.size == 1 }
    assertThat(bannersManager.banners.value.first().text)
      .contains("There are 2 incidents affecting Device Streaming.")
  }

  @Test
  fun outageInformationUpdated() = runTest {
    outageJson.value = "[$ignoredIncident, $incident1]"
    enableBanner.value = true
    bannersManager =
      DirectAccessBannersManager(projectRule.project, backgroundScope, enableBanner) {
        outageJson.value
      }
    // Yield to the jobs launched from scope while creating DirectAccessBannersManager.
    delay(1000)
    yieldUntil { bannersManager.banners.value.size == 1 }
    assertThat(bannersManager.banners.value.first().text)
      .contains("Firebase Test Lab is experiencing elevated retry rate and error rate.")

    outageJson.value = "[$ignoredIncident, $incident2]"
    delay(FETCH_INTERVAL_MILLIS)
    yieldUntil {
      bannersManager.banners.value
        .firstOrNull()
        ?.text
        ?.contains(
          "Firebase test lab is experiencing service disruptions due to issues in downstream services."
        ) == true
    }
  }
}
