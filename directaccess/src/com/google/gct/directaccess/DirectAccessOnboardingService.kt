/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.tools.analytics.UsageTracker
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.projectsystem.getProjectSystem
import com.android.tools.idea.stats.AndroidStudioUsageTracker
import com.android.tools.idea.stats.AnonymizerUtil
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.http.HttpResponseException
import com.google.gct.directaccess.DirectAccessPermissionStatus.Companion.checkDirectAccessPermission
import com.google.gct.login2.GoogleLoginService
import com.google.gct.login2.LoginFeature
import com.google.services.firebase.FirebaseLoginFeature
import com.google.services.firebase.FirebaseProjectClient
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import com.google.wireless.android.sdk.stats.FirebaseManagementEvent
import com.google.wireless.android.sdk.stats.FirebaseManagementEvent.CreateFirebaseProjectDetails.CreateFirebaseProjectState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.ui.Messages
import java.util.UUID
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

private const val INITIAL_WAIT_TIME_SECONDS = 50L
private const val WAIT_TIME_INTERVAL_SECONDS = 10L
private const val WAIT_TIME_AFTER_PROJECT_CREATED_SECONDS = 3L
private const val WAIT_TIME_AFTER_TOS_ACCEPTANCE_SECONDS = 5L
private const val RETRY_COUNT = 3

/** An application level service to track the first created cloud project. */
@Service
class DirectAccessOnboardingService(private val scope: CoroutineScope) {

  enum class State {
    STARTED,
    PROJECT_CREATED,
    TOS_NEEDED,
    TOS_ACCEPTED,
    FIREBASE_ENABLED,
    API_ENABLED;

    fun isPending() = this != API_ENABLED
  }

  /** A data class to track the task with its newly created [cloudProject]. */
  data class Task(val cloudProject: CloudProjectEntry, val state: State)

  private val _taskFlow: MutableStateFlow<Task?> = MutableStateFlow(null)

  /** Tracks the [State] of the newly created default firebase project. */
  val taskFlow: StateFlow<Task?> = _taskFlow

  /**
   * Starts the onboarding process for the first created Firebase Project. This handles logging in, creating the Cloud Project, enabling
   * Firebase, managing Terms of Service (TOS) acceptance if needed, and finally waiting for Device Streaming API enablement and permission
   * propagation.
   */
  fun start() {
    // Avoid duplicate workflows for the same user.
    val user = service<GoogleLoginService>().activeUserFlow.value?.email ?: throw RuntimeException("User not logged in")
    if (_taskFlow.value?.cloudProject?.user == user) {
      return
    }

    _taskFlow.value = Task(CloudProjectEntry(user, ""), State.STARTED)
    scope
      .launch {
        // Step 1: User Login & Account Setup verification
        val feature = LoginFeature.feature<FirebaseLoginFeature>()
        val credential = feature.credential() ?: throw RuntimeException("User not logged in")
        if (FirebaseProjectClient.listFirebaseProjects(credential).isNotEmpty())
          throw RuntimeException("User already has a Firebase project")
        // Step 2: Google Cloud Project Creation
        val projectEntry = createCloudProjectWithRetry(user, credential)
        _taskFlow.value = Task(projectEntry, State.PROJECT_CREATED)
        // Step 3: Enable Firebase on the Cloud Project
        if (!enableFirebase(projectEntry.name, credential)) {
          handleTosAndEnableFirebase(projectEntry, credential)
        } else {
          _taskFlow.value = Task(projectEntry, State.FIREBASE_ENABLED)
        }

        // Step 4: Wait for the Device Streaming API and permission propagation
        enableDeviceStreamingWithRetry(projectEntry.name)
        waitForPermissions(projectEntry)
      }
      .invokeOnCompletion { handler ->
        if (taskFlow.value?.state?.isPending() != false) {
          _taskFlow.value = null
        }
        if (handler != null && handler !is CancellationException) {
          showErrorDialog("Failed to create default firebase project", handler)
        }
      }
  }

