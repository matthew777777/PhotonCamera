precision highp float;
precision highp sampler2D;
uniform sampler2D InputBuffer;
uniform ivec2 size;
out vec4 Output;

// Set from Java (DynamicGaussian node) - isotropic fallback used when the
// KernelNet parameter map is unavailable (single frame / model missing)
#define RADIUS 2
#define STRENGTH 1.0
#define DETAIL_SENS 0.02
#define DEEP_SHADOW 0.001
#define QUANT_STEP (1.0/255.0)

float lum(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

vec3 fetch(ivec2 p) {
    return texelFetch(InputBuffer, clamp(p, ivec2(0), size - 1), 0).rgb;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec3 c = fetch(xy);
    float l = lum(c);

    // Local detail estimate: Sobel gradient on luma
    float gx = -lum(fetch(xy + ivec2(-1, -1))) - 2.0 * lum(fetch(xy + ivec2(-1, 0))) - lum(fetch(xy + ivec2(-1, 1)))
               + lum(fetch(xy + ivec2(1, -1))) + 2.0 * lum(fetch(xy + ivec2(1, 0))) + lum(fetch(xy + ivec2(1, 1)));
    float gy = -lum(fetch(xy + ivec2(-1, -1))) - 2.0 * lum(fetch(xy + ivec2(0, -1))) - lum(fetch(xy + ivec2(1, -1)))
               + lum(fetch(xy + ivec2(-1, 1))) + 2.0 * lum(fetch(xy + ivec2(0, 1))) + lum(fetch(xy + ivec2(1, 1)));
    float g = length(vec2(gx, gy));
    // detail: 0 = flat area (safe to smooth), 1 = strong edge/texture (keep as is)
    float detail = g / (g + DETAIL_SENS);

    // Quantization noise lives in the shadows; fade the filter out towards
    // highlights. Below DEEP_SHADOW the filter always runs at full strength.
    float shadow = 1.0 - smoothstep(DEEP_SHADOW, 0.6, l);

    // Final blending amount: full in flat shadows, zero on detail / bright areas
    float amt = STRENGTH * (1.0 - detail) * shadow;
    amt = clamp(amt, 0.0, 1.0);
    if (amt < 0.001) {
        Output = vec4(c, 1.0);
        return;
    }

    // Dynamic kernel: spatial sigma grows with amt, range sigma slightly above
    // the quantization step so real edges survive the range weighting
    float sigmaS = mix(0.6, float(RADIUS), amt);
    float sigmaR = QUANT_STEP * (0.75 + 2.0 * amt) * STRENGTH;

    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    float inv2ss = 1.0 / (2.0 * sigmaS * sigmaS);
    float inv2sr = 1.0 / (2.0 * sigmaR * sigmaR);
    for (int j = -RADIUS; j <= RADIUS; j++) {
        for (int i = -RADIUS; i <= RADIUS; i++) {
            vec3 s = fetch(xy + ivec2(i, j));
            float ws = exp(-float(i * i + j * j) * inv2ss);
            float dl = lum(s) - l;
            float wr = exp(-dl * dl * inv2sr);
            float w = ws * wr;
            sum += s * w;
            wsum += w;
        }
    }
    vec3 filtered = sum / max(wsum, 1e-6);
    Output = vec4(mix(c, filtered, amt), 1.0);
}
