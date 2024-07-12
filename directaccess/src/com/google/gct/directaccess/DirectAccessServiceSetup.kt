/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.io.grpc.ManagedChannel
import com.android.tools.idea.io.grpc.netty.GrpcSslContexts
import com.android.tools.idea.io.grpc.netty.NettyChannelBuilder
import com.android.tools.idea.io.netty.channel.ChannelOption
import com.google.gct.directaccess.provisioner.CatalogClient
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.gct.login2.GoogleLoginService
import com.google.gct.login2.LoginFeature
import com.google.services.firebase.FirebaseLoginFeature
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.net.ssl.CertificateManager
import com.intellij.util.net.ssl.ConfirmingTrustManager

/**
 * A setup service with methods that are used by other services in direct access module and can be
 * replaced in testing environment.
 */
@Service
class DirectAccessServiceSetup {
  val channel: ManagedChannel =
    NettyChannelBuilder.forTarget("dns:///${StudioFlags.DIRECT_ACCESS_ENDPOINT.get()}")
      .sslContext(
        GrpcSslContexts.forClient()
          .trustManager(
            ConfirmingTrustManager.createForStorage(
              CertificateManager.DEFAULT_PATH,
              CertificateManager.DEFAULT_PASSWORD,
            )
          )
          .build()
      )
      .withOption(ChannelOption.TCP_NODELAY, true)
      .build()

  fun fetchAccessToken(): String? =
    service<GoogleLoginService>().fetchOAuth2Token(LoginFeature.feature<FirebaseLoginFeature>())

  /**
   * Returns a list of device info that are accessible with the current login state and
   * [cloudProject].
   */
  fun getAccessibleDeviceInfoList(cloudProject: String?): List<DeviceInfo> =
    CatalogClient.getAvailableDevices(
      "https://${StudioFlags.DIRECT_ACCESS_ENDPOINT.get()}/",
      cloudProject,
    )
}
