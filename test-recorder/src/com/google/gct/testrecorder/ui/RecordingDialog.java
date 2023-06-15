/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.google.gct.testrecorder.ui;

import static com.android.tools.idea.gradle.dsl.api.dependencies.CommonConfigurationNames.ANDROID_TEST_IMPLEMENTATION;
import static com.android.tools.idea.gradle.dsl.api.ext.GradlePropertyModel.STRING_TYPE;
import static com.android.tools.idea.projectsystem.ProjectSystemUtil.getProjectSystem;
import static com.google.gct.testrecorder.event.TestRecorderAssertion.ASSERTION_RULES_WITHOUT_TEXT;
import static com.google.gct.testrecorder.event.TestRecorderAssertion.ASSERTION_RULES_WITH_TEXT;
import static com.google.gct.testrecorder.event.TestRecorderAssertion.EXISTS;
import static com.google.gct.testrecorder.event.TestRecorderAssertion.TEXT_IS;
import static com.google.gct.testrecorder.event.TestRecorderEvent.SUPPORTED_EVENTS;
import static com.google.gct.testrecorder.ui.TestRecorderAction.TEST_RECORDER_ICON;
import static com.google.gct.testrecorder.util.ClassHelper.getInternalName;
import static com.google.gct.testrecorder.util.ImageHelper.rotateImage;
import static com.google.gct.testrecorder.util.UiAutomatorNodeHelper.createElementLevelMap;
import static com.google.gct.testrecorder.util.UiAutomatorNodeHelper.getAppPackageName;
import static com.google.gct.testrecorder.util.UiAutomatorNodeHelper.getClassName;
import static com.google.gct.testrecorder.util.UiAutomatorNodeHelper.getContentDescription;
import static com.google.gct.testrecorder.util.UiAutomatorNodeHelper.getResourceId;
import static com.google.gct.testrecorder.util.UiAutomatorNodeHelper.getRotation;
import static com.google.gct.testrecorder.util.UiAutomatorNodeHelper.getText;
import static com.google.gct.testrecorder.util.UiAutomatorNodeHelper.getViewGroupChildPosition;
import static com.google.gct.testrecorder.util.UiAutomatorNodeHelper.isTextView;
import static com.google.wireless.android.sdk.stats.GradleSyncStats.Trigger.TRIGGER_ESPRESSO_SETUP;
import static org.apache.commons.lang.StringUtils.isEmpty;

