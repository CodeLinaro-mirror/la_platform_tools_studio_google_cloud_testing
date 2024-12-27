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

import static com.google.gct.testing.CloudTestingUtils.createConfigurationChooserGbc;

import com.android.annotations.Nullable;
import com.android.tools.analytics.UsageTracker;
import com.android.tools.analytics.UsageTrackerUtils;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.gct.testing.android.CloudConfiguration;
import com.google.gct.testing.dimension.ConfigurationChangeEvent;
import com.google.gct.testing.dimension.ConfigurationChangeListener;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventCategory;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventKind;
import com.intellij.execution.ExecutionBundle;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Splitter;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.AnActionButtonRunnable;
import com.intellij.ui.AnActionButtonUpdater;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.util.IconUtil;
import com.intellij.util.PlatformIcons;
import com.intellij.util.ui.tree.TreeUtil;
import icons.StudioIcons;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;

public class CloudConfigurationChooserDialog extends DialogWrapper implements ConfigurationChangeListener {

  private final List<CloudConfigurationImpl> editableConfigurations;
  private final List<CloudConfigurationImpl> defaultConfigurations;
  private final MyAddAction myAddAction = new MyAddAction();
  private final MyRemoveAction myRemoveAction = new MyRemoveAction();
  private final MyCopyActionButton myCopyActionButton = new MyCopyActionButton();
  private final MyMoveUpAction myMoveUpAction = new MyMoveUpAction();
  private final MyMoveDownAction myMoveDownAction = new MyMoveDownAction();

  private final DefaultMutableTreeNode customRoot = new DefaultMutableTreeNode("Custom");
  private final DefaultMutableTreeNode defaultsRoot = new DefaultMutableTreeNode("Defaults");

  private final CloudConfiguration.Kind configurationKind;

  private JPanel myPanel;
  private final Splitter mySplitter = new Splitter(false);
  private JPanel myConfigurationPanel;
  private JTextField myConfigurationName;
  private Tree myConfigurationTree;
  private JPanel myConfigurationTreePanel;
  private JLabel myConfigurationNameLabel;
  private JLabel myGroupDescriptionLabel;
  private JPanel myConfigurationEditorPanel;
  private JPanel myConfigurationInfoPanel;
  private JLabel myConfigurationInfoLabel;
  private JLabel myDimensionNameLabel;

  private ToolbarDecorator myToolbarDecorator;

  // The configuration that is currently selected in the tree
  @Nullable
  private CloudConfigurationImpl selectedConfiguration;

  private final AndroidFacet facet;

