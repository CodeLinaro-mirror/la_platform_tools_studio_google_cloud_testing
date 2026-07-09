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

import static com.google.gct.testrecorder.util.GenerateTestHelperKt.KOTLIN_LANGUAGE_NAME;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;

import com.android.SdkConstants;
import com.android.annotations.VisibleForTesting;
import com.android.tools.analytics.UsageTracker;
import com.android.tools.analytics.UsageTrackerUtils;
import com.android.tools.idea.projectsystem.AndroidProjectSystem;
import com.android.tools.idea.projectsystem.CommonTestType;
import com.android.tools.idea.projectsystem.IdeaSourceProvider;
import com.android.tools.idea.projectsystem.ProjectSystemUtil;
import com.android.tools.idea.projectsystem.SourceProviders;
import com.android.tools.idea.projectsystem.TestArtifactSearchScopes;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Streams;
import com.google.gct.testrecorder.util.EspressoSetupToken;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventCategory;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventKind;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.GeneratedSourcesFilter;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNameHelper;
import com.intellij.ui.JBColor;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.FontUIResource;
import javax.swing.text.StyleContext;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.model.java.JavaSourceRootType;

public class TestClassNameInputDialog extends DialogWrapper {
  private static final String JAVA_LANGUAGE_NAME = "Java";

  private final Project myProject;
  private final String myLaunchedActivityName;
  private final Module myTestClassModule;
  private final VirtualFile myTestSourceDirectory;
  private PsiDirectory myTestClassParent;
  private PsiClass myTestClass;
  private String myClassName;
  private String mySelectedLanguage;

  protected JPanel myRootPanel;
  protected JTextField myClassNameField;
  protected JLabel myErrorMessageLabel;
  protected JComboBox<String> myClassLanguageComboBox;

  public static class EnvironmentResult {
    public final VirtualFile testSourceDirectory;
    public final VirtualFile directoryToCreateParent;
    public final String[] subdirectoriesToCreate;
    public final int defaultLanguageIndex;

    public EnvironmentResult(@Nullable VirtualFile testSourceDirectory,
                             @Nullable VirtualFile directoryToCreateParent,
                             @Nullable String[] subdirectoriesToCreate,
                             int defaultLanguageIndex) {
      this.testSourceDirectory = testSourceDirectory;
      this.directoryToCreateParent = directoryToCreateParent;
      this.subdirectoriesToCreate = subdirectoriesToCreate;
      this.defaultLanguageIndex = defaultLanguageIndex;
    }
  }

