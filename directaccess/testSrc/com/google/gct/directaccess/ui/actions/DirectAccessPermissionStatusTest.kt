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
package com.google.gct.directaccess.ui.actions

import com.google.common.truth.Truth.assertThat
import com.google.gct.directaccess.ADMIN_PERMISSION_SET
import com.google.gct.directaccess.DirectAccessPermissionStatus.Companion.parseFrom
import com.google.gct.directaccess.DirectAccessPermissionStatus.Full
import com.google.gct.directaccess.DirectAccessPermissionStatus.MissingServiceUse
import com.google.gct.directaccess.DirectAccessPermissionStatus.None
import com.google.gct.directaccess.DirectAccessPermissionStatus.Unknown
import com.google.gct.directaccess.DirectAccessPermissionStatus.Viewer
import com.google.gct.directaccess.FULL_PERMISSIONS_SET
import com.google.gct.directaccess.SERVICES_USE
import com.google.gct.directaccess.VIEWER_PERMISSIONS_SET
import org.junit.Test

class DirectAccessPermissionStatusTest {

  @Test
  fun testFullPermissions() {
    val permissions = parseFrom(FULL_PERMISSIONS_SET)
    assertThat(permissions).isInstanceOf(Full::class.java)
    assertThat(permissions.missingPermissions).isEqualTo(emptySet<String>())
  }

  @Test
  fun testViewerPermissions() {
    val permissions = parseFrom(VIEWER_PERMISSIONS_SET + SERVICES_USE)
    assertThat(permissions).isInstanceOf(Viewer::class.java)
    assertThat(permissions.missingPermissions)
      .isEqualTo(ADMIN_PERMISSION_SET - VIEWER_PERMISSIONS_SET)
  }

  @Test
  fun testMissingServiceUseWhenServiceUsageMissing() {
    val permissions = parseFrom(ADMIN_PERMISSION_SET)
    assertThat(permissions).isInstanceOf(MissingServiceUse::class.java)
    assertThat(permissions.missingPermissions).isEqualTo(setOf(SERVICES_USE))
  }

  @Test
  fun testNoneWhenNoPermissionsExist() {
    val permissions = parseFrom(emptySet())
    assertThat(permissions).isInstanceOf(None::class.java)
    assertThat(permissions.missingPermissions).isEqualTo(FULL_PERMISSIONS_SET)
  }

  @Test
  fun testUnknownPermissionWhenMixOfPermissions() {
    val permissions = parseFrom(FULL_PERMISSIONS_SET - VIEWER_PERMISSIONS_SET + SERVICES_USE)
    assertThat(permissions).isInstanceOf(Unknown::class.java)
    assertThat(permissions.missingPermissions).isEqualTo(VIEWER_PERMISSIONS_SET - SERVICES_USE)
  }
}
