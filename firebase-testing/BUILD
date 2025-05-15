load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "firebase-testing",
    srcs = ["src"],
    iml_files = ["firebase-testing.iml"],
    lint_baseline = "lint_baseline.xml",
    resources = ["resources"],
    test_class = "com.google.gct.testing.FirebaseTestingTestSuite",
    test_data = [
        "//prebuilts/studio/sdk:platforms/latest",
        "//tools/adt/idea/android/annotations",
        "//tools/adt/idea/android/testData",
    ],
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/android-test-framework:intellij.android.testFramework",
        "//tools/adt/idea/.idea/libraries:mockito",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/android-plugin:intellij.android.plugin",
        "//tools/adt/idea/adt-testutils:intellij.android.adt.testutils",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/.idea/libraries:mockito-kotlin",
    ],
    test_srcs = ["testSrc"],
    test_tags = [
        "block-network",
        "noci:studio-win",
    ],
    visibility = ["//visibility:public"],
    exports = ["//prebuilts/tools/common/m2:google_api_services_testing"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//prebuilts/tools/common/m2:google_api_services_testing",
        "//prebuilts/tools/common/m2:JSch",
        "@intellij//:org.intellij.groovy",
        "//tools/adt/idea/android-common:intellij.android.common",
        "//tools/adt/idea/android:intellij.android.core",
        "//tools/studio/google/cloud/tools/google-login-plugin:google-login-as",
        "//tools/analytics-library/tracker:analytics-tracker",
        "//tools/adt/idea/artwork:intellij.android.artwork",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/adt/idea/project-system:intellij.android.projectSystem",
        "//tools/adt/idea/android-adb:intellij.android.adb",
        "//tools/adt/idea/render-resources:intellij.android.render-resources",
        "//tools/adt/idea/execution/common:intellij.android.execution.common",
        "//tools/adt/idea/.idea/libraries:google-api-client",
        "//tools/adt/idea/.idea/libraries:cloudresourcemanager",
        "//tools/adt/idea/analytics",
        "//tools/vendor/google/firebase",
        "//tools/base/flags:studio.android.sdktools.flags",
    ],
)
