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


import com.google.gct.testing.ShowScreenshotsAction;
import com.intellij.execution.testframework.TestConsoleProperties;
import com.intellij.execution.testframework.TestFrameworkRunningModel;
import com.intellij.execution.testframework.ToolbarPanel;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl;

import javax.swing.*;
import java.awt.*;

public class GoogleCloudTestRunnerToolbarPanel extends ToolbarPanel {

  public GoogleCloudTestRunnerToolbarPanel(TestConsoleProperties properties,
                                           TestFrameworkRunningModel model,
                                           JComponent contentPane) {
    super(properties, contentPane);
    setModel(model);

    int lastComponentIndex = getComponentCount() - 1;
    ActionToolbarImpl actionToolbar = (ActionToolbarImpl)getComponent(lastComponentIndex);
    final DefaultActionGroup cloudActionGroup = new DefaultActionGroup((String)null, false);
    for (AnAction action : super.getActionsToMerge()) {
      cloudActionGroup.add(action);
    }

    for (AnAction action : actionToolbar.getActions()) {
      cloudActionGroup.add(action);
    }

    // Add firebase actions as a 4th group of actions.
    addCloudActions(cloudActionGroup);

    // Remove the original action bar and add a firebase action bar instead.
    remove(lastComponentIndex);
    add(ActionManager.getInstance().createActionToolbar(ActionPlaces.TESTTREE_VIEW_TOOLBAR, cloudActionGroup, true).getComponent(),
        BorderLayout.CENTER);
  }

  private void addCloudActions(DefaultActionGroup actionGroup) {
    actionGroup.addAction(new ShowScreenshotsAction());
    actionGroup.addSeparator();
  }

}
