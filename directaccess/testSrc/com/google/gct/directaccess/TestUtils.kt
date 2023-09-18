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
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.gct.directaccess.provisioner.DirectAccessDeviceHandle
import com.google.gct.directaccess.provisioner.DirectAccessDeviceProvisionerPlugin
import com.google.gct.directaccess.provisioner.DirectAccessDeviceTemplate
import com.google.services.firebase.directaccess.client.DirectAccessConnection
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
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
        300
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
        400
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
        500
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
        150
      )
    )
  }

  private val phone =
    AndroidModel().apply {
      manufacturer = "Google"
      name = "Phone"
      brand = "Google"
      codename = "oriole"
      supportedVersionIds = listOf("32")
      form = "PHYSICAL"
      set("formFactor", "PHONE")
      screenX = 100
      screenY = 200
      screenDensity = 300
    }

  private val wearable =
    AndroidModel().apply {
      manufacturer = "Google"
      name = "Watch"
      brand = "Google"
      codename = "oriole"
      supportedVersionIds = listOf("32")
      form = "PHYSICAL"
      set("formFactor", "WEARABLE")
      screenX = 10
      screenY = 20
      screenDensity = 30
    }

  private val tablet =
    AndroidModel().apply {
      manufacturer = "Google"
      name = "Tablet"
      brand = "Google"
      codename = "oriole"
      supportedVersionIds = listOf("32")
      form = "PHYSICAL"
      set("formFactor", "TABLET")
      screenX = 1000
      screenY = 2000
      screenDensity = 3000
    }

  val androidDeviceCatalog =
    AndroidDeviceCatalog().apply { models = listOf(phone, wearable, tablet) }

  val DirectAccessDeviceHandle.connectionState: DirectAccessConnection.ConnectionState
    get() = connection.state.value.connection

  val DirectAccessDeviceHandle.reservation: Reservation
    get() = connection.state.value.reservation

  val DirectAccessDeviceHandle.deviceName: String
    get() = sourceTemplate.properties.title

  fun getNotifications(project: Project): Array<Notification> =
    NotificationsManager.getNotificationsManager()
      .getNotificationsOfType(Notification::class.java, project)

  suspend fun DirectAccessDeviceProvisionerPlugin.updateReservations() =
    matchReservations(
      templates.value.mapNotNull { it as? DirectAccessDeviceTemplate },
      fetchReservations()!!
    )
}
