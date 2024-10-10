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
package com.google.gct.testrecorder.debugger;

import static com.android.tools.idea.execution.common.UtilsKt.clearAppStorage;
import static com.google.gct.testrecorder.event.TestRecorderEvent.DELAYED_MESSAGE_POST;
import static com.google.gct.testrecorder.event.TestRecorderEvent.LAZY_CLASSES_LOADER;
import static com.google.gct.testrecorder.event.TestRecorderEvent.LIST_ITEM_CLICK;
import static com.google.gct.testrecorder.event.TestRecorderEvent.PERMISSIONS_REQUEST;
import static com.google.gct.testrecorder.event.TestRecorderEvent.PRESS_BACK;
import static com.google.gct.testrecorder.event.TestRecorderEvent.PRESS_BACK_EMULATOR_28;
import static com.google.gct.testrecorder.event.TestRecorderEvent.PRESS_EDITOR_ACTION;
import static com.google.gct.testrecorder.event.TestRecorderEvent.TEXT_CHANGE;
import static com.google.gct.testrecorder.event.TestRecorderEvent.VIEW_CLICK;
import static com.google.gct.testrecorder.event.TestRecorderEvent.VIEW_LONG_CLICK;
import static com.google.gct.testrecorder.event.TestRecorderEvent.VIEW_SWIPE;
import static com.google.gct.testrecorder.event.TestRecorderEvent.WINDOW_CONTENT_CHANGED;
import static com.google.gct.testrecorder.util.GenerateTestHelperKt.NOTIFICATION_TITLE;

import com.android.SdkConstants;
import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.IDevice;
import com.android.tools.idea.execution.common.stats.RunStats;
import com.android.tools.idea.run.activity.ActivityLocatorUtils;
import com.android.tools.idea.run.activity.DefaultActivityLocator;
import com.google.common.base.Strings;
import com.google.common.collect.Sets;
import com.google.gct.testrecorder.settings.TestRecorderSettings;
import com.google.gct.testrecorder.ui.RecordingDialog;
import com.google.gct.testrecorder.util.GenerateTestHelperKt;
import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.DefaultDebugEnvironment;
import com.intellij.debugger.engine.DebugProcess;
import com.intellij.debugger.engine.DebugProcessImpl;
import com.intellij.debugger.engine.DebugProcessListener;
import com.intellij.debugger.engine.JavaDebugProcess;
import com.intellij.debugger.engine.RemoteDebugProcessHandler;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RemoteConnection;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.NotificationsManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiClass;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.jetbrains.android.dom.manifest.Activity;
import org.jetbrains.android.dom.manifest.ActivityAlias;
import org.jetbrains.android.dom.manifest.Application;
import org.jetbrains.android.dom.manifest.Manifest;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.sdk.AndroidSdkUtils;
import org.jetbrains.annotations.NotNull;

public class TestRecorderDebugProcessListener implements DebugProcessListener {

  private static final Logger LOGGER = Logger.getInstance(TestRecorderDebugProcessListener.class);

  // A replacement press back breakpoint descriptor as a workaround for emulators with API 28+ that cannot reliably handle,
  // i.e., without occasionally freezing, the regular PRESS_BACK breakpoint.
  private static final BreakpointDescriptor PRESS_BACK_EMULATOR_28_BREAKPOINT_DESCRIPTOR =
    new BreakpointDescriptor(PRESS_BACK_EMULATOR_28, "android.app.Activity", "onBackPressed", "()V", false);

  private final Set<BreakpointDescriptor> myBreakpointDescriptors = Sets.newHashSet();
  private final Set<BreakpointCommand> myBreakpointCommands = Sets.newHashSet();

  private final AndroidFacet myFacet;
  private final Project myProject;
  private final ExecutionEnvironment myEnvironment;
  private final boolean myIsRecordingTest;
  private IDevice myDevice;
  private final String myPackageName;
  private volatile DebuggerSession myDebuggerSession;
  private volatile RecordingDialog myRecordingDialog;
  private final String mySpecificActivityClass;


