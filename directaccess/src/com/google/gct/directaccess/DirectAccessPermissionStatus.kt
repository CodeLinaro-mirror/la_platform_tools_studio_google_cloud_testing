/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.annotations.concurrency.Slow
import com.google.api.services.cloudresourcemanager.v3.model.TestIamPermissionsRequest
import com.google.api.services.cloudresourcemanager.v3.model.TestIamPermissionsResponse
import com.google.common.annotations.VisibleForTesting
import com.google.services.firebase.directaccess.client.GOOGLE_USER_PROJECT_KEY
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import java.io.IOException

const val SERVICES_USE = "serviceusage.services.use"
const val ENV_CATALOG_GET = "cloudtestservice.environmentcatalog.get"
const val DEVICE_SESSION_GET = "cloudtestservice.devicesession.get"
const val DEVICE_SESSION_LIST = "cloudtestservice.devicesession.list"
const val DEVICE_SESSION_CREATE = "cloudtestservice.devicesession.create"
const val DEVICE_SESSION_UPDATE = "cloudtestservice.devicesession.update"
const val DEVICE_SESSION_CANCEL = "cloudtestservice.devicesession.cancel"
const val DEVICE_SESSION_USE = "cloudtestservice.devicesession.use"

const val NEW_DEVICE_SESSION_CANCEL = "devicestreaming.googleapis.com/deviceSessions.cancel"
const val NEW_DEVICE_SESSION_CREATE = "devicestreaming.googleapis.com/deviceSessions.create"
const val NEW_DEVICE_SESSION_UPDATE = "devicestreaming.googleapis.com/deviceSessions.update"
const val NEW_DEVICE_SESSION_USE = "devicestreaming.googleapis.com/deviceSessions.use"
const val NEW_DEVICE_SESSION_GET = "devicestreaming.googleapis.com/deviceSessions.get"
const val NEW_DEVICE_SESSION_LIST = "devicestreaming.googleapis.com/deviceSessions.list"

val NEW_VIEWER_PERMISSIONS_SET = setOf(ENV_CATALOG_GET, NEW_DEVICE_SESSION_GET, NEW_DEVICE_SESSION_LIST)
val NEW_ADMIN_PERMISSIONS_SET =
  NEW_VIEWER_PERMISSIONS_SET +
    setOf(NEW_DEVICE_SESSION_CREATE, NEW_DEVICE_SESSION_UPDATE, NEW_DEVICE_SESSION_CANCEL, NEW_DEVICE_SESSION_USE)

val VIEWER_PERMISSIONS_SET = setOf(ENV_CATALOG_GET, DEVICE_SESSION_GET, DEVICE_SESSION_LIST)

val ADMIN_PERMISSIONS_SET =
  VIEWER_PERMISSIONS_SET + setOf(DEVICE_SESSION_CREATE, DEVICE_SESSION_UPDATE, DEVICE_SESSION_CANCEL, DEVICE_SESSION_USE)

val FULL_PERMISSIONS_SET = setOf(SERVICES_USE) + ADMIN_PERMISSIONS_SET
val NEW_FULL_PERMISSIONS_SET = setOf(SERVICES_USE) + NEW_ADMIN_PERMISSIONS_SET

/**
 * Permission of the user for accessing Direct Access.
 *
 * @param Set<String> - Permissions missing from [FULL_PERMISSIONS_SET].
 */
sealed class DirectAccessPermissionStatus(val missingPermissions: Set<String>) {

  /** Unknown state of permissions */
  class Unknown(missingPermissions: Set<String>) : DirectAccessPermissionStatus(missingPermissions)

  /** User is missing SERVICE_USE which is required to use the service. */
  class MissingServiceUse(missingPermissions: Set<String>) : DirectAccessPermissionStatus(missingPermissions)

  /** User/Project does not have DA permissions */
  class None(missingPermissions: Set<String>) : DirectAccessPermissionStatus(missingPermissions)

  /** User has viewer permissions */
  class Viewer(missingPermissions: Set<String>) : DirectAccessPermissionStatus(missingPermissions)

