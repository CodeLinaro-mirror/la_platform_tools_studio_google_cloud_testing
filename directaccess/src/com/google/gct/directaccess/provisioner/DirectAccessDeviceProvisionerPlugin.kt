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
package com.google.gct.directaccess.provisioner

import com.android.adblib.ConnectedDevice
import com.android.sdklib.deviceprovisioner.CreateDeviceTemplateAction
import com.android.sdklib.deviceprovisioner.DeviceAction
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceProvisionerPlugin
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.sdklib.deviceprovisioner.DeviceTemplate
import com.android.sdklib.deviceprovisioner.Extension
import com.android.sdklib.deviceprovisioner.ExtensionRegistry
import com.android.tools.analytics.UsageTracker
import com.android.tools.idea.concurrency.AndroidDispatchers
import com.android.tools.idea.concurrency.createChildScope
import com.android.tools.idea.deviceprovisioner.DeviceProvisionerService
import com.android.tools.idea.deviceprovisioner.StudioDefaultDeviceActionPresentation
import com.android.tools.idea.flags.StudioFlags
import com.google.common.annotations.VisibleForTesting
import com.google.devtools.testing.v1.DeviceSession as Reservation
import com.google.gct.directaccess.DirectAccessOnboardingService
import com.google.gct.directaccess.DirectAccessService
import com.google.gct.directaccess.DirectAccessServiceSetup
import com.google.gct.directaccess.directAccessCloudProjectManager
import com.google.gct.directaccess.ui.AddDirectAccessDeviceDialog
import com.google.gct.directaccess.ui.SelectDeviceDialog
import com.google.gct.login2.GoogleLoginService
import com.google.gct.login2.VetoableLogoutListener
import com.google.services.firebase.directaccess.client.isClosed
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import com.google.wireless.android.sdk.stats.DeviceManagerEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.time.withTimeout
import kotlinx.coroutines.time.withTimeoutOrNull
import kotlinx.coroutines.withContext

const val PLUGIN_ID = "FirebaseDirectAccess"
private val FAST_TASK_TIMEOUT = Duration.ofSeconds(2)
private val PRESELECTED_DEVICE_KEY_SET = setOf("shiba/34", "felix/33", "b0q/33", "gts8uwifi/33")

/**
 * Provides direct access to physical devices run by Firebase. Supports configuring direct access
 * device templates and activating / deactivating them.
 */