  public TestRecorderDebugProcessListener(AndroidFacet facet, ExecutionEnvironment environment, IDevice device, String packageName,
                                          boolean isRecordingTest, String specificActivityClass, DebuggerSession debugSession) {
    myFacet = facet;
    mySpecificActivityClass = specificActivityClass;
    myProject = environment.getProject();
    myPackageName = packageName;
    myDevice = device;
    myEnvironment = environment;
    myIsRecordingTest = isRecordingTest;
    myDebuggerSession = debugSession;
    // TODO: Although more robust than android.view.View#performClick() breakpoint, this might miss "contrived" clicks,
    // originating from the View object itself (e.g., as a result of processing a touch event).
    myBreakpointDescriptors.add(new BreakpointDescriptor(VIEW_CLICK, "android.view.View$PerformClick", "run", "()V", false));
    myBreakpointDescriptors.add(new BreakpointDescriptor(VIEW_LONG_CLICK, SdkConstants.CLASS_VIEW, "performLongClick", "()Z", false));
    myBreakpointDescriptors.add(new BreakpointDescriptor(LIST_ITEM_CLICK, "android.widget.AbsListView", "performItemClick",
                                                         "(Landroid/view/View;IJ)Z", false));
    myBreakpointDescriptors.add(new BreakpointDescriptor(TEXT_CHANGE, "android.widget.TextView$ChangeWatcher", "beforeTextChanged",
                                                         "(Ljava/lang/CharSequence;III)V", true));
    myBreakpointDescriptors.add(new BreakpointDescriptor(TEXT_CHANGE, "android.widget.TextView$ChangeWatcher", "onTextChanged",
                                                         "(Ljava/lang/CharSequence;III)V", false));

    // TODO: This breakpoint is for a finished input event rather than just press back,
    // so some filtering is required when the breakpoint is hit.
    myBreakpointDescriptors.add(new BreakpointDescriptor(PRESS_BACK, "android.view.inputmethod.InputMethodManager",
                                                         "invokeFinishedInputEventCallback",
                                                         "(Landroid/view/inputmethod/InputMethodManager$PendingEvent;Z)V", false));

    myBreakpointDescriptors.add(new BreakpointDescriptor(PRESS_EDITOR_ACTION, "android.widget.TextView", "onEditorAction", "(I)V", false));
    myBreakpointDescriptors.add(
      new BreakpointDescriptor(VIEW_SWIPE, "android.support.v4.view.ViewPager", "smoothScrollTo", "(III)V", false));
    myBreakpointDescriptors.add(new BreakpointDescriptor(DELAYED_MESSAGE_POST, "android.os.Handler", "postDelayed",
                                                         "(Ljava/lang/Runnable;J)Z", false));
    myBreakpointDescriptors.add(
      new BreakpointDescriptor(WINDOW_CONTENT_CHANGED, "android.view.ViewRootImpl$SendWindowContentChangedAccessibilityEvent",
                               "run", "()V", false));
    myBreakpointDescriptors.add(new BreakpointDescriptor(LAZY_CLASSES_LOADER, "android.os.Handler", "dispatchMessage",
                                                         "(Landroid/os/Message;)V", false));
    myBreakpointDescriptors.add(new BreakpointDescriptor(PERMISSIONS_REQUEST, "android.app.Activity", "requestPermissions",
                                                         "([Ljava/lang/String;I)V", false));
  }

