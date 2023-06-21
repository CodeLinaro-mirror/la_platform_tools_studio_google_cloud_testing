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

import com.android.tools.idea.adblib.AdbLibService
import com.android.tools.idea.concurrency.AndroidCoroutineScope
import com.android.tools.idea.concurrency.createChildScope
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.io.grpc.netty.NettyChannelBuilder
import com.android.tools.idea.io.netty.channel.ChannelOption
import com.google.gct.login.GoogleLogin
import com.google.gct.login.common.LoginListener
import com.google.services.firebase.directaccess.client.DirectAccessConnection
import com.google.services.firebase.directaccess.client.DirectAccessConnectionManager
import com.google.services.firebase.directaccess.client.DirectAccessReservationManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren

@Service
class DirectAccessService(val project: Project) : Disposable {
  val gcpProjectListeners = mutableListOf<() -> Unit>()

  var gcpProject: String?
    get() = PropertiesComponent.getInstance(project).getValue("direct.access.project")
    set(value) {
      PropertiesComponent.getInstance(project).setValue("direct.access.project", value)
      reservationManager = null
      connectionManager = null
      gcpProjectListeners.forEach { it() }
    }

  private val channel =
    NettyChannelBuilder.forTarget("dns:///${StudioFlags.DIRECT_ACCESS_ENDPOINT.get()}")
      .withOption(ChannelOption.TCP_NODELAY, true)
      .build()

  private var loginListener: LoginListener? = null

  /**
   * [CoroutineScope] of this service. Its child scope is used by [DirectAccessReservationManager]
   * The child scope is cancelled when the reservation manager is reset.
   */
  private val scope = AndroidCoroutineScope(this)

  @get:Synchronized
  var reservationManager: DirectAccessReservationManager? = null
    get() {
      return field
        ?: gcpProject?.let { project ->
          DirectAccessReservationManager(project, scope.createChildScope(true), channel) {
              GoogleLogin.instance.activeUser?.googleLoginState?.fetchAccessToken()
            }
            .also {
              field = it
              loginListener = LoginListener { loggedIn ->
                if (!loggedIn) {
                  reservationManager = null
                }
              }
              GoogleLogin.instance.activeUser?.googleLoginState?.addLoginListener(loginListener)
            }
        }
    }
    private set(rm) {
      if (rm != null) {
        throw IllegalArgumentException("Argument to reservationManager setter must be null")
      }
      scope.coroutineContext.cancelChildren()
      field = null
      removeLoginListener()
    }

  private var connectionManager: DirectAccessConnectionManager? = null
    get() {
      return field
        ?: reservationManager?.let { reservationManager ->
          DirectAccessConnectionManager(
              AdbLibService.getSession(project),
              { GoogleLogin.instance.activeUser?.googleLoginState?.fetchAccessToken() },
              channel,
              reservationManager,
            )
            .also { field = it }
        }
    }

  /**
   * Returns a [DirectAccessConnection] connecting to a device with [reservationName] and [scope].
   */
  fun connectToReservation(
    reservationName: String,
    scope: CoroutineScope
  ): DirectAccessConnection? = connectionManager?.connect(reservationName, scope)

  private fun removeLoginListener() {
    loginListener?.let {
      GoogleLogin.instance.activeUser?.googleLoginState?.removeLoginListener(it)
      loginListener = null
    }
  }

  override fun dispose() = removeLoginListener()
}
