precision highp float;
precision highp sampler2D;

// HighlightRecovery - pass 3/3: apply the reconstructed color.
//
// See highlight_recovery_ratio.glsl for the license/attribution note and
// pass overview that apply to this whole group.
//
// CoeffBuffer is the final 1x1 output of the highlight_recovery_reduce.glsl
// chain: (R-offset-sum, R-weight-sum, B-offset-sum, B-weight-sum). Dividing
// gives the global chroma offsets used with each pixel's local opposing
// reference, matching darktable's opposed reconstruction.

uniform sampler2D InputBuffer;
uniform sampler2D CoeffBuffer;
uniform float clipThreshold;
uniform int cfaPattern;
uniform int debugMask; // 1 = highlight reconstructed pixels instead of returning the image

out vec4 Output;

int bayerChannel(ivec2 xy, int pattern) {
    int idx = (xy.y & 1) * 2 + (xy.x & 1);
    if (pattern == 0) { int l[4] = int[4](0, 1, 1, 2); return l[idx]; } // RGGB
    if (pattern == 1) { int l[4] = int[4](2, 1, 1, 0); return l[idx]; } // BGGR
    if (pattern == 2) { int l[4] = int[4](1, 0, 2, 1); return l[idx]; } // GRBG
    int l[4] = int[4](1, 2, 0, 1); return l[idx];                       // GBRG
}

float localG(ivec2 xy) {
    ivec2 size = textureSize(InputBuffer, 0);
    ivec2 right = clamp(xy + ivec2(1, 0), ivec2(0), size - ivec2(1));
    ivec2 left = clamp(xy - ivec2(1, 0), ivec2(0), size - ivec2(1));
    ivec2 down = clamp(xy + ivec2(0, 1), ivec2(0), size - ivec2(1));
    ivec2 up = clamp(xy - ivec2(0, 1), ivec2(0), size - ivec2(1));
    float g = texelFetch(InputBuffer, right, 0).r;
    g += texelFetch(InputBuffer, left, 0).r;
    g += texelFetch(InputBuffer, down, 0).r;
    g += texelFetch(InputBuffer, up, 0).r;
    return g * 0.25;
}

float opposingReference(ivec2 xy, int target) {
    ivec2 size = textureSize(InputBuffer, 0);
    float sumR = 0.0;
    float sumG = 0.0;
    float sumB = 0.0;
    float countR = 0.0;
    float countG = 0.0;
    float countB = 0.0;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            ivec2 sampleXY = clamp(xy + ivec2(dx, dy), ivec2(0), size - ivec2(1));
            int channel = bayerChannel(sampleXY, cfaPattern);
            float sampleValue = max(texelFetch(InputBuffer, sampleXY, 0).r, 0.0);
            if (channel == 0) { sumR += sampleValue; countR += 1.0; }
            else if (channel == 1) { sumG += sampleValue; countG += 1.0; }
            else { sumB += sampleValue; countB += 1.0; }
        }
    }
    float rootR = pow(sumR / max(countR, 1.0), 1.0 / 3.0);
    float rootG = pow(sumG / max(countG, 1.0), 1.0 / 3.0);
    float rootB = pow(sumB / max(countB, 1.0), 1.0 / 3.0);
    float opposingRoot = target == 0 ? 0.5 * (rootG + rootB)
        : (target == 1 ? 0.5 * (rootR + rootB) : 0.5 * (rootR + rootG));
    return opposingRoot * opposingRoot * opposingRoot;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    float value = texelFetch(InputBuffer, xy, 0).r;
    int ch = bayerChannel(xy, cfaPattern);

    vec4 totals = texelFetch(CoeffBuffer, ivec2(0, 0), 0);
    float chromaR = totals.g > 0.0 ? totals.r / totals.g : 0.0;
    float chromaB = totals.a > 0.0 ? totals.b / totals.a : 0.0;

    bool clipped = (ch != 1) && value >= clipThreshold;
    float result = value;

    if (clipped) {
        float reference = opposingReference(xy, ch);
        result = max(value, reference + (ch == 0 ? chromaR : chromaB));
    }

    if (debugMask == 1) {
        Output = clipped ? vec4(1.0, 0.0, 0.0, 1.0) : vec4(vec3(value), 1.0);
        return;
    }

    Output = vec4(result, 0.0, 0.0, 1.0); // mosaic stays single-channel, in .r
}
