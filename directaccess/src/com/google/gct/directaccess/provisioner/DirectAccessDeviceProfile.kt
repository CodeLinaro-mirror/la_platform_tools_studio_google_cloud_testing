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

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.sdklib.deviceprovisioner.Resolution
import com.android.sdklib.devices.Abi
import com.android.tools.idea.adddevicedialog.DeviceProfile
import com.android.tools.idea.adddevicedialog.FormFactors
import com.google.common.collect.Range
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal data class DirectAccessDeviceProfile(
  override val apiRange: Range<Int>,
  override val manufacturer: String,
  override val name: String,
  override val resolution: Resolution,
  override val displayDensity: Int,
  override val abis: List<Abi>,
  override val formFactor: String,
  val isAlreadyPresent: Boolean,
  val availabilityEstimate: Duration,
  val key: String,
  val codename: String,
  val labId: String,
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
    formFactor = deviceInfo.formFactor,
    isAlreadyPresent = isAlreadyPresent,
    availabilityEstimate = deviceInfo.deviceAvailabilityEstimateSeconds?.seconds ?: Duration.ZERO,
    key = deviceInfo.key,
    codename = deviceInfo.codename,
    labId = deviceInfo.labId,
  )

  val labIdDisplayName = OemLabsAssetsRegistry.getInstance().retrieveName(labId)

  override val isVirtual: Boolean
    get() = false

  override val isRemote: Boolean
    get() = true

  @Composable
  override fun Icon(modifier: Modifier) {
    val iconType =
      when (formFactor) {
        FormFactors.TV -> OemLabsAssetsRegistry.IconType.TV
        FormFactors.AUTO -> OemLabsAssetsRegistry.IconType.CAR
        FormFactors.WEAR -> OemLabsAssetsRegistry.IconType.WEAR
        FormFactors.TABLET -> OemLabsAssetsRegistry.IconType.PHONE
        else -> OemLabsAssetsRegistry.IconType.PHONE
      }

    OemLabsAssetsRegistry.getInstance().retrieveIcon(labId, iconType).Icon(modifier)
  }

  override fun toBuilder(): Builder = Builder().apply { copyFrom(this@DirectAccessDeviceProfile) }

  class Builder : DeviceProfile.Builder() {
    lateinit var key: String
    lateinit var codename: String
    lateinit var labId: String

    fun copyFrom(profile: DirectAccessDeviceProfile) {
      super.copyFrom(profile)
      key = profile.key
      codename = profile.codename
      labId = profile.labId
      availabilityEstimate = profile.availabilityEstimate
      isAlreadyPresent = profile.isAlreadyPresent
    }

    override fun build(): DeviceProfile =
      DirectAccessDeviceProfile(
        apiRange = apiRange,
        manufacturer = manufacturer,
        name = name,
        resolution = resolution,
        displayDensity = displayDensity,
        abis = abis,
        formFactor = formFactor,
        isAlreadyPresent = isAlreadyPresent,
        availabilityEstimate = availabilityEstimate,
        key = key,
        codename = codename,
        labId = labId,
      )
  }
}
