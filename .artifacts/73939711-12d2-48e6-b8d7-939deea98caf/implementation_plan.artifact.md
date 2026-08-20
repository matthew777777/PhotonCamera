# Implementation Plan - Modify Gallery Icon to Nikon Style

The goal is to update the gallery application icon (PGallery) to match the "Nikon Style" (Nikon Yellow background with Black foreground elements), consistent with the main app's icon.

## User Review Required

> [!NOTE]
> The current gallery icon uses a complex multi-layered vector with blue/purple gradients and a dark mask. This plan simplifies it to a solid Nikon Yellow background with black vector shapes for the frame and mountains.

## Proposed Changes

### 1. Update Gallery Launcher Definition

#### [MODIFY] [ic_gallery_launcher.xml](file:///Users/monikamalinowska/Documents/PhotonCamera/app/src/main/res/mipmap-anydpi-v26/ic_gallery_launcher.xml)
- Change `<background>` to use `@color/nikon_yellow` (or `@color/ic_launcher_background`).

### 2. Update Foreground Drawable

#### [MODIFY] [gallery_launch_fg.xml](file:///Users/monikamalinowska/Documents/PhotonCamera/app/src/main/res/drawable/gallery_launch_fg.xml)
- Remove the complex gradients and dark mask.
- Add the "frame" path from the existing monochrome/foreground drawable in solid Black.
- Add the "mountains" path in solid Black.
- Ensure the viewport and scaling match the adaptive icon requirements (108dp content area).

### 3. Cleanup Background Drawable (Optional)

#### [MODIFY] [gallery_launch_bg.xml](file:///Users/monikamalinowska/Documents/PhotonCamera/app/src/main/res/drawable/gallery_launch_bg.xml)
- Since the background will be handled by the solid color, this file can be simplified or left as is if not used. I will simplify it to a solid yellow just in case.

### 4. Update Monochrome Icon

#### [MODIFY] [gallery_launch_mono.xml](file:///Users/monikamalinowska/Documents/PhotonCamera/app/src/main/res/drawable/gallery_launch_mono.xml)
- Ensure the monochrome version remains consistent (Black shapes on transparent/white).

## Verification Plan

### Manual Verification
1. Build the app and check the launcher icon for "PGallery" on the Android home screen.
2. Verify it shows the Nikon Yellow background with Black frame and mountains.
3. Check different icon shapes (Circle, Square, Squircle) to ensure the adaptive icon layers are correctly centered.
