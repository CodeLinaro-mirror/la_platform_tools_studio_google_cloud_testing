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
package com.google.gct.directaccess.rule

import com.google.gct.directaccess.provisioner.BLAZE_MULTI_DEVICE_DO_NOT_ASK
import com.google.gct.directaccess.provisioner.BLAZE_SINGLE_DEVICE_DO_NOT_ASK
import com.google.gct.directaccess.provisioner.SPARK_MULTI_DEVICE_DO_NOT_ASK
import com.google.gct.directaccess.provisioner.SPARK_SINGLE_DEVICE_DO_NOT_ASK
import com.google.gct.directaccess.provisioner.UNKNOWN_DEVICE_DO_NOT_ASK
import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.ProjectRule
import org.junit.rules.ExternalResource

private val keyList =
  listOf(
    UNKNOWN_DEVICE_DO_NOT_ASK,
    SPARK_SINGLE_DEVICE_DO_NOT_ASK,
    SPARK_MULTI_DEVICE_DO_NOT_ASK,
    BLAZE_SINGLE_DEVICE_DO_NOT_ASK,
    BLAZE_MULTI_DEVICE_DO_NOT_ASK,
  )

class PropertiesComponentRule(private val projectRule: ProjectRule) : ExternalResource() {
  override fun before() {
    keyList.forEach { PropertiesComponent.getInstance(projectRule.project).setValue(it, true) }
  }

  override fun after() {
    keyList.forEach { PropertiesComponent.getInstance(projectRule.project).unsetValue(it) }
  }
}
