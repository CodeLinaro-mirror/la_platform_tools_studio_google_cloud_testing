/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.google.gct.testrecorder.util;

import com.android.uiautomator.tree.BasicTreeNode;

/**
 * A safe version of UiAutomatorModel that doesn't use the unsafe parser from uiautomatorviewer.jar.
 */
public class SafeUiAutomatorModel {
  private final BasicTreeNode mRootNode;

  public SafeUiAutomatorModel(BasicTreeNode rootNode) {
    mRootNode = rootNode;
  }

  public BasicTreeNode getXmlRootNode() {
    return mRootNode;
  }

  public BasicTreeNode updateSelectionForCoordinates(int x, int y) {
    if (mRootNode == null) {
      return null;
    }
    MinAreaFindNodeListener listener = new MinAreaFindNodeListener();
    mRootNode.findLeafMostNodesAtPoint(x, y, listener);
    return listener.mNode;
  }

  private static class MinAreaFindNodeListener implements BasicTreeNode.IFindNodeListener {
    BasicTreeNode mNode;

    @Override
    public void onFoundNode(BasicTreeNode node) {
      if (mNode == null || (node.height * node.width < mNode.height * mNode.width)) {
        mNode = node;
      }
    }
  }
}
