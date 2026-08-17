# Fix NullPointerException in FrameNumberSelector.getFrames()

The application crashes with a `NullPointerException` when `FrameNumberSelector.getFrames()` is called because it relies on `PhotonCamera.getCaptureController()`, which can be `null` if the `CaptureController` instance hasn't been set in `PhotonCamera` or has been cleared (e.g., during activity/fragment destruction) while a background capture task is still running.

## Proposed Changes

The plan is to pass the `CaptureController` instance directly to `getFrames()` to ensure it works even if the static reference in `PhotonCamera` is null.

### [Component Name]

#### [MODIFY] [FrameNumberSelector.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/processing/parameters/FrameNumberSelector.java)
- Update `getFrames()` to accept a `CaptureController` parameter.
- Use the passed `CaptureController` instance to access `mPreviewIso`.
- Add a null check and fallback for `mPreviewIso`.

#### [MODIFY] [CaptureController.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/capture/CaptureController.java)
- Update the call to `FrameNumberSelector.getFrames()` to pass `this`.

#### [MODIFY] [CaptureProcessor.kt](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/capture/CaptureProcessor.kt)
- Update the call to `FrameNumberSelector.getFrames()` to pass the `controller` instance already available in `triggerZslCapture()`.

## Verification Plan

### Automated Tests
- I will check if there are existing tests for `FrameNumberSelector` or `CaptureController` and try to run them if possible.

### Manual Verification
- Since I cannot run the app and trigger the crash manually, I will rely on the code fix ensuring that the `CaptureController` instance is always valid when `getFrames()` is called from its own methods or from `CaptureProcessor`.
