precision highp float;
precision highp sampler2D;

// HighlightRecovery - pass 2/3: 2x2 sum reduction, run repeatedly.
//
// Ping-ponged by the orchestrating Node's Run(): each invocation halves both
// dimensions (rounding up), summing (not averaging) each 2x2 input block, so
// SourceBuffer's total sum is preserved at every level. Called
// ceil(log2(max(width,height))) times, starting from
// highlight_recovery_ratio.glsl's output, until the output is 1x1 - that
// single texel then holds the image-wide (R-offset-sum,R-weight-sum,
// B-offset-sum,B-weight-sum), which highlight_recovery_apply.glsl
// divides to get the two average chroma offsets.
//
// Plain float sums over realistic sensor pixel counts (tens of millions of
// terms, each O(1)) stay well inside highp float's exact-integer range
// (2^24) for the weight channels and lose only harmless fractional
// precision on the offset-sum channels - fine for an averaged correction
// factor. See highlight_recovery_ratio.glsl for the license/attribution
// note that applies to this whole pass group.

uniform sampler2D SourceBuffer;
uniform ivec2 sourceSize; // SourceBuffer's actual valid size (the tail of the
                           // chain is smaller than a real texture's alloc size)

out vec4 Output;

void main() {
    ivec2 outXY = ivec2(gl_FragCoord.xy);
    ivec2 base = outXY * 2;

    vec4 sum = vec4(0.0);
    for (int dy = 0; dy < 2; dy++) {
        for (int dx = 0; dx < 2; dx++) {
            ivec2 s = base + ivec2(dx, dy);
            if (s.x < sourceSize.x && s.y < sourceSize.y) {
                sum += texelFetch(SourceBuffer, s, 0);
            }
        }
    }
    Output = sum;
}