  @Nullable
  public static EnvironmentResult performEnvironmentDetection(@NotNull Module module, @NotNull String launchedActivityName) {
    AtomicReference<EnvironmentResult> result = new AtomicReference<>();
    ApplicationManager.getApplication().runReadAction(() -> {
      String launchedActivityPath = launchedActivityName.replace('.', '/');
      int defaultLanguageIndex = 0; // Java

      AndroidFacet facet = AndroidFacet.getInstance(module);
      if (facet == null) return;

      IdeaSourceProvider mainIdeaSourceProvider = SourceProviders.getInstance(facet).getMainIdeaSourceProvider();
      VirtualFile launchedActivitySourceRoot = null;
      for (VirtualFile sourceRoot : Iterables.concat(mainIdeaSourceProvider.getJavaDirectories(),
                                                     mainIdeaSourceProvider.getKotlinDirectories())) {
        if (!isGenerated(sourceRoot, module.getProject())
            && sourceRoot.findFileByRelativePath(appendJavaExtension(launchedActivityPath)) != null) {
          launchedActivitySourceRoot = sourceRoot;
          break;
        }
        if (!isGenerated(sourceRoot, module.getProject())
            && sourceRoot.findFileByRelativePath(appendKotlinExtension(launchedActivityPath)) != null) {
          launchedActivitySourceRoot = sourceRoot;
          defaultLanguageIndex = 1; // Kotlin
          break;
        }
      }

      if (launchedActivitySourceRoot == null) return;

      VirtualFile testSourceDirectory = null;
      VirtualFile directoryToCreateParent = null;
      String[] subdirectoriesToCreate = null;

      List<VirtualFile> existingAndroidTestSourceRoots = getExistingAndroidTestSourceRoots(module);

      if (existingAndroidTestSourceRoots.isEmpty()) {
        UsageTracker.log(UsageTrackerUtils.withProjectId(
          AndroidStudioEvent.newBuilder()
            .setCategory(EventCategory.TEST_RECORDER)
            .setKind(EventKind.TEST_RECORDER_MISSING_INSTRUMENTATION_TEST_FOLDER),
          module.getProject()));

        VirtualFile closestContentRoot = getClosestContentRoot(module, launchedActivitySourceRoot);
        List<String> androidTestSourceRoots = getAndroidTestSourceRoots(module);

        if (androidTestSourceRoots.isEmpty()) {
          VirtualFile contentRootParent = closestContentRoot.getParent();
          if (contentRootParent != null && contentRootParent.getName().equals("src")) {
            directoryToCreateParent = contentRootParent;
            subdirectoriesToCreate = new String[]{"androidTest", "java"};
          } else {
            directoryToCreateParent = closestContentRoot;
            subdirectoriesToCreate = new String[]{"src", "androidTest", "java"};
          }
        } else {
          String closestAndroidTestSourcePath =
            androidTestSourceRoots.get(findClosestAndroidTestSourceRootIndex(launchedActivitySourceRoot, androidTestSourceRoots));
          closestAndroidTestSourcePath =
            getFilePathPrefix(launchedActivitySourceRoot.getCanonicalPath(), closestAndroidTestSourcePath) + closestAndroidTestSourcePath;
          VirtualFile parentDirectory = closestContentRoot;
          if (closestContentRoot.getCanonicalPath() == null ||
              !closestAndroidTestSourcePath.startsWith(closestContentRoot.getCanonicalPath())) {
            parentDirectory = findContainingDirectory(launchedActivitySourceRoot, closestAndroidTestSourcePath);
          }
          if (parentDirectory != null && parentDirectory.getCanonicalPath() != null) {
            directoryToCreateParent = parentDirectory;
            subdirectoriesToCreate = closestAndroidTestSourcePath.substring(parentDirectory.getCanonicalPath().length() + 1).split("/");
          }
        }
      } else {
        testSourceDirectory = existingAndroidTestSourceRoots.get(
          findClosestAndroidTestSourceRootIndex(launchedActivitySourceRoot, getCanonicalPaths(existingAndroidTestSourceRoots)));
      }

      if (testSourceDirectory != null || directoryToCreateParent != null) {
        result.set(new EnvironmentResult(testSourceDirectory, directoryToCreateParent, subdirectoriesToCreate, defaultLanguageIndex));
      }
    });
    return result.get();
  }

  protected TestClassNameInputDialog(Module launchedModule, String launchedActivityName, @NotNull VirtualFile testSourceDirectory, int defaultLanguageIndex) {
    super(launchedModule.getProject(), true);
    myProject = launchedModule.getProject();
    myLaunchedActivityName = launchedActivityName;
    myTestClassModule = launchedModule;
    myTestSourceDirectory = testSourceDirectory;

    setupUI();
    init();

    setTitle("Specify a test class for your test");

    myClassLanguageComboBox.addItem(JAVA_LANGUAGE_NAME);
    myClassLanguageComboBox.addItem(KOTLIN_LANGUAGE_NAME);
    myClassLanguageComboBox.setSelectedIndex(defaultLanguageIndex);

    prepareEnvironment();

    if (myClassLanguageComboBox.getSelectedIndex() < 1 && !hasKotlinPlugin()) {
      myClassLanguageComboBox.removeItemAt(1);
    }

    SwingUtilities.invokeLater(this::updateOKButton);
  }

  public String getTestClassName() {
    return myClassName;
  }

  public PsiDirectory getTestClassParent() {
    return myTestClassParent;
  }

