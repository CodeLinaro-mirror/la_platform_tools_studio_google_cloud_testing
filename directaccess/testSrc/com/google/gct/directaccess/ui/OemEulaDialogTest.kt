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

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.android.testutils.VirtualTimeScheduler
import com.android.testutils.waitForCondition
import com.android.tools.adtui.compose.utils.StudioComposeTestRule.Companion.createStudioComposeTestRule
import com.android.tools.analytics.TestUsageTracker
import com.android.tools.analytics.UsageTracker
import com.android.tools.idea.testing.HeadlessTaskSupportRule
import com.android.tools.idea.testing.disposable
import com.google.common.truth.Truth.assertThat
import com.google.gct.directaccess.CloudClientService
import com.google.gct.directaccess.CloudProjectEntry
import com.google.gct.directaccess.DirectAccessCloudProjectManager
import com.google.gct.directaccess.DirectAccessService
import com.google.gson.Gson
import com.google.services.firebase.directaccess.client.CloudClient
import com.google.services.firebase.directaccess.client.api.TestIamPermissionsRequest
import com.google.services.firebase.directaccess.client.api.TestIamPermissionsResponse
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent
import com.google.wireless.android.sdk.stats.DirectAccessUsageEventKt.oemLabDialogDetails
import com.google.wireless.android.sdk.stats.directAccessUsageEvent
import com.intellij.ide.browsers.BrowserLauncher
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.replaceService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.LocalComponent
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@Suppress("UnstableApiUsage")
@OptIn(ExperimentalJewelApi::class, ExperimentalTestApi::class)
class OemEulaDialogTest {

  val projectRule = ProjectRule()
  val composeRule = createStudioComposeTestRule()
  val projectManagerFlow = MutableStateFlow<DirectAccessCloudProjectManager?>(null)

  @get:Rule val chain = RuleChain(projectRule, HeadlessTaskSupportRule(), composeRule)

  @Before
  fun setUp() {
    val mockService: DirectAccessService = mock()
    val mockProjectManager: DirectAccessCloudProjectManager = mock()
    whenever(mockService.cloudProjectManager).thenReturn(projectManagerFlow)
    projectManagerFlow.value = mockProjectManager
    whenever(mockProjectManager.cloudProject).thenReturn(CloudProjectEntry("myUser", "myProject"))
    projectRule.project.replaceService(DirectAccessService::class.java, mockService, projectRule.disposable)
  }

  @Test
  fun testCheckResultMetrics() =
    runTest(timeout = 10.seconds) {
      for ((result, metric) in
        listOf(
          CompletableFuture.completedFuture(true) to DirectAccessUsageEvent.OemLabDialogDetails.AccessCheckResult.ACCESS,
          CompletableFuture.completedFuture(false) to DirectAccessUsageEvent.OemLabDialogDetails.AccessCheckResult.NO_ACCESS,
          CompletableFuture.failedFuture<Boolean>(Exception("expected")) to
            DirectAccessUsageEvent.OemLabDialogDetails.AccessCheckResult.CHECK_FAILED,
        )) {
        val tracker = TestUsageTracker(VirtualTimeScheduler())
        UsageTracker.setWriterForTest(tracker)
        val disposable = Disposer.newDisposable()
        val content = OemEulaContent(listOf("myLab", "myLab2"), disposable, projectRule.project) { result.get() }
        composeRule.setContent { CompositionLocalProvider(LocalComponent provides mock()) { content.ComposeContent() } }
        Disposer.dispose(disposable)
        waitForCondition(1.seconds) { tracker.usages.isNotEmpty() }
        assertThat(tracker.usages.first().studioEvent.directAccessUsageEvent)
          .isEqualTo(
            directAccessUsageEvent {
              type = DirectAccessUsageEvent.DirectAccessUsageEventType.OEM_LAB_DIALOG
              oemLabDialogDetails = oemLabDialogDetails {
                receivedCallback = false
                clickedCloudConsoleButton = false
                accessCheckResult = metric
              }
            }
          )
      }
    }

