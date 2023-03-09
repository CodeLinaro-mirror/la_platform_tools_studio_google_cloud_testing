package com.google.gct.directaccess.ui

import com.android.adblib.serialNumber
import com.android.tools.idea.device.explorer.DeviceExplorerService
import com.android.tools.idea.devicemanager.ActivateDeviceFileExplorerWindowValue
import com.android.tools.idea.devicemanager.IconButtonTableCellEditor
import com.android.tools.idea.file.explorer.toolwindow.DeviceExplorer
import com.android.tools.idea.flags.StudioFlags
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import java.awt.Component
import javax.swing.JTable

/**
 * This is a temporary code duplication of [ActivateDeviceFileExplorerWindowButtonTableCellEditor]
 * [com.android.tools.idea.devicemanager.ActivateDeviceFileExplorerWindowButtonTableCellEditor]
 * ActivateDeviceFileExplorerWindowButtonTableCellEditor requires a table that implements
 * [DeviceTable] [com.android.tools.idea.devicemanager.DeviceTable]. Since FirebaseTable does not
 * implement DeviceTable we cannot use the table cell editor from device manager. Since device
 * manager is transitioning to a unified UI, we will not need this once transition is complete
 */
class ActivateDeviceFileExplorerWindowButtonTableCellEditor(project: Project) :
  IconButtonTableCellEditor(
    ActivateDeviceFileExplorerWindowValue.INSTANCE,
    AllIcons.Actions.MenuOpen,
    if (StudioFlags.MERGED_DEVICE_FILE_EXPLORER_AND_DEVICE_MONITOR_TOOL_WINDOW_ENABLED.get()) {
      "Open this device in the Device Explorer."
    } else {
      "Open this device in the Device File Explorer."
    }
  ) {

  private var deviceItem: FirebaseDeviceItem? = null

  init {
    myButton.addActionListener {
      // TODO(b/272116216)
      // We should track DeviceExplorer open event here but the DeviceManagerEvent.EventKind
      // does not have an event for a cloud device.
      invokeLater {
        deviceItem?.handle?.state?.connectedDevice?.serialNumber?.let { serialNumber ->
          if (
            StudioFlags.MERGED_DEVICE_FILE_EXPLORER_AND_DEVICE_MONITOR_TOOL_WINDOW_ENABLED.get()
          ) {
            DeviceExplorerService.openAndShowDevice(project, serialNumber)
          } else {
            DeviceExplorer.openAndShowDevice(project, serialNumber)
          }
        }
      }
    }
  }

  override fun getTableCellEditorComponent(
    table: JTable,
    value: Any,
    selected: Boolean,
    viewRowIndex: Int,
    viewColumnIndex: Int
  ): Component {
    // Button highlighting does not work correctly without this call
    super.getTableCellEditorComponent(table, value, selected, viewRowIndex, viewColumnIndex)

    val item = (table as FirebaseDeviceTable).getItemAt(viewRowIndex)
    deviceItem = item as? FirebaseDeviceItem
    return myButton
  }
}