  /**
   * Generates a unique project ID and creates a Google Cloud Project. Retries once with a different project ID if the first one is already
   * taken.
   */
  private fun createCloudProjectWithRetry(user: String, credential: Credential): CloudProjectEntry {
    var projectId = createUniqueProjectId()
    trackState(projectId, CreateFirebaseProjectState.STARTED)
    try {
      FirebaseProjectClient.createCloudProject(projectId, credential)
    } catch (e: HttpResponseException) {
      // Retry once if the project ID already exists.
      if (e.statusCode == 409) {
        projectId = createUniqueProjectId()
        FirebaseProjectClient.createCloudProject(projectId, credential)
      } else {
        throw e
      }
    }
    return CloudProjectEntry(user, projectId)
  }

  /**
   * Attempts to add Firebase support to the newly created Cloud Project. Returns true if successful immediately, or false if Terms of
   * Service acceptance is required.
   */
  private suspend fun enableFirebase(projectId: String, credential: Credential): Boolean {
    delay(WAIT_TIME_AFTER_PROJECT_CREATED_SECONDS.seconds)
    val e = runCatching { FirebaseProjectClient.addFirebaseProject(projectId, credential) }.exceptionOrNull()
    if (e == null) {
      trackState(projectId, CreateFirebaseProjectState.CREATED)
      return true
    }
    if (e.needsTos()) {
      return false
    }
    // Log failure for coroutine cancellation or other exceptions.
    trackState(projectId, CreateFirebaseProjectState.FAILED)
    throw e
  }

  /** Attempts to enable APIs for Device Streaming. */
  private suspend fun enableDeviceStreamingWithRetry(projectId: String) {
    repeat(RETRY_COUNT) { count ->
      delay(WAIT_TIME_INTERVAL_SECONDS.seconds)
      val exception = runCatching {
        service<CloudClientService>().client.enableDeviceStreamingService(projectId, StudioFlags.DEVICE_STREAMING_ENDPOINT.get())
      }
        .exceptionOrNull()
      if (exception == null) return
      if (count == RETRY_COUNT - 1) {
        throw exception
      }
    }
  }

  /**
   * Transitions the state to TOS_NEEDED and blocks until the user accepts the Terms of Service. Once accepted, retries adding Firebase
   * support to the Cloud Project up to [RETRY_COUNT] times.
   */
  private suspend fun handleTosAndEnableFirebase(projectEntry: CloudProjectEntry, credential: Credential) {
    _taskFlow.value = Task(projectEntry, State.TOS_NEEDED)

    // Wait until the user has accepted the Terms of Service
    _taskFlow.first { task -> task?.state == State.TOS_ACCEPTED }

    delay(WAIT_TIME_AFTER_TOS_ACCEPTANCE_SECONDS.seconds)
    repeat(RETRY_COUNT) { count ->
      val e = runCatching { FirebaseProjectClient.addFirebaseProject(projectEntry.name, credential) }.exceptionOrNull()
      if (e == null) {
        trackState(projectEntry.name, CreateFirebaseProjectState.CREATED)
        _taskFlow.value = Task(projectEntry, State.FIREBASE_ENABLED)
        return
      }
      if (e.needsTos() && count < RETRY_COUNT - 1) {
        delay(2.seconds)
      } else {
        trackState(projectEntry.name, CreateFirebaseProjectState.FAILED)
        throw e
      }
    }
  }

