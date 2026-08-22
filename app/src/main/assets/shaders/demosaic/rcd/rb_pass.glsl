#define LAYOUT //
LAYOUT
precision highp float;

layout(rgba16f, binding = 0) uniform highp readonly image2D inTexture;
layout(rgba16f, binding = 1) uniform highp readonly image2D greenTexture;
layout(rgba16f, binding = 2) uniform highp writeonly image2D outTexture;

#ifndef EPS
#define EPS 0.0001
#endif

#ifndef ARTIFACT_CORRECTION
#define ARTIFACT_CORRECTION 0
#endif

#ifndef RATIO_SMOOTHING
#define RATIO_SMOOTHING 0
#endif

uniform ivec2 imgSize;
uniform float ratioRobustness;
uniform float ratioEdgeProtection;

float bayer(ivec2 pos) {
    ivec2 size = imageSize(inTexture);
    return imageLoad(inTexture, clamp(pos, ivec2(0), size - 1)).r;
}

float green(ivec2 pos) {
    ivec2 size = imageSize(greenTexture);
    return imageLoad(greenTexture, clamp(pos, ivec2(0), size - 1)).r;
}

// Same P/Q diagonal discrimination as full_pass.glsl - see that file for the
// full explanation of why. Duplicated rather than shared because this
// pipeline's shader loader has no #include; if you tune one, tune the other.
float pqRatio(ivec2 p) {
    float c = bayer(p);
    float pE = abs(bayer(p + ivec2(1, 1)) - bayer(p + ivec2(-1, -1))) +
               abs(2.0 * c - bayer(p + ivec2(2, 2)) - bayer(p + ivec2(-2, -2)));
    float qE = abs(bayer(p + ivec2(1, -1)) - bayer(p + ivec2(-1, 1))) +
               abs(2.0 * c - bayer(p + ivec2(2, -2)) - bayer(p + ivec2(-2, 2)));
    return pE / max(pE + qE, 1e-8);
}

