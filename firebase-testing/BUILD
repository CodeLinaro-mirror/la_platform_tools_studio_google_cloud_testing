load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build [unb]
iml_module(
    name = "firebase-testing",
    srcs = ["src"],
    bundle_data = ["//tools/studio/google/cloud/testing/firebase-testing/lib:licenses"],
    bundled_deps = [
        "//tools/studio/google/cloud/testing/firebase-testing/lib:unb.google-api-services-storage-v1-rev1-1.18.0-rc",
        "//tools/studio/google/cloud/testing/firebase-testing/lib:unb.google-api-services-testing-v1-revsnapshot-1.20.0",
        "//tools/studio/google/cloud/testing/firebase-testing/lib:unb.google-api-services-toolresults-v1beta3-rev20151013-1.20.0",
    ],
    iml_files = ["firebase-testing.iml"],
    lint_baseline = "lint_baseline.xml",
    project = "unb",
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
    # do not sort: must match IML order
    deps = [
        "//prebuilts/studio/intellij-sdk:studio-sdk",
        "//prebuilts/studio/intellij-sdk:studio-sdk-plugin-Groovy",
        "//tools/adt/idea/.idea/libraries:unb.Guava",
        "//tools/adt/idea/android-common:intellij.android.common[module]",
        "//tools/adt/idea/android:intellij.android.core[module]",
        "//tools/studio/google/cloud/testing/firebase-testing/lib:unb.google-api-services-storage-v1-rev1-1.18.0-rc",
        "//tools/studio/google/cloud/testing/firebase-testing/lib:unb.google-api-services-testing-v1-revsnapshot-1.20.0",
        "//tools/studio/google/cloud/tools/core-plugin:google-cloud-tools-core-as[module]",
        "//tools/studio/google/cloud/tools/google-login-plugin:google-login-as[module]",
        "//tools/adt/idea/.idea/libraries:unb.tightvnc",
        "//tools/studio/google/cloud/testing/firebase-testing/lib:unb.google-api-services-toolresults-v1beta3-rev20151013-1.20.0",
        "//tools/analytics-library/tracker:analytics-tracker[module]",
        "//tools/adt/idea/android-test-framework:intellij.android.testFramework[module, test]",
        "//tools/adt/idea/.idea/libraries:unb.mockito[test]",
        "//tools/base/testutils:studio.android.sdktools.testutils[module, test]",
        "//tools/adt/idea/android-plugin:intellij.android.plugin[module, test]",
        "//tools/adt/idea/artwork:intellij.android.artwork[module]",
        "//tools/adt/idea/adt-testutils:intellij.android.adt.testutils[module, test]",
        "//tools/adt/idea/.idea/libraries:unb.studio-analytics-proto",
        "//tools/adt/idea/.idea/libraries:unb.protobuf",
        "//tools/adt/idea/project-system:intellij.android.projectSystem[module]",
    ],
)
