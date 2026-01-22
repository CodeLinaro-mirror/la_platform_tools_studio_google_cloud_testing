/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.google.gct.testing.filter

import com.android.tools.idea.testing.ProjectServiceRule
import com.google.common.truth.Truth.assertThat
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.impl.MultipleFilesHyperlinkInfo
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock

/** Tests for [TestLabExceptionFilter] */
class TestLabExceptionFilterTest {
  private val myProjectRule = ProjectRule()
  private val myProject
    get() = myProjectRule.project

  private val mockGlobalSearchScope: GlobalSearchScope = mock()

  private val myExceptionFiles =
    listOf(
      ClassNameFileTuple("TestClass1", "com.test.package.TestClass1", "TestClass1.kt"),
      ClassNameFileTuple("TestClass2", "com.test.package.TestClass2", "TestClass2.kt"),
      ClassNameFileTuple("TestClass2", "com.test.package2.TestClass2", "TestClass2.kt"),
      ClassNameFileTuple("TestClass3", "com.test.TestClass3", "TestClass3.kt"),
      ClassNameFileTuple(
        "TestClass4",
        "com.test.additional.package.string.TestClass4",
        "TestClass4.kt",
      ),
    )

  @get:Rule
  val myRule =
    RuleChain(
      myProjectRule,
      ProjectServiceRule(myProjectRule, PsiShortNamesCache::class.java) {
        FakePsiShortNamesCache(myProject, myExceptionFiles)
      },
    )

  private lateinit var myTestLabFilter: TestLabExceptionFilter

  @Before
  fun setup() {
    myTestLabFilter = TestLabExceptionFilter(myProject, mockGlobalSearchScope)
  }

  @Test
  fun applyFilter_basic() {
    val line =
      "08-07 16:44:16.268: E/TestRunner(9723): \tat com.test.package.TestClass2.fail(TestClass2.kt:34)"
    assertFilteredResult(line, myTestLabFilter.applyFilter(line, line.length)!!)
  }

  @Test
  fun applyFilter_shortPackageName() {
    val line =
      "08-07 16:44:16.268: E/TestRunner(9723): \tat com.test.TestClass3.fail(TestClass3.kt:23)"
    assertFilteredResult(line, myTestLabFilter.applyFilter(line, line.length)!!)
  }

  @Test
  fun applyFilter_nativeMethod() {
    val line =
      "08-07 16:44:16.268: E/TestRunner(9723): \tat com.test.package.TestClass1.failsAgain(Native Method)"
    assertFilteredResult(line, myTestLabFilter.applyFilter(line, line.length)!!, "Native Method")
  }

  @Test
  fun applyFilter_unknownSource() {
    val line =
      "08-07 16:44:16.268: E/TestRunner(9723): \tat com.test.package.TestClass1.anotherFail(Unknown Source)"
    assertFilteredResult(line, myTestLabFilter.applyFilter(line, line.length)!!, "Unknown Source")
  }

  @Test
  fun applyFilter_noMatch() {
    val line =
      "08-07 16:44:16.268: E/TestRunner(9723): \tat com.test.package.NoMatchClass.fail(Unknown Source)"
    assert(myTestLabFilter.applyFilter(line, line.length) == null)
  }

  @Test
  fun applyFilter_multipleMatch() {
    val line =
      "08-07 16:44:16.268: E/TestRunner(9723): \tat com.test.package2.TestClass2.fail(TestClass2.kt:34)"
    assertFilteredResult(line, myTestLabFilter.applyFilter(line, line.length)!!)
  }

  @Test
  fun applyFilter_multipleNestedClass() {
    val line =
      "08-07 16:44:16.268: E/TestRunner(9723): \tat com.test.package2.TestClass2\$NestedClass1\$NestedClass2.fail(TestClass2.kt:34)"
    assertFilteredResult(line, myTestLabFilter.applyFilter(line, line.length)!!)
  }

  @Test
  fun applyFilter_generatedClass() {
    val line =
      "08-07 16:44:16.268: E/TestRunner(9723): \tat com.test.package2.TestClass2$1.fail(TestClass2.kt:34)"
    assertFilteredResult(line, myTestLabFilter.applyFilter(line, line.length)!!)
  }

  @Test
  fun applyFilter_catchException() {
    val line =
      "2023-08-14 03:08:24.346  1154-1524  Conscrypt               com.google.android.gms               W  \tat com.google.android.gms.org.conscrypt.Platform.setSocketWriteTimeout(:com.google.android.gms@221819047@22.18.19 (190800-449480960):2)"
    assert(myTestLabFilter.applyFilter(line, line.length) == null)
  }

  @Test
  fun applyFilter_additionalPackageString() {
    val line = "\tat com.test.additional.package.string.TestClass4.throw(TestClass4.kt:2)"
    assertFilteredResult(line, myTestLabFilter.applyFilter(line, line.length)!!)
  }

  @Test
  fun applyFilter_missingLineNumber() {
    val line = "\tat com.test.additional.package.string.TestClass4.throw(TestClass4.kt)"
    assert(myTestLabFilter.applyFilter(line, line.length) == null)
  }

  @Test
  fun applyFilter_missingFileName() {
    val line = "\tat com.test.additional.package.string.TestClass4.throw(:32)"
    assert(myTestLabFilter.applyFilter(line, line.length) == null)
  }

  private fun assertFilteredResult(
    line: String,
    result: Filter.Result,
    highLightText: String? = null,
  ) {
    result.resultItems.forEach {
      val highlight = line.substring(it.highlightStartOffset, it.highlightEndOffset)
      val descriptor: OpenFileDescriptor =
        (it.hyperlinkInfo as? MultipleFilesHyperlinkInfo)?.descriptor!!
      val link =
        highLightText ?: "${descriptor.file.name.substringAfterLast("/")}:${descriptor.line + 1}"
      assertThat(highlight).isEqualTo(link)
    }
  }
}
