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

import static com.google.gct.testrecorder.util.StringHelper.boxString;
import static com.google.gct.testrecorder.util.StringHelper.getClassName;
import static com.google.gct.testrecorder.util.StringHelper.getPackageName;
import static com.google.gct.testrecorder.util.StringHelper.lowerCaseFirstCharacter;

import com.android.annotations.VisibleForTesting;
import com.android.tools.analytics.UsageTracker;
import com.android.tools.idea.gradle.project.build.GradleBuildState;
import com.android.tools.idea.gradle.project.sync.GradleSyncState;
import com.android.tools.idea.stats.UsageTrackerUtils;
import com.google.common.collect.Collections2;
import com.google.gct.testrecorder.event.ElementAction;
import com.google.gct.testrecorder.event.TestRecorderAssertion;
import com.google.gct.testrecorder.event.TestRecorderEvent;
import com.google.gct.testrecorder.ui.RecordingDialog;
import com.google.gct.testrecorder.util.ResourceHelper;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventCategory;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventKind;
import com.google.wireless.android.sdk.stats.TestRecorderDetails;
import com.intellij.codeInsight.actions.OptimizeImportsProcessor;
import com.intellij.codeInsight.actions.ReformatCodeProcessor;
import com.intellij.concurrency.JobScheduler;
import com.intellij.ide.SelectInContext;
import com.intellij.ide.SelectInTarget;
import com.intellij.ide.actions.OpenFileAction;
import com.intellij.ide.actions.SelectInContextImpl;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.ide.projectView.impl.ProjectViewPane;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.impl.CoreProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiManager;
import com.intellij.util.ThreeState;
import com.intellij.util.containers.ConcurrentLongObjectMap;
import java.io.File;
import java.io.PrintWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.jetbrains.android.sdk.AndroidPlatform;
import org.jetbrains.android.sdk.AndroidTargetData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * This class generates instrumentation test and saves it to target location given the
 * test class name, the destination directory, and the list of recorded actions.
 */
public class TestCodeGenerator {
  private static final String JAVA_TEST_CODE_TEMPLATE_FILE_NAME = "JavaTestCodeTemplate.vm";
  private static final String KOTLIN_TEST_CODE_TEMPLATE_FILE_NAME = "KotlinTestCodeTemplate.vm";
  private static final int BACKGROUND_TASKS_WAIT_LIMIT = 60; // 60 seconds

  private final String myResourcePackageName;
  private final String myApplicationId;
  private final PsiClass myTestClass;
  private final Module myTestClassModule;
  private final List<ElementAction> myActions;
  private final Project myProject;
  private final String myLaunchedActivityName;
  private final boolean myWasEverPaused;
  private final boolean myIsKotlinTestClass;
  private final boolean myUsesAndroidxDependency;


  public TestCodeGenerator(String resourcePackageName, String applicationId, Module testClassModule, PsiClass testClass,
                           List<ElementAction> actions, String launchedActivityName, boolean wasEverPaused, boolean isKotlinTestClass,
                           boolean usesAndroidxDependency) {
    myResourcePackageName = resourcePackageName;
    myApplicationId = applicationId;
    myTestClass = testClass;
    myTestClassModule = testClassModule;
    myActions = actions;
    myProject = myTestClassModule.getProject();
    myLaunchedActivityName = launchedActivityName;
    myWasEverPaused = wasEverPaused;
    myIsKotlinTestClass = isKotlinTestClass;
    myUsesAndroidxDependency = usesAndroidxDependency;
  }