  public String getSelectedLanguage() {
    return mySelectedLanguage;
  }

  private boolean hasKotlinPlugin() {
    AndroidProjectSystem projectSystem = ProjectSystemUtil.getProjectSystem(myProject);
    EspressoSetupToken token = EspressoSetupToken.EP_NAME.getExtensionList().stream()
      .filter((it) -> it.isApplicable(projectSystem))
      .findFirst().orElse(null);
    if (token != null) {
      return token.supportsKotlin(projectSystem, myTestClassModule);
    }
    return false;
  }

  private void prepareEnvironment() {
    String[] activityNameFragments = myLaunchedActivityName.split("\\.");
    VirtualFile testFileParent = getOrCreateSubdirectoryOnEDT(myTestSourceDirectory, activityNameFragments, false);

    String activityTestNameBase = activityNameFragments[activityNameFragments.length - 1] + "Test";
    myTestClassParent = PsiManager.getInstance(myProject).findDirectory(testFileParent);
    myClassName = activityTestNameBase;
    int counter = 2;
    while (doesClassExist()) {
      myClassName = activityTestNameBase + counter++;
    }

    myClassNameField.setText(myClassName);
    myClassNameField.setBorder(BorderFactory.createCompoundBorder(
      new JTextField().getBorder(),
      BorderFactory.createEmptyBorder()));
  }

  private static VirtualFile getClosestContentRoot(Module module, @Nullable VirtualFile launchedActivitySourceRoot) {
    @NotNull VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
    if (contentRoots.length == 0) {
      throw new RuntimeException("Could not find any content roots");
    }

    if (contentRoots.length == 1 || launchedActivitySourceRoot == null
        || launchedActivitySourceRoot.getCanonicalPath() == null) {
      return contentRoots[0];
    }

    for (VirtualFile contentRoot : contentRoots) {
      if (contentRoot.getCanonicalPath() != null
          && launchedActivitySourceRoot.getCanonicalPath().startsWith(contentRoot.getCanonicalPath())) {
        return contentRoot;
      }
    }

    return contentRoots[0];
  }

  private static VirtualFile findContainingDirectory(VirtualFile currentDirectory, String path) {
    if (currentDirectory == null || path.startsWith(currentDirectory.getCanonicalPath())) {
      return currentDirectory;
    }
    return findContainingDirectory(currentDirectory.getParent(), path);
  }

  private static List<String> getAndroidTestSourceRoots(Module module) {
    AndroidFacet facet = AndroidFacet.getInstance(module);
    if (facet == null) return emptyList();
    SourceProviders sourceProviders = SourceProviders.getInstance(facet);
    IdeaSourceProvider androidSourceProvider = sourceProviders.getDeviceTestSources().get(CommonTestType.ANDROID_TEST);
    if (androidSourceProvider == null) return emptyList();
    List<String> androidTestSourceRoots = Streams.stream(Iterables.concat(
      androidSourceProvider.getJavaDirectories(),
      androidSourceProvider.getKotlinDirectories()
    )).map(VirtualFile::getCanonicalPath).collect(toList());
    if (!androidTestSourceRoots.isEmpty()) {
      return androidTestSourceRoots;
    }
    // If no actual Android test source roots were found, look for potential ones as URLs.
    return Streams.stream(Iterables.concat(
      androidSourceProvider.getJavaDirectoryUrls(),
      androidSourceProvider.getKotlinDirectoryUrls()
    )).map(TestClassNameInputDialog::getURLPath).collect(toList());
  }

