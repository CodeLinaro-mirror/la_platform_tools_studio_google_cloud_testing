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

import com.android.tools.adbbridge.Reservation
import com.android.tools.idea.adblib.AdbLibService
import com.android.tools.idea.concurrency.AndroidCoroutineScope
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.io.grpc.netty.NettyChannelBuilder
import com.android.tools.idea.io.netty.channel.ChannelOption
import com.google.gct.login.GoogleLogin
import com.google.services.firebase.directaccess.client.DirectAccessConnection
import com.google.services.firebase.directaccess.client.DirectAccessConnectionManager
import com.google.services.firebase.directaccess.client.DirectAccessReservationManager
import com.google.services.firebase.directaccess.client.isClosed
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Service
class DirectAccessService(val project: Project) : Disposable {
  private val gcpProject: String
    get() = StudioFlags.DIRECT_ACCESS_PROJECT.get()

  private val channel =
    NettyChannelBuilder.forTarget("dns:///${StudioFlags.DIRECT_ACCESS_ENDPOINT.get()}")
      .withOption(ChannelOption.TCP_NODELAY, true)
      .build()

  var reservationManager: DirectAccessReservationManager? = null
    get() {
      return field
        ?: DirectAccessReservationManager(gcpProject, AndroidCoroutineScope(this), channel) {
          GoogleLogin.instance.activeUser?.googleLoginState?.fetchAccessToken()
        }
          .also {
            field = it
            GoogleLogin.instance.activeUser?.googleLoginState?.addLoginListener { loggedIn ->
              if (!loggedIn) field = null
            }
          }
    }
    private set

  var connectionManager: DirectAccessConnectionManager? = null
    get() {
      return field
        ?: reservationManager?.let { reservationManager ->
          DirectAccessConnectionManager(
              AdbLibService.getSession(project),
              AndroidCoroutineScope(this),
              { GoogleLogin.instance.activeUser?.googleLoginState?.fetchAccessToken() },
              channel,
              reservationManager,
            )
            .also { field = it }
        }
    }

  /**
   * Returns a [DirectAccessConnection] connecting to the remote device with [codename] and [api].
   *
   * The remote device is managed by a newly created [Reservation] from [reservationManager].
   * However, if a [Reservation] with the same device information is created from another studio
   * instance before calling this method, the existing [Reservation] will be reused by
   * [reservationManager] and assigned to the returned [DirectAccessConnection].
   */
  suspend fun reserveConnection(codename: String, api: String): DirectAccessConnection? =
    withContext(Dispatchers.IO) {
      val reservation =
        reservationManager?.listReservations()?.firstOrNull { reservation ->
          !reservation.sessionState.isClosed() &&
            reservation.androidDeviceList.androidDevicesList.any {
              it.androidModelId == codename && it.androidVersionId == api
            }
        }
          ?: reservationManager?.createReservation(codename, api) ?: return@withContext null

      connectionManager?.connect(reservation)
        ?: run {
          invokeLater { Messages.showWarningDialog("Please log in first", "Log In Required") }
          return@withContext null
        }
    }

  override fun dispose() {}
}
