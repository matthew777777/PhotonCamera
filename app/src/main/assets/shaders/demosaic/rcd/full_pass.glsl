#define LAYOUT //
LAYOUT
precision highp float;

layout(rgba16f, binding = 0) uniform highp readonly image2D inTexture;
layout(rgba16f, binding = 1) uniform highp readonly image2D greenTexture;
layout(rgba16f, binding = 2) uniform highp writeonly image2D outTexture;
layout(rgba16f, binding = 3) uniform highp readonly image2D colorTexture;

#ifndef DEBUGSTAGE
#define DEBUGSTAGE 1
#endif

#ifndef EPS
#define EPS 0.0001
#endif

#ifndef ARTIFACT_CORRECTION
#define ARTIFACT_CORRECTION 0
#endif

#ifndef RATIO_SMOOTHING
#define RATIO_SMOOTHING 0
#endif

layout(std430, binding = 4) buffer relJumpStats {
    uint bins[8]; // 0:<0.05, 1:0.05-0.1, 2:0.1-0.2, 3:0.2-0.5, 4:>0.5, 5:>0.2, 6:>0.5, 7:total
};

uniform int debugMode;
uniform ivec2 imgSize;
uniform int CfaPattern;

uniform float ratioRobustness;
uniform float ratioEdgeProtection;
uniform float chromaCorrStr;
uniform float greenRefineStrength;

float bayer(ivec2 pos) {
    ivec2 size = imageSize(inTexture);
    return imageLoad(inTexture, clamp(pos, ivec2(0), size - 1)).r;
}

float green(ivec2 pos) {
    ivec2 size = imageSize(greenTexture);
    return imageLoad(greenTexture, clamp(pos, ivec2(0), size - 1)).r;
}

// .x = R reconstructed by rb_pass.glsl, .y = B reconstructed by rb_pass.glsl.
// Only meaningful at green-native (pattern 1/2) positions - see the long
// comment in rb_pass.glsl for why pattern 0/3 texels here are scratch.
vec2 colorRB(ivec2 pos) {
    ivec2 size = imageSize(colorTexture);
    vec4 c = imageLoad(colorTexture, clamp(pos, ivec2(0), size - 1));
    return c.rg;
}

// ---------------------------------------------------------------------------
// Diagonal ("\" = P axis, "/" = Q axis) detail-energy probe. Used only to
// decide which diagonal to trust when reconstructing R@B / B@R. Cheap,
// single-tap, evaluated at the pixel itself and (with ARTIFACT_CORRECTION on)
// at its 4 cardinal neighbours, so an isolated noisy pixel can't out-vote a
// confident neighbourhood consensus. Identical copy lives in rb_pass.glsl.
// ---------------------------------------------------------------------------
float pqRatio(ivec2 p) {
    float c = bayer(p);
    float pE = abs(bayer(p + ivec2(1, 1)) - bayer(p + ivec2(-1, -1))) +
               abs(2.0 * c - bayer(p + ivec2(2, 2)) - bayer(p + ivec2(-2, -2)));
    float qE = abs(bayer(p + ivec2(1, -1)) - bayer(p + ivec2(-1, 1))) +
               abs(2.0 * c - bayer(p + ivec2(2, -2)) - bayer(p + ivec2(-2, 2)));
    return pE / max(pE + qE, 1e-8);
}

