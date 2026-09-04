# MFSR KernelNet integration

This branch reuses PhotonCamera's existing KernelNet inference performed inside ESD4D.
KernelNet is treated as a spatially adaptive anisotropic denoising/reconstruction-kernel network,
not as a blur/downsampling-kernel estimator.

Pipeline:
RAW burst -> ESD4D/PyramidAlignment -> KernelNet (s1,s2,rho) reconstruction map ->
Wronski-style robust multi-frame accumulation on the finer CFA grid -> Mali-safe R16UI
fragment finalization -> synthetic 16-bit DNG.

The MFSR shader samples the exact ESD4D KernelNet map and uses the same covariance quadratic form
as merge/mergeCombineWeight0.glsl. Sub-pixel burst coverage produces the emergent super-resolution.

Mali compatibility: accumulator image storage is exposed through explicit readonly and writeonly
RGBA16F image bindings. No coherent unqualified image2D and no r16ui compute imageStore are used.
