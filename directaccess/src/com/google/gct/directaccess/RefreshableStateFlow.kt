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
package com.google.gct.directaccess

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Exposes a [StateFlow] that updates its value periodically with [Dispatchers.IO].
 *
 * @param refreshIntervalMs refresh interval of the state flow
 * @param refresher a function that gets the latest value to update [stateFlow]
 */
class RefreshableStateFlow<T>(
  scope: CoroutineScope,
  refreshIntervalMs: Long,
  private val refresher: () -> T
) {
  private val mutex = Mutex(false)

  private var job: Job? = null

  private val _stateFlow = MutableStateFlow(refresher())

  val stateFlow: StateFlow<T> = _stateFlow

  init {
    scope.launch {
      while (true) {
        mutex.withLock {
          job = launch {
            delay(refreshIntervalMs)
            internalRefresh()
          }
        }
        job?.join()
      }
    }
  }

  /**
   * Refreshes the state flow and returns its value immediately.
   *
   * This method runs [refresher] sequentially with the internal periodic updater and resets its
   * update interval.
   */
  suspend fun refresh(): T =
    mutex.withLock {
      job?.cancelAndJoin()
      internalRefresh()
    }

  private suspend fun internalRefresh(): T {
    val result = withContext(Dispatchers.IO) { refresher() }
    return _stateFlow.updateAndGet { result }
  }
}
