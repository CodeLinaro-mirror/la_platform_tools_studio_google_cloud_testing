load("//tools/base/bazel:bazel.bzl", "iml_module")

iml_module(
    name = "test-recorder",
    srcs = ["src"],
    resources = ["resources"],
    tags = ["managed"],
    test_resources = ["test-resources"],
    test_srcs = ["testSrc"],
    test_tags = ["manual"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "//tools/adt/idea/android[module]",
        "//tools/adt/idea/android-test-framework[module, test]",
        "//tools:idea.platform-impl_and_others[module]",
        "//tools/idea/java/execution/openapi:execution-openapi[module]",
        "//tools/idea/.idea/libraries:Velocity",
        "//tools:idea.java-impl[module]",
        "//tools/idea/java/openapi[module]",
        "//tools/idea/.idea/libraries:Groovy-Eclipse-Batch",
        "//tools:idea.compiler-impl_and_others[module]",
        "//tools/idea/.idea/libraries:commons-io-2.4",
        "//tools/studio/google/cloud/testing/test-recorder/lib:uiautomatorviewer",
        "//tools/studio/google/cloud/testing/test-recorder/lib:swt",
        "//tools/analytics-library/tracker:analytics-tracker[module]",
        "//tools/analytics-library/protos:analytics-protos[module]",
        "//tools/adt/idea/android-plugin[module, test]",
    ],
)
