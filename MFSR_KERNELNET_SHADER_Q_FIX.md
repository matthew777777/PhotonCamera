# MFSR KernelNet shader q-redeclaration fix

This revision fixes the Mali GLSL compiler error `S0022: Symbol 'q' redeclared` in `wronski_mosaic_accumulate.glsl`.

Changes:
- RAW sample coordinate renamed from `q` to `sampleP`.
- Kernel exponent renamed from `q` to `kernelExponent`.
- Reference-loop coordinate renamed to `refSampleP`.
- Related weights renamed descriptively to avoid driver-sensitive shadowing.
- `rawNorm(frameRaw, sampleP)` now unambiguously receives the integer RAW coordinate.
- Existing KernelNet `(s1,s2,rho)` spatial reconstruction map, PyramidAlignment, Wronski robustness, Mali-safe RGBA16F accumulation, fragment R16UI finalization, and DNG path are unchanged.
