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

import com.intellij.execution.filters.ExceptionFilterFactory
import com.intellij.execution.filters.Filter
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope

// Registers and creates the TestLabExceptionFilter to parse result from Firebase test lab devices
class TestLabExceptionFilterFactory : ExceptionFilterFactory {

  override fun create(searchScope: GlobalSearchScope): Filter {
    return TestLabExceptionFilter(searchScope.project!!, searchScope)
  }

  override fun create(project: Project, searchScope: GlobalSearchScope): Filter {
    return TestLabExceptionFilter(project, searchScope)
  }
}
