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
package com.google.gct.testing.launcher;

import com.android.annotations.Nullable;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.cloudresourcemanager.v3.CloudResourceManager;
import com.google.api.services.monitoring.v3.Monitoring;
import com.google.api.services.monitoring.v3.model.PointData;
import com.google.api.services.monitoring.v3.model.QueryTimeSeriesRequest;
import com.google.api.services.monitoring.v3.model.QueryTimeSeriesResponse;
import com.google.api.services.monitoring.v3.model.TimeSeriesData;
import com.google.api.services.storage.Storage;
import com.google.api.services.testing.Testing;
import com.google.api.services.testing.model.AndroidDeviceCatalog;
import com.google.api.services.toolresults.ToolResults;
import com.google.gct.login.GoogleLogin;
import com.google.gct.login.IGoogleLoginCompletedCallback;
import com.google.gct.testing.CloudTestingUtils;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import kotlin.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.VisibleForTesting;

public class CloudAuthenticator {

  private static final String APPLICATION_NAME = "GCTL";

  private static CloudAuthenticator instance;

  /**
   * Global instance of the HTTP transport.
   */
  private HttpTransport myHttpTransport;
  private Credential myCredential;
  private Storage myStorage;
  private CloudResourceManager myCloudResourceManager;
  private Testing myTest;
  private Monitoring myMonitoring;
  private ToolResults myToolresults;
  private long myLastDiscoveryServiceInvocationTimestamp = -1;


  @NotNull
  public static CloudAuthenticator getInstance() {
    if (instance == null) {
      instance = new CloudAuthenticator();
    }
    return instance;
  }

  /**
   * Should be used in tests only!
   */
  @VisibleForTesting
  public static void setInstance(CloudAuthenticator testInstance) {
    instance = testInstance;
  }

  @NotNull
  public Storage getStorage() {
    prepareCredential();
    if (myStorage == null) {
      myStorage =
        new Storage.Builder(myHttpTransport, GsonFactory.getDefaultInstance(), myCredential).setApplicationName(APPLICATION_NAME).build();
    }
    return myStorage;
  }

  public void recreateTestAndToolResults(String testBackendUrl, String toolResultsBackendUrl) {
    prepareCredential();
    myTest =
      new Testing.Builder(myHttpTransport, GsonFactory.getDefaultInstance(), myCredential).setApplicationName(APPLICATION_NAME)
        .setRootUrl(testBackendUrl).build();
    myToolresults =
      new ToolResults.Builder(myHttpTransport, GsonFactory.getDefaultInstance(), myCredential).setApplicationName(APPLICATION_NAME)
        .setRootUrl(toolResultsBackendUrl).build();
  }

  @NotNull
  public CloudResourceManager getCloudResourceManager() {
    prepareCredential();
    if (myCloudResourceManager == null
        || myCloudResourceManager.getRequestFactory() == null
        || myCloudResourceManager.getRequestFactory().getInitializer() != GoogleLogin.getInstance().getCredential()) {
      myCloudResourceManager =
        new CloudResourceManager.Builder(myHttpTransport, GsonFactory.getDefaultInstance(), myCredential)
          .setApplicationName(APPLICATION_NAME).build();
    }
    return myCloudResourceManager;
  }

  /**
   * Get a test client pointing to the default (prod) backend.
   */
  @NotNull
  public Testing getTest() {
    return getTest(null);
  }

  /**
   * Get a test client pointing to the given backend.
   */
  @NotNull
  public Testing getTest(@Nullable String endpoint) {
    prepareCredential();
    if (myTest == null) {
      Testing.Builder builder =
        new Testing.Builder(myHttpTransport, GsonFactory.getDefaultInstance(), myCredential).setApplicationName(APPLICATION_NAME);
      if (endpoint != null) {
        builder.setRootUrl(endpoint);
      }
      myTest = builder.build();
    }
    return myTest;
  }

