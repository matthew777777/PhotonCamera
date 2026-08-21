precision highp float;
precision highp sampler2D;

// ExperimentalCaptureSharpening - pass 2/4: contrast-threshold mask.
//
// Reimplements RT's "Sharpening Contrast Mask" (RawPedia, Capture Sharpening:
// "Sharpening will occur in the white areas but not in the black areas").
// Computed ONCE from the untouched original + its blur, then reused
// unchanged for every RL iteration below - so the mask can't drift as the
// estimate gets progressively sharper. This is also the debug/preview view:
// feed this pass's output straight to screen instead of running the RL loop
// to show the mask (RT calls that toggle "Showcap").
//
// RT's internal contrast-scoring formula isn't published, so the scoring
// below (local high-frequency energy vs. a soft threshold) is an original
// equivalent tuned so contrastThreshold's 0-100ish range feels like RT's
// Contrast Threshold slider - treat it as a behavioural match, not a
// byte-exact port of RT's internal C++ math.
//
// See capturesharpenblur.glsl for the license/attribution note that applies
// to this whole pass group.

uniform sampler2D OriginalBuffer;
uniform sampler2D BlurredOriginal;   // capturesharpenblur.glsl run on OriginalBuffer (H then V)
uniform float contrastThreshold;     // RT "Contrast Threshold". Default 10.0

out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);

    vec3 orig = texelFetch(OriginalBuffer, xy, 0).rgb;
    vec3 blur = texelFetch(BlurredOriginal, xy, 0).rgb;

    float lumaOrig = dot(orig, vec3(0.2126, 0.7152, 0.0722));
    float lumaBlur = dot(blur, vec3(0.2126, 0.7152, 0.0722));
    float detail = abs(lumaOrig - lumaBlur) * 100.0;

    // Soft-edged threshold (smoothstep, not a hard cut) to avoid banding at
    // the boundary between "sharpened" and "left alone" regions.
    float mask = smoothstep(contrastThreshold * 0.5, contrastThreshold * 1.5, detail);

    Output = vec4(vec3(mask), 1.0);
}
