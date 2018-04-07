load("//tools/base/bazel:bazel.bzl", "iml_module")

iml_module(
    name = "test-recorder",
    srcs = ["src"],
    bundle_data = ["//tools/studio/google/cloud/testing/test-recorder/lib:licenses"],
    iml_files = ["test-recorder.iml"],
    resources = ["resources"],
    tags = ["managed"],
    test_resources = ["test-resources"],
    test_srcs = ["testSrc"],
    test_tags = ["manual"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    runtime_deps = [
        "//tools/idea/xml/relaxng:intellij.relaxng",
        "//tools/idea/platform/tasks-platform-impl:intellij.platform.tasks.impl",
        "//tools/idea/json:intellij.json",
        "//tools/idea/platform/credential-store:intellij.platform.credentialStore",
        "//tools/idea/java/compiler/instrumentation-util-8:intellij.java.compiler.instrumentationUtil.java8",
        "//tools/idea/plugins/IntelliLang/intellilang-jps-plugin:intellij.java.langInjection.jps",
        "//tools/adt/idea/adt-branding:intellij.android.adt.branding",
    ],
    # do not sort: must match IML order
    deps = [
        "//tools/adt/idea/android:intellij.android[module]",
        "//tools/adt/idea/android-test-framework:intellij.android.testFramework[module, test]",
        "//tools/idea/xml/dom-openapi:intellij.xml.dom[module]",
        "//tools/idea/java/execution/openapi:intellij.java.execution[module]",
        "//tools/idea/.idea/libraries:Velocity",
        "//tools/idea:intellij.java.impl[module]",
        "//tools/idea/java/openapi:intellij.java[module]",
        "//tools/idea:intellij.platform.jps.build_and_others[module]",
        "//tools/idea/platform/xdebugger-impl:intellij.platform.debugger.impl[module]",
        "//tools/idea/.idea/libraries:commons-io",
        "//tools/studio/google/cloud/testing/test-recorder/lib:uiautomatorviewer",
        "//tools/studio/google/cloud/testing/test-recorder/lib:swt",
        "//tools/analytics-library/tracker:analytics-tracker[module]",
        "//tools/analytics-library/protos:analytics-protos[module]",
        "//tools/idea/platform/testFramework:intellij.platform.testFramework[module, test]",
        "//tools/adt/idea/android-plugin:intellij.android.plugin[module, test]",
        "//tools/adt/idea/project-system[module]",
    ],
)
