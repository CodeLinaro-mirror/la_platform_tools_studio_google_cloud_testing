load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "test-recorder.gradle",
    srcs = ["src"],
    iml_files = ["test-recorder.gradle.iml"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "//tools/adt/idea/project-system:intellij.android.projectSystem[module]",
        "//tools/adt/idea/project-system-gradle:intellij.android.projectSystem.gradle[module]",
        "//tools/studio/google/cloud/testing/test-recorder[module]",
        "//tools/adt/idea/gradle-dsl:intellij.android.gradle.dsl[module]",
        "//tools/analytics-library/tracker:analytics-tracker[module]",
        "//tools/adt/idea/analytics[module]",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/base/sdk-common:studio.android.sdktools.sdk-common[module]",
        "//tools/adt/idea/android:intellij.android.core[module]",
        "//tools/adt/idea/android-common:intellij.android.common[module]",
    ],
)
