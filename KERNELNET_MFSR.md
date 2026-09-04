# KernelNet-assisted Wronski MFSR

Pipeline preserved:

RAW burst -> existing ESD4D/PyramidAlignment -> Wronski CFA reconstruction -> KernelNet-style degradation prior modulates anisotropic merge covariance -> robustness weighting -> ~sqrt(2) Bayer target -> 16-bit DNG.

The mobile predictor is self-contained in GLSL and adds no TensorFlow/ONNX dependency. It estimates blur, anisotropy, detail and confidence from local RAW statistics and modulates the Wronski covariance; it does not replace alignment. The implementation is KernelNet-style rather than the authors' exact trained model because pretrained KernelNet weights are not distributed with this PhotonCamera project.

Mali-safe finalization is preserved: no r16ui imageStore; final normalization uses a fragment shader into PhotonCamera's R16UI framebuffer/readback path.
