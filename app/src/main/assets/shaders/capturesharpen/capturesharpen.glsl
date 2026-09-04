precision highp float;
precision highp sampler2D;

uniform sampler2D OriginalBuffer;
uniform sampler2D EstimateBuffer;
uniform sampler2D CorrectionBuffer;
uniform int operation;
uniform float contrastThreshold;

out vec4 Output;

ivec2 clampCoord(ivec2 point, ivec2 size) {
    return clamp(point, ivec2(0), size - ivec2(1));
}

const vec3 LUMA = vec3(0.212671, 0.715160, 0.072169);
const float RT_WORKING_RANGE = 16384.0;
const float RT_CONTRAST_SCALE = 0.0625 / 327.68;

float clipMaskAt(ivec2 point, ivec2 size) {
    for (int y = -2; y <= 2; ++y) {
        for (int x = -2; x <= 2; ++x) {
            if (abs(x) + abs(y) > 3) continue;
            vec3 smp = texelFetch(OriginalBuffer,
                clampCoord(point + ivec2(x, y), size), 0).rgb;
            if (max(smp.r, max(smp.g, smp.b)) >= 0.95) return 0.0;
        }
    }
    return 1.0;
}

float luminanceAt(ivec2 point, ivec2 size) {
    return dot(texelFetch(OriginalBuffer, clampCoord(point, size), 0).rgb, LUMA);
}

float blendAt(ivec2 point, ivec2 size) {
    float h1 = luminanceAt(point + ivec2(1, 0), size) - luminanceAt(point - ivec2(1, 0), size);
    float v1 = luminanceAt(point + ivec2(0, 1), size) - luminanceAt(point - ivec2(0, 1), size);
    float h2 = luminanceAt(point + ivec2(2, 0), size) - luminanceAt(point - ivec2(2, 0), size);
    float v2 = luminanceAt(point + ivec2(0, 2), size) - luminanceAt(point - ivec2(0, 2), size);
    if (contrastThreshold <= 0.0) return clipMaskAt(point, size);
    float contrast = length(vec4(h1, v1, h2, v2)) * RT_WORKING_RANGE * RT_CONTRAST_SCALE;
    float x = -16.0 + 16.0 * contrast / contrastThreshold;
    return clipMaskAt(point, size) * 0.5 * (1.0 + x / sqrt(1.0 + x * x));
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(OriginalBuffer, 0);
    if (operation == 0) {
        Output = vec4(vec3(max(texelFetch(EstimateBuffer, xy, 0).r * texelFetch(CorrectionBuffer, xy, 0).r, 0.0)), 1.0);
        return;
    }
    float blend = blendAt(xy, size);
    if (operation == 2) {
        Output = vec4(vec3(blend), 1.0);
        return;
    }
    vec3 original = texelFetch(OriginalBuffer, xy, 0).rgb;
    float oldLuminance = max(dot(original, LUMA), 0.00001);
    float newLuminance = mix(oldLuminance, texelFetch(EstimateBuffer, xy, 0).r, blend);
    Output = vec4(max(original * (newLuminance / oldLuminance), 0.0), 1.0);
}
