/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.google.gct.directaccess.settings

import com.android.tools.idea.flags.StudioFlags
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

@com.intellij.openapi.components.State(
  name = "DirectAccessConfiguration",
  storages = [(Storage(StoragePathMacros.NON_ROAMABLE_FILE))],
)
@Service
class DirectAccessConfiguration :
  SimplePersistentStateComponent<DirectAccessConfiguration.State>(State()) {
  class State : BaseState() {
    // TODO(b/304622231) deprecate StudioFlags.DIRECT_ACCESS
    var isEnabled by property(StudioFlags.DIRECT_ACCESS.get())
  }

  var isEnabled
    get() = state.isEnabled
    set(value) {
      state.isEnabled = value
    }
}
