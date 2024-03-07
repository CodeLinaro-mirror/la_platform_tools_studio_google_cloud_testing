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
package com.google.gct.testing;

import com.android.tools.analytics.UsageTracker;
import com.android.tools.analytics.UsageTrackerUtils;
import com.android.tools.idea.testartifacts.instrumented.AndroidTestRunConfiguration;
import com.google.gct.testing.android.CloudConfiguration;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.BrowserHyperlinkListener;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.ui.UIUtil;
import icons.StudioIcons;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CloudTestingUtils {

  public static final String PRICING_LINK = "https://firebase.google.com/pricing";
  public static final String CREATE_FIREBASE_PROJECT_LINK = "https://console.firebase.google.com";

  public static final String ANDROID_STUDIO_URL_FLAG = "?source=android-studio";

  private static final String GOOGLE_GROUP_URL = "'https://firebase.google.com/support'";

  private static final String SHOW_GOOGLE_CLOUD_TESTING_TIMESTAMPS = "show.google.cloud.testing.timestamps";

  //GCT-specific message names.
  public static final String SET_TEST_RUN_ID = "setTestRunId";
  public static final String SET_ACTIVE_CLOUD_MATRIX = "setActiveCloudMatrix";
  public static final String TEST_CONFIGURATION_STOPPED = "testConfigurationStopped";
  public static final String TEST_CONFIGURATION_STARTED = "testConfigurationStarted";
  public static final String TEST_CONFIGURATION_PROGRESS = "testConfigurationProgress";
  public static final String TEST_CONFIGURATION_SCHEDULED = "testConfigurationScheduled";
  public static final String TEST_CONFIGURATION_FINISHED = "testConfigurationFinished";

  public static Icon CLOUD_DEVICE_ICON;

  private static final Logger LOG = Logger.getInstance(CloudTestingUtils.class.getName());

  static {
    try {
      CLOUD_DEVICE_ICON = new ImageIcon(ImageIO.read(CloudTestingUtils.class.getResourceAsStream("CloudDevice.png")));
    } catch (Exception e) { // If something goes wrong, just use the default device icon.
      CLOUD_DEVICE_ICON = StudioIcons.Avd.DEVICE_PHONE;
    }
  }



  public static enum ConfigurationStopReason {
    FINISHED, INFRASTRUCTURE_FAILURE, TRIGGERING_ERROR, TIMED_OUT
  }

  public static boolean shouldShowProgressTimestamps() {
    return Boolean.getBoolean(SHOW_GOOGLE_CLOUD_TESTING_TIMESTAMPS);
  }

  public static CloudConfigurationImpl getConfigurationById(int id, AndroidFacet facet) {
    for (CloudConfiguration configuration : CloudConfigurationHelper.getAllCloudConfigurations(facet)) {
      if (configuration.getId() == id) {
        return (CloudConfigurationImpl) configuration;
      }
    }
    return null;
  }

  public static String prepareTestSpecification(AndroidTestRunConfiguration testRunConfiguration) {
    switch (testRunConfiguration.TESTING_TYPE) {
      case AndroidTestRunConfiguration.TEST_METHOD :
        return "class " + testRunConfiguration.CLASS_NAME + "#" + testRunConfiguration.METHOD_NAME;
      case AndroidTestRunConfiguration.TEST_CLASS :
        return "class " + testRunConfiguration.CLASS_NAME;
      case AndroidTestRunConfiguration.TEST_ALL_IN_PACKAGE :
        return "package " + testRunConfiguration.PACKAGE_NAME;
      case AndroidTestRunConfiguration.TEST_ALL_IN_MODULE :
        return "";
      default:
        throw new IllegalStateException("Unsupported testing type: " + testRunConfiguration.TESTING_TYPE);
    }
  }

  public static void showBalloonMessage(final Project project, final String message, final MessageType type, final int delaySeconds) {
    SwingUtilities.invokeLater(() -> {
      StatusBar statusBar = WindowManager.getInstance().getStatusBar(project);
      JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(message, type, null).setFadeoutTime(delaySeconds * 1000).createBalloon()
        .show(RelativePoint.getCenterOf(statusBar.getComponent()), Balloon.Position.atRight);
    });
  }

  public static void showErrorMessage(@Nullable Project project, String errorDialogTitle, String errorMessage) {
    int newLineIndex = errorMessage.indexOf('\n');
    String userErrorMessage = newLineIndex != -1 ? errorMessage.substring(0, newLineIndex) : errorMessage;
    String detailedErrorMessage = newLineIndex != -1
                                  ? "<html><a href=" + GOOGLE_GROUP_URL
                                    + ">Report this issue</a> (please copy/paste the text below into the form)<br><br>"
                                    + getDetailedErrorMessage(errorMessage.substring(newLineIndex + 1)) + "</html>"
                                  : "No details...";
    UsageTracker.log(UsageTrackerUtils.withProjectId(
                       AndroidStudioEvent.newBuilder()
                         .setCategory(AndroidStudioEvent.EventCategory.CLOUD_TESTING)
                         .setKind(AndroidStudioEvent.EventKind.CLOUD_TESTING_BACKEND_ERROR)
                         .setCloudTestingErrorMessage(userErrorMessage),
                       project));
    showCascadingErrorMessages(project, errorDialogTitle, userErrorMessage, detailedErrorMessage);
  }

  private static String getDetailedErrorMessage(String errorMessage) {
    String debugInfoField = "\"debugInfo\" : \""; // Available for internal runs only.
    int debugInfoIndex = errorMessage.indexOf(debugInfoField);
    if (debugInfoIndex != -1) {
      int debugInfoStartIndex = debugInfoIndex + debugInfoField.length();
      int debugInfoEndIndex = errorMessage.indexOf('\"', debugInfoStartIndex);
      if (debugInfoEndIndex != -1) {
        String debugInfo = errorMessage.substring(debugInfoStartIndex, debugInfoEndIndex);
        errorMessage = errorMessage.substring(0, debugInfoIndex) + errorMessage.substring(debugInfoEndIndex + 2);
        String unescapedDebugInfo = debugInfo.replace("\\n", "\n").replace("\\t", "\t");
        errorMessage += "\n\n" + "DEBUG INFO:\n" + unescapedDebugInfo;
      }
    }
    return errorMessage.replace("\n", "<br>").replace("\t", "&nbsp;&nbsp;&nbsp;&nbsp;");
  }

  private static void showCascadingErrorMessages(@Nullable final Project project, final String errorDialogTitle, String userErrorMessage,
                                                 final String detailedErrorMessage) {

    new Notification(errorDialogTitle, "", String.format("<b>%s</b> <a href=''>Details</a>", userErrorMessage), NotificationType.WARNING).setListener(
      new NotificationListener.Adapter() {
        @Override
        protected void hyperlinkActivated(@NotNull Notification notification, @NotNull HyperlinkEvent event) {
          notification.expire();
          Messages.showDialog(project, detailedErrorMessage, errorDialogTitle, new String[]{Messages.CANCEL_BUTTON}, 0, null);
        }
      }).notify((project == null || project.isDefault()) ? null : project);
  }

  public static GridBagConstraints createConfigurationChooserGbc(int x, int y) {
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridx = x;
    gbc.gridy = y;
    gbc.gridwidth = 1;
    gbc.gridheight = 1;

    gbc.anchor = (x == 0) ? GridBagConstraints.WEST : GridBagConstraints.EAST;
    gbc.fill = (x == 0) ? GridBagConstraints.BOTH : GridBagConstraints.HORIZONTAL;

    gbc.insets = (x == 0 && y == 0) ? new Insets(5, 7, 5, 5) : new Insets(5, 5, 5, 5);
    gbc.weightx = (x == 0) ? 0.1 : 1.0;
    gbc.weighty = 0.0;
    return gbc;
  }

  public static Color makeDarker(Color color, int shades) {
    if (shades < 1) {
      return color;
    }
    return makeDarker(ColorUtil.darker(color, 1), shades - 1);
  }

  public static String preparePricingAnchor(String linkText) {
    return "<a href='" + PRICING_LINK + "'>" + linkText + "</a>";
  }

  public static String prepareCreateFirebaseProjectAnchor(String linkText) {
    return "<a href='" + CREATE_FIREBASE_PROJECT_LINK + "'>" + linkText + "</a>";
  }

  public static void linkifyEditorPane(@NotNull JEditorPane editorPane, @NotNull Color backgroundColor) {
    editorPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
    editorPane.setEditable(false);
    editorPane.setBackground(backgroundColor);
    editorPane.addHyperlinkListener(BrowserHyperlinkListener.INSTANCE);
  }

  public static void addToInvokeLater(final Runnable runnable) {
    final Application application = ApplicationManager.getApplication();
    if (application.isHeadlessEnvironment() && !application.isUnitTestMode()) {
      runnable.run();
    } else {
      UIUtil.invokeLaterIfNeeded(runnable);
    }
  }

  public static void runInEventDispatchThread(final Runnable runnable, final ModalityState state) {
    try {
      ApplicationManager.getApplication().invokeAndWait(runnable, state);
    }
    catch (Exception e) {
      LOG.warn(e);
    }
  }

  /**
   * Returns the timestamp in millis of last midnight in Pacific Time, when quotas usage for firebase cloud projects are refreshed.
   */
  public static long getTimestampAtMidnightInPT(Instant instant) {
    return ZonedDateTime.ofInstant(instant, ZoneId.of("America/Los_Angeles"))
      .withHour(0)
      .withMinute(0)
      .withSecond(0)
      .withNano(0)
      .toInstant()
      .toEpochMilli();
  }

  /**
   * Returns the timestamp in millis of last midnight in Pacific Time, when quotas usage for firebase cloud projects are refreshed.
   */
  public static long getTimestampAtMonthStartMidnightInPT(Instant instant) {
    return ZonedDateTime.ofInstant(instant, ZoneId.of("America/Los_Angeles"))
      .withDayOfMonth(1)
      .withHour(0)
      .withMinute(0)
      .withSecond(0)
      .withNano(0)
      .toInstant()
      .toEpochMilli();
  }
}
