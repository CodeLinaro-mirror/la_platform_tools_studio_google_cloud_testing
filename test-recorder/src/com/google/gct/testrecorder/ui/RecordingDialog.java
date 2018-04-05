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

import com.android.annotations.VisibleForTesting;
import com.android.builder.model.level2.Library;
import com.android.ddmlib.IDevice;
import com.android.ide.common.gradle.model.level2.IdeDependencies;
import com.android.ide.common.repository.GradleCoordinate;
import com.android.ide.common.repository.GradleVersion;
import com.android.tools.analytics.UsageTracker;
import com.android.tools.idea.gradle.dsl.api.GradleBuildModel;
import com.android.tools.idea.gradle.dsl.api.android.AndroidModel;
import com.android.tools.idea.gradle.dsl.api.dependencies.ArtifactDependencyModel;
import com.android.tools.idea.gradle.dsl.api.dependencies.ArtifactDependencySpec;
import com.android.tools.idea.gradle.project.model.AndroidModuleModel;
import com.android.tools.idea.gradle.project.sync.GradleSyncInvoker;
import com.android.tools.idea.gradle.util.GradleUtil;
import com.android.tools.idea.projectsystem.GoogleMavenArtifactId;
import com.android.tools.idea.run.ApkProviderUtil;
import com.android.uiautomator.UiAutomatorModel;
import com.android.uiautomator.tree.BasicTreeNode;
import com.android.uiautomator.tree.UiNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.gct.testrecorder.codegen.TestCodeGenerator;
import com.google.gct.testrecorder.event.ElementDescriptor;
import com.google.gct.testrecorder.event.TestRecorderAssertion;
import com.google.gct.testrecorder.event.TestRecorderEvent;
import com.google.gct.testrecorder.event.TestRecorderEventListener;
import com.google.gct.testrecorder.settings.TestRecorderSettings;
import com.google.gct.testrecorder.util.StringHelper;
import com.google.gson.*;
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
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.psi.PsiClass;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBDimension;
import com.intellij.util.ui.JBUI;
import com.sun.jdi.request.BreakpointRequest;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.android.dom.manifest.Manifest;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.image.BufferedImage;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

import static com.android.tools.idea.gradle.dsl.api.dependencies.CommonConfigurationNames.ANDROID_TEST_COMPILE;
import static com.android.tools.idea.gradle.dsl.api.ext.GradlePropertyModel.STRING_TYPE;
import static com.google.gct.testrecorder.event.TestRecorderAssertion.*;
import static com.google.gct.testrecorder.event.TestRecorderEvent.SUPPORTED_EVENTS;
import static com.google.gct.testrecorder.ui.TestRecorderAction.TEST_RECORDER_ICON;
import static com.google.gct.testrecorder.util.ClassHelper.getInternalName;
import static com.google.gct.testrecorder.util.ImageHelper.rotateImage;
import static com.google.gct.testrecorder.util.UiAutomatorNodeHelper.*;
import static com.google.wireless.android.sdk.stats.GradleSyncStats.Trigger.TRIGGER_PROJECT_MODIFIED;
import static org.apache.commons.lang.StringUtils.isEmpty;

public class RecordingDialog extends DialogWrapper implements TestRecorderEventListener {
  private static final long ANIMATION_INTERVAL = 400; // milliseconds.
  private static final int ANIMATION_TIMER_INTERVAL = 10; // milliseconds.

  private static final String ESPRESSO_CORE_CUSTOM_ARTIFACT_NAME = "espresso";
  private static final String ESPRESSO_CORE_CUSTOM_GROUP_NAME = "com.jakewharton.espresso";

  public static final String TEST_INSTRUMENTATION_RUNNER = "android.support.test.runner.AndroidJUnitRunner";

  /** The minimal version of Espresso in build.gradle that does not require updating. */
  private static final GradleVersion MIN_ESPRESSO_VERSION = GradleVersion.parse("2.2.2");