  public CloudConfigurationChooserDialog(Module module,
                                         List<CloudConfigurationImpl> editableConfigurations,
                                         List<CloudConfigurationImpl> defaultConfigurations,
                                         @Nullable final CloudConfigurationImpl initiallySelectedConfiguration,
                                         @NotNull CloudConfiguration.Kind configurationKind) {

    super(module.getProject(), true);
    setupUI();

    this.configurationKind = configurationKind;

    myConfigurationInfoPanel.setPreferredSize(new Dimension(490, !JBColor.isBright() ? 153 : 156));
    // Note that we are editing the list we were given.
    // This could be dangerous.
    this.editableConfigurations = editableConfigurations;
    this.defaultConfigurations = defaultConfigurations;

    facet = AndroidFacet.getInstance(module);

    if (configurationKind == CloudConfiguration.Kind.SINGLE_DEVICE) {
      setTitle("Single Device Configurations");

      UsageTracker.log(UsageTrackerUtils.withProjectId(
        AndroidStudioEvent.newBuilder()
          .setCategory(EventCategory.CLOUD_TESTING)
          .setKind(EventKind.CLOUD_TESTING_CONFIGURE_CLOUD_DEVICE),
        module.getProject()));

    } else {
      setTitle("Matrix Configurations");
      UsageTracker.log(UsageTrackerUtils.withProjectId(
        AndroidStudioEvent.newBuilder()
          .setCategory(EventCategory.CLOUD_TESTING)
          .setKind(EventKind.CLOUD_TESTING_CONFIGURE_MATRIX),
        module.getProject()));
    }

    getOKAction().setEnabled(true);

    myConfigurationTree.addTreeSelectionListener(new TreeSelectionListener() {
      @Override
      public void valueChanged(TreeSelectionEvent e) {

        TreePath selectedPath = e.getPath();
        if (selectedPath.getPathCount() == 2) {
          updateConfigurationControls(false);
          DefaultMutableTreeNode selectedGroup = (DefaultMutableTreeNode)selectedPath.getPath()[1];
          if (selectedGroup.equals(customRoot)) {
            myGroupDescriptionLabel.setText("User-defined configurations. To add a new one click ");
            myGroupDescriptionLabel.setIcon(IconUtil.getAddIcon());
            myGroupDescriptionLabel.setHorizontalTextPosition(SwingConstants.LEFT);
          } else {
            myGroupDescriptionLabel.setText("Default configurations");
            myGroupDescriptionLabel.setIcon(null);
          }
          selectedConfiguration = null;
        } else {
          updateConfigurationControls(true);
          selectedConfiguration = (CloudConfigurationImpl)((DefaultMutableTreeNode)selectedPath.getPath()[2]).getUserObject();
          myConfigurationName.setText(selectedConfiguration.getName());
          myConfigurationName.setEnabled(selectedConfiguration.isEditable());
        }
        updateConfigurationDetailsPanel(selectedConfiguration);
      }
    });
    myConfigurationTree.addFocusListener(new FocusListener() {
      @Override
      public void focusGained(FocusEvent e) {
        myDimensionNameLabel.setText("");
        myConfigurationInfoLabel.setText("");
      }

      @Override
      public void focusLost(FocusEvent e) {
        //ignore
      }
    });
    myConfigurationTree.setCellRenderer(new ColoredTreeCellRenderer() {

      @Override
      public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row,
                                        boolean hasFocus) {
        if (value instanceof DefaultMutableTreeNode) {
          final DefaultMutableTreeNode node = (DefaultMutableTreeNode)value;

          if (node == customRoot) {
            append("Custom", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            setIcon(StudioIcons.Common.ANDROID_HEAD);
          } else if (node == defaultsRoot) {
            append("Defaults", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            setIcon(AllIcons.General.Settings);
          } else if (node.getUserObject() instanceof CloudConfigurationImpl) {
            CloudConfigurationImpl config = (CloudConfigurationImpl)node.getUserObject();
            boolean isInvalidConfiguration = config.getDeviceConfigurationCount() < 1;
            boolean oldMySelected = mySelected;
            // This is a trick to avoid using white color for the selected element if it has to be red.
            if (isInvalidConfiguration) {
              mySelected = false;
            }
            append(config.getDisplayName(), isInvalidConfiguration
                                            ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.RED)
                                            : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            mySelected = oldMySelected;
            setIcon(config.getIcon());
          }

          setIconTextGap(2);
        }
      }
    });

    //Disposer.register(myDisposable, ???);

    if (initiallySelectedConfiguration != null) {
      myConfigurationName.setText(initiallySelectedConfiguration.getName());
    }

    myConfigurationName.getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(DocumentEvent e) {
        selectedConfiguration.setName(myConfigurationName.getText());
      }
    });

    myDimensionNameLabel = new JLabel("", SwingConstants.LEFT);
    Font currentFont = myDimensionNameLabel.getFont();
    myDimensionNameLabel.setFont(new Font(currentFont.getFontName(), Font.BOLD, currentFont.getSize()));
    myConfigurationInfoPanel.add(myDimensionNameLabel, createConfigurationChooserGbc(0, 0));
    myConfigurationInfoLabel = new JLabel("", SwingConstants.LEFT);
    myConfigurationInfoPanel.add(myConfigurationInfoLabel, createConfigurationChooserGbc(0, 1));

    DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Root");
    DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
    myConfigurationTree.setModel(treeModel);
    myConfigurationTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
    treeModel.insertNodeInto(customRoot, rootNode, 0);
    treeModel.insertNodeInto(defaultsRoot, rootNode, 1);
    selectTreeNode(customRoot);
    for (CloudConfigurationImpl configuration : editableConfigurations) {
      addConfigurationToTree(-1, configuration, configuration.equals(initiallySelectedConfiguration));
    }
    for (CloudConfigurationImpl configuration : defaultConfigurations) {
      addConfigurationToTree(-1, configuration, configuration.equals(initiallySelectedConfiguration));
    }

    Dimension preferredSize = myConfigurationTreePanel.getPreferredSize();
    preferredSize.width = Math.max(preferredSize.width, 350);
    preferredSize.height = Math.max(preferredSize.height, 440);
    myConfigurationTreePanel.setPreferredSize(preferredSize);
    myConfigurationTreePanel.add(createLeftPanel(), BorderLayout.CENTER);

    mySplitter.setProportion(0.35f);

    mySplitter.setFirstComponent(myConfigurationTreePanel);
    mySplitter.setSecondComponent(myConfigurationPanel);
    myPanel.add(mySplitter, BorderLayout.CENTER);

    treeModel.reload();

    expandAllRows(myConfigurationTree);

