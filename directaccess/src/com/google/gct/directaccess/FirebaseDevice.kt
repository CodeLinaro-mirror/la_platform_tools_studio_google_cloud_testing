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
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.sdklib.deviceprovisioner.DeviceState.Connected
import com.android.tools.idea.devicemanager.ConnectionType
import com.android.tools.idea.devicemanager.Device
import com.android.tools.idea.devicemanager.DeviceType
import com.android.tools.idea.devicemanager.Key
import com.android.tools.idea.devicemanager.SerialNumber
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.services.firebase.directaccess.client.DirectAccessConnection.ConnectionState
import com.google.services.firebase.directaccess.client.DirectAccessConnection.RemoteState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.swing.Icon

/** Model class for rendering firebase device in device manager table cell. */
class FirebaseDevice private constructor(builder: Builder) : Device(builder) {

  val state: DeviceState = builder.myState

  constructor(
    device: DeviceInfo,
    remoteState: RemoteState,
    deviceState: DeviceState
  ) : this(
    Builder().apply {
      setName("${device.manufacturer} ${device.name}")
      setApi(device.api)
      setType(device.type)
      setState(deviceState)
      setTarget(
        when (remoteState.connection) {
          ConnectionState.DISCONNECTED ->
            reservationExpiringMessage(
              TimeUnit.SECONDS.toMillis(remoteState.reservation.expireTime.seconds)
            )
          ConnectionState.CONNECTING,
          ConnectionState.CONNECTED -> {
            if (deviceState is Connected) {
              reservationExpiringMessage(
                TimeUnit.SECONDS.toMillis(remoteState.reservation.expireTime.seconds)
              )
            } else if (
              remoteState.reservation.connectionInfo.adbConnectInfo.adbDevicesList.isNotEmpty()
            ) {
              "Connecting to device..."
            } else "Reserving a device..."
          }
          else -> device.codename
        }
      )
    }
  )

  override fun getIcon(): Icon = myType.physicalIcon

  override fun isOnline() = state is Connected

  private class Builder : Device.Builder() {
    override fun build(): FirebaseDevice = FirebaseDevice(this)
    lateinit var myState: DeviceState
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

    fun setState(state: DeviceState): Builder {
      myState = state
      return this
    }
  }
}

private fun reservationExpiringMessage(expireTimeMillis: Long): String {
  val formattedDate =
    SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(expireTimeMillis))
  return "Device will expire at $formattedDate"
}
