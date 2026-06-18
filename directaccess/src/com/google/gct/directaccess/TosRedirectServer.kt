/*
 * Copyright (C) 2026 The Android Open Source Project
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

import com.google.common.io.ByteStreams
import com.google.gct.login2.GoogleLoginService
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.io.OutputStream
import java.net.URLEncoder
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import kotlinx.coroutines.CompletableDeferred
import org.mortbay.jetty.Request
import org.mortbay.jetty.Server
import org.mortbay.jetty.handler.AbstractHandler

private const val CALLBACK_PATH = "/CALLBACK_Device_Streaming"
private val log = Logger.getInstance(TosRedirectServer::class.java)

/**
 * A simplified redirect server to open a browser page for accepting Firebase ToS, await callback, and show a success page upon completion.
 */
class TosRedirectServer(var port: Int = -1) {
  private var server: Server? = null

  private val completion = CompletableDeferred<Pair<Boolean, String?>>()
  var error: String? = null
    private set

  private var success: Boolean = false

  suspend fun run() {
    val requestedPort = if (port == -1) 0 else port
    server =
      Server(requestedPort).also {
        for (c in it.connectors) {
          c.host = "localhost"
        }
        it.addHandler(CallbackHandler())
        it.start()

        if (port == -1) {
          port = it.connectors[0].localPort
        }

        val tosUrl = "https://console.firebase.google.com/?forceCheckTos=1&dlAction=AndroidStudioSignIn&localPort=$port$CALLBACK_PATH"
        val url =
          GoogleLoginService.instance.getEmail()?.let { email ->
            val encodedEmail = URLEncoder.encode(email, "UTF-8")
            val encodedTosUrl = URLEncoder.encode(tosUrl, "UTF-8")
            "https://accounts.google.com/AccountChooser?Email=$encodedEmail&continue=$encodedTosUrl"
          } ?: tosUrl
        BrowserUtil.browse(url)
        log.info("Opened url is $url")
      }

    try {
      log.info("Listening on port $port: wait until handler is processed or error occurs.")
      val result = completion.await()
      success = result.first
      error = result.second
      if (error != null) {
        throw IOException("Terms of service not accepted ($error)")
      }
    } finally {
      server?.stop()
      server = null
    }
  }

  private fun signalCallbackHandled(isSuccess: Boolean, errorMessage: String?) {
    if (completion.complete(Pair(isSuccess, errorMessage))) {
      success = isSuccess
      error = errorMessage
    }
  }

  inner class CallbackHandler : AbstractHandler() {
    override fun handle(target: String, request: HttpServletRequest, response: HttpServletResponse, dispatch: Int) {
      try {
        if (target != CALLBACK_PATH) {
          return // Ignore other requests
        }

        (request as? Request)?.isHandled = true
        val errorMessage = request.getParameter("error")
        if (errorMessage == null) {
          response.status = HttpServletResponse.SC_OK
          respondWithResource(response)
          response.flushBuffer()
          signalCallbackHandled(true, null)
        } else {
          signalCallbackHandled(false, errorMessage)
        }
      } catch (e: Exception) {
        signalCallbackHandled(false, e.localizedMessage)
      }
    }
  }

  private fun respondWithResource(response: HttpServletResponse) {
    response.contentType = "text/html"
    val inputStream = TosRedirectServer::class.java.getResourceAsStream("/tos_accepted.html")
    val outputStream: OutputStream = response.outputStream
    inputStream?.use { ByteStreams.copy(it, outputStream) }
    outputStream.flush()
  }
}
