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

import com.android.tools.idea.flags.StudioFlags
import com.google.common.annotations.VisibleForTesting
import com.google.gct.directaccess.DirectAccessPermissionStatus.Companion.checkDirectAccessPermission
import com.google.gct.login2.GoogleLoginService
import com.google.gct.login2.LoginFeature
import com.google.services.firebase.FirebaseLoginFeature
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@VisibleForTesting const val DEFAULT_DEVICE_LIST_KEY = "direct.access.onboarding"
private const val INITIAL_WAIT_TIME_SECONDS = 60L
private const val WAIT_TIME_INTERVAL_SECONDS = 10L

/**
 * An application level service to track cloud project created from login flow with
 * [FirebaseLoginFeature.handler].
 */
@Service
class DirectAccessOnboardingService(scope: CoroutineScope) {
  /**
   * A data class to track the task with its newly created [cloudProject].
   *
   * @param isPending if the project is not ready within a timeout
   * @param applyDefaultDevices if the device manager should apply a default list of devices
   */
  data class Task(
    val cloudProject: CloudProjectEntry,
    val isPending: Boolean,
    val applyDefaultDevices: Boolean,
  )

  /**
   * After a new cloud project is created with login, the [taskFlow] emits a pending [Task], waits
   * until the cloud project has necessary permissions and emits the second one with finished state.
   */
  val taskFlow: StateFlow<Task?> =
    MutableStateFlow<Task?>(null).apply {
      scope.launch {
        if (StudioFlags.DIRECT_ACCESS_CREATE_PROJECT.get()) {
          val loginFeature = LoginFeature.feature<FirebaseLoginFeature>()
          service<GoogleLoginService>().activeUserFlow.collectLatest { user ->
            if (user?.isLoggedIn(loginFeature) == true) {
              val applyDefaultDevices =
                !PropertiesComponent.getInstance().getBoolean(DEFAULT_DEVICE_LIST_KEY, false)
              PropertiesComponent.getInstance().setValue(DEFAULT_DEVICE_LIST_KEY, true)
              val createdProject =
                loginFeature.handler?.latestCreatedFirebaseProject?.value ?: return@collectLatest
              val cloudProject = CloudProjectEntry(user.email, createdProject)
              value = Task(cloudProject, true, applyDefaultDevices)
              // Wait a minimum time before project ready.
              delay(TimeUnit.SECONDS.toMillis(INITIAL_WAIT_TIME_SECONDS))
              // Check permissions of the cloud project every [WAIT_TIME_INTERVAL_SECONDS].
              for (count in (1..10)) {
                try {
                  if (checkDirectAccessPermission(cloudProject).missingPermissions.isEmpty()) {
                    break
                  }
                } catch (_: IOException) {
                  // IOExceptions are expected before the cloud project has permissions ready.
                }
                delay(TimeUnit.SECONDS.toMillis(WAIT_TIME_INTERVAL_SECONDS))
              }
              value = Task(cloudProject, false, applyDefaultDevices)
            } else {
              value = null
            }
          }
        }
      }
    }
}