void main() {
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    if (pos.x >= imgSize.x || pos.y >= imgSize.y) return;

#if DEBUGSTAGE == 1
    float b = bayer(pos);
    imageStore(outTexture, pos, vec4(vec3(b), 1.0));
#elif DEBUGSTAGE == 10
    int pattern = ((pos.y % 2) << 1) | (pos.x % 2);
    vec3 diagColor = vec3(0.0);
    if (pattern == 0) diagColor.r = bayer(pos);
    else if (pattern == 1 || pattern == 2) diagColor.g = bayer(pos);
    else diagColor.b = bayer(pos);
    imageStore(outTexture, pos, vec4(diagColor, 1.0));
#else
    vec4 gData = imageLoad(greenTexture, pos);
    float g = gData.r;
    float dir = gData.g;
    float conf = gData.b;

    #if DEBUGSTAGE == 2
        imageStore(outTexture, pos, vec4(vec3(g), 1.0));
    #else
        int pattern = ((pos.y % 2) << 1) | (pos.x % 2);
        float r = 0.0;
        float b = 0.0;
        float edgeRej = 0.0;
        float ratioOutlier = 0.0;
        float chromaCorr = 0.0;
        float maxRelJump = 0.0;

        // Naive bilinear / ratio ladder - kept ONLY so debugStage 3/4/5 still
        // show a meaningful comparison baseline, and so the debugMode==15
        // "how much did correction change things" heatmap still has
        // something to diff against. Not used to clamp the real output.
        float r_base = 0.0;
        float b_base = 0.0;
        if (pattern == 0) {
            r_base = bayer(pos);
            float bS = (bayer(pos + ivec2(-1, -1)) + bayer(pos + ivec2(1, -1)) + bayer(pos + ivec2(-1, 1))  + bayer(pos + ivec2(1, 1)));
            #if DEBUGSTAGE >= 5
                float gS = (green(pos + ivec2(-1, -1)) + green(pos + ivec2(1, -1)) + green(pos + ivec2(-1, 1))  + green(pos + ivec2(1, 1)));
                b_base = g * (bS / max(gS, EPS));
            #else
                b_base = bS / 4.0;
            #endif
        } else if (pattern == 3) {
            b_base = bayer(pos);
            float rS = (bayer(pos + ivec2(-1, -1)) + bayer(pos + ivec2(1, -1)) + bayer(pos + ivec2(-1, 1))  + bayer(pos + ivec2(1, 1)));
            #if DEBUGSTAGE >= 5
                float gS = (green(pos + ivec2(-1, -1)) + green(pos + ivec2(1, -1)) + green(pos + ivec2(-1, 1))  + green(pos + ivec2(1, 1)));
                r_base = g * (rS / max(gS, EPS));
            #else
                r_base = rS / 4.0;
            #endif
        } else if (pattern == 1) {
            float rS = bayer(pos + ivec2(-1, 0)) + bayer(pos + ivec2(1, 0));
            float bS = bayer(pos + ivec2(0, -1)) + bayer(pos + ivec2(0, 1));
            #if DEBUGSTAGE >= 5
                float grS = green(pos + ivec2(-1, 0)) + green(pos + ivec2(1, 0));
                float gbS = green(pos + ivec2(0, -1)) + green(pos + ivec2(0, 1));
                r_base = g * (rS / max(grS, EPS)); b_base = g * (bS / max(gbS, EPS));
            #else
                r_base = rS / 2.0; b_base = bS / 2.0;
            #endif
        } else {
            float rS = bayer(pos + ivec2(0, -1)) + bayer(pos + ivec2(0, 1));
            float bS = bayer(pos + ivec2(-1, 0)) + bayer(pos + ivec2(1, 0));
            #if DEBUGSTAGE >= 5
                float grS = green(pos + ivec2(0, -1)) + green(pos + ivec2(0, 1));
                float gbS = green(pos + ivec2(-1, 0)) + green(pos + ivec2(1, 0));
                r_base = g * (rS / max(grS, EPS)); b_base = g * (bS / max(gbS, EPS));
            #else
                r_base = rS / 2.0; b_base = bS / 2.0;
            #endif
        }

        #if DEBUGSTAGE >= 6
        if (pattern == 0) { // Red-native pixel: keep raw R, reconstruct B diagonally
            r = bayer(pos);

            // --- Green refinement -------------------------------------------------
            // rb_pass.glsl already reconstructed R and B at every green-native
            // (pattern 1/2) pixel using the INITIAL green estimate. (R-G) should
            // vary smoothly, so the R values at this pixel's 4 cardinal
            // (green-native) neighbours are a second, independent opinion on what
            // green should be here - if they disagree with green_pass's estimate,
            // that's a signal the original direction choice was slightly off.
            // (Note: reference RCD itself stops after computing green once; this
            // extra step is closer to how DCB-style demosaicing refines green.)
            {
                float gW = green(pos + ivec2(-1, 0));
                float gE = green(pos + ivec2( 1, 0));
                float gN = green(pos + ivec2(0, -1));
                float gS = green(pos + ivec2(0,  1));
                float diffW = colorRB(pos + ivec2(-1, 0)).x - gW; // R@W - G@W
                float diffE = colorRB(pos + ivec2( 1, 0)).x - gE;
                float diffN = colorRB(pos + ivec2(0, -1)).x - gN;
                float diffS = colorRB(pos + ivec2(0,  1)).x - gS;

                float hDiff = 0.5 * (diffE + diffW);
                float vDiff = 0.5 * (diffN + diffS);
                float blendedDiff = mix(vDiff, hDiff, dir); // reuse green_pass's own H/V call
                float gRefined = r - blendedDiff;

                float gMin = min(min(gN, gS), min(gE, gW));
                float gMax = max(max(gN, gS), max(gE, gW));
                float gSpread = max(gMax - gMin, EPS);
                float delta = clamp(gRefined - g, -gSpread * 2.0, gSpread * 2.0);

                g = max(mix(g, g + delta, greenRefineStrength), 0.0);
            }

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

            // P ("\", NW-SE) and Q ("/", NE-SW) estimates: cross-weighted so the
            // corner on the FLATTER side (smaller local gradient) is trusted more.
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
            float spread = dMax - dMin;
            maxRelJump = spread / max(g, EPS);
            edgeRej = abs(pqDisc - 0.5) * 2.0;

            #if ARTIFACT_CORRECTION == 1
                #if RATIO_SMOOTHING == 1
                    float smDiff = 0.0;
                    for (int j = -1; j <= 1; j++)
                        for (int i = -1; i <= 1; i++) {
                            ivec2 p = pos + ivec2(i * 2, j * 2); // jump to next colour block
                            smDiff += bayer(p + ivec2(1, 1)) - green(p + ivec2(1, 1));
                        }
                    blended = mix(blended, smDiff / 9.0, 0.5);
                #endif
                float slack = spread * 0.5 * (ratioRobustness - 1.0);
                float limited = clamp(blended, dMin - slack, dMax + slack);
                ratioOutlier = abs(blended - limited) / max(g, EPS);
                b = g + limited;

                atomicAdd(bins[7], 1u);
                if (maxRelJump < 0.05) atomicAdd(bins[0], 1u);
                else if (maxRelJump < 0.10) atomicAdd(bins[1], 1u);
                else if (maxRelJump < 0.20) atomicAdd(bins[2], 1u);
                else if (maxRelJump < 0.50) atomicAdd(bins[3], 1u);
                else atomicAdd(bins[4], 1u);
                if (maxRelJump > 0.20) atomicAdd(bins[5], 1u);
                if (maxRelJump > 0.50) atomicAdd(bins[6], 1u);
            #else
                b = g + blended;
            #endif
        } else if (pattern == 3) { // Blue-native pixel: keep raw B, reconstruct R diagonally
            b = bayer(pos);

            // --- Green refinement (mirror of pattern==0 above, B in place of R) ---
            {
                float gW = green(pos + ivec2(-1, 0));
                float gE = green(pos + ivec2( 1, 0));
                float gN = green(pos + ivec2(0, -1));
                float gS = green(pos + ivec2(0,  1));
                float diffW = colorRB(pos + ivec2(-1, 0)).y - gW; // B@W - G@W
                float diffE = colorRB(pos + ivec2( 1, 0)).y - gE;
                float diffN = colorRB(pos + ivec2(0, -1)).y - gN;
                float diffS = colorRB(pos + ivec2(0,  1)).y - gS;

                float hDiff = 0.5 * (diffE + diffW);
                float vDiff = 0.5 * (diffN + diffS);
                float blendedDiff = mix(vDiff, hDiff, dir);
                float gRefined = b - blendedDiff;

                float gMin = min(min(gN, gS), min(gE, gW));
                float gMax = max(max(gN, gS), max(gE, gW));
                float gSpread = max(gMax - gMin, EPS);
                float delta = clamp(gRefined - g, -gSpread * 2.0, gSpread * 2.0);

                g = max(mix(g, g + delta, greenRefineStrength), 0.0);
            }

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
            float spread = dMax - dMin;
            maxRelJump = spread / max(g, EPS);
            edgeRej = abs(pqDisc - 0.5) * 2.0;

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
                float slack = spread * 0.5 * (ratioRobustness - 1.0);
                float limited = clamp(blended, dMin - slack, dMax + slack);
                ratioOutlier = abs(blended - limited) / max(g, EPS);
                r = g + limited;

                atomicAdd(bins[7], 1u);
                if (maxRelJump < 0.05) atomicAdd(bins[0], 1u);
                else if (maxRelJump < 0.10) atomicAdd(bins[1], 1u);
                else if (maxRelJump < 0.20) atomicAdd(bins[2], 1u);
                else if (maxRelJump < 0.50) atomicAdd(bins[3], 1u);
                else atomicAdd(bins[4], 1u);
                if (maxRelJump > 0.20) atomicAdd(bins[5], 1u);
                if (maxRelJump > 0.50) atomicAdd(bins[6], 1u);
            #else
                r = g + blended;
            #endif
        } else { // pattern == 1 or 2, green-native: rb_pass already has the answer
            vec2 rb = colorRB(pos);
            r = rb.x;
            b = rb.y;
        }

        if (ratioOutlier > 0.05) {
            float avgCh = (r + b) * 0.5;
            r = mix(r, avgCh, ratioOutlier * chromaCorrStr);
            b = mix(b, avgCh, ratioOutlier * chromaCorrStr);
            chromaCorr = ratioOutlier * chromaCorrStr;
            if (debugMode == 16) { r = mix(r, avgCh, 0.9); b = mix(b, avgCh, 0.9); }
        }
        #else
        r = r_base;
        b = b_base;
        #endif

        vec3 finalColor = vec3(r, g, b);
        if (debugMode == 1) {
            // dir in [0,1]: 0=vertical, 0.5=ambiguous/blended, 1=horizontal.
            finalColor = (dir < 0.5)
                ? mix(vec3(0.0, 1.0, 0.0), vec3(0.0, 0.0, 1.0), (0.5 - dir) * 2.0)
                : mix(vec3(0.0, 1.0, 0.0), vec3(1.0, 0.0, 0.0), (dir - 0.5) * 2.0);
        }
        else if (debugMode == 2) finalColor = vec3(conf);
        else if (debugMode == 3) finalColor = vec3(r, 0.0, 0.0);
        else if (debugMode == 4) finalColor = vec3(0.0, 0.0, b);
        else if (debugMode == 5) finalColor = vec3(r / max(g, EPS), 0.0, 0.0);
        else if (debugMode == 6) finalColor = vec3(0.0, 0.0, b / max(g, EPS));
        else if (debugMode == 9) finalColor = vec3(conf);
        else if (debugMode == 10) finalColor = vec3(r / max(g, EPS), 0.0, 0.0);
        else if (debugMode == 11) finalColor = vec3(0.0, 0.0, b / max(g, EPS));
        else if (debugMode == 12) finalColor = vec3(edgeRej);
        else if (debugMode == 13) finalColor = vec3(ratioOutlier * 10.0);
        else if (debugMode == 14) finalColor = vec3(chromaCorr * 10.0);
        else if (debugMode == 15) {
            float delta = abs(r - r_base) + abs(b - b_base);
            finalColor = vec3(delta * 100.0, 0.0, 0.0);
        }

        // NUMERICAL SAFETY INSTRUMENTATION
        if (any(isnan(finalColor)) || any(isinf(finalColor))) {
            finalColor = vec3(1.0, 0.0, 1.0);
        }
        imageStore(outTexture, pos, vec4(finalColor, 1.0));
    #endif
#endif
}
