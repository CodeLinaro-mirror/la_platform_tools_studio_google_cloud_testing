/*
 * Copyright (C) 2025 The Android Open Source Project
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

import com.android.tools.idea.gservices.DevServicesDeprecationData
import com.android.tools.idea.gservices.DevServicesDeprecationDataProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Service
class DirectAccessDeprecationState(scope: CoroutineScope) : Disposable {

  val serviceDeprecationData: StateFlow<DevServicesDeprecationData> =
    service<DevServicesDeprecationDataProvider>()
      .registerServiceForChange("directaccess/directaccess", "Android Device Streaming", this@DirectAccessDeprecationState)

  // Service stays enabled for SUPPORTED and DEPRECATED
  val isServiceEnabledFlow = serviceDeprecationData.map { data -> !data.isUnsupported() }.stateIn(scope, SharingStarted.Eagerly, true)

  override fun dispose() = Unit
}
