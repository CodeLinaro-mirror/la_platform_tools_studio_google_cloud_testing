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

import com.android.tools.lint.helpers.DefaultJavaEvaluator;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;

public class ClassHelper {

  /**
   * Returns the name of the class that can be used in the generated test code.
   * For example, for a class foo.bar.Foo.Bar it returns foo.bar.Foo$Bar.
   */
  public static String getInternalName(Project project, String className) {
    PsiClass psiClass = getPsiClass(project, className);
    if (psiClass != null) {
      DefaultJavaEvaluator evaluator = new DefaultJavaEvaluator(project, null);
      String internalName = evaluator.getInternalName(psiClass);
      if (internalName != null) {
        return internalName.replace('/', '.');
      }
    }

    // If the PsiClass was not found or its internal name was not obtained, apply a simple heuristic.
    String[] nameFragments = className.split("\\.");
    StringBuilder resultClassName = new StringBuilder();
    for (int i = 0; i < nameFragments.length - 1; i++) {
      String fragment = nameFragments[i];
      resultClassName.append(fragment).append(Character.isUpperCase(fragment.charAt(0)) ? "$" : ".");
    }
    resultClassName.append(nameFragments[nameFragments.length -1]);

    return resultClassName.toString();
  }

  private static PsiClass getPsiClass(Project project, String className) {
    try {
      return JavaPsiFacade.getInstance(project).findClass(className, GlobalSearchScope.allScope(project));
    } catch (IndexNotReadyException e) {
      return null;
    }
  }

}
