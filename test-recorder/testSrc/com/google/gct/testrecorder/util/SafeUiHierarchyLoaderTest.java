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

import static com.google.common.truth.Truth.assertThat;

import com.android.uiautomator.tree.BasicTreeNode;
import com.android.uiautomator.tree.UiNode;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SafeUiHierarchyLoaderTest {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void testLoadValidXml() throws Exception {
    String xml = "<?xml version='1.0' encoding='UTF-8'?>" +
                 "<hierarchy rotation='1' windowName='testWindow'>" +
                 "  <node index='0' text='hello' class='android.widget.TextView' bounds='[0,0][100,100]' />" +
                 "</hierarchy>";
    File file = temporaryFolder.newFile("test.xml");
    Files.write(file.toPath(), xml.getBytes(StandardCharsets.UTF_8));

    SafeUiAutomatorModel model = SafeUiHierarchyLoader.load(file);
    assertThat(model).isNotNull();
    BasicTreeNode root = model.getXmlRootNode();
    assertThat(root).isNotNull();
    assertThat(root.getChildCount()).isEqualTo(1);
    UiNode node = (UiNode) root.getChildren()[0];
    assertThat(node.getAttribute("text")).isEqualTo("hello");
  }

  @Test
  public void testXxeProtection() throws Exception {
    File secretFile = temporaryFolder.newFile("secret.txt");
    Files.write(secretFile.toPath(), "top-secret-content".getBytes(StandardCharsets.UTF_8));

    // Malicious XML attempting to read secret.txt
    String xml = "<?xml version='1.0' encoding='UTF-8'?>" +
                 "<!DOCTYPE foo [ <!ENTITY xxe SYSTEM 'file://" + secretFile.getAbsolutePath() + "'> ]>" +
                 "<hierarchy rotation='0' windowName='testWindow'>" +
                 "  <node index='0' text='&xxe;' class='android.widget.TextView' bounds='[0,0][100,100]' />" +
                 "</hierarchy>";
    File file = temporaryFolder.newFile("malicious.xml");
    Files.write(file.toPath(), xml.getBytes(StandardCharsets.UTF_8));

    SafeUiAutomatorModel model = SafeUiHierarchyLoader.load(file);
    
    // If hardened, it should either fail to parse (return null) or parse but not expand the entity.
    if (model != null) {
      BasicTreeNode root = model.getXmlRootNode();
      UiNode node = (UiNode) root.getChildren()[0];
      String text = node.getAttribute("text");
      assertThat(text).isNotEqualTo("top-secret-content");
      // Usually it will be empty or contain the entity reference literal depending on parser settings,
      // but the key is that it MUST NOT contain the secret content.
    }
  }
}
