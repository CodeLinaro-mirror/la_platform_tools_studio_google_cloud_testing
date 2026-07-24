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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.android.tools.adtui.compose.LingeringTooltip
import com.android.tools.adtui.compose.LocalProject
import com.android.tools.adtui.compose.StudioComposePanel
import com.android.tools.adtui.compose.table.RowAttribute
import com.android.tools.adtui.compose.table.SetFilter
import com.android.tools.adtui.compose.table.SetFilterState
import com.android.tools.adtui.compose.table.SingleSelectionRadioButtons
import com.android.tools.adtui.compose.table.TableColumn
import com.android.tools.adtui.compose.table.TableColumnWidth
import com.android.tools.adtui.compose.table.TableTextColumn
import com.android.tools.adtui.compose.table.uniqueValuesOf
import com.android.tools.idea.adddevicedialog.DeviceFilterState
import com.android.tools.idea.adddevicedialog.DeviceTable
import com.android.tools.idea.adddevicedialog.DeviceTableColumns
import com.android.tools.idea.adddevicedialog.FormFactor
import com.android.tools.idea.adddevicedialog.Manufacturer
import com.android.tools.idea.adddevicedialog.TextFilterState
import com.android.tools.idea.concurrency.createCoroutineScope
import com.google.common.annotations.VisibleForTesting
import com.google.gct.directaccess.provisioner.DeviceSelection
import com.google.gct.directaccess.provisioner.DirectAccessDeviceProfile
import com.google.services.firebase.insights.config.capitalize
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.text.Collator
import javax.swing.Action
import javax.swing.JComponent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.jewel.bridge.icon.fromPlatformIcon
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.Chip
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.ExternalLink
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.styling.ChipColors
import org.jetbrains.jewel.ui.component.styling.ChipMetrics
import org.jetbrains.jewel.ui.component.styling.ChipStyle
import org.jetbrains.jewel.ui.icon.IntelliJIconKey
import org.jetbrains.jewel.ui.theme.chipStyle

/** Tags with format "$prefix=$api" e.g. "preview=35" */
private val tagPrefixList = listOf("preview", "deprecated")
private val allowedTags = listOf("private")

