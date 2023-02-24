load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "test-recorder",
    srcs = ["src"],
    iml_files = ["test-recorder.iml"],
    lint_baseline = "lint_baseline.xml",
    resources = ["resources"],
    test_class = "com.google.gct.testrecorder.ETRTestSuite",
    test_data = [
        "//prebuilts/studio/sdk:platforms/latest",
        "//tools/adt/idea/android/testData",
    ],
    test_resources = ["test-resources"],
    test_srcs = ["testSrc"],
    test_timeout = "long",
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "//prebuilts/studio/intellij-sdk:studio-sdk",
        "//tools/adt/idea/android-common:intellij.android.common[module]",
        "//tools/adt/idea/android:intellij.android.core[module]",
        "//tools/adt/idea/android-test-framework:intellij.android.testFramework[module, test]",
        "//tools/studio/google/cloud/testing/test-recorder/lib:uiautomatorviewer",
        "//tools/studio/google/cloud/testing/test-recorder/lib:swt",
        "//tools/analytics-library/tracker:analytics-tracker[module]",
        "//tools/adt/idea/android-plugin:intellij.android.plugin[module, test]",
        "//tools/adt/idea/project-system:intellij.android.projectSystem[module]",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/adt/idea/deploy:intellij.android.deploy[module]",
        "//tools/adt/idea/artwork:intellij.android.artwork[module]",
        "//tools/adt/idea/lint:intellij.lint[module]",
        "//tools/adt/idea/gradle-dsl:intellij.android.gradle.dsl[module]",
        "//tools/base/lint:studio.android.sdktools.lint-api[module]",
        "//tools/adt/idea/execution/common:intellij.android.execution.common[module]",
        "//tools/base/testutils:studio.android.sdktools.testutils[module, test]",
        "//tools/adt/idea/adt-testutils:intellij.android.adt.testutils[module, test]",
        "//tools/adt/idea/.idea/libraries:mockito[test]",
    ],
)
