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

import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.testing.flags.overrideForTest
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import org.junit.Rule
import org.junit.Test

class OemLabsAssetsRegistryTest {
  @get:Rule val applicationRule = ApplicationRule()
  @get:Rule val disposableRule = DisposableRule()

  @Test
  fun `google lab test`() {
    val registry = OemLabsAssetsRegistry(useNetwork = false)
    val asset: OemLabsAssetsRegistry.OemLabAsset = registry.getAssetById("google")!!
    with(asset) {
      assertThat(name).isEqualTo("Google")
      assertThat(icons.keys).containsExactlyElementsIn(OemLabsAssetsRegistry.IconType.values())
    }
    val icon = registry.retrieveIcon("Google", OemLabsAssetsRegistry.IconType.PHONE)
    val expectedBytes =
      OemLabsAssetsRegistry::class.java.getResourceAsStream("/oem-labs-assets-offline/google/phone.svg")!!.use { it.readBytes() }
    assertThat(icon.lightThemeData).isEqualTo(expectedBytes)
    assertThat(icon.description).isEqualTo("google_phone")
  }

  @Test
  fun `google lab test when cloud branding enabled`() {
    StudioFlags.DIRECT_ACCESS_CLOUD_BRANDING.overrideForTest(true, disposableRule.disposable)
    val registry = OemLabsAssetsRegistry(useNetwork = false)
    val asset: OemLabsAssetsRegistry.OemLabAsset = registry.getAssetById("google")!!
    with(asset) {
      assertThat(name).isEqualTo("Google")
      assertThat(icons.keys).containsExactlyElementsIn(OemLabsAssetsRegistry.IconType.values())
    }
    val icon = registry.retrieveIcon("Google", OemLabsAssetsRegistry.IconType.PHONE)
    val expectedBytes =
      OemLabsAssetsRegistry::class.java.getResourceAsStream("/oem-labs-assets-offline/google_cloud/phone.svg")!!.use { it.readBytes() }
    assertThat(icon.lightThemeData).isEqualTo(expectedBytes)
    assertThat(icon.description).isEqualTo("google_cloud_phone")
    assertThat(registry.retrieveName("Google")).isEqualTo("Google")
  }

  @Test
  fun `google lab icon flips between firebase and gc icons when cloud branding flag changes`() {
    val registry = OemLabsAssetsRegistry(useNetwork = false)

    // 1. When flag is disabled -> uses firebase-device-* icon
    StudioFlags.DIRECT_ACCESS_CLOUD_BRANDING.overrideForTest(false, disposableRule.disposable)
    val fbIcon = registry.retrieveIcon("Google", OemLabsAssetsRegistry.IconType.PHONE)
    val expectedFbBytes =
      OemLabsAssetsRegistry::class.java.getResourceAsStream("/oem-labs-assets-offline/google/phone.svg")!!.use { it.readBytes() }
    assertThat(fbIcon.lightThemeData).isEqualTo(expectedFbBytes)
    assertThat(fbIcon.description).isEqualTo("google_phone")
    assertThat(registry.retrieveName("Google")).isEqualTo("Google")

    // 2. When flag is enabled -> uses gc-device-* icon
    StudioFlags.DIRECT_ACCESS_CLOUD_BRANDING.overrideForTest(true, disposableRule.disposable)
    val gcIcon = registry.retrieveIcon("Google", OemLabsAssetsRegistry.IconType.PHONE)
    val expectedGcBytes =
      OemLabsAssetsRegistry::class.java.getResourceAsStream("/oem-labs-assets-offline/google_cloud/phone.svg")!!.use { it.readBytes() }
    assertThat(gcIcon.lightThemeData).isEqualTo(expectedGcBytes)
    assertThat(gcIcon.description).isEqualTo("google_cloud_phone")
    assertThat(registry.retrieveName("Google")).isEqualTo("Google")
  }

  @Test
  fun `unknown lab test`() {
    val registry = OemLabsAssetsRegistry(useNetwork = false)
    val asset: OemLabsAssetsRegistry.OemLabAsset = registry.getAssetById("some_new_lab")!!
    with(asset) {
      assertThat(name).isEqualTo("Unknown")
      assertThat(icons.keys)
        .containsExactlyElementsIn(
          listOf(
            OemLabsAssetsRegistry.IconType.CAR,
            OemLabsAssetsRegistry.IconType.PHONE,
            OemLabsAssetsRegistry.IconType.TV,
            OemLabsAssetsRegistry.IconType.WEAR,
            OemLabsAssetsRegistry.IconType.XR_HEADSET,
            OemLabsAssetsRegistry.IconType.AI_GLASSES,
          )
        )
    }
  }
}