class AddDirectAccessDeviceDialog(
  private val project: Project,
  private val deviceSelectionListFlow: MutableStateFlow<List<DeviceSelection>>,
) : DialogWrapper(project) {

  private var rows: List<DirectAccessDeviceProfile> by mutableStateOf(listOf())
  private var profiles: SnapshotStateMap<DirectAccessDeviceProfile, Boolean> = mutableStateMapOf()

  private val selectionColumn =
    TableColumn<DirectAccessDeviceProfile>("", TableColumnWidth.Fixed(24.dp)) { profile, selected ->
      val focusRequester = remember(profile) { FocusRequester() }
      Checkbox(
        profiles[profile] == true,
        onCheckedChange = { profiles[profile] = it },
        modifier = Modifier.focusRequester(focusRequester).size(20.dp),
      )
      LaunchedEffect(selected, profile) {
        if (selected) {
          focusRequester.requestFocus()
        }
      }
    }

  private val modelColumn = TableTextColumn<DirectAccessDeviceProfile>("Model", TableColumnWidth.Weighted(1f), attribute = { it.codename })

  private val labColumn =
    TableTextColumn<DirectAccessDeviceProfile>("Lab", TableColumnWidth.Weighted(1f), attribute = { it.labIdDisplayName })

  /** A table column that displays the device name and any associated tags. The tags are displayed as chips next to the device name. */
  private val nameWithTag =
    TableTextColumnWithTags<DirectAccessDeviceProfile>(
      "Name",
      attribute = { it.name },
      tags = { device ->
        device.tags.mapNotNull { tag ->
          tag.takeIf { allowedTags.contains(it) }?.capitalize()
            ?: tagPrefixList
              .firstOrNull { target ->
                // Match device api with its tag.
                tag.startsWith("$target=") && tag.substringAfter("=").toIntOrNull() == device.apiRange.lowerEndpoint()
              }
              ?.capitalize()
        }
      },
    )

  /** An interaction source that does nothing. Used to disable interactions on the chips. */
  object EmptyInteractionSource : MutableInteractionSource {
    override suspend fun emit(interaction: Interaction) = Unit

    override fun tryEmit(interaction: Interaction): Boolean = true

    override val interactions: Flow<Interaction> = flow {}
  }

  init {
    title = "Select Remote Devices"
    collectDeviceSelection()
    init()
  }

  private fun collectDeviceSelection() {
    val collectAction = {
      rows =
        deviceSelectionListFlow.value.map { deviceSelection ->
          val isEnabled =
            profiles.entries.find { (profile, _) -> profile.isSameDevice(deviceSelection.deviceInfo) }?.value ?: deviceSelection.isSelected
          DirectAccessDeviceProfile(deviceSelection.deviceInfo, isEnabled)
        }
      profiles = rows.map { profile -> profile to profile.isAlreadyPresent }.toMutableStateMap()
    }
    collectAction()
    disposable.createCoroutineScope().launch { deviceSelectionListFlow.collect { collectAction() } }
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
    val component = StudioComposePanel { CompositionLocalProvider(LocalProject provides project) { ComposeContent() } }
    component.preferredSize = JBUI.size(900, 650)
    component.minimumSize = JBUI.size(600, 350)
    return component
  }

  @VisibleForTesting
  @Composable
  fun ComposeContent() {
    Column {
      Content()
      Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
      ButtonBar()
    }
  }

  @Composable
  private fun ButtonBar() {
    Row(modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Spacer(Modifier.weight(1f))
      OutlinedButton(onClick = { close(CANCEL_EXIT_CODE) }) { Text("Cancel") }
      DefaultButton(
        onClick = {
          if (confirm()) {
            close(OK_EXIT_CODE)
          }
        }
      ) {
        Text("Confirm")
      }
    }
  }

  private fun confirm(): Boolean {
    val selectedKeys: Map<String, Boolean> = profiles.entries.associate { (profile, isSelected) -> profile.key to isSelected }
    val unacceptedDevices =
      deviceSelectionListFlow.value.filter {
        selectedKeys[it.deviceInfo.key] == true && it.deviceInfo.accessStatus.contains("EULA_NOT_ACCEPTED")
      }
    if (unacceptedDevices.isNotEmpty()) {
      showEulaDialog(unacceptedDevices.map { it.deviceInfo.labId }.distinct())
      return false
    } else {
      deviceSelectionListFlow.update { devices ->
        devices.map { selection: DeviceSelection -> selection.copy(isSelected = selectedKeys[selection.deviceInfo.key] == true) }
      }
      return true
    }
  }

  private fun showEulaDialog(unapprovedLabs: List<String>) {
    OemEulaDialog(unapprovedLabs, project).show()
  }

  /** A table text column with additional tags in chips. */
  private fun <T> TableTextColumnWithTags(
    name: String,
    width: TableColumnWidth = TableColumnWidth.Weighted(3f),
    attribute: (T) -> String,
    tags: (T) -> List<String>,
    comparator: Comparator<T>? = compareBy(attribute),
    overflow: TextOverflow = TextOverflow.Ellipsis,
    maxLines: Int = 2,
  ) =
    TableColumn(name, width, comparator) { value, _ ->
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(attribute(value), overflow = overflow, maxLines = maxLines)
        tags(value).forEach { tag ->
          Chip(
            Modifier.focusProperties { canFocus = false },
            style = JewelTheme.chipStyle.update(tag),
            interactionSource = remember { EmptyInteractionSource },
          ) {
            Text(text = tag, softWrap = false, overflow = TextOverflow.Ellipsis, maxLines = 1)
          }
        }
      }
    }

  /** Adjusts the padding of the chip to fit the text. This is used to make the chips in the table more compact. */
  private fun ChipStyle.update(tag: String) =
    ChipStyle(
      colors.withBackground(tag),
      ChipMetrics(metrics.cornerSize, PaddingValues(start = 6.dp, top = 2.dp, bottom = 2.dp, end = 6.dp), 0.dp, 0.dp, DpSize.Zero),
    )

  private fun getBackgroundColor(tag: String): Color? {
    val isLightTheme = JBColor.isBright()
    return when (tag) {
      "Preview" -> if (isLightTheme) Color(0xFFDCCBFB) else Color(0xFFA571E6)
      "Deprecated" -> if (isLightTheme) Color(0xFFFEE6B1) else Color(0xFFC27D04)
      "Private" -> if (isLightTheme) Color(0xFFDFE1E5) else Color(0xFF383A42)
      else -> null
    }
  }

  private fun ChipColors.withBackground(tag: String): ChipColors {
    val color = getBackgroundColor(tag) ?: return this
    return ChipColors(
      content = content,
      background = SolidColor(color),
      backgroundDisabled = SolidColor(color),
      backgroundFocused = SolidColor(color),
      backgroundPressed = SolidColor(color),
      backgroundHovered = SolidColor(color),
      backgroundSelected = SolidColor(color),
      backgroundSelectedDisabled = SolidColor(color),
      backgroundSelectedPressed = SolidColor(color),
      backgroundSelectedFocused = SolidColor(color),
      backgroundSelectedHovered = SolidColor(color),
      contentDisabled = contentDisabled,
      contentFocused = contentFocused,
      contentPressed = contentPressed,
      contentHovered = contentHovered,
      contentSelected = contentSelected,
      contentSelectedDisabled = contentSelectedDisabled,
      contentSelectedPressed = contentSelectedPressed,
      contentSelectedFocused = contentSelectedFocused,
      contentSelectedHovered = contentSelectedHovered,
      border = color,
      borderDisabled = color,
      borderFocused = color,
      borderPressed = color,
      borderHovered = color,
      borderSelected = color,
      borderSelectedDisabled = color,
      borderSelectedPressed = color,
      borderSelectedFocused = color,
      borderSelectedHovered = color,
    )
  }

  @Composable
  private fun ColumnScope.Content() {
    Box(Modifier.weight(1f)) {
      DeviceTable(
        rows,
        with(DeviceTableColumns) {
          val columns =
            listOfNotNull(
                selectionColumn,
                icon,
                oem,
                nameWithTag,
                modelColumn,
                labColumn.takeIf { Lab.uniqueValuesOf(rows).size > 1 },
                api,
                width,
                height,
                density,
              )
              .toTypedArray()
          persistentListOf(*columns)
        },
        filterContent = { RemoteDeviceFilters(rows, filterState) },
        filterState = filterState,
      )
    }
  }
}

