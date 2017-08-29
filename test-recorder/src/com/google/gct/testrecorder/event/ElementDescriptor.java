/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.google.gct.testrecorder.event;

import static com.google.common.base.Strings.isNullOrEmpty;

public class ElementDescriptor {
  /**
   * Fully qualified class name of the element.
   */
  private final String className;


  // Attribute fields:

  /**
   * Position of this element in an adapter's data set.
   * The value of -1 signifies that the child position is unknown (e.g., since its parent is not an {@code AdapterView}).
   */
  private final int adapterViewChildPosition;

  /**
   * Position of this element in a {@code GroupView}.
   * The value of -1 signifies that the child position is unknown (e.g., since its parent is not a {@code GroupView})
   * or irrelevant (e.g., if its parent is an {@code AdapterView}, in which case the relevant position is {@code adapterViewChildPosition}).
   */
  private final int groupViewChildPosition;

  private final String resourceId;
  private final String contentDescription;
  private final String text;


  public ElementDescriptor(String className, int adapterViewChildPosition, int groupViewChildPosition, String resourceId,
                           String contentDescription, String text) {

    this.className = className;
    this.adapterViewChildPosition = adapterViewChildPosition;
    this.groupViewChildPosition = groupViewChildPosition;
    this.resourceId = resourceId;
    this.contentDescription = contentDescription;
    this.text = text;
  }

  public String getClassName() {
    return className;
  }

  public int getAdapterViewChildPosition() {
    return adapterViewChildPosition;
  }

  public int getGroupViewChildPosition() {
    return groupViewChildPosition;
  }

  public String getResourceId() {
    return resourceId;
  }

  public String getContentDescription() {
    return contentDescription;
  }

  public String getText() {
    return text;
  }

  /**
   * Returns {@code true} iff all attribute fields are absent.
   */
  public boolean isEmpty() {
    return adapterViewChildPosition == -1 && groupViewChildPosition == -1 && isEmptyIgnoringChildPosition();
  }

  /**
   * Returns {@code true} iff all attribute fields not considering {@code childPosition} are absent.
   */
  public boolean isEmptyIgnoringChildPosition() {
    return isNullOrEmpty(resourceId) && isNullOrEmpty(text) && isNullOrEmpty(contentDescription);
  }
}
