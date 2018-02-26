load("//tools/base/bazel:bazel.bzl", "iml_module")

iml_module(
    name = "test-recorder",
    srcs = ["src"],
    bundle_data = ["//tools/studio/google/cloud/testing/test-recorder/lib:licenses"],
    iml_files = ["test-recorder.iml"],
    resources = ["resources"],
    tags = ["managed"],
    test_resources = ["test-resources"],
    test_runtime_deps = ["//tools/vendor/intellij/cidr:doxygen"],
    test_srcs = ["testSrc"],
    test_tags = ["manual"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    runtime_deps = [
        "//tools/idea/platform/credential-store",
        "//tools/idea/platform/tasks-platform-impl",
        "//tools/idea/json",
        "//tools/idea/xml/relaxng",
        "//tools/idea/java/compiler/instrumentation-util-8",
        "//tools/adt/idea/adt-branding",
    ],
    # do not sort: must match IML order
    deps = [
        "//tools/adt/idea/android[module]",
        "//tools/adt/idea/android-test-framework[module, test]",
        "//tools/idea/xml/dom-openapi[module]",
        "//tools/idea/java/execution/openapi:execution-openapi[module]",
        "//tools/idea/.idea/libraries:Velocity",
        "//tools/idea:java-impl[module]",
        "//tools/idea/java/openapi[module]",
        "//tools/idea:jps-builders_and_others[module]",
        "//tools/idea/platform/xdebugger-impl[module]",
        "//tools/idea/.idea/libraries:commons-io",
        "//tools/studio/google/cloud/testing/test-recorder/lib:uiautomatorviewer",
        "//tools/studio/google/cloud/testing/test-recorder/lib:swt",
        "//tools/analytics-library/tracker:analytics-tracker[module]",
        "//tools/analytics-library/protos:analytics-protos[module]",
        "//tools/idea/platform/testFramework[module, test]",
        "//tools/adt/idea/android-plugin[module, test]",
        "//tools/adt/idea/project-system[module]",
    ],
)
