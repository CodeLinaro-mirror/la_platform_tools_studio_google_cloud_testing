load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "firebase-testing",
    srcs = ["src"],
    iml_files = ["firebase-testing.iml"],
    lint_baseline = "lint_baseline.xml",
    test_class = "com.google.gct.testing.FirebaseTestingTestSuite",
    test_data = [
        "//prebuilts/studio/sdk:platforms/latest",
        "//tools/adt/idea/android/annotations",
        "//tools/adt/idea/android/testData",
    ],
    test_srcs = ["testSrc"],
    test_tags = [
        "block-network",
        "no_test_windows",
    ],
    visibility = ["//visibility:public"],
    exports = ["//prebuilts/tools/common/m2:google_api_services_testing"],
    # do not sort: must match IML order
    deps = [
        "//prebuilts/studio/intellij-sdk:studio-sdk",
        "//prebuilts/tools/common/m2:google_api_services_testing",
        "//prebuilts/tools/common/m2:JSch",
        "//prebuilts/studio/intellij-sdk:studio-sdk-plugin-Groovy",
        "//tools/adt/idea/android-common:intellij.android.common[module]",
        "//tools/adt/idea/android:intellij.android.core[module]",
        "//tools/studio/google/cloud/tools/core-plugin:google-cloud-tools-core-as[module]",
        "//tools/studio/google/cloud/tools/google-login-plugin:google-login-as[module]",
        "//tools/adt/idea/.idea/libraries:tightvnc",
        "//tools/analytics-library/tracker:analytics-tracker[module]",
        "//tools/adt/idea/android-test-framework:intellij.android.testFramework[module, test]",
        "//tools/adt/idea/.idea/libraries:mockito[test]",
        "//tools/base/testutils:studio.android.sdktools.testutils[module, test]",
        "//tools/adt/idea/android-plugin:intellij.android.plugin[module, test]",
        "//tools/adt/idea/artwork:intellij.android.artwork[module]",
        "//tools/adt/idea/adt-testutils:intellij.android.adt.testutils[module, test]",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/adt/idea/project-system:intellij.android.projectSystem[module]",
        "//tools/adt/idea/android-adb:intellij.android.adb[module]",
        "//tools/adt/idea/render-resources:intellij.android.render-resources[module]",
        "//tools/adt/idea/execution/common:intellij.android.execution.common[module]",
        "//tools/adt/idea/.idea/libraries:google-api-client",
        "//tools/adt/idea/.idea/libraries:cloudresourcemanager",
    ],
)

load("//tools/base/bazel:jvm_import.bzl", "jvm_import")
