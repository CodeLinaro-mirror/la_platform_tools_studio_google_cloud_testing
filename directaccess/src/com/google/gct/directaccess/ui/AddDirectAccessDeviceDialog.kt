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
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateMap
import androidx.compose.ui.unit.dp
import com.android.tools.idea.adddevicedialog.ComposeWizard
import com.android.tools.idea.adddevicedialog.DeviceFilterState
import com.android.tools.idea.adddevicedialog.DeviceProfile
import com.android.tools.idea.adddevicedialog.DeviceTable
import com.android.tools.idea.adddevicedialog.DeviceTableColumns
import com.android.tools.idea.adddevicedialog.FormFactor
import com.android.tools.idea.adddevicedialog.Manufacturer
import com.android.tools.idea.adddevicedialog.SetFilter
import com.android.tools.idea.adddevicedialog.SetFilterState
import com.android.tools.idea.adddevicedialog.SingleSelectionRadioButtons
import com.android.tools.idea.adddevicedialog.TableColumn
import com.android.tools.idea.adddevicedialog.TableColumnWidth
import com.android.tools.idea.adddevicedialog.TextFilterState
import com.android.tools.idea.adddevicedialog.WizardAction
import com.android.tools.idea.adddevicedialog.uniqueValuesOf
import com.google.gct.directaccess.provisioner.DeviceSelection
import com.google.gct.directaccess.provisioner.DirectAccessDeviceProfile
import com.intellij.openapi.project.Project
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.jewel.ui.component.Checkbox

internal fun createAddDirectAccessDeviceDialog(
  project: Project?,
  deviceSelectionListFlow: MutableStateFlow<List<DeviceSelection>>,
): ComposeWizard {
  val profiles: SnapshotStateMap<DirectAccessDeviceProfile, Boolean> =
    deviceSelectionListFlow.value
      .map { deviceSelection ->
        DirectAccessDeviceProfile(deviceSelection.deviceInfo, deviceSelection.isSelected) to
          deviceSelection.isSelected
      }
      .toMutableStateMap()

  val selectionColumn =
    TableColumn<DirectAccessDeviceProfile>("", TableColumnWidth.Fixed(24.dp)) { profile ->
      Checkbox(profiles[profile] == true, onCheckedChange = { profiles[profile] = it })
    }

  return ComposeWizard(project, "Add Remote Device") {
    val filterState = getOrCreateState { RemoteDeviceFilterState() }
    val rows = profiles.keys.toList()

    DeviceTable(
      rows,
      persistentListOf(selectionColumn).plus(directAccessColumns),
      filterContent = { RemoteDeviceFilters(rows, filterState) },
      filterState = filterState,
    )

    nextAction = WizardAction.Disabled
    finishAction = WizardAction {
      deviceSelectionListFlow.update { devices ->
        val selectedKeys: Map<String, Boolean> =
          profiles.entries.associate { (profile, isSelected) -> profile.key to isSelected }

        devices.map { selection: DeviceSelection ->
          selection.copy(isSelected = selectedKeys[selection.deviceInfo.key] == true)
        }
      }
      close()
    }
  }
}

private val directAccessColumns =
  with(DeviceTableColumns) { persistentListOf(icon, oem, name, api, width, height, density) }

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
  SingleSelectionRadioButtons(FormFactor.uniqueValuesOf(profiles), filterState.formFactorFilter)
  SetFilter(Manufacturer.uniqueValuesOf(profiles), filterState.manufacturerFilter)
}
