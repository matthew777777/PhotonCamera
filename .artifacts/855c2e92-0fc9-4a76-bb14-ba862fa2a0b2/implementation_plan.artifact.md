# Implementation Plan - Advanced "Pro & Fun" Features

This plan introduces advanced features for power users and modern camera interactions.

## User Review Required

> [!IMPORTANT]
> **Zebra Stripes** and **Focus Peaking** can be visually noisy. We should ensure they are easily toggleable via a "Quick Settings" overlay or the main settings menu.

> [!WARNING]
> **Voice Shutter** requires microphone access and always-on listening while the camera is active, which can impact battery life.

## Proposed Changes

### 1. Zebra Stripes (Overexposure Indicator)
Highlight areas of the preview that are overexposed (clipping) using a animated stripe pattern.

#### [MODIFY] [main_fs.glsl](file:///Users/monikamalinowska/PhotonCamera/app/src/main/assets/shaders/preview/main_fs.glsl)
- Add logic to detect luminance > 0.95.
- Apply a diagonal stripe pattern to those areas.
- Add a uniform `enableZebra` to toggle the effect.

---

### 2. Focus Peaking Color Customization
Allow users to choose their preferred peaking color for better visibility in different scenes.

#### [MODIFY] [main_fs.glsl](file:///Users/monikamalinowska/PhotonCamera/app/src/main/assets/shaders/preview/main_fs.glsl)
- Replace the hardcoded peaking color with a `uniform vec4 peakColor`.

#### [MODIFY] [preferences.xml](file:///Users/monikamalinowska/PhotonCamera/app/src/main/res/xml/preferences.xml)
- Add a `ListPreference` for "Focus Peaking Color" (Red, Green, Blue, White).

---

### 3. Voice Shutter ("Say Cheese")
Trigger a photo capture using voice commands.

#### [NEW] [VoiceTrigger.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/control/VoiceTrigger.java)
- Use Android's `SpeechRecognizer` or a simple audio energy detector to listen for specific keywords.

#### [MODIFY] [CameraActivity.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/ui/camera/CameraActivity.java)
- Initialize and manage the `VoiceTrigger` lifecycle.

---

### 4. Palm Selfie Gesture
Start a 3-second timer when a palm is detected in the frame.

#### [NEW] [GestureController.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/control/GestureController.java)
- Integrate a basic palm detection model (using the already enabled `mlModelBinding` if a lightweight TFLite model is available, or use the Camera2 Face Detection as a proxy for positioning).

---

### 5. Double Tap to Switch Camera
Quickly flip between the front and rear cameras by double-tapping the viewfinder.

#### [MODIFY] [TouchFocus.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/control/TouchFocus.java)
- Implement a `GestureDetector` to distinguish between single tap (focus) and double tap (switch).

---

### 6. Floating Shutter Button
Add a secondary shutter button that the user can drag anywhere on the screen for ergonomic shooting.

#### [NEW] [FloatingShutterView.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/ui/camera/views/FloatingShutterView.java)
- A draggable `ImageButton` that triggers the capture event.

## Verification Plan

### Automated Tests
- Test the `VoiceTrigger` logic with mock audio input.

### Manual Verification
1.  **Zebra Stripes**: Point at a bright light and verify the "marching ants" or stripe pattern appears on clipped areas.
2.  **Peaking Color**: Change the color in settings and verify the preview updates immediately.
3.  **Voice Shutter**: Say "Capture" or "Cheese" in a quiet room and verify the photo is taken.
4.  **Double Tap**: Double tap the screen and verify the camera flips.
5.  **Floating Button**: Drag the button around and ensure it still triggers the shutter.
