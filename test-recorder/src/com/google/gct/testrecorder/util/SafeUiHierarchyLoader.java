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
import com.android.uiautomator.tree.RootWindowNode;
import com.android.uiautomator.tree.UiNode;
import com.android.utils.XmlUtils;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * A safe loader for UI hierarchy XML files that prevents XXE attacks.
 */
public final class SafeUiHierarchyLoader {
  private SafeUiHierarchyLoader() {}

  public static SafeUiAutomatorModel load(File xmlFile) {
    try {
      String xmlContent = new String(Files.readAllBytes(xmlFile.toPath()), StandardCharsets.UTF_8);
      Document doc = XmlUtils.parseDocumentSilently(xmlContent, false);
      if (doc == null) {
        return null;
      }
      Element rootElement = doc.getDocumentElement();
      if (rootElement == null) {
        return null;
      }

      BasicTreeNode rootNode = null;
      if ("hierarchy".equals(rootElement.getTagName())) {
        int rotation = 0;
        try {
          rotation = Integer.parseInt(rootElement.getAttribute("rotation"));
        } catch (NumberFormatException ignored) {
        }
        rootNode = new RootWindowNode(rootElement.getAttribute("windowName"), rotation);

        NodeList children = rootElement.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
          Node child = children.item(i);
          if (child instanceof Element && "node".equals(child.getNodeName())) {
            parseNode((Element) child, rootNode);
          }
        }
      }

      return new SafeUiAutomatorModel(rootNode);
    } catch (Exception e) {
      return null;
    }
  }

  private static void parseNode(Element element, BasicTreeNode parent) {
    UiNode uiNode = new UiNode();
    NamedNodeMap attributes = element.getAttributes();
    for (int i = 0; i < attributes.getLength(); i++) {
      Node attr = attributes.item(i);
      // Note: addAtrribute is misspelled in uiautomatorviewer.jar
      uiNode.addAtrribute(attr.getNodeName(), attr.getNodeValue());
    }
    parent.addChild(uiNode);

    NodeList children = element.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child instanceof Element && "node".equals(child.getNodeName())) {
        parseNode((Element) child, uiNode);
      }
    }
  }
}
