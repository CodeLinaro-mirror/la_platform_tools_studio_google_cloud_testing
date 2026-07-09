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

import static com.google.gct.testrecorder.event.TestRecorderAssertion.ASSERTION_RULES_WITHOUT_TEXT;
import static com.google.gct.testrecorder.event.TestRecorderAssertion.ASSERTION_RULES_WITH_TEXT;
import static com.google.gct.testrecorder.event.TestRecorderAssertion.EXISTS;
import static com.google.gct.testrecorder.event.TestRecorderAssertion.TEXT_IS;
import static com.google.gct.testrecorder.event.TestRecorderEvent.SUPPORTED_EVENTS;
import static com.google.gct.testrecorder.ui.TestRecorderAction.TEST_RECORDER_ICON;
import static com.google.gct.testrecorder.util.ClassHelper.getInternalName;
import static com.google.gct.testrecorder.util.GenerateTestHelperKt.getApplicationId;
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

import com.android.annotations.VisibleForTesting;
import com.android.ddmlib.IDevice;
import com.android.tools.analytics.UsageTracker;
import com.android.tools.analytics.UsageTrackerUtils;
import com.android.uiautomator.tree.BasicTreeNode;
import com.android.uiautomator.tree.UiNode;
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
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.ex.FileSaverDialogImpl;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.xml.util.XmlStringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.psi.PsiDirectory;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.intellij.util.ui.JBDimension;
import com.intellij.util.ui.JBUI;
import com.sun.jdi.request.BreakpointRequest;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
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
import javax.swing.border.TitledBorder;
import org.apache.commons.io.FileUtils;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class RecordingDialog extends DialogWrapper implements TestRecorderEventListener {
  private static final String TEST_RECORDING_DIALOG_TITLE = "Record Your Test";
  private static final String SCRIPT_RECORDING_DIALOG_TITLE = "Record Your Robo Script";
  private static final String DEFAULT_MESSAGE = "Select an element from screenshot";

  private final Project myProject;
  private final AndroidFacet myFacet;
  private final IDevice myDevice;
  private final String myPackageName;
  private final String myLaunchedActivityName;
  private final boolean myIsRecordingTest;
  private String myTestClassName;
  private PsiDirectory myTestClassParent;
  private Module myTestClassModule;
  private String mySelectedLanguage;

  private DebuggerSession myDebuggerSession;
  private boolean myAssertionMode;
  private int myAssertionIndex;
  private LinkedHashMap<BasicTreeNode, Integer> myNodeIndentMap;
  private DefaultComboBoxModel myElementComboBoxModel;
  private final DefaultListModel<ElementAction> myActionListModel;
  /**
   * Shows whether recording is in progress.
   */
  private boolean myIsRecording = true;
  private boolean myWasEverPaused = false;
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
  private CountDownLatch myCountDownLatch;

  public RecordingDialog(AndroidFacet facet,
                         IDevice device,
                         String packageName,
                         String launchedActivityName,
                         boolean isRecordingTest,
                         CountDownLatch latch) {
    super(facet.getModule().getProject(), true, IdeModalityType.MODELESS);
    setupUI();
    myProject = facet.getModule().getProject();
    myFacet = facet;
    myDevice = device;
    myPackageName = packageName;
    myLaunchedActivityName = launchedActivityName;
    myIsRecordingTest = isRecordingTest;
    myAssertionMode = false;
    myCountDownLatch = latch;

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
    }
    else {
      myRecordPauseButton.setVisible(TestRecorderSettings.getInstance().ENABLE_TEST_FRAGMENT_RECORDING);
    }

    myRecordPauseButton.setIcon(AllIcons.Actions.Pause);

    myRecordPauseButton.addActionListener(e -> {
      myWasEverPaused = true;
      myIsRecording = !myIsRecording;
      updateRecordPauseButton();
      toggleDebugging();
    });

    myAddAssertionButton.addActionListener(
      actionEvent -> new TestRecorderScreenshotTask(myProject, myDevice, myPackageName, (initialImage, model) -> {
        if (model == null) {
          Messages.showErrorDialog(myProject, "Failed to load UI hierarchy from device.", "Error");
          return;
        }
        myAssertionMode = true;
        getRootPane().setDefaultButton(mySaveAssertionAndAddAnotherButton);
        BasicTreeNode root = model.getXmlRootNode();
        if (root == null) {
          Messages.showErrorDialog(myProject, "Failed to parse UI hierarchy from device.", "Error");
          return;
        }
        String applicationId = getApplicationId(myFacet, "");
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
      }).queue());

    // TODO: take screenshot in Espresso test code
    myTakeScreenshotButton.addActionListener(actionEvent -> {

    });

    mySaveAssertionButton.addActionListener(actionEvent -> {
      exitAssertionMode(true);
      hideScreenshotPanel();
    });

    mySaveAssertionAndAddAnotherButton.addActionListener(actionEvent -> {
      // Add the new assertion at its remembered index.
      myActionListModel.add(myAssertionIndex, buildAssertionForCurrentSelection());
      // Scroll action list so that assertion is visible.
      myActionList.ensureIndexIsVisible(myAssertionIndex);
      myAssertionIndex++;
    });

    myCancelButton.addActionListener(actionEvent -> {
      exitAssertionMode(false);
      hideScreenshotPanel();
    });

    myAssertionElementComboBox.addItemListener(itemEvent -> {
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
        }
        else {
          CardLayout cardLayout = (CardLayout)myTextFieldWrapper.getLayout();
          cardLayout.show(myTextFieldWrapper, "myPlaceHolder");
          myAssertionRuleComboBox.setModel(new DefaultComboBoxModel(ASSERTION_RULES_WITHOUT_TEXT));
        }
        // Enable save assertion buttons.
        mySaveAssertionButton.setEnabled(true);
        mySaveAssertionAndAddAnotherButton.setEnabled(true);
        myAssertionTextField.setForeground(JBColor.BLACK);
      }
      else {
        // selected element is not UI element (default element)
        myScreenshotPanel.clearSelectionAndRepaint();
      }
    });

    myAssertionElementComboBox.setRenderer(new AssertionElementRenderer(() -> myNodeIndentMap));

    myAssertionRuleComboBox.addItemListener(itemEvent -> {
      Object selectedItem = myAssertionRuleComboBox.getSelectedItem();
      if (selectedItem == null) {
        return;
      }

      String rule = selectedItem.toString();
      CardLayout cardLayout = (CardLayout)myTextFieldWrapper.getLayout();
      if (TEXT_IS.equals(rule)) {
        // Display assertion text field when rule is "text ***"
        cardLayout.show(myTextFieldWrapper, "myAssertionTextField");
      }
      else {
        // Otherwise (exists, does not exist), don't display assertion text field
        cardLayout.show(myTextFieldWrapper, "myPlaceHolder");
      }
    });
  }

  public void setDebuggerSession(DebuggerSession debuggerSession) {
    myDebuggerSession = debuggerSession;
    myIsRecording = myDebuggerSession != null;
    myRecordPauseButton.setEnabled(myIsRecording);
    updateRecordPauseButton();
  }

  public String getTestClassName() {
    return myTestClassName;
  }

  public PsiDirectory getTestClassParent() {
    return myTestClassParent;
  }

  public JPanel getRootPanel() {
    return myRootPanel;
  }

  public List<ElementAction> getAllModelActions() {
    return Collections.list(myActionListModel.elements());
  }

  public String getLaunchedActivityName() {
    return myLaunchedActivityName;
  }

  public Boolean getWasEverPaused() {
    return myWasEverPaused;
  }

  public String getSelectedLanguage() {
    return mySelectedLanguage;
  }

  private void updateRecordPauseButton() {
    if (myIsRecording) {
      myRecordPauseButton.setText("Pause");
      myRecordPauseButton.setIcon(AllIcons.Actions.Pause);
    }
    else {
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

  @Override
  public void doCancelAction() {
    if (myCountDownLatch.getCount() > 0) {
      this.myCountDownLatch.countDown();
    }
    super.doCancelAction();
  }

  private void setupUI() {
    createUIComponents();
    myRootPanel = new JPanel();
    myRootPanel.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), 0, 0));
    myRecordingPanel = new JPanel();
    myRecordingPanel.setLayout(new GridLayoutManager(2, 1, new Insets(0, 0, 0, 0), 0, 0));
    myRootPanel.add(myRecordingPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                                                          GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                                          GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null,
                                                          new Dimension(596, 306), null, 0, false));
    myActionListPanel = new JPanel();
    myActionListPanel.setLayout(new GridLayoutManager(2, 1, new Insets(0, 0, 0, 0), 0, 0));
    myRecordingPanel.add(myActionListPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                                                                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                                                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                                                null, new Dimension(539, 132), null, 0, false));
    myScrollPane = new JBScrollPane();
    myActionListPanel.add(myScrollPane, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                                                            GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                                            GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                                            null, new Dimension(238, 128), null, 0, false));
    myActionList = new JBList();
    myActionList.setSelectionMode(0);
    myScrollPane.setViewportView(myActionList);
    myRecordPauseButton = new JButton();
    myRecordPauseButton.setHideActionText(false);
    myRecordPauseButton.setText("Pause");
    myActionListPanel.add(myRecordPauseButton,
                          new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                                              GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                              GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(0, 40), null, 0, false));
    myAssertionPanel = new JPanel();
    myAssertionPanel.setLayout(new CardLayout(0, 0));
    myRecordingPanel.add(myAssertionPanel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_SOUTH, GridConstraints.FILL_HORIZONTAL,
                                                               GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                                               GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(539, 150), null, 0,
                                                               false));
    myButtonsPanel = new JPanel();
    myButtonsPanel.setLayout(new GridLayoutManager(3, 5, new Insets(0, 0, 0, 0), 0, 0));
    myAssertionPanel.add(myButtonsPanel, "myButtonsPanel");
    myAddAssertionButton = new JButton();
    myAddAssertionButton.setText("Add Assertion");
    myButtonsPanel.add(myAddAssertionButton, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_EAST, GridConstraints.FILL_NONE,
                                                                 GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null,
                                                                 null, null, 0, false));
    myTakeScreenshotButton = new JButton();
    myTakeScreenshotButton.setText("Take Screenshot");
    myButtonsPanel.add(myTakeScreenshotButton,
                       new GridConstraints(1, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                                           GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                           GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    final Spacer spacer1 = new Spacer();
    myButtonsPanel.add(spacer1, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL, 1,
                                                    GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
    final JPanel panel1 = new JPanel();
    panel1.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), 0, 0));
    myButtonsPanel.add(panel1, new GridConstraints(0, 0, 1, 5, GridConstraints.ANCHOR_NORTH, GridConstraints.FILL_HORIZONTAL,
                                                   GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                                   GridConstraints.SIZEPOLICY_FIXED, new Dimension(0, 5), new Dimension(0, 5),
                                                   new Dimension(0, 5), 0, false));
    myEditAssertionPanel = new JPanel();
    myEditAssertionPanel.setLayout(new GridLayoutManager(4, 1, new Insets(0, 0, 0, 0), 0, 0));
    myAssertionPanel.add(myEditAssertionPanel, "myEditAssertionPanel");
    myEditAssertionPanel.setBorder(
      IdeBorderFactory.PlainSmallWithIndent.createTitledBorder(BorderFactory.createEtchedBorder(), "Edit assertion",
                                                               TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null,
                                                               null));
    myAssertionRuleComboBox = new JComboBox();
    myEditAssertionPanel.add(myAssertionRuleComboBox,
                             new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                                                 GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null,
                                                 new Dimension(230, 25), null, 0, false));
    final JPanel panel2 = new JPanel();
    panel2.setLayout(new GridLayoutManager(1, 4, new Insets(0, 0, 0, 0), 0, 0));
    myEditAssertionPanel.add(panel2, new GridConstraints(3, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                                                         GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                                         GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null,
                                                         new Dimension(230, 35), null, 0, false));
    mySaveAssertionButton = new JButton();
    mySaveAssertionButton.setText("Save Assertion");
    panel2.add(mySaveAssertionButton, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                                                          GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                                          GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    final Spacer spacer2 = new Spacer();
    panel2.add(spacer2, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                                            GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
    mySaveAssertionAndAddAnotherButton = new JButton();
    mySaveAssertionAndAddAnotherButton.setText("Save and Add Another");
    panel2.add(mySaveAssertionAndAddAnotherButton,
               new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                                   GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                   GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    myCancelButton = new JButton();
    myCancelButton.setText("Cancel");
    panel2.add(myCancelButton, new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                                                   GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                                   GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    myTextFieldWrapper = new JPanel();
    myTextFieldWrapper.setLayout(new CardLayout(0, 0));
    myEditAssertionPanel.add(myTextFieldWrapper, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                                                                     GridConstraints.SIZEPOLICY_CAN_SHRINK |
                                                                     GridConstraints.SIZEPOLICY_CAN_GROW,
                                                                     GridConstraints.SIZEPOLICY_CAN_SHRINK |
                                                                     GridConstraints.SIZEPOLICY_CAN_GROW, null, new Dimension(230, 24),
                                                                     null, 0, false));
    myAssertionTextField = new JTextField();
    myTextFieldWrapper.add(myAssertionTextField, "myAssertionTextField");
    final JPanel panel3 = new JPanel();
    panel3.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), 0, 0));
    myTextFieldWrapper.add(panel3, "myPlaceHolder");
    myAssertionElementComboBox = new JComboBox();
    myEditAssertionPanel.add(myAssertionElementComboBox,
                             new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                                                 GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null,
                                                 new Dimension(230, 25), null, 0, false));
    myRootPanel.add(myScreenshotPanel, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_EAST, GridConstraints.FILL_VERTICAL,
                                                           GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null,
                                                           null, 0, false));
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

      TestClassNameInputDialog.EnvironmentResult environment =
        ProgressManager.getInstance().run(new Task.WithResult<TestClassNameInputDialog.EnvironmentResult, RuntimeException>(myProject, "Detecting test environment", true) {
          @Override
          protected TestClassNameInputDialog.EnvironmentResult compute(@NotNull ProgressIndicator indicator) {
            return TestClassNameInputDialog.performEnvironmentDetection(myFacet.getModule(), myLaunchedActivityName);
          }
        });

      if (environment == null) {
        Messages.showErrorDialog(myProject, "Could not detect or create the test source directory!", "Error");
        return;
      }

      VirtualFile testSourceDirectory = environment.testSourceDirectory;
      if (testSourceDirectory == null && environment.directoryToCreateParent != null) {
        testSourceDirectory = TestClassNameInputDialog.getOrCreateSubdirectoryOnEDT(
          environment.directoryToCreateParent, environment.subdirectoriesToCreate, true);
      }

      if (testSourceDirectory == null) {
        Messages.showErrorDialog(myProject, "Could not detect or create the test source directory!", "Error");
        return;
      }

      TestClassNameInputDialog chooser = new TestClassNameInputDialog(myFacet.getModule(), myLaunchedActivityName, testSourceDirectory, environment.defaultLanguageIndex);
      if (!chooser.showAndGet()) {
        return;
      }
      myTestClassName = chooser.getTestClassName();
      myTestClassParent = chooser.getTestClassParent();
      mySelectedLanguage = chooser.getSelectedLanguage();
      super.doOKAction();
      myCountDownLatch.countDown();
    }
    else {
      FileSaverDescriptor descriptor = new FileSaverDescriptor("Save Robo Script", "Save Robo script to a file", "json");
      FileSaverDialogImpl fileSaverDialog = new FileSaverDialogImpl(descriptor, myProject);
      VirtualFileWrapper fileWrapper =
        fileSaverDialog.save((VirtualFile)null, StringHelper.getClassName(myLaunchedActivityName) + "_robo_script");

      if (fileWrapper != null) {
        try {
          FileUtils.write(fileWrapper.getFile(), getJsonForActions(myProject, getAllModelActions()));
        }
        catch (Exception ex) {
          String message = StringUtil.isEmpty(ex.getMessage()) ? "Unknown error" : ex.getMessage();
          Messages.showMessageDialog(myRootPanel, message, "Could not save Robo script to a file", null);
        }
      }

      if (fileSaverDialog.isOK()) {
        UsageTracker.log(UsageTrackerUtils.withProjectId(
          AndroidStudioEvent.newBuilder()
            .setCategory(AndroidStudioEvent.EventCategory.TEST_RECORDER)
            .setKind(AndroidStudioEvent.EventKind.TEST_RECORDER_SAVE_ROBO_SCRIPT),
          myProject));
        super.doOKAction();
        myCountDownLatch.countDown();
      }
    }
  }

  private void exitAssertionMode(boolean shouldAddAssertion) {
    myAssertionMode = false;
    getRootPane().setDefaultButton(getButton(getOKAction()));
    // Display button panel.
    CardLayout cardLayout = (CardLayout)myAssertionPanel.getLayout();
    cardLayout.show(myAssertionPanel, "myButtonsPanel");

    if (shouldAddAssertion) {
      // Add the new assertion at its remembered index.
      myActionListModel.add(myAssertionIndex, buildAssertionForCurrentSelection());
      // Scroll action list so that assertion is visible.
      myActionList.ensureIndexIsVisible(myAssertionIndex);
    }
    else {
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
                           : (int)((double)(imageWidth * screenshotPanelTotalHeight) / imageHeight);

    // Cap panel width to not be greater than panel height.
    final int screenshotPanelTotalWidth = scaledImageWidth > screenshotPanelTotalHeight ? screenshotPanelTotalHeight : scaledImageWidth;


    myScreenshotPanel.setMinimumSize(new Dimension(screenshotPanelTotalWidth, screenshotPanelTotalHeight));
    myScreenshotPanel.clearSelectionAndRepaint();
    getWindow().pack();
    myAssertionElementComboBox.requestFocusInWindow();
  }

  private void hideScreenshotPanel() {
    final int screenshotPanelInitialWidth = myScreenshotPanel.getWidth();
    final int marginWidth = ((FlowLayout)myScreenshotPanel.getLayout()).getHgap() * 2;
    final int windowInitialWidth = getWindow().getWidth();

    myScreenshotPanel.setVisible(false);
    myScreenshotPanel.setMinimumSize(new Dimension(0, 0));
    getWindow().setMinimumSize(
      new Dimension(windowInitialWidth - screenshotPanelInitialWidth - marginWidth, getWindow().getHeight()));

    myScreenshotPanel.clearSelectionAndRepaint();
    getWindow().pack();
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
    CardLayout cardLayout = (CardLayout)myTextFieldWrapper.getLayout();
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

    CardLayout cardLayout = (CardLayout)myTextFieldWrapper.getLayout();
    if (isTextView(node)) {
      cardLayout.show(myTextFieldWrapper, "myAssertionTextField");
      myAssertionTextField.setText(getText(node));
      myAssertionRuleComboBox.setModel(new DefaultComboBoxModel(ASSERTION_RULES_WITH_TEXT));
    }
    else {
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
    SwingUtilities.invokeLater(() -> {
      // If it is first element, add it anyway
      if (myActionListModel.isEmpty()) {
        myActionListModel.addElement(event);
      }
      else {
        ElementAction lastAction = myActionListModel.lastElement();
        // If event can merge with the last action, replace last action with the merged one.
        if (lastAction instanceof TestRecorderEvent && ((TestRecorderEvent)lastAction).canMerge(event)) {
          ((TestRecorderEvent)lastAction).merge(event);
          // Repaint is needed since otherwise the change would not be picked up by the renderer.
          myActionList.repaint();
        }
        else {
          myActionListModel.addElement(event);
        }
      }
      // Scroll action list so that the last action is visible.
      myActionList.ensureIndexIsVisible(myActionList.getItemsCount() - 1);
    });
  }

  @VisibleForTesting
  static class AssertionElementRenderer extends DefaultListCellRenderer {
    private final Supplier<Map<BasicTreeNode, Integer>> nodeIndentMapSupplier;

    AssertionElementRenderer(Supplier<Map<BasicTreeNode, Integer>> nodeIndentMapSupplier) {
      this.nodeIndentMapSupplier = nodeIndentMapSupplier;
    }

    @Override
    public Component getListCellRendererComponent(JList list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
      if (value instanceof BasicTreeNode) {
        BasicTreeNode node = (BasicTreeNode)value;
        // Add indent.
        Map<BasicTreeNode, Integer> nodeIndentMap = nodeIndentMapSupplier.get();
        Integer indent = nodeIndentMap == null ? null : nodeIndentMap.get(node);
        String prefix = StringUtil.repeat("  ", indent == null ? 0 : indent);
        // No indent for selected element.
        if (index == -1) {
          prefix = "";
        }
        String resourceId = getResourceId(node);
        String nodeString = resourceId.isEmpty() ? getClassName(node) : resourceId;
        return super.getListCellRendererComponent(list, prefix + XmlStringUtil.escapeString(nodeString), index, isSelected, cellHasFocus);
      }
      else {
        // non UI element
        return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
      }
    }
  }
}
