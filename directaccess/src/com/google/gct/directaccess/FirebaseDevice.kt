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

import com.android.sdklib.AndroidVersion
import com.android.tools.idea.devicemanager.ConnectionType
import com.android.tools.idea.devicemanager.Device
import com.android.tools.idea.devicemanager.DeviceType
import com.android.tools.idea.devicemanager.Key
import com.android.tools.idea.devicemanager.SerialNumber
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.services.firebase.directaccess.client.DirectAccessConnection.State
import javax.swing.Icon

class FirebaseDevice private constructor(builder: Builder) : Device(builder) {

  private val myState = builder.myState

  constructor(
    device: DeviceInfo,
    connectionState: State,
  ) : this(
    Builder().apply {
      setName("${device.manufacturer} ${device.name}")
      setApi(device.api)
      setType(device.type)
      setConnectionState(connectionState)
      setTarget(
        when (connectionState) {
          State.RESERVING -> "Reserving a device..."
          State.RESERVED -> "Connecting to device..."
          State.CONNECTED, State.STREAMING -> device.codename
          else -> device.codename
        }
      )
    }
  )

  override fun getIcon(): Icon = myType.physicalIcon

  override fun isOnline() = myState == State.CONNECTED

  private class Builder : Device.Builder() {
    override fun build(): FirebaseDevice = FirebaseDevice(this)
    var myState: State = State.CLOSED
      private set

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

    fun setType(type: DeviceType): Builder {
      myType = type
      return this
    }

    fun setConnectionState(connectionState: State): Builder {
      myState = connectionState
      return this
    }
  }
}
