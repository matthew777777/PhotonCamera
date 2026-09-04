precision highp sampler2D;
precision highp int;
precision highp float;
uniform sampler2D inTexture;
uniform vec4 exposure;
uniform float input1;
uniform float input2;
// Row-major 3x3 kernel for the f-matched difference operator (only when
// SPATIAL_KERNEL is defined). ESD4D passes the same progressive Gaussian
// weights the temporal blend used, so the operator's nulls align with the
// temporal kernel's passband: detail the blend did not blur is exactly
// where the operator is blind.
uniform float spatialKernel[9];
#define COL_R 1
#define COL_G 1
#define COL_B 1
#define COL_A 1
#define COL_CUSTOM 0
#define HISTSIZE 256
//#define HISTMPY 255.0
#define SCALE 1
#define HISTSTEPS uint(HISTSIZE/64)
#ifndef SPATIAL_KERNEL
#define SPATIAL_KERNEL 0
#endif

#if COL_R == 1
layout(std430, binding = 1) buffer histogramRed {
    uint reds[];
};
shared uint localRed[HISTSIZE];
#endif
#if COL_G == 1
layout(std430, binding = 2) buffer histogramGreen {
    uint greens[];
};
shared uint localGreen[HISTSIZE];
#endif
#if COL_B == 1
layout(std430, binding = 3) buffer histogramBlue {
    uint blues[];
};
shared uint localBlue[HISTSIZE];
#endif
#if COL_A == 1
layout(std430, binding = 4) buffer histogramAlpha {
    uint alphas[];
};
shared uint localAlpha[HISTSIZE];
#endif

#define CUSTOM_PROGRAM //
#import median
#define LAYOUT //
LAYOUT

void main() {
ivec2 storePos = ivec2(gl_GlobalInvocationID.xy)*SCALE;
ivec2 imgsize = textureSize(inTexture,0).xy;
uint index = uint(gl_LocalInvocationIndex) * HISTSTEPS; // 0 - 64 * HISTSTEPS
for (uint i = 0u; i < HISTSTEPS; i++) {
#if COL_R == 1
        localRed[index + i] = 0u;
#endif
        #if COL_G == 1
        localGreen[index + i] = 0u;
#endif
        #if COL_B == 1
        localBlue[index + i] = 0u;
#endif
        #if COL_A == 1
        localAlpha[index + i] = 0u;
#endif
    }
barrier();

if (storePos.x < imgsize.x && storePos.y < imgsize.y) {
vec4 texColor = texture(inTexture,(vec2(storePos) + 0.5)/vec2(imgsize));
uvec4 texColorUint = clamp(uvec4(exposure * texColor), uvec4(0), uvec4(HISTSIZE - 1));
        #if COL_CUSTOM == 1
        #if SPATIAL_KERNEL
        // ------------------------------------------------------------
        // Luma f-matched difference operator (ESD4D adaptive noise): the
        // quad luma (mean of the 4 packed Bayer channels) against its
        // kernel mean, with the same progressive weights the temporal
        // blend used (largest symmetric prefix). Working on the luma:
        //   - chroma structure cancels exactly (opposite-signed channel
        //     deviations cancel in the mean),
        //   - the luma noise variance equals S*b + O in quad-mean
        //     brightness for ANY white point (channel weights cancel
        //     identically),
        //   - the symmetric kernel annihilates planes, so gradients
        //     contribute exactly zero.
        // Robustness comes from the histogram's per-brightness-row cutoff
        // over ~1e5 samples; the noise response is calibrated end-to-end
        // by NOISE_BLEND_VAR_STAT (see tools/.../luma_mc.py).
        // ------------------------------------------------------------
        float lcenter = dot(texColor, vec4(0.25));
        float kmean = 0.0;
        for (int i = -1; i <= 1; i++) {
        for (int j = -1; j <= 1; j++) {
        vec4 v = texture(inTexture,
        (vec2(storePos + ivec2(i, j)) + 0.5) / vec2(imgsize));
        // row-major kernel: offset (i=x, j=y) -> spatialKernel[(j+1)*3 + (i+1)]
        kmean += spatialKernel[(j + 1) * 3 + (i + 1)] * dot(v, vec4(0.25));
        }
        }
        float var = abs(lcenter - kmean);
        float br = sqrt(max(kmean, 0.0) + 1e-8);
        #else
        // ------------------------------------------------------------
        // Legacy median-chain statistic (single frame, no kernel):
        // approximate 5x5 median -> median of squared deviations.
        // ------------------------------------------------------------
        vec4 pixels[5][5];
        for (int i = -2; i <= 2; i++) {
        for (int j = -2; j <= 2; j++) {
        pixels[i+2][j+2] = texture(inTexture,
        (vec2(storePos + ivec2(i, j)) + 0.5) / vec2(imgsize));
        }
        }
        vec4 blockMedians[9];
        int idx = 0;
        for (int bi = 0; bi < 3; bi++) {
        for (int bj = 0; bj < 3; bj++) {
        vec4 block[9];
        int k = 0;
        for (int di = 0; di < 3; di++) {
        for (int dj = 0; dj < 3; dj++) {
        block[k++] = pixels[bi + di][bj + dj];
        }
        }
        blockMedians[idx++] = median9(block);
        }
        }
        vec4 medK = median9(blockMedians);
        vec4 sqDiff[5][5];
        for (int i = 0; i < 5; i++) {
                for (int j = 0; j < 5; j++) {
                        vec4 diff = pixels[i][j] - medK;
                        sqDiff[i][j] = diff * diff;
                }
        }
        vec4 varBlockMedians[9];
        idx = 0;
        for (int bi = 0; bi < 3; bi++) {
                for (int bj = 0; bj < 3; bj++) {
                        vec4 block[9];
                        int k = 0;
                        for (int di = 0; di < 3; di++) {
                                for (int dj = 0; dj < 3; dj++) {
                                block[k++] = sqDiff[bi + di][bj + dj];
                                }
                        }
                        varBlockMedians[idx++] = median9(block);
                }
        }
        vec4 variance = median9(varBlockMedians);
        float vmed[5];
        vmed[0] = variance.r;
        vmed[1] = variance.g;
        vmed[2] = variance.b;
        vmed[3] = variance.a;
        vmed[4] = dot(variance, vec4(0.25));
        float br = sqrt(dot(medK, vec4(0.25)) + 1e-8);
        float var = sqrt(median5(vmed) + 1e-8);
        #endif
        uint brBin = uint(min(63.0, br * input1));
        uint varBin = uint(min(63.0, var * input2));
        uint combined = brBin * 64u + varBin;
        texColorUint = uvec4(combined, 0u, 0u, 0u);
#endif
        #if COL_R == 1
        atomicAdd(localRed[texColorUint.r], 1u);
#endif
        #if COL_G == 1
        atomicAdd(localGreen[texColorUint.g], 1u);
#endif
        #if COL_B == 1
        atomicAdd(localBlue[texColorUint.b], 1u);
#endif
        #if COL_A == 1
        atomicAdd(localAlpha[texColorUint.a], 1u);
#endif
    }
barrier();

for (uint i = 0u; i < HISTSTEPS; i++) {
#if COL_R == 1
        atomicAdd(reds[index + i], localRed[index + i]);
#endif
        #if COL_G == 1
        atomicAdd(greens[index + i], localGreen[index + i]);
#endif
        #if COL_B == 1
        atomicAdd(blues[index + i], localBlue[index + i]);
#endif
        #if COL_A == 1
        atomicAdd(alphas[index + i], localAlpha[index + i]);
#endif
    }
}