    init();
  }

  /**
   * Adds the given configuration to the appropriate place in the tree and selects it if desired.
   */
  private void addConfigurationToTree(int index, CloudConfigurationImpl configuration, boolean makeSelected) {
    configuration.addConfigurationChangeListener(this);

    final DefaultMutableTreeNode newChild = new DefaultMutableTreeNode(configuration);
    if (configuration.isEditable()) {
      addChildToNode(index, newChild, customRoot);
    } else {
      addChildToNode(index, newChild, defaultsRoot);
    }

    if (makeSelected) {
      selectTreeNode(newChild);
    }
  }

  private void addChildToNode(int index, DefaultMutableTreeNode child, DefaultMutableTreeNode node) {
    if (index == -1) {
      node.add(child);
    } else {
      node.insert(child, index);
    }
  }

  private void selectTreeNode(final DefaultMutableTreeNode node) {
    SwingUtilities.invokeLater(new Runnable() {
      @Override
      public void run() {
        TreeUtil.selectInTree(node, true, myConfigurationTree);
      }
    });
  }

  /**
   * Removes the given configuration from the tree.  We only look under the custom root.
   */
  private void removeConfigurationFromTree(CloudConfigurationImpl configuration) {
    configuration.removeConfigurationChangeListener(this);
    MutableTreeNode toRemove = (MutableTreeNode)TreeUtil.findNodeWithObject(configuration, myConfigurationTree.getModel(), customRoot);
    customRoot.remove(toRemove);
  }

  private JPanel createLeftPanel() {
    myToolbarDecorator = ToolbarDecorator.createDecorator(myConfigurationTree).setAsUsualTopToolbar()
      .setAddAction(myAddAction).setAddActionUpdater(myAddAction).setAddActionName(
        ExecutionBundle.message("add.new.run.configuration.action2.name"))
      .setRemoveAction(myRemoveAction).setRemoveActionUpdater(myRemoveAction).setRemoveActionName(
        ExecutionBundle.message("remove.run.configuration.action.name"))
      .setMoveUpAction(myMoveUpAction).setMoveUpActionUpdater(myMoveUpAction).setMoveUpActionName(
        ExecutionBundle.message("move.up.action.name"))
      .setMoveDownAction(myMoveDownAction).setMoveDownActionUpdater(myMoveDownAction).setMoveDownActionName(
        ExecutionBundle.message("move.down.action.name"))
      .addExtraAction(myCopyActionButton)
      //.addExtraAction(AnActionButton.fromAction(new MySaveAction()))
      .setButtonComparator(ExecutionBundle.message("add.new.run.configuration.action2.name"),
                           ExecutionBundle.message("remove.run.configuration.action.name"),
                           ExecutionBundle.message("copy.configuration.action.name"),
                           ExecutionBundle.message("action.name.save.configuration"),
                           ExecutionBundle.message("run.configuration.edit.default.configuration.settings.text"),
                           ExecutionBundle.message("move.up.action.name"), ExecutionBundle.message("move.down.action.name"),
                           ExecutionBundle.message("run.configuration.create.folder.text")).setForcedDnD();
    return myToolbarDecorator.createPanel();
  }

  LoadingCache<CloudConfigurationImpl, TwoPanelTree> dimensionTreeCache = CacheBuilder.newBuilder()
    .build(new CacheLoader<CloudConfigurationImpl, TwoPanelTree>() {
      @Override
      public TwoPanelTree load(CloudConfigurationImpl configuration) throws Exception {
        final TwoPanelTree tree = new TwoPanelTree(configuration);
        final TwoPanelTreeSelectionListener treeSelectionListener = new TwoPanelTreeSelectionListener() {
          @Override
          public void dimensionSelectionChanged(TwoPanelTreeDimensionSelectionEvent e) {
            myDimensionNameLabel.setText("");
            myConfigurationInfoLabel.setText("");
          }

          @Override
          public void typeSelectionChanged(TwoPanelTreeTypeSelectionEvent e) {
            myDimensionNameLabel.setText(e.getType().getConfigurationDialogDisplayName());
            String details = "<html><table cellpadding='0'>";
            for (Map.Entry<String, String> entry : e.getType().getDetails().entrySet()) {
              details += "<tr><td>" + entry.getKey() + ":&nbsp;</td><td>" + entry.getValue() + "</td></tr>";
            }
            details += "</table>";
            if (!e.getCurrentDimension().getSupportedDomain().contains(e.getType())) {
              details += "<br><i>This option is not currently supported by Google Cloud.</i>";
            }
            details += "</html>";
            myConfigurationInfoLabel.setText(details);
          }

          @Override
          public void groupSelectionChanged(TwoPanelTreeTypeGroupSelectionEvent e) {
            myDimensionNameLabel.setText(e.getGroup().getDescription());
            myConfigurationInfoLabel.setText("");
          }
        };

        tree.addSelectionListener(treeSelectionListener);
        return tree;
      }
    });


  private void updateConfigurationDetailsPanel(CloudConfigurationImpl configuration) {

    myConfigurationEditorPanel.removeAll();

    if (configuration != null) {
      myConfigurationInfoPanel.setVisible(true);
      try {
        myConfigurationEditorPanel.add(dimensionTreeCache.get(configuration).getPanel());
      }
      catch (ExecutionException e) {
        e.printStackTrace();
      }
      myConfigurationEditorPanel.updateUI();
    } else {
      myConfigurationInfoPanel.setVisible(false);
    }
  }

  private void updateConfigurationControls(boolean isRealConfiguration) {
    myConfigurationName.setVisible(isRealConfiguration);
    myConfigurationNameLabel.setVisible(isRealConfiguration);
    myGroupDescriptionLabel.setVisible(!isRealConfiguration);
  }

  private void expandAllRows(Tree tree) {
    for (int i = 0; i < tree.getRowCount(); i++) {
      tree.expandRow(i);
    }
  }

  @Nullable
  public CloudConfigurationImpl getSelectedConfiguration() {
    return selectedConfiguration;
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return myConfigurationTree;
  }

  @Override
  protected JComponent createCenterPanel() {
    return myPanel;
  }

  private void createUIComponents() {
    // TODO: place custom component creation code here
  }

  @Override
  public void configurationChanged(ConfigurationChangeEvent e) {
    DefaultTreeModel model = (DefaultTreeModel)myConfigurationTree.getModel();
    model.nodeChanged(TreeUtil.findNodeWithObject(e.getConfiguration(), model, customRoot));
    myConfigurationTreePanel.updateUI();
  }

  private void setupUI() {
    myPanel = new JPanel();
    myPanel.setLayout(new BorderLayout(0, 0));
    myConfigurationPanel = new JPanel();
    myConfigurationPanel.setLayout(new BorderLayout(0, 0));
    myPanel.add(myConfigurationPanel, BorderLayout.NORTH);
    final JPanel panel1 = new JPanel();
    panel1.setLayout(new GridLayoutManager(3, 2, new Insets(0, 0, 0, 0), -1, -1));
    myConfigurationPanel.add(panel1, BorderLayout.NORTH);
    myConfigurationName = new JTextField();
    panel1.add(myConfigurationName, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                                                        GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null,
                                                        new Dimension(150, -1), null, 0, false));
    myConfigurationNameLabel = new JLabel();
    myConfigurationNameLabel.setText("Name");
    panel1.add(myConfigurationNameLabel,
               new GridConstraints(0, 0, 2, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED,
                                   GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    myGroupDescriptionLabel = new JLabel();
    myGroupDescriptionLabel.setText("Label");
    myGroupDescriptionLabel.setVisible(false);
    panel1.add(myGroupDescriptionLabel,
               new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED,
                                   GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    final JPanel panel2 = new JPanel();
    panel2.setLayout(new BorderLayout(0, 0));
    myConfigurationPanel.add(panel2, BorderLayout.CENTER);
    final JPanel panel3 = new JPanel();
    panel3.setLayout(new GridBagLayout());
    panel2.add(panel3, BorderLayout.NORTH);
    myConfigurationEditorPanel = new JPanel();
    myConfigurationEditorPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 5, 5));
    GridBagConstraints gbc;
    gbc = new GridBagConstraints();
    gbc.gridx = 0;
    gbc.gridy = 0;
    gbc.weightx = 1.0;
    gbc.weighty = 1.0;
    gbc.fill = GridBagConstraints.BOTH;
    panel3.add(myConfigurationEditorPanel, gbc);
    myConfigurationInfoPanel = new JPanel();
    myConfigurationInfoPanel.setLayout(new GridBagLayout());
    myConfigurationInfoPanel.setMinimumSize(new Dimension(540, 150));
    myConfigurationInfoPanel.setOpaque(true);
    myConfigurationInfoPanel.setPreferredSize(new Dimension(10, 10));
    gbc = new GridBagConstraints();
    gbc.gridx = 0;
    gbc.gridy = 1;
    gbc.anchor = GridBagConstraints.NORTH;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    panel3.add(myConfigurationInfoPanel, gbc);
    myConfigurationInfoPanel.setBorder(
      IdeBorderFactory.PlainSmallWithIndent.createTitledBorder(BorderFactory.createEtchedBorder(), "Details",
                                                               TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null,
                                                               null));
    myConfigurationTreePanel = new JPanel();
    myConfigurationTreePanel.setLayout(new BorderLayout(0, 0));
    myPanel.add(myConfigurationTreePanel, BorderLayout.CENTER);
    myConfigurationTree = new Tree();
    myConfigurationTree.setAutoscrolls(true);
    myConfigurationTree.setEditable(false);
    myConfigurationTree.setRootVisible(false);
    myConfigurationTree.setShowsRootHandles(true);
    myConfigurationTreePanel.add(myConfigurationTree, BorderLayout.CENTER);
  }

  private class MyRemoveAction extends AnAction implements AnActionButtonRunnable, AnActionButtonUpdater {

    @Override
    public void actionPerformed(AnActionEvent e) {
      doRemove();
    }

    @Override
    public void run(AnActionButton button) {
      doRemove();
    }

    private void doRemove() {
      if (selectedConfiguration == null || !selectedConfiguration.isEditable()) {
        // TODO: This button should be disabled in these cases
        return;
      }

      int removedIndex = editableConfigurations.indexOf(selectedConfiguration);
      editableConfigurations.remove(selectedConfiguration);
      removeConfigurationFromTree(selectedConfiguration);

      DefaultMutableTreeNode treeNode;
      if (editableConfigurations.isEmpty()) {
        if (defaultConfigurations.isEmpty()) {
          treeNode = customRoot;
        } else {
          treeNode = TreeUtil.findNodeWithObject(defaultsRoot, defaultConfigurations.get(0));
        }
      } else {
        if (removedIndex > editableConfigurations.size() - 1) {
          removedIndex = editableConfigurations.size() - 1;
        }
        treeNode = TreeUtil.findNodeWithObject(customRoot, editableConfigurations.get(removedIndex));
      }
      selectTreeNode(treeNode);

      updateConfigurationTree();
    }

    @Override
    public boolean isEnabled(AnActionEvent e) {
      return selectedConfiguration != null && selectedConfiguration.isEditable();
    }
  }

  private class MyCopyActionButton extends AnActionButton implements AnActionButtonUpdater {

    public MyCopyActionButton() {
      super(ExecutionBundle.message("copy.configuration.action.name"), ExecutionBundle.message("copy.configuration.action.name"),
            PlatformIcons.COPY_ICON);
      addCustomUpdater(this);
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
      // selectedConfiguration should not be null here, but handle this scenario just in case.
      CloudConfigurationImpl newConfiguration = selectedConfiguration != null
                                                ? selectedConfiguration.copy("Copy of ")
                                                : new CloudConfigurationImpl(facet, configurationKind);

      addNewConfiguration(newConfiguration);
    }

    @Override
    public boolean isEnabled(AnActionEvent e) {
      return selectedConfiguration != null;
    }
  }

  private class MyAddAction extends AnAction implements AnActionButtonRunnable, AnActionButtonUpdater {

    @Override
    public void actionPerformed(AnActionEvent e) {
      doAdd();
    }

    @Override
    public void run(AnActionButton button) {
      doAdd();
    }

    private void doAdd() {
      addNewConfiguration(new CloudConfigurationImpl(facet, configurationKind));
    }

    @Override
    public boolean isEnabled(AnActionEvent e) {
      return true;
    }
  }

  private void addNewConfiguration(CloudConfigurationImpl newConfiguration) {
    newConfiguration.setIcon(CloudConfigurationHelper.DEFAULT_ICON);
    int addIndex = selectedConfiguration == null
                   ? editableConfigurations.size()
                   : editableConfigurations.indexOf(selectedConfiguration) + 1;
    editableConfigurations.add(addIndex, newConfiguration);
    addConfigurationToTree(addIndex, newConfiguration, true);
    selectedConfiguration = newConfiguration;

    updateConfigurationTree();
  }

  private class MyMoveUpAction extends AnAction implements AnActionButtonRunnable, AnActionButtonUpdater {

    @Override
    public void actionPerformed(AnActionEvent e) {
      moveUp();
    }

    @Override
    public void run(AnActionButton button) {
      moveUp();
    }

    private void moveUp() {
      if (selectedConfiguration != null) {
        int index = editableConfigurations.indexOf(selectedConfiguration);
        if (index > 0) {
          moveConfigurationToIndex(index - 1, selectedConfiguration);
        }
      }
    }

    @Override
    public boolean isEnabled(AnActionEvent e) {
      return selectedConfiguration != null
             && selectedConfiguration.isEditable()
             && editableConfigurations.indexOf(selectedConfiguration) > 0;
    }
  }

  private class MyMoveDownAction extends AnAction implements AnActionButtonRunnable, AnActionButtonUpdater {

    @Override
    public void actionPerformed(AnActionEvent e) {
      moveDown();
    }

    @Override
    public void run(AnActionButton button) {
      moveDown();
    }

    private void moveDown() {
      if (selectedConfiguration != null) {
        int index = editableConfigurations.indexOf(selectedConfiguration);
        if (index != -1 && index < editableConfigurations.size() - 1) {
          moveConfigurationToIndex(index + 1, selectedConfiguration);
        }
      }
    }

    @Override
    public boolean isEnabled(AnActionEvent e) {
      return selectedConfiguration != null
             && selectedConfiguration.isEditable()
             && editableConfigurations.indexOf(selectedConfiguration) < editableConfigurations.size() - 1;
    }
  }

  private void moveConfigurationToIndex(int index, CloudConfigurationImpl configuration) {
    editableConfigurations.remove(configuration);
    editableConfigurations.add(index, configuration);
    removeConfigurationFromTree(configuration);
    addConfigurationToTree(index, configuration, true);
    updateConfigurationTree();
  }

  private void updateConfigurationTree() {
    ((DefaultTreeModel)myConfigurationTree.getModel()).reload();
    expandAllRows(myConfigurationTree);
    myConfigurationTree.updateUI();
  }

  @Override
  protected void doOKAction() {
    super.doOKAction();
  }
}
