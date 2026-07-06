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

import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.sdklib.deviceprovisioner.DeviceType
import com.android.tools.idea.adddevicedialog.FormFactors
import com.google.cloud.devicestreaming.v1.DeviceSession as Reservation
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.gct.directaccess.provisioner.DeviceSelection
import com.google.gct.directaccess.provisioner.DirectAccessDeviceHandle
import com.google.services.firebase.directaccess.client.DirectAccessConnection
import com.google.services.firebase.directaccess.client.api.AndroidDeviceCatalog
import com.google.services.firebase.directaccess.client.api.AndroidModel
import com.google.services.firebase.directaccess.client.api.DirectAccessVersionInfo
import com.google.services.firebase.directaccess.client.api.LabInfo
import com.google.services.firebase.directaccess.client.api.PerAndroidVersionInfo
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.update

object TestUtils {
  val deviceInfoListProvider = {
    listOf(
      DeviceInfo(
        id = "id1",
        brand = "Google",
        name = "Pixel 5",
        labId = "google",
        manufacturer = "Google",
        codename = "codename1",
        api = 31,
        type = DeviceType.HANDHELD,
        formFactor = FormFactors.PHONE,
        screenX = 100,
        screenY = 200,
        screenDensity = 300,
        deviceAvailabilityEstimateSeconds = null,
      ),
      DeviceInfo(
        id = "id2",
        brand = "Google",
        name = "Pixel 6",
        labId = "google",
        manufacturer = "Google",
        codename = "codename2",
        api = 32,
        type = DeviceType.HANDHELD,
        formFactor = FormFactors.PHONE,
        screenX = 200,
        screenY = 300,
        screenDensity = 400,
        deviceAvailabilityEstimateSeconds = 300,
      ),
      DeviceInfo(
        id = "id3",
        brand = "Google",
        name = "Pixel 6 Pro",
        labId = "google",
        manufacturer = "Google",
        codename = "codename3",
        api = 33,
        type = DeviceType.HANDHELD,
        formFactor = FormFactors.PHONE,
        screenX = 300,
        screenY = 400,
        screenDensity = 500,
        deviceAvailabilityEstimateSeconds = 3000,
      ),
      DeviceInfo(
        id = "id4",
        brand = "Google",
        name = "Pixel Watch",
        labId = "google",
        manufacturer = "Google",
        codename = "watch",
        api = 33,
        type = DeviceType.WEAR,
        formFactor = FormFactors.WEAR,
        screenX = 50,
        screenY = 100,
        screenDensity = 150,
        deviceAvailabilityEstimateSeconds = 30,
      ),
      DeviceInfo(
        id = "id4",
        brand = "Google",
        name = "Pixel Watch",
        labId = "google",
        manufacturer = "Google",
        codename = "watch",
        api = 34,
        type = DeviceType.WEAR,
        formFactor = FormFactors.WEAR,
        screenX = 50,
        screenY = 100,
        screenDensity = 150,
        deviceAvailabilityEstimateSeconds = null,
        accessStatus = listOf("EULA_NOT_ACCEPTED"),
      ),
    )
  }