  @Override
  public void processAttached(DebugProcess process) {

    // Mute any user-defined breakpoints to avoid Test Recorder hanging the app when such a breakpoint gets hit.
    // This event arrives before initBreakpoints is called in DebugProcessEvents,
    // but after XDebugSession is supposed to be initialized, so looks like a perfect time to mute breakpoints.
    // Muting breakpoints requires read access.
    ApplicationManager.getApplication().runReadAction(new Runnable() {
      @Override
      public void run() {
        for (XDebugSession debugSession : XDebuggerManager.getInstance(myProject).getDebugSessions()) {
          debugSession.setBreakpointMuted(true);
        }
      }
    });

    scheduleBreakpointCommands(myDevice);

    if (myRecordingDialog == null) {
      // The initial debug process, open Test Recorder dialog.
      // Detect the launched activity name outside the dispatch thread to avoid pausing it until dumb mode is over.
      String launchedActivityName = detectLaunchedActivityName();

      CountDownLatch latch = new CountDownLatch(1);
      // TODO: Open the dialog after all breakpoints are set up (i.e., the scheduled actions are actually executed).
      // Also, consider waiting for the app to be ready first (e.g., such that we can take a screenshot).
      ApplicationManager.getApplication().invokeLater(() -> {
        //Show Test Recorder dialog after adding and enabling breakpoints.
        myRecordingDialog = new RecordingDialog(myFacet, myDevice, myPackageName, launchedActivityName, myIsRecordingTest, latch);
        myRecordingDialog.setDebuggerSession(myDebuggerSession);
        for (BreakpointCommand breakpointCommand : myBreakpointCommands) {
          breakpointCommand.setEventListener(myRecordingDialog);
        }
        myRecordingDialog.show();
      });

      if (myDebuggerSession.isAttached() && myDevice.isOnline()) {
        ProgressManager.getInstance().run(new Task.Backgroundable(myProject, "Espresso test recorder running", true) {
          @Override
          public void run(@NotNull ProgressIndicator indicator) {
            NotificationsManager notificationsManager = NotificationsManager.getNotificationsManager();
            try {
              // Wait for end of recording session or if progress is cancelled.
              while (!latch.await(1, TimeUnit.SECONDS)) {
                indicator.checkCanceled();
              }
              if (myRecordingDialog.isOK()) {
                indicator.setText("Creating test file");
                GenerateTestHelperKt.generateTest(
                  myProject,
                  myRecordingDialog.getTestClassName(),
                  myRecordingDialog.getTestClassParent(),
                  myRecordingDialog.getSelectedLanguage(),
                  myFacet,
                  myRecordingDialog.getRootPanel(),
                  myRecordingDialog.getAllModelActions(),
                  myRecordingDialog.getLaunchedActivityName(),
                  myRecordingDialog.getWasEverPaused(),
                  indicator);
              }
            }
            catch (InterruptedException | ProcessCanceledException e) {
              notificationsManager.showNotification(
                new Notification(
                  this.getClass().toString(),
                  NOTIFICATION_TITLE,
                  "Espresso test recorder interrupted",
                  NotificationType.ERROR
                ), myProject
              );
            }
            catch (Exception e) {
              notificationsManager.showNotification(
                new Notification(
                  this.getClass().toString(),
                  NOTIFICATION_TITLE,
                  "Error recording espresso test",
                  NotificationType.ERROR
                ), myProject
              );
            }
          }

          @Override
          public void onCancel() {
            if (latch.getCount() > 0) {
              myRecordingDialog.doCancelAction();
            }
            stopDebugger();
            super.onCancel();
          }

          @Override
          public void onFinished() {
            stopDebugger();
            super.onFinished();
          }
        });
      }
    }
    else {
      // The restarted debug process, reuse the already shown Test Recorder dialog.
      myRecordingDialog.setDebuggerSession(myDebuggerSession);
      for (BreakpointCommand breakpointCommand : myBreakpointCommands) {
        breakpointCommand.setEventListener(myRecordingDialog);
      }
    }
  }

  @Override
  public void processDetached(DebugProcess process, boolean closedByUser) {
    if (myRecordingDialog != null && myRecordingDialog.isShowing()) {
      // Since the recoding dialog is still up, the process has detached accidentally, so try to restart debugging.
      promptToRestartDebugging();
    }
  }


