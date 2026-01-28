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
package com.google.gct.testrecorder.util

import com.android.tools.idea.projectsystem.AndroidProjectSystem
import com.android.tools.idea.projectsystem.Token
import com.google.gct.testrecorder.event.ElementAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.module.Module
import javax.swing.JPanel
import org.jetbrains.android.facet.AndroidFacet

interface EspressoSetupToken<P : AndroidProjectSystem> : Token {
  /**
   * Return true if we should generate androidx test code, false otherwise.
   *
   * TODO(xof): extract androidxness from the module system instead, and stop returning a value from this.
   */
  fun ensureSetup(
    projectSystem: P,
    testClassModule: Module,
    facet: AndroidFacet,
    rootPanel: JPanel,
    elementActions: MutableList<ElementAction>,
  ): Boolean

  /** Return true if the given module supports Kotlin source code. */
  fun supportsKotlin(projectSystem: P, testClassModule: Module): Boolean

  /** Return true if this module corresponds in some sense to a native project. */
  fun isNativeProject(projectSystem: P, module: Module): Boolean

  companion object {
    @JvmField
    val EP_NAME = ExtensionPointName<EspressoSetupToken<AndroidProjectSystem>>("com.google.gct.testrecorder.util.espressoSetupToken")
  }
}
