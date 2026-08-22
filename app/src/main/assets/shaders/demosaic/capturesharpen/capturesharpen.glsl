precision highp float;
precision highp sampler2D;

uniform sampler2D OriginalBuffer;
uniform sampler2D BlurredOriginal;
uniform sampler2D EstimateBuffer;
uniform sampler2D CorrectionBuffer;
uniform int iterationIndex;
uniform int iterations;
uniform int debugResponse;
uniform float contrastThreshold;
uniform float minCorrection;
uniform float maxCorrection;
uniform float maxOutput;

out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec3 original = texelFetch(OriginalBuffer, xy, 0).rgb;
    float originalLuma = dot(original, vec3(0.2126, 0.7152, 0.0722));
    float blurredLuma = dot(texelFetch(BlurredOriginal, xy, 0).rgb,
        vec3(0.2126, 0.7152, 0.0722));
    float detail = abs(originalLuma - blurredLuma) * 100.0;
    float mask = smoothstep(contrastThreshold * 0.5,
        contrastThreshold * 1.5, detail);

    if (debugResponse == 1) {
        Output = vec4(vec3(mask), 1.0);
        return;
    }
    vec3 estimate = texelFetch(EstimateBuffer, xy, 0).rgb;
    if (iterationIndex >= iterations) {
        Output = vec4(estimate, 1.0);
        return;
    }

    float correction = clamp(texelFetch(CorrectionBuffer, xy, 0).r,
        minCorrection, maxCorrection);
    Output = vec4(clamp(mix(estimate, estimate * correction, mask),
        0.0, maxOutput), 1.0);
}
