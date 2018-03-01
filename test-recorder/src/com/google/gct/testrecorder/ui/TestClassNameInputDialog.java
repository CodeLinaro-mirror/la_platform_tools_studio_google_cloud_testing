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
package com.google.gct.testrecorder.ui;

import com.android.SdkConstants;
import com.android.builder.model.SourceProvider;
import com.android.tools.analytics.UsageTracker;
import com.android.tools.idea.gradle.project.model.AndroidModuleModel;
import com.android.tools.idea.testartifacts.scopes.TestArtifactSearchScopes;
import com.google.common.collect.Lists;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventCategory;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventKind;
import com.intellij.ide.fileTemplates.JavaTemplateUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.GeneratedSourcesFilter;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.model.java.JavaSourceRootType;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.io.File;
import java.io.IOException;
import java.util.List;

import static com.android.builder.model.AndroidProject.ARTIFACT_ANDROID_TEST;
import static com.intellij.openapi.vfs.VfsUtil.findFileByIoFile;

public class TestClassNameInputDialog extends DialogWrapper {
  private static final String JAVA_LANGUAGE_NAME = "Java";
  private static final String KOTLIN_LANGUAGE_NAME = "Kotlin";

  private final Project myProject;
  private final String myLaunchedActivityName;
  private Module myTestClassModule;
  private PsiDirectory myTestClassParent;
  private PsiClass myTestClass;
  private String myClassName;
  private String mySelectedLanguage;

  private JPanel myRootPanel;
  private JTextField myClassNameField;
  private JLabel myErrorMessageLabel;
  private JComboBox<String> myClassLanguageComboBox;


  protected TestClassNameInputDialog(Module launchedModule, String launchedActivityName) {
    super(launchedModule.getProject(), true);
    myProject = launchedModule.getProject();
    myLaunchedActivityName = launchedActivityName;
    myTestClassModule = launchedModule;

    // Initialize dialog
    init();

    setTitle("Specify a test class for your test");

    myClassLanguageComboBox.addItem(JAVA_LANGUAGE_NAME);
    myClassLanguageComboBox.addItem(KOTLIN_LANGUAGE_NAME);

    prepareEnvironment();

    SwingUtilities.invokeLater(new Runnable(){
      @Override
      public void run() {
        updateOKButton();
      }
    });
  }

  private void prepareEnvironment() {
    final VirtualFile testSourceDirectory = detectOrCreateTestSourceDirectoryAndDefaultOutputLanguage();

    if (testSourceDirectory == null) {
      throw new RuntimeException("Could not detect or create the test source directory!");
    }

    String[] activityNameFragments = myLaunchedActivityName.split("\\.");

    VirtualFile testFileParent = getOrCreateSubdirectory(testSourceDirectory, activityNameFragments, false);

    // Generate a unique test class name based on the name of the launched activity.
    String activityTestNameBase = activityNameFragments[activityNameFragments.length - 1] + "Test";
    myTestClassParent = PsiManager.getInstance(myProject).findDirectory(testFileParent);
    myClassName = activityTestNameBase;
    int counter = 2;
    while (doesClassExist()) {
      myClassName = activityTestNameBase + counter++;
    }

    myClassNameField.setText(myClassName);
  }

  private VirtualFile detectOrCreateTestSourceDirectoryAndDefaultOutputLanguage() {
    String launchedActivityPath = myLaunchedActivityName.replace('.', '/');
    VirtualFile launchedActivitySourceRoot = getContainingSourceRoot(appendJavaExtension(launchedActivityPath));
    if (launchedActivitySourceRoot == null) {
      launchedActivitySourceRoot = getContainingSourceRoot(appendKotlinExtension(launchedActivityPath));
      if (launchedActivitySourceRoot != null) {
        // If the launched activity is a Kotlin class, select Kotlin as the default output language for the test class.
        myClassLanguageComboBox.setSelectedIndex(1);
      }
    }

    List<VirtualFile> existingAndroidTestSourceRoots = getExistingAndroidTestSourceRoots();

    if (existingAndroidTestSourceRoots.isEmpty()) {
      UsageTracker.getInstance().log(AndroidStudioEvent.newBuilder()
                                       .setCategory(EventCategory.TEST_RECORDER)
                                       .setKind(EventKind.TEST_RECORDER_MISSING_INSTRUMENTATION_TEST_FOLDER));

      VirtualFile moduleRoot = findFileByIoFile(new File(myTestClassModule.getModuleFilePath()).getParentFile(), true);
      if (moduleRoot == null) {
        throw new RuntimeException("Could not find module root for module " + myTestClassModule.getName());
      }

      List<String> androidTestSourceRoots = getAndroidTestSourceRoots();

      if (androidTestSourceRoots.isEmpty()) {
        // This is not expected to ever happen in a properly set up project, but if it does,
        // create a test source root following naming convention, i.e., $MODULE_DIR$/src/androidTest/java.
        // TODO: If there are examples when naming convention fails, consider updating .iml as well,
        // e.g., using contentEntry.addSourceFolder(VfsUtilCore.pathToUrl(parentSourceRoot.getCanonicalPath() + "/androidTest/java"), true);
        return getOrCreateSubdirectory(moduleRoot, new String[]{"src", "androidTest", "java"}, true);
      } else {
        String closestAndroidTestSourcePath =
          androidTestSourceRoots.get(findClosestAndroidTestSourceRootIndex(launchedActivitySourceRoot, androidTestSourceRoots));
        String moduleRootCanonicalPath = moduleRoot.getCanonicalPath();
        if (moduleRootCanonicalPath == null || !closestAndroidTestSourcePath.startsWith(moduleRootCanonicalPath)) {
          // Why this should ever be the case?
          throw new RuntimeException("Android test source path is not inside the module: " + closestAndroidTestSourcePath);
        }
        return getOrCreateSubdirectory(
          moduleRoot, closestAndroidTestSourcePath.substring(moduleRootCanonicalPath.length() + 1).split("/"), true);
      }
    } else {
      return existingAndroidTestSourceRoots.get(
        findClosestAndroidTestSourceRootIndex(launchedActivitySourceRoot, getCanonicalPaths(existingAndroidTestSourceRoots)));
    }
  }

