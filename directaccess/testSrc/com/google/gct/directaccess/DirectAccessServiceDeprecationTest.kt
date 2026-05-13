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
import com.android.sdklib.deviceprovisioner.DeviceId
import com.android.sdklib.deviceprovisioner.DeviceProperties
import com.android.sdklib.deviceprovisioner.DeviceTemplate
import com.android.sdklib.deviceprovisioner.TemplateActivationAction
import com.android.testutils.VirtualTimeScheduler
import com.android.tools.adtui.swing.findDescendant
import com.android.tools.analytics.TestUsageTracker
import com.android.tools.analytics.UsageTracker
import com.android.tools.idea.concurrency.createCoroutineScope
import com.android.tools.idea.deviceprovisioner.NotificationBannersExtension
import com.android.tools.idea.gservices.DevServicesDeprecationData
import com.android.tools.idea.gservices.DevServicesDeprecationStatus
import com.android.tools.idea.testing.disposable
import com.google.api.services.testing.model.AndroidModel
import com.google.api.services.testing.model.PerAndroidVersionInfo
import com.google.common.truth.Truth.assertThat
import com.google.gct.directaccess.provisioner.DirectAccessDeviceProvisionerPlugin
import com.google.gct.directaccess.ui.actions.SelectProjectAction
import com.google.wireless.android.sdk.stats.DevServiceDeprecationInfo
import com.google.wireless.android.sdk.stats.DirectAccessUsageEvent.DirectAccessUsageEventType
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.replaceService
import com.intellij.ui.InplaceButton
import com.intellij.util.ui.JBUI.CurrentTheme.Banner
import java.awt.event.MouseEvent
import java.time.Duration
import javax.swing.JPanel
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class DirectAccessServiceDeprecationTest {

  @get:Rule val projectRule = ProjectRule()

  private val session = FakeAdbSession()
  private var deprecationProto: DevServicesDeprecationData =
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
  private val fakeTemplate =
    object : DeviceTemplate {
      override val id = DeviceId("", true, "")
      override val properties = DeviceProperties.buildForTest { icon = AllIcons.General.Warning }
      override val activationAction = mock<TemplateActivationAction>()
      override val editAction = null
    }

  private lateinit var tracker: TestUsageTracker
  private lateinit var mockDeprecationService: DirectAccessDeprecationState
  private lateinit var deprecationDataFlow: MutableStateFlow<DevServicesDeprecationData>
  private lateinit var serviceEnabledFlow: StateFlow<Boolean>
  private lateinit var scope: CoroutineScope

  @Before
  fun setUp() {
    scope = projectRule.disposable.createCoroutineScope()
    mockDeprecationService = mock()
    deprecationDataFlow = MutableStateFlow(DevServicesDeprecationData.EMPTY)
    serviceEnabledFlow = deprecationDataFlow.map { data -> !data.isUnsupported() }.stateIn(scope, SharingStarted.Eagerly, true)
    doAnswer { deprecationDataFlow.asStateFlow() }.whenever(mockDeprecationService).serviceDeprecationData
    doAnswer { serviceEnabledFlow }.whenever(mockDeprecationService).isServiceEnabledFlow
    ApplicationManager.getApplication()
      .replaceService(DirectAccessDeprecationState::class.java, mockDeprecationService, projectRule.disposable)

    val mockCloudClientService = mock<CloudClientService>()
    doReturn(listOf(model to PerAndroidVersionInfo().apply { versionId = "35" }))
      .whenever(mockCloudClientService)
      .getAvailableDevices(any(), any())
    ApplicationManager.getApplication().replaceService(CloudClientService::class.java, mockCloudClientService, projectRule.disposable)

    ApplicationManager.getApplication()
      .replaceService(DirectAccessServiceSetup::class.java, DirectAccessServiceSetup(), projectRule.disposable)

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
    templates.value = listOf(fakeTemplate)
    deprecationDataFlow.update { deprecationProto.copy(status = DevServicesDeprecationStatus.UNSUPPORTED) }
    val banners = plugin.getNotificationBanners()

    // Get the first non-empty value
    val banner = banners.first { it.isNotEmpty() }.first()
    withContext(Dispatchers.EDT) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }
    assertThat(banner.text).isEqualTo("<html>${deprecationProto.description}</html>")
    assertThat(banner.background).isEqualTo(Banner.ERROR_BACKGROUND)

    findUsageEvent().let {
      assertThat(it.deprecationStatus).isEqualTo(DevServiceDeprecationInfo.DeprecationStatus.UNSUPPORTED)
      assertThat(it.deliveryType).isEqualTo(DevServiceDeprecationInfo.DeliveryType.BANNER)
      assertThat(it.userNotified).isTrue()
      assertThat(it.hasMoreInfoClicked()).isFalse()
      assertThat(it.hasUpdateClicked()).isFalse()
    }

    val updateLink = banner.findLabelByName("Update Android Studio")
    withContext(Dispatchers.EDT) { updateLink?.doClick() }
    findUsageEvent().let {
      assertThat(it.deprecationStatus).isEqualTo(DevServiceDeprecationInfo.DeprecationStatus.UNSUPPORTED)
      assertThat(it.deliveryType).isEqualTo(DevServiceDeprecationInfo.DeliveryType.BANNER)
      assertThat(it.hasUserNotified()).isFalse()
      assertThat(it.hasMoreInfoClicked()).isFalse()
      assertThat(it.updateClicked).isTrue()
    }

    val moreInfoLink = banner.findLabelByName("More info")
    withContext(Dispatchers.EDT) { moreInfoLink?.doClick() }
    findUsageEvent().let {
      assertThat(it.deprecationStatus).isEqualTo(DevServiceDeprecationInfo.DeprecationStatus.UNSUPPORTED)
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
  fun testBannerChangesFromWarningToErrorOnDataChange() = runBlockingWithTimeout {
    deprecationDataFlow.update { DevServicesDeprecationData.EMPTY }
    val plugin = DirectAccessDeviceProvisionerPlugin(session.scope, projectRule.project)
    val templates = plugin.templates as MutableStateFlow

    // Show the banner when there are templates.
    templates.value = listOf(fakeTemplate)

    var banners = plugin.getNotificationBanners()

    assertThat(banners.value).isEmpty()
    withContext(Dispatchers.EDT) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }

    deprecationDataFlow.update { deprecationProto.copy(status = DevServicesDeprecationStatus.DEPRECATED) }
    yieldUntil { banners.value.isNotEmpty() }
    val banner = banners.first { it.isNotEmpty() }.first()
    assertThat(banner.background).isEqualTo(Banner.WARNING_BACKGROUND)

    deprecationDataFlow.update { deprecationProto.copy(status = DevServicesDeprecationStatus.UNSUPPORTED) }
    yieldUntil { banners.first { it.isNotEmpty() }.first().background == Banner.ERROR_BACKGROUND }
  }

  @Test
  fun testDeviceList() = runBlockingWithTimeout {
    configureDevServicesDeprecationStatus(DevServicesDeprecationStatus.UNSUPPORTED)
    assertThat(service<DirectAccessServiceSetup>().getAccessibleDeviceInfoList("any")).isEmpty()
  }

  @Test
  fun disableAddDeviceAction() = runBlockingWithTimeout {
    configureDevServicesDeprecationStatus(DevServicesDeprecationStatus.UNSUPPORTED)
    val plugin = DirectAccessDeviceProvisionerPlugin(session.scope, projectRule.project)
    assertThat(plugin.createDeviceTemplateAction.presentation.value.enabled).isFalse()
  }

  @Test
  fun disableSelectProjectActionWhenUnsupported() = runBlocking {
    deprecationDataFlow.update { deprecationProto.copy(status = DevServicesDeprecationStatus.UNSUPPORTED) }
    yieldUntil { !service<DirectAccessDeprecationState>().isServiceEnabledFlow.value }
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
    assertThat(event.presentation.text).isEqualTo("Firebase Device Streaming is no longer compatible with this version of Android Studio.")
  }

  @Test
  fun testActionEnabledWhenDeprecated() {
    deprecationProto = deprecationProto.copy(status = DevServicesDeprecationStatus.DEPRECATED)
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
    assertThat(event.presentation.isEnabled).isTrue()
  }

  @Test
  fun testDeprecationBannerCanBeDismissed() = runBlockingWithTimeout {
    configureDevServicesDeprecationStatus(DevServicesDeprecationStatus.DEPRECATED)
    val plugin = DirectAccessDeviceProvisionerPlugin(session.scope, projectRule.project)
    val templates = plugin.templates as MutableStateFlow

    // Show the banner when there are templates.
    templates.value = listOf(fakeTemplate)
    val banners = plugin.getNotificationBanners()
    // Get the first non-empty value
    val banner = banners.first { it.isNotEmpty() }.first()

    assertThat(banner.isVisible).isTrue()
    assertThat(banner.background).isEqualTo(Banner.WARNING_BACKGROUND)

    val closeButton = banner.findDescendant<InplaceButton>() ?: fail("Close button not found")
    withContext(Dispatchers.EDT) { closeButton.doClick() }

    assertThat(banner.isVisible).isFalse()
  }

  @Test
  fun testDeprecationBannerRemovedWhenDataChangesToSupported() = runBlockingWithTimeout {
    configureDevServicesDeprecationStatus(DevServicesDeprecationStatus.DEPRECATED)
    val plugin = DirectAccessDeviceProvisionerPlugin(session.scope, projectRule.project)
    val templates = plugin.templates as MutableStateFlow

    // Show the banner when there are templates.
    templates.value = listOf(fakeTemplate)
    val banners = plugin.getNotificationBanners()
    // Get the first non-empty value
    val banner = banners.first { it.isNotEmpty() }.first()

    assertThat(banner.isVisible).isTrue()
    assertThat(banner.background).isEqualTo(Banner.WARNING_BACKGROUND)

    deprecationDataFlow.update { deprecationProto.copy(status = DevServicesDeprecationStatus.SUPPORTED) }

    yieldUntil { plugin.getNotificationBanners().value.isEmpty() }
  }

  private suspend fun findUsageEvent(): DevServiceDeprecationInfo {
    var info: DevServiceDeprecationInfo? = null
    yieldUntil {
      val event = tracker.usages.lastOrNull { it.studioEvent.directAccessUsageEvent.type == DirectAccessUsageEventType.SERVICE_DEPRECATION }
      info = event?.studioEvent?.directAccessUsageEvent?.devServiceDeprecationInfo
      info != null
    }
    return info!!
  }

  private suspend fun configureDevServicesDeprecationStatus(status: DevServicesDeprecationStatus) {
    deprecationDataFlow.update { deprecationProto.copy(status = status) }
    if (status == DevServicesDeprecationStatus.UNSUPPORTED) {
      yieldUntil(Duration.ofSeconds(2)) { !service<DirectAccessDeprecationState>().isServiceEnabledFlow.value }
    }
  }

  private fun DirectAccessDeviceProvisionerPlugin.getNotificationBanners() =
    extension(NotificationBannersExtension::class.java)!!.notificationBanners
}
