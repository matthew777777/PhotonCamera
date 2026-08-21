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

// Local Green Equilibration (Binned Quad Bayer fix)
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
        // Interpolate green at R/B location
        float gh = (getBalancedGreen(pos + ivec2(1, 0)) + getBalancedGreen(pos - ivec2(1, 0))) / 2.0 +
                   (2.0 * bayer(pos) - bayer(pos + ivec2(2, 0)) - bayer(pos - ivec2(2, 0))) / 4.0;

        float gv = (getBalancedGreen(pos + ivec2(0, 1)) + getBalancedGreen(pos - ivec2(0, 1))) / 2.0 +
                   (2.0 * bayer(pos) - bayer(pos + ivec2(0, 2)) - bayer(pos - ivec2(0, 2))) / 4.0;

        float sumDH = 0.0;
        float sumDV = 0.0;

        // Expanded discrimination for binned sensor stability
        for(int j = -2; j <= 2; j++) {
            for(int i = -2; i <= 2; i++) {
                float w = 1.0 / (1.0 + float(i*i + j*j));
                ivec2 p = pos + ivec2(i, j);
                sumDH += w * (abs(bayer(p + ivec2(1, 0)) - bayer(p - ivec2(1, 0))) +
                             abs(2.0 * bayer(p) - bayer(p + ivec2(2, 0)) - bayer(p - ivec2(2, 0))));
                sumDV += w * (abs(bayer(p + ivec2(0, 1)) - bayer(p - ivec2(0, 1))) +
                             abs(2.0 * bayer(p) - bayer(p + ivec2(0, 2)) - bayer(p - ivec2(0, 2))));
            }
        }

#if ARTIFACT_CORRECTION == 1
        float diff = abs(sumDH - sumDV) * edgeSensitivity;
        float total = (sumDH + sumDV) + 1e-6;
        float normalizedConf = clamp(diff / total + (dirConfidence - 0.5), 0.0, 1.0);
        conf = normalizedConf;

        if (sumDH < sumDV) {
            g = mix((gh + gv) * 0.5, gh, normalizedConf);
            dir = 0.5 - 0.5 * normalizedConf;
        } else if (sumDV < sumDH) {
            g = mix((gh + gv) * 0.5, gv, normalizedConf);
            dir = 0.5 + 0.5 * normalizedConf;
        } else {
            g = (gh + gv) / 2.0;
            dir = 0.5;
        }
#else
        if (sumDH < sumDV) {
            g = gh;
            dir = 0.0;
        } else if (sumDV < sumDH) {
            g = gv;
            dir = 1.0;
        } else {
            g = (gh + gv) / 2.0;
            dir = 0.5;
        }
#endif
    }

    vec4 outData = vec4(g, dir, conf, 1.0);
    if (any(isnan(outData)) || any(isinf(outData))) {
        outData = vec4(1.0, 0.0, 1.0, 1.0);
    }
    imageStore(outTexture, pos, outData);
}