class DirectAccessDeviceProvisionerPlugin(
  private val scope: CoroutineScope,
  private val project: Project,
) : DeviceProvisionerPlugin, Disposable {
  // TODO: find a proper priority
  override val priority: Int = 120

  private val extensionRegistry = ExtensionRegistry(this)

  override fun <T : Extension> extension(extensionClass: Class<T>): T? =
    extensionRegistry.extension(extensionClass)

  private val _devices = MutableStateFlow(emptyList<DeviceHandle>())
  override val devices: StateFlow<List<DeviceHandle>> = _devices
  private val _templates = MutableStateFlow(emptyList<DirectAccessDeviceTemplate>())
  override val templates: StateFlow<List<DeviceTemplate>> = _templates

  private val reservationsFlow = MutableStateFlow<List<Reservation>?>(null)

  // A flow of map for devices that are accessible with the current login state and cloud project.
  // The mapping is from a string of device id to its full device information.
  private val accessibleDeviceInfoMapFlow = MutableStateFlow(mapOf<String, DeviceInfo>())
  private val cachedTemplatesMap = mutableMapOf<String, DirectAccessDeviceTemplate>()

  private val vetoableLogOutListener =
    object : VetoableLogoutListener {
      private val deviceList: List<DirectAccessDeviceHandle>
        get() =
          devices.value.filterIsInstance<DirectAccessDeviceHandle>().filter {
            it.state is DeviceState.Connected
          }

      override fun canLogout(): Boolean {
        val (title, message) =
          when (deviceList.size) {
            0 -> return true
            1 -> {
              Pair(
                "Firebase ${deviceList[0].sourceTemplate.properties.title} is connected",
                "Return and erase the device to end the session?\nActive sessions consume quota after Android Studio is closed.",
              )
            }
            else -> {
              Pair(
                "Firebase devices connected",
                "Return and erase the devices to end the session?\nActive sessions consume quota after Android Studio is closed.",
              )
            }
          }
        return Messages.showYesNoDialog(project, message, title, null) == Messages.YES
      }

      override fun isLoggingOut() {
        runBlocking {
          deviceList
            .map {
              scope.launch {
                withContext(NonCancellable) {
                  withTimeout(FAST_TASK_TIMEOUT) { it.reservationAction.endReservation() }
                }
              }
            }
            .joinAll()
        }
      }
    }

  init {
    Disposer.register(project.service<DeviceProvisionerService>(), this)
    // Clean up remaining templates when scope is cancelled.
    scope.coroutineContext.job.invokeOnCompletion { _templates.update { listOf() } }

    // Select project from login onboarding tasks.
    if (StudioFlags.DIRECT_ACCESS_CREATE_PROJECT.get()) {
      scope.launch {
        service<DirectAccessOnboardingService>().taskFlow.filterNotNull().collect { task ->
          if (task.isPending) {
            project
              .service<DirectAccessService>()
              .deviceSelectionListFlow
              .takeWhile { it.isEmpty() }
              .collect()
            project.service<DirectAccessService>().maybeApplyDefaultDevices()
          } else {
            project.service<DirectAccessService>().selectCloudProject(task.cloudProject.name)
          }
        }
      }
    }

    scope.launch {
      // Create a flow of valid gcp projects.
      project.service<DirectAccessService>().cloudProjectManager.collectLatest { cloudProjectManager
        ->
        if (cloudProjectManager == null) {
          accessibleDeviceInfoMapFlow.value = mapOf()
          reservationsFlow.value = null
        } else {
          launch {
            cloudProjectManager.accessibleDeviceInfoListFlow.stateFlow.collect {
              newAccessibleDeviceInfoList ->
              accessibleDeviceInfoMapFlow.value =
                newAccessibleDeviceInfoList.groupBy { it.key }.mapValues { it.value.first() }
            }
          }
          launch {
            cloudProjectManager.reservationListFlowWithException.stateFlow.collect { pair ->
              val newReservations = pair.first
              reservationsFlow.value = newReservations
              if (newReservations != null) {
                matchReservations(_templates.value, newReservations)
              }
            }
          }
        }
      }
    }

    scope.launch {
      accessibleDeviceInfoMapFlow.collect { accessibleDeviceInfoMap ->
        project.service<DirectAccessService>().deviceSelectionListFlow.update {
          oldDeviceSelectionList ->
          // A device will occur in the new list if it was selected with the previous project
          // or accessible with the new project.

          // Keeps the selected devices in order and updates their [DeviceInfo].
          val selectedDeviceSelectionList =
            oldDeviceSelectionList
              .filter { it.isSelected }
              .map { oldSelection ->
                accessibleDeviceInfoMap[oldSelection.deviceInfo.key]?.let { newDeviceInfo ->
                  oldSelection.copy(deviceInfo = newDeviceInfo)
                } ?: oldSelection
              }
          val selectedDeviceKeySet = selectedDeviceSelectionList.map { it.deviceInfo.key }.toSet()

          val unSelectedDeviceList =
            accessibleDeviceInfoMap.values
              .ifEmpty {
                try {
                  // If no devices are accessible with the new project, use the public device
                  // list instead.
                  service<DirectAccessServiceSetup>().getAccessibleDeviceInfoList(null)
                } catch (e: Exception) {
                  if (e is ControlFlowException) {
                    throw e
                  }
                  thisLogger().error(e)
                  listOf()
                }
              }
              .filter { it.key !in selectedDeviceKeySet }
          selectedDeviceSelectionList + unSelectedDeviceList.map { DeviceSelection(false, it) }
        }
      }
    }

    // Update templates with enabledDevicesFlow.
    scope.launch {
      project.service<DirectAccessService>().deviceSelectionListFlow.collect { deviceSelectionList
        ->
        val newTemplates =
          _templates.updateAndGet {
            val existingDeviceInfoMap = it.groupBy { template -> template.deviceInfo }
            deviceSelectionList
              .filter { selection -> selection.isSelected }
              .map { selection -> selection.deviceInfo }
              .map { deviceInfo ->
                existingDeviceInfoMap[deviceInfo]?.firstOrNull()
                  ?: cachedTemplatesMap.computeIfAbsent(deviceInfo.key) {
                    val templateScope = scope.createChildScope(isSupervisor = true)
                    val deviceInfoFlow =
                      MutableStateFlow(deviceInfo).also { flow ->
                        templateScope.launch {
                          accessibleDeviceInfoMapFlow.collect { deviceMap ->
                            flow.update { oldDeviceInfo ->
                              deviceMap[deviceInfo.key] ?: oldDeviceInfo.copy(isInCatalog = false)
                            }
                          }
                        }
                      }

                    DirectAccessDeviceTemplate(
                      project,
                      deviceInfoFlow,
                      _devices,
                      templateScope,
                      reservationsFlow.map { reservations -> reservations != null },
                    )
                  }
              }
          }
        // Refresh the reservation flow to match the new selection list.
        // TODO (b/338286373) remove reservationListFlowWithException from CloudProjectManager.
        matchReservations(
          newTemplates,
          project.directAccessCloudProjectManager
            ?.reservationListFlowWithException
            ?.refresh()
            ?.first ?: listOf(),
        )
      }
    }

    service<GoogleLoginService>().addVetoableLogoutListener(vetoableLogOutListener)
  }

  @VisibleForTesting
  suspend fun matchReservations(
    templates: List<DirectAccessDeviceTemplate>,
    reservations: List<Reservation>?,
  ): Unit =
    withContext(NonCancellable) {
      if (reservations == null) {
        return@withContext
      }
      // Applies a NonCancellable job to the coroutine context so that if this method is cancelled
      // and called again from an outside `collectLatest` block, `createDeviceHandleIfAbsent` will
      // not be called concurrently for the same template.
      val templateMap =
        templates.groupBy { template ->
          template.deviceInfo.let { it.codename to it.api.toString() }
        }

      reservations
        .filter { reservation -> !reservation.state.isClosed() && reservation.hasAndroidDevice() }
        .map { reservation ->
          val key = reservation.androidDevice.let { it.androidModelId to it.androidVersionId }
          templateMap[key]?.firstOrNull()?.let { template ->
            // Create handle without blocking iteration
            launch { template.createDeviceHandleIfAbsent(reservation.name) }
          }
            ?: launch {
              // Select the device with active reservation to create its template.
              project.service<DirectAccessService>().deviceSelectionListFlow.update { selectionList
                ->
                selectionList.map { selection ->
                  if (selection.deviceInfo.let { it.codename to it.api.toString() } == key) {
                    DeviceSelection(true, selection.deviceInfo)
                  } else selection
                }
              }
            }
        }
        .joinAll()
    }

  override suspend fun claim(device: ConnectedDevice): DeviceHandle? {
    val sn = device.deviceInfoFlow.value.serialNumber
    if (sn.matches(Regex("^localhost:\\d+$"))) {
      val port = sn.substringAfter(':').toIntOrNull() ?: return null
      return devices.value.filterIsInstance<DirectAccessDeviceHandle>().firstOrNull {
        it.claim(port, device)
      }
        ?: project.directAccessCloudProjectManager
          ?.connectionManager
          ?.connections
          ?.get(port)
          ?.let { connection ->
            // Create a handle with the ConnectedDevice if its port is managed by the
            // DirectAccessConnectionManager.
            val reservation = connection.state.value.reservation
            val androidDevice = reservation.androidDevice
            if (!reservation.state.isClosed() && reservation.hasAndroidDevice()) {
              // Wait for the target template becoming available before creating a device handle.
              withTimeoutOrNull(FAST_TASK_TIMEOUT) {
                  _templates
                    .mapNotNull { list ->
                      list.firstOrNull {
                        it.deviceInfo.codename == androidDevice.androidModelId &&
                          it.deviceInfo.api.toString() == androidDevice.androidVersionId
                      }
                    }
                    .first()
                }
                ?.createDeviceHandleIfAbsent(reservation.name)
            } else null
          }
    }
    return null
  }

  override val createDeviceTemplateAction =
    object : CreateDeviceTemplateAction {
      override suspend fun create() {
        if (StudioFlags.DIRECT_ACCESS_DEVICE_CATALOG_ENABLED.get()) {
          withContext(AndroidDispatchers.uiThread) {
            val deviceSelectionListFlow =
              project.service<DirectAccessService>().deviceSelectionListFlow
            if (AddDirectAccessDeviceDialog(project, deviceSelectionListFlow).showAndGet()) {
              UsageTracker.log(
                AndroidStudioEvent.newBuilder()
                  .setKind(AndroidStudioEvent.EventKind.DEVICE_MANAGER)
                  .setDeviceManagerEvent(
                    DeviceManagerEvent.newBuilder()
                      .setKind(DeviceManagerEvent.EventKind.DIRECT_ACCESS_ADD_DEVICE_ACTION)
                  )
              )
            }
          }
        } else {
          withContext(AndroidDispatchers.uiThread) { SelectDeviceDialog(project).show() }
        }
      }

      override val presentation: StateFlow<DeviceAction.Presentation> =
        MutableStateFlow(
            StudioDefaultDeviceActionPresentation.fromContext()
              .copy(label = "Select Remote Devices")
          )
          .asStateFlow()
    }

  override fun dispose() {
    service<GoogleLoginService>().removeVetoableLogoutListener(vetoableLogOutListener)
  }
}
