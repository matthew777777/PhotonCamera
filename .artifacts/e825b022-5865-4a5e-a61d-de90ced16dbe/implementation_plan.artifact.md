# Fix NullPointerException in LiveData observation

The application crashes with a `NullPointerException` because `mCameraUIEventsListener` in `CameraFragment` is never initialized. This null value is passed to `SettingsBarEntryProvider.addObserver()`, which calls `LiveData.observeForever(null)`. When the `LiveData` value changes, the system attempts to call `onChanged()` on the null observer, leading to the crash.

## Proposed Changes

### [app](file:///Users/monikamalinowska/PhotonCamera/app)

#### [MODIFY] [CameraFragment.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/ui/camera/CameraFragment.java)
- Initialize `mCameraUIEventsListener` in `initMembers()`.
- Update `initSettingsBar()` to pass `getViewLifecycleOwner()` to `settingsBarEntryProvider.addObserver()`.
- Remove manual `removeObserver()` call in `onDestroy()` as the observation will now be lifecycle-aware.

#### [MODIFY] [SettingsBarEntryProvider.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/ui/camera/viewmodel/SettingsBarEntryProvider.java)
- Update `addObserver()` to accept a `LifecycleOwner` and use `observe()` instead of `observeForever()`.
- Add a null check for the observer to prevent similar issues in the future.
- Keep `removeObserver()` for compatibility if needed, but it should be rarely used now.

## Verification Plan

### Manual Verification
- Deploy the application to a device/emulator.
- Navigate to the camera screen.
- Verify that changing settings in the top bar no longer causes a crash.
- Verify that the application remains stable during fragment transitions and rotations.
