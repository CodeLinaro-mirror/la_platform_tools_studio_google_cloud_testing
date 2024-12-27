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
package com.google.gct.testing.ui;

import com.google.gct.testing.CloudTestingUtils;
import com.intellij.ui.JBColor;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import static javax.swing.JFrame.EXIT_ON_CLOSE;

public class AddCompareScreenshotPanel {
  public static final int WIDTH = 140;

  private JLabel imageLabel;
  private JLabel textLabel;
  private JPanel myPanel;

  private final ImageIcon COMPARE_REGULAR;
  private final ImageIcon COMPARE_HOVER;

  private final Color regularColor = !JBColor.isBright() ? Color.gray : Color.lightGray;
  private final Color hoverColor = !JBColor.isBright() ? Color.lightGray : Color.gray;

  private final Border regularBorder = createDashedBorder(regularColor, 4, 5, 3, true);
  private final Border hoverBorder = createDashedBorder(hoverColor, 4, 5, 3, true);

  List<AddScreenshotListener> listeners = new LinkedList<AddScreenshotListener>();

  public AddCompareScreenshotPanel() {
    setupUI();
    BufferedImage darkImage = null;
    BufferedImage lightImage = null;
    BufferedImage brightImage = null;

    try {
      darkImage = ImageIO.read(AddCompareScreenshotPanel.class.getResourceAsStream("compare-dark.png"));
      darkImage.flush();

      lightImage = ImageIO.read(AddCompareScreenshotPanel.class.getResourceAsStream("compare-light.png"));
      lightImage.flush();

      brightImage = ImageIO.read(AddCompareScreenshotPanel.class.getResourceAsStream("compare-bright.png"));
      brightImage.flush();
    }
    catch (IOException e) {
      e.printStackTrace();
    }

    COMPARE_HOVER = !JBColor.isBright() ? new ImageIcon(brightImage) : new ImageIcon(darkImage);
    COMPARE_REGULAR = new ImageIcon(lightImage);

    textLabel.setForeground(Color.gray);
    textLabel.setFont(new Font("Arial", Font.BOLD, 13));

    //noinspection Since15
    myPanel.setBorder(regularBorder);

    imageLabel.setText(null);
    imageLabel.setIcon(COMPARE_REGULAR);

    //myPanel.setPreferredSize(new Dimension(WIDTH, 450));

    myPanel.addMouseListener(new MouseListener());
  }

  public void setHeight(int height) {
    Dimension panelSize = new Dimension(WIDTH, height);
    myPanel.setMinimumSize(panelSize);
    myPanel.setPreferredSize(panelSize);
  }

  public void addListener(AddScreenshotListener listener) {
    listeners.add(listener);
  }

  public JPanel getPanel() {
    return myPanel;
  }

  private void setupUI() {
    myPanel = new JPanel();
    myPanel.setLayout(new BorderLayout(0, 0));
    myPanel.setOpaque(true);
    final JPanel panel1 = new JPanel();
    panel1.setLayout(new GridLayoutManager(4, 1, new Insets(0, 0, 0, 0), -1, -1));
    panel1.setOpaque(false);
    myPanel.add(panel1, BorderLayout.CENTER);
    textLabel = new JLabel();
    textLabel.setText("Compare");
    panel1.add(textLabel, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE,
                                              GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                              GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null,
                                              0, false));
    imageLabel = new JLabel();
    imageLabel.setText("Image");
    panel1.add(imageLabel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE,
                                               GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                               GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null,
                                               null, 0, false));
    final Spacer spacer1 = new Spacer();
    panel1.add(spacer1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL, 1,
                                            GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
    final Spacer spacer2 = new Spacer();
    panel1.add(spacer2, new GridConstraints(3, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL, 1,
                                            GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
  }

  class MouseListener extends MouseAdapter {
    @Override
    public void mouseClicked(MouseEvent e) {
      for (AddScreenshotListener listener : listeners) {
        listener.addScreenshot();
      }
      mouseExited(e);
    }

    @Override
    public void mouseReleased(MouseEvent e) {
      myPanel.setBackground(null);
    }

    @Override
    public void mousePressed(MouseEvent e) {
      myPanel.setBackground(CloudTestingUtils.makeDarker(myPanel.getBackground(), 1));
    }

    @Override
    public void mouseEntered(MouseEvent e) {
      myPanel.setToolTipText("Compare to another screenshot");
      imageLabel.setIcon(COMPARE_HOVER);
      myPanel.setBorder(hoverBorder);
      textLabel.setForeground(hoverColor);
    }

    @Override
    public void mouseExited(MouseEvent e) {
      myPanel.setToolTipText(null);
      imageLabel.setIcon(COMPARE_REGULAR);
      myPanel.setBorder(regularBorder);
      textLabel.setForeground(regularColor);
    }
  }

  public static Border createDashedBorder(Paint paint, float thickness, float length, float spacing, boolean rounded) {
    int cap = rounded ? BasicStroke.CAP_ROUND : BasicStroke.CAP_SQUARE;
    int join = rounded ? BasicStroke.JOIN_ROUND : BasicStroke.JOIN_MITER;
    float[] array = {thickness * (length - 1.0f), thickness * (spacing + 1.0f)};
    Border border = new StrokeBorder(new BasicStroke(thickness, cap, join, thickness * 2.0f, array, 0.0f), paint);
    return border;
  }

  public static void main(String[] args) {
    SwingUtilities.invokeLater(new Runnable() {
      @Override
      public void run() {
        JFrame frame = new JFrame("AddScreenshotPanel");
        frame.setDefaultCloseOperation(EXIT_ON_CLOSE);

        JPanel containerPanel = new JPanel(new FlowLayout());
        containerPanel.add(new AddCompareScreenshotPanel().getPanel());

        frame.add(containerPanel);
        frame.setSize(600, 600);
        frame.setVisible(true);
      }
    });
  }
}
