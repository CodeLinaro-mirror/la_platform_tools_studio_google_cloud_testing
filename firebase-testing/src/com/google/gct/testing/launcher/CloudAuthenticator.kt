/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.google.gct.testing.launcher

import com.google.api.client.http.HttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.cloudbilling.Cloudbilling
import com.google.api.services.cloudresourcemanager.v3.CloudResourceManager
import com.google.api.services.monitoring.v3.Monitoring
import com.google.api.services.monitoring.v3.model.PointData
import com.google.api.services.monitoring.v3.model.QueryTimeSeriesRequest
import com.google.api.services.monitoring.v3.model.QueryTimeSeriesResponse
import com.google.api.services.monitoring.v3.model.TimeSeriesData
import com.google.api.services.storage.Storage
import com.google.api.services.testing.Testing
import com.google.api.services.testing.model.AndroidDeviceCatalog
import com.google.api.services.toolresults.ToolResults
import com.google.gct.login.LoginState
import com.google.gct.login2.GoogleLoginService
import com.google.gct.login2.LoginFeature
import com.google.gct.testing.CloudTestingUtils
import com.google.services.firebase.FirebaseLoginFeature
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.io.IOException
import java.time.Instant
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val APPLICATION_NAME = "GCTL"

@Service
class CloudAuthenticator(scope: CoroutineScope) {
  /** Global instance of the HTTP transport. */
  private var myHttpTransport: HttpTransport? = null
    get() = field ?: NetHttpTransport().also { field = it }

  private var myStorage: Storage? = null
  private var myCloudbilling: Cloudbilling? = null
  private var myCloudResourceManager: CloudResourceManager? = null
  private var myTest: Testing? = null
  private var myMonitoring: Monitoring? = null
  private var myToolresults: ToolResults? = null
  private var myLastDiscoveryServiceInvocationTimestamp: Long = -1

  init {
    scope.launch {
      fun reset() {
        myHttpTransport = null
        myStorage = null
        myCloudResourceManager = null
        myTest = null
        myMonitoring = null
        myToolresults = null
      }

      if (service<GoogleLoginService>().useOldVersion) {
        service<LoginState>().loginStatus.collect { reset() }
      } else {
        service<GoogleLoginService>().activeUserFlow.collect { reset() }
      }
    }
  }

  private val firebaseFeature = LoginFeature.feature<FirebaseLoginFeature>()

  val storage: Storage
    get() {
      return myStorage
        ?: Storage.Builder(myHttpTransport, GsonFactory.getDefaultInstance(), firebaseFeature.credential())
          .setApplicationName(APPLICATION_NAME)
          .build()
          .also { myStorage = it }
    }

  fun recreateTestAndToolResults(testBackendUrl: String?, toolResultsBackendUrl: String?) {
    myTest =
      Testing.Builder(myHttpTransport, GsonFactory.getDefaultInstance(), firebaseFeature.credential())
        .setApplicationName(APPLICATION_NAME)
        .setRootUrl(testBackendUrl)
        .build()
    myToolresults =
      ToolResults.Builder(myHttpTransport, GsonFactory.getDefaultInstance(), firebaseFeature.credential())
        .setApplicationName(APPLICATION_NAME)
        .setRootUrl(toolResultsBackendUrl)
        .build()
  }

  val cloudbilling: Cloudbilling
    get() {
      return myCloudbilling
        ?: Cloudbilling.Builder(
          myHttpTransport,
          GsonFactory.getDefaultInstance(),
          firebaseFeature.credential(),
        ).setApplicationName(APPLICATION_NAME).build().also { myCloudbilling = it }
    }

  val cloudResourceManager: CloudResourceManager
    get() {
      return myCloudResourceManager
        ?: CloudResourceManager.Builder(
            myHttpTransport,
            GsonFactory.getDefaultInstance(),
            firebaseFeature.credential(),
          )
          .setApplicationName(APPLICATION_NAME)
          .build()
          .also { myCloudResourceManager = it }
    }

  /** Get a test client pointing to the default (prod) backend. */
  val test: Testing
    get() = getTest(null)

  /** Get a test client pointing to the given backend. */
  private fun getTest(endpoint: String?): Testing {
    return myTest
      ?: Testing.Builder(myHttpTransport, GsonFactory.getDefaultInstance(), firebaseFeature.credential())
        .setApplicationName(APPLICATION_NAME)
        .apply {
          if (endpoint != null) {
            setRootUrl(endpoint)
          }
        }
        .build()
        .also { myTest = it }
  }

  private fun getMonitoring(endpoint: String?): Monitoring {
    return myMonitoring
      ?: Monitoring.Builder(myHttpTransport, GsonFactory.getDefaultInstance(), firebaseFeature.credential())
        .setApplicationName(APPLICATION_NAME)
        .apply {
          if (endpoint != null) {
            setRootUrl(endpoint)
          }
        }
        .build()
        .also { myMonitoring = it }
  }

  /** Get the [AndroidDeviceCatalog] for the given FTL `endpoint`. */
  @Throws(IOException::class)
  fun getAndroidDeviceCatalogForEnvironment(
    endpoint: String?,
    gcpProject: String?,
  ): AndroidDeviceCatalog {
    val currentTimestamp = System.currentTimeMillis()
    try {
      val getter = getTest(endpoint).testEnvironmentCatalog()["ANDROID"]
      getter.setProjectId(gcpProject)
      getter.requestHeaders["X-Goog-User-Project"] = gcpProject
      val catalog = getter.execute().androidDeviceCatalog
      if (
        catalog.versions.isEmpty() ||
          catalog.models.isEmpty() ||
          catalog.runtimeConfiguration.locales.isEmpty() ||
          catalog.runtimeConfiguration.orientations.isEmpty()
      ) {
        showDeviceCatalogError(
          "Android device catalog is empty for some dimensions",
          currentTimestamp,
        )
      }
      return catalog
    } finally {
      myLastDiscoveryServiceInvocationTimestamp = currentTimestamp
    }
  }

