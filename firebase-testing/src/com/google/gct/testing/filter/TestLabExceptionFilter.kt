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

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfoFactory
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

private const val FULL_CLASS_NAME = "fullClassName"
private const val PACKAGE_NAME = "packageName"
private const val CLASS_NAME = "className"
private const val LOCATION = "location"
private const val LINE_NUMBER = "lineNumber"
private const val SOURCES = "sources"
private const val FILE_NAME = "fileName"

private const val PACKAGE_CLASS_REGEX =
  "\\tat (?<$FULL_CLASS_NAME>(?<$PACKAGE_NAME>.+)\\.(?<$CLASS_NAME>.+?))\\.(?<method>.+?)"

private val fileAndLineRegex =
  Regex("$PACKAGE_CLASS_REGEX\\((?<$LOCATION>(?<$FILE_NAME>.+):(?<$LINE_NUMBER>\\d+)\\))")

private val otherMethodRegex =
  Regex("$PACKAGE_CLASS_REGEX\\((?<$SOURCES>(Native Method|Unknown Source)\\))")

/**
 * Parses exceptions from log returned by Firebase test lab and generates hyperlinks to the
 * corresponding file. This filter is used in all console windows.
 *
 * In log emitted by FTL devices, we see that exception message is formatted as below: 08-07
 * 16:44:16.268: E/TestRunner(9723): at
 * com.example.ftlgmdtestproject.ExampleInstrumentedTest2$TestOnly.fail(ExampleInstrumentedTest2.kt:34)
 *
 * A regular Gradle managed device or a connected device would have the following output: 08-07
 * 10:51:07.672 2798 2997 E TestRunner: at
 * android.app.Instrumentation$InstrumentationThread.run(Instrumentation.java:2248)
 *
 * The parenthesis in string "E/TestRunner(9723)" causes current exception filter to fail since the
 * parser only recognizes the first "(" and ignores content in "(Instrumentation.java:2248)".
 */
class TestLabExceptionFilter(
  private val myProject: Project,
  private val mySearchScope: GlobalSearchScope,
) : Filter, DumbAware {

  private val hyperlinkInfoFactory = HyperlinkInfoFactory.getInstance()
  private val fileNamesCache = PsiShortNamesCache.getInstance(myProject)

  override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
    val offset = entireLength - line.length
    try {
      var items: List<Filter.ResultItem> =
        fileAndLineRegex
          .findAll(line)
          .mapNotNull {
            val range = it.groups[LOCATION]?.range ?: return@mapNotNull null
            val lineNumber =
              (it.groups[LINE_NUMBER]?.value?.toIntOrNull() ?: return@mapNotNull null) - 1
            createHyperLinkFromMatch(it, offset, range, lineNumber, true)
          }
          .toList()

      // Handle other sources like "Native Method" and "Unknown Source" if items inside parenthesis
      // is not formatted as fileName:lineNumber
      if (items.isEmpty()) {
        items =
          otherMethodRegex
            .findAll(line)
            .mapNotNull {
              val range = it.groups[SOURCES]?.range ?: return@mapNotNull null
              createHyperLinkFromMatch(it, offset, range, 0, false)
            }
            .toList()
      }
      return when {
        items.isEmpty() -> null
        else -> Filter.Result(items)
      }
    } catch (e: Exception) {
      thisLogger().debug("Line $line caused exception in TestLabExceptionFilter", e)
      return null
    }
  }

  private fun createHyperLinkFromMatch(
    match: MatchResult,
    offset: Int,
    range: IntRange,
    lineNumber: Int,
    matchFileName: Boolean,
  ): Filter.ResultItem? {
    val matchSourceFile = findFilesForMatch(match, matchFileName)
    if (matchSourceFile.isEmpty()) return null
    return Filter.ResultItem(
      offset + range.first,
      offset + range.last,
      hyperlinkInfoFactory.createMultipleFilesHyperlinkInfo(matchSourceFile, lineNumber, myProject),
    )
  }

  // Nested classes are formatted as OutClass$NestedClass1$NestedClass2. We format the class name to
  // OutClass.NestedClass1.NestedClass2
  // to match return result from PsiClass.qualifiedName to filter the classes we found in
  // fileNamesCache. After filtering the
  // package name of the class should match the package name in the log.
  private fun findFilesForMatch(match: MatchResult, matchFileName: Boolean): List<VirtualFile> {
    val expandedClassName =
      match.groups[CLASS_NAME]?.value?.split("$")?.filter { it.toIntOrNull() == null }
        ?: return emptyList()
    val fullQualifiedName =
      match.groups[FULL_CLASS_NAME]
        ?.value
        ?.split("$")
        ?.filter { it.toIntOrNull() == null }
        ?.joinToString(".") ?: return emptyList()

    if (expandedClassName.isEmpty() || fullQualifiedName.isEmpty()) {
      return emptyList()
    }

    // We don't need to dig into the innermost nested class to obtain the source file. Sometimes
    // nested class
    // does not appear as inner class in its parent class in PSI class representation.
    return fileNamesCache
      .getClassesByName(expandedClassName.first(), mySearchScope)
      .filter { matchingClass ->
        val qualifiedName = matchingClass.qualifiedName ?: return@filter false
        fullQualifiedName.startsWith(qualifiedName) &&
          (!matchFileName ||
            run {
              var containingFileName = matchingClass.containingFile?.name ?: return@filter false
              containingFileName = containingFileName.split(".")[0]
              match.groups[FILE_NAME]?.value?.startsWith(containingFileName) ?: false
            })
      }
      .mapNotNull { it.containingFile?.virtualFile }
  }
}
