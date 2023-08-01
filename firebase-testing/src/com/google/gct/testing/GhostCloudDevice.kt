/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.google.gct.testing

import com.android.ddmlib.AdbHelper
import com.android.ddmlib.Client
import com.android.ddmlib.FileListingService
import com.android.ddmlib.IDevice
import com.android.ddmlib.IDevice.DeviceUnixSocketNamespace
import com.android.ddmlib.IDevice.HardwareFeature
import com.android.ddmlib.IShellOutputReceiver
import com.android.ddmlib.InstallReceiver
import com.android.ddmlib.RawImage
import com.android.ddmlib.ScreenRecorderOptions
import com.android.ddmlib.SyncService
import com.android.ddmlib.ServiceInfo
import com.android.ddmlib.log.LogReceiver
import com.android.sdklib.AndroidVersion
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

data class GhostCloudDevice(
  val deviceId: String,
  val androidModelId: String,
  val androidVersion: AndroidVersion,
  val locale: String,
  val orientation: String
) : IDevice {

  override fun getSerialNumber(): String = "N/A"

  val encodedConfigurationInstance: String
    get() = "$androidModelId-${androidVersion.apiLevel}-$locale-$orientation"

  override fun getAvdName(): String? = null

  override fun getAvdPath(): String? = null

  override fun getState(): IDevice.DeviceState = IDevice.DeviceState.OFFLINE

  override fun getProperties(): Map<String, String> = emptyMap()

  override fun getPropertyCount(): Int = 0

  override fun getProperty(name: String): String? {
    return when (name) {
      IDevice.PROP_BUILD_API_LEVEL -> androidVersion.apiLevel.toString()
      else -> null
    }
  }

  override fun arePropertiesSet(): Boolean = false

  override fun getPropertySync(name: String): String? = null

  override fun getPropertyCacheOrSync(name: String): String? = null

  override fun supportsFeature(feature: IDevice.Feature): Boolean = true

  override fun supportsFeature(feature: HardwareFeature): Boolean = feature != HardwareFeature.WATCH

  override fun services(): MutableMap<String, ServiceInfo> = mutableMapOf()

  override fun getMountPoint(name: String): String? = null

  override fun isOnline(): Boolean = false

  override fun isEmulator(): Boolean = false

  override fun isOffline(): Boolean = true

  override fun isBootLoader(): Boolean = false

  override fun hasClients(): Boolean = false

  override fun getClients(): Array<Client> = arrayOf()

  override fun getClient(applicationName: String): Client? = null

  override fun getSyncService(): SyncService? = null

  override fun getFileListingService(): FileListingService? = null

  override fun getScreenshot(): RawImage? = null

  override fun getScreenshot(timeout: Long, unit: TimeUnit): RawImage? = null

  override fun startScreenRecorder(
    remoteFilePath: String,
    options: ScreenRecorderOptions,
    receiver: IShellOutputReceiver
  ) = Unit

  override fun executeShellCommand(command: String, receiver: IShellOutputReceiver, maxTimeToOutputResponse: Int) = Unit

  override fun executeShellCommand(command: String, receiver: IShellOutputReceiver) = Unit

  override fun runEventLogService(receiver: LogReceiver) = Unit

  override fun runLogService(logname: String, receiver: LogReceiver) = Unit

  override fun createForward(localPort: Int, remotePort: Int) = Unit

  override fun createForward(localPort: Int, remoteSocketName: String, namespace: DeviceUnixSocketNamespace) = Unit

  override fun removeForward(localPort: Int) = Unit

  override fun createReverse(remotePort: Int, localPort: Int) = Unit

  override fun removeReverse(remotePort: Int) = Unit

  override fun getClientName(pid: Int): String? = null

  override fun push(local: Array<String>, remote: String) = Unit
  override fun pushFile(local: String, remote: String) = Unit

  override fun pullFile(remote: String, local: String) = Unit

  override fun installPackage(packageFilePath: String, reinstall: Boolean, vararg extraArgs: String) = Unit

  override fun installPackage(packageFilePath: String, reinstall: Boolean, receiver: InstallReceiver, vararg extraArgs: String) = Unit

  override fun installPackage(
    packageFilePath: String, reinstall: Boolean, receiver: InstallReceiver, maxTimeout: Long,
    maxTimeToOutputResponse: Long, maxTimeUnits: TimeUnit, vararg extraArgs: String
  ) = Unit

  override fun installPackages(
    apks: List<File>,
    reinstall: Boolean,
    installOptions: List<String>,
    timeout: Long,
    timeoutUnit: TimeUnit
  ) = Unit

  override fun syncPackageToDevice(localFilePath: String): String? = null

  override fun installRemotePackage(remoteFilePath: String, reinstall: Boolean, vararg extraArgs: String) = Unit

  override fun installRemotePackage(remoteFilePath: String, reinstall: Boolean, receiver: InstallReceiver, vararg extraArgs: String) = Unit

  override fun installRemotePackage(
    remoteFilePath: String, reinstall: Boolean, receiver: InstallReceiver, maxTimeout: Long,
    maxTimeToOutputResponse: Long, maxTimeUnits: TimeUnit, vararg extraArgs: String
  ) = Unit

  override fun removeRemotePackage(remoteFilePath: String) = Unit

  override fun uninstallPackage(packageName: String): String? = null

  override fun uninstallApp(applicationID: String, vararg extraArgs: String): String? = null

  override fun reboot(into: String) = Unit

  override fun root(): Boolean = false

  override fun isRoot(): Boolean = false

  override fun getBatteryLevel(): Int? = null

  override fun getBatteryLevel(freshnessMs: Long): Int? = null

  override fun getBattery(): Future<Int?> = Futures.immediateFuture(null)

  override fun getBattery(freshnessTime: Long, timeUnit: TimeUnit): Future<Int?> = Futures.immediateFuture(null)

  override fun getAbis(): List<String> = emptyList()

  override fun getDensity(): Int = 0

  override fun getLanguage(): String? = null

  override fun getRegion(): String? = null

  override fun getVersion(): AndroidVersion = androidVersion

  override fun executeRemoteCommand(
    adbSockAddr: InetSocketAddress,
    command: String,
    device: IDevice,
    rcvr: IShellOutputReceiver,
    maxTimeout: Long,
    maxTimeToOutputResponse: Long,
    maxTimeUnits: TimeUnit
  ) = Unit

  override fun executeRemoteCommand(
    adbSockAddr: InetSocketAddress,
    command: String,
    device: IDevice,
    rcvr: IShellOutputReceiver,
    maxTimeToOutputResponse: Long,
    maxTimeUnits: TimeUnit
  ) = Unit

  override fun executeRemoteCommand(
    adbSockAddr: InetSocketAddress,
    adbService: AdbHelper.AdbService,
    command: String,
    device: IDevice,
    rcvr: IShellOutputReceiver,
    maxTimeToOutputResponse: Long,
    maxTimeUnits: TimeUnit,
    `is`: InputStream?
  ) = Unit

  override fun executeRemoteCommand(
    adbSockAddr: InetSocketAddress,
    adbService: AdbHelper.AdbService,
    command: String,
    device: IDevice,
    rcvr: IShellOutputReceiver,
    maxTimeout: Long,
    maxTimeToOutputResponse: Long,
    maxTimeUnits: TimeUnit,
    `is`: InputStream?
  ) = Unit

  override fun getName(): String = "Firebase device: $deviceId"

  override fun executeShellCommand(command: String, receiver: IShellOutputReceiver, maxTimeToOutputResponse: Long, maxTimeUnits: TimeUnit) =
    Unit
  override fun executeShellCommand(
    command: String,
    receiver: IShellOutputReceiver,
    maxTimeout: Long,
    maxTimeToOutputResponse: Long,
    maxTimeUnits: TimeUnit
  ) = Unit

  override fun getSystemProperty(name: String): ListenableFuture<String?> = Futures.immediateFuture(null)
}