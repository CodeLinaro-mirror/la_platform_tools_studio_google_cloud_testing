/*
 * Copyright (C) 2017 The Android Open Source Project
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

import com.google.common.collect.Lists;
import com.google.gct.testrecorder.event.ElementDescriptor;
import com.google.gct.testrecorder.event.TestRecorderAssertion;
import com.google.gct.testrecorder.event.TestRecorderEvent;

import java.util.List;

import static com.google.gct.testrecorder.event.TestRecorderAssertion.EXISTS;
import static com.google.gct.testrecorder.event.TestRecorderAssertion.TEXT_IS;
import static com.google.gct.testrecorder.event.TestRecorderEvent.SwipeDirection.Right;

public class EventsCreator {

  public static List<Object> createEvents(long timestamp) {
    List<Object> events = Lists.newLinkedList();

    TestRecorderEvent viewClickEvent = new TestRecorderEvent(TestRecorderEvent.VIEW_CLICK, timestamp);
    viewClickEvent.addElementDescriptor(new ElementDescriptor("ElementClass.ElementInnerClass", -1, -1, 0, "resourceId1", "content description 1", ""));
    viewClickEvent.addElementDescriptor(new ElementDescriptor("ParentClass1", -1, -1, 1, "parentResourceId1", "", ""));
    viewClickEvent.addElementDescriptor(new ElementDescriptor("ParentClass2", -1, -1, 2, "parentResourceId2", "parent content description 1", ""));
    events.add(viewClickEvent);

    TestRecorderEvent viewClickEvent2 = new TestRecorderEvent(TestRecorderEvent.VIEW_CLICK, timestamp);
    viewClickEvent2.addElementDescriptor(new ElementDescriptor("ElementClass", -1, -1, 1, "", "", ""));
    viewClickEvent2.addElementDescriptor(new ElementDescriptor("ParentClass1", -1, -1, 0, "", "", ""));
    events.add(viewClickEvent2);

    TestRecorderEvent viewClickEvent3 = new TestRecorderEvent(TestRecorderEvent.VIEW_CLICK, timestamp);
    viewClickEvent3.addElementDescriptor(new ElementDescriptor("ElementClass", 5, -1, 0, "elementResourceId", "", ""));
    viewClickEvent3.addElementDescriptor(new ElementDescriptor("GridClass", -1, -1, 0, "gridResourceId", "", ""));
    viewClickEvent3.addElementDescriptor(new ElementDescriptor("ParentClass1", -1, -1, 0, "gridParentResourceId", "", ""));
    events.add(viewClickEvent3);

    TestRecorderAssertion assertion = new TestRecorderAssertion(EXISTS);
    assertion.addElementDescriptor(new ElementDescriptor("ElementClass", -1, -1, -1, "resourceId2", "content description 2", ""));
    events.add(assertion);

    TestRecorderEvent viewLongClickEvent = new TestRecorderEvent(TestRecorderEvent.VIEW_LONG_CLICK, timestamp);
    viewLongClickEvent.addElementDescriptor(new ElementDescriptor("ElementClass", -1, -1,-1, "resourceId2", "content description 2", ""));
    events.add(viewLongClickEvent);

    TestRecorderEvent listItemClickEvent = new TestRecorderEvent(TestRecorderEvent.LIST_ITEM_CLICK, timestamp);
    listItemClickEvent.addElementDescriptor(new ElementDescriptor("ElementClass", -1, -1, 2, "resourceId3", "", ""));
    listItemClickEvent.addElementDescriptor(new ElementDescriptor("ParentClass1", -1, -1, 0, "parentResourceId3", "", ""));
    listItemClickEvent.addElementDescriptor(new ElementDescriptor("ParentClass2", -1, -1, 1, "parentResourceId4", "", ""));
    events.add(listItemClickEvent);

    TestRecorderEvent pressBackEvent = new TestRecorderEvent(TestRecorderEvent.PRESS_BACK, timestamp);
    events.add(pressBackEvent);

    TestRecorderEvent delayedMessagePostEvent = new TestRecorderEvent(TestRecorderEvent.DELAYED_MESSAGE_POST, timestamp);
    delayedMessagePostEvent.setDelayTime(1500);
    events.add(delayedMessagePostEvent);

    TestRecorderEvent textChangeEvent = new TestRecorderEvent(TestRecorderEvent.TEXT_CHANGE, timestamp);
    textChangeEvent.addElementDescriptor(new ElementDescriptor("ElementClass", -1, -1, 1, "resourceId4", "content description 3", "original text"));
    textChangeEvent.addElementDescriptor(new ElementDescriptor("ParentClass1", -1, -1, 0, "parentResourceId5", "", ""));
    textChangeEvent.addElementDescriptor(new ElementDescriptor("ParentClass2", -1, -1, 2, "parentResourceId6", "parent content description 2", ""));
    textChangeEvent.setReplacementText("replacement \n\ntext\n");
    events.add(textChangeEvent);

    TestRecorderAssertion assertion2 = new TestRecorderAssertion(TEXT_IS);
    assertion2.addElementDescriptor(new ElementDescriptor("ElementClass", -1, -1, 1, "resourceId4", "content description 3", "replacement \n\ntext\n"));
    assertion2.addElementDescriptor(new ElementDescriptor("ParentClass1", -1, -1, 0, "parentResourceId5", "", ""));
    assertion2.addElementDescriptor(new ElementDescriptor("ParentClass2", -1, -1, 2, "parentResourceId6", "parent content description 2", ""));
    assertion2.setText("replacement \n\ntext\n");
    events.add(assertion2);

    TestRecorderEvent pressEditorActionEvent = new TestRecorderEvent(TestRecorderEvent.PRESS_EDITOR_ACTION, timestamp);
    pressEditorActionEvent.addElementDescriptor(new ElementDescriptor("ElementClass", -1, -1, 1, "resourceId4", "content description 3", "replacement \n\ntext\n"));
    pressEditorActionEvent.addElementDescriptor(new ElementDescriptor("ParentClass1", -1, -1, 0, "parentResourceId5", "", ""));
    pressEditorActionEvent.addElementDescriptor(new ElementDescriptor("ParentClass2", -1, -1, 2, "parentResourceId6", "parent content description 2", ""));
    events.add(pressEditorActionEvent);

    TestRecorderEvent swipeEvent = new TestRecorderEvent(TestRecorderEvent.VIEW_SWIPE, timestamp);
    swipeEvent.addElementDescriptor(new ElementDescriptor("ElementClass", -1, -1, 0, "resourceId5", "content description 4", ""));
    swipeEvent.addElementDescriptor(new ElementDescriptor("ParentClass1", -1, -1, 1, "parentResourceId7", "", "parent text"));
    swipeEvent.setSwipeDirection(Right);
    events.add(swipeEvent);

    TestRecorderEvent viewAdapterItemClickEvent = new TestRecorderEvent(TestRecorderEvent.VIEW_CLICK, timestamp);
    viewAdapterItemClickEvent.addElementDescriptor(new ElementDescriptor("ElementClass", -1, 3, -1, "", "", ""));
    viewAdapterItemClickEvent.addElementDescriptor(new ElementDescriptor("ParentClass1", -1, -1, -1, "list", "", ""));
    events.add(viewAdapterItemClickEvent);

    TestRecorderEvent trailingDelayedMessagePostEvent = new TestRecorderEvent(TestRecorderEvent.DELAYED_MESSAGE_POST, timestamp);
    trailingDelayedMessagePostEvent.setDelayTime(2500);
    events.add(trailingDelayedMessagePostEvent);

    return events;
  }

}
