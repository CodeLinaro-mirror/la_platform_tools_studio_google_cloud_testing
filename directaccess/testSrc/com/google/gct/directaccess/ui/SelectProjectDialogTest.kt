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

import com.android.flags.junit.FlagRule
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.idea.flags.StudioFlags
import com.google.common.truth.Truth.assertThat
import com.google.gct.directaccess.DirectAccessPermissionStatus
import com.google.gct.directaccess.FULL_PERMISSIONS_SET
import com.google.gct.directaccess.NEW_ADMIN_PERMISSIONS_SET
import com.google.gct.directaccess.NEW_FULL_PERMISSIONS_SET
import com.google.gct.directaccess.SERVICES_USE
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import io.grpc.Status
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

@RunsInEdt
class SelectProjectDialogTest {
  private val projectRule = ProjectRule()

  @get:Rule
  val ruleChain =
    RuleChain.outerRule(projectRule)
      .around(EdtRule())
      .around(HeadlessDialogRule())
      .around(FlagRule(StudioFlags.DIRECT_ACCESS_MIGRATE_TO_DDP, false))

  private lateinit var dialog: SelectProjectDialog

  @Before
  fun setUp() {
    dialog = SelectProjectDialog(projectRule.project)
  }

  @After
  fun tearDown() {
    dialog.close(0)
  }

  @Test
  fun testGetErrorMessage_nullPermission() {
    val message = dialog.getErrorMessage("test-project", null, null, isDefaultApiEnabled = false)
    assertThat(message).isEqualTo("Unable to retrieve permission")
  }

  @Test
  fun testGetErrorMessage_apiNotEnabled() {
    val message = dialog.getErrorMessage("test-project", DirectAccessPermissionStatus.ApiNotEnabled, null, isDefaultApiEnabled = true)
    assertThat(message)
      .isEqualTo("Android Device Streaming API is not enabled in project test-project. Enable it by visiting Google Cloud console.")
  }

  @Test
  fun testGetErrorMessage_fullPermission() {
    val message = dialog.getErrorMessage("test-project", DirectAccessPermissionStatus.Full(), null, isDefaultApiEnabled = true)
    assertThat(message).isNull()
  }

  @Test
  fun testGetErrorMessage_missingServiceUse_defaultApiEnabled() {
    val permission = DirectAccessPermissionStatus.parseFrom(NEW_ADMIN_PERMISSIONS_SET, isDefaultApiEnabled = true)
    val message = dialog.getErrorMessage("test-project", permission, null, isDefaultApiEnabled = true)
    assertThat(message)
      .isEqualTo(
        "You do not have full access to Device Streaming in project test-project. You are missing the following permissions:<br>serviceusage.services.use"
      )
  }

  @Test
  fun testGetErrorMessage_none_defaultApiEnabled() {
    val permission = DirectAccessPermissionStatus.parseFrom(setOf(), isDefaultApiEnabled = true)
    val message = dialog.getErrorMessage("test-project", permission, null, isDefaultApiEnabled = true)
    assertThat(message).isEqualTo("You do not have access to Device Streaming in project test-project.")
  }

  @Test
  fun testGetStatusCodeErrorMessage_deviceStreamingApiDisabled_isDefaultApiEnabledTrue() {
    val permission = DirectAccessPermissionStatus.parseFrom(NEW_FULL_PERMISSIONS_SET, isDefaultApiEnabled = true)
    val exception =
      Status.PERMISSION_DENIED.withDescription("Device Streaming API has not been used in project test-project before or it is disabled.")
        .asRuntimeException()
    val message = dialog.getStatusCodeErrorMessage("test-project", permission, exception, isDefaultApiEnabled = true)
    assertThat(message)
      .isEqualTo("Device Streaming API is not enabled in your project test-project. Enable it by visiting Google Cloud console.")
  }

  @Test
  fun testGetStatusCodeErrorMessage_cloudTestingApiDisabled_isDefaultApiEnabledFalse() {
    val permission = DirectAccessPermissionStatus.parseFrom(FULL_PERMISSIONS_SET, isDefaultApiEnabled = false)
    val exception =
      Status.PERMISSION_DENIED.withDescription("Cloud Testing API has not been used in project test-project before or it is disabled.")
        .asRuntimeException()
    val message = dialog.getStatusCodeErrorMessage("test-project", permission, exception, isDefaultApiEnabled = false)
    assertThat(message)
      .isEqualTo("Cloud Testing API is not enabled in your project test-project. Enable it by visiting Google Cloud console.")
  }