  /** Version of Espresso added/updated in build.gradle, when missing or obsolete. */
  public static final String ESPRESSO_VERSION = "3.0.1";

  public static final ImmutableList<ArtifactDependencySpec> ESPRESSO_EXCLUDES =
    ImmutableList.of(ArtifactDependencySpec.create(GoogleMavenArtifactId.SUPPORT_ANNOTATIONS, null));

  public static final ImmutableList<ArtifactDependencySpec> ESPRESSO_CONTRIB_EXCLUDES =
    ImmutableList.of(ArtifactDependencySpec.create(GoogleMavenArtifactId.SUPPORT_ANNOTATIONS, null),
                     ArtifactDependencySpec.create(GoogleMavenArtifactId.SUPPORT_V4, null),
                     ArtifactDependencySpec.create(GoogleMavenArtifactId.DESIGN, null),
                     ArtifactDependencySpec.create(GoogleMavenArtifactId.RECYCLERVIEW_V7, null));

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
  private final DefaultListModel myEventListModel;
  /** Shows whether recording is in progress. */
  private boolean myIsRecording = true;
  private boolean myWasEverPaused = false;

  private JPanel myRootPanel;
  private ScreenshotPanel myScreenshotPanel;
  private JPanel myEventListPanel;
  private JBScrollPane myScrollPane;
  private JBList myEventList;
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
    super(facet.getModule().getProject());
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

    myEventList.setEmptyText("No events recorded yet.");
    myEventListModel = new DefaultListModel();
    myEventList.setModel(myEventListModel);
    myEventList.setCellRenderer(new TestRecorderListRenderer());

    if (!myIsRecordingTest) {
      // No need for adding assertions and screenshots while recording a Robo script.
      myAssertionPanel.setVisible(false);
      // Recording a Robo script does not support snippets.
      myRecordPauseButton.setVisible(false);
    } else {
      myRecordPauseButton.setVisible(TestRecorderSettings.getInstance().ENABLE_TEST_FRAGMENT_RECORDING);
    }

    myRecordPauseButton.setIcon(AllIcons.Debugger.ThreadStates.Paused);

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
            myAssertionIndex = myEventListModel.size();

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
        myEventListModel.add(myAssertionIndex, buildAssertionForCurrentSelection());
        // Scroll event list so that assertion is visible
        myEventList.ensureIndexIsVisible(myAssertionIndex);
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

  public void setDebuggerSession(DebuggerSession debuggerSession) {
    myDebuggerSession = debuggerSession;
    myIsRecording = myDebuggerSession != null;
    myRecordPauseButton.setEnabled(myIsRecording);
    updateRecordPauseButton();
  }

