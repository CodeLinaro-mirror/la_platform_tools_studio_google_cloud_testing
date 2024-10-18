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

import com.intellij.mock.MockPsiFile
import com.intellij.mock.MockPsiManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.Processor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

internal data class ClassNameFileTuple(
  val shortClassName: String,
  val fullClassName: String,
  val fileLocation: String,
)

internal class FakePsiShortNamesCache(project: Project, exceptionClasses: List<ClassNameFileTuple>) : PsiShortNamesCache() {
  private val projectClasses: Map<String, Array<PsiClass>> = exceptionClasses.map { klass ->
    val fakePsiClass: PsiClass = mock {
      on { qualifiedName } doReturn klass.fullClassName
      on { containingFile } doReturn FakePsiFile(project, klass.fileLocation)
    }
    (klass.shortClassName to fakePsiClass)
  }.groupBy({ it.first }, {it.second}).mapValues { (_, values) -> values.toTypedArray() }

  override fun getClassesByName(name: String, scope: GlobalSearchScope): Array<PsiClass> = projectClasses[name] ?: emptyArray()

  override fun getAllClassNames(): Array<String> {
    TODO("Not yet implemented")
  }

  override fun getMethodsByName(name: String, scope: GlobalSearchScope): Array<PsiMethod> {
    TODO("Not yet implemented")
  }

  override fun getMethodsByNameIfNotMoreThan(name: String, scope: GlobalSearchScope, maxCount: Int): Array<PsiMethod> {
    TODO("Not yet implemented")
  }

  override fun getFieldsByNameIfNotMoreThan(name: String, scope: GlobalSearchScope, maxCount: Int): Array<PsiField> {
    TODO("Not yet implemented")
  }

  override fun processMethodsWithName(name: String, scope: GlobalSearchScope, processor: Processor<in PsiMethod>): Boolean {
    TODO("Not yet implemented")
  }

  override fun getAllMethodNames(): Array<String> {
    TODO("Not yet implemented")
  }

  override fun getFieldsByName(name: String, scope: GlobalSearchScope): Array<PsiField> {
    TODO("Not yet implemented")
  }

  override fun getAllFieldNames(): Array<String> {
    TODO("Not yet implemented")
  }
}

private class FakePsiFile(project: Project, private val filename: String)
  : MockPsiFile(LightVirtualFile(filename), MockPsiManager(project)) {
  override fun getName(): String = filename

  override fun getContainingFile(): PsiFile = this
}
