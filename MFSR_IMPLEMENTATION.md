# Wronski Mosaic MFSR integration

This branch adds an experimental RAW-burst super-resolution output that reconstructs a **derived/synthetic Bayer mosaic** rather than pretending to preserve untouched sensor samples.

## Pipeline

`RAW Bayer burst -> PhotonCamera PyramidAlignment (local sub-pixel field) -> Wronski-style structure-steered / robust kernel accumulation -> sqrt(2)x RGGB target grid -> 16-bit synthetic CFA DNG`

For a 12 MP source, sqrt(2) scale in each dimension produces approximately 24 MP. Width and height are rounded and forced even so the output RGGB phase stays valid. A 2x scale in both dimensions would instead produce roughly 48 MP.

The merge only accumulates input sensor samples whose **color plane matches the target output CFA site**. It uses local gradient steering, anisotropic Gaussian weights, and a noise-aware robustness term so moving/disoccluded/misaligned samples are suppressed. The reference frame is retained as the graceful fallback.

## Product behavior

Advanced settings now include:

- `Standard merged RAW` (default): existing PhotonCamera behavior.
- `Mosaic SR DNG (experimental)`: saves the sqrt(2)x synthetic 16-bit RGGB DNG for later RawTherapee AMaZE/RCD/etc. demosaicing.
- `Keep source RAW burst`: saves the source Bayer frames alongside the reconstructed output.

A true `Linear RGB Prime DNG` was intentionally not mislabeled here: PhotonCamera's current native DNG writer emits CFA DNG. Implementing a standards-correct LinearRaw/Linear DNG requires extending `dngCreator.cpp` with RGB SamplesPerPixel/PhotometricInterpretation/plane metadata and is separate from the requested CFA MFSR path.

## Package / installation

`applicationId` is `com.particlesdevs.photoncamera.community`, while the Java namespace remains `com.particlesdevs.photoncamera`. This permits side-by-side Android installation without breaking the existing JNI symbol names.

## References

- Wronski et al., *Handheld Multi-Frame Super-Resolution*, ACM TOG 2019.
- Lafenetre, Facciolo, Eboli, *Implementing Handheld Burst Super-resolution*, IPOL 2023.
- Jamy-L/Handheld-Multi-Frame-Super-Resolution.
- kunzmi/ImageStackAlignator.
- JVision/Handheld-Multi-Frame-Super-Resolution.

## 2026-09-03 SR DNG output fix

- Mosaic SR accumulation now uses RG16F (numerator + denominator) instead of two RGBA32F textures. At ~25 MP this reduces the ping-pong accumulator allocation from roughly 800 MB to roughly 200 MB, avoiding the most likely mobile GPU OOM/failure path.
- The derived mosaic inherits `Parameters.cfaPattern`; RGGB is no longer hard-coded.
- Synthetic DNG output is explicitly 16-bit full range with BlackLevel=0 and WhiteLevel=65535 after black subtraction / normalization in the merge.
- SR dimensions are passed directly to `DngCreator.writeBuffer`; for 4080x3060 input the expected output is 5770x4328 (~24.97 MP).
- Failure no longer silently writes a 12 MP standard ESD4D file under the requested SR filename. A diagnostic standard fallback, if produced, is saved as `*_ESD4D_12MP_FALLBACK.dng`.
