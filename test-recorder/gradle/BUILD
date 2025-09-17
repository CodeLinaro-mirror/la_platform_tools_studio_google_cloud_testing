load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "test-recorder.gradle",
    srcs = ["src"],
    iml_files = ["test-recorder.gradle.iml"],
    resources = ["resources"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "//tools/adt/idea/project-system:intellij.android.projectSystem",
        "//tools/adt/idea/project-system-gradle:intellij.android.projectSystem.gradle",
        "//tools/studio/google/cloud/testing/test-recorder",
        "//tools/adt/idea/gradle-dsl:intellij.android.gradle.dsl",
        "//tools/analytics-library/tracker:analytics-tracker",
        "//tools/adt/idea/analytics",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/base/sdk-common:studio.android.sdktools.sdk-common",
        "//tools/adt/idea/android:intellij.android.core",
        "//tools/adt/idea/android-common:intellij.android.common",
        "//tools/adt/idea/gradle-dsl-android:intellij.android.gradle.dsl.android",
    ],
)
