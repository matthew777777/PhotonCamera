
precision highp float;

layout(rgba16f, binding = 0) readonly uniform highp image2D currentTexture;
layout(rgba16f, binding = 1) readonly uniform highp image2D newTexture;
layout(rgba16f, binding = 2) writeonly uniform highp image2D outTexture;

// One pass of the progressive temporal blend used for noise estimation:
// each frame is sampled at its own 3x3 grid slot (one packed texel = one
// 2x2 Bayer quad = 2 raw px, CFA-periodic so channels stay aligned) with its
// normalized Gaussian weight. Scene detail is correlated across frames, so
// the offsets convolve it with the kernel; frame-independent noise only
// drops by the known factor sum(w_i^2), which the CPU fit compensates.
uniform float weight;
uniform ivec2 offset;
// Accumulator content is undefined before the first pass
uniform bool firstPass;

#define LAYOUT //
LAYOUT
void main() {
    ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(outTexture);
    if (coord.x >= size.x || coord.y >= size.y) return;

    ivec2 src = clamp(coord + offset, ivec2(0), size - ivec2(1));
    vec4 current = firstPass ? vec4(0.0) : imageLoad(currentTexture, coord);
    imageStore(outTexture, coord, current + imageLoad(newTexture, src) * weight);
}
