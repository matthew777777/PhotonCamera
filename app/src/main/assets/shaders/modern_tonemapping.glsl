precision highp float;
precision highp sampler2D;

uniform sampler2D InputBuffer;
uniform int tonemapMethod; // 0: ACES, 1: OpenDRT
uniform float strength;
uniform float gamma;

uniform float acesA;
uniform float acesB;
uniform float acesC;
uniform float acesD;
uniform float acesE;

uniform float odtContrast;
uniform float odtSx;
uniform float odtToe;
uniform float odtPurity;

out vec4 Output;

// Narkowicz ACES filmic tone mapping approximation
float ACESFilm(float x) {
    float protected = x / (1.0 + 0.1 * x);
    return clamp((protected * (acesA * protected + acesB)) /
        (protected * (acesC * protected + acesD) + acesE), 0.0, 1.0);
}

vec3 ACESFilm(vec3 color) {
    float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
    if (luminance <= 0.0) return vec3(0.0);
    float mappedLuminance = ACESFilm(luminance);
    float highlight = smoothstep(0.35, 1.0, mappedLuminance);
    vec3 desaturated = mix(color, vec3(luminance), highlight * 0.35);
    return clamp(desaturated * (mappedLuminance / max(luminance, 1e-6)), 0.0, 1.0);
}

// OpenDRT (Open Display Transform) - Michaelis-Menten Tonescale
// Based on Jed Smith's picture formation research.

float opendrt_tonescale(float x) {
    // 1. Toe Compression (Flare compensation)
    // g(x) = x^2 / (x + t0)
    float g = (x * x) / (x + odtToe + 1e-6);

    // 2. Michaelis-Menten Sigmoid
    // f(x) = (g / (g + sx))^p
    return pow(g / (g + odtSx), odtContrast);
}

// Purity compression (highlight dechroma)
vec3 purity_compression(vec3 color, float purity) {
    float max_c = max(color.r, max(color.g, color.b));
    float min_c = min(color.r, min(color.g, color.b));

    // Saturation estimate
    float saturation = (max_c - min_c) / max(max_c, 1e-5);

    // Compress saturation in highlights
    float compression = 1.0 - purity * smoothstep(0.4, 0.9, max_c);

    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    return mix(vec3(luma), color, compression);
}

vec3 OpenDRT(vec3 color) {
    float norm = dot(color, vec3(0.2126, 0.7152, 0.0722));

    if (norm <= 0.0) return vec3(0.0);

    float mapped_norm = opendrt_tonescale(norm);
    vec3 mapped_color = color * (mapped_norm / max(norm, 1e-6));
    float highlight = smoothstep(0.35, 0.95, mapped_norm);
    mapped_color = purity_compression(mapped_color, odtPurity * highlight);

    return clamp(mapped_color, 0.0, 1.0);
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec3 linearRGB = texelFetch(InputBuffer, xy, 0).rgb;
    linearRGB = max(linearRGB, 0.0) * strength;

    vec3 mapped;
    if (tonemapMethod == 1) {
        mapped = OpenDRT(linearRGB);
    } else {
        mapped = ACESFilm(linearRGB);
    }

    // 2. Apply Gamma Correction (Linear to Display).
    // mapped is already clamped to [0,1] by ACESFilm, so this stays in range.
    vec3 displayRGB = pow(mapped, vec3(1.0 / max(gamma, 1e-4)));

    Output = vec4(displayRGB, 1.0);
}
