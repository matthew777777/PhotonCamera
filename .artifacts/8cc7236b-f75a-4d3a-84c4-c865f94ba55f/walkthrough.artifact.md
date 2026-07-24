# Walkthrough - Google Camera HDR+ Enhanced AE Strategy

I have implemented the Google Camera-style Shutter-Priority AE behavior. This change ensures that the camera captures as many photons as possible by prioritizing longer shutter speeds before increasing ISO, which helps reduce noise and improves brightness in low-light conditions.

## Changes Made

### [IsoExpoSelector.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/processing/parameters/IsoExpoSelector.java)

- **Added Shutter Limits**:
    - Handheld Photo: 1/10s
    - Handheld Night: 1/5s
    - Tripod: 1.0s
- **Implemented `applyShutterPriorityCurve`**:
    - This method takes the "Total Exposure Energy" (ISO × Exposure) from the preview/AE system.
    - It first resets ISO to minimum (101) and sets the shutter speed to the required value.
    - If the required shutter speed exceeds the "handheld" or "tripod" limit, it caps the shutter speed at that limit and increases ISO as a last resort.
- **Dynamic Mode Support**:
    - Automatically switches limits between Photo and Night modes.
    - Leverages existing tripod detection from the `Gyro` class.
- **Cleanup**:
    - Removed legacy, hardcoded "ReduceIso" and "ReduceExpo" blocks that were causing inconsistent underexposure.

## Verification Results

### Logic Verification
Logs have been added to track the transformation:
`Applied Curve: Energy=... -> Result: Exp=1/10 ISO=...`

This allows verifying that the curve is indeed pushing the shutter speed to 1/10s (Photo) or 1/5s (Night) before ISO starts climbing significantly.

> [!TIP]
> This behavior mimics the HDR+ Enhanced strategy where the camera "drifts" towards the longest safe handheld shutter speed to maximize SNR.
