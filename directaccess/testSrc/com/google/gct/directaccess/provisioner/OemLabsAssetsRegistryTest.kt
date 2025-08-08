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

import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.ApplicationRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class OemLabsAssetsRegistryTest {
  @get:Rule val rule = RuleChain.outerRule(ApplicationRule())

  @Test
  fun `google lab test`() {
    val registry = OemLabsAssetsRegistry(useNetwork = false)
    val asset: OemLabsAssetsRegistry.OemLabAsset = registry.getAssetById("google")!!
    with(asset) {
      assertThat(name).isEqualTo("Google")
      assertThat(icons.keys).containsExactlyElementsIn(OemLabsAssetsRegistry.IconType.values())
    }
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
            OemLabsAssetsRegistry.IconType.XR_GLASSES,
          )
        )
    }
  }
}