// Note Collator is by default case-insensitive
private val Lab = RowAttribute<DirectAccessDeviceProfile, String>("Device Lab", Collator.getInstance()) { it.labIdDisplayName }

internal class RemoteDeviceFilterState : DeviceFilterState<DirectAccessDeviceProfile>() {
  val labFilter = SetFilterState(Lab)
  val manufacturerFilter = SetFilterState(Manufacturer)
  override val textFilter = RemoteDeviceTextFilter()

  override fun apply(row: DirectAccessDeviceProfile): Boolean = super.apply(row) && labFilter.apply(row) && manufacturerFilter.apply(row)
}

internal class RemoteDeviceTextFilter : TextFilterState<DirectAccessDeviceProfile>() {
  override val description = "Search for a device by name, model, or OEM"

  override fun apply(row: DirectAccessDeviceProfile): Boolean =
    super.apply(row) ||
      row.manufacturer.contains(searchText.trim(), ignoreCase = true) ||
      row.codename.contains(searchText.trim(), ignoreCase = true)
}

@Composable
internal fun RemoteDeviceFilters(profiles: List<DirectAccessDeviceProfile>, filterState: RemoteDeviceFilterState) {
  SingleSelectionRadioButtons(FormFactor.uniqueValuesOf(profiles), filterState.formFactorFilter)
  SetFilter(Manufacturer.uniqueValuesOf(profiles), filterState.manufacturerFilter)
  SetFilter(Lab.uniqueValuesOf(profiles), filterState.labFilter) { name ->
    Text(name)
    if (profiles.any { Lab.value(it) == name && it.accessStatus.isNotEmpty() }) {
      @OptIn(ExperimentalFoundationApi::class)
      LingeringTooltip({
        Column {
          Text(
            "This Partner OEM device lab is currently not enabled\n" +
              "for your Firebase project. An Owner or Editor of the\n" +
              "project may have to take additional steps before you\n" +
              "can use a device from this lab.",
            Modifier.padding(bottom = 4.dp),
          )
          ExternalLink(
            "Learn more",
            onClick = { BrowserUtil.browse("http://developer.android.com/r/studio-ui/device-streaming/2P/enable") },
          )
        }
      }) {
        Icon(IntelliJIconKey.fromPlatformIcon(AllIcons.General.Warning), "Lab inaccessible", Modifier.padding(horizontal = 4.dp))
      }
    }
  }
}
