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

import com.intellij.execution.testframework.TestConsoleProperties;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.RelativeFont;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;

import static com.intellij.ide.ui.UISettings.setupAntialiasing;
import static com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES;
import static com.intellij.util.ui.UIUtil.getTreeSelectionForeground;

public class GoogleCloudTestTreeRenderer extends ColoredTreeCellRenderer {
  @NonNls private static final String SPACE_STRING = " ";

  private final TestConsoleProperties myConsoleProperties;
  private GoogleCloudTestingRootTestProxyFormatter myAdditionalRootFormatter;
  private String myDurationText;
  private Color myDurationColor;
  private int myDurationWidth;
  private int myDurationOffset;

  public GoogleCloudTestTreeRenderer(final TestConsoleProperties consoleProperties) {
    myConsoleProperties = consoleProperties;
  }

  @Override
  public void customizeCellRenderer(final JTree tree,
                                    final Object value,
                                    final boolean selected,
                                    final boolean expanded,
                                    final boolean leaf,
                                    final int row,
                                    final boolean hasFocus) {
    final DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
    final Object userObj = node.getUserObject();
    if (userObj instanceof GoogleCloudTestNodeDescriptor) {
      final GoogleCloudTestNodeDescriptor desc = (GoogleCloudTestNodeDescriptor)userObj;
      final GoogleCloudTestProxy testProxy = desc.getElement();

      if (testProxy instanceof GoogleCloudTestProxy.GoogleCloudRootTestProxy) {
        GoogleCloudTestProxy.GoogleCloudRootTestProxy rootTestProxy = (GoogleCloudTestProxy.GoogleCloudRootTestProxy) testProxy;
        if (rootTestProxy.isLeaf()) {
          GoogleCloudTestsPresentationUtil.formatRootNodeWithoutChildren(rootTestProxy, this);
        } else {
          GoogleCloudTestsPresentationUtil.formatRootNodeWithChildren(rootTestProxy, this);
        }
        if (myAdditionalRootFormatter != null) {
          myAdditionalRootFormatter.format(rootTestProxy, this);
        }
      } else {
        GoogleCloudTestsPresentationUtil.formatTestProxy(testProxy, this);
      }

      if (TestConsoleProperties.SHOW_INLINE_STATISTICS.value(myConsoleProperties)) {
        myDurationText = testProxy.getDurationString(myConsoleProperties);
        if (myDurationText != null) {
          FontMetrics metrics = getFontMetrics(RelativeFont.SMALL.derive(getFont()));
          myDurationWidth = metrics.stringWidth(myDurationText);
          myDurationOffset = metrics.getHeight() / 2; // an empty area before and after the text
          myDurationColor = selected ? getTreeSelectionForeground(hasFocus) : GRAYED_ATTRIBUTES.getFgColor();
        }
      }
      //Done
      return;
    }

    //strange node
    final String text = node.toString();
    //no icon
    append(text != null ? text : SPACE_STRING, GRAYED_ATTRIBUTES);
  }

  @NotNull
  @Override
  public Dimension getPreferredSize() {
    final Dimension preferredSize = super.getPreferredSize();
    if (myDurationWidth > 0) preferredSize.width += myDurationWidth + myDurationOffset;
    return preferredSize;
  }

  public TestConsoleProperties getConsoleProperties() {
    return myConsoleProperties;
  }

  public void setAdditionalRootFormatter(@NotNull GoogleCloudTestingRootTestProxyFormatter formatter) {
    myAdditionalRootFormatter = formatter;
  }

  public void removeAdditionalRootFormatter() {
    myAdditionalRootFormatter = null;
  }

  @Override
  protected void paintComponent(Graphics g) {
    setupAntialiasing(g);
    Shape clip = null;
    int width = getWidth();
    int height = getHeight();
    if (isOpaque()) {
      // paint background for expanded row
      g.setColor(getBackground());
      g.fillRect(0, 0, width, height);
    }
    if (myDurationText != null && myDurationWidth > 0) {
      width -= myDurationWidth + myDurationOffset;
      if (width > 0 && height > 0) {
        g.setColor(myDurationColor);
        g.setFont(RelativeFont.SMALL.derive(getFont()));
        g.drawString(myDurationText, width + myDurationOffset / 2, getTextBaseLine(g.getFontMetrics(), height));
        clip = g.getClip();
        g.clipRect(0, 0, width, height);
      }
    }
    super.paintComponent(g);
    // restore clip area if needed
    if (clip != null) g.setClip(clip);
  }
}
