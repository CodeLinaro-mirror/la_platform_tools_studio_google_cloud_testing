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

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.android.annotations.concurrency.Slow
import com.android.ide.common.repository.IdeNetworkCacheUtils
import com.android.ide.common.repository.NetworkCache
import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.icons.CachedImageIcon
import com.intellij.ui.icons.IconTransform
import com.intellij.ui.icons.ImageDataLoader
import com.intellij.ui.icons.LoadIconParameters
import com.intellij.ui.icons.convertImage
import com.intellij.ui.scale.DerivedScaleType
import com.intellij.ui.scale.ScaleContext
import com.intellij.ui.svg.renderSvg
import java.awt.Image
import java.io.InputStream
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToSvgPainter
import org.jetbrains.jewel.foundation.theme.JewelTheme

private const val ASSETS_BASE_URL = "https://www.gstatic.com/android-devtools-oem-labs/labs/"
private const val OEM_LABS_ASSETS_CACHE_DIR_KEY = "oem.labs.assets"
private const val MAX_NETWORK_TIMEOUT_MS = 3000

internal const val ASSETS_OFFLINE_DIR = "/oem-labs-assets-offline"

private fun getCacheDir(): Path? {
  if (ApplicationManager.getApplication().isUnitTestMode) {
    return null
  }
  return Paths.get(PathManager.getSystemPath()).normalize().resolve(OEM_LABS_ASSETS_CACHE_DIR_KEY)
}

/**
 * Provides OEM-specific lab resources (e.g. display name, icon)
 *
 * Assets are fully populated when fetched -- there's no lazy loading here as the use case is simple
 * here.
 *
 * How assets are loaded: Server first: we try to download it from a server (or it could be that the
 * previously downloaded cache is available). Fallback: we try to find the built-in assets, If all
 * else fails, the asset is considered and shown as "unknown".
 */
