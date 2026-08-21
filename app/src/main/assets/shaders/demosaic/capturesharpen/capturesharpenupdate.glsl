precision highp float;
precision highp sampler2D;

// ExperimentalCaptureSharpening - pass 4/4: Richardson-Lucy update step.
//
// estimate_{k+1} = estimate_k * blur(ratio_k). This is the multiplicative
// RL update - see capturesharpenratio.glsl for the ratio half of the
// iteration, and capturesharpenblur.glsl for the license/attribution note
// that applies to this whole pass group.
//
// The correction factor is clamped every iteration, not just at the very
// end: RawPedia's Capture Sharpening page calls out that a wrongly-set
// camera white level can make the ratio spike at clipped/non-clipped
// highlight boundaries, which is exactly where an unclamped multiplicative
// update blows up fastest.
//
// debugResponse doubles this node's single visible output as RT's "Showcap"
// mask preview, so the orchestrating node doesn't need a second exit point.

uniform sampler2D EstimateBuffer;
uniform sampler2D CorrectionBuffer;  // capturesharpenblur.glsl run on the ratio map (H then V)
uniform sampler2D MaskBuffer;        // only sampled when debugResponse == 1
uniform int debugResponse;           // 1 = output the sharpening mask (RT "Showcap") instead
uniform float minCorrection;         // per-iteration clamp floor. Default 0.25
uniform float maxCorrection;         // per-iteration clamp ceiling. Default 4.0
uniform float maxOutput;             // output ceiling. Default 1.0 - raise this if
                                      // InputBuffer isn't white-level-normalized to
                                      // [0,1] yet at this pipeline stage

out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);

    if (debugResponse == 1) {
        float mask = texelFetch(MaskBuffer, xy, 0).r;
        Output = vec4(vec3(mask), 1.0);
        return;
    }

    vec3 estimate = texelFetch(EstimateBuffer, xy, 0).rgb;
    vec3 correction = texelFetch(CorrectionBuffer, xy, 0).rgb;
    correction = clamp(correction, vec3(minCorrection), vec3(maxCorrection));

    Output = vec4(clamp(estimate * correction, 0.0, maxOutput), 1.0);
}
