precision highp float;
precision highp sampler2D;

uniform sampler2D InputBuffer;
uniform float strength;
uniform float gamma;

// Selection and Tunables
uniform int toneMapper;
uniform int debugMode;
uniform float agxExposure;
uniform float agxLook;
uniform float agxContrast;
uniform float agxSaturation;

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

// AgX Tone Mapping implementation
// References: Troy Sobotka's AgX, Blender implementation.
// This is a GLSL approximation using polynomial curves.

vec3 AgXLog(vec3 color) {
    // Log-like perceptual encoding
    return clamp(log2(max(color, 0.0001)) / 12.0 + 0.5, 0.0, 1.0);
}

vec3 AgXCurve(vec3 x) {
    // AgX default tone curve approximation
    vec3 x2 = x * x;
    vec3 x3 = x2 * x;
    vec3 x4 = x3 * x;
    vec3 x5 = x4 * x;
    return 15.5 * x5 - 40.14 * x4 + 31.96 * x3 - 6.868 * x2 + 0.4298 * x + 0.01944;
}

vec3 AgXToneMap(vec3 color) {
    // 1. Exposure Scaling
    color *= agxExposure;

    // 2. AgX Working Space Transform (Rec.709 to AgX)
    mat3 m1 = mat3(
        0.842420235, 0.0784335993, 0.079223745,
        0.042321043, 0.878468636, 0.079166127,
        0.042375112, 0.078433054, 0.879264433
    );
    color = m1 * color;

    // 3. Log Encoding
    color = AgXLog(color);

    // 4. Contrast / Look (Simplified)
    color *= agxLook;
    // Applying contrast in log space
    color = pow(max(color, 0.0), vec3(agxContrast));

    // 5. Tone Curve
    color = AgXCurve(color);

    // 6. AgX Output Transform (AgX to Rec.709)
    mat3 m2 = mat3(
        1.196821, -0.098046, -0.099029,
        -0.057769, 1.154214, -0.098961,
        -0.057397, -0.098025, 1.154273
    );
    color = m2 * color;

    // 7. Saturation look adjustment
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color = mix(vec3(luma), color, agxSaturation);

    return clamp(color, 0.0, 1.0);
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec3 linearRGB = texelFetch(InputBuffer, xy, 0).rgb;

    vec3 aces = ACESFilm(linearRGB * strength);
    vec3 agx = AgXToneMap(linearRGB);

    vec3 mapped;

    if (debugMode == 1) {
        mapped = aces;
    } else if (debugMode == 2) {
        mapped = agx;
    } else if (debugMode == 3) {
        mapped = abs(aces - agx) * 10.0; // Boosted difference for visibility
    } else {
        // Normal mode: select based on toneMapper setting
        if (toneMapper == 1) {
            mapped = agx;
        } else {
            mapped = aces;
        }
    }

    // Apply Gamma Correction (Linear to Display sRGB)
    vec3 displayRGB = pow(max(mapped, 0.0), vec3(1.0 / gamma));

    Output = vec4(displayRGB, 1.0);
}
