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

import com.intellij.execution.testframework.AbstractTestProxy;
import com.intellij.execution.testframework.TestFrameworkRunningModel;
import com.intellij.execution.testframework.ui.AbstractTestTreeBuilderBase;

import com.intellij.ide.util.treeView.AbstractTreeStructure;
import com.intellij.ide.util.treeView.NodeDescriptor;
import com.intellij.openapi.Disposable;
import com.intellij.ui.tree.StructureTreeModel;
import com.intellij.util.Alarm;
import javax.swing.*;

public class GoogleCloudTestTreeBuilder implements Disposable, AbstractTestTreeBuilderBase<AbstractTestProxy> {
  private final JTree myTree;
  private final GoogleCloudTestTreeStructure myTreeStructure;
  private boolean myDisposed;
  private StructureTreeModel myTreeModel;
  private final Alarm mySelectionAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
  public GoogleCloudTestTreeBuilder(final JTree tree, final GoogleCloudTestTreeStructure structure) {
    myTree = tree;
    myTreeStructure = structure;
  }

  public GoogleCloudTestTreeStructure getGoogleCloudTestTreeStructure() {
    return myTreeStructure;
  }

  public void updateTestsSubtree(final GoogleCloudTestProxy parentTestProxy) {
    getTreeModel().invalidate(parentTestProxy, true);
  }

  public boolean isDisposed() {
    return myDisposed;
  }

  public JTree getTree() {
    return myTree;
  }

  public void updateFromRoot() {
    myTreeModel.invalidateAsync();
  }

  public void setModel(StructureTreeModel asyncTreeModel) {
    myTreeModel = asyncTreeModel;
  }

  public void select(Object proxy, Runnable onDone) {
    if (!(proxy instanceof AbstractTestProxy)) return;
    mySelectionAlarm.cancelAllRequests();
    mySelectionAlarm.addRequest(() -> getTreeModel().select(proxy, getTree(), path -> {
      if (onDone != null) onDone.run();
    }), 50);
  }

  public GoogleCloudTestTreeStructure getTreeStructure() {
    return myTreeStructure;
  }

  @Override
  public void repaintWithParents(AbstractTestProxy testProxy) {
    do {
      getTreeModel().invalidate(testProxy, false);
      testProxy = testProxy.getParent();
    }
    while (testProxy != null);
  }

  @Override
  public void setTestsComparator(TestFrameworkRunningModel model) {
    myTreeModel.setComparator(model.createComparator());
  }

  @Override
  public void dispose() {
    myDisposed = true;
  }

  protected StructureTreeModel getTreeModel() {
    return myTreeModel;
  }

  protected boolean isAutoExpandNode(final NodeDescriptor nodeDescriptor) {
    final AbstractTreeStructure treeStructure = getTreeStructure();
    final Object rootElement = treeStructure.getRootElement();
    final Object nodeElement = nodeDescriptor.getElement();

    if (nodeElement == rootElement) {
      return true;
    }

    return ((AbstractTestProxy)nodeElement).getParent() == rootElement
           && ((AbstractTestProxy)rootElement).getChildren().size() == 1;
  }
}
