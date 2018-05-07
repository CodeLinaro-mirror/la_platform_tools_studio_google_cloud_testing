/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.google.gct.testing.android;

import com.android.tools.idea.run.editor.DeployTargetConfigurable;
import com.android.tools.idea.run.editor.DeployTargetConfigurableContext;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.actionSystem.impl.PresentationFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import icons.GoogleCloudToolsIcons;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

import static com.google.gct.testing.CloudTestingUtils.*;
import static com.google.gct.testing.android.CloudConfiguration.Kind.MATRIX;
import static com.google.gct.testing.launcher.CloudAuthenticator.authorize;
import static com.google.gct.testing.launcher.CloudAuthenticator.isUserLoggedIn;

public class CloudTestMatrixTargetConfigurable implements DeployTargetConfigurable<CloudTestMatrixTargetProvider.State> {
  @Nullable private AndroidFacet myFacet;
  private final JPanel topPanel;
  private final JPanel connectToCloudPanel;
  private final JPanel cloudDeviceMatrixPanel;
  private final CloudConfigurationComboBox myCloudConfigurationComboBox;
  private final CloudProjectSelector myCloudProjectSelector;

  public CloudTestMatrixTargetConfigurable(@NotNull Project project, Disposable parentDisposable,
                                           @NotNull final DeployTargetConfigurableContext context) {
    myFacet = context.getModule() == null ? null : AndroidFacet.getInstance(context.getModule());
    context.addModuleChangeListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        myFacet = context.getModule() == null ? null : AndroidFacet.getInstance(context.getModule());
        if (isUserLoggedIn()) {
          myCloudConfigurationComboBox.setFacet(myFacet);
          myCloudProjectSelector.setFacet(myFacet);
        }
      }
    });

    topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    topPanel.setName("Cloud Testing");
    connectToCloudPanel = new JPanel();
    connectToCloudPanel.setLayout(new GridLayoutManager(3, 1));
    cloudDeviceMatrixPanel = new JPanel();
    cloudDeviceMatrixPanel.setLayout(new GridLayoutManager(4, 3));
    topPanel.add(connectToCloudPanel, preparePanelGridConstraints(0));
    topPanel.add(cloudDeviceMatrixPanel, preparePanelGridConstraints(1));

    connectToCloudPanel.add(createRunTestsInCloudPane(topPanel.getBackground(), 6, 4), prepareEditorPaneGridConstraints(0));
    JButton connectToCloudButton = new JButton("Sign in with Google");
    connectToCloudButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        authorize();
        updateVisibility();
      }
    });
    connectToCloudPanel.add(connectToCloudButton, prepareElementGridConstraints(1, 0));
    connectToCloudPanel.add(createSignupForCloudPane(topPanel.getBackground(), 6, 0), prepareEditorPaneGridConstraints(2));

    cloudDeviceMatrixPanel.add(new JLabel("Matrix configuration:"), prepareElementGridConstraints(0, 0));
    myCloudConfigurationComboBox = new CloudConfigurationComboBox(MATRIX);
    cloudDeviceMatrixPanel.add(myCloudConfigurationComboBox, prepareElementGridConstraints(0, 1));
    cloudDeviceMatrixPanel.add(new JPanel(), prepareHorizontalSpacerGridConstraints(0, 2));
    cloudDeviceMatrixPanel.add(new JLabel("Cloud project:"), prepareElementGridConstraints(1, 0));
    JPanel cloudProjectPanel = new JPanel();
    cloudProjectPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 0, 5));
    myCloudProjectSelector = new CloudProjectSelector(MATRIX);
    cloudProjectPanel.add(myCloudProjectSelector);
    JPanel gapPanel = new JPanel();
    gapPanel.setPreferredSize(new Dimension(5, 5));
    cloudProjectPanel.add(gapPanel);
    AnAction cloudMatrixProjectAction = new RefreshCloudProjectsAction(myCloudProjectSelector);
    ActionButton cloudMatrixProjectActionButton = new ActionButton(
      cloudMatrixProjectAction, new PresentationFactory().getPresentation(cloudMatrixProjectAction), "MyPlace", JBUI.size(25, 25));
    cloudMatrixProjectActionButton.setFocusable(true);
    cloudProjectPanel.add(cloudMatrixProjectActionButton);
    cloudDeviceMatrixPanel.add(cloudProjectPanel, prepareElementGridConstraints(1, 1));
    cloudDeviceMatrixPanel.add(createLinkPane(topPanel.getBackground(), prepareCreateFirebaseProjectAnchor("Create new Firebase project")),
                               prepareElementGridConstraints(2, 0));
    cloudDeviceMatrixPanel.add(createLinkPane(topPanel.getBackground(), preparePricingAnchor("Pricing information")),
                               prepareElementGridConstraints(3, 0));

    topPanel.addComponentListener(new ComponentAdapter() {
      @Override
      public void componentShown(ComponentEvent e) {
        // Update UI such that combobox adjusts its size depending on its content.
        myCloudProjectSelector.updateUI();
      }
    });

    updateVisibility();

    Disposer.register(parentDisposable, myCloudConfigurationComboBox);
  }

  @Nullable
  @Override
  public JComponent createComponent() {
    return topPanel;
  }

  @Override
  public void resetFrom(@NotNull CloudTestMatrixTargetProvider.State state, int configurationId) {
    myCloudConfigurationComboBox.setRunConfigurationId(configurationId);
    myCloudProjectSelector.setRunConfigurationId(configurationId);

    // Update the relevant UI components only if they have meaning (are shown).
    if (isUserLoggedIn()) {
      // Set facet (potentially, again) after run configuration id such that we remember user choices properly
      // (i.e., per configuration per module).
      myCloudConfigurationComboBox.setFacet(myFacet);
      myCloudProjectSelector.setFacet(myFacet);

      myCloudConfigurationComboBox.selectCloudConfiguration(state.SELECTED_CLOUD_MATRIX_CONFIGURATION_ID);
      myCloudProjectSelector.updateCloudProjectId(state.SELECTED_CLOUD_MATRIX_PROJECT_ID);
    }
  }

  @Override
  public void applyTo(@NotNull CloudTestMatrixTargetProvider.State state, int configurationId) {
    // Store the state only if there is some, i.e., the user is logged in and a proper configuration is selected.
    if (isUserLoggedIn() && myCloudConfigurationComboBox.getComboBox().getSelectedItem() instanceof CloudConfiguration) {
      CloudConfiguration selectedConfiguration = (CloudConfiguration)myCloudConfigurationComboBox.getComboBox().getSelectedItem();
      state.SELECTED_CLOUD_MATRIX_CONFIGURATION_ID = selectedConfiguration == null ? -1 : selectedConfiguration.getId();
      state.SELECTED_CLOUD_MATRIX_PROJECT_ID = myCloudProjectSelector.getProjectId();
    }
  }

  private void updateVisibility() {
    if (isUserLoggedIn()) {
      myCloudConfigurationComboBox.setFacet(myFacet);
      myCloudProjectSelector.setFacet(myFacet);
      cloudDeviceMatrixPanel.setVisible(true);
      connectToCloudPanel.setVisible(false);
      simulateChangeEvent(myCloudConfigurationComboBox);
    } else {
      cloudDeviceMatrixPanel.setVisible(false);
      connectToCloudPanel.setVisible(true);
    }
  }

  private GridConstraints preparePanelGridConstraints(int row) {
    return new GridConstraints(row, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                               GridConstraints.SIZEPOLICY_CAN_GROW | GridConstraints.SIZEPOLICY_WANT_GROW | GridConstraints.SIZEPOLICY_CAN_SHRINK,
                               GridConstraints.SIZEPOLICY_CAN_GROW | GridConstraints.SIZEPOLICY_WANT_GROW | GridConstraints.SIZEPOLICY_CAN_SHRINK,
                               new Dimension(-1, -1), new Dimension(-1, -1), new Dimension(-1, -1));
  }

  private GridConstraints prepareEditorPaneGridConstraints(int row) {
    return new GridConstraints(row, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                               GridConstraints.SIZEPOLICY_CAN_GROW | GridConstraints.SIZEPOLICY_WANT_GROW,
                               GridConstraints.SIZEPOLICY_CAN_GROW | GridConstraints.SIZEPOLICY_WANT_GROW,
                               new Dimension(-1, -1), new Dimension(490, 30), new Dimension(-1, -1));
  }

  private GridConstraints prepareElementGridConstraints(int row, int column) {
    return new GridConstraints(row, column, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                               GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                               new Dimension(-1, -1), new Dimension(-1, -1), new Dimension(-1, -1));
  }

  private GridConstraints prepareHorizontalSpacerGridConstraints(int row, int column) {
    return new GridConstraints(row, column, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                               GridConstraints.SIZEPOLICY_CAN_GROW | GridConstraints.SIZEPOLICY_WANT_GROW | GridConstraints.SIZEPOLICY_CAN_SHRINK,
                               GridConstraints.SIZEPOLICY_CAN_GROW | GridConstraints.SIZEPOLICY_WANT_GROW | GridConstraints.SIZEPOLICY_CAN_SHRINK,
                               new Dimension(-1, -1), new Dimension(-1, -1), new Dimension(-1, -1));
  }

  private JEditorPane createRunTestsInCloudPane(@NotNull Color backgroundColor, int topMargin, int bottomMargin) {
    JEditorPane runTestsInCloudPane =
      new JEditorPane(UIUtil.HTML_MIME,
                      "<html><p style='margin-top: " + topMargin + "px; margin-bottom: " + bottomMargin + "px;'>"
                      + "Run tests against a wide variety of physical and virtual devices simultaneously in "
                      + "<a href='https://firebase.google.com/docs/test-lab'>Firebase Test Lab</a>.<br>"
                      + preparePricingAnchor("Pricing information") + "</p></html>");
    linkifyEditorPane(runTestsInCloudPane, backgroundColor);
    return runTestsInCloudPane;
  }

  private JEditorPane createSignupForCloudPane(@NotNull Color backgroundColor, int topMargin, int bottomMargin) {
    JEditorPane signupForCloudPane =
      new JEditorPane(UIUtil.HTML_MIME,
                      "<html><p style='margin-top: " + topMargin + "px; margin-bottom: " + bottomMargin + "px;'>"
                      + "Don&rsquo;t have a Firebase account? "
                      + "<a href='https://console.firebase.google.com'>Sign up</a> for one now.</p></html>");
    linkifyEditorPane(signupForCloudPane, backgroundColor);
    return signupForCloudPane;
  }

  private JEditorPane createLinkPane(@NotNull Color backgroundColor, String anchor) {
    JEditorPane linkPane = new JEditorPane(UIUtil.HTML_MIME, "<html>" + anchor + "</html>");
    linkPane.setMargin(new Insets(0, 1, 0, 0));
    linkifyEditorPane(linkPane, backgroundColor);
    return linkPane;
  }

  /**
   * Simulate a change event such that it is picked up by the editor validation mechanisms.
   */
  private static void simulateChangeEvent(CloudConfigurationComboBox comboBox) {
    for (ItemListener itemListener : comboBox.getComboBox().getItemListeners()) {
      itemListener.itemStateChanged(
        new ItemEvent(comboBox.getComboBox(), ItemEvent.ITEM_STATE_CHANGED, comboBox.getComboBox(), ItemEvent.SELECTED));
    }
  }

  private static class RefreshCloudProjectsAction extends AnAction {
    private final CloudProjectSelector myCloudProjectSelector;

    public RefreshCloudProjectsAction(CloudProjectSelector cloudProjectSelector) {
      myCloudProjectSelector = cloudProjectSelector;
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
      myCloudProjectSelector.refreshCloudProjects();
    }

    @Override
    public void update(AnActionEvent event) {
      Presentation presentation = event.getPresentation();
      presentation.setIcon(GoogleCloudToolsIcons.REFRESH);
      presentation.setText("Refresh cloud projects");
    }
  }
}