  /** User has full DA admin permissions */
  class Full : DirectAccessPermissionStatus(emptySet())

  companion object {
    @VisibleForTesting
    internal fun parseFrom(permissions: Set<String>, isDefaultApiEnabled: Boolean = false): DirectAccessPermissionStatus {
      if (isDefaultApiEnabled) {
        // None of the permissions requested exist on the user's IAM role
        if (permissions.isEmpty()) return None(NEW_FULL_PERMISSIONS_SET)

        val missingPermissions = NEW_FULL_PERMISSIONS_SET - permissions
        return if (missingPermissions.isEmpty()) {
          // All the required permissions exist for the user.
          Full()
        } else if (missingPermissions.contains(SERVICES_USE)) {
          MissingServiceUse(missingPermissions)
        } else if (missingPermissions.containsAll(NEW_ADMIN_PERMISSIONS_SET)) {
          None(missingPermissions)
        } else if (permissions.containsAll(NEW_VIEWER_PERMISSIONS_SET)) {
          // User has viewer permissions
          Viewer(missingPermissions)
        } else {
          // Possibly a custom role with a mix of permissions from VIEWER and ADMIN.
          Unknown(missingPermissions)
        }
      } else {
        // None of the permissions requested exist on the user's IAM role
        if (permissions.isEmpty()) return None(FULL_PERMISSIONS_SET)

        val missingPermissions = FULL_PERMISSIONS_SET - permissions
        return if (missingPermissions.isEmpty()) {
          // All the required permissions exist for the user.
          Full()
        } else if (missingPermissions.contains(SERVICES_USE)) {
          MissingServiceUse(missingPermissions)
        } else if (missingPermissions.containsAll(ADMIN_PERMISSIONS_SET)) {
          None(missingPermissions)
        } else if (permissions.containsAll(VIEWER_PERMISSIONS_SET)) {
          // User has viewer permissions
          Viewer(missingPermissions)
        } else {
          // Possibly a custom role with a mix of permissions from VIEWER and ADMIN.
          Unknown(missingPermissions)
        }
      }
    }

    private fun getTestIamPermissionsResponse(
      cloudProject: CloudProjectEntry,
      applyUserProject: Boolean,
      isDefaultApiEnabled: Boolean,
    ): TestIamPermissionsResponse {
      val fullPermissionsSet = if (isDefaultApiEnabled) NEW_FULL_PERMISSIONS_SET else FULL_PERMISSIONS_SET
      return checkPermissions(fullPermissionsSet, cloudProject, applyUserProject) ?: throw IOException("Got null response")
    }

    fun checkDirectAccessPermission(cloudProject: CloudProjectEntry, isDefaultApiEnabled: Boolean): DirectAccessPermissionStatus {
      val response =
        try {
          getTestIamPermissionsResponse(cloudProject, true, isDefaultApiEnabled)
        } catch (e: IOException) {
          thisLogger().warn("Cloud Resource Manager API may not be enabled.", e)
          null
        }
          ?: try {
            getTestIamPermissionsResponse(cloudProject, false, isDefaultApiEnabled)
          } catch (e: IOException) {
            thisLogger().warn("Could not fetch permissions for user ${cloudProject.user} for project ${cloudProject.name}: ${e.message}")
            return None(FULL_PERMISSIONS_SET)
          }
      // response.permission is null if the user does not have any permissions
      val permissions = response.permissions ?: emptyList()
      return parseFrom(permissions.toSet(), isDefaultApiEnabled)
    }
  }
}

@Slow
internal fun checkPermissions(
  permissions: Set<String>,
  cloudProject: CloudProjectEntry,
  applyUserProject: Boolean = true,
): TestIamPermissionsResponse? {
  val request = TestIamPermissionsRequest().apply { this.permissions = permissions.toList() }
  return service<CloudClientService>()
    .client
    .cloudResourceManager
    .projects()
    .testIamPermissions("projects/${cloudProject.name}", request)
    .apply { if (applyUserProject) requestHeaders[GOOGLE_USER_PROJECT_KEY] = cloudProject.name }
    .execute()
}