  private List<String> getAndroidTestSourceRoots() {
    List<String> androidTestSourceRoots = Lists.newArrayList();

    AndroidModuleModel androidModel = AndroidModuleModel.get(myTestClassModule);
    if (androidModel != null) {
      for (SourceProvider sourceProvider : androidModel.getTestSourceProviders(ARTIFACT_ANDROID_TEST)) {
        for (File javaDirectory : sourceProvider.getJavaDirectories()) {
          try {
            androidTestSourceRoots.add(FileUtil.toSystemIndependentName(javaDirectory.getCanonicalPath()));
          } catch (IOException e) {
            // ignore
          }
        }
      }
    }

    return androidTestSourceRoots;
  }

  private static List<String> getCanonicalPaths(List<VirtualFile> virtualFiles) {
    List<String> canonicalPaths = Lists.newArrayList();
    for (VirtualFile virtualFile : virtualFiles) {
      canonicalPaths.add(virtualFile.getCanonicalPath());
    }
    return canonicalPaths;
  }

  private VirtualFile getOrCreateSubdirectory(final VirtualFile parentDirectory, final String[] subdirectoriesPath,
                                              final boolean includeLastPathElement) {
    return ApplicationManager.getApplication().runWriteAction(new Computable<VirtualFile>() {
      @Override
      public VirtualFile compute() {
        VirtualFile currentDirectory = parentDirectory;
        int subdirectoriesPathLength = includeLastPathElement ? subdirectoriesPath.length : subdirectoriesPath.length - 1;
        for (int i = 0; i < subdirectoriesPathLength; i++) {
          String subdirectory = subdirectoriesPath[i];
          VirtualFile child = currentDirectory.findChild(subdirectory);
          if (child == null) {
            try {
              currentDirectory = currentDirectory.createChildDirectory(this, subdirectory);
            } catch (Exception e) {
              throw new RuntimeException("Failed to create subdirectory " + subdirectory, e);
            }
          } else {
            currentDirectory = child;
          }
        }
        return currentDirectory;
      }
    });
  }

  private static int findClosestAndroidTestSourceRootIndex(@Nullable VirtualFile sourceRoot, List<String> androidTestSourceRootPaths) {
    if (sourceRoot == null) {
      return 0;
    }

    String sourceRootCanonicalPath = sourceRoot.getCanonicalPath();

    int closestAndroidTestSourceIndex = 0;
    // androidTestSourceRootPaths should never be empty in this method.
    String closestAndroidTestSourceRootPath = androidTestSourceRootPaths.get(closestAndroidTestSourceIndex);


    int maxOverlapSize = computeOverlapSize(sourceRootCanonicalPath, closestAndroidTestSourceRootPath);
    for (int i = 1; i < androidTestSourceRootPaths.size(); i++) {
      int overlapSize = computeOverlapSize(sourceRootCanonicalPath, androidTestSourceRootPaths.get(i));
      if (overlapSize > maxOverlapSize) {
        maxOverlapSize = overlapSize;
        closestAndroidTestSourceIndex = i;
      }
    }

    return closestAndroidTestSourceIndex;
  }

  private static int computeOverlapSize(String path1, String path2) {
    char[] pathChars1 = path1.toCharArray();
    char[] pathChars2 = path2.toCharArray();
    int overlapSize = 0;
    for (int i = 0; i < Math.min(pathChars1.length, pathChars2.length); i++) {
      if (pathChars1[i] == pathChars2[i]) {
        overlapSize++;
      } else {
        break;
      }
    }
    return overlapSize;
  }

  private static void collectModulesClosure(@NotNull Module module, List<Module> result) {
    if (result.contains(module)) {
      return;
    }

    result.add(module);

    for (Module depModule : ModuleRootManager.getInstance(module).getDependencies()) {
      collectModulesClosure(depModule, result);
    }
  }

