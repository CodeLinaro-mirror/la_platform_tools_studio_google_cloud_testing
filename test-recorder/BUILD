load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:maven.bzl", "maven_repository")

# managed by go/iml_to_build
iml_module(
    name = "test-recorder",
    srcs = ["src"],
    iml_files = ["test-recorder.iml"],
    lint_baseline = "lint_baseline.xml",
    resources = ["resources"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/adt/idea/android-common:intellij.android.common[module]",
        "//tools/adt/idea/android:intellij.android.core[module]",
        "//tools/studio/google/cloud/testing/test-recorder/lib:uiautomatorviewer",
        "//tools/studio/google/cloud/testing/test-recorder/lib:swt",
        "//tools/analytics-library/tracker:analytics-tracker[module]",
        "//tools/adt/idea/android-adb:intellij.android.adb[module]",
        "//tools/adt/idea/android-adb-ui:intellij.android.adb.ui[module]",
        "//tools/adt/idea/project-system:intellij.android.projectSystem[module]",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/adt/idea/deploy:intellij.android.deploy[module]",
        "//tools/adt/idea/artwork:intellij.android.artwork[module]",
        "//tools/adt/idea/lint:intellij.lint[module]",
        "//tools/adt/idea/gradle-dsl:intellij.android.gradle.dsl[module]",
        "//tools/base/lint:studio.android.sdktools.lint-api[module]",
        "//tools/adt/idea/execution/common:intellij.android.execution.common[module]",
        "//tools/adt/idea/render-resources:intellij.android.render-resources[module]",
        "//tools/base/deploy/deployer:studio.android.sdktools.deployer[module]",
        "//tools/adt/idea/analytics[module]",
        "//tools/adt/idea/project-system-gradle:intellij.android.projectSystem.gradle[module]",
    ],
)

maven_repository(
    name = "test_deps",
    # keep sorted: for buildifier
    artifacts = [
        "@maven//:androidx.appcompat.appcompat_1.6.1",
        "@maven//:androidx.core.core-ktx_1.8.0",
        "@maven//:androidx.core.core-ktx_1.9.0",
        "@maven//:androidx.test.espresso.espresso-contrib_3.5.1",
        "@maven//:androidx.test.espresso.espresso-core_3.5.1",
        "@maven//:androidx.test.ext.junit_1.1.5",
        "@maven//:androidx.test.rules_1.3.0",
        "@maven//:com.android.support.constraint.constraint-layout_1.0.2",
        "@maven//:com.android.support.support-compat_28.0.0",
        "@maven//:com.android.support.support-v4_28.0.0",
        "@maven//:com.android.support.test.espresso.espresso-contrib_3.0.2",
        "@maven//:com.android.support.test.espresso.espresso-core_3.0.2",
        "@maven//:com.android.support.test.rules_1.0.2",
        "@maven//:com.google.guava.guava_19.0",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core_1.6.1",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "test-recorder.tests",
    iml_files = ["test-recorder.tests.iml"],
    test_class = "com.google.gct.testrecorder.ETRTestSuite",
    test_data = [
        ":test_deps",
        "//prebuilts/studio/jdk:jdk_1_8",
        "//prebuilts/studio/jdk/jdk11",
        "//prebuilts/studio/sdk:build-tools/33.0.1",
        "//prebuilts/studio/sdk:build-tools/latest",
        "//prebuilts/studio/sdk:platform-tools",
        "//prebuilts/studio/sdk:platforms/latest",
        "//tools/adt/idea/android/testData",
        "//tools/base/build-system:android_gradle_plugin.zip",  # Please do not add old versions of AGP here. Use the old-agp-tests module instead.
        "//tools/base/build-system:android_gradle_plugin_runtime_dependencies",
        "//tools/base/build-system:gradle-distrib",
        "//tools/base/build-system/integration-test:kotlin_gradle_plugin_prebuilts",
    ],
    test_resources = ["testSrc/test-resources"],
    test_srcs = ["testSrc"],
    test_timeout = "long",
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk[test]",
        "@intellij//:com.intellij.java[test]",
        "//tools/adt/idea/.idea/libraries:truth[test]",
        "//tools/adt/idea/.idea/libraries:mockito[test]",
        "//tools/adt/idea/android-plugin:intellij.android.plugin[module, test]",
        "//tools/adt/idea/android-test-framework:intellij.android.testFramework[module, test]",
        "//tools/studio/google/cloud/testing/test-recorder[module, test]",
        "//tools/analytics-library/testing:android.sdktools.analytics-testing[module, test]",
        "//tools/adt/idea/android:intellij.android.core[module, test]",
        "//tools/adt/idea/execution/common:intellij.android.execution.common[module, test]",
        "//tools/base/testutils:studio.android.sdktools.testutils[module, test]",
        "//tools/adt/idea/execution/common:intellij.android.execution.common.tests[module, test]",
        "//tools/adt/idea/android-common:intellij.android.common[module, test]",
        "//tools/adt/idea/project-system-gradle:intellij.android.projectSystem.gradle[module, test]",
        "//tools/adt/idea/adt-testutils:intellij.android.adt.testutils[module, test]",
        "//tools/adt/idea/render-resources:intellij.android.render-resources[module, test]",
    ],
)