// ---------------------------------------------------------------------------
// Pipeline stage 1.5: reconstruct R and B everywhere from the INITIAL green
// estimate (green_pass.glsl's output, before refinement). This is the same
// difference-based + P/Q-discriminated maths as full_pass.glsl's DEBUGSTAGE>=6
// path - see that file for the full reasoning.
//
// Only the pattern==1/2 (green-native) output is actually read downstream:
// full_pass.glsl's green-refinement step samples colorTexture at the four
// CARDINAL neighbours of an R/B site, which are always green-native
// positions (that's just how the Bayer grid works). The pattern==0/3 output
// written here is scratch - full_pass.glsl recomputes R@B/B@R itself once
// the refined green is available there, so nothing downstream ever reads a
// pattern==0/3 texel from this texture. It's still computed the same way as
// everywhere else (rather than skipped) to keep this pass simple and its
// output well-defined at every pixel. If GPU time here matters for you, the
// pattern==0/3 branch below is the first thing worth short-circuiting.
// ---------------------------------------------------------------------------
void main() {
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    if (pos.x >= imgSize.x || pos.y >= imgSize.y) return;

    int pattern = ((pos.y % 2) << 1) | (pos.x % 2);
    float g = green(pos);
    float r = 0.0;
    float b = 0.0;

    if (pattern == 0) { // Red-native: keep raw R, reconstruct B diagonally
        r = bayer(pos);

        float gNW = green(pos + ivec2(-1, -1));
        float gNE = green(pos + ivec2( 1, -1));
        float gSW = green(pos + ivec2(-1,  1));
        float gSE = green(pos + ivec2( 1,  1));
        float bNW = bayer(pos + ivec2(-1, -1)) - gNW;
        float bNE = bayer(pos + ivec2( 1, -1)) - gNE;
        float bSW = bayer(pos + ivec2(-1,  1)) - gSW;
        float bSE = bayer(pos + ivec2( 1,  1)) - gSE;

        float gradNW = EPS + abs(gNW - g) * ratioEdgeProtection;
        float gradNE = EPS + abs(gNE - g) * ratioEdgeProtection;
        float gradSW = EPS + abs(gSW - g) * ratioEdgeProtection;
        float gradSE = EPS + abs(gSE - g) * ratioEdgeProtection;

        float pEst = (gradSE * bNW + gradNW * bSE) / (gradNW + gradSE);
        float qEst = (gradSW * bNE + gradNE * bSW) / (gradNE + gradSW);

        float pqDisc = pqRatio(pos);
        #if ARTIFACT_CORRECTION == 1
            float pqNb = 0.25 * (pqRatio(pos + ivec2(-1, 0)) + pqRatio(pos + ivec2(1, 0)) +
                                  pqRatio(pos + ivec2(0, -1)) + pqRatio(pos + ivec2(0, 1)));
            pqDisc = (abs(0.5 - pqDisc) < abs(0.5 - pqNb)) ? pqNb : pqDisc;
        #endif
        float blended = mix(pEst, qEst, pqDisc);
        float dMin = min(min(bNW, bNE), min(bSW, bSE));
        float dMax = max(max(bNW, bNE), max(bSW, bSE));

        #if ARTIFACT_CORRECTION == 1
            #if RATIO_SMOOTHING == 1
                float smDiff = 0.0;
                for (int j = -1; j <= 1; j++)
                    for (int i = -1; i <= 1; i++) {
                        ivec2 p = pos + ivec2(i * 2, j * 2);
                        smDiff += bayer(p + ivec2(1, 1)) - green(p + ivec2(1, 1));
                    }
                blended = mix(blended, smDiff / 9.0, 0.5);
            #endif
            float spread = dMax - dMin;
            float slack = spread * 0.5 * (ratioRobustness - 1.0);
            blended = clamp(blended, dMin - slack, dMax + slack);
        #endif
        b = g + blended;
    } else if (pattern == 3) { // Blue-native: keep raw B, reconstruct R diagonally
        b = bayer(pos);

        float gNW = green(pos + ivec2(-1, -1));
        float gNE = green(pos + ivec2( 1, -1));
        float gSW = green(pos + ivec2(-1,  1));
        float gSE = green(pos + ivec2( 1,  1));
        float rNW = bayer(pos + ivec2(-1, -1)) - gNW;
        float rNE = bayer(pos + ivec2( 1, -1)) - gNE;
        float rSW = bayer(pos + ivec2(-1,  1)) - gSW;
        float rSE = bayer(pos + ivec2( 1,  1)) - gSE;

        float gradNW = EPS + abs(gNW - g) * ratioEdgeProtection;
        float gradNE = EPS + abs(gNE - g) * ratioEdgeProtection;
        float gradSW = EPS + abs(gSW - g) * ratioEdgeProtection;
        float gradSE = EPS + abs(gSE - g) * ratioEdgeProtection;

        float pEst = (gradSE * rNW + gradNW * rSE) / (gradNW + gradSE);
        float qEst = (gradSW * rNE + gradNE * rSW) / (gradNE + gradSW);

        float pqDisc = pqRatio(pos);
        #if ARTIFACT_CORRECTION == 1
            float pqNb = 0.25 * (pqRatio(pos + ivec2(-1, 0)) + pqRatio(pos + ivec2(1, 0)) +
                                  pqRatio(pos + ivec2(0, -1)) + pqRatio(pos + ivec2(0, 1)));
            pqDisc = (abs(0.5 - pqDisc) < abs(0.5 - pqNb)) ? pqNb : pqDisc;
        #endif
        float blended = mix(pEst, qEst, pqDisc);
        float dMin = min(min(rNW, rNE), min(rSW, rSE));
        float dMax = max(max(rNW, rNE), max(rSW, rSE));

        #if ARTIFACT_CORRECTION == 1
            #if RATIO_SMOOTHING == 1
                float smDiff = 0.0;
                for (int j = -1; j <= 1; j++)
                    for (int i = -1; i <= 1; i++) {
                        ivec2 p = pos + ivec2(i * 2, j * 2);
                        smDiff += bayer(p + ivec2(-1, -1)) - green(p + ivec2(-1, -1));
                    }
                blended = mix(blended, smDiff / 9.0, 0.5);
            #endif
            float spread = dMax - dMin;
            float slack = spread * 0.5 * (ratioRobustness - 1.0);
            blended = clamp(blended, dMin - slack, dMax + slack);
        #endif
        r = g + blended;
    } else if (pattern == 1) { // Green-native (R-row): R horizontal, B vertical
        float gW = green(pos + ivec2(-1, 0));
        float gE = green(pos + ivec2( 1, 0));
        float rW = bayer(pos + ivec2(-1, 0)) - gW;
        float rE = bayer(pos + ivec2( 1, 0)) - gE;
        float gradW = EPS + abs(gW - g) * ratioEdgeProtection;
        float gradE = EPS + abs(gE - g) * ratioEdgeProtection;
        r = g + (gradE * rW + gradW * rE) / (gradW + gradE);

        float gN = green(pos + ivec2(0, -1));
        float gS = green(pos + ivec2(0,  1));
        float bN = bayer(pos + ivec2(0, -1)) - gN;
        float bS = bayer(pos + ivec2(0,  1)) - gS;
        float gradN = EPS + abs(gN - g) * ratioEdgeProtection;
        float gradS = EPS + abs(gS - g) * ratioEdgeProtection;
        b = g + (gradS * bN + gradN * bS) / (gradN + gradS);
    } else { // pattern == 2, Green-native (B-row): R vertical, B horizontal
        float gN = green(pos + ivec2(0, -1));
        float gS = green(pos + ivec2(0,  1));
        float rN = bayer(pos + ivec2(0, -1)) - gN;
        float rS = bayer(pos + ivec2(0,  1)) - gS;
        float gradN = EPS + abs(gN - g) * ratioEdgeProtection;
        float gradS = EPS + abs(gS - g) * ratioEdgeProtection;
        r = g + (gradS * rN + gradN * rS) / (gradN + gradS);

        float gW = green(pos + ivec2(-1, 0));
        float gE = green(pos + ivec2( 1, 0));
        float bW = bayer(pos + ivec2(-1, 0)) - gW;
        float bE = bayer(pos + ivec2( 1, 0)) - gE;
        float gradW = EPS + abs(gW - g) * ratioEdgeProtection;
        float gradE = EPS + abs(gE - g) * ratioEdgeProtection;
        b = g + (gradE * bW + gradW * bE) / (gradW + gradE);
    }

    vec4 outData = vec4(r, b, 0.0, 1.0);
    if (any(isnan(outData)) || any(isinf(outData))) {
        outData = vec4(g, g, 0.0, 1.0);
    }
    imageStore(outTexture, pos, outData);
}
