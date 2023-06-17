/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.google.gct.testing.android;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.gct.testing.CloudConfigurationHelper;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.util.Pair;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.ui.JBUI;
import org.jdesktop.swingx.combobox.ListComboBoxModel;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.util.List;
import java.util.Map;

public class CloudProjectSelector extends ComboBox<String> {
  private static final String LOADING_CLOUD_PROJECTS_STRING = "Loading cloud projects...";
  private static final List<String> LOADING_CLOUD_PROJECTS_LIST = ImmutableList.of(LOADING_CLOUD_PROJECTS_STRING);

  // Load cloud projects once per Android Studio session, unless explicitly refreshed by the user.
  private static volatile List<String> myCloudProjects;

  private final CloudConfiguration.Kind myConfigurationKind;
  private int myCurrentConfigurationId = -1;
  private Module myCurrentModule;

  // Used to keep track of user choices when run config and/or module are not available.
  private static Map<CloudConfiguration.Kind, String> myLastChosenProjectIdPerKind = Maps.newHashMapWithExpectedSize(5);

  /** A cache of project ids selected by <kind, module> per android run configuration, so that if
   * the configuration and/or module selections change back and forth, we retain the appropriate selected project id.
   */
  private static Map<Integer, Map<Pair<CloudConfiguration.Kind, Module>, String>> myProjectByConfigurationIdAndModuleCache =
    Maps.newHashMapWithExpectedSize(5);

  public CloudProjectSelector(@NotNull CloudConfiguration.Kind configurationKind) {
    myConfigurationKind = configurationKind;

    setRenderer(new CloudProjectRenderer());

    addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        Object selectedItem = getSelectedItem();
        if (selectedItem != null && !LOADING_CLOUD_PROJECTS_STRING.equals(selectedItem)) {
          rememberChosenProjectId((String)selectedItem);
        }
      }
    });

    if (myCloudProjects == null || myCloudProjects.isEmpty()) {
      setDefaultPreferredSize();
    }

    if (myCloudProjects != null) {
      setModel(new ListComboBoxModel(myCloudProjects));
    }
  }

  public void refreshCloudProjects() {
    setModel(new ListComboBoxModel<String>(LOADING_CLOUD_PROJECTS_LIST));
    Boolean wasEnabled = isEnabled();
    setEnabled(false);

    // Do not block the UI thread while getting cloud projects, since it requires network communication.
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      myCloudProjects = CloudConfigurationHelper.getCloudProjects();
      SwingUtilities.invokeLater(() -> {
        if (!myCloudProjects.isEmpty()) {
          // Make sure UI recomputes the size based on the content of the combobox.
          setPreferredSize(null);
        } else {
          setDefaultPreferredSize();
        }
        setModel(new ListComboBoxModel(myCloudProjects));
        restoreChosenProjectId();
        setEnabled(wasEnabled);

        // Simulate a change event such that it is picked up by the editor validation mechanisms.
        for (ItemListener itemListener : getItemListeners()) {
          itemListener.itemStateChanged(new ItemEvent(this, ItemEvent.ITEM_STATE_CHANGED, this, ItemEvent.SELECTED));
        }
      });
    });
  }

  private void setDefaultPreferredSize() {
    setPreferredSize(new Dimension(JBUI.scale(200), getMinimumSize().height));
  }

  @NotNull
  public String getProjectId() {
    Object selectedItem = getSelectedItem();
    if (LOADING_CLOUD_PROJECTS_STRING.equals(selectedItem)) {
      selectedItem = getStoredChosenProjectId();
    }
    return selectedItem == null ? "" : (String)selectedItem;
  }

  public void updateCloudProjectId(@NotNull String cloudProjectId) {
    setSelectedItem(cloudProjectId);
    // Reset the selection whenever a persisted cloud project is empty or is missing in the list of available cloud projects
    // for consistent behavior of "Cancel" button in all scenarios.
    if (!cloudProjectId.equals(getSelectedItem()) && getItemCount() > 0) {
      setSelectedIndex(0);
    }
    rememberChosenProjectId(cloudProjectId);
  }

  public void setFacet(@Nullable AndroidFacet facet) {
    if (facet == null) {
      return;
    }

    myCurrentModule = facet.getModule();

    if (myCloudProjects == null) {
      refreshCloudProjects();
    } else {
      restoreChosenProjectId();
    }
  }

  public void setRunConfigurationId(int configurationId) {
    myCurrentConfigurationId = configurationId;
  }

  private void rememberChosenProjectId(@NotNull String cloudProjectId) {
    myLastChosenProjectIdPerKind.put(myConfigurationKind, cloudProjectId);

    if (myCurrentConfigurationId == -1 || myCurrentModule == null) {
      return;
    }

    Map<Pair<CloudConfiguration.Kind, Module>, String> projectByModuleCache =
      myProjectByConfigurationIdAndModuleCache.get(myCurrentConfigurationId);
    if (projectByModuleCache == null) {
      projectByModuleCache = Maps.newHashMapWithExpectedSize(5);
      myProjectByConfigurationIdAndModuleCache.put(myCurrentConfigurationId, projectByModuleCache);
    }

    projectByModuleCache.put(Pair.create(myConfigurationKind, myCurrentModule), cloudProjectId);
  }

  private void restoreChosenProjectId() {
    String storedChosenProjectId = getStoredChosenProjectId();
    if (storedChosenProjectId != null) {
      setSelectedItem(storedChosenProjectId);
    }
  }

  @Nullable
  private String getStoredChosenProjectId() {
    if (myCurrentConfigurationId == -1 || myCurrentModule == null) {
      return myLastChosenProjectIdPerKind.get(myConfigurationKind);
    }

    Map<Pair<CloudConfiguration.Kind, Module>, String> projectByModuleCache =
      myProjectByConfigurationIdAndModuleCache.get(myCurrentConfigurationId);
    if (projectByModuleCache != null) {
      return projectByModuleCache.get(Pair.create(myConfigurationKind, myCurrentModule));
    }

    return null;
  }

  private static class CloudProjectRenderer extends ColoredListCellRenderer {
    @Override
    protected void customizeCellRenderer(JList list, Object value, int index, boolean selected, boolean hasFocus) {
      if (value == null) {
        append("[none]", SimpleTextAttributes.ERROR_ATTRIBUTES);
      } else {
        append(String.valueOf(value));
      }
    }
  }
}