  /**
   * There are two major uses for the fully qualified launched activity name:
   * 1) As a template value for the base class of the generated instrumentation test and
   * 2) For establishing the package name and suggested class name of the generated test class.
   */
  @NotNull
  private String detectLaunchedActivityName() {
    if (!Strings.isNullOrEmpty(mySpecificActivityClass)) {
      return mySpecificActivityClass;
    }

    return DumbService.getInstance(myProject).runReadActionInSmartMode(new Computable<String>() {
      @Override
      public String compute() {
        String activityName = "unknownPackage.unknownActivity";
        try {
          activityName = new DefaultActivityLocator(myFacet).getQualifiedActivityName(myDevice);
        }
        catch (Exception e) {
          return activityName;
        }

        // If alias, replace with the actual activity.

        if (Manifest.getMainManifest(myFacet) == null || Manifest.getMainManifest(myFacet).getApplication() == null) {
          return activityName;
        }

        Application application = Manifest.getMainManifest(myFacet).getApplication();

        for (Activity activity : application.getActivities()) {
          if (activityName.equals(ActivityLocatorUtils.getQualifiedName(activity))) {
            return activityName; // Not an alias, return as is.
          }
        }

        for (ActivityAlias activityAlias : application.getActivityAliases()) {
          if (activityName.equals(ActivityLocatorUtils.getQualifiedName(activityAlias))) {
            // It is an alias, return the actual activity name.
            PsiClass psiClass = activityAlias.getTargetActivity().getValue();
            if (psiClass != null) {
              String qualifiedName = psiClass.getQualifiedName();
              if (qualifiedName != null) {
                return qualifiedName;
              }
            }
            // Could not establish the actual activity, so return the alias (should not really happen).
            return activityName;
          }
        }

        // A workaround until new DefaultActivityLocator(myFacet).getQualifiedActivityName(myDevice)
        // returns a fully qualified activity name again (b/216843699).
        if (activityName.startsWith(".")) {
          for (Activity activity : application.getActivities()) {
            if (ActivityLocatorUtils.getQualifiedName(activity).endsWith(activityName)) {
              return ActivityLocatorUtils.getQualifiedName(activity);
            }
          }
          // No matching activity found, remove the leading period.
          activityName = activityName.substring(1);
        }

        // Neither actual activity nor alias - should not happen, but return the originally found activity for the sake of completeness.
        return activityName;
      }
    });
  }

  private void promptToRestartDebugging() {
    // Do NOT use ApplicationManager.getApplication().invokeLater(...) here
    // as the prompt dialog will not show up until the main dialog is closed.
    SwingUtilities.invokeLater(new Runnable() {
      @Override
      public void run() {
        String title = "Test Recorder has detached from the device VM";
        if (isDeviceConnected()) {
          // The device is still connected, so the app might have crashed or some other VM issue happened, and thus,
          // it is impossible to reconnect.
          Messages.showMessageDialog(myRecordingDialog.getRootPane(),
                                     "Test Recorder stopped recording your actions because the app stopped.", title, null);
          return;
        }

        String message = "Test Recorder stopped recording your actions because it has detached from the device VM.\n" +
                         "Please fix the connection and click Resume to continue.";
        // Keep trying until a successful reconnection or the user explicitly stops attempting to reconnect.
        while (message != null) {
          myDebuggerSession = null;
          if (myRecordingDialog != null) {
            myRecordingDialog.setDebuggerSession(null);
          }

          boolean shouldResume =
            MessageDialogBuilder.yesNo(title, message).yesText("Resume").noText("Stop").icon(null).ask(myRecordingDialog.getRootPane());

          message = null;
          if (shouldResume) {
            try {
              restartDebugging();
            }
            catch (Exception e) {
              message = "Could not reattach the debugger: " + e.getMessage();
            }
          }
        }
      }
    });
  }

