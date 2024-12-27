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
package com.google.gct.testrecorder.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.NumberFormatter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class TestRecorderSettingsConfigurable implements SearchableConfigurable, Configurable.NoScroll {
  @NotNull private final TestRecorderSettings mySettings;

  private JPanel myPanel;
  private JSpinner myEvaluationDepthSpinner;
  private JSpinner myScrollDepthSpinner;
  private JSpinner myAssertionDepthSpinner;
  private JCheckBox myCapEvaluationDepthCheckBox;
  private JCheckBox myCleanBeforeStartCheckbox;
  private JCheckBox myCleanAfterFinishCheckbox;
  private JCheckBox myStopAppCheckbox;
  private JPanel myLaunchAndTearDownSettingsPanel;
  private JPanel myDataCollecationAndCodeGenerationSettingsPanel;
  private JCheckBox myUseTextForElementMatchingCheckBox;
  private JCheckBox myUseContentDescriptionForElementMatchingCheckBox;
  private JCheckBox myEnableTestFragmentRecordingCheckBox;

  public TestRecorderSettingsConfigurable() {
    setupUI();
    mySettings = TestRecorderSettings.getInstance();
  }

  @Override
  @NotNull
  public String getId() {
    return "test.recorder";
  }

  @Override
  @Nullable
  public Runnable enableSearch(String option) {
    return null;
  }

  @Override
  @Nls
  public String getDisplayName() {
    return "Espresso Test Recorder";
  }

  @Override
  @Nullable
  public String getHelpTopic() {
    return null;
  }

  @Override
  @NotNull
  public JComponent createComponent() {
    myDataCollecationAndCodeGenerationSettingsPanel.setBorder(
      IdeBorderFactory.createTitledBorder("Data collection and code generation settings", false, new Insets(7, 0, 0, 0)));

    myLaunchAndTearDownSettingsPanel.setBorder(
      IdeBorderFactory.createTitledBorder("Launch and tear down settings", false, new Insets(7, 0, 0, 0)));

    myCleanAfterFinishCheckbox.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        updateDependentCheckboxes();
      }
    });

    return myPanel;
  }

  private void updateDependentCheckboxes() {
    if (myCleanAfterFinishCheckbox.isSelected()) {
      myStopAppCheckbox.setSelected(true);
      myStopAppCheckbox.setEnabled(false);
    } else {
      myStopAppCheckbox.setEnabled(true);
    }
  }

  @Override
  public boolean isModified() {
    Integer evaluationDepth = getSpinnerValue(myEvaluationDepthSpinner);
    Integer scrollDepth = getSpinnerValue(myScrollDepthSpinner);
    Integer assertionDepth = getSpinnerValue(myAssertionDepthSpinner);
    return evaluationDepth != null && mySettings.EVALUATION_DEPTH != evaluationDepth
           || scrollDepth != null && mySettings.SCROLL_DEPTH != scrollDepth
           || assertionDepth != null && mySettings.ASSERTION_DEPTH != assertionDepth
           || mySettings.CAP_AT_NON_IDENTIFIABLE_ELEMENTS != myCapEvaluationDepthCheckBox.isSelected()
           || mySettings.USE_TEXT_FOR_ELEMENT_MATCHING != myUseTextForElementMatchingCheckBox.isSelected()
           || mySettings.USE_CONTENT_DESCRIPTION_FOR_ELEMENT_MATCHING != myUseContentDescriptionForElementMatchingCheckBox.isSelected()
           || mySettings.ENABLE_TEST_FRAGMENT_RECORDING != myEnableTestFragmentRecordingCheckBox.isSelected()
           || mySettings.CLEAN_BEFORE_START != myCleanBeforeStartCheckbox.isSelected()
           || mySettings.CLEAN_AFTER_FINISH != myCleanAfterFinishCheckbox.isSelected()
           || mySettings.STOP_APP_AFTER_RECORDING != myStopAppCheckbox.isSelected();
  }

  @Override
  public void apply() throws ConfigurationException {
    Integer evaluationDepth = getSpinnerValue(myEvaluationDepthSpinner);
    if (evaluationDepth != null) {
      mySettings.EVALUATION_DEPTH = evaluationDepth;
    }

    Integer scrollDepth = getSpinnerValue(myScrollDepthSpinner);
    if (scrollDepth != null) {
      mySettings.SCROLL_DEPTH = scrollDepth;
    }

    Integer assertionDepth = getSpinnerValue(myAssertionDepthSpinner);
    if (assertionDepth != null) {
      mySettings.ASSERTION_DEPTH = assertionDepth;
    }

    mySettings.CAP_AT_NON_IDENTIFIABLE_ELEMENTS = myCapEvaluationDepthCheckBox.isSelected();
    mySettings.USE_TEXT_FOR_ELEMENT_MATCHING = myUseTextForElementMatchingCheckBox.isSelected();
    mySettings.USE_CONTENT_DESCRIPTION_FOR_ELEMENT_MATCHING = myUseContentDescriptionForElementMatchingCheckBox.isSelected();
    mySettings.ENABLE_TEST_FRAGMENT_RECORDING = myEnableTestFragmentRecordingCheckBox.isSelected();
    mySettings.CLEAN_BEFORE_START = myCleanBeforeStartCheckbox.isSelected();
    mySettings.CLEAN_AFTER_FINISH = myCleanAfterFinishCheckbox.isSelected();
    mySettings.STOP_APP_AFTER_RECORDING = myStopAppCheckbox.isSelected();
  }

  @Override
  public void reset() {
    myEvaluationDepthSpinner.setValue(mySettings.EVALUATION_DEPTH);
    myScrollDepthSpinner.setValue(mySettings.SCROLL_DEPTH);
    myAssertionDepthSpinner.setValue(mySettings.ASSERTION_DEPTH);
    myCapEvaluationDepthCheckBox.setSelected(mySettings.CAP_AT_NON_IDENTIFIABLE_ELEMENTS);
    myUseTextForElementMatchingCheckBox.setSelected(mySettings.USE_TEXT_FOR_ELEMENT_MATCHING);
    myUseContentDescriptionForElementMatchingCheckBox.setSelected(mySettings.USE_CONTENT_DESCRIPTION_FOR_ELEMENT_MATCHING);
    myEnableTestFragmentRecordingCheckBox.setSelected(mySettings.ENABLE_TEST_FRAGMENT_RECORDING);
    myCleanBeforeStartCheckbox.setSelected(mySettings.CLEAN_BEFORE_START);
    myCleanAfterFinishCheckbox.setSelected(mySettings.CLEAN_AFTER_FINISH);
    myStopAppCheckbox.setSelected(mySettings.STOP_APP_AFTER_RECORDING);
    updateDependentCheckboxes();
  }

  @Override
  public void disposeUIResources() {
  }

  private void createUIComponents() {
    TestRecorderSettings testRecorderSettings = TestRecorderSettings.getInstance();

    myEvaluationDepthSpinner = new JSpinner(new SpinnerNumberModel(testRecorderSettings.EVALUATION_DEPTH, 1, 20, 1));
    forceToAcceptNumbersOnly(myEvaluationDepthSpinner);
    myScrollDepthSpinner = new JSpinner(new SpinnerNumberModel(testRecorderSettings.SCROLL_DEPTH, 1, 20, 1));
    forceToAcceptNumbersOnly(myScrollDepthSpinner);
    myAssertionDepthSpinner = new JSpinner(new SpinnerNumberModel(testRecorderSettings.ASSERTION_DEPTH, 1, 20, 1));
    forceToAcceptNumbersOnly(myAssertionDepthSpinner);
  }

  @Nullable
  private Integer getSpinnerValue(JSpinner spinner) {
    Object value = spinner.getValue();
    return value instanceof Integer ? (Integer)value : null;
  }

  private void forceToAcceptNumbersOnly(JSpinner spinner) {
    JComponent editor = spinner.getEditor();
    if (editor instanceof JSpinner.NumberEditor) {
      JFormattedTextField.AbstractFormatter formatter = ((JSpinner.NumberEditor)editor).getTextField().getFormatter();
      if (formatter instanceof NumberFormatter) {
        ((NumberFormatter)formatter).setAllowsInvalid(false);
      }
    }
  }

  private void setupUI() {
    createUIComponents();
    myPanel = new JPanel();
    myPanel.setLayout(new GridLayoutManager(6, 4, new Insets(0, 0, 0, 0), -1, -1));
    final JBLabel jBLabel1 = new JBLabel();
    jBLabel1.setText(
      "<html><b>Warning:</b> changing these settings could affect the performance of Test Recorder, or impact the quality of the recorded tests.</html>");
    myPanel.add(jBLabel1, new GridConstraints(0, 0, 1, 4, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                                              GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0,
                                              false));
    final JBLabel jBLabel2 = new JBLabel();
    jBLabel2.setText("<html><br></html>");
    myPanel.add(jBLabel2,
                new GridConstraints(1, 0, 1, 4, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED,
                                    GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    myDataCollecationAndCodeGenerationSettingsPanel = new JPanel();
    myDataCollecationAndCodeGenerationSettingsPanel.setLayout(new GridLayoutManager(7, 4, new Insets(0, 0, 0, 0), -1, -1));
    myPanel.add(myDataCollecationAndCodeGenerationSettingsPanel,
                new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                                    GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                    GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0,
                                    false));
    final JBLabel jBLabel3 = new JBLabel();
    jBLabel3.setText("Max UI depth");
    myDataCollecationAndCodeGenerationSettingsPanel.add(jBLabel3, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST,
                                                                                      GridConstraints.FILL_NONE,
                                                                                      GridConstraints.SIZEPOLICY_FIXED,
                                                                                      GridConstraints.SIZEPOLICY_FIXED, null, null, null, 1,
                                                                                      false));
    final Spacer spacer1 = new Spacer();
    myDataCollecationAndCodeGenerationSettingsPanel.add(spacer1, new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_CENTER,
                                                                                     GridConstraints.FILL_HORIZONTAL,
                                                                                     GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null,
                                                                                     null, 0, false));
    final JBLabel jBLabel4 = new JBLabel();
    jBLabel4.setText("ScrollView detection depth");
    myDataCollecationAndCodeGenerationSettingsPanel.add(jBLabel4, new GridConstraints(1, 0, 1, 2, GridConstraints.ANCHOR_WEST,
                                                                                      GridConstraints.FILL_NONE,
                                                                                      GridConstraints.SIZEPOLICY_FIXED,
                                                                                      GridConstraints.SIZEPOLICY_FIXED, null, null, null, 1,
                                                                                      false));
    myDataCollecationAndCodeGenerationSettingsPanel.add(myScrollDepthSpinner, new GridConstraints(1, 2, 1, 1, GridConstraints.ANCHOR_WEST,
                                                                                                  GridConstraints.FILL_NONE,
                                                                                                  GridConstraints.SIZEPOLICY_CAN_SHRINK |
                                                                                                  GridConstraints.SIZEPOLICY_CAN_GROW,
                                                                                                  GridConstraints.SIZEPOLICY_FIXED, null,
                                                                                                  null, null, 0, false));
    myDataCollecationAndCodeGenerationSettingsPanel.add(myEvaluationDepthSpinner,
                                                        new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_WEST,
                                                                            GridConstraints.FILL_NONE,
                                                                            GridConstraints.SIZEPOLICY_CAN_SHRINK |
                                                                            GridConstraints.SIZEPOLICY_CAN_GROW,
                                                                            GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    final JBLabel jBLabel5 = new JBLabel();
    jBLabel5.setText("Assertion depth");
    myDataCollecationAndCodeGenerationSettingsPanel.add(jBLabel5, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_WEST,
                                                                                      GridConstraints.FILL_NONE,
                                                                                      GridConstraints.SIZEPOLICY_FIXED,
                                                                                      GridConstraints.SIZEPOLICY_FIXED, null, null, null, 1,
                                                                                      false));
    myDataCollecationAndCodeGenerationSettingsPanel.add(myAssertionDepthSpinner,
                                                        new GridConstraints(2, 2, 1, 1, GridConstraints.ANCHOR_WEST,
                                                                            GridConstraints.FILL_NONE,
                                                                            GridConstraints.SIZEPOLICY_CAN_SHRINK |
                                                                            GridConstraints.SIZEPOLICY_CAN_GROW,
                                                                            GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    myCapEvaluationDepthCheckBox = new JCheckBox();
    myCapEvaluationDepthCheckBox.setSelected(true);
    myCapEvaluationDepthCheckBox.setText("Limit max depth to identifiable elements");
    myDataCollecationAndCodeGenerationSettingsPanel.add(myCapEvaluationDepthCheckBox,
                                                        new GridConstraints(3, 0, 1, 1, GridConstraints.ANCHOR_WEST,
                                                                            GridConstraints.FILL_NONE,
                                                                            GridConstraints.SIZEPOLICY_CAN_SHRINK |
                                                                            GridConstraints.SIZEPOLICY_CAN_GROW,
                                                                            GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    myUseTextForElementMatchingCheckBox = new JCheckBox();
    myUseTextForElementMatchingCheckBox.setSelected(true);
    myUseTextForElementMatchingCheckBox.setText("Use text for element matching");
    myDataCollecationAndCodeGenerationSettingsPanel.add(myUseTextForElementMatchingCheckBox,
                                                        new GridConstraints(4, 0, 1, 1, GridConstraints.ANCHOR_WEST,
                                                                            GridConstraints.FILL_NONE,
                                                                            GridConstraints.SIZEPOLICY_CAN_SHRINK |
                                                                            GridConstraints.SIZEPOLICY_CAN_GROW,
                                                                            GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    myUseContentDescriptionForElementMatchingCheckBox = new JCheckBox();
    myUseContentDescriptionForElementMatchingCheckBox.setSelected(true);
    myUseContentDescriptionForElementMatchingCheckBox.setText("Use content description for element matching");
    myDataCollecationAndCodeGenerationSettingsPanel.add(myUseContentDescriptionForElementMatchingCheckBox,
                                                        new GridConstraints(5, 0, 1, 1, GridConstraints.ANCHOR_WEST,
                                                                            GridConstraints.FILL_NONE,
                                                                            GridConstraints.SIZEPOLICY_CAN_SHRINK |
                                                                            GridConstraints.SIZEPOLICY_CAN_GROW,
                                                                            GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    myEnableTestFragmentRecordingCheckBox = new JCheckBox();
    myEnableTestFragmentRecordingCheckBox.setText("Enable test fragment recording");
    myDataCollecationAndCodeGenerationSettingsPanel.add(myEnableTestFragmentRecordingCheckBox,
                                                        new GridConstraints(6, 0, 1, 1, GridConstraints.ANCHOR_WEST,
                                                                            GridConstraints.FILL_NONE,
                                                                            GridConstraints.SIZEPOLICY_CAN_SHRINK |
                                                                            GridConstraints.SIZEPOLICY_CAN_GROW,
                                                                            GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    myLaunchAndTearDownSettingsPanel = new JPanel();
    myLaunchAndTearDownSettingsPanel.setLayout(new GridLayoutManager(3, 2, new Insets(0, 0, 0, 0), -1, -1));
    myPanel.add(myLaunchAndTearDownSettingsPanel, new GridConstraints(4, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                                                                      GridConstraints.SIZEPOLICY_CAN_SHRINK |
                                                                      GridConstraints.SIZEPOLICY_CAN_GROW,
                                                                      GridConstraints.SIZEPOLICY_CAN_SHRINK |
                                                                      GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
    myCleanBeforeStartCheckbox = new JCheckBox();
    myCleanBeforeStartCheckbox.setSelected(true);
    myCleanBeforeStartCheckbox.setText("Clear app data before recording");
    myLaunchAndTearDownSettingsPanel.add(myCleanBeforeStartCheckbox,
                                         new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                                                             GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                                             GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    final Spacer spacer2 = new Spacer();
    myLaunchAndTearDownSettingsPanel.add(spacer2,
                                         new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                                                             GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
    myCleanAfterFinishCheckbox = new JCheckBox();
    myCleanAfterFinishCheckbox.setSelected(true);
    myCleanAfterFinishCheckbox.setText("Clear app data after recording");
    myLaunchAndTearDownSettingsPanel.add(myCleanAfterFinishCheckbox,
                                         new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                                                             GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                                             GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    myStopAppCheckbox = new JCheckBox();
    myStopAppCheckbox.setEnabled(false);
    myStopAppCheckbox.setSelected(true);
    myStopAppCheckbox.setText("Exit app after recording");
    myLaunchAndTearDownSettingsPanel.add(myStopAppCheckbox,
                                         new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                                                             GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                                             GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    final Spacer spacer3 = new Spacer();
    myPanel.add(spacer3, new GridConstraints(5, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL, 1,
                                             GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
  }
}
