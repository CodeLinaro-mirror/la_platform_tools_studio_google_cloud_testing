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
package com.google.gct.testrecorder.util;

import static com.android.ide.common.repository.GoogleMavenArtifactId.ANDROIDX_ESPRESSO_CONTRIB;
import static com.android.ide.common.repository.GoogleMavenArtifactId.ANDROIDX_ESPRESSO_CORE;
import static com.android.ide.common.repository.GoogleMavenArtifactId.ANDROIDX_JUNIT;
import static com.android.ide.common.repository.GoogleMavenArtifactId.ANDROIDX_TEST_RULES;
import static com.android.ide.common.repository.GoogleMavenArtifactId.SUPPORT_DESIGN;
import static com.android.ide.common.repository.GoogleMavenArtifactId.ESPRESSO_CONTRIB;
import static com.android.ide.common.repository.GoogleMavenArtifactId.ESPRESSO_CORE;
import static com.android.ide.common.repository.GoogleMavenArtifactId.SUPPORT_RECYCLERVIEW_V7;
import static com.android.ide.common.repository.GoogleMavenArtifactId.SUPPORT_ANNOTATIONS;
import static com.android.ide.common.repository.GoogleMavenArtifactId.SUPPORT_V4;
import static com.android.ide.common.repository.GoogleMavenArtifactId.TEST_RULES;
import static com.android.tools.idea.gradle.dsl.api.dependencies.CommonConfigurationNames.ANDROID_TEST_IMPLEMENTATION;
import static com.android.tools.idea.gradle.dsl.api.ext.GradlePropertyModel.STRING_TYPE;
import static com.android.tools.idea.projectsystem.ProjectSystemUtil.getProjectSystem;
import static com.google.wireless.android.sdk.stats.GradleSyncStats.Trigger.TRIGGER_ESPRESSO_SETUP;

