precision highp float;
precision highp sampler2D;

uniform sampler2D SourceBuffer;
uniform sampler2D OriginalBuffer;
uniform sampler2D BlurredEstimate;
uniform sampler2D BlurredOriginal;
uniform vec2 direction;
uniform int mode;
uniform float radius;
uniform float cornerBoost;
uniform float maxSigma;
uniform float contrastThreshold;
uniform float epsilon;

out vec4 Output;

ivec2 clampCoord(ivec2 point, ivec2 size) {
    return clamp(point, ivec2(0), size - ivec2(1));
}

float maskAt(ivec2 point) {
    ivec2 size = textureSize(OriginalBuffer, 0);
    point = clampCoord(point, size);
    float original = dot(texelFetch(OriginalBuffer, point, 0).rgb, vec3(0.2126, 0.7152, 0.0722));
    float blurred = dot(texelFetch(BlurredOriginal, point, 0).rgb, vec3(0.2126, 0.7152, 0.0722));
    float detail = abs(original - blurred) * 100.0;
    return smoothstep(contrastThreshold * 0.5, contrastThreshold * 1.5, detail);
}

float ratioAt(ivec2 point) {
    ivec2 size = textureSize(OriginalBuffer, 0);
    point = clampCoord(point, size);
    float original = dot(texelFetch(OriginalBuffer, point, 0).rgb, vec3(0.2126, 0.7152, 0.0722));
    float estimate = dot(texelFetch(BlurredEstimate, point, 0).rgb, vec3(0.2126, 0.7152, 0.0722));
    return mix(1.0, original / max(estimate, epsilon), maskAt(point));
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(SourceBuffer, 0);
    vec2 resolution = vec2(size);
    vec2 centered = (gl_FragCoord.xy - 0.5 * resolution) / (0.5 * length(resolution));
    float cornerDistance = clamp(length(centered), 0.0, 1.0);
    float sigma = clamp(radius + cornerBoost * cornerDistance, 0.02, maxSigma);
    float twoSigma2 = 2.0 * sigma * sigma;
    vec3 sum = vec3(0.0);
    float weightSum = 0.0;
    for (int offset = -6; offset <= 6; ++offset) {
        float weight = exp(-float(offset * offset) / twoSigma2);
        ivec2 point = clampCoord(xy + ivec2(direction) * offset, size);
        vec3 value = mode == 1 ? vec3(ratioAt(point)) : texelFetch(SourceBuffer, point, 0).rgb;
        sum += value * weight;
        weightSum += weight;
    }
    Output = vec4(sum / weightSum, 1.0);
}
