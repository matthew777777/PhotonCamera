precision highp float;
precision highp sampler2D;
uniform sampler2D InputBuffer;
uniform ivec2 inputSize;
uniform float downscaleFactor;
out vec4 result;

// Gaussian low-pass + decimation for pyramid analysis.
// The kernel is centered on the input position that the bilinear upsampling
// used by pyramiddiff / fusionbayer3 assigns to this output texel:
//   center = (out + 0.5) * downscaleFactor - 0.5
// (gl_FragCoord.xy already carries the +0.5 pixel-center offset).
// Keeping this phase aligned is required for the Laplacian pyramid to fuse
// levels without residual ringing. Sigma tracks the new sample spacing so
// larger downscale factors do not alias, and weights are renormalized after
// truncation so the filter never alters the DC level.
const int MAX_TAPS = 9; // radius <= ceil(2*sigma) <= 4 for downscale <= 4

void main() {
    vec2 center = vec2(gl_FragCoord.xy) * downscaleFactor - vec2(0.5);
    float sigma = max(0.75, 0.5 * downscaleFactor);
    int radius = int(ceil(2.0 * sigma));
    radius = min(radius, (MAX_TAPS - 1) / 2);
    int taps = 2 * radius + 1;
    ivec2 origin = ivec2(floor(center));
    vec2 sub = center - vec2(origin);

    // Separable 1D weights, shifted by the fractional phase of the center
    float wx[MAX_TAPS];
    float wy[MAX_TAPS];
    float swx = 0.0;
    float swy = 0.0;
    for (int i = 0; i < taps; i++) {
        float dx = float(i - radius) + sub.x;
        float dy = float(i - radius) + sub.y;
        wx[i] = exp(-0.5 * dx * dx / (sigma * sigma));
        wy[i] = exp(-0.5 * dy * dy / (sigma * sigma));
        swx += wx[i];
        swy += wy[i];
    }
    for (int i = 0; i < taps; i++) {
        wx[i] /= swx;
        wy[i] /= swy;
    }

    vec4 sum = vec4(0.0);
    for (int j = 0; j < taps; j++) {
        int sy = clamp(origin.y + j - radius, 0, inputSize.y - 1);
        vec4 row = vec4(0.0);
        for (int i = 0; i < taps; i++) {
            int sx = clamp(origin.x + i - radius, 0, inputSize.x - 1);
            row += texelFetch(InputBuffer, ivec2(sx, sy), 0) * wx[i];
        }
        sum += row * wy[j];
    }
    result = sum;
}
