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
package com.google.gct.testing.dimension;

import com.google.api.services.testing.model.AndroidDeviceCatalog;
import com.google.api.services.testing.model.AndroidModel;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.gct.testing.CloudConfigurationImpl;
import com.google.gct.testing.CloudTestingUtils;
import com.google.gct.testing.dimension.ApiDimension.ApiLevel;
import com.google.gct.testing.launcher.CloudAuthenticator;

import javax.swing.*;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class DeviceDimension extends CloudConfigurationDimension {

  public static final String DISPLAY_NAME = "Device";

  private static ImmutableList<Device> FULL_DOMAIN;
  private static Device defaultDevice;

  public DeviceDimension(CloudConfigurationImpl googleCloudTestingConfiguration) {
    super(googleCloudTestingConfiguration);
  }

  @Override
  public List<? extends CloudTestingType> getAppSupportedDomain() {
    return getFullDomain();
  }

  public static List<? extends CloudTestingType> getFullDomain() {
    if (isFullDomainMissing() || shouldPollDiscoveryTestApi(DISPLAY_NAME)) {
      ImmutableList.Builder<Device> fullDomainBuilder = new ImmutableList.Builder<Device>();
      AndroidDeviceCatalog androidDeviceCatalog = CloudAuthenticator.getInstance().getAndroidDeviceCatalog();
      if (androidDeviceCatalog != null) {
        for (AndroidModel model : androidDeviceCatalog.getModels()) {
          List<String> supportedVersionIds = model.getSupportedVersionIds();
          // Disregard devices with no supported APIs.
          if (supportedVersionIds == null || supportedVersionIds.isEmpty()) {
            continue;
          }

          Map<String, String> details = new HashMap<>();
          int numberOfPixels = 0;
          if (model.getScreenX() != null && model.getScreenY() != null) {
            details.put("Display", model.getScreenX() + "x" + model.getScreenY());
            numberOfPixels = model.getScreenX() * model.getScreenY();
          }
          Device device = new Device(model.getId(), model.getName(), model.getManufacturer(), model.getForm(), details,
                                     supportedVersionIds, numberOfPixels);
          fullDomainBuilder.add(device);
          List<String> tags = model.getTags();
          if (tags != null && tags.contains("default")) {
            defaultDevice = device;
          }
        }
      }
      // Do not reset a valid full domain if some intermittent issues happened.
      if (isFullDomainMissing() || !fullDomainBuilder.build().isEmpty()) {
        FULL_DOMAIN = fullDomainBuilder.build();
      }
      resetDiscoveryTestApiUpdateTimestamp(DISPLAY_NAME);
    }
    return FULL_DOMAIN;
  }

  private static boolean isFullDomainMissing() {
    return FULL_DOMAIN == null || FULL_DOMAIN.isEmpty();
  }

  private static Device getDefaultDevice() {
    if (defaultDevice == null) {
      getFullDomain();
    }
    return defaultDevice;
  }

  public void enableDefault(String minSupportedApiId) {
    if (getDefaultDevice() == null) {
      return;
    }
    if (defaultDevice.supportedVersionIds.contains(minSupportedApiId)) {
      enable(defaultDevice);
    } else {
      for (CloudTestingType supportedTestingType : getAppSupportedDomain()) {
        Device supportedDevice = (Device) supportedTestingType;
        if (supportedDevice.isVirtual() && supportedDevice.supportedVersionIds.contains(minSupportedApiId)) {
          enable(supportedDevice);
          return;
        }
      }
    }
  }

  @Override
  public String getDisplayName() {
    return DISPLAY_NAME;
  }

  @Override
  public String getId() {
    return "DEVICE";
  }

  @Override
  public Icon getIcon() {
    return CloudTestingUtils.CLOUD_DEVICE_ICON;
  }

  @Override
  public boolean shouldBeAlwaysGrouped() {
    return true;
  }

  public void enableCompatibleTopN(List<? extends CloudTestingType> devices, ImmutableList<? extends CloudTestingType> apiLevels,
                                   int numPhysical, int numVirtual) {
    List<Device> virtualCompatibleDevices = Lists.newLinkedList();
    List<Device> physicalCompatibleDevices = Lists.newLinkedList();

    for (CloudTestingType deviceTestingType : devices) {
      Device device = (Device)deviceTestingType;
      if (device.supportedVersionIds == null) {
        continue;
      }
      for (CloudTestingType apiLevel : apiLevels) {
        if (device.supportedVersionIds.contains(String.valueOf(((ApiLevel)apiLevel).getApiVersion()))) {
          if (device.isVirtual()) {
            virtualCompatibleDevices.add(device);
          } else {
            physicalCompatibleDevices.add(device);
          }
          break;
        }
      }
    }

    List<Device> sortedVirtualDevices = Ordering.from(DEVICE_COMPARATOR).reverse().sortedCopy(virtualCompatibleDevices);
    List<Device> sortedPhysicalDevices = Ordering.from(DEVICE_COMPARATOR).reverse().sortedCopy(physicalCompatibleDevices);

    if (numVirtual > sortedVirtualDevices.size()) {
      numPhysical += numVirtual - sortedVirtualDevices.size();
      numVirtual = sortedVirtualDevices.size();
    }

    int actualNumPhysical = 0;
    for (Device device : sortedPhysicalDevices) {
      if (actualNumPhysical == numPhysical) {
        break;
      }
      enable(device);
      actualNumPhysical++;
    }

    if (numPhysical > actualNumPhysical) {
      numVirtual += numPhysical - actualNumPhysical;
    }

    int actualNumVirtual = 0;
    for (Device device : sortedVirtualDevices) {
      if (actualNumVirtual == numVirtual) {
        break;
      }
      enable(device);
      actualNumVirtual++;
    }
  }

  public static class Device extends CloudTestingType {

    private final String id;
    private final String name;
    private final String manufacturer;
    private final String form;
    private final List<String> supportedVersionIds;
    private final int numberOfPixels;

    public Device(String id, String name, String manufacturer, String form, Map<String, String> details, List<String> supportedVersionIds,
                  int numberOfPixels) {
      this.id = id;
      this.manufacturer = manufacturer;
      this.name = name;
      this.form = form;
      this.details = details;
      this.supportedVersionIds = supportedVersionIds;
      this.numberOfPixels = numberOfPixels;
    }

    @Override
    public String getGroupName() {
      return form; // Group devices by their form, i.e., VIRTUAL or PHYSICAL.
    }

    @Override
    public String getGroupDescription() {
      return isVirtual()
             ? "Android OS running on Google Compute Engine."
             : "An actual physical device managed by Google.";
    }

    @Override
    public String getConfigurationDialogDisplayName() {
      return name + ", " + manufacturer;
    }

    @Override
    public String getResultsViewerDisplayName() {
      return getConfigurationDialogDisplayName() + (isVirtual() ? ", Virtual" : "");
    }

    public boolean isVirtual() {
      return form.equals("VIRTUAL");
    }

    @Override
    public String getId() {
      return id;
    }
  }

  private static final Comparator<Device> DEVICE_COMPARATOR = (device1, device2) -> device1.numberOfPixels - device2.numberOfPixels;
}
