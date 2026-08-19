# Implement Lens Discovery and Fix CaptureProcessor

The goal is to update `CaptureProcessor.kt` to work with the current project structure, specifically supporting the new lens discovery tool and enhanced camera ID management. `CaptureProcessor.kt` was authored with assumptions about missing classes and private fields, which need to be corrected.

## Proposed Changes

### [Capture Component]

#### [MODIFY] [CaptureProcessor.kt](file:///Users/monikamalinowska/Documents/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/capture/CaptureProcessor.kt)
- Remove Dagger annotations (`@Singleton`, `@Inject`) as the project does not use Dagger.
- Remove dependencies on non-existent `CameraLifecycleManager` and `PreviewManager`.
- Use `CaptureController`'s fields directly (after making them accessible).
- Replace `DebugTimeline` with standard `Log`.
- Fix ZSL logic to correctly handle `CameraCharacteristics` passed from the enhanced camera ID system.

#### [MODIFY] [CaptureController.java](file:///Users/monikamalinowska/Documents/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/capture/CaptureController.java)
- Make `cameraEventsListener` and `processExecutor` public to allow `CaptureProcessor` to access them.
- Integrate `CaptureProcessor` into the capture flow, replacing the legacy ZSL logic in `CaptureController`.

## Verification Plan

### Automated Tests
- Build the project to ensure no compilation errors in `CaptureProcessor.kt`.

### Manual Verification
- Deploy to a device and test ZSL capture with different lenses (including hidden ones discovered via the Lens Discovery Tool).
- Verify that camera rotation and exposure parameters are correctly applied for all lenses.
