precision highp float;
precision highp sampler2D;

uniform sampler2D InputBuffer;
uniform float saturation;
uniform float contrast;
uniform float shadows;
out vec4 Output;

float luminance(vec3 rgb) {
    return dot(rgb, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec2 texCoord = gl_FragCoord.xy / vec2(textureSize(InputBuffer, 0));
    // Input is already white-balanced, matrix-corrected, tone-mapped by the
    // histogram GTM curve and gamma-encoded by the StreamedColor compute
    // pass; only tone controls happen here, matching the point at which
    // Initial applies saturation and contrast. Highlight compression lives in
    // the native GTM curve (it receives the compressor setting).
    vec3 rgb = clamp(texture(InputBuffer, texCoord).rgb, 0.0, 1.0);
    float luma = luminance(rgb);
    float shadowMix = min(abs(shadows) * 0.5, 1.0);
    if (shadowMix > 0.0 && luma > 1e-6) {
        float mapped = shadows >= 0.0 ? sqrt(luma) : luma * luma;
        rgb *= mix(luma, mapped, shadowMix) / luma;
    }
    luma = luminance(rgb);
    // PhotonCamera saturation is a direct factor: 1.0 is identity.
    rgb = mix(vec3(luma), rgb, clamp(saturation, 0.0, 3.0));
    // Match Initial.contrastSin(): contrast 0.0 is identity.
    vec3 contrastCurve = 0.5 + 0.5 * sin((2.0 * rgb - 1.0) * 1.57079632679);
    float contrastWeight = clamp(mix(contrast + shadows, contrast,
            clamp(luminance(rgb), 0.0, 1.0)), 0.0, 1.0);
    rgb = mix(rgb, contrastCurve, contrastWeight);
    Output = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
