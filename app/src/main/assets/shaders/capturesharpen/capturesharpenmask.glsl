#version 310 es

precision highp float;
precision highp int;
precision highp usampler2D;
precision highp sampler2D;
precision highp image2D;

// Direct GPU port of RawTherapee buildClipMask* + buildBlendMask before its
// final sigma=2 Young-van Vliet Gaussian smoothing pass.
layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

uniform sampler2D OriginalBuffer;
uniform usampler2D RawBuffer;
uniform ivec2 size;
uniform vec4 rawClip;
uniform float contrastThreshold;

#ifndef RGB_RAW
#define RGB_RAW 0
#endif
#ifndef MONO_RAW
#define MONO_RAW 0
#endif

layout(binding = 0, r32f) writeonly uniform highp image2D OutputBuffer;

const vec3 XYZ_Y = vec3(0.212671, 0.715160, 0.072169);
const float LAB_EPSILON = 216.0 / 24389.0;
const float LAB_KAPPA = 24389.0 / 27.0;

float rtLabL(ivec2 point) {
    float y = dot(texelFetch(OriginalBuffer, point, 0).rgb, XYZ_Y);
    return y > LAB_EPSILON ? 116.0 * pow(y, 1.0 / 3.0) - 16.0
        : LAB_KAPPA * y;
}

bool rawSampleClipped(ivec2 point) {
    // RT only tests raw clip candidates whose complete 5x5 footprint fits.
    if (any(lessThan(point, ivec2(2)))
            || any(greaterThanEqual(point, size - ivec2(2)))) return false;
    uvec4 raw = texelFetch(RawBuffer, point, 0);
#if RGB_RAW
    return any(greaterThanEqual(vec3(raw.rgb), rawClip.rgb));
#elif MONO_RAW
    return float(raw.r) >= rawClip.x;
#else
    int patternIndex = (point.y & 1) * 2 + (point.x & 1);
    return float(raw.r) >= rawClip[patternIndex];
#endif
}

float rtClipMask(ivec2 point) {
    for (int y = -2; y <= 2; ++y) {
        for (int x = -2; x <= 2; ++x) {
            // RT's footprint is 3,5,5,5,3 pixels wide.
            if (abs(y) == 2 && abs(x) == 2) continue;
            if (rawSampleClipped(point + ivec2(x, y))) return 0.0;
        }
    }
    return 1.0;
}

void main() {
    ivec2 point = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(point, size))) return;

    // RT treats threshold zero as an all-one mask (including clipped areas)
    // and deliberately skips the Gaussian smoothing pass.
    if (contrastThreshold <= 0.0) {
        imageStore(OutputBuffer, point, vec4(1.0));
        return;
    }

    // buildBlendMask copies its two-pixel interior edge into the image border.
    ivec2 p = clamp(point, ivec2(2), size - ivec2(3));
    float h1 = rtLabL(p + ivec2(1, 0)) - rtLabL(p - ivec2(1, 0));
    float v1 = rtLabL(p + ivec2(0, 1)) - rtLabL(p - ivec2(0, 1));
    float h2 = rtLabL(p + ivec2(2, 0)) - rtLabL(p - ivec2(2, 0));
    float v2 = rtLabL(p + ivec2(0, 2)) - rtLabL(p - ivec2(0, 2));

    // RT stores L as 327.68*L*, then multiplies contrast by .0625/327.68.
    float contrast = length(vec4(h1, v1, h2, v2)) * 0.0625;
    float x = -16.0 + (16.0 / contrastThreshold) * contrast;
    float blend = rtClipMask(p) * 0.5 * (1.0 + x / sqrt(1.0 + x * x));
    imageStore(OutputBuffer, point, vec4(blend));
}
