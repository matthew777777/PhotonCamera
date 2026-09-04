#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
precision highp usampler2D;
precision highp image2D;

uniform highp usampler2D baseRaw;
uniform highp usampler2D frameRaw;
uniform highp sampler2D alignmentTexture;
uniform highp sampler2D kernelMap;
layout(rgba16f, binding = 0) uniform highp readonly image2D prevAccumulator;
layout(rgba16f, binding = 1) uniform highp writeonly image2D outAccumulator;

uniform ivec2 rawSize;
uniform ivec2 rawHalf;
uniform ivec2 alignmentSize;
uniform ivec2 alignmentShift;
uniform int tileSize;
uniform int cfaPattern;
uniform int isBase;
uniform float scale;
uniform float whiteLevel;
uniform vec4 blackLevel;
uniform float exposure;
uniform float robustnessStrength;
uniform float kernelDetail;
uniform float kernelDenoise;
uniform float kernelNetStrength;

// Channel ids: R=0, G=1, B=2. PhotonCamera CFA ids: RGGB=0, GRBG=1, GBRG=2, BGGR=3.
int inputColor(ivec2 p) {
    int x = p.x & 1;
    int y = p.y & 1;
    if (cfaPattern == 0) return (y == 0) ? (x == 0 ? 0 : 1) : (x == 0 ? 1 : 2);
    if (cfaPattern == 1) return (y == 0) ? (x == 0 ? 1 : 0) : (x == 0 ? 2 : 1);
    if (cfaPattern == 2) return (y == 0) ? (x == 0 ? 1 : 2) : (x == 0 ? 0 : 1);
    return (y == 0) ? (x == 0 ? 2 : 1) : (x == 0 ? 1 : 0);
}
int targetColor(ivec2 p) {
    // The derived CFA keeps the camera's Bayer ordering.  Scaling changes sample
    // positions, but not the 2x2 phase encoded by the synthetic output grid.
    return inputColor(p);
}
float blFor(ivec2 p) {
    // Parameters.blackLevel is stored in sensor 2x2 site order.
    return blackLevel[(p.y & 1) * 2 + (p.x & 1)];
}
float rawNorm(highp usampler2D tex, ivec2 p) {
    p = clamp(p, ivec2(0), rawSize - ivec2(1));
    float b = blFor(p);
    return clamp((float(texelFetch(tex, p, 0).r) - b) / max(whiteLevel - b, 1.0), 0.0, 1.0);
}
float lumaQuad(highp usampler2D tex, ivec2 p) {
    ivec2 q = clamp((p / 2) * 2, ivec2(0), rawSize - ivec2(2));
    return 0.25 * (rawNorm(tex,q) + rawNorm(tex,q+ivec2(1,0)) + rawNorm(tex,q+ivec2(0,1)) + rawNorm(tex,q+ivec2(1,1)));
}

// KernelNet is this camera pipeline's learned spatially adaptive denoising /
// reconstruction-kernel network. ESD4D already runs it once and exports a half-resolution
// map with texel=(s1,s2,rho,1). We reuse that exact map here; no degradation-kernel
// estimation and no synthetic MLP are involved.
vec3 kernelNetParams(vec2 rawPos) {
    vec2 denom = 2.0 * vec2(textureSize(kernelMap, 0));
    vec2 uv = clamp((rawPos + vec2(0.5)) / max(denom, vec2(1.0)), vec2(0.0), vec2(1.0));
    vec3 kp = texture(kernelMap, uv).rgb;
    kp.x = max(kp.x, 0.02);
    kp.y = max(kp.y, 0.02);
    kp.z = clamp(kp.z, -0.95, 0.95);
    return kp;
}

vec2 decodeAlignment(vec4 a) {
    return floor(a.xy * vec2(rawHalf) + vec2(0.5)) + a.zw; // packed/quad pixels
}
vec2 localAlignment(vec2 basePos) {
    if (isBase != 0) return vec2(0.0);
    // Existing PyramidAlignment indexes one displacement sample per tile in raw coordinates.
    vec2 t = basePos / float(max(tileSize,1));
    ivec2 i0 = clamp(ivec2(floor(t)), ivec2(0), alignmentSize - ivec2(1));
    ivec2 i1 = min(i0 + ivec2(1), alignmentSize - ivec2(1));
    vec2 f = fract(t);
    vec2 a00 = decodeAlignment(texelFetch(alignmentTexture, alignmentShift + i0, 0));
    vec2 a10 = decodeAlignment(texelFetch(alignmentTexture, alignmentShift + ivec2(i1.x,i0.y), 0));
    vec2 a01 = decodeAlignment(texelFetch(alignmentTexture, alignmentShift + ivec2(i0.x,i1.y), 0));
    vec2 a11 = decodeAlignment(texelFetch(alignmentTexture, alignmentShift + i1, 0));
    // Alignment is represented in packed 2x2 Bayer coordinates; convert to sensor-pixel coordinates.
    return 2.0 * mix(mix(a00,a10,f.x), mix(a01,a11,f.x), f.y);
}

