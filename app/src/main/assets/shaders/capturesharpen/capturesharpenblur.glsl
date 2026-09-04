precision highp float;
precision highp sampler2D;

// RawTherapee's kernels are circular 2D Gaussians, not separable filters.
uniform sampler2D SourceBuffer;
uniform sampler2D OriginalBuffer;
uniform sampler2D BlurredEstimate;
uniform int mode; // 0: blur SourceBuffer luminance; 1: blur O / (G * estimate)
uniform float radius;
uniform float cornerBoost;
uniform float epsilon;

out vec4 Output;

const vec3 LUMA = vec3(0.212671, 0.715160, 0.072169);

ivec2 clampCoord(ivec2 point, ivec2 size) { return clamp(point, ivec2(0), size - ivec2(1)); }

float sourceAt(ivec2 point, ivec2 size) {
    return dot(texelFetch(SourceBuffer, clampCoord(point, size), 0).rgb, LUMA);
}

float ratioAt(ivec2 point, ivec2 size) {
    point = clampCoord(point, size);
    float original = dot(texelFetch(OriginalBuffer, point, 0).rgb, LUMA);
    return original / max(texelFetch(BlurredEstimate, point, 0).r, epsilon);
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    // SourceBuffer is bound for both modes; avoid a conditional sampler
    // expression, which some GLES shader compilers reject.
    ivec2 size = textureSize(SourceBuffer, 0);
    vec2 center = (vec2(xy) + 0.5 - 0.5 * vec2(size)) / (0.5 * length(vec2(size)));
    float sigma = min(2.0, radius + cornerBoost * length(center));
    int halfWidth = sigma < 0.6 ? 1 : sigma <= 0.84 ? 2 : sigma <= 1.15 ? 3 : sigma <= 1.5 ? 4 : 6;
    float support2 = halfWidth == 1 ? 2.0 : halfWidth == 2 ? 6.3504 : halfWidth == 3 ? 11.9025 : halfWidth == 4 ? 20.25 : 36.0;
    float twoSigma2 = 2.0 * max(sigma * sigma, 0.0004);
    float sum = 0.0;
    float weightSum = 0.0;
    for (int y = -6; y <= 6; ++y) {
        for (int x = -6; x <= 6; ++x) {
            int distance2i = x * x + y * y;
            if (abs(x) > halfWidth || abs(y) > halfWidth || float(distance2i) > support2) continue;
            float weight = exp(-float(distance2i) / twoSigma2);
            float value = mode == 0 ? sourceAt(xy + ivec2(x, y), size) : ratioAt(xy + ivec2(x, y), size);
            sum += value * weight;
            weightSum += weight;
        }
    }
    Output = vec4(vec3(sum / weightSum), 1.0);
}
