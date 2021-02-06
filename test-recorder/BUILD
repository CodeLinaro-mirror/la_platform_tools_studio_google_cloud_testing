load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "test-recorder",
    srcs = ["src"],
    bundled_deps = [
        "//tools/studio/google/cloud/testing/test-recorder/lib:uiautomatorviewer",
        "//tools/studio/google/cloud/testing/test-recorder/lib:swt",
    ],
    iml_files = ["test-recorder.iml"],
    lint_baseline = "lint_baseline.xml",
    resources = ["resources"],
    test_resources = ["test-resources"],
    test_srcs = ["testSrc"],
    test_tags = ["manual"],
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
    ],
)
