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

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.gct.testrecorder.codegen.MatcherBuilder.Kind.ClassName;
import static com.google.gct.testrecorder.codegen.MatcherBuilder.Kind.ContentDescription;
import static com.google.gct.testrecorder.codegen.MatcherBuilder.Kind.Id;
import static com.google.gct.testrecorder.codegen.MatcherBuilder.Kind.Text;
import static com.google.gct.testrecorder.event.TestRecorderAssertion.EXISTS;
import static com.google.gct.testrecorder.event.TestRecorderAssertion.NOT_EXISTS;
import static com.google.gct.testrecorder.event.TestRecorderAssertion.TEXT_IS;
import static com.google.gct.testrecorder.util.StringHelper.boxString;
import static com.google.gct.testrecorder.util.StringHelper.getClassName;
import static com.google.gct.testrecorder.util.StringHelper.lowerCaseFirstCharacter;
import static com.google.gct.testrecorder.util.StringHelper.parseId;

import com.android.SdkConstants;
import com.android.annotations.VisibleForTesting;
import com.android.resources.ResourceType;
import com.android.utils.Pair;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gct.testrecorder.event.ElementAction;
import com.google.gct.testrecorder.event.ElementDescriptor;
import com.google.gct.testrecorder.event.TestRecorderAssertion;
import com.google.gct.testrecorder.event.TestRecorderEvent;
import com.google.gct.testrecorder.settings.TestRecorderSettings;
import com.intellij.lang.java.lexer.JavaLexer;
import com.intellij.openapi.project.Project;
import com.intellij.pom.java.LanguageLevel;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.android.sdk.AndroidTargetData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class TestCodeMapper {

  private static final String VIEW_VARIABLE_CLASS_NAME = "ViewInteraction";
  private static final String DATA_VARIABLE_CLASS_NAME = "DataInteraction";

  private final String myApplicationId;
  private final Project myProject;
  @Nullable private final AndroidTargetData myAndroidTargetData;
  private final boolean myIsKotlinTestClass;
  private boolean myIsChildAtPositionAdded;
  private boolean myIsRecyclerViewActionAdded;
  private Set<String> myRequestedPermissions = Sets.newHashSet();

  /**
   * Map of variable_name -> first_unused_index. This map is used to ensure that variable names are unique.
   */
  private final Map<String, Integer> myVariableNameIndexes = Maps.newHashMap();


  public TestCodeMapper(String applicationId, Project project, @Nullable AndroidTargetData androidTargetData, boolean isKotlinTestClass) {
    myApplicationId = applicationId;
    myProject = project;
    myAndroidTargetData = androidTargetData;
    myIsKotlinTestClass = isKotlinTestClass;
  }

  public List<String> getTestCodeLinesForEvent(TestRecorderEvent event) {
    List<String> testCodeLines = Lists.newArrayList();

    if (event.isPermissionsRequest()) {
      myRequestedPermissions.addAll(event.getRequestedPermissions());
      return testCodeLines;
    }

    if (event.isPressBack()) {
      testCodeLines.add("pressBack()" + getStatementTerminator());
      return testCodeLines;
    }

    if (event.isViewClick() && isOverflowMenuButton(event.getElementClassName())) {
      testCodeLines.add("openActionBarOverflowOrOptionsMenu(getInstrumentation().getTargetContext())" + getStatementTerminator());
      return testCodeLines;
    }

    if (event.isDelayedMessagePost()) {
      testCodeLines.add(createSleepStatement(event.getDelayTime()));
      return testCodeLines;
    }

    String variableName = addPickingStatement(event, testCodeLines);
    int recyclerViewChildPosition = event.getElementRecyclerViewChildPosition();
    if (event.isSwipe()) {
      testCodeLines.add(createActionStatement(variableName, recyclerViewChildPosition, "swipe" + event.getSwipeDirection().name() + "()", false));
    } else if (event.isPressEditorAction()) {
      // TODO: If this is the same element that was just edited, consider reusing the same view interaction (i.e., variable name).
      testCodeLines.add(createActionStatement(variableName, recyclerViewChildPosition, "pressImeActionButton()", false));
    } else if (event.isClickEvent()) {
      testCodeLines.add(createActionStatement(variableName, recyclerViewChildPosition, event.isViewLongClick() ? "longClick()" : "click()", event.canScrollTo()));
    } else if (event.isTextChange()) {
      String closeSoftKeyboardAction = doesNeedStandaloneCloseSoftKeyboardAction(event) ? "" : ", closeSoftKeyboard()";
      testCodeLines.add(createActionStatement(
        variableName, recyclerViewChildPosition, "replaceText(" + boxString(event.getReplacementText()) + ")" + closeSoftKeyboardAction, event.canScrollTo()));
    } else {
      throw new RuntimeException("Unsupported event type: " + event.getEventType());
    }

    if (doesNeedStandaloneCloseSoftKeyboardAction(event)) {
      addStandaloneCloseSoftKeyboardAction(event, testCodeLines);
    }

    return testCodeLines;
  }

  private void addStandaloneCloseSoftKeyboardAction(TestRecorderEvent textChangeEvent, List<String> testCodeLines) {
    // Simulate an artificial close soft keyboard event.
    TestRecorderEvent closeSoftKeyboardEvent = new TestRecorderEvent(textChangeEvent.getEventType(), textChangeEvent.getTimestamp());

    List<ElementDescriptor> originalElementDescriptors = textChangeEvent.getElementDescriptorList();
    assert !originalElementDescriptors.isEmpty();

    ElementDescriptor originalDescriptor = originalElementDescriptors.get(0);
    // Copy the first descriptor except for the text, which will become the replacement text.
    ElementDescriptor updatedDescriptor =
      new ElementDescriptor(originalDescriptor.getClassName(), originalDescriptor.getRecyclerViewChildPosition(),
                            originalDescriptor.getAdapterViewChildPosition(), originalDescriptor.getGroupViewChildPosition(),
                            originalDescriptor.getResourceId(), originalDescriptor.getContentDescription(),
                            textChangeEvent.getReplacementText());
    closeSoftKeyboardEvent.addElementDescriptor(updatedDescriptor);
    // Copy the rest of the descriptors unmodified.
    for (int i = 1; i < originalElementDescriptors.size(); i++) {
      closeSoftKeyboardEvent.addElementDescriptor(originalElementDescriptors.get(i));
    }

    testCodeLines.add("");
    String variableName = addPickingStatement(closeSoftKeyboardEvent, testCodeLines);
    testCodeLines.add(createActionStatement(variableName, closeSoftKeyboardEvent.getElementRecyclerViewChildPosition(), "closeSoftKeyboard()", false));
  }

  private static boolean doesNeedStandaloneCloseSoftKeyboardAction(TestRecorderEvent event) {
    // Make text edit in a RecyclerView child always require a standalone close soft keyboard action since actionOnItemAtPosition
    // accepts only a single action.
    return TestRecorderSettings.getInstance().USE_TEXT_FOR_ELEMENT_MATCHING && event.isTextChange()
           && (!isNullOrEmpty(event.getElementText()) || event.getElementRecyclerViewChildPosition() != -1);
  }

  private String createSleepStatement(long sleepTime) {
    return String.format(" // Added a sleep statement to match the app's execution delay.\n"
                         + " // The recommended way to handle such scenarios is to use Espresso idling resources:\n "
                         + " // https://google.github.io/android-testing-support-library/docs/espresso/idling-resource/index.html\n"
                         + (myIsKotlinTestClass
                            ? "Thread.sleep(%s)"
                            : "try {\n Thread.sleep(%s);\n } catch (InterruptedException e) {\n e.printStackTrace();\n }"), sleepTime);
  }

  @VisibleForTesting
  boolean isOverflowMenuButton(String className) {
    if (StringUtils.isEmpty(className)) {
      return false;
    }
    return className.startsWith("android.") && className.endsWith(".widget.ActionMenuPresenter.OverflowMenuButton");
  }

  private String createActionStatement(String variableName, int recyclerViewChildPosition, String action, boolean addScrollTo) {
    myIsRecyclerViewActionAdded = myIsRecyclerViewActionAdded || recyclerViewChildPosition != -1;

    // No need to explicitly scroll to perform an action on a RecyclerView child.
    String completeAction = (addScrollTo && recyclerViewChildPosition == -1 ? "scrollTo(), " : "") + action;
    completeAction = recyclerViewChildPosition == -1
                     ? completeAction
                     : getActionOnItemAtPositionMethodCallPrefix() + recyclerViewChildPosition + ", " + completeAction + ")";

    return variableName + ".perform(" + completeAction + ")" + getStatementTerminator();
  }

  private String getActionOnItemAtPositionMethodCallPrefix() {
    return myIsKotlinTestClass ? "actionOnItemAtPosition<ViewHolder>(" : "actionOnItemAtPosition(";
  }

  public List<String> getTestCodeLinesForAssertion(TestRecorderAssertion assertion) {
    List<String> testCodeLines = new LinkedList<String>();

    String rule = assertion.getRule();
    String variableName = addPickingStatement(assertion, testCodeLines);

    if (NOT_EXISTS.equals(rule)) {
      testCodeLines.add(variableName + ".check(doesNotExist())" + getStatementTerminator());
    } else if (EXISTS.equals(rule)) {
      testCodeLines.add(variableName + ".check(matches(isDisplayed()))" + getStatementTerminator());
    } else if (TEXT_IS.equals(rule)) {
      String text = assertion.getText();
      testCodeLines.add(variableName + ".check(matches(withText(" + boxString(text) + ")))" + getStatementTerminator());
    } else {
      throw new RuntimeException("Unsupported assertion rule: " + rule);
    }

    return testCodeLines;
  }

  private String addPickingStatement(ElementAction action, List<String> testCodeLines) {
    if (isAdapterViewAction(action)) {
      return addDataPickingStatement(action, testCodeLines);
    }
    return addViewPickingStatement(action, testCodeLines);
  }

  private String addViewPickingStatement(ElementAction action, List<String> testCodeLines) {
    // Skip a level for RecyclerView children as they will be identified through their position.
    int startIndex = action.getElementRecyclerViewChildPosition() != -1 && action.getElementDescriptorsCount() > 1 ? 1 : 0;

    String variableName = generateVariableNameFromElementClassName(action.getElementDescriptor(startIndex).getClassName(), VIEW_VARIABLE_CLASS_NAME);
    testCodeLines.add(getVariableTypeDeclaration(true) + " " + variableName + " = onView(\n" +
                      generateElementHierarchyConditions(action, startIndex) + ")" + getStatementTerminator());
    return variableName;
  }

  private String addDataPickingStatement(ElementAction action, List<String> testCodeLines) {
    String variableName = generateVariableNameFromElementClassName(action.getElementClassName(), DATA_VARIABLE_CLASS_NAME);
    // TODO: Add '.onChildView(...)' when we support AdapterView beyond the immediate parent of the affected element.
    testCodeLines.add(getVariableTypeDeclaration(false) + " " + variableName + " = onData(anything())\n.inAdapterView(" +
                      generateElementHierarchyConditions(action, 1) + ")\n.atPosition(" + action.getElementAdapterViewChildPosition() +
                      ")" + getStatementTerminator());
    return variableName;
  }

  private String getVariableTypeDeclaration(boolean isOnViewInteraction) {
    if (myIsKotlinTestClass) {
      return "val";
    }
    return isOnViewInteraction ? VIEW_VARIABLE_CLASS_NAME : DATA_VARIABLE_CLASS_NAME;
  }

  private String getStatementTerminator() {
    return myIsKotlinTestClass ? "" : ";";
  }

  // TODO: This will not detect an adapter view action if the affected element's immediate parent is not an AdapterView
  // (e.g., clicking on a button, whose parent's parent is AdapterView will not be detected as an AdapterView action).
  private static boolean isAdapterViewAction(ElementAction action) {
    return action.getElementAdapterViewChildPosition() != -1 && action.getElementDescriptorsCount() > 1;
  }

  private String generateVariableNameFromElementClassName(@Nullable String elementClassName, @NotNull String defaultClassName) {
    if (isNullOrEmpty(elementClassName)) {
      return generateVariableNameFromTemplate(defaultClassName);
    }
    return generateVariableNameFromTemplate(getClassName(elementClassName));
  }

  private String generateVariableNameFromTemplate(String template) {
    String variableName = lowerCaseFirstCharacter(template);
    if (JavaLexer.isKeyword(variableName, LanguageLevel.HIGHEST)) {
      variableName += "_";
    }

    Integer unusedIndex = myVariableNameIndexes.get(variableName);
    if (unusedIndex == null) {
      myVariableNameIndexes.put(variableName, 2);
      return variableName;
    }

    myVariableNameIndexes.put(variableName, unusedIndex + 1);
    return variableName + unusedIndex;
  }

  private String generateElementHierarchyConditions(ElementAction action, int startIndex) {
    List<ElementDescriptor> elementDescriptors = action.getElementDescriptorList();

    if (elementDescriptors.size() <= startIndex) {
      return "UNKNOWN";
    }
    return generateElementHierarchyConditionsRecursively(action instanceof TestRecorderAssertion, !action.canScrollTo(),
                                                         elementDescriptors, startIndex);
  }

  private String generateElementHierarchyConditionsRecursively(boolean isAssertionConditions, boolean checkIsDisplayed,
                                                               List<ElementDescriptor> elementDescriptors, int index) {
    // Add isDisplayed() only to the innermost element.
    boolean addIsDisplayed = checkIsDisplayed && index == 0;

    ElementDescriptor elementDescriptor = elementDescriptors.get(index);
    MatcherBuilder matcherBuilder = new MatcherBuilder(myProject, myIsKotlinTestClass);

    int lastIndex = elementDescriptors.size() - 1;

    if (elementDescriptor.isEmpty()
        // Cannot use child position for the last element, since no parent descriptor available.
        || index == lastIndex && elementDescriptor.isEmptyIgnoringChildPosition()
        || index == 0 && isLoginRadioButton(elementDescriptors)) {
      matcherBuilder.addMatcher(ClassName, elementDescriptor.getClassName(), true, isAssertionConditions);
    } else {
      // Do not use android framework ids that are not visible to the compiler.
      String resourceId = elementDescriptor.getResourceId();
      if (isAndroidFrameworkPrivateId(resourceId)) {
        matcherBuilder.addMatcher(ClassName, elementDescriptor.getClassName(), true, isAssertionConditions);
      } else {
        matcherBuilder.addMatcher(Id, convertIdToTestCodeFormat(resourceId), false, isAssertionConditions);
      }

      if (TestRecorderSettings.getInstance().USE_TEXT_FOR_ELEMENT_MATCHING) {
        matcherBuilder.addMatcher(Text, elementDescriptor.getText(), true, isAssertionConditions);
      }

      if (TestRecorderSettings.getInstance().USE_CONTENT_DESCRIPTION_FOR_ELEMENT_MATCHING) {
        matcherBuilder.addMatcher(ContentDescription, elementDescriptor.getContentDescription(), true, isAssertionConditions);
      }
    }

    // TODO: Consider minimizing the generated statement to improve test's readability and maintainability (e.g., by capping parent hierarchy).

    // The last element has no parent.
    if (index == lastIndex) {
      if (matcherBuilder.getMatcherCount() > 1 || addIsDisplayed) {
        return "allOf(" + matcherBuilder.getMatchers() + (addIsDisplayed ? ", isDisplayed()" : "") + ")";
      }
      return matcherBuilder.getMatchers();
    }

    boolean addAllOf = matcherBuilder.getMatcherCount() > 0 || addIsDisplayed;
    int groupViewChildPosition = elementDescriptor.getGroupViewChildPosition();

    // Do not use child position for ViewPager children as it changes dynamically and non-deterministically.
    if (SdkConstants.CLASS_VIEW_PAGER.isEquals(elementDescriptors.get(index + 1).getClassName())
        // Do not use child position for assertions due to potential child position mismatches between UIAutomator (when assertion is
        // recorded) and Espresso (when assertion is replayed/verified).
        || isAssertionConditions) {
      groupViewChildPosition = -1;
    }

    myIsChildAtPositionAdded = myIsChildAtPositionAdded || groupViewChildPosition != -1;

    return (addAllOf ? "allOf(" : "") + matcherBuilder.getMatchers() + (matcherBuilder.getMatcherCount() > 0 ? ",\n" : "")
           + (groupViewChildPosition != -1 ? "childAtPosition(\n" : "withParent(")
           + generateElementHierarchyConditionsRecursively(isAssertionConditions, checkIsDisplayed, elementDescriptors, index + 1)
           + (groupViewChildPosition != -1 ? ",\n" + groupViewChildPosition : "") + ")"
           + (addIsDisplayed ? ",\nisDisplayed()" : "") + (addAllOf ? ")" : "");
  }

  private boolean isAndroidFrameworkPrivateId(String resourceId) {
    Pair<String, String> parsedId = parseId(resourceId);
    return myAndroidTargetData != null && parsedId != null && "android".equals(parsedId.getFirst())
           && !myAndroidTargetData.isResourcePublic(ResourceType.ID.getName(), parsedId.getSecond());
  }

  /**
   * TODO: This is a temporary workaround for picking a login option in a username-agnostic way
   * such that the generated test is generic enough to run on other devices.
   * TODO: Also, it assumes a single radio button choice (such that it could be identified by the class name).
   */
  private boolean isLoginRadioButton(List<ElementDescriptor> elementDescriptors) {
    if (elementDescriptors.size() > 1 && elementDescriptors.get(0).getClassName().endsWith(".widget.AppCompatRadioButton")
        && "R.id.welcome_account_list".equals(convertIdToTestCodeFormat(elementDescriptors.get(1).getResourceId()))) {
      return true;
    }

    return false;
  }

  private String convertIdToTestCodeFormat(String resourceId) {
    Pair<String, String> parsedId = parseId(resourceId);

    if (parsedId == null) {
      // Parsing failed, return the raw id.
      return resourceId;
    }

    String testCodeId = "R.id." + parsedId.getSecond();
    if (!parsedId.getFirst().equals(myApplicationId)) {
      // Only the app's resource package will be explicitly imported, so use a fully qualified id for other packages.
      testCodeId = parsedId.getFirst() + "." + testCodeId;
    }

    return testCodeId;
  }

  public boolean isChildAtPositionAdded() {
    return myIsChildAtPositionAdded;
  }

  public boolean isRecyclerViewActionAdded() {
    return myIsRecyclerViewActionAdded;
  }

  public Set<String> getRequestedPermissions() {
    return ImmutableSet.copyOf(myRequestedPermissions);
  }
}
