precision highp float;
precision highp sampler2D;

// ExperimentalCaptureSharpening - pass 1/4: separable Gaussian PSF blur.
//
// Reimplements the blur step of RawTherapee's Capture Sharpening tool
// (rtengine/capturesharpening.cc, GPL-3.0-or-later, author Ingo Weyrich
// "heckflosse" - see https://rawpedia.rawtherapee.com/Capture_Sharpening).
// PhotonCamera is GPL-3.0 too, so this is a same-license, from-scratch GLSL
// reimplementation of the published algorithm/parameters, not a line-for-line
// port of the C++ (which wouldn't compile as a shader anyway). Keep this
// credit comment with the file.
//
// One shader, three jobs (called by the orchestrating node, see the .java
// sketch):
//   1. blur the ORIGINAL once  -> feeds capturesharpenmask.glsl
//   2. blur the ESTIMATE       -> once per RL iteration
//   3. blur the RATIO map      -> once per RL iteration (this is the RL
//      "correlation" half-step; valid to reuse the same forward-blur kernel
//      here because a Gaussian PSF is symmetric, i.e. its own mirror image)
//
// Call twice per blur: direction = (1,0) then (0,1). sigma is recomputed
// per-fragment (not baked into a CPU-side kernel array) so the corner-radius
// boost can vary smoothly across the frame, mirroring RT's per-pixel radius.

uniform sampler2D SourceBuffer;
uniform vec2 direction;      // (1,0) horizontal pass, (0,1) vertical pass
uniform float radius;        // RT "Radius" == the PSF sigma, in px. Default 0.75
uniform float cornerBoost;   // RT "Corner radius boost". Default 0.0 (neutral)
uniform float maxSigma;      // ceiling for sigma. Default 1.15 - RT's own dev
                              // build capped sigma here for a 7-tap kernel
                              // (heckflosse, pixls.us "New tool Capture
                              // Sharpening" thread, 2019); kept as the ceiling
                              // for this 7-tap GLSL kernel too.

out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec2 res = vec2(textureSize(SourceBuffer, 0));

    // Distance from image center, 0 at the center to 1 at the corners,
    // independent of aspect ratio.
    vec2 centered = (gl_FragCoord.xy - 0.5 * res) / (0.5 * length(res));
    float cornerDist2 = clamp(dot(centered, centered), 0.0, 1.0);

    float sigma = clamp(radius * (1.0 + cornerBoost * cornerDist2), 0.02, maxSigma);
    float twoSigma2 = 2.0 * sigma * sigma;

    vec4 sum = vec4(0.0);
    float wSum = 0.0;
    for (int i = -3; i <= 3; i++) {
        float w = exp(-float(i * i) / twoSigma2);
        sum += texelFetch(SourceBuffer, xy + ivec2(direction) * i, 0) * w;
        wSum += w;
    }
    Output = sum / wSum;
}
