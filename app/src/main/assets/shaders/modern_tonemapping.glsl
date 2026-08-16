precision highp float;
precision highp sampler2D;

uniform sampler2D InputBuffer;
uniform float strength;
uniform float gamma;

out vec4 Output;

#import coords

// Standard ACES Filmic Tone Mapping approximation
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

    // 1. Apply Tone Mapping
    // ACES expects linear input, usually scaled so that 1.0 is white.
    // strength allows fine-tuning the look.
    vec3 mapped = ACESFilm(linearRGB * strength);

    // 2. Apply Gamma Correction (Linear to Display sRGB)
    // pow(x, 1/2.2) is a standard approximation for sRGB gamma.
    vec3 displayRGB = pow(max(mapped, 0.0), vec3(1.0 / gamma));

    Output = vec4(displayRGB, 1.0);
}
