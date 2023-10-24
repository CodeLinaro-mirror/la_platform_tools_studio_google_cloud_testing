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
package com.google.gct.directaccess.analytics

import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.google.common.truth.Truth.assertThat
import com.google.gct.directaccess.TestUtils
import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.ProjectRule
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test

class DirectAccessFeatureSurveysTest {
  @get:Rule val projectRule = ProjectRule()

  @Test
  fun testSurveyWorkflow() = runBlockingWithTimeout {
    resetPropertiesComponent()
    val calendar = Calendar.getInstance()
    val directAccessFeatureSurveys = DirectAccessFeatureSurveys { calendar }

    for (count in 1..MINIMUM_DAYS_WITH_CONNECTION_BEFORE_SURVEY) {
      directAccessFeatureSurveys.trackConnection()
      assertThat(PropertiesComponent.getInstance().getLong(DAYS_WITH_CONNECTION_FIELD, 0))
        .isEqualTo(count)
      calendar.time = Date(calendar.time.time + TimeUnit.DAYS.toMillis(1))
      directAccessFeatureSurveys.trackDisconnection()

      if (count < MINIMUM_DAYS_WITH_CONNECTION_BEFORE_SURVEY) {
        assertThat(PropertiesComponent.getInstance().getBoolean(SURVEY_DONE_FIELD, false)).isFalse()
      } else {
        assertThat(PropertiesComponent.getInstance().getBoolean(SURVEY_DONE_FIELD, false)).isTrue()
        yieldUntil { TestUtils.getNotifications(projectRule.project).size == 1 }
        val notification = TestUtils.getNotifications(projectRule.project)[0]
        assertThat(notification.title).isEqualTo("Device Streaming feature surveys")
        notification.expire()
      }
    }
    resetPropertiesComponent()
  }

  private fun resetPropertiesComponent() {
    PropertiesComponent.getInstance().setValue(LAST_DAY_WITH_CONNECTION_FIELD, "")
    PropertiesComponent.getInstance().setValue(SURVEY_DONE_FIELD, false)
    PropertiesComponent.getInstance().setValue(DAYS_WITH_CONNECTION_FIELD, "")
  }
}
