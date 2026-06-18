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

import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.google.common.truth.Truth.assertThat
import com.google.gct.login2.LoginUsersRule
import com.intellij.ide.BrowserUtil
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mockStatic

class TosRedirectServerTest {

  private val projectRule = ProjectRule()
  private val loginUsersRule = LoginUsersRule()
  @get:Rule val ruleChain = RuleChain(projectRule, loginUsersRule)

  private fun triggerCallback(port: Int, query: String = "") {
    val url = URI.create("http://localhost:$port/CALLBACK_Device_Streaming$query").toURL()
    var connection: HttpURLConnection? = null
    for (retry in 1..50) {
      try {
        connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 1000
        connection.readTimeout = 1000
        connection.connect()
        break
      } catch (e: java.net.ConnectException) {
        if (retry == 50) throw e
        Thread.sleep(50)
      }
    }
    try {
      connection!!.inputStream.use { it.readBytes() }
    } catch (_: Exception) {
      // Ignore exceptions (like 400/500 if the server closed early)
    } finally {
      connection?.disconnect()
    }
  }

  @Test
  fun testCallbackSuccess() = runBlockingWithTimeout {
    loginUsersRule.setActiveUser("test@google.com")
    val server = TosRedirectServer(-1)
    var completed = false
    var hasException: Throwable? = null

    val job =
      launch(Dispatchers.IO) {
        mockStatic(BrowserUtil::class.java).use { browserUtil ->
          browserUtil.`when`<Any> { BrowserUtil.browse(anyString()) }.thenAnswer {}
          try {
            server.run()
            completed = true
          } catch (t: Throwable) {
            hasException = t
          }
        }
      }

    // Wait until port is assigned and server is started
    yieldUntil { server.port != -1 }

    // Make the HTTP request
    triggerCallback(server.port)

    job.join()

    assertThat(completed).isTrue()
    assertThat(hasException).isNull()
  }

  @Test
  fun testCallbackError() = runBlockingWithTimeout {
    loginUsersRule.setActiveUser("test@google.com")
    val server = TosRedirectServer(-1)
    var completed = false
    var hasException: Throwable? = null

    val job =
      launch(Dispatchers.IO) {
        mockStatic(BrowserUtil::class.java).use { browserUtil ->
          browserUtil.`when`<Any> { BrowserUtil.browse(anyString()) }.thenAnswer {}
          try {
            server.run()
            completed = true
          } catch (t: Throwable) {
            hasException = t
          }
        }
      }

    yieldUntil { server.port != -1 }

    // Trigger callback with custom error parameter
    triggerCallback(server.port, "?error=test_error")

    job.join()

    assertThat(completed).isFalse()
    assertThat(hasException).isInstanceOf(IOException::class.java)
    assertThat(hasException?.message).contains("Terms of service not accepted (test_error)")
  }
}