  @Test
  fun testGetStatusCodeErrorMessage_serviceUsageMissing_isDefaultApiEnabledTrue() {
    val permission = DirectAccessPermissionStatus.parseFrom(NEW_ADMIN_PERMISSIONS_SET, isDefaultApiEnabled = true)
    val exception =
      Status.PERMISSION_DENIED.withDescription("Grant the caller the roles/serviceusage.serviceUsageConsumer role").asRuntimeException()
    val message = dialog.getStatusCodeErrorMessage("test-project", permission, exception, isDefaultApiEnabled = true)
    assertThat(message)
      .isEqualTo(
        "You do not have full access to Device Streaming in project test-project. You are missing the following permissions:<br>serviceusage.services.use"
      )
  }

  @Test
  fun testGetStatusCodeErrorMessage_none_isDefaultApiEnabledTrue() {
    val permission = DirectAccessPermissionStatus.parseFrom(setOf(), isDefaultApiEnabled = true)
    val exception =
      Status.PERMISSION_DENIED.withDescription("Grant the caller the roles/serviceusage.serviceUsageConsumer role").asRuntimeException()
    val message = dialog.getStatusCodeErrorMessage("test-project", permission, exception, isDefaultApiEnabled = true)
    assertThat(message).isEqualTo("You do not have access to Device Streaming in project test-project.")
  }

  @Test
  fun testGetStatusCodeErrorMessage_partialPermission_isDefaultApiEnabledTrue() {
    val permission = DirectAccessPermissionStatus.parseFrom(setOf(SERVICES_USE), isDefaultApiEnabled = true)
    val exception =
      Status.PERMISSION_DENIED.withDescription("Grant the caller the roles/serviceusage.serviceUsageConsumer role").asRuntimeException()
    val message = dialog.getStatusCodeErrorMessage("test-project", permission, exception, isDefaultApiEnabled = true)
    assertThat(message).contains("You do not have full access to Device Streaming in project test-project.")
  }

  @Test
  fun testGetStatusCodeErrorMessage_permissionApiNotEnabled_isDefaultApiEnabledTrue() {
    val permission = DirectAccessPermissionStatus.ApiNotEnabled
    val exception = Status.PERMISSION_DENIED.withDescription("Generic PERMISSION_DENIED exception").asRuntimeException()
    val message = dialog.getStatusCodeErrorMessage("test-project", permission, exception, isDefaultApiEnabled = true)
    assertThat(message)
      .isEqualTo("Device Streaming API is not enabled in your project test-project. Enable it by visiting Google Cloud console.")
  }

  @Test
  fun testGetStatusCodeErrorMessage_permissionApiNotEnabled_isDefaultApiEnabledFalse() {
    val permission = DirectAccessPermissionStatus.ApiNotEnabled
    val exception = Status.PERMISSION_DENIED.withDescription("Generic PERMISSION_DENIED exception").asRuntimeException()
    val message = dialog.getStatusCodeErrorMessage("test-project", permission, exception, isDefaultApiEnabled = false)
    assertThat(message)
      .isEqualTo("Cloud Testing API is not enabled in your project test-project. Enable it by visiting Google Cloud console.")
  }

  @Test
  fun testGetErrorMessageLink_permissions() {
    val errorMessage =
      "You do not have full access to Device Streaming in project test-project. You are missing the following permissions:<br>serviceusage.services.use"
    val (linkText, link) = dialog.getErrorMessageLink(errorMessage, "test-project", isDefaultApiEnabled = true)
    assertThat(linkText).isEqualTo("Google Cloud console")
    assertThat(link).isEqualTo("https://console.cloud.google.com/iam-admin/iam?project=test-project")
  }

  @Test
  fun testGetErrorMessageLink_apiDisabled_defaultApiEnabled() {
    val errorMessage = "Device Streaming API is not enabled in your project test-project. Enable it by visiting Google Cloud console."
    val (linkText, link) = dialog.getErrorMessageLink(errorMessage, "test-project", isDefaultApiEnabled = true)
    assertThat(linkText).isEqualTo("Google Cloud console")
    assertThat(link).isEqualTo("https://console.cloud.google.com/apis/api/devicestreaming.googleapis.com/overview?project=test-project")
  }

  @Test
  fun testGetErrorMessageLink_apiDisabled_defaultApiDisabled() {
    val errorMessage = "Cloud Testing API is not enabled in your project test-project. Enable it by visiting Google Cloud console."
    val (linkText, link) = dialog.getErrorMessageLink(errorMessage, "test-project", isDefaultApiEnabled = false)
    assertThat(linkText).isEqualTo("Google Cloud console")
    assertThat(link).isEqualTo("https://console.developers.google.com/apis/api/testing.googleapis.com/overview?project=test-project")
  }

  @Test
  fun testGetErrorMessageLink_otherError() {
    val errorMessage = "Unable to retrieve permission"
    val (linkText, link) = dialog.getErrorMessageLink(errorMessage, "test-project", isDefaultApiEnabled = true)
    assertThat(linkText).isEqualTo("Learn More")
    assertThat(link).isEqualTo("http://d.android.com/r/studio-ui/device-streaming/help/permissions")
  }
}
