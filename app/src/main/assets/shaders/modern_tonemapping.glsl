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
uniform float odtShoulder;
uniform float odtPurity;

out vec4 Output;

// Narkowicz ACES filmic tone mapping approximation
vec3 ACESFilm(vec3 x) {
    return clamp((x * (acesA * x + acesB)) / (x * (acesC * x + acesD) + acesE), 0.0, 1.0);
}

// OpenDRT Core Tonescale & Chromaticity Preservation
// Based on Jed Smith's Open Display Transform (OpenDRT)

float opendrt_tonescale(float x, float contrast, float shoulder) {
    // 1. Apply Contrast (Power function)
    float x_c = pow(max(0.0, x), contrast);

    // 2. Hyperbolic compression function for the highlight "shoulder"
    // f(x) = x / (x + s)
    return x_c / (x_c + shoulder);
}

// Purity compression (highlight dechroma)
// Helps prevent harsh clipping of highly saturated colors in highlights.
vec3 purity_compression(vec3 color, float purity) {
    float max_c = max(color.r, max(color.g, color.b));
    float min_c = min(color.r, min(color.g, color.b));
    float saturation = (max_c - min_c) / max(max_c, 1e-5);

    // As max_c approaches display white, we compress the saturation.
    float compression = 1.0 - purity * smoothstep(0.5, 1.0, max_c);

    // Blend towards the luminance to desaturate
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    return mix(vec3(luma), color, compression);
}

vec3 OpenDRT(vec3 color) {
    // 1. Calculate the norm (Max RGB for neutral/chromaticity preserving)
    float norm = max(color.r, max(color.g, color.b));

    if (norm <= 0.0) return vec3(0.0);

    // 2. Apply the tonescale to the norm
    float mapped_norm = opendrt_tonescale(norm, odtContrast, odtShoulder);

    // 3. Apply the compression ratio to original RGB to preserve chromaticity
    vec3 mapped_color = color * (mapped_norm / norm);

    // 4. Apply Purity Compression for highlight handling
    mapped_color = purity_compression(mapped_color, odtPurity);

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
