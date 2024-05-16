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
package com.google.gct.directaccess.rule

import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.google.gct.directaccess.TestUtils.getNotifications
import com.intellij.testFramework.ProjectRule
import org.junit.rules.ExternalResource

internal class CleanUpNotificationRule(private val projectRule: ProjectRule) : ExternalResource() {
  override fun after() = runBlockingWithTimeout {
    getNotifications(projectRule.project).forEach { it.expire() }
    yieldUntil { getNotifications(projectRule.project).isEmpty() }
  }
}