  private void restartDebugging() throws ExecutionException {
    reconnectToDevice();

    String debugPort = Integer.toString(myDevice.getClient(myPackageName).getDebuggerListenPort());
    RemoteConnection connection = new RemoteConnection(true, "localhost", debugPort, false);

    RunProfileState state = new RunProfileState() {
      @Override
      public ExecutionResult execute(Executor executor, @NotNull ProgramRunner runner) throws ExecutionException {
        return new DefaultExecutionResult();
      }
    };

    final RemoteDebugProcessHandler processHandler = new RemoteDebugProcessHandler(myProject);

    DefaultDebugEnvironment debugEnvironment = new DefaultDebugEnvironment(myEnvironment, state, connection, false) {
      @Override
      public ExecutionResult createExecutionResult() throws ExecutionException {
        return new DefaultExecutionResult(null, processHandler);
      }
    };

    DebuggerManagerEx.getInstanceEx(myProject).attachVirtualMachine(debugEnvironment);

    if (myDebuggerSession == null) {
      throw new RuntimeException("Could not attach the virtual machine!");
    }

    XDebuggerManager.getInstance(myProject).startSession(myEnvironment, new XDebugProcessStarter() {
      @Override
      @NotNull
      public XDebugProcess start(@NotNull XDebugSession session) {
        return JavaDebugProcess.create(session, myDebuggerSession);
      }
    });

    // Notify that debugging has started.
    processHandler.startNotify();
  }

  private void scheduleBreakpointCommands(IDevice device) {
    myBreakpointCommands.clear();
    DebugProcessImpl debugProcess = myDebuggerSession.getProcess();
    for (BreakpointDescriptor breakpointDescriptor : myBreakpointDescriptors) {
      if (device.getVersion().getApiLevel() >= 28) {
        if (Objects.equals(breakpointDescriptor.eventType, DELAYED_MESSAGE_POST)) {
          // Skip setting the delayed message breakpoint on Android 28+ as it freezes recording in some scenarios.
          continue;
        }
        if (device.isEmulator() && Objects.equals(breakpointDescriptor.eventType, PRESS_BACK)) {
          // Use a replacement press back breakpoint descriptor for emulators with API 28+.
          breakpointDescriptor = PRESS_BACK_EMULATOR_28_BREAKPOINT_DESCRIPTOR;
        }
      }

      BreakpointCommand breakpointCommand = new BreakpointCommand(debugProcess, breakpointDescriptor);
      myBreakpointCommands.add(breakpointCommand);
      debugProcess.getManagerThread().schedule(breakpointCommand);
    }
  }

  private Void stopTestRecorder() {
    stopDebugger();
    if (myDevice != null && TestRecorderSettings.getInstance().CLEAN_AFTER_FINISH) {
      try {
        // Clear app data such that there is no stale state => the generated test can run (pass) immediately.
        clearAppStorage(myProject, myDevice, myPackageName, RunStats.from(myEnvironment));
      }
      catch (Exception e) {
        LOGGER.warn("Exception stopping the app", e);
      }
    }
    return null;
  }

  private void stopDebugger() {
    if (TestRecorderSettings.getInstance().STOP_APP_AFTER_RECORDING) {
      if (myDebuggerSession != null) {
        XDebugSession xDebugSession = myDebuggerSession.getXDebugSession();
        if (xDebugSession != null) {
          // Stop the debug session on the event dispatch thread (b/254411132).
          ApplicationManager.getApplication().invokeLater(new Runnable() {
            @Override
            public void run() {
              xDebugSession.stop();
            }
          });
        }
      }
    }
    else {
      // Keep the process running, but disable breakpoints such that it is not slowed down.
      for (BreakpointCommand breakpointCommand : myBreakpointCommands) {
        breakpointCommand.disable();
      }
    }
  }

  private void reconnectToDevice() {
    AndroidDebugBridge debugBridge = AndroidSdkUtils.getDebugBridge(myProject);
    if (debugBridge == null) {
      throw new RuntimeException("Could not obtain the debug bridge!");
    }

    for (IDevice device : debugBridge.getDevices()) {
      if (myDevice.getSerialNumber().equals(device.getSerialNumber())) {
        myDevice = device;
        return;
      }
    }

    throw new RuntimeException("Could not find the original device to reconnect to!");
  }

  private boolean isDeviceConnected() {
    AndroidDebugBridge debugBridge = AndroidSdkUtils.getDebugBridge(myProject);
    if (debugBridge != null) {
      for (IDevice device : debugBridge.getDevices()) {
        if (myDevice.getSerialNumber().equals(device.getSerialNumber())) {
          return true;
        }
      }
    }
    return false;
  }
}