  private void setupUI() {
    myRootPanel = new JPanel();
    myRootPanel.setLayout(new BorderLayout(0, 0));
    myRootPanel.setInheritsPopupMenu(false);
    myRootPanel.setMinimumSize(new Dimension(450, 85));
    myRootPanel.setOpaque(true);
    myRootPanel.setPreferredSize(new Dimension(450, 85));
    myErrorMessageLabel = new JLabel();
    Font myErrorMessageLabelFont = getFont(null, Font.BOLD, -1, myErrorMessageLabel.getFont());
    if (myErrorMessageLabelFont != null) myErrorMessageLabel.setFont(myErrorMessageLabelFont);
    myErrorMessageLabel.setHorizontalAlignment(4);
    myErrorMessageLabel.setHorizontalTextPosition(4);
    myErrorMessageLabel.setText("ERROR");
    myRootPanel.add(myErrorMessageLabel, BorderLayout.SOUTH);
    final JPanel panel1 = new JPanel();
    panel1.setLayout(new BorderLayout(0, 0));
    panel1.setMinimumSize(new Dimension(450, 66));
    panel1.setOpaque(true);
    panel1.setPreferredSize(new Dimension(450, 66));
    myRootPanel.add(panel1, BorderLayout.NORTH);
    final JPanel panel2 = new JPanel();
    panel2.setLayout(new BorderLayout(0, 0));
    panel2.setMinimumSize(new Dimension(450, 27));
    panel2.setPreferredSize(new Dimension(450, 27));
    panel1.add(panel2, BorderLayout.NORTH);
    final JLabel label1 = new JLabel();
    label1.setOpaque(true);
    label1.setText("Test class name:");
    panel2.add(label1, BorderLayout.WEST);
    myClassNameField = new JTextField();
    myClassNameField.setMinimumSize(new Dimension(320, 27));
    myClassNameField.setOpaque(true);
    myClassNameField.setPreferredSize(new Dimension(320, 27));
    panel2.add(myClassNameField, BorderLayout.EAST);
    final JPanel panel3 = new JPanel();
    panel3.setLayout(new BorderLayout(0, 0));
    panel3.setMinimumSize(new Dimension(450, 29));
    panel3.setPreferredSize(new Dimension(450, 29));
    panel1.add(panel3, BorderLayout.SOUTH);
    final JLabel label2 = new JLabel();
    label2.setOpaque(true);
    label2.setText("Test class language:");
    panel3.add(label2, BorderLayout.WEST);
    myClassLanguageComboBox = new JComboBox<>();
    myClassLanguageComboBox.setMaximumSize(new Dimension(320, 29));
    myClassLanguageComboBox.setMinimumSize(new Dimension(320, 29));
    myClassLanguageComboBox.setOpaque(true);
    myClassLanguageComboBox.setPopupVisible(false);
    myClassLanguageComboBox.setPreferredSize(new Dimension(320, 29));
    panel3.add(myClassLanguageComboBox, BorderLayout.EAST);
  }

