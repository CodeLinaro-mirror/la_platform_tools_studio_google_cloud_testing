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
import com.google.gct.directaccess.provisioner.CatalogClient
import com.google.gct.directaccess.provisioner.DeviceInfo
import com.google.gct.login2.GoogleLoginService
import com.google.gct.login2.LoginFeature
import com.google.services.firebase.FirebaseLoginFeature
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.net.ssl.CertificateManager
import com.intellij.util.net.ssl.ConfirmingTrustManager
import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.netty.channel.ChannelOption
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider

/**
 * A setup service with methods that are used by other services in direct access module and can be
 * replaced in testing environment.
 */
@Service
class DirectAccessServiceSetup {
  private val defaultChannel = createChannel(StudioFlags.DEVICE_STREAMING_ENDPOINT.get())
  private val backupChannel = createChannel(StudioFlags.DIRECT_ACCESS_ENDPOINT.get())

  private fun createChannel(endpoint: String): ManagedChannel =
    NettyChannelBuilder.forTarget("dns:///$endpoint")
      .sslContext(
        GrpcSslContexts.configure(SslContextBuilder.forClient(), SslProvider.JDK)
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

  fun channel(isDefaultApiEnabled: Boolean): ManagedChannel =
    if (isDefaultApiEnabled) defaultChannel else backupChannel

  fun endPoint(isDefaultApiEnabled: Boolean): String =
    if (isDefaultApiEnabled) StudioFlags.DEVICE_STREAMING_ENDPOINT.get()
    else StudioFlags.DIRECT_ACCESS_ENDPOINT.get()

  fun fetchAccessToken(): String? =
    service<GoogleLoginService>().fetchOAuth2Token(LoginFeature.feature<FirebaseLoginFeature>())

  /**
   * Returns a list of device info that are accessible with the current login state and
   * [cloudProject].
   */
  fun getAccessibleDeviceInfoList(cloudProject: String?): List<DeviceInfo> =
    if (service<DirectAccessDeprecationState>().isServiceEnabledFlow.value) {
      CatalogClient.getAvailableDevices(
        "https://${StudioFlags.DIRECT_ACCESS_ENDPOINT.get()}/",
        cloudProject,
      )
    } else {
      listOf()
    }
}