  @NotNull
  private Monitoring getMonitoring(@Nullable String endpoint) {
    prepareCredential();
    if (myMonitoring == null) {
      Monitoring.Builder builder =
        new Monitoring.Builder(myHttpTransport, GsonFactory.getDefaultInstance(), myCredential).setApplicationName(APPLICATION_NAME);
      if (endpoint != null) {
        builder.setRootUrl(endpoint);
      }
      myMonitoring = builder.build();
    }
    return myMonitoring;
  }

  /**
   * Get the {@link AndroidDeviceCatalog} for the given FTL {@code endpoint}.
   */
  @NotNull
  public AndroidDeviceCatalog getAndroidDeviceCatalogForEnvironment(@Nullable String endpoint, @Nullable String gcpProject)
    throws IOException {
    long currentTimestamp = System.currentTimeMillis();
    try {
      Testing.TestEnvironmentCatalog.Get getter = getTest(endpoint)
        .testEnvironmentCatalog()
        .get("ANDROID");
      getter.setProjectId(gcpProject);
      getter.getRequestHeaders().set("X-Goog-User-Project", gcpProject);
      AndroidDeviceCatalog catalog = getter
        .execute()
        .getAndroidDeviceCatalog();
      if (catalog.getVersions().isEmpty() || catalog.getModels().isEmpty() || catalog.getRuntimeConfiguration().getLocales().isEmpty()
          || catalog.getRuntimeConfiguration().getOrientations().isEmpty()) {
        showDeviceCatalogError("Android device catalog is empty for some dimensions", currentTimestamp);
      }
      return catalog;
    }
    finally {
      myLastDiscoveryServiceInvocationTimestamp = currentTimestamp;
    }
  }

  /**
   * Get the {@link AndroidDeviceCatalog} for the default (prod) FTL backend.
   */
  @Nullable
  public AndroidDeviceCatalog getAndroidDeviceCatalog() {
    try {
      return getAndroidDeviceCatalogForEnvironment(null, null);
    }
    catch (IOException e) {
      showDeviceCatalogError("Exception while getting Android device catalog\n\n" + e.getMessage(), System.currentTimeMillis());
      return null;
    }
  }

  @NotNull
  private QueryTimeSeriesResponse queryMonitoring(@NotNull String endpoint, @NotNull String project, @NotNull String queryString)
    throws IOException {
    Monitoring monitoring = getMonitoring(endpoint);
    QueryTimeSeriesRequest request = new QueryTimeSeriesRequest()
      .setQuery(queryString)
      .setPageSize(200);
    return monitoring.projects().timeSeries()
      .query(project, request)
      .execute();
  }

  /**
   * Returns a pair of usage and limit numbers of quota in minutes for the endPoint and project, null if not available.
   *
   * @param endpoint end point of the monitoring backend, effective only for the first calling
   * @param project  name of the cloud project
   */
  @Nullable
  public Pair<Long, Long> getQuotaUsageAndLimit(@NotNull String endpoint, @NotNull String project) {
    Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    calendar.setTimeInMillis(CloudTestingUtils.getTimestampAtMidnightInPT(Instant.now()));
    // Sets up the beginning date of the query interval.
    String date = String.format(Locale.US, "d'%d/%d/%d 7:00'",
                                calendar.get(Calendar.YEAR),
                                calendar.get(Calendar.MONTH) + 1,
                                calendar.get(Calendar.DAY_OF_MONTH));

    try {
      QueryTimeSeriesResponse usageResponse = queryMonitoring(
        endpoint,
        project,
        "fetch consumer_quota | metric 'serviceruntime.googleapis.com/quota/rate/net_usage'\n" +
        "| filter metric.quota_metric==\"testing.googleapis.com/direct_access/blaze_physical_minutes\" " +
        "|| metric.quota_metric==\"testing.googleapis.com/direct_access/spark_physical_minutes\"\n" +
        "| within " + date
      );
      // Response does not has enough data to determine usage.
      if (usageResponse.size() < 2) {
        return null;
      }
      long usageNumber = sumNumbers(usageResponse);
      QueryTimeSeriesResponse limitResponse = queryMonitoring(
        endpoint,
        project,
        "fetch consumer_quota\n" +
        "| metric 'serviceruntime.googleapis.com/quota/limit'\n" +
        "| filter metric.limit_name==\"BlazePhysicalDeviceDirectAccessMinutesPerDayPerProject\"" +
        "|| metric.limit_name==\"SparkPhysicalDeviceDirectAccessMinutesPerDayPerProject\"\n" +
        "| within " + date
      );
      long limitNumber = findNumber(limitResponse);
      // Response does not has enough data to determine usage limit.
      if (usageResponse.size() < 2) {
        return null;
      }
      return new Pair<>(usageNumber, limitNumber);
    }
    catch (Exception e) {
      // TODO: Surface errors in the UI.
      CloudTestingUtils.showErrorMessage(null, "Error retrieving remaining quotas",
                                         "Failed to retrieve remaining quotas! Please try again later.\n" + e.getLocalizedMessage());
      return null;
    }
  }

