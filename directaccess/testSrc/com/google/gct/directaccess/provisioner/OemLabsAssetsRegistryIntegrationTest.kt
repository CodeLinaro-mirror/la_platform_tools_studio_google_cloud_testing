/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.google.gct.directaccess.provisioner

import com.android.testutils.file.createInMemoryFileSystemAndFolder
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.ApplicationRule
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class OemLabsAssetsRegistryIntegrationTest {
  private val LOCALHOST = "127.0.0.1"
  private lateinit var server: HttpServer
  private lateinit var url: String
  private lateinit var cacheDir: Path
  private lateinit var registry: OemLabsAssetsRegistry

  @get:Rule val rule = ApplicationRule()

  @Before
  fun setup() {
    // Create cache directory.
    cacheDir = createInMemoryFileSystemAndFolder("tempCacheDir")

    // Set up server.
    server = HttpServer.create()
    with(server) {
      bind(InetSocketAddress(LOCALHOST, 0), 0)
      start()
      url = "http://$LOCALHOST:${address.port}/"
    }

    registry = OemLabsAssetsRegistry(baseUrl = url, cacheDir = cacheDir)
  }

  @After
  fun tearDown() {
    server.stop(0)
  }

  @Test
  fun `fetch assets from server`() {
    createContext(
      path = "/some_lab/descriptor.json",
      content =
        """
          {
            "name": "SomeLab",
            "icons": [
              {
                "formFactor": "tv",
                "pathLight": "tv.svg",
                "pathDark": "tv_dark.svg"
              }
            ]
          }
      """
          .trimIndent(),
      rCode = HttpURLConnection.HTTP_OK,
    )

    createContext(
      path = "/some_lab/tv.svg",
      content = "<svg>tv</svg>",
      rCode = HttpURLConnection.HTTP_OK,
    )

    createContext(
      path = "/some_lab/tv_dark.svg",
      content = "<svg>tv_dark</svg>",
      rCode = HttpURLConnection.HTTP_OK,
    )

    // Check fetched assets
    val assets: OemLabsAssetsRegistry.OemLabAsset = registry.getAssetById("some_lab")!!
    with(assets) {
      assertThat(name).isEqualTo("SomeLab")
      assertThat(icons.keys).containsExactlyElementsIn(listOf(OemLabsAssetsRegistry.IconType.TV))
    }

    // Check cached assets
    val cached = cacheDir.resolve("some_lab")
    assertThat(cached.exists()).isTrue()

    cached
      .resolve("descriptor.json")
      .checkContents(
        """
          {
            "name": "SomeLab",
            "icons": [
              {
                "formFactor": "tv",
                "pathLight": "tv.svg",
                "pathDark": "tv_dark.svg"
              }
            ]
          }
    """
          .trimIndent()
      )

    cached
      .resolve("tv.svg")
      .checkContents(
        """
      <svg>tv</svg>
    """
          .trimIndent()
      )

    cached
      .resolve("tv_dark.svg")
      .checkContents(
        """
      <svg>tv_dark</svg>
    """
          .trimIndent()
      )
  }

  @Test
  fun `test lenient parsing`() {
    createContext(
      path = "/some_lab/descriptor.json",
      content =
        """
          {
            "name": "SomeLab",
            "foo": "bar",
            "icons": [
              {
                "formFactor": "phone",
                "pathLight": "phone.svg",
                "pathDark": "phone_dark.svg",
                "foo": "bar"
              }
            ]
          }
      """
          .trimIndent(),
      rCode = HttpURLConnection.HTTP_OK,
    )

    createContext(
      path = "/some_lab/phone.svg",
      content = "<svg>phone</svg>",
      rCode = HttpURLConnection.HTTP_OK,
    )

    createContext(
      path = "/some_lab/phone_dark.svg",
      content = "<svg>phone_dark</svg>",
      rCode = HttpURLConnection.HTTP_OK,
    )

    val assets = registry.getAssetById("some_lab")!!
    with(assets) {
      assertThat(name).isEqualTo("SomeLab")
      assertThat(icons.keys).containsExactlyElementsIn(listOf(OemLabsAssetsRegistry.IconType.PHONE))
    }

    val cached = cacheDir.resolve("some_lab")
    assertThat(cached.exists()).isTrue()
    assertThat(cached.resolve("descriptor.json").exists()).isTrue()
    assertThat(cached.resolve("phone.svg").exists()).isTrue()
    assertThat(cached.resolve("phone_dark.svg").exists()).isTrue()
  }

  private fun Path.checkContents(content: String) {
    assertThat(exists()).isTrue()
    assertThat(readText()).isEqualTo(content)
  }

  private fun createContext(path: String, content: String, rCode: Int, rLen: Long = 0) {
    synchronized(server) {
      server.createContext(path) { exchange ->
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(rCode, rLen)
        exchange.responseBody.write(content.toByteArray(UTF_8))
        exchange.close()
      }
    }
  }
}
