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
package com.google.gct.directaccess.provisioner

import com.android.sdklib.deviceprovisioner.Resolution
import com.android.sdklib.devices.Abi
import com.android.tools.idea.adddevicedialog.DeviceProfile
import com.android.tools.idea.adddevicedialog.DeviceSource
import com.android.tools.idea.adddevicedialog.DeviceSourceProvider
import com.android.tools.idea.adddevicedialog.WizardAction
import com.android.tools.idea.adddevicedialog.WizardPageScope
import com.google.common.collect.Range
import com.google.gct.directaccess.DirectAccessService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.update

/** Implements support for Direct Access devices in the Add Device dialog. */
class DirectAccessDeviceSource(private val project: Project) : DeviceSource {
  class Provider : DeviceSourceProvider {
    override fun createDeviceSource(project: Project?): DeviceSource? =
      project?.let { DirectAccessDeviceSource(it) }
  }

  override val profiles: List<DeviceProfile>
    get() {
      return project.service<DirectAccessService>().deviceSelectionListFlow.value.map {
        deviceSelection ->
        DirectAccessDeviceProfile(deviceSelection.deviceInfo, deviceSelection.isSelected)
      }
    }

  override fun WizardPageScope.selectionUpdated(device: DeviceProfile) {
    nextAction = WizardAction.Disabled
    finishAction =
      if (device.isAlreadyPresent) WizardAction.Disabled
      else
        WizardAction {
          if (device is DirectAccessDeviceProfile) {
            project.service<DirectAccessService>().deviceSelectionListFlow.update { devices ->
              devices.map {
                if (it.deviceInfo.key == device.key) it.copy(isSelected = true) else it
              }
            }
            close()
          }
        }
  }
}

internal data class DirectAccessDeviceProfile(
  override val apiRange: Range<Int>,
  override val manufacturer: String,
  override val name: String,
  override val resolution: Resolution,
  override val displayDensity: Int,
  override val abis: List<Abi>,
  override val isAlreadyPresent: Boolean,
  override val availabilityEstimate: Duration,
  val key: String,
) : DeviceProfile {
  constructor(
    deviceInfo: DeviceInfo,
    isAlreadyPresent: Boolean,
  ) : this(
    apiRange = Range.singleton(deviceInfo.api),
    manufacturer = deviceInfo.manufacturer,
    name = deviceInfo.name,
    resolution = Resolution(deviceInfo.screenX, deviceInfo.screenY),
    displayDensity = deviceInfo.screenDensity,
    abis = emptyList(), // TODO
    isAlreadyPresent = isAlreadyPresent,
    availabilityEstimate = deviceInfo.deviceAvailabilityEstimateSeconds?.seconds ?: Duration.ZERO,
    key = deviceInfo.key,
  )

  override val source
    get() = DirectAccessDeviceSource::class.java

  override val isVirtual: Boolean
    get() = false

  override val isRemote: Boolean
    get() = true

  override fun toBuilder(): Builder = Builder().apply { copyFrom(this@DirectAccessDeviceProfile) }

  class Builder : DeviceProfile.Builder() {
    lateinit var key: String

    fun copyFrom(profile: DirectAccessDeviceProfile) {
      super.copyFrom(profile)
      key = profile.key
    }

    override fun build(): DeviceProfile =
      DirectAccessDeviceProfile(
        apiRange = apiRange,
        manufacturer = manufacturer,
        name = name,
        resolution = resolution,
        displayDensity = displayDensity,
        abis = abis,
        isAlreadyPresent = isAlreadyPresent,
        availabilityEstimate = availabilityEstimate,
        key = key,
      )
  }
}
