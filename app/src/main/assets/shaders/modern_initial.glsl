precision highp float;
precision highp sampler2D;

uniform sampler2D InputBuffer;
uniform sampler2D GainMap;

uniform mat3 sensorToIntermediate;
uniform mat3 intermediateToSRGB;
uniform vec3 whitePoint;
uniform float exposureScale;
uniform float contrast;
uniform float saturation;
uniform float vignette;

#define USE_GAINMAP 0
#define NOISES 0.0
#define NOISEO 0.0

out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);

    // 1. Fetch RAW linear input (already black level corrected by upstream node).
    vec3 rawLinear = texelFetch(InputBuffer, xy, 0).rgb;

    // 2. White balance.
    // InputBuffer is still sensor-referred (raw) at this point, so this is the
    // one place the per-channel WB gains get applied - mirrors the
    // `pRGB * neutralPoint` step in the legacy initial.glsl pipeline.
    vec3 wbLinear = rawLinear * whitePoint;

    // 3. Apply Gain Map (Lens Shading Correction) if enabled, in linear space.
    #if USE_GAINMAP == 1
    vec2 inputSize = vec2(textureSize(InputBuffer, 0));
    vec2 texCoord = gl_FragCoord.xy / inputSize;
    vec4 gains = texture(GainMap, texCoord);

    // The map packs 4 Bayer-domain gains (R, Gr, Gb, B). Average the two green
    // slots instead of all four so this actually corrects color shading
    // (e.g. corner tint), not just brightness falloff - matches the convention
    // used by tofloat.glsl and legacy initial.glsl.
    vec3 gain3 = vec3(gains.r, (gains.g + gains.b) * 0.5, gains.a);

    // Re-normalize so the map only reshapes relative shading across the frame,
    // instead of also nudging overall exposure (tofloat.glsl does the same).
    gain3 /= max(dot(gain3, vec3(1.0 / 3.0)), 1e-6);

    // Fade the correction out as signal approaches the sensor noise floor, so
    // it doesn't amplify noise in dark corners - mirrors the noise-gated
    // VIGNETTE term in legacy initial.glsl.
    // 'vignette' tunable scales the overall strength of the correction.
    float noiseFloor = sqrt(max(NOISES + NOISEO, 0.0) + 1e-8);
    float lum = dot(wbLinear, vec3(0.299, 0.587, 0.114));
    float gainConfidence = (lum * lum) / (lum * lum + noiseFloor * noiseFloor);
    wbLinear *= mix(vec3(1.0), gain3, gainConfidence * vignette);
    #endif

    // 4. Scene-referred Color Transformation.
    // Full chain: Sensor -> Intermediate (XYZ/ProPhoto) -> Linear sRGB.
    vec3 sceneRGB = intermediateToSRGB * sensorToIntermediate * wbLinear;

    // 5. Exposure Scaling.
    vec3 exposedRGB = sceneRGB * exposureScale;

    // 6. Contrast adjustment (pivot at 0.18 midtone).
    // Avoid negative values and division by zero.
    vec3 contrastRGB = pow(max(exposedRGB, 1e-6) / 0.18, vec3(contrast)) * 0.18;

    // 7. Saturation adjustment.
    float luma = dot(contrastRGB, vec3(0.299, 0.587, 0.114));
    vec3 finalRGB = mix(vec3(luma), contrastRGB, saturation);

    // Output scene-referred HDR RGB. No upper clamp - highlights above 1.0
    // are legitimate HDR data for the tone mapper further down the chain.
    Output = vec4(max(finalRGB, 0.0), 1.0);
}
