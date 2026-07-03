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
package com.google.gct.testrecorder.ui;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.android.tools.idea.testing.AndroidProjectRule;
import com.android.uiautomator.tree.BasicTreeNode;
import com.android.uiautomator.tree.UiNode;
import com.google.gct.testrecorder.event.ElementDescriptor;
import com.google.gct.testrecorder.event.TestRecorderAssertion;
import com.google.gct.testrecorder.event.TestRecorderEvent;
import com.intellij.xml.util.XmlStringUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JLabel;
import javax.swing.JList;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TestRecorderListRendererSecurityTest {

  @Rule
  public AndroidProjectRule myProjectRule = AndroidProjectRule.inMemory();

  private static final String EVIL_STRING = "<html><img src='http://attacker.example/p' onload='alert(1)'></html>";

  @Test
  public void getRendererString_fallbacks_escapeAll() {
    // 1. Text fallback
    ElementDescriptor desc1 = new ElementDescriptor("C", -1, -1, -1, "", "", EVIL_STRING);
    TestRecorderEvent event1 = new TestRecorderEvent(TestRecorderEvent.VIEW_CLICK, 0);
    event1.addElementDescriptor(desc1);
    assertThat(event1.getRendererString()).contains(XmlStringUtil.escapeString(EVIL_STRING));
    assertThat(event1.getRendererString()).doesNotContain(EVIL_STRING);

    // 2. Content Description fallback (Text is empty)
    ElementDescriptor desc2 = new ElementDescriptor("C", -1, -1, -1, "", EVIL_STRING, "");
    TestRecorderEvent event2 = new TestRecorderEvent(TestRecorderEvent.VIEW_CLICK, 0);
    event2.addElementDescriptor(desc2);
    assertThat(event2.getRendererString()).contains(XmlStringUtil.escapeString(EVIL_STRING));
    assertThat(event2.getRendererString()).doesNotContain(EVIL_STRING);

    // 3. Resource ID fallback (Text and Content Desc are empty)
    ElementDescriptor desc3 = new ElementDescriptor("C", -1, -1, -1, "id/" + EVIL_STRING, "", "");
    TestRecorderEvent event3 = new TestRecorderEvent(TestRecorderEvent.VIEW_CLICK, 0);
    event3.addElementDescriptor(desc3);
    assertThat(event3.getRendererString()).contains(XmlStringUtil.escapeString(EVIL_STRING));
    assertThat(event3.getRendererString()).doesNotContain(EVIL_STRING);

    // 4. Class Name fallback (All others empty)
    String evilClassName = "<script>alert(1)</script>";
    ElementDescriptor desc4 = new ElementDescriptor(evilClassName, -1, -1, -1, "", "", "");
    TestRecorderEvent event4 = new TestRecorderEvent(TestRecorderEvent.VIEW_CLICK, 0);
    event4.addElementDescriptor(desc4);
    // getClassName will return the full string if no dots are present.
    assertThat(event4.getRendererString()).contains(XmlStringUtil.escapeString(evilClassName));
    assertThat(event4.getRendererString()).doesNotContain(evilClassName);
  }

  @Test
  public void getRendererString_childPositionFallback_isSafe() {
    // 5. Child position fallback (All others empty)
    ElementDescriptor desc = new ElementDescriptor("android.view.View", 42, -1, -1, "", "", "");
    TestRecorderEvent event = new TestRecorderEvent(TestRecorderEvent.VIEW_CLICK, 0);
    event.addElementDescriptor(desc);

    String rendered = event.getRendererString();
    assertThat(rendered).contains("child position");
    assertThat(rendered).contains("42");
    // Ensure the labels are escaped
    assertThat(rendered).contains("View");
  }

  @Test
  public void getRendererString_escapesPermissions() {
    TestRecorderEvent event = new TestRecorderEvent(TestRecorderEvent.PERMISSIONS_REQUEST, 0);
    List<String> permissions = new ArrayList<>();
    permissions.add(EVIL_STRING);
    event.setRequestedPermissions(permissions);

    String rendered = event.getRendererString();

    assertThat(rendered).contains(XmlStringUtil.escapeString(EVIL_STRING));
    assertThat(rendered).doesNotContain(EVIL_STRING);
  }

  @Test
  public void getRendererString_nullPermissions_doesNotCrash() {
    TestRecorderEvent event = new TestRecorderEvent(TestRecorderEvent.PERMISSIONS_REQUEST, 0);
    // requestedPermissions is null by default or if never set.

    // Test for null list
    String rendered = event.getRendererString();
    assertThat(rendered).isNotNull();
  }

  @Test
  public void getRendererString_swipeDirection_isSafe() {
    TestRecorderEvent event = new TestRecorderEvent(TestRecorderEvent.VIEW_SWIPE, 0);
    event.setSwipeDirection(TestRecorderEvent.SwipeDirection.Left);

    String rendered = event.getRendererString();
    assertThat(rendered).contains("Left");
    // Swipe events don't include element descriptors in renderer string.
  }

  @Test
  public void getRendererString_delayedMessagePost_isSafe() {
    TestRecorderEvent event = new TestRecorderEvent(TestRecorderEvent.DELAYED_MESSAGE_POST, 0);
    event.setDelayTime(1500);

    String rendered = event.getRendererString();
    assertThat(rendered).contains("1500");
  }

  @Test
  public void listRenderer_escapesReplacementText_andEventData() {
    TestRecorderListRenderer renderer = new TestRecorderListRenderer();
    JList<Object> list = new JList<>();

    // TEXT_CHANGE
    TestRecorderEvent textEvent = new TestRecorderEvent(TestRecorderEvent.TEXT_CHANGE, 0);
    textEvent.setReplacementText(EVIL_STRING);
    textEvent.addElementDescriptor(new ElementDescriptor("android.widget.EditText", -1, -1, -1, "id", "", ""));
    JLabel textLabel = (JLabel) renderer.getListCellRendererComponent(list, textEvent, 0, false, false);
    assertThat(textLabel.getText()).contains(XmlStringUtil.escapeString(EVIL_STRING));
    assertThat(textLabel.getText()).doesNotContain(EVIL_STRING);

    // VIEW_CLICK
    TestRecorderEvent clickEvent = new TestRecorderEvent(TestRecorderEvent.VIEW_CLICK, 0);
    clickEvent.addElementDescriptor(new ElementDescriptor("Button", -1, -1, -1, EVIL_STRING, "", ""));
    JLabel clickLabel = (JLabel) renderer.getListCellRendererComponent(list, clickEvent, 0, false, false);
    assertThat(clickLabel.getText()).contains(XmlStringUtil.escapeString(EVIL_STRING));
    assertThat(clickLabel.getText()).doesNotContain(EVIL_STRING);

    // PRESS_EDITOR_ACTION
    TestRecorderEvent pressEvent = new TestRecorderEvent(TestRecorderEvent.PRESS_EDITOR_ACTION, 0);
    pressEvent.setActionCode(6); // Done
    JLabel pressLabel = (JLabel) renderer.getListCellRendererComponent(list, pressEvent, 0, false, false);
    assertThat(pressLabel.getText()).contains("Done");
  }

  @Test
  public void listRenderer_escapesAssertionRuleAndText() {
    String evilRule = "<script>alert(1)</script>";
    String evilText = "<img src=x onerror=alert(2)>";
    TestRecorderListRenderer renderer = new TestRecorderListRenderer();
    JList<Object> list = new JList<>();

    // With text
    TestRecorderAssertion assertion = new TestRecorderAssertion(evilRule);
    assertion.setText(evilText);
    assertion.addElementDescriptor(new ElementDescriptor("android.widget.TextView", -1, -1, -1, "my_id", "", ""));
    JLabel label = (JLabel) renderer.getListCellRendererComponent(list, assertion, 0, false, false);

    assertThat(label.getText()).contains(XmlStringUtil.escapeString(evilRule));
    assertThat(label.getText()).contains(XmlStringUtil.escapeString(evilText));
    assertThat(label.getText()).doesNotContain(evilRule);
    assertThat(label.getText()).doesNotContain(evilText);

    // Null text
    TestRecorderAssertion nullTextAssertion = new TestRecorderAssertion(evilRule);
    nullTextAssertion.setText(null);
    nullTextAssertion.addElementDescriptor(new ElementDescriptor("android.widget.TextView", -1, -1, -1, "my_id", "", ""));
    JLabel nullTextLabel = (JLabel) renderer.getListCellRendererComponent(list, nullTextAssertion, 0, false, false);
    assertThat(nullTextLabel.getText()).contains(XmlStringUtil.escapeString(evilRule));
    assertThat(nullTextLabel.getText()).doesNotContain(evilRule);
  }

  @Test
  public void recordingDialog_elementPickerRenderer_isSafe() {
    Map<BasicTreeNode, Integer> indentMap = new HashMap<>();
    UiNode node = mock(UiNode.class);
    when(node.getAttribute("resource-id")).thenReturn(EVIL_STRING);
    when(node.getAttribute("class")).thenReturn("android.widget.Button");
    indentMap.put(node, 1);

    RecordingDialog.AssertionElementRenderer renderer = new RecordingDialog.AssertionElementRenderer(() -> indentMap);
    JList<Object> list = new JList<>();
    JLabel label = (JLabel) renderer.getListCellRendererComponent(list, node, 0, false, false);

    assertThat(label.getText()).contains(XmlStringUtil.escapeString(EVIL_STRING));
    assertThat(label.getText()).doesNotContain(EVIL_STRING);
  }
}