void main() {
    ivec2 outP = ivec2(gl_GlobalInvocationID.xy);
    ivec2 outSize = imageSize(outAccumulator);
    if (any(greaterThanEqual(outP, outSize))) return;

    vec2 old = (isBase != 0) ? vec2(0.0) : imageLoad(prevAccumulator, outP).rg;
    vec2 basePos = (vec2(outP) + vec2(0.5)) / scale - vec2(0.5);
    vec2 srcPos = basePos + localAlignment(basePos);
    int wanted = targetColor(outP);

    // Structure-tensor-inspired steering from reference luminance. Narrow across an edge,
    // stretch along it; in flat/noisy areas widen toward denoising support.
    ivec2 bp = clamp(ivec2(round(basePos)), ivec2(1), rawSize - ivec2(2));
    float gx = 0.5 * (lumaQuad(baseRaw,bp+ivec2(2,0)) - lumaQuad(baseRaw,bp-ivec2(2,0)));
    float gy = 0.5 * (lumaQuad(baseRaw,bp+ivec2(0,2)) - lumaQuad(baseRaw,bp-ivec2(0,2)));
    float g2 = gx*gx + gy*gy;
    vec2 tangent = (g2 > 1e-10) ? normalize(vec2(-gy,gx)) : vec2(1.0,0.0);
    vec2 normal = vec2(-tangent.y,tangent.x);
    float detail = clamp(sqrt(g2) * 18.0, 0.0, 1.0);

    // Exact KernelNet reconstruction covariance, matching merge/mergeCombineWeight0:
    // s1/s2 are learned anisotropic standard deviations and rho is correlation.
    vec3 kn = kernelNetParams(basePos);
    float s1 = max(kn.x, 0.02);
    float s2 = max(kn.y, 0.02);
    float rho = clamp(kn.z, -0.95, 0.95);
    float detK = max(1.0 - rho*rho, 1e-4);
    float ka = 1.0 / (s1*s1*detK);
    float kb = -rho / (s1*s2*detK);
    float kc = 1.0 / (s2*s2*detK);

    // Wronski structure tensor remains as a graceful geometric fallback/blend knob,
    // but with kernelNetStrength=1 the learned spatial reconstruction kernel is used directly.
    float sigmaN = mix(kernelDenoise, kernelDetail, detail);
    float sigmaT = mix(kernelDenoise, max(kernelDetail*3.5, kernelDenoise), detail);

    // Noise-aware Wronski-style robustness: compare same-color source against the reference
    // prediction at the corresponding base coordinate and reject motion/disocclusion outliers.
    ivec2 ref0 = clamp(ivec2(round(basePos)), ivec2(0), rawSize - ivec2(1));
    float refVal = 0.0;
    float refW = 0.0;
    for (int yy=-2; yy<=2; ++yy) for (int xx=-2; xx<=2; ++xx) {
        ivec2 refSampleP = ref0 + ivec2(xx,yy);
        if (all(greaterThanEqual(refSampleP,ivec2(0))) && all(lessThan(refSampleP,rawSize)) && inputColor(refSampleP)==wanted) {
            float d2 = dot(vec2(refSampleP)-basePos, vec2(refSampleP)-basePos);
            float refKernelW = exp(-0.5*d2/1.2);
            refVal += refKernelW*rawNorm(baseRaw,refSampleP); refW += refKernelW;
        }
    }
    refVal /= max(refW,1e-6);

    float sum = 0.0;
    float wsum = 0.0;
    ivec2 c = ivec2(round(srcPos));
    // 5x5 raw neighborhood gives the same-color support needed by an RGGB-targeted reconstruction.
    for (int yy=-2; yy<=2; ++yy) for (int xx=-2; xx<=2; ++xx) {
        ivec2 sampleP = c + ivec2(xx,yy);
        if (any(lessThan(sampleP,ivec2(0))) || any(greaterThanEqual(sampleP,rawSize)) || inputColor(sampleP)!=wanted) continue;
        vec2 d = vec2(sampleP) - srcPos;
        float dn = dot(d,normal) / max(sigmaN,0.25);
        float dt = dot(d,tangent) / max(sigmaT,0.25);
        float wronskiExponent = 0.5*(dn*dn + dt*dt);
        // Same quadratic form used by the existing KernelNet-aware ESD4D merge.
        float kernelNetExponent = kc*d.x*d.x + 2.0*kb*d.x*d.y + ka*d.y*d.y;
        float kernelExponent = mix(wronskiExponent, kernelNetExponent, clamp(kernelNetStrength,0.0,1.0));
        float kernelWeight = exp(-kernelExponent);
        float v = clamp(rawNorm(frameRaw,sampleP) * exposure, 0.0, 1.0);
        float sigma = 0.004 + 0.018*sqrt(max(refVal,0.0));
        float z = abs(v-refVal) / max(sigma,1e-4);
        float robustnessWeight = 1.0 / (1.0 + robustnessStrength*z*z);
        float sampleWeight = kernelWeight * robustnessWeight;
        sum += sampleWeight*v; wsum += sampleWeight;
    }

    // Reference frame is always retained as a graceful single-frame fallback.
    if (isBase != 0) wsum = max(wsum, 1e-5);
    imageStore(outAccumulator, outP, vec4(old.x + sum, old.y + wsum, 0.0, 1.0));
}