  val extendedDeviceInfoListProvider = {
    deviceInfoListProvider() +
      listOf(
        DeviceInfo(
          "id5",
          "SomeBrand",
          "SomeName",
          "somelab",
          "SomeBrand",
          "codename5",
          33,
          DeviceType.HANDHELD,
          FormFactors.PHONE,
          300,
          400,
          500,
          10,
          tags = listOf("preview=33"),
        ),
        DeviceInfo(
          "id6",
          "Boop",
          "Foop",
          "LabNameCapitalized",
          "woop",
          "codename6",
          33,
          DeviceType.HANDHELD,
          FormFactors.PHONE,
          300,
          400,
          500,
          10,
          tags = listOf("deprecated=33", "private"),
        ),
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
      formFactor = "PHONE"
      screenX = 100
      screenY = 200
      screenDensity = 300
      perVersionInfo = listOf(generatePerVersionInfo())
    }

  private val wearable =
    AndroidModel().apply {
      manufacturer = "Google"
      name = "Google Watch"
      brand = "Google"
      codename = "watch"
      id = codename
      supportedVersionIds = listOf("32")
      form = "PHYSICAL"
      formFactor = "WEARABLE"
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
      formFactor = "TABLET"
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
      formFactor = "PHONE"
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
      formFactor = "PHONE"
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
      supportedVersionIds = listOf("30")
      form = "PHYSICAL"
      formFactor = "PHONE"
      screenX = 100
      screenY = 200
      screenDensity = 300
      perVersionInfo =
        listOf(generatePerVersionInfo().apply { directAccessVersionInfo?.apply { minimumAndroidStudioVersion = "999.9999.99" } })
    }

  private val phoneWithNoCapacity =
    AndroidModel().apply {
      manufacturer = "Google"
      name = "Phone with no capacity"
      brand = "Google"
      codename = "phone-with-no-capacity"
      id = codename
      supportedVersionIds = listOf("30")
      form = "PHYSICAL"
      formFactor = "PHONE"
      screenX = 100
      screenY = 200
      screenDensity = 300
      perVersionInfo = listOf(generatePerVersionInfo().apply { deviceCapacity = "DEVICE_CAPACITY_NONE" })
    }

  private val phoneWithOemEulaNotAccepted =
    AndroidModel().apply {
      manufacturer = "Google"
      name = "Phone with EULA not approved"
      brand = "Bob"
      codename = "phone-with-eula-not-approved"
      id = codename
      supportedVersionIds = listOf("30")
      form = "PHYSICAL"
      formFactor = "PHONE"
      screenX = 100
      screenY = 200
      screenDensity = 300
      perVersionInfo = listOf(generatePerVersionInfo())
      accessDeniedReasons = listOf("EULA_NOT_ACCEPTED")
      labInfo = LabInfo().apply { name = "myLab" }
    }

  private val phoneWithLabAccessDenied =
    AndroidModel().apply {
      manufacturer = "Google"
      name = "Phone with access denied"
      brand = "Exclusive Brand"
      codename = "phone-with-access-denied"
      id = codename
      supportedVersionIds = listOf("30")
      form = "PHYSICAL"
      formFactor = "PHONE"
      screenX = 100
      screenY = 200
      screenDensity = 300
      perVersionInfo = listOf(generatePerVersionInfo())
      accessDeniedReasons = listOf("INSUFFICIENTLY_AWESOME")
      labInfo = LabInfo().apply { name = "myOtherLab" }
    }

  private fun generatePerVersionInfo(api: String = "32", isDirectAccessSupported: Boolean = true) =
    PerAndroidVersionInfo().apply {
      deviceCapacity = "DEVICE_CAPACITY_HIGH"
      versionId = api
      directAccessVersionInfo = DirectAccessVersionInfo().apply { directAccessSupported = isDirectAccessSupported }
      interactiveDeviceAvailabilityEstimate = "30s"
    }

  val androidDeviceCatalog =
    createDeviceCatalog(
      phone,
      wearable,
      tablet,
      phoneLessThanApi26,
      phoneSupportedOnHigherASVersion,
      phoneWithNoCapacity,
      phoneWithLabAccessDenied,
      phoneWithOemEulaNotAccepted,
    )

  val androidDeviceCatalogWithMissingFields =
    createDeviceCatalog(phone, wearable, tablet, invalidDevice, phoneLessThanApi26, phoneSupportedOnHigherASVersion)

  private fun createDeviceCatalog(vararg androidModels: AndroidModel) = AndroidDeviceCatalog(models = androidModels.toList())

  val DirectAccessDeviceHandle.connectionState: DirectAccessConnection.ConnectionState
    get() = connection.state.value.connection

  val DirectAccessDeviceHandle.reservation: Reservation
    get() = connection.state.value.reservation

  val DirectAccessDeviceHandle.deviceName: String
    get() = sourceTemplate.properties.title

  fun getNotifications(project: Project): Array<Notification> =
    NotificationsManager.getNotificationsManager().getNotificationsOfType(Notification::class.java, project)

  suspend fun Project.refreshReservations() =
    service<DirectAccessService>().cloudProjectManager.value?.reservationListFlowWithException?.refresh()

  suspend fun Project.showAllTemplates(verifyOldSelection: (List<DeviceSelection>) -> Unit = {}) {
    yieldUntil { service<DirectAccessService>().deviceSelectionListFlow.value.isNotEmpty() }
    val deviceSelectionListFlow = service<DirectAccessService>().deviceSelectionListFlow
    verifyOldSelection(deviceSelectionListFlow.value)
    deviceSelectionListFlow.update { it.map { selection -> DeviceSelection(true, selection.deviceInfo) } }
  }
}
