load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "directaccess-client",
    srcs = ["src"],
    iml_files = ["directaccess-client.iml"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "//prebuilts/studio/intellij-sdk:studio-sdk",
        "//tools/studio/google/cloud/tools/google-login-plugin:google-login-as[module]",
        "//tools/studio/google/cloud/testing/firebase-testing[module]",
        "//tools/studio/google/cloud/testing/firebase-testing/lib:google-api-services-testing-v1-revsnapshot-1.20.0",
    ],
)
