/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.google.gct.directaccess

import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.tools.idea.projectsystem.AndroidProjectSystem
import com.android.tools.idea.projectsystem.ProjectSystemService
import com.google.api.client.http.HttpHeaders
import com.google.api.client.http.HttpResponseException
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.ProjectRule
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class DirectAccessOnboardingServiceTest {

  @get:Rule val projectRule = ProjectRule()

  private val scope = CoroutineScope(SupervisorJob())

  @Test
  fun testNeedsTos() {
    val service = DirectAccessOnboardingService(scope)
    val ex403 = HttpResponseException.Builder(403, "Forbidden", HttpHeaders()).build()
    val ex400 = HttpResponseException.Builder(400, "Bad Request", HttpHeaders()).build()
    val ex500 = HttpResponseException.Builder(500, "Internal Error", HttpHeaders()).build()
    val otherEx = java.lang.RuntimeException()

    with(service) {
      assertThat(ex403.needsTos()).isTrue()
      assertThat(ex400.needsTos()).isTrue()
      assertThat(ex500.needsTos()).isFalse()
      assertThat(otherEx.needsTos()).isFalse()
    }
  }

  @Test
  fun testCancel() = runBlockingWithTimeout {
    val job = SupervisorJob()
    val testScope = CoroutineScope(job)
    val onboardingService = DirectAccessOnboardingService(testScope)

    var wasStarted = false
    var wasCancelled = false
    testScope.launch {
      try {
        wasStarted = true
        delay(10.seconds)
      } catch (_: CancellationException) {
        wasCancelled = true
      }
    }

    yieldUntil { wasStarted }
    onboardingService.cancel()
    yieldUntil { wasCancelled }
    assertThat(wasCancelled).isTrue()
  }

  @Test
  fun testCreateUniqueProjectId_normal() {
    val onboardingService = DirectAccessOnboardingService(scope)
    val mockProjectSystem = mock<AndroidProjectSystem>()
    whenever(mockProjectSystem.getKnownApplicationIds()).thenReturn(setOf("com.example.app"))
    ProjectSystemService.getInstance(projectRule.project).replaceProjectSystemForTests(mockProjectSystem)

    val method = DirectAccessOnboardingService::class.java.getDeclaredMethod("createUniqueProjectId")
    method.isAccessible = true
    val result = method.invoke(onboardingService) as String

    assertThat(result).startsWith("app-")
    assertThat(result.length).isEqualTo(3 + 1 + 8) // "app-" + 8-char UUID suffix
  }

  @Test
  fun testCreateUniqueProjectId_purelyNumeric() {
    val onboardingService = DirectAccessOnboardingService(scope)
    val mockProjectSystem = mock<AndroidProjectSystem>()
    whenever(mockProjectSystem.getKnownApplicationIds()).thenReturn(setOf("com.example.12345"))
    ProjectSystemService.getInstance(projectRule.project).replaceProjectSystemForTests(mockProjectSystem)

    val method = DirectAccessOnboardingService::class.java.getDeclaredMethod("createUniqueProjectId")
    method.isAccessible = true
    val result = method.invoke(onboardingService) as String

    // Purely numeric application IDs should fallback to "device-streaming" instead of crashing
    assertThat(result).startsWith("device-streaming-")
  }

  @Test
  fun testCreateUniqueProjectId_empty() {
    val onboardingService = DirectAccessOnboardingService(scope)
    val mockProjectSystem = mock<AndroidProjectSystem>()
    whenever(mockProjectSystem.getKnownApplicationIds()).thenReturn(emptySet())
    ProjectSystemService.getInstance(projectRule.project).replaceProjectSystemForTests(mockProjectSystem)

    val method = DirectAccessOnboardingService::class.java.getDeclaredMethod("createUniqueProjectId")
    method.isAccessible = true
    val result = method.invoke(onboardingService) as String

    assertThat(result).startsWith("device-streaming-")
  }
}