  private long findNumber(@NotNull QueryTimeSeriesResponse item) {
    TimeSeriesData timeSeriesData = (TimeSeriesData)((ArrayList<?>)item.get("timeSeriesData")).get(0);
    PointData pointData = timeSeriesData.getPointData().get(0);
    return pointData.getValues().get(0).getInt64Value();
  }

  private long sumNumbers(@NotNull QueryTimeSeriesResponse item) {
    TimeSeriesData timeSeriesData = (TimeSeriesData)((ArrayList<?>)item.get("timeSeriesData")).get(0);
    return timeSeriesData.getPointData().stream().mapToLong((a) -> a.getValues().get(0).getInt64Value()).sum();
  }

  private void showDeviceCatalogError(String errorMessageSuffix, long currentTimestamp) {
    // The error should be reported just once per burst of invocations.
    if (currentTimestamp - myLastDiscoveryServiceInvocationTimestamp > 1000L) { // If more than a second has passed.
      CloudTestingUtils.showErrorMessage(null, "Error retrieving android device catalog",
                                         "Failed to retrieve available firebase devices! Please try again later.\n" + errorMessageSuffix);
    }
  }

  @NotNull
  public ToolResults getToolresults() {
    prepareCredential();
    if (myToolresults == null) {
      myToolresults =
        new ToolResults.Builder(myHttpTransport, GsonFactory.getDefaultInstance(), myCredential).setApplicationName(APPLICATION_NAME)
          .build();
    }
    return myToolresults;
  }

  public void prepareCredential() {
    if (myHttpTransport == null) {
      myHttpTransport = createHttpTransport();
    }
    GoogleLogin googleLogin = GoogleLogin.getInstance();
    if (myCredential == null || myCredential != googleLogin.getCredential()) {
      if (!authorize()) {
        throw new RuntimeException("Failed to authorize to Google Cloud! Please check if you set the correct user account.");
      }
      myCredential = googleLogin.getCredential();
    }
  }

  @NotNull
  private HttpTransport createHttpTransport() {
    try {
      return new NetHttpTransport();
    }
    catch (Exception e) {
      System.err.println(e.getMessage());
      throw new RuntimeException("Failed to acquire HTTP transport for Google Cloud Storage!");
    }
  }

  /**
   * Authorizes the installed application to access user's protected data.
   */
  public static boolean authorize() {
    final GoogleLogin googleLogin = GoogleLogin.getInstance();
    Credential credential = googleLogin.getCredential();
    if (credential == null) {
      googleLogin.logIn(null, (IGoogleLoginCompletedCallback)null);
      credential = googleLogin.getCredential();
      return credential != null;
    }
    return true;
  }

  public static boolean isUserLoggedIn() {
    return GoogleLogin.getInstance().getCredential() != null;
  }
}
