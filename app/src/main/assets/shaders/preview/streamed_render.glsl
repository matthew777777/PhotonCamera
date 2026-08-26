precision highp float;
precision highp sampler2D;

uniform sampler2D ToneCurve;
uniform float saturation;
uniform float contrast;
uniform float shadows;
out vec4 Output;
#import streamed_color

void main() {
    ivec2 position = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InputBuffer, 0);
    vec3 linear = max(streamedLinearColor(position, size), vec3(0.0));
    float sourceLuma = streamedLuminance(linear);
    float mappedLuma = texture(ToneCurve,
            vec2(streamedLogCoordinate(sourceLuma), 0.5)).r;
    float shadowMix = clamp(abs(shadows) * 0.5, 0.0, 1.0);
    float shadowMapped = shadows >= 0.0 ? sqrt(mappedLuma)
            : mappedLuma * mappedLuma;
    mappedLuma = mix(mappedLuma, shadowMapped, shadowMix);
    linear *= sourceLuma > 1e-6 ? mappedLuma / sourceLuma : 0.0;

    vec3 encoded = pow(clamp(linear, 0.0, 1.0), vec3(1.0 / 2.2));
    float luma = streamedLuminance(encoded);
    encoded = mix(vec3(luma), encoded, clamp(saturation, 0.0, 3.0));
    vec3 contrastCurve = 0.5 + 0.5
            * sin((2.0 * encoded - 1.0) * 1.57079632679);
    encoded = mix(encoded, contrastCurve, clamp(contrast, 0.0, 1.0));
    Output = vec4(clamp(encoded, 0.0, 1.0), 1.0);
}