  private Font getFont(String fontName, int style, int size, Font currentFont) {
    if (currentFont == null) return null;
    String resultName;
    if (fontName == null) {
      resultName = currentFont.getName();
    }
    else {
      Font testFont = new Font(fontName, Font.PLAIN, 10);
      if (testFont.canDisplay('a') && testFont.canDisplay('1')) {
        resultName = fontName;
      }
      else {
        resultName = currentFont.getName();
      }
    }
    Font font = new Font(resultName, style >= 0 ? style : currentFont.getStyle(), size >= 0 ? size : currentFont.getSize());
    boolean isMac = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH).startsWith("mac");
    Font fontWithFallback = isMac
                            ? new Font(font.getFamily(), font.getStyle(), font.getSize())
                            : new StyleContext().getFont(font.getFamily(), font.getStyle(), font.getSize());
    return fontWithFallback instanceof FontUIResource ? fontWithFallback : new FontUIResource(fontWithFallback);
  }

  private static String getURLPath(String url) {
    try {
      return new URL(url).getPath();
    }
    catch (MalformedURLException e) {
      return url;
    }
  }

  private static List<String> getCanonicalPaths(List<VirtualFile> virtualFiles) {
    List<String> canonicalPaths = Lists.newArrayList();
    for (VirtualFile virtualFile : virtualFiles) {
      canonicalPaths.add(virtualFile.getCanonicalPath());
    }
    return canonicalPaths;
  }

  static VirtualFile getOrCreateSubdirectoryOnEDT(final VirtualFile parentDirectory, final String[] subdirectoriesPath,
                                                  final boolean includeLastPathElement) {
    return ApplicationManager.getApplication().runWriteAction((Computable<VirtualFile>) () -> {
      VirtualFile currentDirectory = parentDirectory;
      int subdirectoriesPathLength = includeLastPathElement ? subdirectoriesPath.length : subdirectoriesPath.length - 1;
      for (int i = 0; i < subdirectoriesPathLength; i++) {
        String subdirectory = subdirectoriesPath[i];
        VirtualFile child = currentDirectory.findChild(subdirectory);
        if (child == null) {
          try {
            currentDirectory = currentDirectory.createChildDirectory(null, subdirectory);
          }
          catch (Exception e) {
            throw new RuntimeException("Failed to create subdirectory " + subdirectory, e);
          }
        }
        else {
          currentDirectory = child;
        }
      }
      return currentDirectory;
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

  private static int computeOverlapSize(String sourceRootPath, String androidTestPath) {
    // Ensure that test path has the same file path prefix as the source root path (b/262355661).
    androidTestPath = getFilePathPrefix(sourceRootPath, androidTestPath) + androidTestPath;
    char[] pathChars1 = sourceRootPath.toCharArray();
    char[] pathChars2 = androidTestPath.toCharArray();
    int overlapSize = 0;
    for (int i = 0; i < Math.min(pathChars1.length, pathChars2.length); i++) {
      if (pathChars1[i] == pathChars2[i]) {
        overlapSize++;
      }
      else {
        break;
      }
    }
    return overlapSize;
  }

  private static String getFilePathPrefix(String sourceRootPath, String androidTestPath) {
    final String prefixMarker = ":/";
    int prefixIndex = sourceRootPath.indexOf(prefixMarker);
    if (prefixIndex != -1 && !androidTestPath.contains(prefixMarker)) {
      return sourceRootPath.substring(0, prefixIndex + 1);
    }
    return "";
  }

  @VisibleForTesting
  static boolean isGenerated(VirtualFile file, Project project) {
    if (ApplicationManager.getApplication().isDispatchThread()) {
      // GeneratedSourcesFilter.isGeneratedSourceByAnyFilter requires background thread access in recent
      // platform versions. Fall back to path-based detection on EDT to avoid threading assertions.
      String path = file.getPath();
      return path.contains("/build/generated/");
    }
    return GeneratedSourcesFilter.isGeneratedSourceByAnyFilter(file, project);
  }

  @VisibleForTesting
  static boolean isAndroidTest(VirtualFile file, Module module) {
    if (ApplicationManager.getApplication().isDispatchThread()) {
      // TestArtifactSearchScopes.isAndroidTestSource may require background thread access in recent
      // platform versions. Fall back to path-based detection on EDT.
      String path = file.getPath();
      return path.contains("/src/androidTest/");
    }
    TestArtifactSearchScopes searchScopes = TestArtifactSearchScopes.getInstance(module);
    return searchScopes != null && searchScopes.isAndroidTestSource(file);
  }

  private static List<VirtualFile> getExistingAndroidTestSourceRoots(Module module) {
    List<VirtualFile> existingAndroidTestSourceRoots = Lists.newArrayList();
    for (VirtualFile testSourceRoot : ModuleRootManager.getInstance(module).getSourceRoots(JavaSourceRootType.TEST_SOURCE)) {
      if (!isGenerated(testSourceRoot, module.getProject())) {
        if (isAndroidTest(testSourceRoot, module)) {
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

  private void updateOKButton() {
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

    if (ApplicationManager.getApplication().runReadAction((Computable<Boolean>) this::doesClassExist)) {
      myErrorMessageLabel.setText("File already exists.");
      return;
    }

    super.doOKAction();
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
  protected String getDimensionServiceKey() {
    return "#com.google.gct.testrecorder.ui.TestClassNameInputDialog";
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return myClassNameField;
  }
}
