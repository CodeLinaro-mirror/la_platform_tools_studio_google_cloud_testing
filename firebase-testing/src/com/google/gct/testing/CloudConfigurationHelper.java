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
package com.google.gct.testing;

import static com.google.gct.testing.CloudConfigurationHelperUtils.getSingleApkOrParentFolderForRunConfiguration;

import com.android.annotations.VisibleForTesting;
import com.android.ddmlib.IDevice;
import com.android.sdklib.AndroidVersion;
import com.android.tools.analytics.UsageTracker;
import com.android.tools.idea.model.AndroidModel;
import com.android.tools.idea.testartifacts.instrumented.AndroidTestRunConfiguration;
import com.google.api.client.util.Maps;
import com.google.api.services.cloudresourcemanager.v3.CloudResourceManager;
import com.google.api.services.cloudresourcemanager.v3.model.SearchProjectsResponse;
import com.google.api.services.testing.model.TestMatrix;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.gct.testing.android.CloudConfiguration;
import com.google.gct.testing.android.CloudMatrixTestRunningState;
import com.google.gct.testing.config.GoogleCloudTestingDeveloperConfigurable;
import com.google.gct.testing.config.GoogleCloudTestingDeveloperSettings;
import com.google.gct.testing.dimension.ApiDimension;
import com.google.gct.testing.dimension.CloudTestingType;
import com.google.gct.testing.dimension.DeviceDimension;
import com.google.gct.testing.dimension.LanguageDimension;
import com.google.gct.testing.dimension.OrientationDimension;
import com.google.gct.testing.launcher.CloudAuthenticator;
import com.google.gct.testing.launcher.CloudTestsLauncher;
import com.google.gct.testing.results.GoogleCloudTestConsoleProperties;
import com.google.gct.testing.results.GoogleCloudTestListener;
import com.google.gct.testing.results.GoogleCloudTestResultsConnectionUtil;
import com.google.gct.testing.results.GoogleCloudTestingResultParser;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventCategory;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventKind;
import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import icons.StudioIcons;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.swing.Icon;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class CloudConfigurationHelper {
  private static final String TEST_RUN_ID_PREFIX = "GoogleCloudTest:";
  private static final Map<String, CloudConfigurationImpl> testRunIdToCloudConfiguration = new HashMap<>();
  private static final Map<String, CloudResultsAdapter> testRunIdToCloudResultsAdapter = new HashMap<>();
  // Do not use MultiMap to ensure proper reuse of serial numbers (IP:port).
  private static final Map<String, String> serialNumberToConfigurationInstance = Maps.newHashMap();

  public static final Icon DEFAULT_ICON = StudioIcons.Shell.Filetree.ANDROID_FILE;

  public static final Function<CloudConfiguration, CloudConfigurationImpl> CLONE_CONFIGURATIONS =
    configuration -> ((CloudConfigurationImpl) configuration).clone();

  public static final Function<CloudConfiguration, CloudConfigurationImpl> CAST_CONFIGURATIONS =
    configuration -> (CloudConfigurationImpl) configuration;


  private CloudConfigurationHelper() { } // Not instantiable.

  public static Map<String, List<? extends CloudTestingType>> getAllDimensionTypes() {
    Map<String, List<? extends CloudTestingType>> dimensionTypes = new HashMap<>();
    dimensionTypes.put(DeviceDimension.DISPLAY_NAME, DeviceDimension.getFullDomain());
    dimensionTypes.put(ApiDimension.DISPLAY_NAME, ApiDimension.getFullDomain());
    dimensionTypes.put(LanguageDimension.DISPLAY_NAME, LanguageDimension.getFullDomain());
    dimensionTypes.put(OrientationDimension.DISPLAY_NAME, OrientationDimension.getFullDomain());
    return dimensionTypes;
  }

  @NotNull
  public static List<String> getCloudProjects() {
    List<String> cloudProjects = Lists.newArrayList();

    try {
      SearchProjectsResponse response = listProjects(null);
      while (response != null && response.getProjects() != null) {
        for (com.google.api.services.cloudresourcemanager.v3.model.Project pantheonProject : response.getProjects()) {
          if (!Strings.isNullOrEmpty(pantheonProject.getProjectId())
              // Ignore any projects scheduled for deletion.
              && Strings.isNullOrEmpty(pantheonProject.getDeleteTime())) {
            cloudProjects.add(pantheonProject.getProjectId());
          }
        }
        String nextPageToken = response.getNextPageToken();
        if (nextPageToken == null || nextPageToken.isEmpty()) {
          break;
        }
        response = listProjects(nextPageToken);
      }
    } catch(Exception ignored) {
    }

    Collections.sort(cloudProjects);

    return cloudProjects;
  }

  private static SearchProjectsResponse listProjects(String pageToken) throws IOException {
    CloudResourceManager.Projects.Search listProjects =
      CloudAuthenticator.getInstance().getCloudResourceManager().projects().search().setPageSize(1000);
    if (pageToken != null) {
      listProjects.setPageToken(pageToken);
    }
    return listProjects.execute();
  }

  @NotNull
  public static List<? extends CloudConfiguration> getCloudConfigurations(@NotNull AndroidFacet facet, @NotNull final CloudConfiguration.Kind configurationKind) {
    try {
      CloudAuthenticator.getInstance().prepareCredential();
    } catch(Exception e) {
      return Lists.newArrayList();
    }

    return Lists.newArrayList(Iterables.concat(deserializeConfigurations(getPersistentConfigurations(facet, configurationKind), true, facet),
                                               getDefaultConfigurations(facet, configurationKind)));
  }

  @NotNull
  public static List<CloudPersistentConfiguration> getPersistentConfigurations(
    @NotNull AndroidFacet facet, @NotNull final CloudConfiguration.Kind configurationKind) {
    return Lists.newArrayList(Iterables.filter(
      CloudCustomPersistentConfigurations.getInstance(facet.getModule()).getState().myCloudPersistentConfigurations,
      configuration -> configuration != null && configuration.kind == configurationKind));
  }

  @VisibleForTesting
  public static List<? extends CloudConfiguration> getDefaultConfigurations(AndroidFacet facet, CloudConfiguration.Kind kind) {
    if (kind == CloudConfiguration.Kind.SINGLE_DEVICE) {
      CloudConfigurationImpl defaultConfiguration =
        new CloudConfigurationImpl(CloudConfigurationImpl.DEFAULT_DEVICE_CONFIGURATION_ID, "", CloudConfiguration.Kind.SINGLE_DEVICE, StudioIcons.Avd.DEVICE_MOBILE, facet);
      defaultConfiguration.apiDimension.enableDefault();
      ImmutableList<CloudTestingType> enabledApis = defaultConfiguration.apiDimension.getEnabledTypes();
      if (enabledApis.isEmpty()) {
        return ImmutableList.of();
      }
      defaultConfiguration.deviceDimension.enableDefault(enabledApis.get(0).getId());
      defaultConfiguration.languageDimension.enableDefault();
      defaultConfiguration.orientationDimension.enableDefault();

      ImmutableList<CloudTestingType> enabledDevices = defaultConfiguration.deviceDimension.getEnabledTypes();
      if (enabledDevices.isEmpty() || defaultConfiguration.languageDimension.getEnabledTypes().isEmpty()
          || defaultConfiguration.orientationDimension.getEnabledTypes().isEmpty()) {
        return ImmutableList.of();
      }

      defaultConfiguration.setName(enabledDevices.get(0).getConfigurationDialogDisplayName() + " API " + enabledApis.get(0).getId());
      defaultConfiguration.setNonEditable();
      return ImmutableList.of(defaultConfiguration);
    }

    CloudConfigurationImpl defaultConfiguration =
      new CloudConfigurationImpl(CloudConfigurationImpl.DEFAULT_MATRIX_CONFIGURATION_ID, "Sample configuration", CloudConfiguration.Kind.MATRIX, StudioIcons.Avd.DEVICE_MOBILE, facet);
    defaultConfiguration.apiDimension.enableTopN(ApiDimension.getFullDomain(), 3);
    defaultConfiguration.deviceDimension.enableCompatibleTopN(DeviceDimension.getFullDomain(), defaultConfiguration.apiDimension.getEnabledTypes(), 2, 1);
    defaultConfiguration.languageDimension.enableDefault();
    defaultConfiguration.orientationDimension.enableAll();
    defaultConfiguration.setNonEditable();

    CloudConfigurationImpl defaultSparkConfiguration =
      new CloudConfigurationImpl(CloudConfigurationImpl.DEFAULT_FREE_TIER_MATRIX_CONFIGURATION_ID, "Sample Spark configuration", CloudConfiguration.Kind.MATRIX, StudioIcons.Avd.DEVICE_MOBILE, facet);
    defaultSparkConfiguration.apiDimension.enableTopN(ApiDimension.getFullDomain(), 2);
    defaultSparkConfiguration.deviceDimension.enableCompatibleTopN(DeviceDimension.getFullDomain(), defaultSparkConfiguration.apiDimension.getEnabledTypes(), 1, 1);
    defaultSparkConfiguration.languageDimension.enableDefault();
    defaultSparkConfiguration.orientationDimension.enableDefault();
    defaultSparkConfiguration.setNonEditable();

    return ImmutableList.of(defaultSparkConfiguration, defaultConfiguration);
  }

  @NotNull
  public static List<? extends CloudConfiguration> getAllCloudConfigurations(@NotNull AndroidFacet facet) {
    try {
      CloudAuthenticator.getInstance().prepareCredential();
    } catch(Exception e) {
      return Lists.newArrayList();
    }

    List<CloudPersistentConfiguration> cloudPersistentConfigurations =
      CloudCustomPersistentConfigurations.getInstance(facet.getModule()).getState().myCloudPersistentConfigurations;
    return Lists.newArrayList(Iterables.concat(deserializeConfigurations(cloudPersistentConfigurations, true, facet),
                                               getDefaultConfigurations(facet, CloudConfiguration.Kind.MATRIX),
                                               getDefaultConfigurations(facet, CloudConfiguration.Kind.SINGLE_DEVICE)));
  }

  @Nullable
  public static CloudConfiguration openMatrixConfigurationDialog(@NotNull AndroidFacet currentFacet,
                                                                 @Nullable CloudConfiguration selectedConfiguration,
                                                                 @NotNull CloudConfiguration.Kind configurationKind) {

    final CloudConfiguration.Kind selectedConfigurationKind =
      selectedConfiguration == null ? configurationKind : ((CloudConfigurationImpl)selectedConfiguration).getKind();

    Module currentModule = currentFacet.getModule();
    List<? extends CloudConfiguration> testingConfigurations = getCloudConfigurations(currentFacet, selectedConfigurationKind);

    List<CloudConfigurationImpl> castDefaultConfigurations =
      Lists.newArrayList(Iterables.transform(Iterables.filter(testingConfigurations,
                                                              (Predicate<CloudConfiguration>)testingConfiguration -> !testingConfiguration.isEditable()), CAST_CONFIGURATIONS));

    List<CloudConfigurationImpl> copyCustomConfigurations =
      Lists.newArrayList(Iterables.transform(Iterables.filter(testingConfigurations,
                                                              (Predicate<CloudConfiguration>)testingConfiguration -> testingConfiguration.isEditable()), CLONE_CONFIGURATIONS));

    CloudConfigurationChooserDialog dialog =
      new CloudConfigurationChooserDialog(currentModule, copyCustomConfigurations, castDefaultConfigurations,
                                          (CloudConfigurationImpl) selectedConfiguration, selectedConfigurationKind);

    dialog.show();
    if (dialog.isOK()) {
      CloudCustomPersistentConfigurations persistentConfigurations = CloudCustomPersistentConfigurations.getInstance(currentModule);
      CloudPersistentState customState = persistentConfigurations.getState();

      // Keep all un-edited configurations (i.e., configurations of other kinds).
      customState.myCloudPersistentConfigurations =
        Lists.newArrayList(Iterables.filter(customState.myCloudPersistentConfigurations,
                                            configuration -> configuration != null && configuration.kind != selectedConfigurationKind));

      // Persist the edited configurations.
      customState.myCloudPersistentConfigurations.addAll(Lists.newArrayList(
        Iterables.transform(copyCustomConfigurations, CloudConfigurationImpl::getPersistentConfiguration)));

      persistentConfigurations.loadState(customState);

      return dialog.getSelectedConfiguration();
    }
    return null;
  }

  @Nullable
  private static String getDefaultBucketName(@NotNull Project project, @NotNull String cloudProjectId) {
    try {
      return CloudAuthenticator.getInstance().getToolresults().projects().initializeSettings(cloudProjectId).execute().getDefaultBucket();
    } catch (Exception e) {
      CloudTestingUtils
        .showErrorMessage(project, "Firebase test configuration is invalid",
                          "Failed to get the default bucket name! Please select a project you are authorized to use.\n"
                          + "Exception while getting the default bucket name\n\n" + e.getMessage());
      return null;
    }
  }

  private static String getUniquePathPrefix() {
    final String characters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    final int suffixLength = 4;
    final Random randomGenerator = new Random();

    StringBuilder suffix = new StringBuilder(suffixLength);
    for (int i = 0; i < suffixLength; i++) {
      suffix.append(characters.charAt(randomGenerator.nextInt(characters.length())));
    }

    return "as-build_" + new SimpleDateFormat("yyyy-mm-dd_HH:mm:ss.SSS").format(new Date()) + "_" + suffix;
  }

  @Nullable
  public static Icon getCloudDeviceIcon() {
    return CloudTestingUtils.CLOUD_DEVICE_ICON;
  }

  @Nullable
  public static String getCloudDeviceConfiguration(IDevice device) {
    String encodedConfigurationInstance = device instanceof GhostCloudDevice
                                          ? ((GhostCloudDevice)device).getEncodedConfigurationInstance()
                                          : serialNumberToConfigurationInstance.get(device.getSerialNumber());
    if (encodedConfigurationInstance != null) {
      return ConfigurationInstance.parseFromEncodedString(encodedConfigurationInstance).getResultsViewerDisplayString();
    }
    return null;
  }

  public static ExecutionResult executeCloudMatrixTests(
    int selectedConfigurationId, String cloudProjectId, CloudMatrixTestRunningState runningState, Executor executor) {
    UsageTracker.log(AndroidStudioEvent.newBuilder()
                                     .setCategory(EventCategory.CLOUD_TESTING)
                                     .setKind(EventKind.CLOUD_TESTING_RUN_TEST_MATRIX));

    Project project = runningState.getFacet().getModule().getProject();

    String bucketName = getDefaultBucketName(project, cloudProjectId);

    if (bucketName == null) {
      // Cloud project is invalid, nothing to do.
      return null;
    }

    CloudConfigurationImpl cloudConfiguration = CloudTestingUtils.getConfigurationById(selectedConfigurationId, runningState.getFacet());

    if (cloudConfiguration == null || cloudConfiguration.getKind() != CloudConfiguration.Kind.MATRIX) {
      // Should handle only matrix configurations.
      return null;
    }

    AndroidTestRunConfiguration testRunConfiguration = runningState.getConfiguration();
    GoogleCloudTestConsoleProperties properties = new GoogleCloudTestConsoleProperties(testRunConfiguration, executor);
    CloudMatrixExecutionCancellator matrixExecutionCancellator = new CloudMatrixExecutionCancellator();
    ConsoleView console = GoogleCloudTestResultsConnectionUtil.createAndAttachConsole(
      "Firebase Testing", runningState.getProcessHandler(), properties, matrixExecutionCancellator);
    Disposer.register(project, console);

    GoogleCloudTestingResultParser
      cloudResultParser = new GoogleCloudTestingResultParser("Firebase Test Run", new GoogleCloudTestListener(runningState));

    List<String> expectedConfigurationInstances =
      cloudConfiguration.computeConfigurationInstances(ConfigurationInstance.DISPLAY_NAME_DELIMITER);
    for (String configurationInstance : expectedConfigurationInstances) {
      cloudResultParser.getTestRunListener().testConfigurationScheduled(configurationInstance);
    }
    GoogleCloudTestingDeveloperConfigurable.GoogleCloudTestingDeveloperState googleCloudTestingDeveloperState =
      GoogleCloudTestingDeveloperSettings.getInstance(project).getState();
    if (!googleCloudTestingDeveloperState.shouldUseFakeBucket) {
      performTestsInCloud(
        cloudConfiguration, cloudProjectId, bucketName, getUniquePathPrefix(), runningState, cloudResultParser, matrixExecutionCancellator);
    }
    else {
      String testRunId = TEST_RUN_ID_PREFIX + googleCloudTestingDeveloperState.fakeBucketName + System.currentTimeMillis();
      CloudResultsAdapter cloudResultsAdapter =
        new CloudResultsAdapter(cloudProjectId, googleCloudTestingDeveloperState.fakeBucketName, "prefix", runningState.getProcessHandler(),
                                cloudResultParser, expectedConfigurationInstances, testRunId, null, null);
      addCloudConfiguration(testRunId, cloudConfiguration);
      addCloudResultsAdapter(testRunId, cloudResultsAdapter);
      cloudResultsAdapter.startPolling();
    }
    return new DefaultExecutionResult(console, runningState.getProcessHandler());
  }

  private static void performTestsInCloud(final CloudConfigurationImpl cloudTestingConfiguration, final String cloudProjectId,
                                          final String bucketName, final String uniquePrefix,
                                          final CloudMatrixTestRunningState runningState,
                                          final GoogleCloudTestingResultParser cloudResultParser,
                                          final CloudMatrixExecutionCancellator matrixExecutionCancellator) {
    if (cloudTestingConfiguration != null && cloudTestingConfiguration.getDeviceConfigurationCount() > 0) {
      final List<String> expectedConfigurationInstances =
        cloudTestingConfiguration.computeConfigurationInstances(ConfigurationInstance.DISPLAY_NAME_DELIMITER);
      int minApiVersion = cloudTestingConfiguration
        .apiDimension
        .getEnabledTypes()
        .stream()
        .filter(it -> it instanceof ApiDimension.ApiLevel)
        .map(it -> (ApiDimension.ApiLevel)it)
        .map(ApiDimension.ApiLevel::getApiVersion)
        .min(Integer::compareTo)
        .orElse(1);
      IDevice ghostMinApiVersionDevice =
        new GhostCloudDevice("*not an id*", "*not a module id*", new AndroidVersion(minApiVersion), "", "");
      new Thread(new Runnable() {
        @Override
        public void run() {
          final String bucketPath = bucketName + "/" + uniquePrefix;
          AndroidTestRunConfiguration testRunConfiguration = runningState.getConfiguration();

          if (matrixExecutionCancellator.isCancelled()) {
            return;
          }
          runningState.getProcessHandler().notifyTextAvailable(
            prepareProgressString("Using Cloud Storage Bucket location " + bucketPath + " ...", ""), ProcessOutputTypes.STDOUT);

          if (matrixExecutionCancellator.isCancelled()) {
            return;
          }

          AndroidModel androidModel = AndroidModel.get(runningState.getFacet());
          Module module = runningState.getFacet().getModule();
          if (androidModel == null) {
            CloudTestingUtils.showErrorMessage(module.getProject(), "Error uploading APKs",
                                               "Your project is not an idea android project!\n");
            return;
          }

          File appApk = getSingleApkOrParentFolderForRunConfiguration(module, testRunConfiguration, false, ghostMinApiVersionDevice);
          if (appApk == null) {
            CloudTestingUtils.showErrorMessage(module.getProject(), "Error finding app APK",
                                               "Could not find your app APK!\n");
            return;
          }

          File testApk = getSingleApkOrParentFolderForRunConfiguration(module, testRunConfiguration, true, ghostMinApiVersionDevice);
          if (testApk == null) {
            CloudTestingUtils.showErrorMessage(module.getProject(), "Error finding test APK",
                                               "Could not find your test APK!\n");
            return;
          }

          runningState.getProcessHandler().notifyTextAvailable(prepareProgressString("Uploading app APK ...", ""),
                                                               ProcessOutputTypes.STDOUT);
          String appApkName = CloudTestsLauncher.uploadFile(bucketName, uniquePrefix, appApk).getName();

          if (matrixExecutionCancellator.isCancelled()) {
            return;
          }
          runningState.getProcessHandler().notifyTextAvailable(prepareProgressString("Uploading test APK ...", ""),
                                                               ProcessOutputTypes.STDOUT);
          String testApkName = CloudTestsLauncher.uploadFile(bucketName, uniquePrefix, testApk).getName();

          if (matrixExecutionCancellator.isCancelled()) {
            return;
          }
          runningState.getProcessHandler().notifyTextAvailable(prepareProgressString("Submitting tests to Firebase Test Lab ...", ""),
                                                               ProcessOutputTypes.STDOUT);
          String testSpecification = CloudTestingUtils.prepareTestSpecification(testRunConfiguration);

          TestMatrix testMatrix = CloudTestsLauncher
            .triggerTestApi(cloudProjectId, getBucketGcsPath(bucketPath), getApkGcsPath(bucketName, appApkName),
                            getApkGcsPath(bucketName, testApkName), testSpecification, testRunConfiguration.INSTRUMENTATION_RUNNER_CLASS,
                            cloudTestingConfiguration);

          if (testMatrix != null) {
            runningState.getProcessHandler().notifyTextAvailable(prepareProgressString("Validating APKs ...", "\n\n"),
                                                                 ProcessOutputTypes.STDOUT);
          }
          matrixExecutionCancellator.setCloudProjectId(cloudProjectId);
          matrixExecutionCancellator.setTestMatrixId(testMatrix == null ? null : testMatrix.getTestMatrixId());
          String testRunId = TEST_RUN_ID_PREFIX + bucketPath;
          CloudResultsAdapter cloudResultsAdapter =
            new CloudResultsAdapter(cloudProjectId, bucketName, uniquePrefix, runningState.getProcessHandler(), cloudResultParser,
                                    expectedConfigurationInstances, testRunId, testMatrix, matrixExecutionCancellator);
          addCloudConfiguration(testRunId, cloudTestingConfiguration);
          addCloudResultsAdapter(testRunId, cloudResultsAdapter);
          if (testMatrix == null) {
            // If the test matrix is null, there was a triggering error.
            cloudResultsAdapter.terminateResultProcessing();
          } else {
            cloudResultsAdapter.startPolling();
          }
        }

        private String prepareProgressString(String progressMessage, String suffix) {
          return CloudTestingUtils.shouldShowProgressTimestamps()
                 ? progressMessage + "\t" + System.currentTimeMillis() + "\n" + suffix
                 : progressMessage + "\n" + suffix;
        }
      }).start();
    }
  }

  private static String getBucketGcsPath(String bucketPath) {
    return "gs://" + bucketPath;
  }

  private static String getApkGcsPath(String bucketName, String apkName) {
    return "gs://" + bucketName + "/" + apkName;
  }

  private static void addCloudResultsAdapter(String testRunId, CloudResultsAdapter cloudResultsAdapter) {
    if (testRunIdToCloudResultsAdapter.get(testRunId) != null) {
      throw new IllegalStateException("Cannot add more than one firebase results adapter for test run id: " + testRunId);
    }
    testRunIdToCloudResultsAdapter.put(testRunId, cloudResultsAdapter);
  }

  private static void addCloudConfiguration(String testRunId, CloudConfigurationImpl cloudConfiguration) {
    if (testRunIdToCloudConfiguration.get(testRunId) != null) {
      throw new IllegalStateException("Cannot add more than one firebase configuration for test run id: " + testRunId);
    }
    testRunIdToCloudConfiguration.put(testRunId, cloudConfiguration);
  }

  public static CloudConfigurationImpl getSelectedCloudConfiguration(String testRunId) {
    return testRunIdToCloudConfiguration.get(testRunId);
  }

  public static CloudResultsAdapter getCloudResultsAdapter(String testRunId) {
    return testRunIdToCloudResultsAdapter.get(testRunId);
  }

  public static List<CloudConfigurationImpl> deserializeConfigurations(
    final List<CloudPersistentConfiguration> persistentConfigurations, boolean isEditable, AndroidFacet facet) {
    List<CloudConfigurationImpl> googleCloudTestingConfigurations = new LinkedList<>();
    for (CloudPersistentConfiguration persistentConfiguration : persistentConfigurations) {
      Icon icon = getIcon(persistentConfiguration.name, isEditable);
      CloudConfigurationImpl configuration =
        new CloudConfigurationImpl(persistentConfiguration.id, persistentConfiguration.name, persistentConfiguration.kind, icon, facet);
      configuration.deviceDimension.enable(DeviceDimension.getFullDomain(), persistentConfiguration.devices);
      configuration.apiDimension.enable(ApiDimension.getFullDomain(), persistentConfiguration.apiLevels);
      configuration.languageDimension.enable(LanguageDimension.getFullDomain(), persistentConfiguration.languages);
      configuration.orientationDimension.enable(OrientationDimension.getFullDomain(), persistentConfiguration.orientations);
      if (!isEditable) {
        configuration.setNonEditable();
      }
      googleCloudTestingConfigurations.add(configuration);
    }
    return googleCloudTestingConfigurations;
  }

  private static Icon getIcon(String configurationName, boolean isEditable) {
    if (isEditable) {
      return StudioIcons.Shell.Filetree.ANDROID_FILE;
    }
    if (configurationName.equals("All Available")) {
      return StudioIcons.Avd.DEVICE_MOBILE;
    }
    return StudioIcons.Avd.DEVICE_PHONE;
  }
}
