# Implementation Plan - Add AE Metering Mode for MTK Devices

This plan outlines the steps to add a new "Metering Mode" setting to the camera settings bar, specifically targeting MediaTek (MTK) devices using the vendor key `com.mediatek.3afeature.aeMeteringMode`.

## User Review Required

> [!IMPORTANT]
> - I will be using `ic_exposure` as the placeholder icon for the Metering Mode setting as no specific metering icons were found in the project.
> - The setting will only have an effect on devices that support the `com.mediatek.3afeature.aeMeteringMode` vendor tag. On other devices, the setting will be visible in the menu but will not affect the camera behavior (due to the `isSupported` check).

## Proposed Changes

### [Component: UI & Resources]

#### [MODIFY] [strings.xml](file:///Users/monikamalinowska/PhotonCamera/app/src/main/res/values/strings.xml)
- Add strings for AE Metering Mode title and its options (Off, Center Weighted, Frame Average, Spot Metering).
- Add the preference key string.

#### [MODIFY] [ids.xml](file:///Users/monikamalinowska/PhotonCamera/app/src/main/res/values/ids.xml)
- Add IDs for the new settings bar entry layout and the four buttons (Off, Center, Average, Spot).

---

### [Component: Settings]

#### [MODIFY] [SettingType.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/settings/SettingType.java)
- Add `AE_METERING` to the enum.

#### [MODIFY] [PreferenceKeys.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/settings/PreferenceKeys.java)
- Add `KEY_AE_METERING` to the `Key` enum.
- Add static methods `getAeMetering()` and `setAeMetering(int)`.
- Set the default value (0 for Off) in `setDefaults()`.

---

### [Component: Camera UI & Control]

#### [MODIFY] [SettingsBarEntryProvider.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/ui/camera/viewmodel/SettingsBarEntryProvider.java)
- Define `aeMeteringEntry`.
- Initialize it and add to `allEntries`.
- Implement `createAeMeteringEntry()` to set up the buttons and their values.
- Update `createEntries()` and `updateAllEntries()` to include the new entry.

#### [MODIFY] [CameraUIController.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/ui/camera/CameraUIController.java)
- Update `onChanged()` to handle the `AE_METERING` setting change.
- Call `applyAeMetering()` on the capture controller when the setting changes.

#### [MODIFY] [CaptureController.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/capture/CaptureController.java)
- Add `applyAeMetering()` method which triggers `VendorTagUtils.builderSessionApply` and then rebuilds the preview request.

#### [MODIFY] [VendorTagUtils.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/api/VendorTagUtils.java)
- Define the `com.mediatek.3afeature.aeMeteringMode` `CaptureRequest.Key`.
- Update `builderSessionApply()` to read the AE Metering setting and apply it to the builder if supported.

---

## Verification Plan

### Automated Tests
- Not applicable for this UI/Vendor tag change.

### Manual Verification
- **UI Check**: Verify that "Metering Mode" appears in the settings bar with four options.
- **Selection Check**: Verify that selecting different modes updates the UI state (icon/text) correctly.
- **Functionality (MTK Device)**: If deployed on an MTK device, check logs for "aeMeteringMode set to X" messages from `VendorTagUtils`.
- **Safety Check**: Verify the app still runs and takes photos on non-MTK devices without crashes.
