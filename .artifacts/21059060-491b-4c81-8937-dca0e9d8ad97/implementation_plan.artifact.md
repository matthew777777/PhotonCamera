# Implementation Plan - Fix Histogram Flickering and Rotation

The histogram flickers because it is being cleared by the metadata update loop when debug data is disabled. It is also appearing upside down in landscape mode due to incorrect rotation mapping.

## User Review Required

> [!IMPORTANT]
> I am moving the clearing logic entirely into the `SurfaceViewOverViewfinder`'s drawing pass. Instead of a separate `clear()` method that nullifies data, the view will decide what to draw based on the current preferences during every `refresh()` call.

## Proposed Changes

### [Viewfinder & Overlay]

#### [MODIFY] [SurfaceViewOverViewfinder.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/ui/camera/views/viewfinder/SurfaceViewOverViewfinder.java)
- **Fix Flickering**:
    - Remove the code that nullifies `histogramData` inside `clear()`.
    - Modify `drawOnCanvas` to always clear the surface and then conditionally draw layers based on `PreferenceKeys`.
    - Add a `forceClear()` method for when we really need to wipe everything (e.g., on pause).
- **Fix Upside-Down Rotation**:
    - Adjust the `rotationDegrees` mapping and the `canvas.rotate` call.
    - Test-based correction: Change `-rotationDegrees` to `rotationDegrees` or adjust the specific cases.
    - Specifically, in landscape orientations, ensure the bars grow "up" relative to the user's visual horizon.

#### [MODIFY] [CameraFragment.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/ui/camera/CameraFragment.java)
- **Simplify Refresh Logic**:
    - In `updateScreenLog`, always call `surfaceView.refresh()` if either Histogram or AF Data is enabled.
    - Avoid calling `clear()` in the high-frequency loop if there is any active overlay.

## Verification Plan

### Automated Tests
- None.

### Manual Verification
1.  **Landscape Stability**: Rotate to landscape and verify the histogram is upright and in the top-right corner.
2.  **Flicker Test**: Ensure the histogram doesn't disappear/flicker when "Show Debug Data" is OFF but "Histogram" is ON.
3.  **Clean Exit**: Verify the overlay is cleared when both Histogram and Debug Data are turned OFF.
