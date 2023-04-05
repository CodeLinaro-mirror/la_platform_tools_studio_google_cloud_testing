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
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.cloudresourcemanager.v3.CloudResourceManager;
import com.google.api.services.storage.Storage;
import com.google.api.services.testing.Testing;
import com.google.api.services.testing.model.AndroidDeviceCatalog;
import com.google.api.services.toolresults.ToolResults;
import com.google.gct.login.GoogleLogin;
import com.google.gct.testing.CloudTestingUtils;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.VisibleForTesting;

public class CloudAuthenticator {

  private static final String APPLICATION_NAME = "GCTL";

  private static CloudAuthenticator instance;

  /** Global instance of the HTTP transport. */
  private HttpTransport myHttpTransport;
  private Credential myCredential;
  private Storage myStorage;
  private CloudResourceManager myCloudResourceManager;
  private Testing myTest;
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
    if (myCloudResourceManager == null) {
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
    } finally {
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
    if (myCredential == null) {
      if (!authorize()) {
        throw new RuntimeException("Failed to authorize to Google Cloud!");
      }
      myCredential = GoogleLogin.getInstance().getCredential();
    }
  }

  @NotNull
  private HttpTransport createHttpTransport() {
    try {
      return GoogleNetHttpTransport.newTrustedTransport();
    } catch (Exception e) {
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
      googleLogin.logIn();
      credential = googleLogin.getCredential();
      return credential != null;
    }
    return true;
  }

  public static boolean isUserLoggedIn() {
    return GoogleLogin.getInstance().getCredential() != null;
  }

}
