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
package com.google.gct.directaccess.ui

import androidx.compose.runtime.Composable
import com.android.tools.idea.adddevicedialog.ComposeWizard
import com.android.tools.idea.adddevicedialog.DefaultDeviceGridPage
import com.android.tools.idea.adddevicedialog.DeviceFilterState
import com.android.tools.idea.adddevicedialog.DeviceLoadingPage
import com.android.tools.idea.adddevicedialog.DeviceProfile
import com.android.tools.idea.adddevicedialog.DeviceTableColumns
import com.android.tools.idea.adddevicedialog.FormFactor
import com.android.tools.idea.adddevicedialog.Manufacturer
import com.android.tools.idea.adddevicedialog.SetFilter
import com.android.tools.idea.adddevicedialog.SetFilterState
import com.android.tools.idea.adddevicedialog.SingleSelectionDropdown
import com.android.tools.idea.adddevicedialog.TextFilterState
import com.android.tools.idea.adddevicedialog.uniqueValuesOf
import com.google.gct.directaccess.provisioner.DirectAccessDeviceProfile
import com.google.gct.directaccess.provisioner.DirectAccessDeviceSource
import com.intellij.openapi.project.Project
import kotlinx.collections.immutable.persistentListOf

internal fun createAddDirectAccessDeviceDialog(
  source: DirectAccessDeviceSource,
  project: Project?,
): ComposeWizard {
  return ComposeWizard(project, "Add Remote Device") {
    val filterState = getOrCreateState { RemoteDeviceFilterState() }
    DeviceLoadingPage(source) { profiles ->
      DefaultDeviceGridPage(
        profiles,
        directAccessColumns,
        filterContent = { RemoteDeviceFilters(profiles, filterState) },
        filterState = filterState,
        onSelectionUpdated = { with(source) { selectionUpdated(it) } },
      )
    }
  }
}

private val directAccessColumns =
  with(DeviceTableColumns) { persistentListOf(icon, oem, name, width, height, density) }

internal class RemoteDeviceFilterState : DeviceFilterState<DirectAccessDeviceProfile>() {
  val manufacturerFilter = SetFilterState(Manufacturer)
  override val textFilter = RemoteDeviceTextFilter()

  override fun apply(row: DirectAccessDeviceProfile): Boolean =
    super.apply(row) && manufacturerFilter.apply(row)
}

internal class RemoteDeviceTextFilter : TextFilterState<DirectAccessDeviceProfile>() {
  override val description = "Search for a device by name, model, or OEM"

  override fun apply(row: DirectAccessDeviceProfile): Boolean =
    super.apply(row) || row.manufacturer.contains(searchText.trim(), ignoreCase = true)
}

@Composable
internal fun RemoteDeviceFilters(
  profiles: List<DeviceProfile>,
  filterState: RemoteDeviceFilterState,
) {
  SingleSelectionDropdown(FormFactor.uniqueValuesOf(profiles), filterState.formFactorFilter)
  SetFilter(Manufacturer.uniqueValuesOf(profiles), filterState.manufacturerFilter)
}
