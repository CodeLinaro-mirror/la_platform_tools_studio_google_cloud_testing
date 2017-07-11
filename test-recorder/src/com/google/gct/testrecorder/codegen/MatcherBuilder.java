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
package com.google.gct.testrecorder.codegen;

import com.intellij.openapi.project.Project;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.gct.testrecorder.codegen.MatcherBuilder.Kind.ClassName;
import static com.google.gct.testrecorder.util.ClassHelper.getInternalName;
import static com.google.gct.testrecorder.util.StringHelper.boxString;

public class MatcherBuilder {
  public enum Kind {Id, Text, ContentDescription, ClassName}

  private final Project myProject;

  private int myMatcherCount = 0;
  private final StringBuilder myMatchers = new StringBuilder();

  public MatcherBuilder(Project project) {
    myProject = project;
  }

  public void addMatcher(Kind kind, String matchedString, boolean shouldBox, boolean isAssertionMatcher) {
    if (!isNullOrEmpty(matchedString)) {
      if (kind == ClassName && !isAssertionMatcher) {
        matchedString = getInternalName(myProject, matchedString);
      }

      if (myMatcherCount > 0) {
        myMatchers.append(", ");
      }

      if (kind == ClassName && isAssertionMatcher) {
       myMatchers.append("IsInstanceOf.<View>instanceOf(").append(matchedString).append(".class)");
      } else {
        myMatchers.append("with").append(kind.name()).append(kind == ClassName ? "(is(" : "(")
          .append(shouldBox ? boxString(matchedString) : matchedString).append(kind == ClassName ? "))" : ")");
      }

      myMatcherCount++;
    }
  }

  public int getMatcherCount() {
    return myMatcherCount;
  }

  public String getMatchers() {
    return myMatchers.toString();
  }

}
