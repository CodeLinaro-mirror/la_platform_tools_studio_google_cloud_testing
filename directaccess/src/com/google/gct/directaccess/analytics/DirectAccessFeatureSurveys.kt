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

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.util.Calendar
import java.util.concurrent.TimeUnit

internal const val MINIMUM_DAYS_WITH_CONNECTION_BEFORE_SURVEY = 5
internal const val LAST_DAY_WITH_CONNECTION_FIELD = "direct.access.connection.last.day"
internal const val DAYS_WITH_CONNECTION_FIELD = "direct.access.connection.days"
internal const val SURVEY_DONE_FIELD = "direct.access.survey.done"

@Service(Service.Level.APP)
class DirectAccessFeatureSurveys(val calendar: () -> Calendar = { Calendar.getInstance() }) {

  private var lastDayWithConnection = PropertiesComponent.getInstance().getLong(LAST_DAY_WITH_CONNECTION_FIELD, 0)
    set(value) {
      field = value
      PropertiesComponent.getInstance().setValue(LAST_DAY_WITH_CONNECTION_FIELD, value.toString())
    }

  private var isSurveyDone = PropertiesComponent.getInstance().getBoolean(SURVEY_DONE_FIELD, false)
    set(value) {
      field = value
      PropertiesComponent.getInstance().setValue(SURVEY_DONE_FIELD, value)
    }

  private var daysWithConnection = PropertiesComponent.getInstance().getLong(DAYS_WITH_CONNECTION_FIELD, 0)
    set(value) {
      field = value
      PropertiesComponent.getInstance().setValue(DAYS_WITH_CONNECTION_FIELD, value.toString())
    }

  private val lock = Any()

  fun trackConnection() =
    synchronized(lock) {
      if (isSurveyDone || daysWithConnection >= MINIMUM_DAYS_WITH_CONNECTION_BEFORE_SURVEY) return
      val currentDayNumber = getDayNumber()
      if (currentDayNumber == lastDayWithConnection) return

      lastDayWithConnection = currentDayNumber
      daysWithConnection += 1
    }

  fun trackDisconnection() {
    synchronized(lock) {
      if (isSurveyDone || daysWithConnection < MINIMUM_DAYS_WITH_CONNECTION_BEFORE_SURVEY) return
      isSurveyDone = true
    }
    triggerSurvey()
  }

  private fun getDayNumber(): Long {
    val currentCalendar = calendar()
    val date = currentCalendar.time.time
    return (date + currentCalendar.timeZone.getOffset(date)) / TimeUnit.DAYS.toMillis(1)
  }

  private fun triggerSurvey() {
    val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("Feature Survey") ?: return

    val notification =
      notificationGroup.createNotification(
        "Device Streaming feature surveys",
        "Would you like to take a brief survey based on your recent activity to help us improve Android Studio?",
        NotificationType.INFORMATION,
      )

    notification.addAction(
      object : NotificationAction("Take survey") {
        override fun actionPerformed(e: AnActionEvent, notification: Notification) {
          BrowserUtil.browse("https://forms.gle/tzvZrJ6oQ5cwM9j9A")
        }
      }
    )

    ApplicationManager.getApplication().invokeLater { Notifications.Bus.notify(notification) }
  }
}