@Service
class OemLabsAssetsRegistry(
  baseUrl: String = ASSETS_BASE_URL,
  cacheDir: Path? = getCacheDir(),
  networkTimeoutMs: Int = MAX_NETWORK_TIMEOUT_MS,
  cacheExpiryHours: Int = TimeUnit.DAYS.toHours(1).toInt(),
  useNetwork: Boolean = true,
) :
  NetworkCache(
    baseUrl,
    OEM_LABS_ASSETS_CACHE_DIR_KEY,
    cacheDir,
    networkTimeoutMs,
    cacheExpiryHours,
    useNetwork,
  ) {

  private val assetsMap: MutableMap<String, OemLabAsset> = mutableMapOf()

  init {
    // We load [assetsMap] with built-in data first.
    refreshAssetsMap("google") { relativeUrl -> readDefaultData(relativeUrl) }
  }

  override fun readUrlData(url: String, timeout: Int, lastModified: Long): ReadUrlDataResult {
    return IdeNetworkCacheUtils.readHttpUrlData(url, timeout, lastModified)
  }

  override fun readDefaultData(relative: String): InputStream {
    return OemLabsAssetsRegistry::class.java.getResourceAsStream("$ASSETS_OFFLINE_DIR/$relative")
      ?: readFallbackUnknownData(relative)
  }

  private fun readFallbackUnknownData(relative: String): InputStream {
    // We fall back to the unknown assets.
    val relativePath = "unknown/${relative.substringAfterLast("/")}"

    thisLogger().warn("Unknown assets ($relative), fallback to default data at $relativePath.")

    return OemLabsAssetsRegistry::class
      .java
      .getResourceAsStream("$ASSETS_OFFLINE_DIR/$relativePath")
      ?: error("Resource not found: $ASSETS_OFFLINE_DIR/$relativePath")
  }

  override fun error(throwable: Throwable, message: String?) {
    thisLogger().warn(message, throwable)
  }

  @Slow
  fun getAssetById(assetId: String): OemLabAsset? {
    if (assetsMap[assetId] == null)
      refreshAssetsMap(assetId) { relativeUrl ->
        checkNotNull(findData(relativeUrl)) // The fallback data is guaranteed.
      }
    return assetsMap[assetId]
  }

  @Slow
  fun retrieveName(assetId: String): String {
    return getAssetById(assetId)?.name ?: "Unknown"
  }

  @Slow
  fun retrieveIcon(assetId: String, type: IconType): OemLabIcon {
    return getAssetById(assetId)?.icons?.get(type) ?: createUnknownIcon(type)
  }

  private fun createUnknownIcon(iconType: IconType) =
    OemLabIcon(
      description = "unknown_${iconType.type}",
      lightThemeData = readFallbackUnknownData("${iconType.type}.svg").use { it.readBytes() },
      darkThemeData = readFallbackUnknownData("${iconType.type}_dark.svg").use { it.readBytes() },
    )

  /**
   * Under the hood, it fetches assets from a server (or potentially a local cache). If the asset
   * remains unavailable or if an error occurs during retrieval, the function falls back to built-in
   * or unknown assets.
   *
   * If anything unexpected during extraction, we just give up.
   */
  @Slow
  private fun refreshAssetsMap(assetId: String, fetcher: (relative: String) -> InputStream) {
    val asset =
      try {
        OemLabAsset.extract(assetId, fetcher)
      } catch (exception: Exception) {
        error(exception, exception.message ?: "Unknown error")
        return
      }

    assetsMap[assetId] = asset
  }

  class OemLabIcon(
    val description: String,
    val lightThemeData: ByteArray,
    val darkThemeData: ByteArray,
  ) :
    CachedImageIcon(
      loader =
        object : ImageDataLoader {
          override val path: String? = null
          override val url: URL? = null

          override fun isMyClassLoader(classLoader: ClassLoader) = false

          override fun patch(transform: IconTransform): ImageDataLoader? = null

          override fun loadImage(
            parameters: LoadIconParameters,
            scaleContext: ScaleContext,
          ): Image {
            val data = if (parameters.isDark) darkThemeData else lightThemeData
            val scale = scaleContext.getScale(DerivedScaleType.PIX_SCALE).toFloat()
            return renderSvgToImage(data, scale, parameters, scaleContext)
          }
        }
    ) {

    fun getIcon(): Icon = this

    @OptIn(ExperimentalResourceApi::class)
    @Composable
    fun Icon(modifier: Modifier, isDark: Boolean = JewelTheme.isDark) {
      val data = if (isDark) darkThemeData else lightThemeData
      val painter = data.decodeToSvgPainter(LocalDensity.current)

      org.jetbrains.jewel.ui.component.Icon(
        painter = painter,
        contentDescription = description,
        modifier = modifier,
      )
    }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as OemLabIcon

      if (description != other.description) return false
      if (!lightThemeData.contentEquals(other.lightThemeData)) return false
      if (!darkThemeData.contentEquals(other.darkThemeData)) return false

      return true
    }

    override fun hashCode(): Int {
      var result = description.hashCode()
      result = 31 * result + lightThemeData.contentHashCode()
      result = 31 * result + darkThemeData.contentHashCode()
      return result
    }
  }

  enum class IconType(val type: String) {
    CAR("car"),
    PHONE("phone"),
    TV("tv"),
    WEAR("wear"),
    XR_HEADSET(type = "xr_headset"),
    XR_GLASSES(type = "xr_glasses");

    companion object {
      fun fromFormFactorString(formFactor: String): IconType {
        return when (formFactor) {
          CAR.type -> CAR
          PHONE.type -> PHONE
          TV.type -> TV
          WEAR.type -> WEAR
          XR_HEADSET.type -> XR_HEADSET
          XR_GLASSES.type -> XR_GLASSES
          else -> PHONE
        }
      }
    }
  }

  data class OemLabAsset(val name: String, val icons: Map<IconType, OemLabIcon>) {
    companion object {
      private val gson = GsonBuilder().setLenient().create()

      fun extract(assetId: String, fetcher: (relative: String) -> InputStream): OemLabAsset {
        val jsonString = fetcher.invoke("$assetId/descriptor.json").reader().use { it.readText() }
        val descriptor = gson.fromJson(jsonString, Descriptor::class.java)
        val icons =
          descriptor.icons.associate { icon ->
            val type = IconType.fromFormFactorString(icon.formFactor)
            val populatedIcon =
              OemLabIcon(
                description = "${assetId}_${type.type}",
                lightThemeData = fetcher.invoke("$assetId/${icon.pathLight}").readBytes(),
                darkThemeData = fetcher.invoke("$assetId/${icon.pathDark}").readBytes(),
              )

            type to populatedIcon
          }

        return OemLabAsset(descriptor.name, icons)
      }
    }

    private data class Descriptor(val name: String, val icons: List<Icon>)

    private data class Icon(val formFactor: String, val pathLight: String, val pathDark: String)
  }

  companion object {
    fun getInstance() = service<OemLabsAssetsRegistry>()
  }
}

private fun renderSvgToImage(
  data: ByteArray,
  scale: Float,
  parameters: LoadIconParameters,
  scaleContext: ScaleContext,
): Image {
  val image = renderSvg(data, scale)
  return convertImage(
    image = image,
    filters = parameters.filters,
    scaleContext = scaleContext,
    isUpScaleNeeded = false,
    imageScale = scale,
  )
}
