precision highp float;
precision highp sampler2D;

// ExperimentalCaptureSharpening - pass 3/4: Richardson-Lucy ratio step.
//
// One RL iteration is: blur the estimate, take original/blurred, blur that
// ratio back (capturesharpenblur.glsl again), then multiply it into the
// estimate (capturesharpenupdate.glsl). This pass is the "take the ratio"
// half, gated by the fixed mask from capturesharpenmask.glsl so flat/noisy
// regions fall back to ratio = 1 (no correction) instead of amplifying
// noise. See capturesharpenblur.glsl for the license/attribution note that
// applies to this whole pass group.

uniform sampler2D OriginalBuffer;
uniform sampler2D BlurredEstimate;  // capturesharpenblur.glsl run on the current estimate (H then V)
uniform sampler2D MaskBuffer;       // output of capturesharpenmask.glsl
uniform float epsilon;              // divide-by-zero guard near black. Default 1e-4

out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);

    vec3 orig = texelFetch(OriginalBuffer, xy, 0).rgb;
    vec3 blurred = texelFetch(BlurredEstimate, xy, 0).rgb;
    float mask = texelFetch(MaskBuffer, xy, 0).r;

    vec3 rawRatio = orig / max(blurred, vec3(epsilon));
    Output = vec4(mix(vec3(1.0), rawRatio, mask), 1.0);
}
