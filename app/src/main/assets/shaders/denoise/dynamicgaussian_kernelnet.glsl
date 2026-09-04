#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
precision highp image2D;

layout(rgba16f, binding = 0) uniform highp writeonly image2D outTexture;

uniform sampler2D InputBuffer;
// KernelNet anisotropic kernel parameters (s1, s2, rho) generated during the
// merge pass (ESD4D) and reused here at no extra inference cost.
uniform sampler2D KernelsMap;
uniform ivec2 size;
// sigmaScale = strength * (post resolution / rawHalf resolution)
uniform float sigmaScale;
// Noise model (variance = S*luma + O), averaged over channels:
// pre = single-frame model the KernelNet sigmas were predicted for,
// post = merged/stacked model of the image this pass actually denoises.
uniform vec2 noisePre;   // (S, O) pre-merge
uniform vec2 noisePost;  // (S, O) post-merge

#define TS 8
#define RADIUS 4
#define WIN (TS + 2 * RADIUS)   // 16x16 = 256 texels, 4 per thread
#define AREA_MULTIPLIER 1.0

shared vec3 tile[WIN][WIN];

float lum(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void loadTile(int k) {
    int x = k % WIN;
    int y = k / WIN;
    ivec2 g = ivec2(gl_WorkGroupID.xy) * TS - RADIUS + ivec2(x, y);
    tile[y][x] = texelFetch(InputBuffer, clamp(g, ivec2(0), size - 1), 0).rgb;
}

void main() {
    int tid = int(gl_LocalInvocationIndex);
    // 2-pass preload, 2 texels per thread per pass (64*2*2 = 256)
    loadTile(tid);
    loadTile(tid + 64);
    loadTile(tid + 128);
    loadTile(tid + 192);
    barrier();

    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    if (xy.x >= size.x || xy.y >= size.y) return;
    ivec2 lc = ivec2(gl_LocalInvocationID.xy) + RADIUS;

    // Noise-robust brightness for the noise model evaluation: 3x3 average
    // luma from the preloaded window
    float l = 0.0;
    for (int j = -1; j <= 1; j++)
        for (int i = -1; i <= 1; i++)
            l += lum(tile[lc.y + j][lc.x + i]);
    l /= 9.0;

    // Kernel area correction: KernelNet predicted sigmas for the single-frame
    // pre-merge noise, but this pass runs on the merged image where stacking
    // already shrunk the variance. Kernel area scales with noise variance, so
    // the area is scaled by the variance ratio (clamped to [0,1]) and the
    // AREA_MULTIPLIER tunable. Shadows keep headroom for the quantization
    // noise the photon-noise model underestimates.
    // sqrt() converts the area ratio to a sigma ratio (area ~ sigma^2).
    float preVar = noisePre.x * l + noisePre.y;
    float postVar = noisePost.x * l + noisePost.y;
    float areaRatio = clamp(postVar / max(preVar, 1e-9), 0.0, 1.0);
    float corrScaleShadow = mix(2.0, 1.0, smoothstep(0.0, 0.1, l));
    float corrScale = sigmaScale * sqrt(AREA_MULTIPLIER * areaRatio * corrScaleShadow);

    // Anisotropic gaussian: std devs (s1, s2) with correlation rho
    vec3 kp = texture(KernelsMap, (vec2(xy) + 0.5) / vec2(size)).rgb;
    float s1 = max(kp.r * corrScale, 0.02);
    float s2 = max(kp.g * corrScale, 0.02);
    float rho = clamp(kp.b, -0.95, 0.95);
    float ir2 = 1.0 / (2.0 * (1.0 - rho * rho));
    float i11 = ir2 / (s1 * s1);
    float i22 = ir2 / (s2 * s2);
    float i12 = -ir2 * 2.0 * rho / (s1 * s2);

    // Wiener shrinkage weight (same form as merge/mergeCombineWeight):
    // N^2 / (d^2 + N^2), with N the post-merge noise sigma and d the luma
    // deviation of the neighbor from the center. Neighbors differing by much
    // more than the noise level (edges/details) get their weight suppressed
    // instead of being averaged in.
    float nVar = postVar;

    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    for (int j = -RADIUS; j <= RADIUS; j++) {
        for (int i = -RADIUS; i <= RADIUS; i++) {
            vec3 s = tile[lc.y + j][lc.x + i];
            float ws = exp(-(float(i * i) * i11 + float(i * j) * i12 + float(j * j) * i22));
            float dl = lum(s) - l;
            float wiener = nVar / (dl * dl + nVar);
            float w = ws * wiener;
            sum += s * w;
            wsum += w;
        }
    }
    imageStore(outTexture, xy, vec4(sum / max(wsum, 1e-6), 1.0));
}
