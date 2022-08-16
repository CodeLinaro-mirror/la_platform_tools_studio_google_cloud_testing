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
package com.google.gct.directaccess

import com.android.adblib.serialNumber
import com.android.sdklib.AndroidVersion
import com.android.tools.idea.devicemanager.ConnectionType
import com.android.tools.idea.devicemanager.Device
import com.android.tools.idea.devicemanager.Key
import com.android.tools.idea.devicemanager.SerialNumber
import com.google.gct.directaccess.provisioner.DirectAccessDeviceHandle
import javax.swing.Icon

class FirebaseDevice private constructor(builder: Builder) : Device(builder) {

  constructor(
    device: DirectAccessDeviceHandle
  ) : this(
    device.stateFlow.value.let { deviceState ->
      val properties = deviceState.properties
      Builder()
        .setName(properties.title())
        .setApi(properties.androidVersion?.apiLevel ?: 0)
        .setTarget(deviceState.connectedDevice?.serialNumber ?: "unknown")
    }
  )

  override fun getIcon(): Icon = myType.physicalIcon

  override fun isOnline() = true

  private class Builder : Device.Builder() {
    override fun build(): FirebaseDevice = FirebaseDevice(this)

    init {
      myKey =
        object : Key() {
          override fun getConnectionType(): ConnectionType = ConnectionType.UNKNOWN

          override fun getSerialNumber(): SerialNumber = SerialNumber("none")

          override fun isPersistent() = true
        }
    }

    fun setName(name: String): Builder {
      myName = name
      return this
    }

    fun setTarget(name: String): Builder {
      myTarget = name
      return this
    }

    fun setApi(api: Int): Builder {
      myAndroidVersion = AndroidVersion(api)
      return this
    }
  }
}
