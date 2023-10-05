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
package com.google.gct.directaccess

import com.android.tools.adbbridge.Reservation
import com.android.tools.idea.devicemanager.DeviceType
import com.google.api.services.testing.model.AndroidDeviceCatalog
import com.google.api.services.testing.model.AndroidModel
import com.google.api.services.testing.model.DirectAccessVersionInfo
import com.google.api.services.testing.model.PerAndroidVersionInfo
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.gct.directaccess.provisioner.DirectAccessDeviceHandle
import com.google.services.firebase.directaccess.client.DirectAccessConnection
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

object TestUtils {
  val deviceInfoListProvider = {
    listOf(
      DeviceInfo(
        "id1",
        "Google",
        "Pixel 5",
        "Google",
        "codename1",
        31,
        DeviceType.PHONE,
        100,
        200,
        300,
        null,
      ),
      DeviceInfo(
        "id2",
        "Google",
        "Pixel 6",
        "Google",
        "codename2",
        32,
        DeviceType.PHONE,
        200,
        300,
        400,
        30
      ),
      DeviceInfo(
        "id3",
        "Google",
        "Pixel 6 Pro",
        "Google",
        "codename3",
        33,
        DeviceType.PHONE,
        300,
        400,
        500,
        300
      ),
      DeviceInfo(
        "id4",
        "Google",
        "Pixel Watch",
        "Google",
        "watch",
        33,
        DeviceType.WEAR_OS,
        50,
        100,
        150,
        30
      )
    )
  }

  private val phone =
    AndroidModel().apply {
      manufacturer = "Google"
      name = "Phone"
      brand = "Google"
      codename = "oriole"
      id = codename
      supportedVersionIds = listOf("32")
      form = "PHYSICAL"
      set("formFactor", "PHONE")
      screenX = 100
      screenY = 200
      screenDensity = 300
      perVersionInfo = listOf(generatePerVersionInfo())
    }

  private val wearable =
    AndroidModel().apply {
      manufacturer = "Google"
      name = "Watch"
      brand = "Google"
      codename = "watch"
      id = codename
      supportedVersionIds = listOf("32")
      form = "PHYSICAL"
      set("formFactor", "WEARABLE")
      screenX = 10
      screenY = 20
      screenDensity = 30
      perVersionInfo = listOf(generatePerVersionInfo())
    }

  private val tablet =
    AndroidModel().apply {
      manufacturer = "Google"
      name = "Tablet"
      brand = "Google"
      codename = "tablet"
      id = codename
      supportedVersionIds = listOf("32")
      form = "PHYSICAL"
      set("formFactor", "TABLET")
      screenX = 1000
      screenY = 2000
      screenDensity = 3000
      perVersionInfo = listOf(generatePerVersionInfo(isDirectAccessSupported = false))
    }

  private val invalidDevice =
    AndroidModel().apply {
      manufacturer = "invalid"
      name = "device"
      brand = "invalid"
      codename = "device"
      supportedVersionIds = listOf("33")
      form = "PHYSICAL"
      set("formFactor", "PHONE")
      screenX = 1000
      screenY = 2000
      // Missing screenDensity
      perVersionInfo = listOf(generatePerVersionInfo())
    }

  private val phoneLessThanApi26 =
    AndroidModel().apply {
      manufacturer = "Google"
      name = "Phone API 25"
      brand = "Google"
      codename = "phone-api-25"
      id = codename
      supportedVersionIds = listOf("25")
      form = "PHYSICAL"
      set("formFactor", "PHONE")
      screenX = 100
      screenY = 200
      screenDensity = 300
      perVersionInfo = listOf(generatePerVersionInfo("25"))
    }

  private val phoneSupportedOnHigherASVersion =
    AndroidModel().apply {
      manufacturer = "Google"
      name = "Phone Higher AS Version"
      brand = "Google"
      codename = "phone-higher-as-version"
      id = codename
      supportedVersionIds = listOf("25")
      form = "PHYSICAL"
      set("formFactor", "PHONE")
      screenX = 100
      screenY = 200
      screenDensity = 300
      perVersionInfo =
        listOf(
          generatePerVersionInfo().apply {
            directAccessVersionInfo.apply { minimumAndroidStudioVersion = "999.9999.99" }
          }
        )
    }

  private val phoneWithNoCapacity =
    AndroidModel().apply {
      manufacturer = "Google"
      name = "Phone with no capacity"
      brand = "Google"
      codename = "phone-with-no-capacity"
      id = codename
      supportedVersionIds = listOf("25")
      form = "PHYSICAL"
      set("formFactor", "PHONE")
      screenX = 100
      screenY = 200
      screenDensity = 300
      perVersionInfo =
        listOf(generatePerVersionInfo().apply { deviceCapacity = "DEVICE_CAPACITY_NONE" })
    }

  private fun generatePerVersionInfo(api: String = "32", isDirectAccessSupported: Boolean = true) =
    PerAndroidVersionInfo().apply {
      deviceCapacity = "DEVICE_CAPACITY_HIGH"
      versionId = api
      directAccessVersionInfo =
        DirectAccessVersionInfo().apply { directAccessSupported = isDirectAccessSupported }
      interactiveDeviceAvailabilityEstimate = "30s"
    }

  val androidDeviceCatalog =
    createDeviceCatalog(
      phone,
      wearable,
      tablet,
      phoneLessThanApi26,
      phoneSupportedOnHigherASVersion,
      phoneWithNoCapacity
    )

  val androidDeviceCatalogWithMissingFields =
    createDeviceCatalog(
      phone,
      wearable,
      tablet,
      invalidDevice,
      phoneLessThanApi26,
      phoneSupportedOnHigherASVersion
    )

  private fun createDeviceCatalog(vararg androidModels: AndroidModel) =
    AndroidDeviceCatalog().apply { models = androidModels.toList() }

  val DirectAccessDeviceHandle.connectionState: DirectAccessConnection.ConnectionState
    get() = connection.state.value.connection

  val DirectAccessDeviceHandle.reservation: Reservation
    get() = connection.state.value.reservation

  val DirectAccessDeviceHandle.deviceName: String
    get() = sourceTemplate.properties.title

  fun getNotifications(project: Project): Array<Notification> =
    NotificationsManager.getNotificationsManager()
      .getNotificationsOfType(Notification::class.java, project)

  suspend fun Project.refreshReservations() =
    service<DirectAccessService>()
      .cloudProjectManager
      .value
      ?.reservationListFlowWithException
      ?.refresh()
}