  @Nullable
  private VirtualFile getContainingSourceRoot(String fileRelativePath) {
    List<Module> relevantModules = Lists.newLinkedList();
    collectModulesClosure(myTestClassModule, relevantModules);
    for (Module module : relevantModules) {
      for (VirtualFile sourceRoot : ModuleRootManager.getInstance(module).getSourceRoots(JavaSourceRootType.SOURCE)) {
        if (!GeneratedSourcesFilter.isGeneratedSourceByAnyFilter(sourceRoot, myProject)
            && sourceRoot.findFileByRelativePath(fileRelativePath) != null) {
          myTestClassModule = module;
          return sourceRoot;
        }
      }
    }
    return null;
  }

  private List<VirtualFile> getExistingAndroidTestSourceRoots() {
    List<VirtualFile> existingAndroidTestSourceRoots = Lists.newArrayList();
    for (VirtualFile testSourceRoot : ModuleRootManager.getInstance(myTestClassModule).getSourceRoots(JavaSourceRootType.TEST_SOURCE)) {
      if (!GeneratedSourcesFilter.isGeneratedSourceByAnyFilter(testSourceRoot, myProject)) {
        TestArtifactSearchScopes searchScopes = TestArtifactSearchScopes.get(myTestClassModule);
        if (searchScopes != null && searchScopes.isAndroidTestSource(testSourceRoot)) {
          existingAndroidTestSourceRoots.add(testSourceRoot);
        }
      }
    }
    return existingAndroidTestSourceRoots;
  }

  @Nullable
  @Override
  protected JComponent createCenterPanel() {
    myErrorMessageLabel.setText("");
    myErrorMessageLabel.setForeground(JBColor.RED);

    // Set up document listener for class name text field.
    // Update OK button based on the entered class name.
    myClassNameField.getDocument().addDocumentListener(new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent documentEvent) {
        update();
      }

      @Override
      public void removeUpdate(DocumentEvent documentEvent) {
        update();
      }

      @Override
      public void changedUpdate(DocumentEvent documentEvent) {
        update();
      }

      private void update() {
        myClassName = myClassNameField.getText().trim();
        updateOKButton();
      }
    });

    return myRootPanel;
  }

  private void updateOKButton(){
    setOKActionEnabled(PsiNameHelper.getInstance(myProject).isIdentifier(myClassName));
  }

  public PsiClass getTestClass() {
    return myTestClass;
  }

  public boolean isKotlinTestClass() {
    return KOTLIN_LANGUAGE_NAME.equals(mySelectedLanguage);
  }

  public Module getTestClassModule() {
    return myTestClassModule;
  }

  @Override
  protected void doOKAction() {
    mySelectedLanguage = (String)myClassLanguageComboBox.getSelectedItem();

    if (ApplicationManager.getApplication().runReadAction(new Computable<Boolean>() {
      @Override
      public Boolean compute() {
        return doesClassExist();
      }
    })) {
      myErrorMessageLabel.setText("File already exists.");
      return;
    }

    String errorMessage = ApplicationManager.getApplication().runWriteAction(new Computable<String>() {
      @Override
      public String compute() {
        try {
          DumbService service = DumbService.getInstance(myProject);
          service.setAlternativeResolveEnabled(true);
          try {
            myTestClass = JavaDirectoryService.getInstance().createClass(
              myTestClassParent, myClassName, JavaTemplateUtil.INTERNAL_CLASS_TEMPLATE_NAME, false);
            if (isKotlinTestClass()) {
              myTestClass.getContainingFile().setName(appendKotlinExtension(myClassName));
            }
          } finally {
            service.setAlternativeResolveEnabled(false);
          }

          // To avoid a potential concurrent modification warning.
          PsiManager.getInstance(myProject).reloadFromDisk(myTestClass.getContainingFile());
        } catch (Exception e) {
          return e.getMessage();
        }
        return null;
      }
    });

    if (errorMessage != null || myTestClass == null) {
      // Do not use the raw errorMessage as it could be quite lengthy.
      myErrorMessageLabel.setText("File creation failed.");
    } else {
      super.doOKAction();
    }
  }

  private boolean doesClassExist() {
    return myTestClassParent.findFile(appendJavaExtension(myClassName)) != null
           || myTestClassParent.findFile(appendKotlinExtension(myClassName)) != null;
  }

  @NotNull
  private static String appendJavaExtension(String appendToString) {
    return appendToString + SdkConstants.DOT_JAVA;
  }

  @NotNull
  private static String appendKotlinExtension(String appendToString) {
    return appendToString + SdkConstants.DOT_KT;
  }

  @Override
  public void doCancelAction() {
    myClassName = null;
    myTestClass = null;
    super.doCancelAction();
  }

  @Override
  public void dispose() {
    super.dispose();
  }

  @Override
  protected String getDimensionServiceKey() {
    return "#com.google.gct.testrecorder.ui.TestClassNameInputDialog";
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return myClassNameField;
  }

}
