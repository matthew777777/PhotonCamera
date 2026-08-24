precision highp float;
precision highp sampler2D;

// HighlightRecovery - pass 1/3: candidate chroma-offset accumulation.
//
// Reimplements the first stage of "Inpaint Opposed" highlight reconstruction
// as used by RawTherapee (ported there from the G'MIC/darktable team - see
// RawPedia's Exposure page) and, in GPU form, by darktable's own OpenCL
// "inpaint opposed" path (darktable-org/darktable PR #17190, which names the
// reference-average step calc_refavg() and describes building "correction
// coeffs" from "highlights chroma per row" via reduction). PhotonCamera is
// GPL-3.0, matching both projects, so this is a same-license, from-scratch
// GLSL reimplementation of the published/observed approach - not a
// line-for-line port of unseen C++/OpenCL. Keep this credit comment with
// the file group.
//
// RT's own "Inpaint Opposed" is CPU-only - RT has no GPU pipeline at all -
// so "copy the exact version from RawTherapee, but on GPU" has no literal
// source to copy. This design instead follows darktable's real, shipped GPU
// port of the same base algorithm (global correction coefficients computed
// by reduction, then applied), since that's the closest real precedent for
// "this exact algorithm on a GPU."
//
// Runs once, at full resolution, on the raw Bayer mosaic (InputBuffer, one
// native channel per photosite in .r - matches this node's demosaic/rcd/
// placement, i.e. before RCD demosaic runs). For every bright, unclipped
// photosite near clipped data of the same color, this emits a candidate
// chroma offset from the local opposing reference.
// highlight_recovery_reduce.glsl then sums these across the whole image so
// highlight_recovery_apply.glsl can read back image-wide average chroma
// offsets and use them with each clipped pixel's local reference.

uniform sampler2D InputBuffer;   // raw Bayer mosaic, one channel in .r per texel
uniform float clipThreshold;     // photosite values >= this are "clipped"
uniform float chromaSampleMin;   // ignore candidates whose local G is darker than this -
                                  // avoid using noisy dark samples for chroma correction
uniform int cfaPattern;          // 0=RGGB 1=BGGR 2=GRBG 3=GBRG

out vec4 Output; // (R candidate * weight, R weight, B candidate * weight, B weight)

int bayerChannel(ivec2 xy, int pattern) {
    int idx = (xy.y & 1) * 2 + (xy.x & 1); // 0=TL 1=TR 2=BL 3=BR of the 2x2 CFA tile
    if (pattern == 0) { int l[4] = int[4](0, 1, 1, 2); return l[idx]; } // RGGB
    if (pattern == 1) { int l[4] = int[4](2, 1, 1, 0); return l[idx]; } // BGGR
    if (pattern == 2) { int l[4] = int[4](1, 0, 2, 1); return l[idx]; } // GRBG
    int l[4] = int[4](1, 2, 0, 1); return l[idx];                       // GBRG
}

float localG(ivec2 xy) {
    // The 4 orthogonal neighbors of any R or B photosite are G on every
    // standard Bayer CFA variant, so this needs no pattern switch.
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

bool nearbyClipped(ivec2 xy, int target) {
    ivec2 size = textureSize(InputBuffer, 0);
    for (int dy = -4; dy <= 4; dy++) {
        for (int dx = -4; dx <= 4; dx++) {
            ivec2 sampleXY = clamp(xy + ivec2(dx, dy), ivec2(0), size - ivec2(1));
            if (bayerChannel(sampleXY, cfaPattern) == target
                    && texelFetch(InputBuffer, sampleXY, 0).r >= clipThreshold) {
                return true;
            }
        }
    }
    return false;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    int ch = bayerChannel(xy, cfaPattern);

    if (ch == 1) { // G photosite: never a reconstruction candidate here
        Output = vec4(0.0);
        return;
    }

    float value = texelFetch(InputBuffer, xy, 0).r;
    bool valid = value < clipThreshold
        && value >= max(0.2 * clipThreshold, chromaSampleMin)
        && nearbyClipped(xy, ch);
    float offset = valid ? (value - opposingReference(xy, ch)) : 0.0;
    float w = valid ? 1.0 : 0.0;

    if (ch == 0) { // R photosite
        Output = vec4(offset * w, w, 0.0, 0.0);
    } else { // B photosite
        Output = vec4(0.0, 0.0, offset * w, w);
    }
}
