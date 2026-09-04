precision highp float;
precision highp sampler2D;
uniform sampler2D upsampled;
uniform bool useUpsampled;
uniform float blendMpy;
// Weighting is done using these.
uniform sampler2D normalExpo;

// Blending is done using these.
uniform sampler2D normalExpoDiff;

uniform int level;
uniform vec2 upscaleIn;
uniform float gauss;
uniform float target;
//#define TARGET 0.0
//#define GAUSS 0.5
#define MAXLEVEL 4
#define NORM 1.0
#define EPS 1e-6
#define LAPLACEMIN 0.01
#define EXPOMIN 0.01
// Overwritten via glProg.setDefine("FUSEWEIGHTED",true): packing per
// exposebayer2 — r/g/b are exposures anchored at luma 1.0 (highlights, base),
// 0.5 (midtones) and 0.0 (shadows, max boost); a carries the blend factor t,
// which this shader reconstructs per level (upsampled base + t-detail) and
// turns into linear tent shares on nodes 0.0/0.5/1.0.
#define FUSEWEIGHTED 0
out vec4 result;
#import gaussian
#import interpolation

vec4 laplace(sampler2D tex, vec4 mid, ivec2 xyCenter) {
        vec4 outp = mid*9.0;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                outp -= texelFetch(tex, xyCenter + ivec2(i, j), 0);
            }
        }
        return abs(outp);
}

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);
    // If this is the lowest layer, start with zero.
    vec2 uvUp = (vec2(gl_FragCoord.xy))*(vec2(upscaleIn));
    float base = (useUpsampled) ? texture(upsampled, uvUp).r : float(0.0);
    float baseT = (useUpsampled) ? texture(upsampled, uvUp).a : float(0.0);

    #if FUSEWEIGHTED == 1
    // t is clamped only to evaluate the shares; the unclamped value is
    // passed on in .a so the Laplacian reconstruction of t stays exact.
    #else
    // To know that, look at multiple factors.
    vec4 expoVal = texelFetch(normalExpo, xyCenter, 0)*NORM;
    vec4 weights = vec4(1.0, 1.0, 1.0, 1.0);
    // Factor 1: Well-exposedness.
    vec4 normToAvg = (pdf4((expoVal - vec4(target))/gauss));

    weights *= normToAvg + EXPOMIN;

    // Factor 2: Contrast.
    vec4 laplaceVal = laplace(normalExpo, expoVal/NORM, xyCenter)*NORM;

    weights *= laplaceVal + LAPLACEMIN;

    weights *= weights;
    #endif
    // How are we going to blend these two?
    vec4 expoDiff = texelFetch(normalExpoDiff, xyCenter, 0);
    #if FUSEWEIGHTED == 1
    float tRaw = baseT + expoDiff.a;
    float t = clamp(tRaw, 0.0, 1.0);
    float s1 = clamp(t*2.0, 0.0, 1.0);        // seg(t, 0.0, 0.5)
    float s2 = clamp(t*2.0 - 1.0, 0.0, 1.0);  // seg(t, 0.5, 1.0)
    // Shares form an exact partition of unity: r = highlights (s2),
    // b = midtones (s1-s2), g = shadows (1-s1).
    result = vec4(base + expoDiff.r*s2 + expoDiff.b*(s1 - s2) + expoDiff.g*(1.0 - s1), 0.0, 0.0, tRaw);
    #else
    result = vec4(base + (expoDiff.r*weights.r + expoDiff.g*weights.g + expoDiff.b*weights.b + expoDiff.a*weights.a)/(weights.r + weights.g + weights.b + weights.a));
    #endif
    // No clamp: Laplacian reconstruction must be allowed to overshoot [0,1].
    // Clamping per level truncates signed detail energy and the clipped value
    // becomes the base for the next finer level, compounding ringing.
    //if(level == 0){
    //    result = result*result;
    //}
}
