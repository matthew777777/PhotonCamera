precision highp float;
precision highp sampler2D;

uniform sampler2D InputBuffer;
uniform float clipThreshold;
uniform int debugMask;

out vec4 fragColor;

// Inpaint Opposed Highlight Recovery for Bayer data
// Recovers clipped pixels using unclipped information from different color channels.
// Helps avoiding magenta highlights and preserving highlight detail.
void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    float centerVal = texelFetch(InputBuffer, xy, 0).r;

    // If pixel is not clipped, return original value
    if (centerVal < clipThreshold) {
        fragColor = vec4(centerVal, 0.0, 0.0, 1.0);
        if (debugMask == 1) fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // Determine Bayer color pattern for current pixel
    int pattern = ((xy.y % 2) << 1) | (xy.x % 2);

    // Search for unclipped neighbors of DIFFERENT color channels
    float unclippedSum = 0.0;
    float unclippedCount = 0.0;

    // Use a 3x3 neighborhood for recovery guidance
    for(int j = -1; j <= 1; j++) {
        for(int i = -1; i <= 1; i++) {
            if (i == 0 && j == 0) continue;
            ivec2 p = xy + ivec2(i, j);
            float v = texelFetch(InputBuffer, p, 0).r;
            int p_pattern = ((p.y % 2) << 1) | (p.x % 2);

            // If the neighbor is a DIFFERENT channel and NOT clipped
            if (p_pattern != pattern && v < clipThreshold) {
                unclippedSum += v;
                unclippedCount += 1.0;
            }
        }
    }

    float res = centerVal;

    if (unclippedCount > 0.0) {
        // Simple inpaint opposed: replace clipped value with average of opposed channels if they carry detail
        // This prevents the channel from staying stuck at peak while others are lower (hue shift)
        float avgOpposed = unclippedSum / unclippedCount;

        // We ensure recovered value is at least the original clipped value
        res = max(centerVal, avgOpposed);
    }

    if (debugMask == 1) {
        // Red color for recovered pixels
        fragColor = vec4(1.0, 0.0, 0.0, 1.0);
    } else {
        fragColor = vec4(res, 0.0, 0.0, 1.0);
    }
}
