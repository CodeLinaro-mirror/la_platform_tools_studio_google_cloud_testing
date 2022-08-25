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

import com.android.tools.adbbridge.DeviceSession
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.io.grpc.netty.NettyChannelBuilder
import com.google.gct.login.GoogleLogin
import com.google.services.firebase.directaccess.client.device.directaccess.DirectAccessClient
import com.google.services.firebase.directaccess.client.device.remote.service.adb.forwardingdaemon.directaccess.DirectAccessServiceClient
import com.google.services.firebase.directaccess.client.session.models.resourcenames.DeviceAssociationName
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.AppExecutorUtil

@Service
class DirectAccessService(val project: Project) {
  private val gcpProject: String
    get() = StudioFlags.DIRECT_ACCESS_PROJECT.get()

  var serviceClient: DirectAccessServiceClient? = null
    get() {
      return field
        ?: GoogleLogin.instance.fetchOAuth2Token()?.let { token ->
          DirectAccessServiceClient(
              gcpProject,
              NettyChannelBuilder.forTarget("dns:///${StudioFlags.DIRECT_ACCESS_ENDPOINT.get()}")
                .build(),
              AppExecutorUtil.getAppExecutorService(),
              token
            )
            .also {
              field = it
              GoogleLogin.instance.activeUser?.googleLoginState?.addLoginListener { loggedIn ->
                if (!loggedIn) field = null
              }
            }
        }
    }
    private set

  // Port to client
  val deviceClients: MutableMap<Int, DirectAccessClient> = mutableMapOf()

  fun acquireAndConnect(device: String, api: String) {
    val directAccessClient =
      serviceClient?.let { DirectAccessClient(gcpProject, device, api, it) }
        ?: run {
          Messages.showWarningDialog("Please log in first", "Log In Required")
          return
        }
    directAccessClient.reserveAndStartStreaming()
    directAccessClient.port?.let { port -> deviceClients.put(port, directAccessClient) }
  }

  fun reconnect(session: DeviceSession) {
    val device = session.androidDeviceList.getAndroidDevices(0)
    val directAccessClient =
      serviceClient?.let {
        DirectAccessClient(gcpProject, device.androidModelId, device.androidVersionId, it)
      }
        ?: run {
          Messages.showWarningDialog("Please log in first", "Log In Required")
          return
        }
    directAccessClient.startStreaming(
      DeviceAssociationName.parseFrom(
        session.connectionInfo.adbConnectInfo.adbDevicesList.single().device
      )
    )
    directAccessClient.port?.let { port -> deviceClients.put(port, directAccessClient) }
  }

  fun listDevices() = serviceClient?.listDeviceSessions()
  fun getDeviceSession(deviceSessionName: String) =
    serviceClient?.getDeviceSession(deviceSessionName)
}
