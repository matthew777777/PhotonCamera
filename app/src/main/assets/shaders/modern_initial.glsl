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

    // Removed per-pixel re-normalization! Dividing by the local average gain
    // was effectively stripping the brightness correction (vignetting) from
    // the map and only keeping the color shading. By removing this, we
    // restore full vignetting correction.

    // Fade the correction out as signal approaches the sensor noise floor, so
    // it doesn't amplify noise in dark corners. We use a more relaxed
    // confidence curve to ensure corners are brightened even in deeper shadows.
    float noiseFloor = sqrt(max(NOISES + NOISEO, 0.0) + 1e-8);
    float lum = dot(wbLinear, vec3(0.299, 0.587, 0.114));
    // Squaring the denominator less aggressively to keep correction active.
    float gainConfidence = (lum * lum) / (lum * lum + noiseFloor * noiseFloor * 0.25);
    wbLinear *= mix(vec3(1.0), gain3, gainConfidence * vignette);
    #endif

    // 4. Scene-referred Color Transformation.
    // Full chain: Sensor -> Intermediate (XYZ/ProPhoto) -> Linear sRGB.
    vec3 sceneRGB = intermediateToSRGB * sensorToIntermediate * wbLinear;

    // 5. Exposure Scaling.
    vec3 exposedRGB = sceneRGB * exposureScale;

    // 6. Contrast adjustment (pivot at 0.18 midtone).
    // Avoid negative values and division by zero.
    float exposedLuma = dot(exposedRGB, vec3(0.2126, 0.7152, 0.0722));
    float contrastLuma = 0.18 * pow(max(exposedLuma, 1e-6) / 0.18, contrast);
    vec3 contrastRGB = exposedLuma > 1e-6
        ? exposedRGB * (contrastLuma / exposedLuma)
        : vec3(0.0);

    // 7. Saturation adjustment.
    float luma = dot(contrastRGB, vec3(0.2126, 0.7152, 0.0722));
    vec3 finalRGB = mix(vec3(luma), contrastRGB, max(saturation, 0.0));

    // Output scene-referred HDR RGB. No upper clamp - highlights above 1.0
    // are legitimate HDR data for the tone mapper further down the chain.
    Output = vec4(max(finalRGB, 0.0), 1.0);
}
