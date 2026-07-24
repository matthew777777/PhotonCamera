# Implementation Plan - Google Camera Style HDR+ Enhanced AE Strategy

Implement Google Camera's HDR+ Enhanced (HDR+ E) auto exposure behavior in PhotonCamera for Photo and Night modes. This includes a Shutter-Priority AE Curve to maximize photon capture and a Dynamic Low-Light AE Strategy.

## User Review Required

> [!IMPORTANT]
> The "handheld limit" for shutter speed is set to 1/10s for Photo mode and 1/5s for Night mode by default. These values assume some level of stability or OIS. If the user wants different defaults, they can be adjusted.
> Tripod detection is used to extend the shutter speed up to 1s.

## Proposed Changes

### [Component] Exposure Selection logic

#### [MODIFY] [IsoExpoSelector.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/processing/parameters/IsoExpoSelector.java)

- Define constants for handheld and tripod shutter limits.
- Update `GenerateExpoPair` to calculate total exposure and redistribute it using a shutter-priority curve.
- Implement `applyShutterPriorityCurve` helper in `ExpoPair` class.
- Incorporate dynamic logic based on `CameraMode` (Photo/Night) and tripod detection.
- Remove or refactor existing crude "ReduceIso" logic to use the new curve.

## Verification Plan

### Automated Tests
- Since this is hardware-dependent camera logic, unit tests are limited. I will verify the logic by adding logs to `IsoExpoSelector` and ensuring the calculated ISO/Exposure values follow the expected curve for various input "preview" values.

### Manual Verification
- Deploy to device.
- Test in Photo mode under various lighting conditions:
    - Bright light: ISO should be near minimum, shutter speed short.
    - Dim light: ISO should stay low while shutter speed increases up to 1/10s.
    - Very dim light: Shutter speed stays at 1/10s while ISO increases.
- Test in Night mode:
    - Shutter speed should be able to go up to 1/5s handheld.
    - If on tripod, shutter speed should go much longer (up to 1s).
- Compare result brightness with previous versions to ensure "underexposure" is mitigated.