  public void generate() {
    final String testFilePath = myTestClass.getContainingFile().getVirtualFile().getPath();
    final VirtualFile testVirtualFile = LocalFileSystem.getInstance().findFileByPath(testFilePath);

    if (testVirtualFile == null) {
      // TODO: notify user we failed to get virtual file
      return;
    }

    ApplicationManager.getApplication().runWriteAction(() -> writeCode(testVirtualFile));

    // Commit the document such that the automatically triggered code formatter does not fail.
    PsiDocumentManager psiDocumentManager = PsiDocumentManager.getInstance(myProject);
    Document document = FileDocumentManager.getInstance().getDocument(testVirtualFile);
    if (document != null) {
      psiDocumentManager.commitDocument(document);
    } else {
      psiDocumentManager.commitAllDocuments();
    }

    // TODO: Figure out why we need to do refresh two times here.
    testVirtualFile.refresh(false, true);
    OpenFileAction.openFile(testFilePath, myProject);

    // Do not refresh asynchronously as it might lead to different IDE fatal errors.
    testVirtualFile.refresh(false, true, new Runnable() {
      @Override
      public void run() {
        // Select the generated test class in the project view hierarchy tree.
        ProjectView projectView = ProjectView.getInstance(myProject);
        String currentViewId = projectView.getCurrentViewId() == null ? ProjectViewPane.ID : projectView.getCurrentViewId();
        for (SelectInTarget target : projectView.getSelectInTargets()) {
          if (currentViewId.equals(target.getMinorViewId())) {
            AnActionEvent event = AnActionEvent.createFromDataContext(ActionPlaces.UNKNOWN, null, new DataContext() {
              @Nullable
              @Override
              public Object getData(String dataId) {
                if (CommonDataKeys.PROJECT.getName().equals(dataId)) {
                  return myProject;
                }
                else if (PlatformDataKeys.FILE_EDITOR.getName().equals(dataId)) {
                  return FileEditorManagerEx.getInstanceEx(myProject).getSelectedEditor(testVirtualFile);
                }
                else if (CommonDataKeys.VIRTUAL_FILE.getName().equals(dataId)) {
                  return testVirtualFile;
                }
                return null;
              }
            });
            SelectInContext context = SelectInContextImpl.createContext(event);
            target.selectIn(context, false);
            break;
          }
        }

        JobScheduler.getScheduler().schedule(() -> {
          waitForBackgroundTasksToFinish();

          ApplicationManager.getApplication().invokeAndWait(() ->
            new OptimizeImportsProcessor(myProject, PsiManager.getInstance(myProject).findFile(testVirtualFile)).run());

          waitForBackgroundTasksToFinish();

          ApplicationManager.getApplication().invokeLater(() ->
            new ReformatCodeProcessor(myProject, PsiManager.getInstance(myProject).findFile(testVirtualFile), null, false).run());
        }, 10, TimeUnit.MILLISECONDS);
      }
    });
  }

  private void waitForBackgroundTasksToFinish() {
    int secondsWaited = 0;
    GradleSyncState syncState = GradleSyncState.getInstance(myProject);
    GradleBuildState buildState = GradleBuildState.getInstance(myProject);
    while (secondsWaited < BACKGROUND_TASKS_WAIT_LIMIT) {
      if (!syncState.isSyncInProgress() && syncState.isSyncNeeded() != ThreeState.YES && !buildState.isBuildInProgress()) {
        break;
      }
      try {
        Thread.sleep(1000);
        secondsWaited++;
      } catch (InterruptedException ignored) {
      }
    }

    // Wait for other background tasks to complete, e.g., refreshing, indexing, etc.
    try {
      Field field = CoreProgressManager.class.getDeclaredField("currentIndicators");
      field.setAccessible(true);
      while (secondsWaited < BACKGROUND_TASKS_WAIT_LIMIT) {
        if (((ConcurrentLongObjectMap)field.get(null)).isEmpty()) {
          break;
        }
        try {
          Thread.sleep(1000);
          secondsWaited++;
        } catch (InterruptedException ignored) {
        }
      }
    } catch (NoSuchFieldException | IllegalAccessException e) {
      // Give it some time just in case there are any tasks running in the background.
      try {
        Thread.sleep(3000);
      }catch (InterruptedException ignored) {
      }
    }
  }

  @VisibleForTesting
  protected void writeCode(VirtualFile testVirtualFile) {
    // Write code to the test class file.
    Writer writer = null;
    try {
      writer = new PrintWriter(testVirtualFile.getOutputStream(this));

      VelocityEngine velocityEngine = new VelocityEngine();
      // Suppress creation of velocity.log file.
      velocityEngine.setProperty(RuntimeConstants.RUNTIME_LOG_LOGSYSTEM_CLASS, "org.apache.velocity.runtime.log.NullLogChute");
      velocityEngine.init();
      velocityEngine.evaluate(createVelocityContext(testVirtualFile), writer, RecordingDialog.class.getName(), readTemplateFileContent());
      writer.flush();
    } catch (Exception e) {
      throw new RuntimeException("Failed to generate test class file: ", e);
    } finally {
      if (writer != null) {
        try {
          writer.close();
        }
        catch (Exception e) {
          // ignore
        }
      }
    }
  }