  private void updateRecordPauseButton() {
    if (myIsRecording) {
      myRecordPauseButton.setText("Pause");
      myRecordPauseButton.setIcon(AllIcons.Debugger.ThreadStates.Paused);
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

  @VisibleForTesting
  static String getJsonForEvents(Project project, List<Object> events) {
    // Consider only TestRecorderEvents.
    List<TestRecorderEvent> testRecorderEvents = new ArrayList<>();
    for (Object event : events) {
      if (event instanceof TestRecorderEvent) {
        testRecorderEvents.add((TestRecorderEvent)event);
      }
    }

    GsonBuilder gsonBuilder = new GsonBuilder();
    gsonBuilder.registerTypeAdapter(ElementDescriptor.class, new ElementDescriptorSerializer(project));
    return gsonBuilder.setPrettyPrinting().create().toJson(testRecorderEvents);
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
      Module testClassModule = chooser.getTestClassModule();

      //Similarly, compute resource package name and application id before the potential Gradle confusion.
      String resourcePackageName = "unknown";
      AndroidFacet testClassFacet = AndroidFacet.getInstance(testClassModule);
      if (testClassFacet !=  null) {
        Manifest manifest = testClassFacet.getManifest();
        if (manifest != null) {
          resourcePackageName = manifest.getPackage().getStringValue();
        }
      }
      String applicationId = getApplicationId(resourcePackageName);

      // Automatically check/setup Espresso dependencies for Gradle projects only.
      GradleBuildModel gradleBuildModel = GradleBuildModel.get(testClassModule);
      AndroidModuleModel androidModuleModel = AndroidModuleModel.get(testClassModule);
      if (gradleBuildModel != null && androidModuleModel != null) {
        AndroidModel androidModel = gradleBuildModel.android();
        // androidModel will be null when the Gradle experimental plugin is used and it's not possible to update the instrumentation runner.
        // TODO: Provide an appropriate error message or some alternative way to update instrumentation runner when the Gradle experimental
        // plugin is used.
        if (androidModel != null && !hasAllRequiredEspressoDependencies(androidModel, androidModuleModel)) {
          UsageTracker.getInstance().log(AndroidStudioEvent.newBuilder()
                                           .setCategory(EventCategory.TEST_RECORDER)
                                           .setKind(EventKind.TEST_RECORDER_MISSING_ESPRESSO_DEPENDENCIES));

          if (Messages.showDialog(myProject,
                                  "Some dependencies for running Espresso tests are missing or obsolete.\n" +
                                  "Would you like to automatically add/update Espresso dependencies for this app?\n" +
                                  "To complete the set up, Gradle might ask you to install the missing libraries.\n" +
                                  "Please click on the corresponding link(s) to install them.",
                                  "Missing or obsolete Espresso dependencies",
                                  new String[]{Messages.NO_BUTTON, Messages.YES_BUTTON}, 1, null) != 0) {
            setupEspresso(gradleBuildModel, androidModuleModel);
          }
        }
      }

      PsiClass testClass = chooser.getTestClass();

      if (testClass != null) {
        super.doOKAction();
        new TestCodeGenerator(resourcePackageName, applicationId, testClassModule, testClass, getAllModelEvents(), myLaunchedActivityName,
                              myWasEverPaused, chooser.isKotlinTestClass()).generate();
      }
    } else {
      FileSaverDescriptor descriptor = new FileSaverDescriptor("Save Robo Script", "Save Robo script to a file", "json");
      FileSaverDialogImpl fileSaverDialog = new FileSaverDialogImpl(descriptor, myProject);
      VirtualFileWrapper fileWrapper = fileSaverDialog.save(null, StringHelper.getClassName(myLaunchedActivityName) + "_robo_script");

      if (fileWrapper != null) {
        try {
          FileUtils.write(fileWrapper.getFile(), getJsonForEvents(myProject, getAllModelEvents()));
        } catch (Exception ex) {
          String message = isEmpty(ex.getMessage()) ? "Unknown error" : ex.getMessage();
          Messages.showDialog(myProject, message, "Could not save Robo script to a file", new String[]{"OK"}, 0, null);
        }
      }

      if (fileSaverDialog.isOK()) {
        UsageTracker.getInstance().log(AndroidStudioEvent.newBuilder()
                                         .setCategory(EventCategory.TEST_RECORDER)
                                         .setKind(EventKind.TEST_RECORDER_SAVE_ROBO_SCRIPT));
        super.doOKAction();
      }
    }
  }

  private String getApplicationId(String defaultId) {
    try {
      return ApkProviderUtil.computePackageName(myFacet);
    } catch (Exception e) {
      return defaultId;
    }
  }

  private List<Object> getAllModelEvents() {
    List<Object> events = new ArrayList<>();
    for (int i = 0; i < myEventListModel.size(); i++) {
      events.add(myEventListModel.get(i));
    }
    return events;
  }

  private void exitAssertionMode(boolean shouldAddAssertion) {
    myAssertionMode = false;
    getRootPane().setDefaultButton(getButton(getOKAction()));
    // Display button panel.
    CardLayout cardLayout = (CardLayout) myAssertionPanel.getLayout();
    cardLayout.show(myAssertionPanel, "myButtonsPanel");

    if (shouldAddAssertion) {
      // Add the new assertion at its remembered index.
      myEventListModel.add(myAssertionIndex, buildAssertionForCurrentSelection());
      // Scroll event list so that assertion is visible.
      myEventList.ensureIndexIsVisible(myAssertionIndex);
    } else {
      // Scroll event list so that the last event is visible.
      myEventList.ensureIndexIsVisible(myEventListModel.size() - 1);
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
        if (elapsed > ANIMATION_INTERVAL) {
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
        if (elapsed > ANIMATION_INTERVAL) {
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

  private boolean hasAllRequiredEspressoDependencies(@NotNull AndroidModel androidModel, @NotNull AndroidModuleModel androidModuleModel) {
    // TODO: To improve performance, consider doing these checks in a single pass.
    return hasUptodateEspressoCoreDependency(androidModuleModel)
           && (!needsEspressoContribDependency() || hasUptodateEspressoContribDependency(androidModuleModel))
           && hasSetInstrumentationRunner(androidModel);
  }

  private boolean needsEspressoContribDependency() {
    for (int i = 0; i < myEventListModel.size(); i++) {
      Object event = myEventListModel.get(i);
      if (event instanceof TestRecorderEvent && ((TestRecorderEvent)event).getElementRecyclerViewChildPosition() != -1) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasUptodateEspressoCoreDependency(@NotNull AndroidModuleModel androidModuleModel) {
    String artifact = GoogleMavenArtifactId.ESPRESSO_CORE.toString();
    return hasUptodateEspressoDependency(androidModuleModel, artifact);
  }

  private static boolean hasUptodateEspressoContribDependency(@NotNull AndroidModuleModel androidModuleModel) {
    String artifact = GoogleMavenArtifactId.ESPRESSO_CONTRIB.toString();
    return hasUptodateEspressoDependency(androidModuleModel, artifact);
  }

  private static boolean hasSetInstrumentationRunner(@NotNull AndroidModel androidModel) {
    String testInstrumentationRunner = androidModel.defaultConfig().testInstrumentationRunner().getValue(STRING_TYPE);
    return testInstrumentationRunner != null && !testInstrumentationRunner.isEmpty();
  }

  private static boolean hasUptodateEspressoDependency(@NotNull AndroidModuleModel androidModuleModel, String artifact) {
    GradleVersion dependencyVersion = getDependencyVersion(androidModuleModel, artifact);
    return dependencyVersion != null && dependencyVersion.compareTo(MIN_ESPRESSO_VERSION) >= 0;
  }

  @Nullable
  private static GradleVersion getDependencyVersion(@NotNull AndroidModuleModel androidModuleModel, String artifact) {
    Collection<Library> libraries = Lists.newArrayList();
    IdeDependencies androidTestCompileDependencies = androidModuleModel.getSelectedAndroidTestCompileDependencies();
    if (androidTestCompileDependencies != null) {
      libraries.addAll(androidTestCompileDependencies.getAndroidLibraries());
    }
    libraries.addAll(androidModuleModel.getSelectedMainCompileLevel2Dependencies().getAndroidLibraries());

    for (Library library : libraries) {
      if (GradleUtil.dependsOn(library, artifact)) {
        GradleCoordinate coordinate = GradleCoordinate.parseCoordinateString(library.getArtifactAddress());
        return coordinate != null ? coordinate.getVersion() : null;
      }
    }

    return null;
  }

  private void setupEspresso(@NotNull GradleBuildModel gradleBuildModel, @NotNull AndroidModuleModel androidModuleModel) {
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
          if (!hasUptodateEspressoCoreDependency(androidModuleModel)) {
            addOrUpdateEspressoCoreDependency();
          }

          if (needsEspressoContribDependency() && !hasUptodateEspressoContribDependency(androidModuleModel)) {
            addOrUpdateEspressoContribDependency();
          }

          AndroidModel androidModel = gradleBuildModel.android();
          if (androidModel != null && !hasSetInstrumentationRunner(androidModel)) {
            androidModel.defaultConfig().testInstrumentationRunner().setValue(TEST_INSTRUMENTATION_RUNNER);
          }

          gradleBuildModel.applyChanges();

          GradleSyncInvoker.getInstance().requestProjectSyncAndSourceGeneration(myProject, TRIGGER_PROJECT_MODIFIED);
        });
      }

      private void addOrUpdateEspressoCoreDependency() {
        boolean hasUpdatedEspressoCoreVersion = false;
        for (ArtifactDependencyModel artifact : gradleBuildModel.dependencies().artifacts()) {
          if (ESPRESSO_CORE_CUSTOM_GROUP_NAME.equals(artifact.group().value())
              && ESPRESSO_CORE_CUSTOM_ARTIFACT_NAME.equals(artifact.name().value())) {
            // Remove the obsolete custom Espresso dependency.
            gradleBuildModel.dependencies().remove(artifact);
          } else if (isMatchingArtifact(artifact, GoogleMavenArtifactId.ESPRESSO_CORE)) {
            artifact.setVersion(ESPRESSO_VERSION);
            hasUpdatedEspressoCoreVersion = true;
          } else if (isMatchingArtifact(artifact, GoogleMavenArtifactId.ESPRESSO_CONTRIB)) {
            // Update Espresso contrib dependency, if present, to match Espresso core dependency version.
            artifact.setVersion(ESPRESSO_VERSION);
          }
        }
        if (!hasUpdatedEspressoCoreVersion) {
          gradleBuildModel.dependencies().addArtifact(ANDROID_TEST_COMPILE,
                                                      ArtifactDependencySpec.create(GoogleMavenArtifactId.ESPRESSO_CORE, ESPRESSO_VERSION),
                                                      ESPRESSO_EXCLUDES);
        }
      }

      private void addOrUpdateEspressoContribDependency() {
        for (ArtifactDependencyModel artifact : gradleBuildModel.dependencies().artifacts()) {
          if (isMatchingArtifact(artifact, GoogleMavenArtifactId.ESPRESSO_CONTRIB)) {
            artifact.setVersion(ESPRESSO_VERSION);
            return;
          }
        }
        gradleBuildModel.dependencies().addArtifact(ANDROID_TEST_COMPILE,
                                                    ArtifactDependencySpec.create(GoogleMavenArtifactId.ESPRESSO_CONTRIB, ESPRESSO_VERSION),
                                                    ESPRESSO_CONTRIB_EXCLUDES);
      }

      private boolean isMatchingArtifact(ArtifactDependencyModel artifact, GoogleMavenArtifactId artifactId) {
        return artifactId.getMavenGroupId().equals(artifact.group().value())
               && artifactId.getMavenArtifactId().equals(artifact.name().value());
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
    // Use text identification only for text views.
    String text = isTextView(node) ? getText(node) : "";
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
  // Listen to debugger event and update event list.
  public void onEvent(final TestRecorderEvent event) {
    // Ignore not supported events.
    if (!SUPPORTED_EVENTS.contains(event.getEventType())) {
      return;
    }
    // Add event to list
    SwingUtilities.invokeLater(new Runnable() {
      @Override
      public void run() {
        // It it is first element, add it anyway
        if (myEventListModel.isEmpty()) {
          myEventListModel.addElement(event);
        } else {
          Object lastEvent = myEventListModel.lastElement();
          // If can merge with last event, replace last event with the merged one.
          if (lastEvent instanceof TestRecorderEvent && ((TestRecorderEvent)lastEvent).canMerge(event)) {
            ((TestRecorderEvent)lastEvent).merge(event);
            // Repaint is needed since otherwise the change would not be picked up by the renderer.
            myEventList.repaint();
          } else {
            myEventListModel.addElement(event);
          }
        }
        // Scroll event list so that the last event is visible
        myEventList.ensureIndexIsVisible(myEventList.getItemsCount() - 1);
      }
    });
  }
}
