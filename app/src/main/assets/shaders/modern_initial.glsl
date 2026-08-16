precision highp float;
precision highp sampler2D;

uniform sampler2D InputBuffer;
uniform sampler2D GainMap;

uniform mat3 sensorToIntermediate;
uniform mat3 intermediateToSRGB;
uniform vec3 whitePoint;
uniform float exposureScale;

#define USE_GAINMAP 0

out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);

    // 1. Fetch RAW linear input (already black level corrected by upstream node).
    vec3 rawLinear = texelFetch(InputBuffer, xy, 0).rgb;

    // 2. White Balance / Re-alignment
    // Multiplying by whitePoint here is used to align the neutralized data
    // from Bayer2Float back into the sensor-referred space for the camera matrix.
    vec3 wbLinear = rawLinear * whitePoint;

    // 3. Apply Gain Map (Lens Shading Correction) if enabled.
    #if USE_GAINMAP == 1
    vec2 inputSize = vec2(textureSize(InputBuffer, 0));
    vec2 texCoord = gl_FragCoord.xy / inputSize;
    vec4 gains = texture(GainMap, texCoord);
    float gain = (gains.r + gains.g + gains.b + gains.a) * 0.25;
    wbLinear *= gain;
    #endif

    // 4. Scene-referred Color Transformation.
    // Full chain: Sensor -> Intermediate (XYZ/ProPhoto) -> Linear sRGB.
    vec3 sceneRGB = intermediateToSRGB * sensorToIntermediate * wbLinear;

    // 5. Exposure Scaling.
    vec3 exposedRGB = sceneRGB * exposureScale;

    // Output scene-referred HDR RGB.
    // We add a tiny epsilon to prevent absolute zero if needed, but 0.0 is technically valid HDR.
    Output = vec4(max(exposedRGB, 0.0), 1.0);
}