  private String readTemplateFileContent() {
    File testTemplateFile = ResourceHelper.getFileForResource(
      this, myIsKotlinTestClass ? KOTLIN_TEST_CODE_TEMPLATE_FILE_NAME : JAVA_TEST_CODE_TEMPLATE_FILE_NAME, "test_code_template_", "vm");
    try {
      return FileUtils.readFileToString(testTemplateFile);
    } catch (Exception e) {
      throw new RuntimeException("Failed to read the test template file " + testTemplateFile.getAbsolutePath(), e);
    }
  }

  @NotNull
  private VelocityContext createVelocityContext(VirtualFile testCodeVirtualFile) {
    VelocityContext velocityContext = new VelocityContext();
    velocityContext.put("TestActivityName", getClassName(myLaunchedActivityName));
    velocityContext.put("ClassName", myTestClass.getName());
    velocityContext.put("TestMethodName", lowerCaseFirstCharacter(myTestClass.getName()));
    velocityContext.put("PackageName", getPackageName(myTestClass.getQualifiedName()));
    velocityContext.put("WasEverPaused", myWasEverPaused);
    velocityContext.put("ResourcePackageName", myResourcePackageName);
    velocityContext.put("UsesAndroidxDependency", myUsesAndroidxDependency);
    velocityContext.put("EspressoPackageNamePrefix", myUsesAndroidxDependency ? "androidx" : "android.support");
    velocityContext.put("RecyclerViewPackageNamePrefix", myUsesAndroidxDependency ? "androidx.recyclerview.widget" : "android.support.v7.widget");

    // Generate test code.
    TestCodeMapper codeMapper = new TestCodeMapper(myApplicationId, myTestClassModule, getAndroidTargetData(), myIsKotlinTestClass);
    ArrayList<String> testCodeLines = new ArrayList<String>();
    int eventCount = 0;
    int assertionCount = 0;

    // Remove the last sleep since it would unnecessary prolong the test execution.
    if (!myActions.isEmpty()) {
      Object lastAction = myActions.get(myActions.size() - 1);
      if (lastAction instanceof TestRecorderEvent && ((TestRecorderEvent)lastAction).isDelayedMessagePost()) {
        myActions.remove(myActions.size() - 1);
      }
    }

    for (ElementAction action : myActions) {
      List<String> actionCodeLines;
      if (action instanceof TestRecorderEvent) {
        actionCodeLines = codeMapper.getTestCodeLinesForEvent((TestRecorderEvent)action);
        eventCount++;
      } else {
        actionCodeLines = codeMapper.getTestCodeLinesForAssertion((TestRecorderAssertion)action);
        assertionCount++;
      }
      if (!actionCodeLines.isEmpty()) {
        testCodeLines.addAll(actionCodeLines);
        testCodeLines.add("");
      }
    }
    if (!testCodeLines.isEmpty()) {
      // Remove the trailing empty line.
      testCodeLines.remove(testCodeLines.size() - 1);
    }

    Set<String> requestedPermissions = codeMapper.getRequestedPermissions();
    if (!requestedPermissions.isEmpty()) {
      velocityContext.put("HasRequestedPermissions", true);
      velocityContext.put("RequestedPermissions",
                          StringUtils.join(Collections2.transform(requestedPermissions, permission -> boxString(permission)), ",\n"));
    }

    velocityContext.put("AddContribImport", codeMapper.isRecyclerViewActionAdded());
    velocityContext.put("AddChildAtPositionMethod", codeMapper.isChildAtPositionAdded());
    velocityContext.put("TestCode", testCodeLines);

    UsageTracker.log(UsageTrackerUtils.withProjectId(
                     AndroidStudioEvent.newBuilder()
                                   .setCategory(EventCategory.TEST_RECORDER)
                                   .setKind(EventKind.TEST_RECORDER_GENERATE_TEST_CLASS)
                                   .setTestRecorderDetails(TestRecorderDetails.newBuilder()
                                                           .setAssertionCount(assertionCount)
                                                           .setEventCount(eventCount)), myProject));
    return velocityContext;
  }

  @Nullable
  private AndroidTargetData getAndroidTargetData() {
    AndroidPlatform androidPlatform = AndroidPlatform.getInstance(myTestClassModule);
    if (androidPlatform == null) {
      return null;
    }

    return androidPlatform.getSdkData().getTargetData(androidPlatform.getTarget());
  }

}
