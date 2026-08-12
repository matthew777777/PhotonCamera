# Implementation Plan - Fix Focus Shift During Capture

This plan addresses the "focus shift" issue where the focus jumps after pressing the shutter button. The fix involves ensuring consistent focus state and parameters between the viewfinder preview and the still capture sequence.

## User Review Required

> [!IMPORTANT]
> The changes modify core focus locking and capture request building. While intended to stabilize focus, behavior might vary across different device drivers.

## Proposed Changes

### Camera Capture Logic

#### [MODIFY] [CaptureController.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/capture/CaptureController.java)

- **Fix `captureStillPicture()` focus reset**:
    - Remove the block that incorrectly sends `CONTROL_AF_TRIGGER_CANCEL` and overrides `CONTROL_AF_MODE` to `AUTO` when the preview is in `CONTINUOUS_PICTURE` mode.
    - Instead, ensure the `captureBuilder` inherits the focus mode and state from the preview request that was just locked.
- **Synchronize Focus Regions**:
    - Ensure `CONTROL_AF_REGIONS` are always carried over from `mPreviewRequestBuilder` to `captureBuilder`, not just during touch-to-focus.
- **Restore Manual Focus Support**:
    - Uncomment and fix the manual focus application logic. If `ParamController` has a manual focus distance set, apply `CONTROL_AF_MODE_OFF` and the specific `LENS_FOCUS_DISTANCE` to the still capture request.
- **Clean up `unlockFocus()`**:
    - Simplify the focus unlock sequence. Replace the erratic `CANCEL` -> `START` -> `CANCEL` triggers with a single `CONTROL_AF_TRIGGER_CANCEL` and restore the default focus mode. This will prevent the viewfinder from jumping after a picture is taken.
- **Optimize `lockFocus()`**:
    - Ensure that if the camera is already in a focused state (e.g., `PASSIVE_FOCUSED` or `FOCUSED_LOCKED`), the `AF_START` trigger is handled gracefully to avoid unnecessary re-scans on devices with buggy drivers.

## Verification Plan

### Manual Verification
- **Touch-to-Focus Test**: Touch a near object, wait for focus lock, and press shutter. Verify the focus stays on the object in the final image and doesn't jump in the viewfinder.
- **Manual Focus Test**: Set a specific manual focus distance, press shutter, and verify the resulting image uses that exact focus distance.
- **Continuous AF Test**: Point the camera at different objects, let it focus naturally, and press shutter. Verify no "hunting" or jumping happens at the moment of capture.
- **Post-Capture Stability**: Verify the viewfinder doesn't perform a distracting focus scan/jump immediately after the capture sequence finishes.