  @Test
  fun testConsoleButtonClickMetric() =
    runTest(timeout = 10.seconds) {
      val browserLauncher: BrowserLauncher = mock()
      val disposable = Disposer.newDisposable()

      // Wait for the link to be clicked, then close the dialog
      whenever(browserLauncher.browse(any<URI>())).thenAnswer { Disposer.dispose(disposable) }
      ApplicationManager.getApplication().replaceService(BrowserLauncher::class.java, browserLauncher, projectRule.disposable)
      val tracker = TestUsageTracker(VirtualTimeScheduler())
      UsageTracker.setWriterForTest(tracker)
      val mutex = Mutex(true)
      val content =
        OemEulaContent(listOf("myLab", "myLab2"), disposable, projectRule.project) {
          mutex.unlock()
          true
        }
      composeRule.setContent { CompositionLocalProvider(LocalComponent provides mock()) { content.ComposeContent() } }
      // Wait for the permission check to complete
      mutex.lock()
      // click the link
      composeRule.onNodeWithText("Enable in Google Cloud Console...").performClick()
      waitForCondition(1.seconds) { tracker.usages.isNotEmpty() }

      val actual = tracker.usages.first().studioEvent.directAccessUsageEvent
      val expected = directAccessUsageEvent {
        type = DirectAccessUsageEvent.DirectAccessUsageEventType.OEM_LAB_DIALOG
        oemLabDialogDetails = oemLabDialogDetails {
          receivedCallback = false
          clickedCloudConsoleButton = true
          accessCheckResult = DirectAccessUsageEvent.OemLabDialogDetails.AccessCheckResult.ACCESS
        }
      }
      assertThat(actual).isEqualTo(expected)
    }

  @Test
  fun testReceivedCallbackMetric() =
    runTest(timeout = 10.seconds) {
      val browserLauncher: BrowserLauncher = mock()
      val disposable = Disposer.newDisposable()

      // Wait for the link to be clicked, then generate the callback
      whenever(browserLauncher.browse(any<URI>())).thenAnswer { invocation ->
        val port = invocation.getArgument<URI>(0).path.substringAfter("localPort=").substringBefore(";")
        HttpClient.newHttpClient()
          .send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:$port/CALLBACK_Cloud_PartnerLab")).build(),
            HttpResponse.BodyHandlers.discarding(),
          )
        Disposer.dispose(disposable)
      }
      ApplicationManager.getApplication().replaceService(BrowserLauncher::class.java, browserLauncher, projectRule.disposable)
      val tracker = TestUsageTracker(VirtualTimeScheduler())
      UsageTracker.setWriterForTest(tracker)
      val mutex = Mutex(true)
      val content =
        OemEulaContent(listOf("myLab", "myLab2"), disposable, projectRule.project) {
          mutex.unlock()
          true
        }
      composeRule.setContent { CompositionLocalProvider(LocalComponent provides mock()) { content.ComposeContent() } }
      // Wait for the permission check to complete
      mutex.lock()
      composeRule.waitForIdle()
      // click the link
      composeRule.onNodeWithText("Enable in Google Cloud Console...").performClick()
      waitForCondition(1.seconds) { tracker.usages.isNotEmpty() }

