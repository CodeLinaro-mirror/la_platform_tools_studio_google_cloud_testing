/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.gct.testing.results;

import static com.intellij.execution.testframework.sm.runner.states.TestStateInfo.Magnitude.NOT_RUN_INDEX;

import com.intellij.execution.testframework.PoolOfTestIcons;
import com.intellij.execution.testframework.TestConsoleProperties;
import com.intellij.execution.testframework.sm.SmRunnerBundle;
import com.intellij.execution.testframework.sm.runner.states.TestStateInfo;
import com.intellij.execution.testframework.sm.runner.ui.SMPoolOfTestIcons;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.SimpleTextAttributes;
import javax.swing.Icon;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class GoogleCloudTestsPresentationUtil {
  @NonNls private static final String NO_NAME_TEST = "<no name>";


  private GoogleCloudTestsPresentationUtil() {
  }

  public static void formatRootNodeWithChildren(final GoogleCloudTestProxy.GoogleCloudRootTestProxy testProxy,
                                                final GoogleCloudTestTreeRenderer renderer) {
    renderer.setIcon(getIcon(testProxy, renderer.getConsoleProperties()));

    final TestStateInfo.Magnitude magnitude = testProxy.getMagnitudeInfo();

    final String text;
    if (magnitude == TestStateInfo.Magnitude.RUNNING_INDEX) {
      text = SmRunnerBundle.message("sm.test.runner.ui.tests.tree.presentation.labels.running.tests");
    } else if (magnitude == TestStateInfo.Magnitude.TERMINATED_INDEX) {
      text = SmRunnerBundle.message("sm.test.runner.ui.tests.tree.presentation.labels.was.terminated");
    } else if (magnitude == TestStateInfo.Magnitude.TIMEOUT_INDEX) {
      text = "Timed out";
    } else if (magnitude == TestStateInfo.Magnitude.INFRASTRUCTURE_FAILURE_INDEX) {
      text = "Infrastructure failure";
    } else if (magnitude == TestStateInfo.Magnitude.TRIGGERING_ERROR_INDEX) {
      text = "Triggering error";
    } else {
      text = SmRunnerBundle.message("sm.test.runner.ui.tests.tree.presentation.labels.test.results");
    }
    renderer.append(text, SimpleTextAttributes.REGULAR_ATTRIBUTES);
  }

  public static void formatRootNodeWithoutChildren(final GoogleCloudTestProxy.GoogleCloudRootTestProxy testProxy,
                                                   final GoogleCloudTestTreeRenderer renderer) {
    final TestStateInfo.Magnitude magnitude = testProxy.getMagnitudeInfo();
    if (magnitude == TestStateInfo.Magnitude.RUNNING_INDEX) {
      renderer.setIcon(getIcon(testProxy, renderer.getConsoleProperties()));
      renderer.append(SmRunnerBundle.message(
          "sm.test.runner.ui.tests.tree.presentation.labels.instantiating.tests"),
                      SimpleTextAttributes.REGULAR_ATTRIBUTES);
    } else if (magnitude == NOT_RUN_INDEX) {
      renderer.setIcon(PoolOfTestIcons.NOT_RAN);
      renderer.append(SmRunnerBundle.message(
          "sm.test.runner.ui.tests.tree.presentation.labels.not.test.results"),
                      SimpleTextAttributes.ERROR_ATTRIBUTES);
    } else if (magnitude == TestStateInfo.Magnitude.TERMINATED_INDEX) {
      renderer.setIcon(PoolOfTestIcons.TERMINATED_ICON);
      renderer.append(SmRunnerBundle.message(
          "sm.test.runner.ui.tests.tree.presentation.labels.was.terminated"),
                      SimpleTextAttributes.REGULAR_ATTRIBUTES);
    } else if (magnitude == TestStateInfo.Magnitude.PASSED_INDEX) {
      renderer.setIcon(PoolOfTestIcons.PASSED_ICON);
      renderer.append(SmRunnerBundle.message(
          "sm.test.runner.ui.tests.tree.presentation.labels.all.tests.passed"),
                      SimpleTextAttributes.REGULAR_ATTRIBUTES);
    }
    else {
      if (!testProxy.getChildren().isEmpty()) {
        // some times test proxy may be updated faster than tests tree
        // so let's process such situation correctly
        formatRootNodeWithChildren(testProxy, renderer);
      }
      else {
        renderer.setIcon(PoolOfTestIcons.NOT_RAN);
        renderer.append(testProxy.isTestsReporterAttached()
                        ? SmRunnerBundle.message("sm.test.runner.ui.tests.tree.presentation.labels.no.tests.were.found")
                        : SmRunnerBundle.message("sm.test.runner.ui.tests.tree.presentation.labels.test.reporter.not.attached"),
                        SimpleTextAttributes.ERROR_ATTRIBUTES);
      }
    }
  }

  public static void formatTestProxy(final GoogleCloudTestProxy testProxy,
                                     final GoogleCloudTestTreeRenderer renderer) {
    renderer.setIcon(getIcon(testProxy, renderer.getConsoleProperties()));
    renderer.append(testProxy.getPresentableName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
  }

  @NotNull
  public static String getPresentableName(final GoogleCloudTestProxy testProxy) {
    final GoogleCloudTestProxy parent = testProxy.getParent();
    final String name = testProxy.getName();

    String presentationCandidate = name;
    if (parent != null) {
      final String parentName = parent.getName();
      if (name.startsWith(parentName)) {
        presentationCandidate = name.substring(parentName.length());

        // remove "." separator
        if (presentationCandidate.startsWith(".")) {
          presentationCandidate = presentationCandidate.substring(1);
        }
      }
    }

    // trim
    presentationCandidate = presentationCandidate.trim();

    // remove extra spaces
    presentationCandidate = presentationCandidate.replaceAll("\\s+", " ");

    if (StringUtil.isEmpty(presentationCandidate)) {
      return NO_NAME_TEST;
    }

    return presentationCandidate;
  }

  @NotNull
  public static String getPresentableNameTrimmedOnly(@NotNull GoogleCloudTestProxy testProxy) {
    String name = testProxy.getName();
    if (name != null) {
      name = name.trim();
    }
    if (name == null || name.isEmpty()) {
      name = NO_NAME_TEST;
    }
    return name;
  }

  @Nullable
  private static Icon getIcon(final GoogleCloudTestProxy testProxy,
                              final TestConsoleProperties consoleProperties) {
    final TestStateInfo.Magnitude magnitude = testProxy.getMagnitudeInfo();

    final boolean hasErrors = testProxy.hasErrors();

    switch (magnitude) {
      case ERROR_INDEX:
        return PoolOfTestIcons.ERROR_ICON;
      case FAILED_INDEX:
        return hasErrors ? SMPoolOfTestIcons.FAILED_E_ICON : PoolOfTestIcons.FAILED_ICON;
      case IGNORED_INDEX:
        return hasErrors ? SMPoolOfTestIcons.IGNORED_E_ICON : PoolOfTestIcons.IGNORED_ICON;
      case NOT_RUN_INDEX:
        return PoolOfTestIcons.NOT_RAN;
      case COMPLETE_INDEX:
      case PASSED_INDEX:
        return hasErrors ? SMPoolOfTestIcons.PASSED_E_ICON : PoolOfTestIcons.PASSED_ICON;
      case SCHEDULED_INDEX:
        return testProxy.isActiveScheduled() ? CloudMatrixProgressAnimator.getCurrentFrame() : AllIcons.Process.Step_passive;
      case TIMEOUT_INDEX:
        return AllIcons.Debugger.KillProcess;
      case INFRASTRUCTURE_FAILURE_INDEX:
        return AllIcons.Debugger.Db_exception_breakpoint;
      case TRIGGERING_ERROR_INDEX:
        return AllIcons.Debugger.Db_invalid_breakpoint;
      case RUNNING_INDEX:
        if (consoleProperties.isPaused()) {
          return hasErrors ? SMPoolOfTestIcons.PAUSED_E_ICON : AllIcons.RunConfigurations.TestPaused;
        }
        else {
          return hasErrors ? SMPoolOfTestIcons.RUNNING_E_ICON : SMPoolOfTestIcons.RUNNING_ICON;
        }
      case SKIPPED_INDEX:
        return hasErrors ? SMPoolOfTestIcons.SKIPPED_E_ICON : PoolOfTestIcons.SKIPPED_ICON;
      case TERMINATED_INDEX:
        return hasErrors ? SMPoolOfTestIcons.TERMINATED_E_ICON : PoolOfTestIcons.TERMINATED_ICON;
    }
    return null;
  }
}
