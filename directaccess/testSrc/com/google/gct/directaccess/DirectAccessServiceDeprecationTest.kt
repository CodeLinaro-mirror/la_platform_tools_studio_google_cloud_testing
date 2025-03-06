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
package com.google.gct.directaccess

import com.android.adblib.testing.FakeAdbSession
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.testutils.VirtualTimeScheduler
import com.android.tools.analytics.TestUsageTracker
import com.android.tools.analytics.UsageTracker
import com.android.tools.idea.deviceprovisioner.NotificationBannersExtension
import com.android.tools.idea.gservices.DevServicesDeprecationData
import com.android.tools.idea.gservices.DevServicesDeprecationDataProvider
import com.android.tools.idea.gservices.DevServicesDeprecationStatus
import com.android.tools.idea.testing.disposable
import com.google.api.services.testing.model.AndroidModel
import com.google.api.services.testing.model.PerAndroidVersionInfo
import com.google.common.truth.Truth.assertThat
import com.google.gct.directaccess.provisioner.DirectAccessDeviceProvisionerPlugin
import com.google.gct.directaccess.provisioner.DirectAccessDeviceTemplate
import com.google.gct.directaccess.ui.actions.SelectProjectAction
import com.google.wireless.android.sdk.stats.DevServiceDeprecationInfo
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.replaceService
import java.awt.event.MouseEvent
import javax.swing.JPanel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class DirectAccessServiceDeprecationTest {

  @get:Rule val projectRule = ProjectRule()

  private val session = FakeAdbSession()
  private val deprecationProto =
    DevServicesDeprecationData(
      header = "",
      description = "my description",
      moreInfoUrl = "link",
      showUpdateAction = true,
      status = DevServicesDeprecationStatus.UNSUPPORTED,
    )

  private val model =
    AndroidModel().apply {
      id = "id"
      brand = "brand"
      name = "name"
      manufacturer = "manufacturer"
      codename = "codename"
      screenX = 100
      screenY = 100
      screenDensity = 1000
    }

  private lateinit var tracker: TestUsageTracker

  @Before
  fun setUp() {
    val mockDevServicesDeprecationDataProvider = mock<DevServicesDeprecationDataProvider>()
    doReturn(deprecationProto)
      .whenever(mockDevServicesDeprecationDataProvider)
      .getCurrentDeprecationData("directaccess/directaccess")
    ApplicationManager.getApplication()
      .replaceService(
        DevServicesDeprecationDataProvider::class.java,
        mockDevServicesDeprecationDataProvider,
        projectRule.disposable,
      )

    ApplicationManager.getApplication()
      .replaceService(
        DirectAccessDeprecationState::class.java,
        DirectAccessDeprecationState(),
        projectRule.disposable,
      )

    val mockCloudClientService = mock<CloudClientService>()
    doReturn(listOf(model to PerAndroidVersionInfo().apply { versionId = "35" }))
      .whenever(mockCloudClientService)
      .getAvailableDevices(any(), any())
    ApplicationManager.getApplication()
      .replaceService(
        CloudClientService::class.java,
        mockCloudClientService,
        projectRule.disposable,
      )

    ApplicationManager.getApplication()
      .replaceService(
        DirectAccessServiceSetup::class.java,
        DirectAccessServiceSetup(),
        projectRule.disposable,
      )

    tracker = TestUsageTracker(VirtualTimeScheduler())
    UsageTracker.setWriterForTest(tracker)
  }

  @After
  fun tearDown() {
    session.close()
  }

  @Test
  fun testNotificationBanner() = runBlockingWithTimeout {
    val plugin = DirectAccessDeviceProvisionerPlugin(session.scope, projectRule.project)
    val templates = plugin.templates as MutableStateFlow

    // Show the banner when there are templates.
    templates.value = listOf(mock<DirectAccessDeviceTemplate>())
    val banners = plugin.extension(NotificationBannersExtension::class.java)!!.notificationBanners
    yieldUntil { banners.value.size == 1 }
    val banner = banners.value.first()
    assertThat(banner.text).isEqualTo("<html>${deprecationProto.description}</html>")

    findUsageEvent().let {
      assertThat(it.deprecationStatus)
        .isEqualTo(DevServiceDeprecationInfo.DeprecationStatus.UNSUPPORTED)
      assertThat(it.deliveryType).isEqualTo(DevServiceDeprecationInfo.DeliveryType.BANNER)
      assertThat(it.userNotified).isTrue()
      assertThat(it.hasMoreInfoClicked()).isFalse()
      assertThat(it.hasUpdateClicked()).isFalse()
    }

    val updateLink = banner.findLabelByName("Update")
    updateLink?.doClick()
    findUsageEvent().let {
      assertThat(it.deprecationStatus)
        .isEqualTo(DevServiceDeprecationInfo.DeprecationStatus.UNSUPPORTED)
      assertThat(it.deliveryType).isEqualTo(DevServiceDeprecationInfo.DeliveryType.BANNER)
      assertThat(it.hasUserNotified()).isFalse()
      assertThat(it.hasMoreInfoClicked()).isFalse()
      assertThat(it.updateClicked).isTrue()
    }

    val moreInfoLink = banner.findLabelByName("More info")
    moreInfoLink?.doClick()
    findUsageEvent().let {
      assertThat(it.deprecationStatus)
        .isEqualTo(DevServiceDeprecationInfo.DeprecationStatus.UNSUPPORTED)
      assertThat(it.deliveryType).isEqualTo(DevServiceDeprecationInfo.DeliveryType.BANNER)
      assertThat(it.hasUserNotified()).isFalse()
      assertThat(it.moreInfoClicked).isTrue()
      assertThat(it.hasUpdateClicked()).isFalse()
    }

    // Hide the banner when all remote templates are removed.
    templates.value = listOf()
    yieldUntil { banners.value.isEmpty() }
  }

  @Test
  fun testDeviceList() {
    assertThat(service<DirectAccessServiceSetup>().getAccessibleDeviceInfoList("any")).isEmpty()
  }

  @Test
  fun disableAddDeviceAction() {
    val plugin = DirectAccessDeviceProvisionerPlugin(session.scope, projectRule.project)
    assertThat(plugin.createDeviceTemplateAction.presentation.value.enabled).isFalse()
  }

  @Test
  fun disableSelectProjectAction() {
    val action = SelectProjectAction()
    // Click the device selection button.
    val mouseEvent = MouseEvent(JPanel(), MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, 1, true, 0)
    val event =
      TestActionEvent.createTestEvent(
        action,
        {
          when (it) {
            CommonDataKeys.PROJECT.name -> projectRule.project
            else -> null
          }
        },
        mouseEvent,
      )
    action.update(event)
    assertThat(event.presentation.isEnabled).isFalse()
  }

  private suspend fun findUsageEvent(): DevServiceDeprecationInfo {
    var info: DevServiceDeprecationInfo? = null
    yieldUntil {
      val event =
        tracker.usages.lastOrNull {
          it.studioEvent.directAccessUsageEvent.type ==
            DirectAccessUsageEventType.SERVICE_DEPRECATION
        }
      info = event?.studioEvent?.directAccessUsageEvent?.devServiceDeprecationInfo
      info != null
    }
    return info!!
  }
}