  /**
   * Polls the Google Cloud Project to check if the user has the required permissions and if the Android Device Streaming API has been
   * enabled.
   */
  private suspend fun waitForPermissions(projectEntry: CloudProjectEntry) {
    delay(INITIAL_WAIT_TIME_SECONDS.seconds)
    // Check permissions of the cloud project every [WAIT_TIME_INTERVAL_SECONDS].
    repeat(RETRY_COUNT) { count ->
      delay(WAIT_TIME_INTERVAL_SECONDS.seconds)
      val exception = runCatching {
        if (
          checkDirectAccessPermission(projectEntry, true).missingPermissions.isEmpty() &&
            service<CloudClientService>()
              .client
              .isDeviceStreamingServiceEnabled(projectEntry.name, StudioFlags.DEVICE_STREAMING_ENDPOINT.get())
        ) {
          _taskFlow.value = Task(projectEntry, State.API_ENABLED)
          return
        }
      }
        .exceptionOrNull()
      if (count == RETRY_COUNT - 1) {
        throw exception ?: RuntimeException("Timed out waiting for permissions")
      }
    }
  }

  /** Update [_taskFlow] state from TOS_NEEDED to TOS_ACCEPTED. */
  fun notifyTosAccepted(): Boolean =
    _taskFlow
      .updateAndGet {
        if (it?.state == State.TOS_NEEDED) {
          Task(it.cloudProject, State.TOS_ACCEPTED)
        } else it
      }
      ?.state == State.TOS_ACCEPTED

  fun cancel() {
    scope.coroutineContext.job.cancelChildren()
  }

  /** Wait until the first time TOS check and return true if not accepted. */
  suspend fun isTosNeeded(): Boolean = taskFlow.map { it?.state }.first { it == null || it >= State.TOS_NEEDED } == State.TOS_NEEDED

  private fun trackState(projectId: String, state: CreateFirebaseProjectState) {
    UsageTracker.log(
      AndroidStudioEvent.newBuilder().apply {
        kind = AndroidStudioEvent.EventKind.FIREBASE_MANAGEMENT_EVENT
        productDetails = AndroidStudioUsageTracker.productDetails
        firebaseManagementEventBuilder.apply {
          type = FirebaseManagementEvent.FirebaseManagementEventType.CREATE_FIREBASE_PROJECT
          this.projectId = AnonymizerUtil.anonymizeUtf8(projectId)
          createFirebaseProjectDetailsBuilder.apply { this.state = state }
        }
      }
    )
  }

  fun Throwable.needsTos() = this is HttpResponseException && (statusCode == 403 || statusCode == 400)

  /** See https://cloud.google.com/resource-manager/docs/creating-managing-projects for project ID requirements. */
  private fun createUniqueProjectId(): String {
    // Project ID can only contain lowercase letters, numbers, and hyphens.
    val restrictedWords = listOf("google", "null", "undefined", "ssl")
    @Suppress("UnstableApiUsage")
    val projectId =
      ProjectManagerEx.getOpenProjects()
        .firstOrNull()
        ?.getProjectSystem()
        ?.getKnownApplicationIds()
        ?.firstOrNull()
        ?.substringAfterLast(".")
        ?.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        ?.lowercase()
        ?.let { id ->
          // Project ID must start with a letter.
          val firstLetter = id.indexOfFirst { it.isLowerCase() }
          if (firstLetter >= 0) id.substring(firstLetter) else ""
        }
        ?.let { id ->
          // Project ID must be 6 to 30 characters in length. save 10 characters for random
          // suffix.
          id.substring(0, min(20, id.length))
        }
        ?.takeIf {
          // Project ID cannot contain restricted strings
          it.isNotEmpty() && restrictedWords.none { restrictedWord -> it.contains(restrictedWord) }
        } ?: "device-streaming"
    return "$projectId-${UUID.randomUUID().toString().substring(0, 8)}"
  }
}

fun showErrorDialog(title: String, e: Throwable) {
  val message = e.localizedMessage ?: e.toString()
  if (ApplicationManager.getApplication().isDispatchThread) {
    Messages.showErrorDialog(message, title)
  } else {
    ApplicationManager.getApplication().invokeLater({ Messages.showErrorDialog(message, title) }, ModalityState.defaultModalityState())
  }
}