      val actual = tracker.usages.first().studioEvent.directAccessUsageEvent
      val expected = directAccessUsageEvent {
        type = DirectAccessUsageEvent.DirectAccessUsageEventType.OEM_LAB_DIALOG
        oemLabDialogDetails = oemLabDialogDetails {
          receivedCallback = true
          clickedCloudConsoleButton = true
          accessCheckResult = DirectAccessUsageEvent.OemLabDialogDetails.AccessCheckResult.ACCESS
        }
      }
      assertThat(actual).isEqualTo(expected)
    }

  @Test
  fun testPermissionCheckCall_hasPermission() =
    runTest(timeout = 10.seconds) {
      val permissions = listOf("resourcemanager.projects.update")
      val mockHttpClient: HttpClient = mockClient(permissions)

      val content = OemEulaContent(listOf("myLab", "myLab2"), projectRule.disposable, projectRule.project)
      assertThat(content.permissionChecker(CloudProjectEntry("myUser", "myProject"))).isTrue()

      val requestCaptor = argumentCaptor<HttpRequest>()
      verify(mockHttpClient).send(requestCaptor.capture(), any<HttpResponse.BodyHandler<String>>())
      val request = requestCaptor.firstValue

      val requestBody = Gson().fromJson(getRequestBody(request), TestIamPermissionsRequest::class.java)
      assertThat(requestBody.permissions).isEqualTo(permissions)
      assertThat(request.uri().toString()).isEqualTo("https://cloudresourcemanager.googleapis.com/v3/projects/myProject:testIamPermissions")
    }

  @Test
  fun testPermissionCheckCall_noPermission() =
    runTest(timeout = 10.seconds) {
      mockClient(listOf())
      val content = OemEulaContent(listOf("myLab", "myLab2"), projectRule.disposable, projectRule.project)
      assertThat(content.permissionChecker(CloudProjectEntry("myUser", "myProject"))).isFalse()
    }

  private fun mockClient(permissions: List<String>): HttpClient {
    val mockHttpClient: HttpClient = mock()
    val mockResponse: HttpResponse<String> = mock()
    whenever(mockResponse.statusCode()).thenReturn(200)
    whenever(mockResponse.body()).thenReturn(Gson().toJson(TestIamPermissionsResponse(permissions)))
    whenever(mockHttpClient.send(any(), any<HttpResponse.BodyHandler<String>>())).thenReturn(mockResponse)

    Disposer.register(projectRule.disposable) { CloudClientService.instance().overrideClientForTest = null }
    CloudClientService.instance().overrideClientForTest = CloudClient({ "faketoken" }, overrideHttpClient = mockHttpClient)
    return mockHttpClient
  }

  private fun getRequestBody(request: HttpRequest): String {
    val publisher = request.bodyPublisher().orElse(null) ?: return ""
    val byteArrayOutputStream = java.io.ByteArrayOutputStream()
    publisher.subscribe(
      object : java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
        override fun onSubscribe(subscription: java.util.concurrent.Flow.Subscription) {
          subscription.request(Long.MAX_VALUE)
        }

        override fun onNext(item: java.nio.ByteBuffer) {
          val bytes = ByteArray(item.remaining())
          item.get(bytes)
          byteArrayOutputStream.write(bytes)
        }

        override fun onError(throwable: Throwable) {}

        override fun onComplete() {}
      }
    )
    return byteArrayOutputStream.toString("UTF-8")
  }

  @Test
  @RunsInEdt
  fun testPermissionCheckUi() =
    runTest(timeout = 10.seconds) {
      val checkLatch = Mutex(true)
      val inCheckLatch = Mutex(true)

      fun createDialog(result: Future<Boolean>) =
        OemEulaContent(listOf("myLab", "myLab2"), projectRule.disposable, projectRule.project) {
          inCheckLatch.unlock()
          checkLatch.lock()
          result.get()
        }

      // Check case with access
      composeRule.setContent {
        CompositionLocalProvider(LocalComponent provides mock()) { createDialog(CompletableFuture.completedFuture(true)).ComposeContent() }
      }
      composeRule.waitForIdle()
      inCheckLatch.lock()

      composeRule.waitUntilExactlyOneExists(hasText("Checking permissions..."))
      composeRule.onNodeWithContentDescription("Lab inaccessible").assertDoesNotExist()
      composeRule.onNodeWithText("Contact project administrator for access.").assertDoesNotExist()
      checkLatch.unlock()
      composeRule.waitUntilDoesNotExist(hasText("Checking permissions..."))
      composeRule.onNodeWithContentDescription("Lab inaccessible").assertDoesNotExist()
      composeRule.onNodeWithText("Contact project administrator for access.").assertDoesNotExist()

      // Check case without access
      composeRule.setContent {
        CompositionLocalProvider(LocalComponent provides mock()) { createDialog(CompletableFuture.completedFuture(false)).ComposeContent() }
      }
      composeRule.waitForIdle()
      inCheckLatch.lock()

      composeRule.waitUntilExactlyOneExists(hasText("Checking permissions..."))
      composeRule.onNodeWithContentDescription("Lab inaccessible").assertDoesNotExist()
      composeRule.onNodeWithText("Contact project administrator for access.").assertDoesNotExist()
      checkLatch.unlock()
      composeRule.waitUntilDoesNotExist(hasText("Checking permissions..."))
      composeRule.onNodeWithContentDescription("Lab inaccessible").assertExists()
      composeRule.onNodeWithText("Contact project administrator for access.").assertExists()

      // Check error case
      composeRule.setContent {
        CompositionLocalProvider(LocalComponent provides mock()) {
          createDialog(CompletableFuture.failedFuture(RuntimeException("failed"))).ComposeContent()
        }
      }
      composeRule.waitForIdle()
      inCheckLatch.lock()
      // same as "with access" case
      composeRule.waitUntilExactlyOneExists(hasText("Checking permissions..."))
      composeRule.onNodeWithContentDescription("Lab inaccessible").assertDoesNotExist()
      composeRule.onNodeWithText("Contact project administrator for access.").assertDoesNotExist()
      checkLatch.unlock()
      composeRule.waitUntilDoesNotExist(hasText("Checking permissions..."))
      composeRule.onNodeWithContentDescription("Lab inaccessible").assertDoesNotExist()
      composeRule.onNodeWithText("Contact project administrator for access.").assertDoesNotExist()

      projectManagerFlow.value = null

      // Check no project selected case
      composeRule.setContent {
        CompositionLocalProvider(LocalComponent provides mock()) {
          createDialog(CompletableFuture.failedFuture(RuntimeException("failed"))).ComposeContent()
        }
      }
      composeRule.waitForIdle()

      composeRule.waitUntilExactlyOneExists(hasContentDescription("Lab inaccessible"))
      composeRule.waitUntilExactlyOneExists(hasText("Select a project before adding OEM Lab devices."))
    }
}
