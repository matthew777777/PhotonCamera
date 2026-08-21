#define LAYOUT //
LAYOUT
precision highp float;

layout(rgba16f, binding = 0) uniform highp readonly image2D inTexture;
layout(rgba16f, binding = 2) uniform highp writeonly image2D outTexture;

#ifndef DEBUGSTAGE
#define DEBUGSTAGE 6
#endif

#ifndef ARTIFACT_CORRECTION
#define ARTIFACT_CORRECTION 0
#endif

uniform ivec2 imgSize;
uniform float edgeSensitivity;
uniform float dirConfidence;
uniform float greenEquil;

float bayer(ivec2 pos) {
    ivec2 size = imageSize(inTexture);
    return imageLoad(inTexture, clamp(pos, ivec2(0), size - 1)).r;
}

// Local Green Equilibration (Binned Quad Bayer fix) - unchanged from before.
float getBalancedGreen(ivec2 p) {
    float val = bayer(p);
    int ptype = ((p.y % 2) << 1) | (p.x % 2);
    if (ptype == 1 || ptype == 2) {
        // Neighbors at (+-1, +-1) are of the OTHER green type (G1 vs G2)
        float otherAvg = (bayer(p + ivec2(1, 1)) + bayer(p + ivec2(-1, 1)) + bayer(p + ivec2(1, -1)) + bayer(p + ivec2(-1, -1))) * 0.25;
        // Neighbors at (+-2, 0) and (0, +-2) are of the SAME green type
        float selfAvg = (bayer(p + ivec2(2, 0)) + bayer(p + ivec2(-2, 0)) + bayer(p + ivec2(0, 2)) + bayer(p + ivec2(0, -2))) * 0.25;
        if (selfAvg > 1e-6) {
            float corrected = val * (otherAvg / selfAvg);
            return mix(val, corrected, greenEquil);
        }
    }
    return val;
}

// ---------------------------------------------------------------------------
// Directional discrimination (RCD-style: Sanz Rodriguez / RawTherapee rcd_demosaic).
//
// dirStats() is the ORIGINAL 5x5 distance-weighted high-frequency energy probe,
// unchanged. It is the "expensive but robust" signal used at the pixel we are
// actually reconstructing.
//
// dirRatioCheap() is a lightweight single-tap probe (9 samples) used only to
// sample the direction field at the four diagonal neighbours.
//
// dirDisc() is the piece that was MISSING before: instead of trusting the
// pixel's own H-vs-V energy in isolation, it compares how CONFIDENT
// (i.e. how far from the ambiguous 0.5 midpoint) the central estimate is
// against the neighbourhood's average estimate, and keeps whichever is more
// decisive. A single noisy/ambiguous pixel can no longer flip direction
// against a clear neighbourhood consensus - this is the classic mechanism
// that suppresses zipper artifacts along edges. Previously the shader made a
// 100% independent, hard H/V choice per pixel, which is exactly what causes
// the woven/zipper pattern along diagonal or textured edges.
// ---------------------------------------------------------------------------
vec2 dirStats(ivec2 pos) {
    float sumDH = 0.0;
    float sumDV = 0.0;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            float w = 1.0 / (1.0 + float(i * i + j * j));
            ivec2 p = pos + ivec2(i, j);
            sumDH += w * (abs(bayer(p + ivec2(1, 0)) - bayer(p - ivec2(1, 0))) +
                         abs(2.0 * bayer(p) - bayer(p + ivec2(2, 0)) - bayer(p - ivec2(2, 0))));
            sumDV += w * (abs(bayer(p + ivec2(0, 1)) - bayer(p - ivec2(0, 1))) +
                         abs(2.0 * bayer(p) - bayer(p + ivec2(0, 2)) - bayer(p - ivec2(0, 2))));
        }
    }
    return vec2(sumDH, sumDV);
}

float dirRatioCheap(ivec2 p) {
    float c = bayer(p);
    float h = abs(bayer(p + ivec2(1, 0)) - bayer(p - ivec2(1, 0))) +
              abs(2.0 * c - bayer(p + ivec2(2, 0)) - bayer(p - ivec2(2, 0)));
    float v = abs(bayer(p + ivec2(0, 1)) - bayer(p - ivec2(0, 1))) +
              abs(2.0 * c - bayer(p + ivec2(0, 2)) - bayer(p - ivec2(0, 2)));
    return v / max(h + v, 1e-8);
}

// Returns a continuous blend weight in [0,1]: 0 -> pure vertical estimate,
// 1 -> pure horizontal estimate. (ratio close to 1 means the VERTICAL probe
// found strong energy, i.e. a horizontal edge is present, so we interpolate
// horizontally to stay parallel to it - and vice versa.)
float dirDisc(ivec2 pos, vec2 centralStat) {
    float central = centralStat.y / max(centralStat.x + centralStat.y, 1e-8);
#if ARTIFACT_CORRECTION == 1
    float nb = 0.25 * (dirRatioCheap(pos + ivec2(-1, -1)) + dirRatioCheap(pos + ivec2(1, -1)) +
                        dirRatioCheap(pos + ivec2(-1,  1)) + dirRatioCheap(pos + ivec2(1,  1)));
    float disc = (abs(0.5 - central) < abs(0.5 - nb)) ? nb : central;
#else
    float disc = central;
#endif
    // edgeSensitivity is a contrast control around the ambiguous midpoint:
    // 1.0 = unchanged, >1 snaps to a direction more decisively,
    // <1 stays blended (softer, less prone to committing to a wrong edge).
    return clamp(0.5 + (disc - 0.5) * edgeSensitivity, 0.0, 1.0);
}

void main() {
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    if (pos.x >= imgSize.x || pos.y >= imgSize.y) return;

    int pattern = ((pos.y % 2) << 1) | (pos.x % 2);
    float g = 0.0;
    float dir = 0.5;
    float conf = 0.0;

    if (pattern == 1 || pattern == 2) {
        g = getBalancedGreen(pos);
    } else {
        // Interpolate green at R/B location (Hamilton-Adams gradient-corrected estimate)
        float gh = (getBalancedGreen(pos + ivec2(1, 0)) + getBalancedGreen(pos - ivec2(1, 0))) / 2.0 +
                   (2.0 * bayer(pos) - bayer(pos + ivec2(2, 0)) - bayer(pos - ivec2(2, 0))) / 4.0;

        float gv = (getBalancedGreen(pos + ivec2(0, 1)) + getBalancedGreen(pos - ivec2(0, 1))) / 2.0 +
                   (2.0 * bayer(pos) - bayer(pos + ivec2(0, 2)) - bayer(pos - ivec2(0, 2))) / 4.0;

        vec2 stat = dirStats(pos);
        float disc = dirDisc(pos, stat);

        // Continuous blend, never a hard switch - this alone removes most of
        // the per-pixel flip-flopping that a binary "if (sumDH < sumDV)"
        // choice produces on fine texture and near-45-degree edges.
        g = mix(gv, gh, disc);
        dir = disc;
        conf = clamp(abs(disc - 0.5) * 2.0 + (dirConfidence - 0.5), 0.0, 1.0);
    }

    vec4 outData = vec4(g, dir, conf, 1.0);
    if (any(isnan(outData)) || any(isinf(outData))) {
        outData = vec4(1.0, 0.0, 1.0, 1.0);
    }
    imageStore(outTexture, pos, outData);
}
