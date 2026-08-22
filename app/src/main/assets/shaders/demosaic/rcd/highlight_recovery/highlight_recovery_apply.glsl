precision highp float;
precision highp sampler2D;

// HighlightRecovery - pass 3/3: apply the reconstructed color.
//
// See highlight_recovery_ratio.glsl for the license/attribution note and
// pass overview that apply to this whole group.
//
// CoeffBuffer is the final 1x1 output of the highlight_recovery_reduce.glsl
// chain: (R-candidate-sum, R-weight-sum, B-candidate-sum, B-weight-sum) for
// the whole image. Dividing gives the two global correction ratios used to
// reconstruct any genuinely clipped R or B photosite from its local G. G
// photosites and unclipped R/B photosites pass through unchanged - this
// only touches pixels that are actually clipped. If a channel had zero
// valid candidates anywhere in the frame (totals.g or totals.a == 0), its
// coefficient falls back to 1.0 (reconstruct as pure local G) rather than
// dividing by zero.

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
    float g = texelFetch(InputBuffer, xy + ivec2(1, 0), 0).r;
    g += texelFetch(InputBuffer, xy + ivec2(-1, 0), 0).r;
    g += texelFetch(InputBuffer, xy + ivec2(0, 1), 0).r;
    g += texelFetch(InputBuffer, xy + ivec2(0, -1), 0).r;
    return g * 0.25;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    float value = texelFetch(InputBuffer, xy, 0).r;
    int ch = bayerChannel(xy, cfaPattern);

    vec4 totals = texelFetch(CoeffBuffer, ivec2(0, 0), 0);
    float coeffR = totals.g > 0.0 ? totals.r / totals.g : 1.0;
    float coeffB = totals.a > 0.0 ? totals.b / totals.a : 1.0;

    bool clipped = (ch != 1) && value >= clipThreshold;
    float result = value;

    if (clipped) {
        float g = localG(xy);
        result = g * (ch == 0 ? coeffR : coeffB);
    }

    if (debugMask == 1) {
        Output = clipped ? vec4(1.0, 0.0, 0.0, 1.0) : vec4(vec3(value), 1.0);
        return;
    }

    Output = vec4(result, 0.0, 0.0, 1.0); // mosaic stays single-channel, in .r
}
