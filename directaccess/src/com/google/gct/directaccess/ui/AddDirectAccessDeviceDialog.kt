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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.android.tools.adtui.compose.LocalProject
import com.android.tools.adtui.compose.StudioComposePanel
import com.android.tools.idea.adddevicedialog.DeviceFilterState
import com.android.tools.idea.adddevicedialog.DeviceTable
import com.android.tools.idea.adddevicedialog.DeviceTableColumns
import com.android.tools.idea.adddevicedialog.FormFactor
import com.android.tools.idea.adddevicedialog.Manufacturer
import com.android.tools.idea.adddevicedialog.RowAttribute
import com.android.tools.idea.adddevicedialog.SetFilter
import com.android.tools.idea.adddevicedialog.SetFilterState
import com.android.tools.idea.adddevicedialog.SingleSelectionRadioButtons
import com.android.tools.idea.adddevicedialog.TableColumn
import com.android.tools.idea.adddevicedialog.TableColumnWidth
import com.android.tools.idea.adddevicedialog.TableTextColumn
import com.android.tools.idea.adddevicedialog.TextFilterState
import com.android.tools.idea.adddevicedialog.uniqueValuesOf
import com.google.common.annotations.VisibleForTesting
import com.google.gct.directaccess.provisioner.DeviceSelection
import com.google.gct.directaccess.provisioner.DirectAccessDeviceProfile
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import javax.swing.Action
import javax.swing.JComponent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.enableNewSwingCompositing
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

class AddDirectAccessDeviceDialog(
  private val project: Project,
  private val deviceSelectionListFlow: MutableStateFlow<List<DeviceSelection>>,
) : DialogWrapper(project) {
  private val rows =
    deviceSelectionListFlow.value.map { deviceSelection ->
      DirectAccessDeviceProfile(deviceSelection.deviceInfo, deviceSelection.isSelected)
    }
  private val profiles: SnapshotStateMap<DirectAccessDeviceProfile, Boolean> =
    rows.map { profile -> profile to profile.isAlreadyPresent }.toMutableStateMap()

  private val selectionColumn =
    TableColumn<DirectAccessDeviceProfile>("", TableColumnWidth.Fixed(24.dp)) { profile, selected ->
      val focusRequester = remember(profile) { FocusRequester() }
      Checkbox(
        profiles[profile] == true,
        onCheckedChange = { profiles[profile] = it },
        modifier = Modifier.focusRequester(focusRequester),
      )
      LaunchedEffect(selected, profile) {
        if (selected) {
          focusRequester.requestFocus()
        }
      }
    }

  private val modelColumn =
    TableTextColumn<DirectAccessDeviceProfile>(
      "Model",
      TableColumnWidth.Weighted(2f),
      attribute = { it.codename },
      maxLines = 2,
    )

  init {
    title = "Select Remote Devices"
    init()
  }

  private val filterState by mutableStateOf(RemoteDeviceFilterState())

  override fun createActions(): Array<Action> {
    return arrayOf()
  }

  // Don't include the default border; our banners need to span the entire width
  override fun createContentPaneBorder() = null

  // Don't include the bottom panel; we'll make buttons ourselves
  override fun createSouthPanel(): JComponent? = null

  override fun createCenterPanel(): JComponent {
    @OptIn(ExperimentalJewelApi::class) (enableNewSwingCompositing())
    val component = StudioComposePanel {
      CompositionLocalProvider(LocalProject provides project) { ComposeContent() }
    }
    component.preferredSize = JBUI.size(900, 650)
    component.minimumSize = JBUI.size(600, 350)
    return component
  }

  @VisibleForTesting
  @Composable
  fun ComposeContent() {
    Column {
      Content()
      Divider(Orientation.Horizontal)
      ButtonBar()
    }
  }

  @Composable
  private fun ButtonBar() {
    Row(
      modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Spacer(Modifier.weight(1f))
      OutlinedButton(onClick = { close(CANCEL_EXIT_CODE) }) { Text("Cancel") }
      DefaultButton(
        onClick = {
          deviceSelectionListFlow.update { devices ->
            val selectedKeys: Map<String, Boolean> =
              profiles.entries.associate { (profile, isSelected) -> profile.key to isSelected }

            devices.map { selection: DeviceSelection ->
              selection.copy(isSelected = selectedKeys[selection.deviceInfo.key] == true)
            }
          }
          close(OK_EXIT_CODE)
        }
      ) {
        Text("Confirm")
      }
    }
  }

  @Composable
  private fun ColumnScope.Content() {
    Box(Modifier.weight(1f)) {
      DeviceTable(
        rows,
        with(DeviceTableColumns) {
          persistentListOf(
            selectionColumn,
            icon,
            oem,
            name,
            modelColumn,
            api,
            width,
            height,
            density,
          )
        },
        filterContent = { RemoteDeviceFilters(rows, filterState) },
        filterState = filterState,
      )
    }
  }
}

private fun <V : Comparable<V>> DirectAccessDeviceAttribute(
  name: String,
  value: (DirectAccessDeviceProfile) -> V,
) = RowAttribute(name, Comparator.naturalOrder(), value)

private val Lab = DirectAccessDeviceAttribute("Device Lab") { it.labIdDisplayName }

internal class RemoteDeviceFilterState : DeviceFilterState<DirectAccessDeviceProfile>() {
  val labFilter = SetFilterState(Lab)
  val manufacturerFilter = SetFilterState(Manufacturer)
  override val textFilter = RemoteDeviceTextFilter()

  override fun apply(row: DirectAccessDeviceProfile): Boolean =
    super.apply(row) && labFilter.apply(row) && manufacturerFilter.apply(row)
}

internal class RemoteDeviceTextFilter : TextFilterState<DirectAccessDeviceProfile>() {
  override val description = "Search for a device by name, model, or OEM"

  override fun apply(row: DirectAccessDeviceProfile): Boolean =
    super.apply(row) || row.manufacturer.contains(searchText.trim(), ignoreCase = true)
}

@Composable
internal fun RemoteDeviceFilters(
  profiles: List<DirectAccessDeviceProfile>,
  filterState: RemoteDeviceFilterState,
) {
  SingleSelectionRadioButtons(FormFactor.uniqueValuesOf(profiles), filterState.formFactorFilter)
  SetFilter(Manufacturer.uniqueValuesOf(profiles), filterState.manufacturerFilter)
  SetFilter(Lab.uniqueValuesOf(profiles), filterState.labFilter)
}