import com.android.ide.common.gradle.Version;
import com.android.ide.common.repository.GoogleMavenArtifactId;
import com.android.ide.common.repository.GradleCoordinate;
import com.android.tools.analytics.UsageTracker;
import com.android.tools.analytics.UsageTrackerUtils;
import com.android.tools.idea.gradle.dependencies.DependenciesHelper;
import com.android.tools.idea.gradle.dependencies.ExactDependencyMatcher;
import com.android.tools.idea.gradle.dsl.api.GradleBuildModel;
import com.android.tools.idea.gradle.dsl.api.PluginModel;
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel;
import com.android.tools.idea.gradle.dsl.api.android.AndroidModel;
import com.android.tools.idea.gradle.dsl.api.dependencies.ArtifactDependencyModel;
import com.android.tools.idea.gradle.dsl.api.dependencies.ArtifactDependencySpec;
import com.android.tools.idea.gradle.dsl.api.ext.GradlePropertyModel;
import com.android.tools.idea.gradle.repositories.RepositoryUrlManager;
import com.android.tools.idea.projectsystem.AndroidModuleSystem;
import com.android.tools.idea.projectsystem.DependencyScopeType;
import com.android.tools.idea.projectsystem.GradleToken;
import com.android.tools.idea.projectsystem.ProjectSystemSyncManager;
import com.android.tools.idea.projectsystem.ProjectSystemUtil;
import com.android.tools.idea.projectsystem.gradle.GradleProjectSystem;
import com.google.common.collect.ImmutableList;
import com.google.gct.testrecorder.event.ElementAction;
import com.google.gct.testrecorder.event.TestRecorderEvent;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventCategory;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventKind;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageDialogBuilder;
import java.util.List;
import javax.swing.JPanel;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class EspressoSetupGradleToken implements EspressoSetupToken<GradleProjectSystem>, GradleToken {
  @Override
  public boolean ensureSetup(
    @NotNull GradleProjectSystem projectSystem,
    @NotNull Module testClassModule,
    @NotNull AndroidFacet facet,
    @NotNull JPanel rootPanel,
    @NotNull List<ElementAction> elementActions
  ) {
    return new SetupEnsurer(projectSystem, testClassModule, facet, rootPanel, elementActions).setup();
  }

  @Override
  public boolean supportsKotlin(@NotNull GradleProjectSystem projectSystem, @NotNull Module testClassModule) {
    GradleBuildModel gradleBuildModel = GradleBuildModel.get(testClassModule);
    if (gradleBuildModel == null) {
      return false;
    }
    List<String> pluginNames = PluginModel.extractNames(gradleBuildModel.plugins());
    return pluginNames.contains("kotlin-android") || pluginNames.contains("org.jetbrains.kotlin.android");
  }

  @Override
  public boolean isNativeProject(@NotNull GradleProjectSystem projectSystem, @NotNull Module module) {
    // TODO(b/294274926): Do not use DSL models to detect Gradle native projects.
    return GradleBuildModel.get(module) != null
           && GradleBuildModel.get(module).android().externalNativeBuild().cmake().version().getValueType()
              != GradlePropertyModel.ValueType.NONE;
  }

  private static class SetupEnsurer {
    GradleProjectSystem projectSystem;
    Module testClassModule;
    AndroidFacet facet;
    JPanel rootPanel;
    List<ElementAction> elementActions;

    private boolean myNeedsContribDependency = false;
    private boolean myUsesAnyEspressoDependency = false;
    private boolean myUsesAndroidxDependency = false;
    private boolean myUsesGrantPermissionRule = false;
    private Version myMinEspressoCoreVersion = MIN_ESPRESSO_CORE_VERSION_FOR_LARGE_TEST;
    private final Version myMinAndroidxEspressoCoreVersion = MIN_ANDROIDX_ESPRESSO_CORE_VERSION;
    private Version myMinRulesVersion = MIN_RULES_VERSION_FOR_LARGE_TEST;
    private final Version myMinAndroidxRulesVersion = MIN_ANDROIDX_RULES_VERSION;
    private final Version myMinAndroidxExtJunitVersion = MIN_ANDROIDX_EXT_JUNIT_VERSION;

    SetupEnsurer(
      GradleProjectSystem projectSystem,
      Module testClassModule,
      AndroidFacet facet,
      JPanel rootPanel,
      List<ElementAction> elementActions
    ) {
      this.projectSystem = projectSystem;
      this.testClassModule = testClassModule;
      this.rootPanel = rootPanel;
      this.facet = facet;
      this.elementActions = elementActions;
      initializeDependencyRequirements();
    }

    private boolean setup() {
      Project project = projectSystem.getProject();
      ProjectBuildModel projectModel = ProjectBuildModel.get(project);
      GradleBuildModel gradleBuildModel = projectModel.getModuleBuildModel(testClassModule);
      if (gradleBuildModel != null) {
        AndroidModel androidModel = gradleBuildModel.android();
        // androidModel will be null when the Gradle experimental plugin is used and it's not possible to update the instrumentation runner.
        // TODO: Provide an appropriate error message or some alternative way to update instrumentation runner when the Gradle experimental
        // plugin is used.
        if (!hasAllRequiredEspressoDependencies(androidModel, ProjectSystemUtil.getModuleSystem(testClassModule))) {
          UsageTracker.log(UsageTrackerUtils.withProjectId(
            AndroidStudioEvent.newBuilder()
              .setCategory(EventCategory.TEST_RECORDER)
              .setKind(EventKind.TEST_RECORDER_MISSING_ESPRESSO_DEPENDENCIES),
            project));

          if (MessageDialogBuilder.yesNo("Missing or obsolete Espresso dependencies",
                                         "Some dependencies for running Espresso tests are missing or obsolete.\n" +
                                         "Would you like to automatically add/update Espresso dependencies for this app?\n" +
                                         "To complete the set up, Gradle might ask you to install the missing libraries.\n" +
                                         "Please click on the corresponding link(s) to install them.").icon(null).ask(rootPanel)) {
            setupEspresso(projectModel, gradleBuildModel);
          }
        }
      }
      return myUsesAndroidxDependency;
    }

    private boolean hasAllRequiredEspressoDependencies(@NotNull AndroidModel androidModel,
                                                       @NotNull AndroidModuleSystem androidModuleSystem) {
      initializeDependencyRequirements();
      // TODO: To improve performance, consider doing these checks in a single pass.
      return hasUptodateEspressoCoreDependency(androidModuleSystem)
             && hasUptodateRulesDependency(androidModuleSystem)
             && (!myNeedsContribDependency || hasUptodateEspressoContribDependency(androidModuleSystem))
             && (!myUsesAndroidxDependency || !myUsesGrantPermissionRule || hasUptodateAndroidxRulesDependency(androidModuleSystem))
             && hasSetInstrumentationRunner(androidModel);
    }

    private void initializeDependencyRequirements() {
      myNeedsContribDependency = false;
      myUsesAnyEspressoDependency = false;
      myUsesAndroidxDependency = false;
      myUsesGrantPermissionRule = false;
      myMinEspressoCoreVersion = MIN_ESPRESSO_CORE_VERSION_FOR_LARGE_TEST;
      myMinRulesVersion = MIN_RULES_VERSION_FOR_LARGE_TEST;

      for (ElementAction action : elementActions) {
        if (action instanceof TestRecorderEvent) {
          TestRecorderEvent testRecorderEvent = (TestRecorderEvent)action;
          if (testRecorderEvent.getElementRecyclerViewChildPosition() != -1) {
            myNeedsContribDependency = true;
          }
          else if (testRecorderEvent.isPermissionsRequest()) {
            myUsesGrantPermissionRule = true;
            myMinEspressoCoreVersion = MIN_ESPRESSO_CORE_VERSION_FOR_GRANT_PERMISSION_RULE;
            myMinRulesVersion = MIN_RULES_VERSION_FOR_GRANT_PERMISSION_RULE;
          }
        }
      }
    }

    private boolean hasUptodateEspressoCoreDependency(@NotNull AndroidModuleSystem androidModuleSystem) {
      return hasUptodateDependency(androidModuleSystem, ESPRESSO_CORE, ANDROIDX_ESPRESSO_CORE, myMinEspressoCoreVersion,
                                   myMinAndroidxEspressoCoreVersion);
    }

    private boolean hasUptodateRulesDependency(@NotNull AndroidModuleSystem androidModuleSystem) {
      return hasUptodateDependency(androidModuleSystem, TEST_RULES, ANDROIDX_JUNIT, myMinRulesVersion,
                                   myMinAndroidxExtJunitVersion);
    }

    private boolean hasUptodateEspressoContribDependency(@NotNull AndroidModuleSystem androidModuleSystem) {
      return hasUptodateDependency(androidModuleSystem, ESPRESSO_CONTRIB, ANDROIDX_ESPRESSO_CONTRIB, myMinEspressoCoreVersion,
                                   myMinAndroidxEspressoCoreVersion);
    }

    private boolean hasUptodateAndroidxRulesDependency(@NotNull AndroidModuleSystem androidModuleSystem) {
      return hasUptodateDependency(androidModuleSystem, null, ANDROIDX_TEST_RULES, myMinRulesVersion, myMinAndroidxRulesVersion);
    }

    private static boolean hasSetInstrumentationRunner(@NotNull AndroidModel androidModel) {
      String testInstrumentationRunner = androidModel.defaultConfig().testInstrumentationRunner().getValue(STRING_TYPE);
      return testInstrumentationRunner != null && !testInstrumentationRunner.isEmpty();
    }

    /**
     * This logic assumes that existing ATSL dependencies are consistent, i.e., either all are androidx or all are not androidx.
     * Otherwise, it is an app build configuration error and Espresso Test Recorder dependency handling is undefined.
     */
    private boolean hasUptodateDependency(
      @NotNull AndroidModuleSystem androidModuleSystem,
      @Nullable GoogleMavenArtifactId artifact,
      @Nullable GoogleMavenArtifactId androidxArtifact,
      @NotNull Version minVersion,
      @NotNull Version androidxMinVersion
    ) {
      Version dependencyVersion = getDependencyVersion(androidModuleSystem, artifact);
      if (dependencyVersion != null) {
        myUsesAnyEspressoDependency = true;
        return dependencyVersion.compareTo(minVersion) >= 0;
      }

      Version androidxDependencyVersion = getDependencyVersion(androidModuleSystem, androidxArtifact);
      if (androidxDependencyVersion != null) {
        myUsesAnyEspressoDependency = true;
        myUsesAndroidxDependency = true;
        return androidxDependencyVersion.compareTo(androidxMinVersion) >= 0;
      }

      return false;
    }

    @Nullable
    private static Version getDependencyVersion(@NotNull AndroidModuleSystem androidModuleSystem,
                                                @Nullable GoogleMavenArtifactId artifact) {
      if (artifact == null) return null;
      GradleCoordinate resolvedDependency = androidModuleSystem.getResolvedDependency(artifact, DependencyScopeType.ANDROID_TEST);
      if (resolvedDependency == null) return null;
      return resolvedDependency.getLowerBoundVersion();
    }

    private void setupEspresso(@NotNull ProjectBuildModel projectModel, @NotNull GradleBuildModel gradleBuildModel) {
      if (!myUsesAnyEspressoDependency) {
        // Establish whether to use androidx Espresso dependencies based on other present dependencies.
        AndroidModuleSystem moduleSystem = ProjectSystemUtil.getModuleSystem(facet.getModule());
        for (GoogleMavenArtifactId artifactId : GoogleMavenArtifactId.values()) {
          if (artifactId.getMavenGroupId().startsWith("androidx.") || artifactId.getMavenGroupId().equals("com.google.android.material")) {
            GradleCoordinate coordinate = moduleSystem.getResolvedDependency(artifactId);
            if (coordinate != null) {
              myUsesAndroidxDependency = true;
              break;
            }
          }
        }
      }

      new Task.Modal(projectSystem.getProject(), "Setting up Espresso", false) {
        @Override
        public void run(@NotNull ProgressIndicator indicator) {
          indicator.setText("Adding Espresso dependencies");
          indicator.setIndeterminate(true);

          // TODO: This is a trick to make sure the progress dialog is shown. Otherwise, the action is too quick for the dialog to show up,
          // but long enough to see a noticeable delay.
          try {
            Thread.sleep(500);
          }
          catch (Exception e) {
            //  ignore
          }

          WriteCommandAction.runWriteCommandAction(myProject, () -> {
            addOrUpdateEspressoCoreDependency();
            addOrUpdateRulesDependency();
            if (myNeedsContribDependency) {
              addOrUpdateEspressoContribDependency();
            }
            if (myUsesAndroidxDependency && myUsesGrantPermissionRule) {
              addOrUpdateAndroidxRulesDependency();
            }

            AndroidModel androidModel = gradleBuildModel.android();
            if (androidModel != null && !hasSetInstrumentationRunner(androidModel)) {
              androidModel.defaultConfig().testInstrumentationRunner()
                .setValue(myUsesAndroidxDependency ? ANDROIDX_TEST_INSTRUMENTATION_RUNNER : TEST_INSTRUMENTATION_RUNNER);
            }

            projectModel.applyChanges();

            if (myProject != null) {
              getProjectSystem(myProject).getSyncManager().requestSyncProject(new ProjectSystemSyncManager.SyncReason(TRIGGER_ESPRESSO_SETUP));
            }
          });
        }

        private void addOrUpdateEspressoCoreDependency() {
          boolean hasUpdatedEspressoCoreVersion = false;
          for (ArtifactDependencyModel artifact : gradleBuildModel.dependencies().artifacts()) {
            if (ESPRESSO_CORE_CUSTOM_GROUP_NAME.equals(artifact.group().toString())
                && ESPRESSO_CORE_CUSTOM_ARTIFACT_NAME.equals(artifact.name().forceString())) {
              // Remove the obsolete custom Espresso dependency.
              gradleBuildModel.dependencies().remove(artifact);
            }
            else if (isMatchingArtifact(artifact, getEspressoArtifactId())) {
              artifact.version().setValue(getEspressoArtifactUpdateVersion());
              hasUpdatedEspressoCoreVersion = true;
            }
            else if (isMatchingArtifact(artifact, getEspressoContribArtifactId())) {
              // Update espresso-contrib dependency, if present, to match espresso-core dependency version.
              artifact.version().setValue(getEspressoArtifactUpdateVersion());
            }
            else if (isMatchingArtifact(artifact, getTestRulesArtifactId())) {
              // Update rules dependency, if present, to match espresso-core dependency version.
              artifact.version().setValue(getTestRulesArtifactUpdateVersion());
            }
            else if (isMatchingArtifact(artifact, ANDROIDX_TEST_RULES)) {
              // Update androidx rules dependency, if present, to match espresso-core dependency version.
              artifact.version().setValue(getAndroidxRulesVersion());
            }
          }
          if (!hasUpdatedEspressoCoreVersion) {
            if (myUsesAndroidxDependency) {
              // No need to add excludes for more recent (e.g., androidx) dependency versions.
              addArtifact(createArtifactDependencySpec(ANDROIDX_ESPRESSO_CORE, getAndroidxEspressoCoreVersion()),
                          ImmutableList.of());
            }
            else {
              addArtifact(createArtifactDependencySpec(ESPRESSO_CORE, getEspressoCoreVersion()),
                          ESPRESSO_CORE_EXCLUDES);
            }
          }
        }

        private void addOrUpdateRulesDependency() {
          for (ArtifactDependencyModel artifact : gradleBuildModel.dependencies().artifacts()) {
            if (isMatchingArtifact(artifact, getTestRulesArtifactId())) {
              artifact.version().setValue(getTestRulesArtifactUpdateVersion());
              return;
            }
          }
          addArtifact(createArtifactDependencySpec(getTestRulesArtifactId(), getTestRulesArtifactUpdateVersion()),
                      ImmutableList.of());
        }

        private void addOrUpdateAndroidxRulesDependency() {
          for (ArtifactDependencyModel artifact : gradleBuildModel.dependencies().artifacts()) {
            if (isMatchingArtifact(artifact, ANDROIDX_TEST_RULES)) {
              artifact.version().setValue(getAndroidxRulesVersion());
              return;
            }
          }
          addArtifact(createArtifactDependencySpec(ANDROIDX_TEST_RULES, getAndroidxRulesVersion()),
                      ImmutableList.of());
        }

        private void addOrUpdateEspressoContribDependency() {
          for (ArtifactDependencyModel artifact : gradleBuildModel.dependencies().artifacts()) {
            if (isMatchingArtifact(artifact, getEspressoContribArtifactId())) {
              artifact.version().setValue(getEspressoArtifactUpdateVersion());
              return;
            }
          }
          if (myUsesAndroidxDependency) {
            // No need to add excludes for more recent (e.g., androidx) dependency versions.
            ArtifactDependencySpec spec = createArtifactDependencySpec(ANDROIDX_ESPRESSO_CONTRIB,
                                                                       getAndroidxEspressoCoreVersion());
            addArtifact(createArtifactDependencySpec(ANDROIDX_ESPRESSO_CONTRIB, getAndroidxEspressoCoreVersion()),
                        ImmutableList.of());
          }
          else {
            addArtifact(createArtifactDependencySpec(ESPRESSO_CONTRIB, getEspressoCoreVersion()),
                        ESPRESSO_CONTRIB_EXCLUDES);
          }
        }

        private boolean isMatchingArtifact(ArtifactDependencyModel artifact, GoogleMavenArtifactId artifactId) {
          return artifactId.getMavenGroupId().equals(artifact.group().toString())
                 && artifactId.getMavenArtifactId().equals(artifact.name().forceString());
        }

        private void addArtifact(@NotNull ArtifactDependencySpec dependency,
                                 @NotNull List<ArtifactDependencySpec> excludes) {
          String compactNotation = dependency.compactNotation();
          DependenciesHelper.withModel(projectModel).addDependency(ANDROID_TEST_IMPLEMENTATION,
                                                                   compactNotation,
                                                                   excludes,
                                                                   gradleBuildModel,
                                                                   new ExactDependencyMatcher(ANDROID_TEST_IMPLEMENTATION, compactNotation),
                                                                   null);
        }
      }.queue();
    }


    private GoogleMavenArtifactId getEspressoArtifactId() {
      return myUsesAndroidxDependency ? ANDROIDX_ESPRESSO_CORE : ESPRESSO_CORE;
    }

    private String getEspressoArtifactUpdateVersion() {
      return myUsesAndroidxDependency ? getAndroidxEspressoCoreVersion() : getEspressoCoreVersion();
    }

    private GoogleMavenArtifactId getEspressoContribArtifactId() {
      return myUsesAndroidxDependency ? ANDROIDX_ESPRESSO_CONTRIB : ESPRESSO_CONTRIB;
    }

    private GoogleMavenArtifactId getTestRulesArtifactId() {
      return myUsesAndroidxDependency ? ANDROIDX_JUNIT : TEST_RULES;
    }

    private String getTestRulesArtifactUpdateVersion() {
      return myUsesAndroidxDependency ? getAndroidxExtJunitVersion() : getRulesVersion();
    }

    private static String getEspressoCoreVersion() {
      if (espressoCoreVersion == null) {
        espressoCoreVersion = getLatestDependencyVersion(ESPRESSO_CORE, "3.0.2");
      }
      return espressoCoreVersion;
    }

    private static String getAndroidxEspressoCoreVersion() {
      if (androidxEspressoCoreVersion == null) {
        androidxEspressoCoreVersion = getLatestDependencyVersion(ANDROIDX_ESPRESSO_CORE, "3.5.0");
      }
      return androidxEspressoCoreVersion;
    }

    private static String getRulesVersion() {
      if (rulesVersion == null) {
        rulesVersion = getLatestDependencyVersion(TEST_RULES, "1.0.2");
      }
      return rulesVersion;
    }

    private static String getAndroidxRulesVersion() {
      if (androidxRulesVersion == null) {
        androidxRulesVersion = getLatestDependencyVersion(ANDROIDX_TEST_RULES, "1.5.0");
      }
      return androidxRulesVersion;
    }

    private static String getAndroidxExtJunitVersion() {
      if (androidxExtJunitVersion == null) {
        androidxExtJunitVersion = getLatestDependencyVersion(ANDROIDX_JUNIT, "1.1.5");
      }
      return androidxExtJunitVersion;
    }

    private static String getLatestDependencyVersion(GoogleMavenArtifactId artifactId, String fallbackVersion) {
      com.android.ide.common.gradle.Component latestComponent = RepositoryUrlManager.get().getArtifactComponent(artifactId, true);
      if (latestComponent != null) {
        return latestComponent.getVersion().toString();
      }

      //Fallback to some default version.
      return fallbackVersion;
    }

    public static final ImmutableList<ArtifactDependencySpec> ESPRESSO_CORE_EXCLUDES =
      ImmutableList.of(createArtifactDependencySpec(SUPPORT_ANNOTATIONS, null));

    public static final ImmutableList<ArtifactDependencySpec> ESPRESSO_CONTRIB_EXCLUDES =
      ImmutableList.of(createArtifactDependencySpec(SUPPORT_ANNOTATIONS, null),
                       createArtifactDependencySpec(SUPPORT_V4, null),
                       createArtifactDependencySpec(SUPPORT_DESIGN, null),
                       createArtifactDependencySpec(SUPPORT_RECYCLERVIEW_V7, null));

    @NotNull
    private static ArtifactDependencySpec createArtifactDependencySpec(@NotNull GoogleMavenArtifactId artifactId,
                                                                       @Nullable String version) {
      return ArtifactDependencySpec.create(artifactId.getMavenArtifactId(), artifactId.getMavenGroupId(), version);
    }

    private static final String ESPRESSO_CORE_CUSTOM_ARTIFACT_NAME = "espresso";
    private static final String ESPRESSO_CORE_CUSTOM_GROUP_NAME = "com.jakewharton.espresso";

    public static final String TEST_INSTRUMENTATION_RUNNER = "android.support.test.runner.AndroidJUnitRunner";

    public static final String ANDROIDX_TEST_INSTRUMENTATION_RUNNER = "androidx.test.runner.AndroidJUnitRunner";

    /**
     * The minimal version of espresso-core in build.gradle that does not require updating for importing LargeTest.
     */
    private static final Version MIN_ESPRESSO_CORE_VERSION_FOR_LARGE_TEST = Version.parse("2.2.2");

    /**
     * The minimal version of rules in build.gradle that does not require updating for importing LargeTest.
     */
    private static final Version MIN_RULES_VERSION_FOR_LARGE_TEST = Version.parse("0.5");

    /**
     * The minimal version of espresso-core in build.gradle that does not require updating for using GrantPermissionRule.
     */
    private static final Version MIN_ESPRESSO_CORE_VERSION_FOR_GRANT_PERMISSION_RULE = Version.parse("3.0.0");

    /**
     * The minimal version of rules in build.gradle that does not require updating for using GrantPermissionRule.
     */
    private static final Version MIN_RULES_VERSION_FOR_GRANT_PERMISSION_RULE = Version.parse("1.0.0");

    /**
     * The minimal version of androidx espresso-core in build.gradle that does not require updating.
     */
    private static final Version MIN_ANDROIDX_ESPRESSO_CORE_VERSION = Version.parse("3.5.0");

    /**
     * The minimal version of androidx rules in build.gradle that does not require updating.
     */
    private static final Version MIN_ANDROIDX_RULES_VERSION = Version.parse("1.5.0");

    /**
     * The minimal version of androidx ext junit in build.gradle that does not require updating.
     */
    private static final Version MIN_ANDROIDX_EXT_JUNIT_VERSION = Version.parse("1.1.5");
    /** Version of espresso-core added/updated in build.gradle, when missing or obsolete. Should be used only via its accessor method. */
    private static String espressoCoreVersion = null;

    /** Version of androidx espresso-core added/updated in build.gradle, when missing or obsolete. Should be used only via its accessor method. */
    private static String androidxEspressoCoreVersion = null;

    /** Version of rules added/updated in build.gradle, when missing or obsolete. Should be used only via its accessor method. */
    private static String rulesVersion = null;

    /** Version of androidx rules added/updated in build.gradle, when missing or obsolete. Should be used only via its accessor method. */
    private static String androidxRulesVersion = null;

    /** Version of androidx ext junit added/updated in build.gradle, when missing or obsolete. Should be used only via its accessor method. */
    private static String androidxExtJunitVersion = null;


  }
}
