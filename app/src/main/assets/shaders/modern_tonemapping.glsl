precision highp float;
precision highp sampler2D;

uniform sampler2D InputBuffer;
uniform float strength;
uniform float gamma;

out vec4 Output;

// Narkowicz ACES filmic tone mapping approximation - same fit already used by
// the (currently uncalled) aces() helper in initial.glsl.
vec3 ACESFilm(vec3 x) {
    float a = 2.51;
    float b = 0.03;
    float c = 2.43;
    float d = 0.59;
    float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec3 linearRGB = texelFetch(InputBuffer, xy, 0).rgb;

    // 1. Apply Tone Mapping.
    // `strength` pre-scales the input going into the filmic curve (effectively
    // an extra trim on top of ModernAutoExposure's metering) rather than
    // blending the output, which is why its useful range extends past 1.0.
    vec3 mapped = ACESFilm(max(linearRGB, 0.0) * strength);

    // 2. Apply Gamma Correction (Linear to Display).
    // mapped is already clamped to [0,1] by ACESFilm, so this stays in range.
    vec3 displayRGB = pow(mapped, vec3(1.0 / max(gamma, 1e-4)));

    Output = vec4(displayRGB, 1.0);
}
