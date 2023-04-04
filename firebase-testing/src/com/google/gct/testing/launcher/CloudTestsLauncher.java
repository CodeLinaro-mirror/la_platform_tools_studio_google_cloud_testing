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

import static com.google.gct.testing.CloudTestingUtils.ANDROID_STUDIO_URL_FLAG;

import com.google.api.client.http.InputStreamContent;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.model.StorageObject;
import com.google.api.services.testing.model.AndroidInstrumentationTest;
import com.google.api.services.testing.model.AndroidMatrix;
import com.google.api.services.testing.model.ClientInfo;
import com.google.api.services.testing.model.EnvironmentMatrix;
import com.google.api.services.testing.model.FileReference;
import com.google.api.services.testing.model.GoogleCloudStorage;
import com.google.api.services.testing.model.ResultStorage;
import com.google.api.services.testing.model.TestMatrix;
import com.google.api.services.testing.model.TestSpecification;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.gct.testing.CloudConfigurationImpl;
import com.google.gct.testing.CloudTestingUtils;
import com.google.gct.testing.dimension.CloudTestingType;
import com.google.gct.testing.dimension.DeviceDimension;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.ui.Messages;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;


public class CloudTestsLauncher {

  private static final Function<CloudTestingType, String> TO_CLOUD_TESTING_TYPE_IDS = CloudTestingType::getId;

  public CloudTestsLauncher() {
  }

  /**
   * Returns {@code StorageObject} for the uploaded file (i.e., the file in the bucket).
   */
  public static StorageObject uploadFile(String bucketName, String uniquePrefix, File file) {
    InputStreamContent mediaContent;
    try {
      mediaContent = new InputStreamContent("application/octet-stream", new FileInputStream(file));
    }
    catch (FileNotFoundException e) {
      throw new RuntimeException(e);
    }

    // Setting the size of the uploaded file is extremely important! It reduces upload times by two orders of magnitude!
    mediaContent.setLength(file.length());

    try {
      Storage.Objects.Insert insertObject = CloudAuthenticator.getInstance().getStorage().objects().insert(bucketName, null, mediaContent);

      // If you don't provide metadata, you will have specify the object
      // name by parameter. You will probably also want to ensure that your
      // default object ACLs (a bucket property) are set appropriately:
      // https://developers.google.com/storage/docs/json_api/v1/buckets#defaultObjectAcl
      insertObject.setName(uniquePrefix + "/" + file.getName());

      return insertObject.execute();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns the triggered test matrix or {@code null} if the attempt was unsuccessful.
   */
  public static @Nullable TestMatrix triggerTestApi(
    String cloudProjectId, String bucketGcsPath, String appApkGcsPath, String testApkGcsPath, String testSpecification,
    String instrumentationTestRunner, CloudConfigurationImpl cloudTestConfiguration) {

    TestMatrix testMatrix = new TestMatrix();

    testMatrix.setClientInfo(new ClientInfo().setName("Android Studio " + ApplicationInfo.getInstance().getFullVersion()));

    // Max timeout is 45 minutes for physical and 60 minutes for virtual devices.
    final String testTimeout = usesPhysicalDevice(cloudTestConfiguration) ? "2700s" : "3600s";

    testMatrix.setTestSpecification(
      new TestSpecification().setTestTimeout(testTimeout).setAndroidInstrumentationTest(
        new AndroidInstrumentationTest().setAppApk(new FileReference().setGcsPath(appApkGcsPath))
          .setTestApk(new FileReference().setGcsPath(testApkGcsPath)).setTestRunnerClass(instrumentationTestRunner)
          .setTestTargets(Lists.newArrayList(testSpecification))));

    testMatrix.setResultStorage(new ResultStorage().setGoogleCloudStorage(new GoogleCloudStorage().setGcsPath(bucketGcsPath)));

    AndroidMatrix androidMatrix = new AndroidMatrix();

    androidMatrix.setAndroidModelIds(
      Lists.transform(cloudTestConfiguration.getDeviceDimension().getEnabledTypes(), TO_CLOUD_TESTING_TYPE_IDS));

    androidMatrix.setAndroidVersionIds(
      Lists.transform(cloudTestConfiguration.getApiDimension().getEnabledTypes(), TO_CLOUD_TESTING_TYPE_IDS));

    androidMatrix.setLocales(
      Lists.transform(cloudTestConfiguration.getLanguageDimension().getEnabledTypes(), TO_CLOUD_TESTING_TYPE_IDS));

    androidMatrix.setOrientations(
      Lists.transform(cloudTestConfiguration.getOrientationDimension().getEnabledTypes(), TO_CLOUD_TESTING_TYPE_IDS));

    testMatrix.setEnvironmentMatrix(new EnvironmentMatrix().setAndroidMatrix(androidMatrix));

    TestMatrix triggeredTestMatrix = null;
    try {
      triggeredTestMatrix =
        CloudAuthenticator.getInstance().getTest().projects().testMatrices().create(cloudProjectId, testMatrix).execute();
    } catch (Exception e) {
      String exceptionMessage = e.getMessage();
      String backendMessageHeader = "\"message\" : \"";
      int indexOfBackendMessage = exceptionMessage.indexOf(backendMessageHeader);
      if (indexOfBackendMessage != -1) {
        int startOfBackendMessageText = indexOfBackendMessage + backendMessageHeader.length();
        final String message = exceptionMessage.substring(startOfBackendMessageText, exceptionMessage.indexOf("\",", startOfBackendMessageText));
        if (message.contains("is not registered for Firebase Test Lab")) {
          String urlPrefix = "Please visit: ";
          int urlPrefixIndex = message.indexOf(urlPrefix);
          if (urlPrefixIndex != -1) {
            int urlIndex = urlPrefixIndex + urlPrefix.length();
            String url = message.substring(urlIndex);
            final String userMessage = "<html>" + message.substring(0, urlPrefixIndex) + "<br>" +
                                       message.substring(urlPrefixIndex, urlIndex) + "<a href='" + url + ANDROID_STUDIO_URL_FLAG + "'>" +
                                       url + "</a></html>";
            SwingUtilities.invokeLater(
              () -> Messages.showDialog(userMessage, "Project not registered", new String[]{Messages.OK_BUTTON}, 0, null));
            return null;
          }
        }
      }
      CloudTestingUtils.showErrorMessage(null, "Error triggering a matrix test", "Failed to trigger a firebase matrix execution!\n" +
                                                                                 "Exception while triggering a matrix execution\n\n" +
                                                                                 exceptionMessage);
    }
    return triggeredTestMatrix;
  }

  private static boolean usesPhysicalDevice(CloudConfigurationImpl cloudTestConfiguration) {
    for (CloudTestingType enabledDevice : cloudTestConfiguration.getDeviceDimension().getEnabledTypes()) {
      if (((DeviceDimension.Device) enabledDevice).isPhysical()) {
        return true;
      }
    }
    return false;
  }
}