  /** Get the [AndroidDeviceCatalog] for the default (prod) FTL backend. */
  val androidDeviceCatalog: AndroidDeviceCatalog?
    get() {
      try {
        return getAndroidDeviceCatalogForEnvironment(null, null)
      } catch (e: IOException) {
        showDeviceCatalogError(
          """
  Exception while getting Android device catalog

  ${e.message}
  """
            .trimIndent(),
          System.currentTimeMillis(),
        )
        return null
      }
    }

  fun isBillingEnabled(cloudProject: String):Boolean =
      cloudbilling.projects().getBillingInfo("projects/$cloudProject").execute().billingEnabled

  @Throws(IOException::class)
  private fun queryMonitoring(
    endpoint: String,
    project: String,
    queryString: String,
  ): QueryTimeSeriesResponse {
    val monitoring = getMonitoring(endpoint)
    val request = QueryTimeSeriesRequest().setQuery(queryString).setPageSize(200)
    return monitoring.projects().timeSeries().query(project, request).execute()
  }

  /**
   * Returns remaining quota in minutes for the endPoint and project, -1 if not available.
   *
   * @param endpoint end point of the monitoring backend, effective only for the first calling
   * @param project name of the cloud project
   */
  fun getQuotaUsageAndLimit(endpoint: String, project: String): Pair<Long, Long>? {
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    calendar.timeInMillis = CloudTestingUtils.getTimestampAtMidnightInPT(Instant.now())
    // Sets up the beginning date of the query interval.
    val date =
      String.format(
        Locale.US,
        "d'%d/%d/%d 7:00'",
        calendar[Calendar.YEAR],
        calendar[Calendar.MONTH] + 1,
        calendar[Calendar.DAY_OF_MONTH],
      )

    try {
      val usageResponse =
        queryMonitoring(
          endpoint,
          project,
          """fetch consumer_quota | metric 'serviceruntime.googleapis.com/quota/rate/net_usage'
| filter metric.quota_metric=="testing.googleapis.com/direct_access/blaze_physical_minutes" || metric.quota_metric=="testing.googleapis.com/direct_access/spark_physical_minutes"
| within $date""",
        )
      // Response does not has enough data to determine usage.
      if (usageResponse.size < 2) {
        return null
      }
      val usageNumber = sumNumbers(usageResponse)
      val limitResponse =
        queryMonitoring(
          endpoint,
          project,
          """
              fetch consumer_quota
              | metric 'serviceruntime.googleapis.com/quota/limit'
              | filter metric.limit_name=="BlazePhysicalDeviceDirectAccessMinutesPerDayPerProject"|| metric.limit_name=="SparkPhysicalDeviceDirectAccessMinutesPerDayPerProject"
              | within $date
              """
            .trimIndent(),
        )
      val limitNumber = findNumber(limitResponse)
      // Response does not has enough data to determine usage limit.
      if (usageResponse.size < 2) {
        return null
      }
      return Pair(usageNumber, limitNumber)
    } catch (e: Exception) {
      // TODO: Surface errors in the UI.
      return null
    }
  }

  private fun findNumber(item: QueryTimeSeriesResponse): Long {
    val timeSeriesData = (item["timeSeriesData"] as ArrayList<*>?)!![0] as TimeSeriesData
    val pointData = timeSeriesData.pointData[0]
    return pointData.getValues()[0].int64Value
  }

  private fun sumNumbers(item: QueryTimeSeriesResponse): Long {
    return (item["timeSeriesData"] as ArrayList<*>)
      .stream()
      .mapToLong { timeSeriesData ->
        (timeSeriesData as TimeSeriesData)
          .pointData
          .stream()
          .mapToLong { a: PointData -> a.getValues()[0].int64Value }
          .sum()
      }
      .sum()
  }

  private fun showDeviceCatalogError(errorMessageSuffix: String, currentTimestamp: Long) {
    // The error should be reported just once per burst of invocations.
    if (
      currentTimestamp - myLastDiscoveryServiceInvocationTimestamp > 1000L
    ) { // If more than a second has passed.
      CloudTestingUtils.showErrorMessage(
        null,
        "Error retrieving android device catalog",
        "Failed to retrieve available firebase devices! Please try again later.\n$errorMessageSuffix",
      )
    }
  }

  val toolresults: ToolResults
    get() =
      myToolresults
        ?: ToolResults.Builder(myHttpTransport, GsonFactory.getDefaultInstance(), firebaseFeature.credential())
          .setApplicationName(APPLICATION_NAME)
          .build()
          .also { myToolresults = it }

  fun prepareCredential() {
    if (!firebaseFeature.isLoggedIn()) {
      if (!authorize()) {
        throw RuntimeException(
          "Failed to authorize to Google Cloud! Please check if you set the correct user account."
        )
      }
    }
  }

  /** Authorizes the installed application to access user's protected data. */
  fun authorize(): Boolean {
    if (!firebaseFeature.isLoggedIn()) {
      val complete = CompletableFuture<Nothing>()
      firebaseFeature.logInAsync { complete.complete(null) }
      complete.get()
    }
    return firebaseFeature.isLoggedIn()
  }

  companion object {

    @JvmStatic
    val instance: CloudAuthenticator
      get() = service<CloudAuthenticator>()

    @JvmStatic
    @Deprecated("Just check the status directly")
    val isUserLoggedIn: Boolean
      get() = LoginFeature.feature<FirebaseLoginFeature>().isLoggedIn()
  }
}
