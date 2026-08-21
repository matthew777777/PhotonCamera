#define LAYOUT //
LAYOUT
precision highp float;

layout(rgba16f, binding = 0) uniform highp readonly image2D inTexture;
layout(rgba16f, binding = 1) uniform highp readonly image2D greenTexture;
layout(rgba16f, binding = 2) uniform highp writeonly image2D outTexture;

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

// New tunables for Step 3+
uniform float ratioRobustness;
uniform float ratioEdgeProtection;
uniform float chromaCorrStr;

float bayer(ivec2 pos) {
    ivec2 size = imageSize(inTexture);
    return imageLoad(inTexture, clamp(pos, ivec2(0), size - 1)).r;
}

float green(ivec2 pos) {
    ivec2 size = imageSize(greenTexture);
    return imageLoad(greenTexture, clamp(pos, ivec2(0), size - 1)).r;
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

        // Base results for delta visualization
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

#if ARTIFACT_CORRECTION == 1
        if (pattern == 0) { // Red pixel
            r = bayer(pos);
            float bSum = 0.0, gSum = 0.0, bMin = 1e6, bMax = -1e6;
            ivec2 neighbors[4] = ivec2[](ivec2(-1, -1), ivec2(1, -1), ivec2(-1, 1), ivec2(1, 1));
            for(int i = 0; i < 4; i++) {
                ivec2 p = pos + neighbors[i];
                float gn = green(p), val = bayer(p), rat = val / max(gn, EPS);
                bMin = min(bMin, rat); bMax = max(bMax, rat);
                float relJump = abs(gn - g) / (max(g, gn) + EPS);
                maxRelJump = max(maxRelJump, relJump);
                float w = 1.0 / (1.0 + pow(abs(gn - g) * 10.0 * ratioEdgeProtection, 2.0));
                bSum += val * w; gSum += gn * w; edgeRej += (1.0 - w) * 0.25;
            }
            float rawRatio = bSum / max(gSum, EPS);
            #if RATIO_SMOOTHING == 1
                // 3x3 simple ratio smoothing for binned artifacts
                float smRatio = 0.0;
                for(int j=-1; j<=1; j++) for(int i=-1; i<=1; i++) {
                    ivec2 p = pos + ivec2(i*2, j*2); // Jump to next color block
                    smRatio += bayer(p+ivec2(1,1)) / max(green(p+ivec2(1,1)), EPS);
                }
                rawRatio = mix(rawRatio, smRatio / 9.0, 0.5);
            #endif
            float limitedRatio = clamp(rawRatio, (bMin+bMax)*0.5 - (bMax-bMin)*0.5*ratioRobustness, (bMin+bMax)*0.5 + (bMax-bMin)*0.5*ratioRobustness);
            ratioOutlier = abs(rawRatio - limitedRatio);
            b = g * limitedRatio;
        } else if (pattern == 3) { // Blue pixel
            b = bayer(pos);
            float rSum = 0.0, gSum = 0.0, rMin = 1e6, rMax = -1e6;
            ivec2 neighbors[4] = ivec2[](ivec2(-1, -1), ivec2(1, -1), ivec2(-1, 1), ivec2(1, 1));
            for(int i = 0; i < 4; i++) {
                ivec2 p = pos + neighbors[i];
                float gn = green(p), val = bayer(p), rat = val / max(gn, EPS);
                rMin = min(rMin, rat); rMax = max(rMax, rat);
                float relJump = abs(gn - g) / (max(g, gn) + EPS);
                maxRelJump = max(maxRelJump, relJump);
                float w = 1.0 / (1.0 + pow(abs(gn - g) * 10.0 * ratioEdgeProtection, 2.0));
                rSum += val * w; gSum += gn * w; edgeRej += (1.0 - w) * 0.25;
            }
            float rawRatio = rSum / max(gSum, EPS);
            #if RATIO_SMOOTHING == 1
                float smRatio = 0.0;
                for(int j=-1; j<=1; j++) for(int i=-1; i<=1; i++) {
                    ivec2 p = pos + ivec2(i*2, j*2);
                    smRatio += bayer(p+ivec2(-1,-1)) / max(green(p+ivec2(-1,-1)), EPS);
                }
                rawRatio = mix(rawRatio, smRatio / 9.0, 0.5);
            #endif
            float limitedRatio = clamp(rawRatio, (rMin+rMax)*0.5 - (rMax-rMin)*0.5*ratioRobustness, (rMin+rMax)*0.5 + (rMax-rMin)*0.5*ratioRobustness);
            ratioOutlier = abs(rawRatio - limitedRatio);
            r = g * limitedRatio;
        } else if (pattern == 1) { // G at R row
            float rS = 0.0, rgS = 0.0;
            ivec2 rN[2] = ivec2[](ivec2(-1, 0), ivec2(1, 0));
            for(int i = 0; i < 2; i++) {
                ivec2 p = pos + rN[i];
                float gn = green(p), val = bayer(p);
                float relJump = abs(gn - g) / (max(g, gn) + EPS);
                maxRelJump = max(maxRelJump, relJump);
                float w = 1.0 / (1.0 + pow(abs(gn - g) * 10.0 * ratioEdgeProtection, 2.0));
                rS += val * w; rgS += gn * w;
            }
            r = g * (rS / max(rgS, EPS));
            float bS = 0.0, bgS = 0.0;
            ivec2 bN[2] = ivec2[](ivec2(0, -1), ivec2(0, 1));
            for(int i = 0; i < 2; i++) {
                ivec2 p = pos + bN[i];
                float gn = green(p), val = bayer(p);
                float relJump = abs(gn - g) / (max(g, gn) + EPS);
                maxRelJump = max(maxRelJump, relJump);
                float w = 1.0 / (1.0 + pow(abs(gn - g) * 10.0 * ratioEdgeProtection, 2.0));
                bS += val * w; bgS += gn * w;
            }
            b = g * (bS / max(bgS, EPS));
        } else { // G at B row
            float rS = 0.0, rgS = 0.0;
            ivec2 rN[2] = ivec2[](ivec2(0, -1), ivec2(0, 1));
            for(int i = 0; i < 2; i++) {
                ivec2 p = pos + rN[i];
                float gn = green(p), val = bayer(p);
                float relJump = abs(gn - g) / (max(g, gn) + EPS);
                maxRelJump = max(maxRelJump, relJump);
                float w = 1.0 / (1.0 + pow(abs(gn - g) * 10.0 * ratioEdgeProtection, 2.0));
                rS += val * w; rgS += gn * w;
            }
            r = g * (rS / max(rgS, EPS));
            float bS = 0.0, bgS = 0.0;
            ivec2 bN[2] = ivec2[](ivec2(-1, 0), ivec2(1, 0));
            for(int i = 0; i < 2; i++) {
                ivec2 p = pos + bN[i];
                float gn = green(p), val = bayer(p);
                float relJump = abs(gn - g) / (max(g, gn) + EPS);
                maxRelJump = max(maxRelJump, relJump);
                float w = 1.0 / (1.0 + pow(abs(gn - g) * 10.0 * ratioEdgeProtection, 2.0));
                bS += val * w; bgS += gn * w;
            }
            b = g * (bS / max(bgS, EPS));
        }

        // Final directional clamping to suppress binned zippering
        r = clamp(r, min(r_base, g), max(r_base, g));
        b = clamp(b, min(b_base, g), max(b_base, g));

        if (ratioOutlier > 0.05) {
            float avgCh = (r + b) * 0.5;
            r = mix(r, avgCh, ratioOutlier * chromaCorrStr);
            b = mix(b, avgCh, ratioOutlier * chromaCorrStr);
            chromaCorr = ratioOutlier * chromaCorrStr;
        }

        atomicAdd(bins[7], 1u);
        if (maxRelJump < 0.05) atomicAdd(bins[0], 1u);
        else if (maxRelJump < 0.10) atomicAdd(bins[1], 1u);
        else if (maxRelJump < 0.20) atomicAdd(bins[2], 1u);
        else if (maxRelJump < 0.50) atomicAdd(bins[3], 1u);
        else atomicAdd(bins[4], 1u);
#else
        r = r_base;
        b = b_base;
#endif

        vec3 finalColor = vec3(r, g, b);
        if (debugMode == 1) finalColor = (dir == 0.0) ? vec3(1.0, 0.0, 0.0) : ((dir == 1.0) ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0));
        else if (debugMode == 2) finalColor = vec3(conf);
        else if (debugMode == 3) finalColor = vec3(r, 0.0, 0.0);
        else if (debugMode == 4) finalColor = vec3(0.0, 0.0, b);
        else if (debugMode == 15) {
            float delta = abs(r - r_base) + abs(b - b_base);
            finalColor = vec3(delta * 100.0, 0.0, 0.0);
        }

        if (any(isnan(finalColor)) || any(isinf(finalColor))) {
            finalColor = vec3(1.0, 0.0, 1.0);
        }
        imageStore(outTexture, pos, vec4(finalColor, 1.0));
    #endif
#endif
}