import com.android.annotations.VisibleForTesting;
import com.android.ddmlib.IDevice;
import com.android.ide.common.gradle.Version;
import com.android.ide.common.repository.GradleCoordinate;
import com.android.tools.analytics.UsageTracker;
import com.android.tools.analytics.UsageTrackerUtils;
import com.android.tools.idea.gradle.dsl.api.GradleBuildModel;
import com.android.tools.idea.gradle.dsl.api.android.AndroidModel;
import com.android.tools.idea.gradle.dsl.api.dependencies.ArtifactDependencyModel;
import com.android.tools.idea.gradle.dsl.api.dependencies.ArtifactDependencySpec;
import com.android.tools.idea.gradle.repositories.RepositoryUrlManager;
import com.android.tools.idea.projectsystem.AndroidModuleSystem;
import com.android.tools.idea.projectsystem.DependencyScopeType;
import com.android.tools.idea.projectsystem.GoogleMavenArtifactId;
import com.android.tools.idea.projectsystem.ProjectSystemSyncManager;
import com.android.tools.idea.projectsystem.ProjectSystemUtil;
import com.android.uiautomator.UiAutomatorModel;
import com.android.uiautomator.tree.BasicTreeNode;
import com.android.uiautomator.tree.UiNode;
import com.google.common.collect.ImmutableList;
import com.google.gct.testrecorder.codegen.TestCodeGenerator;
import com.google.gct.testrecorder.event.ElementAction;
import com.google.gct.testrecorder.event.ElementDescriptor;
import com.google.gct.testrecorder.event.TestRecorderAssertion;
import com.google.gct.testrecorder.event.TestRecorderEvent;
import com.google.gct.testrecorder.event.TestRecorderEventListener;
import com.google.gct.testrecorder.roboscript.ContextualRoboscript;
import com.google.gct.testrecorder.roboscript.RoboscriptConfiguration;
import com.google.gct.testrecorder.settings.TestRecorderSettings;
import com.google.gct.testrecorder.util.StringHelper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventCategory;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventKind;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.ex.FileSaverDialogImpl;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.SystemInfoRt;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.psi.PsiClass;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBDimension;
import com.intellij.util.ui.JBUI;
import com.sun.jdi.request.BreakpointRequest;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.image.BufferedImage;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.android.dom.manifest.Manifest;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class RecordingDialog extends DialogWrapper implements TestRecorderEventListener {
  private static final long ANIMATION_INTERVAL = 400; // milliseconds.
  private static final int ANIMATION_TIMER_INTERVAL = 10; // milliseconds.

  private static final String ESPRESSO_CORE_CUSTOM_ARTIFACT_NAME = "espresso";
  private static final String ESPRESSO_CORE_CUSTOM_GROUP_NAME = "com.jakewharton.espresso";

  public static final String TEST_INSTRUMENTATION_RUNNER = "android.support.test.runner.AndroidJUnitRunner";

  public static final String ANDROIDX_TEST_INSTRUMENTATION_RUNNER = "androidx.test.runner.AndroidJUnitRunner";

  /** The minimal version of espresso-core in build.gradle that does not require updating for importing LargeTest. */
  private static final Version MIN_ESPRESSO_CORE_VERSION_FOR_LARGE_TEST = Version.Companion.parse("2.2.2");

  /** The minimal version of rules in build.gradle that does not require updating for importing LargeTest. */
  private static final Version MIN_RULES_VERSION_FOR_LARGE_TEST = Version.Companion.parse("0.5");

  /** The minimal version of espresso-core in build.gradle that does not require updating for using GrantPermissionRule. */
  private static final Version MIN_ESPRESSO_CORE_VERSION_FOR_GRANT_PERMISSION_RULE = Version.Companion.parse("3.0.0");

  /** The minimal version of rules in build.gradle that does not require updating for using GrantPermissionRule. */
  private static final Version MIN_RULES_VERSION_FOR_GRANT_PERMISSION_RULE = Version.Companion.parse("1.0.0");

  /** The minimal version of androidx espresso-core in build.gradle that does not require updating. */
  private static final Version MIN_ANDROIDX_ESPRESSO_CORE_VERSION = Version.Companion.parse("3.5.0");

  /** The minimal version of androidx rules in build.gradle that does not require updating. */
  private static final Version MIN_ANDROIDX_RULES_VERSION = Version.Companion.parse("1.5.0");

  /** The minimal version of androidx ext junit in build.gradle that does not require updating. */
  private static final Version MIN_ANDROIDX_EXT_JUNIT_VERSION = Version.Companion.parse("1.1.5");

  /** Version of espresso-core added/updated in build.gradle, when missing or obsolete. Should be used only via its accessor method. */
  private static String espressoCoreVersion = null;

  /** Version of androidx espresso-core added/updated in build.gradle, when missing or obsolete. Should be used only via its accessor method. */
  private static String androidxEspressoCoreVersion = null;

  /** Version of rules added/updated in build.gradle, when missing or obsolete. Should be used only via its accessor method. */
  private static String rulesVersion = null;

  /** Version of androidx rules added/updated in build.gradle, when missing or obsolete. Should be used only via its accessor method. */
  private static String androidxRulesVersion = null;

  /** Version of androidx ext junit added/updated in build.gradle, when missing or obsolete. Should be used only via its accessor method. */
  private static String androidxExtJunitVersion = null;

  public static final ImmutableList<ArtifactDependencySpec> ESPRESSO_CORE_EXCLUDES =
    ImmutableList.of(createArtifactDependencySpec(GoogleMavenArtifactId.SUPPORT_ANNOTATIONS, null));

  public static final ImmutableList<ArtifactDependencySpec> ESPRESSO_CONTRIB_EXCLUDES =
    ImmutableList.of(createArtifactDependencySpec(GoogleMavenArtifactId.SUPPORT_ANNOTATIONS, null),
                     createArtifactDependencySpec(GoogleMavenArtifactId.SUPPORT_V4, null),
                     createArtifactDependencySpec(GoogleMavenArtifactId.DESIGN, null),
                     createArtifactDependencySpec(GoogleMavenArtifactId.RECYCLERVIEW_V7, null));

  private static final String TEST_RECORDING_DIALOG_TITLE = "Record Your Test";
  private static final String SCRIPT_RECORDING_DIALOG_TITLE = "Record Your Robo Script";
  private static final String DEFAULT_MESSAGE = "Select an element from screenshot";

  private final Project myProject;
  private final AndroidFacet myFacet;
  private final IDevice myDevice;
  private final String myPackageName;
  private final String myLaunchedActivityName;
  private final boolean myIsRecordingTest;

  private DebuggerSession myDebuggerSession;
  private boolean myAssertionMode;
  private int myAssertionIndex;
  private LinkedHashMap<BasicTreeNode, Integer> myNodeIndentMap;
  private DefaultComboBoxModel myElementComboBoxModel;
  private final DefaultListModel<ElementAction> myActionListModel;
  /** Shows whether recording is in progress. */
  private boolean myIsRecording = true;
  private boolean myWasEverPaused = false;
  private boolean myNeedsContribDependency = false;
  private boolean myUsesAnyEspressoDependency = false;
  private boolean myUsesAndroidxDependency = false;
  private boolean myUsesGrantPermissionRule = false;
  private Version myMinEspressoCoreVersion = MIN_ESPRESSO_CORE_VERSION_FOR_LARGE_TEST;
  private final Version myMinAndroidxEspressoCoreVersion = MIN_ANDROIDX_ESPRESSO_CORE_VERSION;
  private Version myMinRulesVersion = MIN_RULES_VERSION_FOR_LARGE_TEST;
  private final Version myMinAndroidxRulesVersion = MIN_ANDROIDX_RULES_VERSION;
  private final Version myMinAndroidxExtJunitVersion = MIN_ANDROIDX_EXT_JUNIT_VERSION;

  private JPanel myRootPanel;
  private ScreenshotPanel myScreenshotPanel;
  private JPanel myActionListPanel;
  private JBScrollPane myScrollPane;
  private JBList<ElementAction> myActionList;
  private JPanel myAssertionPanel;
  private JPanel myButtonsPanel;
  private JButton myAddAssertionButton;
  private JButton myTakeScreenshotButton;
  private JPanel myEditAssertionPanel;
  private JComboBox myAssertionElementComboBox;
  private JComboBox myAssertionRuleComboBox;
  private JPanel myTextFieldWrapper;
  private JTextField myAssertionTextField;
  private JButton mySaveAssertionButton;
  private JButton myCancelButton;
  private JButton mySaveAssertionAndAddAnotherButton;
  private JPanel myRecordingPanel;
  private JButton myRecordPauseButton;

  public RecordingDialog(AndroidFacet facet, IDevice device, String packageName, String launchedActivityName, boolean isRecordingTest) {
    super(facet.getModule().getProject(), true, IdeModalityType.MODELESS);
    myProject = facet.getModule().getProject();
    myFacet = facet;
    myDevice = device;
    myPackageName = packageName;
    myLaunchedActivityName = launchedActivityName;
    myIsRecordingTest = isRecordingTest;
    myAssertionMode = false;

    init();

    setTitle(myIsRecordingTest ? TEST_RECORDING_DIALOG_TITLE : SCRIPT_RECORDING_DIALOG_TITLE);

    getRootPane().setDefaultButton(getButton(getOKAction()));

    // TODO: Make it visible when we add the required functionality.
    myTakeScreenshotButton.setVisible(false);

    myActionList.setEmptyText("No actions recorded yet.");
    myActionListModel = new DefaultListModel<>();
    myActionList.setModel(myActionListModel);
    myActionList.setCellRenderer(new TestRecorderListRenderer());

    if (!myIsRecordingTest) {
      // No need for adding assertions and screenshots while recording a Robo script.
      myAssertionPanel.setVisible(false);
      // Recording a Robo script does not support snippets.
      myRecordPauseButton.setVisible(false);
    } else {
      myRecordPauseButton.setVisible(TestRecorderSettings.getInstance().ENABLE_TEST_FRAGMENT_RECORDING);
    }

    myRecordPauseButton.setIcon(AllIcons.Actions.Pause);

    myRecordPauseButton.addActionListener(e -> {
      myWasEverPaused = true;
      myIsRecording = !myIsRecording;
      updateRecordPauseButton();
      toggleDebugging();
    });

    myAddAssertionButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent actionEvent) {
        new TestRecorderScreenshotTask(myProject, myDevice, myPackageName, new ScreenshotCallback() {
          @Override
          public void onSuccess(BufferedImage initialImage, UiAutomatorModel model) {
            myAssertionMode = true;
            getRootPane().setDefaultButton(mySaveAssertionAndAddAnotherButton);
            BasicTreeNode root = model.getXmlRootNode();
            String applicationId = getApplicationId("");
            if (!applicationId.isEmpty() && !applicationId.equals(getAppPackageName(root))) {
              Messages.showMessageDialog(myRootPanel, "Out-of-app assertions are not supported and will break the generated Espresso test.",
                                         "Warning: adding an out-of-app assertion", null);
            }
            BufferedImage preparedImage = rotateImage(initialImage, getRotation(root));
            myScreenshotPanel.updateScreenShot(preparedImage, model);
            // Populate drop down menu
            myNodeIndentMap = createElementLevelMap(root);
            myElementComboBoxModel = new DefaultComboBoxModel(myNodeIndentMap.keySet().toArray());
            // Add a default element for drop down menu
            myElementComboBoxModel.insertElementAt(DEFAULT_MESSAGE, 0);
            // Show assertion panel
            CardLayout cardLayout = (CardLayout)myAssertionPanel.getLayout();
            cardLayout.show(myAssertionPanel, "myEditAssertionPanel");
            // Set up assertion panel
            setUpEmptyAssertionPanel();
            // Remember the index of to-be-added assertion.
            myAssertionIndex = myActionListModel.size();

            revealScreenshotPanel(preparedImage.getWidth(), preparedImage.getHeight());
          }
        }).queue();
      }
    });

    // TODO: take screenshot in Espresso test code
    myTakeScreenshotButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent actionEvent) {

      }
    });

    mySaveAssertionButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent actionEvent) {
        exitAssertionMode(true);
        hideScreenshotPanel();
      }
    });

    mySaveAssertionAndAddAnotherButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent actionEvent) {
        // Add the new assertion at its remembered index.
        myActionListModel.add(myAssertionIndex, buildAssertionForCurrentSelection());
        // Scroll action list so that assertion is visible.
        myActionList.ensureIndexIsVisible(myAssertionIndex);
        myAssertionIndex++;
      }
    });

    myCancelButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent actionEvent) {
        exitAssertionMode(false);
        hideScreenshotPanel();
      }
    });

    myAssertionElementComboBox.addItemListener(new ItemListener() {
      @Override
      public void itemStateChanged(ItemEvent itemEvent) {
        // Get selected element
        Object element = myAssertionElementComboBox.getSelectedItem();
        if (element instanceof BasicTreeNode) {
          // selected element is UI element
          BasicTreeNode node = (BasicTreeNode)element;
          // Update selected element in screenshot panel
          myScreenshotPanel.setSelectedNodeAndRepaint(node);
          // Update edit assertion panel
          if (isTextView(node)) {
            CardLayout cardLayout = (CardLayout)myTextFieldWrapper.getLayout();
            cardLayout.show(myTextFieldWrapper, "myAssertionTextField");
            myAssertionTextField.setText(getText(node));
            myAssertionRuleComboBox.setModel(new DefaultComboBoxModel(ASSERTION_RULES_WITH_TEXT));
          } else {
            CardLayout cardLayout = (CardLayout) myTextFieldWrapper.getLayout();
            cardLayout.show(myTextFieldWrapper, "myPlaceHolder");
            myAssertionRuleComboBox.setModel(new DefaultComboBoxModel(ASSERTION_RULES_WITHOUT_TEXT));
          }
          // Enable save assertion buttons.
          mySaveAssertionButton.setEnabled(true);
          mySaveAssertionAndAddAnotherButton.setEnabled(true);
          myAssertionTextField.setForeground(JBColor.BLACK);
        } else {
          // selected element is not UI element (default element)
          myScreenshotPanel.clearSelectionAndRepaint();
        }
      }
    });

    myAssertionElementComboBox.setRenderer(new DefaultListCellRenderer() {
      @Override
      public Component getListCellRendererComponent(JList list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
        if (value instanceof BasicTreeNode) {
          BasicTreeNode node = (BasicTreeNode) value;
          // Add indent.
          int indent = myNodeIndentMap.get(node);
          String prefix = StringUtils.repeat("  ", indent);
          // No indent for selected element.
          if (index == -1) {
            prefix = "";
          }
          String resourceId = getResourceId(node);
          String nodeString = resourceId.isEmpty() ? getClassName(node) : resourceId;
          return super.getListCellRendererComponent(list, prefix + nodeString, index, isSelected, cellHasFocus);
        } else {
          // non UI element
          return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        }
      }
    });

    myAssertionRuleComboBox.addItemListener(new ItemListener() {
      @Override
      public void itemStateChanged(ItemEvent itemEvent) {
        Object selectedItem = myAssertionRuleComboBox.getSelectedItem();
        if (selectedItem == null) {
          return;
        }

        String rule = selectedItem.toString();
        if (TEXT_IS.equals(rule)) {
          // Display assertion text field when rule is "text ***"
          CardLayout cardLayout = (CardLayout) myTextFieldWrapper.getLayout();
          cardLayout.show(myTextFieldWrapper, "myAssertionTextField");
        } else {
          // Otherwise (exists, does not exist), don't display assertion text field
          CardLayout cardLayout = (CardLayout) myTextFieldWrapper.getLayout();
          cardLayout.show(myTextFieldWrapper, "myPlaceHolder");
        }
      }
    });
  }

  private static String getEspressoCoreVersion() {
    if (espressoCoreVersion == null) {
      espressoCoreVersion = getLatestDependencyVersion(GoogleMavenArtifactId.ESPRESSO_CORE, "3.0.2");
    }
    return espressoCoreVersion;
  }

  private static String getAndroidxEspressoCoreVersion() {
    if (androidxEspressoCoreVersion == null) {
      androidxEspressoCoreVersion = getLatestDependencyVersion(GoogleMavenArtifactId.ANDROIDX_ESPRESSO_CORE, "3.5.0");
    }
    return androidxEspressoCoreVersion;
  }

  private static String getRulesVersion() {
    if (rulesVersion == null) {
      rulesVersion = getLatestDependencyVersion(GoogleMavenArtifactId.TEST_RULES, "1.0.2");
    }
    return rulesVersion;
  }

  private static String getAndroidxRulesVersion() {
    if (androidxRulesVersion == null) {
      androidxRulesVersion = getLatestDependencyVersion(GoogleMavenArtifactId.ANDROIDX_TEST_RULES, "1.5.0");
    }
    return androidxRulesVersion;
  }

  private static String getAndroidxExtJunitVersion() {
    if (androidxExtJunitVersion == null) {
      androidxExtJunitVersion = getLatestDependencyVersion(GoogleMavenArtifactId.ANDROIDX_TEST_EXT_JUNIT, "1.1.5");
    }
    return androidxExtJunitVersion;
  }

  public void setDebuggerSession(DebuggerSession debuggerSession) {
    myDebuggerSession = debuggerSession;
    myIsRecording = myDebuggerSession != null;
    myRecordPauseButton.setEnabled(myIsRecording);
    updateRecordPauseButton();
  }

  private void updateRecordPauseButton() {
    if (myIsRecording) {
      myRecordPauseButton.setText("Pause");
      myRecordPauseButton.setIcon(AllIcons.Actions.Pause);
    } else {
      myRecordPauseButton.setText("Resume");
      myRecordPauseButton.setIcon(TEST_RECORDER_ICON);
    }
  }

  private void toggleDebugging() {
    if (myDebuggerSession != null) {
      List<BreakpointRequest> requests = myDebuggerSession.getProcess().getRequestsManager().getVMRequestManager().breakpointRequests();
      for (BreakpointRequest request : requests) {
        request.setEnabled(myIsRecording);
      }
    }
  }

  private static String getLatestDependencyVersion(GoogleMavenArtifactId artifactId, String fallbackVersion) {
    String latestIdentifier = RepositoryUrlManager.get().getArtifactComponentIdentifier(artifactId, true);
    if (latestIdentifier != null) {
      com.android.ide.common.gradle.Component component = com.android.ide.common.gradle.Component.Companion.tryParse(latestIdentifier);
      if (component != null) {
        return component.getVersion().toString();
      }
    }

    //Fallback to some default version.
    return fallbackVersion;
  }

  @VisibleForTesting
  static String getJsonForActions(Project project, List<ElementAction> actions) {
    // Consider only TestRecorderEvents.
    List<TestRecorderEvent> testRecorderEvents = new ArrayList<>();
    for (ElementAction action : actions) {
      if (action instanceof TestRecorderEvent) {
        testRecorderEvents.add((TestRecorderEvent)action);
      }
    }

    GsonBuilder gsonBuilder = new GsonBuilder();
    gsonBuilder.registerTypeAdapter(ElementDescriptor.class, new ElementDescriptorSerializer(project));
    Gson gson = gsonBuilder.setPrettyPrinting().create();
    RoboscriptConfiguration roboscriptConfiguration = new RoboscriptConfiguration(false, false);
    String roboscriptHeader = "\"roboscript\": "
                              + gson.toJson(roboscriptConfiguration)
                              + "\n";
    List<ContextualRoboscript> contextualRoboscripts = new ArrayList<>();
    contextualRoboscripts.add(new ContextualRoboscript(testRecorderEvents));
    return roboscriptHeader + gson.toJson(contextualRoboscripts);
  }

  @NotNull
  private static ArtifactDependencySpec createArtifactDependencySpec(@NotNull GoogleMavenArtifactId artifactId, @Nullable String version) {
    return ArtifactDependencySpec.create(artifactId.getMavenArtifactId(), artifactId.getMavenGroupId(), version);
  }

  private static class ElementDescriptorSerializer implements JsonSerializer<ElementDescriptor> {
    private final Project myProject;

    public ElementDescriptorSerializer(Project project) {
      myProject = project;
    }

    @Override
    public JsonElement serialize(ElementDescriptor elementDescriptor, Type typeOfSrc, JsonSerializationContext context) {
      JsonObject jsonObject = new JsonObject();
      jsonObject.addProperty("className", getInternalName(myProject, elementDescriptor.getClassName()));
      jsonObject.addProperty("recyclerViewChildPosition", elementDescriptor.getRecyclerViewChildPosition());
      jsonObject.addProperty("adapterViewChildPosition", elementDescriptor.getAdapterViewChildPosition());
      jsonObject.addProperty("groupViewChildPosition", elementDescriptor.getGroupViewChildPosition());
      jsonObject.addProperty("resourceId", elementDescriptor.getResourceId());
      jsonObject.addProperty("contentDescription", elementDescriptor.getContentDescription());
      jsonObject.addProperty("text", elementDescriptor.getText());
      return jsonObject;
    }
  }

  @NotNull
  @Override
  protected String getHelpId() {
    return myIsRecordingTest
           ? "https://developer.android.com/r/studio-ui/test-recorder.html"
           : "https://firebase.google.com/docs/test-lab/robo-ux-test#scripting";
  }

  @Override
  protected void doHelpAction() {
    BrowserUtil.browse(getHelpId());
  }

  @Override
  protected void doOKAction() {
    if (myIsRecordingTest) {
      // Show the test class name input dialog before (potentially) setting up Espresso dependencies,
      // which might confuse Gradle about the location of android tests.
      TestClassNameInputDialog chooser = new TestClassNameInputDialog(myFacet.getModule(), myLaunchedActivityName);
      chooser.show();
      PsiClass testClass = chooser.getTestClass();
      if (testClass == null) {
        // Test class generation was cancelled or resulted in an error.
        return;
      }
      Module testClassModule = chooser.getTestClassModule();

      //Similarly, compute resource package name and application id before the potential Gradle confusion.
      String resourcePackageName = "unknown";
      AndroidFacet testClassFacet = AndroidFacet.getInstance(testClassModule);
      if (testClassFacet !=  null) {
        Manifest manifest = Manifest.getMainManifest(testClassFacet);
        if (manifest != null) {
          resourcePackageName = manifest.getPackage().getStringValue();
        }
      }
      String applicationId = getApplicationId(resourcePackageName);

      if (resourcePackageName == null) {
        // Fallback to application ID as the app's package name.
        resourcePackageName = applicationId;
      }

      // Automatically check/setup Espresso dependencies for Gradle projects only.
      GradleBuildModel gradleBuildModel = GradleBuildModel.get(testClassModule);
      if (gradleBuildModel != null) {
        AndroidModel androidModel = gradleBuildModel.android();
        // androidModel will be null when the Gradle experimental plugin is used and it's not possible to update the instrumentation runner.
        // TODO: Provide an appropriate error message or some alternative way to update instrumentation runner when the Gradle experimental
        // plugin is used.
        if (!hasAllRequiredEspressoDependencies(androidModel, ProjectSystemUtil.getModuleSystem(testClassModule))) {
          UsageTracker.log(UsageTrackerUtils.withProjectId(
            AndroidStudioEvent.newBuilder()
             .setCategory(EventCategory.TEST_RECORDER)
             .setKind(EventKind.TEST_RECORDER_MISSING_ESPRESSO_DEPENDENCIES),
            myProject));

          if (MessageDialogBuilder.yesNo("Missing or obsolete Espresso dependencies",
                                         "Some dependencies for running Espresso tests are missing or obsolete.\n" +
                                         "Would you like to automatically add/update Espresso dependencies for this app?\n" +
                                         "To complete the set up, Gradle might ask you to install the missing libraries.\n" +
                                         "Please click on the corresponding link(s) to install them.").icon(null).ask(myRootPanel)) {
            setupEspresso(gradleBuildModel);
          }
        }
      }
      super.doOKAction();
      new TestCodeGenerator(resourcePackageName, applicationId, testClassModule, testClass, getAllModelActions(), myLaunchedActivityName,
                            myWasEverPaused, chooser.isKotlinTestClass(), myUsesAndroidxDependency).generate();
    } else {
      FileSaverDescriptor descriptor = new FileSaverDescriptor("Save Robo Script", "Save Robo script to a file", "json");
      FileSaverDialogImpl fileSaverDialog = new FileSaverDialogImpl(descriptor, myProject);
      VirtualFileWrapper fileWrapper = fileSaverDialog.save((VirtualFile)null, StringHelper.getClassName(myLaunchedActivityName) + "_robo_script");

      if (fileWrapper != null) {
        try {
          FileUtils.write(fileWrapper.getFile(), getJsonForActions(myProject, getAllModelActions()));
        } catch (Exception ex) {
          String message = isEmpty(ex.getMessage()) ? "Unknown error" : ex.getMessage();
          Messages.showMessageDialog(myRootPanel, message, "Could not save Robo script to a file", null);
        }
      }

      if (fileSaverDialog.isOK()) {
        UsageTracker.log(UsageTrackerUtils.withProjectId(
          AndroidStudioEvent.newBuilder()
           .setCategory(EventCategory.TEST_RECORDER)
           .setKind(EventKind.TEST_RECORDER_SAVE_ROBO_SCRIPT),
          myProject));
        super.doOKAction();
      }
    }
  }

  private String getApplicationId(String defaultId) {
    try {
      return ProjectSystemUtil.getModuleSystem(myFacet).getApplicationIdProvider().getPackageName();
    } catch (Exception e) {
      return defaultId;
    }
  }

  private List<ElementAction> getAllModelActions() {
    return Collections.list(myActionListModel.elements());
  }

  private void exitAssertionMode(boolean shouldAddAssertion) {
    myAssertionMode = false;
    getRootPane().setDefaultButton(getButton(getOKAction()));
    // Display button panel.
    CardLayout cardLayout = (CardLayout) myAssertionPanel.getLayout();
    cardLayout.show(myAssertionPanel, "myButtonsPanel");

    if (shouldAddAssertion) {
      // Add the new assertion at its remembered index.
      myActionListModel.add(myAssertionIndex, buildAssertionForCurrentSelection());
      // Scroll action list so that assertion is visible.
      myActionList.ensureIndexIsVisible(myAssertionIndex);
    } else {
      // Scroll action list so that the last action is visible.
      myActionList.ensureIndexIsVisible(myActionListModel.size() - 1);
    }
  }

  private void revealScreenshotPanel(int imageWidth, int imageHeight) {
    // Make the current size the minimum one to avoid shrinking of the current recording panel.
    // TODO: As a result, the user would not be able to reduce the size of the recording panel to a value smaller than the one
    // it had at the latest screenshot panel reveal.
    myRecordingPanel.setMinimumSize(new Dimension(myRecordingPanel.getWidth(), myRecordingPanel.getHeight()));

    myScreenshotPanel.setVisible(true);

    final int screenshotPanelTotalHeight = myRootPanel.getHeight();
    int scaledImageWidth = imageHeight <= screenshotPanelTotalHeight
                           ? imageWidth
                           : (int) ((double) (imageWidth * screenshotPanelTotalHeight) / imageHeight);

    // Cap panel width to not be greater than panel height.
    final int screenshotPanelTotalWidth = scaledImageWidth > screenshotPanelTotalHeight ? screenshotPanelTotalHeight : scaledImageWidth;

    final Timer t = new Timer(ANIMATION_TIMER_INTERVAL, null);
    final long start = System.currentTimeMillis();
    t.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed > ANIMATION_INTERVAL || SystemInfoRt.isMac) {
          myScreenshotPanel.setMinimumSize(new Dimension(screenshotPanelTotalWidth, screenshotPanelTotalHeight));
          t.stop();
        } else {
          double percentRevealed = ((double) elapsed / ANIMATION_INTERVAL);
          myScreenshotPanel.setMinimumSize(new Dimension((int)(screenshotPanelTotalWidth * percentRevealed), screenshotPanelTotalHeight));
        }

        myScreenshotPanel.clearSelectionAndRepaint();
        getWindow().pack();
        myAssertionElementComboBox.requestFocusInWindow();
      }
    });

    t.start();
  }

  private void hideScreenshotPanel() {
    final int screenshotPanelInitialWidth = myScreenshotPanel.getWidth();
    final int screenshotPanelInitialHeight = myScreenshotPanel.getHeight();
    final int marginWidth = ((FlowLayout)myScreenshotPanel.getLayout()).getHgap() * 2;
    final int windowInitialWidth = getWindow().getWidth();

    final Timer t = new Timer(ANIMATION_TIMER_INTERVAL, null);
    final long start = System.currentTimeMillis();
    t.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed > ANIMATION_INTERVAL || SystemInfoRt.isMac) {
          myScreenshotPanel.setVisible(false);
          myScreenshotPanel.setMinimumSize(new Dimension(0, 0));
          getWindow().setMinimumSize(
            new Dimension(windowInitialWidth - screenshotPanelInitialWidth - marginWidth, getWindow().getHeight()));
          t.stop();
        } else {
          double percentHidden = ((double) elapsed / ANIMATION_INTERVAL);
          myScreenshotPanel.setMinimumSize(
            new Dimension((int)(screenshotPanelInitialWidth * (1d - percentHidden)), screenshotPanelInitialHeight));
          getWindow().setMinimumSize(
            new Dimension(windowInitialWidth - (int)(screenshotPanelInitialWidth * percentHidden) - marginWidth, getWindow().getHeight()));
        }

        myScreenshotPanel.clearSelectionAndRepaint();
        getWindow().pack();
      }
    });

    t.start();
  }

  private void createUIComponents() {
    myScreenshotPanel = new ScreenshotPanel(this);
    myScreenshotPanel.setPreferredSize(new Dimension(0, 0));
    myScreenshotPanel.setVisible(false);
  }

  @Nullable
  @Override
  protected JComponent createCenterPanel() {
    JBDimension initialSize = JBUI.size(450, 600);
    myRootPanel.setPreferredSize(initialSize);
    myRecordingPanel.setMinimumSize(initialSize);
    return myRootPanel;
  }

  private boolean hasAllRequiredEspressoDependencies(@NotNull AndroidModel androidModel, @NotNull AndroidModuleSystem androidModuleSystem) {
    initializeDependencyRequirements();
    // TODO: To improve performance, consider doing these checks in a single pass.
    return hasUptodateEspressoCoreDependency(androidModuleSystem)
           && hasUptodateRulesDependency(androidModuleSystem)
           && (!myNeedsContribDependency || hasUptodateEspressoContribDependency(androidModuleSystem))
           && (!myUsesAndroidxDependency || !myUsesGrantPermissionRule || hasUptodateAndroidxRulesDependency(androidModuleSystem))
           && hasSetInstrumentationRunner(androidModel);
  }

  private void initializeDependencyRequirements() {
    myNeedsContribDependency = false;
    myUsesAnyEspressoDependency = false;
    myUsesAndroidxDependency = false;
    myUsesGrantPermissionRule = false;
    myMinEspressoCoreVersion = MIN_ESPRESSO_CORE_VERSION_FOR_LARGE_TEST;
    myMinRulesVersion = MIN_RULES_VERSION_FOR_LARGE_TEST;

    for (ElementAction action : getAllModelActions()) {
      if (action instanceof TestRecorderEvent) {
        TestRecorderEvent testRecorderEvent = (TestRecorderEvent)action;
        if (testRecorderEvent.getElementRecyclerViewChildPosition() != -1) {
          myNeedsContribDependency = true;
        } else if (testRecorderEvent.isPermissionsRequest()) {
          myUsesGrantPermissionRule = true;
          myMinEspressoCoreVersion = MIN_ESPRESSO_CORE_VERSION_FOR_GRANT_PERMISSION_RULE;
          myMinRulesVersion = MIN_RULES_VERSION_FOR_GRANT_PERMISSION_RULE;
        }
      }
    }
  }

  private boolean hasUptodateEspressoCoreDependency(@NotNull AndroidModuleSystem androidModuleSystem) {
    String artifact = GoogleMavenArtifactId.ESPRESSO_CORE.toString();
    String androidxArtifact = GoogleMavenArtifactId.ANDROIDX_ESPRESSO_CORE.toString();
    return hasUptodateDependency(androidModuleSystem, artifact, androidxArtifact, myMinEspressoCoreVersion, myMinAndroidxEspressoCoreVersion);
  }

  private boolean hasUptodateRulesDependency(@NotNull AndroidModuleSystem androidModuleSystem) {
    String artifact = GoogleMavenArtifactId.TEST_RULES.toString();
    String androidxArtifact = GoogleMavenArtifactId.ANDROIDX_TEST_EXT_JUNIT.toString();
    return hasUptodateDependency(androidModuleSystem, artifact, androidxArtifact, myMinRulesVersion, myMinAndroidxExtJunitVersion);
  }

  private boolean hasUptodateEspressoContribDependency(@NotNull AndroidModuleSystem androidModuleSystem) {
    String artifact = GoogleMavenArtifactId.ESPRESSO_CONTRIB.toString();
    String androidxArtifact = GoogleMavenArtifactId.ANDROIDX_ESPRESSO_CONTRIB.toString();
    return hasUptodateDependency(androidModuleSystem, artifact, androidxArtifact, myMinEspressoCoreVersion, myMinAndroidxEspressoCoreVersion);
  }

  private boolean hasUptodateAndroidxRulesDependency(@NotNull AndroidModuleSystem androidModuleSystem) {
    String androidxArtifact = GoogleMavenArtifactId.ANDROIDX_TEST_RULES.toString();
    return hasUptodateDependency(androidModuleSystem, "", androidxArtifact, myMinRulesVersion, myMinAndroidxRulesVersion);
  }

  private static boolean hasSetInstrumentationRunner(@NotNull AndroidModel androidModel) {
    String testInstrumentationRunner = androidModel.defaultConfig().testInstrumentationRunner().getValue(STRING_TYPE);
    return testInstrumentationRunner != null && !testInstrumentationRunner.isEmpty();
  }

  /**
   * This logic assumes that existing ATSL dependencies are consistent, i.e., either all are androidx or all are not androidx.
   * Otherwise, it is an app build configuration error and Espresso Test Recorder dependency handling is undefined.
   */
  private boolean hasUptodateDependency(@NotNull AndroidModuleSystem androidModuleSystem, String artifact, String androidxArtifact,
                                        Version minVersion, Version androidxMinVersion) {
    Version dependencyVersion = getDependencyVersion(androidModuleSystem, artifact);
    if (dependencyVersion != null) {
      myUsesAnyEspressoDependency = true;
      return dependencyVersion.compareTo(minVersion) >= 0;
    }

    Version androidxDependencyVersion = getDependencyVersion(androidModuleSystem, androidxArtifact);
    if (androidxDependencyVersion != null) {
      myUsesAnyEspressoDependency = true;
      myUsesAndroidxDependency = true;
      return androidxDependencyVersion.compareTo(androidxMinVersion) >= 0;
    }

    return false;
  }

  @Nullable
  private static Version getDependencyVersion(@NotNull AndroidModuleSystem androidModuleSystem, String artifact) {
    GradleCoordinate coordinate = GradleCoordinate.parseCoordinateString(artifact + ":+");
    if (coordinate == null) return null;
    GradleCoordinate resolvedDependency = androidModuleSystem.getResolvedDependency(
      coordinate,
      DependencyScopeType.ANDROID_TEST
    );
    if (resolvedDependency == null) return null;
    return resolvedDependency.getLowerBoundVersion();
  }

  private GoogleMavenArtifactId getEspressoArtifactId() {
    return myUsesAndroidxDependency? GoogleMavenArtifactId.ANDROIDX_ESPRESSO_CORE : GoogleMavenArtifactId.ESPRESSO_CORE;
  }

  private String getEspressoArtifactUpdateVersion() {
    return myUsesAndroidxDependency ? getAndroidxEspressoCoreVersion() : getEspressoCoreVersion();
  }

  private GoogleMavenArtifactId getEspressoContribArtifactId() {
    return myUsesAndroidxDependency? GoogleMavenArtifactId.ANDROIDX_ESPRESSO_CONTRIB : GoogleMavenArtifactId.ESPRESSO_CONTRIB;
  }

  private GoogleMavenArtifactId getTestRulesArtifactId() {
    return myUsesAndroidxDependency? GoogleMavenArtifactId.ANDROIDX_TEST_EXT_JUNIT : GoogleMavenArtifactId.TEST_RULES;
  }

  private String getTestRulesArtifactUpdateVersion() {
    return myUsesAndroidxDependency ? getAndroidxExtJunitVersion() : getRulesVersion();
  }

  private void setupEspresso(@NotNull GradleBuildModel gradleBuildModel) {
    if (!myUsesAnyEspressoDependency) {
      // Establish whether to use androidx Espresso dependencies based on other present dependencies.
      AndroidModuleSystem moduleSystem = ProjectSystemUtil.getModuleSystem(myFacet.getModule());
      for (GoogleMavenArtifactId artifactId : GoogleMavenArtifactId.values()) {
        if (artifactId.getMavenGroupId().startsWith("androidx.") || artifactId.getMavenGroupId().equals("com.google.android.material")) {
          GradleCoordinate coordinate = moduleSystem.getResolvedDependency(artifactId.getCoordinate("+"));
          if (coordinate != null) {
            myUsesAndroidxDependency = true;
            break;
          }
        }
      }
    }

    new Task.Modal(myProject, "Setting up Espresso", false) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        indicator.setText("Adding Espresso dependencies");
        indicator.setIndeterminate(true);

        // TODO: This is a trick to make sure the progress dialog is shown. Otherwise, the action is too quick for the dialog to show up,
        // but long enough to see a noticeable delay.
        try {
          Thread.sleep(500);
        } catch (Exception e) {
          //  ignore
        }

        WriteCommandAction.runWriteCommandAction(myProject, () -> {
          addOrUpdateEspressoCoreDependency();
          addOrUpdateRulesDependency();
          if (myNeedsContribDependency) {
            addOrUpdateEspressoContribDependency();
          }
          if (myUsesAndroidxDependency && myUsesGrantPermissionRule) {
            addOrUpdateAndroidxRulesDependency();
          }

          AndroidModel androidModel = gradleBuildModel.android();
          if (androidModel != null && !hasSetInstrumentationRunner(androidModel)) {
            androidModel.defaultConfig().testInstrumentationRunner()
              .setValue(myUsesAndroidxDependency ? ANDROIDX_TEST_INSTRUMENTATION_RUNNER : TEST_INSTRUMENTATION_RUNNER);
          }

          gradleBuildModel.applyChanges();

          if (myProject != null) {
            getProjectSystem(myProject).getSyncManager().syncProject(new ProjectSystemSyncManager.SyncReason(TRIGGER_ESPRESSO_SETUP));
          }
        });
      }

      private void addOrUpdateEspressoCoreDependency() {
        boolean hasUpdatedEspressoCoreVersion = false;
        for (ArtifactDependencyModel artifact : gradleBuildModel.dependencies().artifacts()) {
          if (ESPRESSO_CORE_CUSTOM_GROUP_NAME.equals(artifact.group().toString())
              && ESPRESSO_CORE_CUSTOM_ARTIFACT_NAME.equals(artifact.name().forceString())) {
            // Remove the obsolete custom Espresso dependency.
            gradleBuildModel.dependencies().remove(artifact);
          } else if (isMatchingArtifact(artifact, getEspressoArtifactId())) {
            artifact.version().setValue(getEspressoArtifactUpdateVersion());
            hasUpdatedEspressoCoreVersion = true;
          } else if (isMatchingArtifact(artifact, getEspressoContribArtifactId())) {
            // Update espresso-contrib dependency, if present, to match espresso-core dependency version.
            artifact.version().setValue(getEspressoArtifactUpdateVersion());
          } else if (isMatchingArtifact(artifact, getTestRulesArtifactId())) {
            // Update rules dependency, if present, to match espresso-core dependency version.
            artifact.version().setValue(getTestRulesArtifactUpdateVersion());
          } else if (isMatchingArtifact(artifact, GoogleMavenArtifactId.ANDROIDX_TEST_RULES)) {
            // Update androidx rules dependency, if present, to match espresso-core dependency version.
            artifact.version().setValue(getAndroidxRulesVersion());
          }
        }
        if (!hasUpdatedEspressoCoreVersion) {
          if (myUsesAndroidxDependency) {
            // No need to add excludes for more recent (e.g., androidx) dependency versions.
            gradleBuildModel.dependencies().addArtifact(
              ANDROID_TEST_IMPLEMENTATION, createArtifactDependencySpec(GoogleMavenArtifactId.ANDROIDX_ESPRESSO_CORE,
                                                                        getAndroidxEspressoCoreVersion()));
          } else {
            gradleBuildModel.dependencies().addArtifact(ANDROID_TEST_IMPLEMENTATION,
                                                        createArtifactDependencySpec(GoogleMavenArtifactId.ESPRESSO_CORE, getEspressoCoreVersion()),
                                                        ESPRESSO_CORE_EXCLUDES);
          }
        }
      }

      private void addOrUpdateRulesDependency() {
        for (ArtifactDependencyModel artifact : gradleBuildModel.dependencies().artifacts()) {
          if (isMatchingArtifact(artifact, getTestRulesArtifactId())) {
            artifact.version().setValue(getTestRulesArtifactUpdateVersion());
            return;
          }
        }
        gradleBuildModel.dependencies().addArtifact(
          ANDROID_TEST_IMPLEMENTATION, createArtifactDependencySpec(getTestRulesArtifactId(), getTestRulesArtifactUpdateVersion()));
      }

      private void addOrUpdateAndroidxRulesDependency() {
        for (ArtifactDependencyModel artifact : gradleBuildModel.dependencies().artifacts()) {
          if (isMatchingArtifact(artifact, GoogleMavenArtifactId.ANDROIDX_TEST_RULES)) {
            artifact.version().setValue(getAndroidxRulesVersion());
            return;
          }
        }
        gradleBuildModel.dependencies().addArtifact(
          ANDROID_TEST_IMPLEMENTATION, createArtifactDependencySpec(GoogleMavenArtifactId.ANDROIDX_TEST_RULES, getAndroidxRulesVersion()));
      }

      private void addOrUpdateEspressoContribDependency() {
        for (ArtifactDependencyModel artifact : gradleBuildModel.dependencies().artifacts()) {
          if (isMatchingArtifact(artifact, getEspressoContribArtifactId())) {
            artifact.version().setValue(getEspressoArtifactUpdateVersion());
            return;
          }
        }
        if (myUsesAndroidxDependency) {
          // No need to add excludes for more recent (e.g., androidx) dependency versions.
          gradleBuildModel.dependencies().addArtifact(ANDROID_TEST_IMPLEMENTATION,
                                                      createArtifactDependencySpec(GoogleMavenArtifactId.ANDROIDX_ESPRESSO_CONTRIB,
                                                                                   getAndroidxEspressoCoreVersion()));
        } else {
          gradleBuildModel.dependencies().addArtifact(ANDROID_TEST_IMPLEMENTATION,
                                                      createArtifactDependencySpec(GoogleMavenArtifactId.ESPRESSO_CONTRIB, getEspressoCoreVersion()),
                                                      ESPRESSO_CONTRIB_EXCLUDES);
        }
      }

      private boolean isMatchingArtifact(ArtifactDependencyModel artifact, GoogleMavenArtifactId artifactId) {
        return artifactId.getMavenGroupId().equals(artifact.group().toString())
               && artifactId.getMavenArtifactId().equals(artifact.name().forceString());
      }
    }.queue();
  }

  private TestRecorderAssertion buildAssertionForCurrentSelection() {
    UiNode node = (UiNode)myAssertionElementComboBox.getSelectedItem();
    String rule = EXISTS;
    Object assertionRuleSelectedItem = myAssertionRuleComboBox.getSelectedItem();
    if (assertionRuleSelectedItem != null) {
      rule = assertionRuleSelectedItem.toString();
    }

    TestRecorderAssertion assertion = new TestRecorderAssertion(rule);
    addElementDescriptors(assertion, node);

    if (TEXT_IS.equals(rule)) {
      assertion.setText(myAssertionTextField.getText());
    }

    return assertion;
  }

  private void addElementDescriptors(TestRecorderAssertion assertion, UiNode node) {
    if (node == null || assertion.getElementDescriptorsCount() >= TestRecorderSettings.getInstance().ASSERTION_DEPTH) {
      return;
    }

    String className = getClassName(node);
    String resourceId = getResourceId(node);
    String text = getText(node);
    String contentDescription = getContentDescription(node);
    int viewGroupChildPosition = getViewGroupChildPosition(node);

    // TODO: Not sure how to properly handle RecyclerView and AdapterView child positions given that assertions are added for the visible
    // node hierarchy.
    if (!className.isEmpty() || !resourceId.isEmpty() || !text.isEmpty() || !contentDescription.isEmpty() || viewGroupChildPosition != -1) {
      assertion
        .addElementDescriptor(new ElementDescriptor(className, -1, -1, viewGroupChildPosition, resourceId, contentDescription, text));
      if (node.getParent() instanceof UiNode) {
        addElementDescriptors(assertion, (UiNode)node.getParent());
      }
    }
  }

  // Set up assertion panel with no element selected.
  protected void setUpEmptyAssertionPanel() {
    myAssertionElementComboBox.setModel(myElementComboBoxModel);
    myAssertionElementComboBox.setSelectedIndex(0);
    myAssertionElementComboBox.setForeground(JBColor.BLACK);

    myAssertionRuleComboBox.setModel(new DefaultComboBoxModel());

    // Hide assertion text field.
    CardLayout cardLayout = (CardLayout) myTextFieldWrapper.getLayout();
    cardLayout.show(myTextFieldWrapper, "myPlaceHolder");

    // Disable save assertion buttons.
    mySaveAssertionButton.setEnabled(false);
    mySaveAssertionAndAddAnotherButton.setEnabled(false);
  }

  protected void setUpAssertionPanel(BasicTreeNode node) {
    myAssertionElementComboBox.setModel(myElementComboBoxModel);
    myAssertionElementComboBox.setSelectedItem(node);
    myAssertionElementComboBox.setForeground(JBColor.BLACK);

    mySaveAssertionButton.setEnabled(true);
    mySaveAssertionAndAddAnotherButton.setEnabled(true);

    CardLayout cardLayout = (CardLayout) myTextFieldWrapper.getLayout();
    if (isTextView(node)) {
      cardLayout.show(myTextFieldWrapper, "myAssertionTextField");
      myAssertionTextField.setText(getText(node));
      myAssertionRuleComboBox.setModel(new DefaultComboBoxModel(ASSERTION_RULES_WITH_TEXT));
    } else {
      cardLayout.show(myTextFieldWrapper, "myPlaceHolder");
      myAssertionRuleComboBox.setModel(new DefaultComboBoxModel(ASSERTION_RULES_WITHOUT_TEXT));
    }
  }

  public boolean isAssertionMode() {
    return myAssertionMode;
  }

  @Override
  // Listen to debugger events and update action list.
  public void onEvent(final TestRecorderEvent event) {
    // Ignore not supported events.
    if (!SUPPORTED_EVENTS.contains(event.getEventType())) {
      return;
    }
    // Add event to action list.
    SwingUtilities.invokeLater(new Runnable() {
      @Override
      public void run() {
        // It it is first element, add it anyway
        if (myActionListModel.isEmpty()) {
          myActionListModel.addElement(event);
        } else {
          ElementAction lastAction = myActionListModel.lastElement();
          // If can merge with the last action, replace last action with the merged one.
          if (lastAction instanceof TestRecorderEvent && ((TestRecorderEvent)lastAction).canMerge(event)) {
            ((TestRecorderEvent)lastAction).merge(event);
            // Repaint is needed since otherwise the change would not be picked up by the renderer.
            myActionList.repaint();
          } else {
            myActionListModel.addElement(event);
          }
        }
        // Scroll action list so that the last action is visible.
        myActionList.ensureIndexIsVisible(myActionList.getItemsCount() - 1);
      }
    });
  }
}
