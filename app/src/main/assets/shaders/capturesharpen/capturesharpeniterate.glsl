#version 310 es

precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;

// RawTherapee CaptureDeconvSharpening mapped to one mobile GPU workgroup per
// 32x32 output tile. The RL equations, circular kernels, kernel thresholds,
// tile borders, per-tile corner radius and 1% mask early-out are preserved.
// Capture sharpening: Copyright (c) 2019 Ingo Weyrich; GPL-3.0-or-later.
layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

uniform sampler2D OriginalBuffer;
uniform sampler2D BlendBuffer;
uniform ivec2 size;
uniform float radius;
uniform float cornerBoost;
uniform float epsilon;
uniform int iterations;
uniform int iterationCheck;

#ifndef MAX_KERNEL_RADIUS
#define MAX_KERNEL_RADIUS 2
#endif
#ifndef MIN_KERNEL_RADIUS
#define MIN_KERNEL_RADIUS 2
#endif
#ifndef TILE_BORDER
#define TILE_BORDER 5
#endif
#ifndef VARIABLE_RADIUS
#define VARIABLE_RADIUS 0
#endif

layout(binding = 0, rgba16f) writeonly uniform highp image2D OutputBuffer;

const int LOCAL_SIZE = 8;
const int OUTPUT_SIZE = 32;
const int OUTPUT_COUNT = OUTPUT_SIZE * OUTPUT_SIZE;
const int FULL_SIZE = OUTPUT_SIZE + 2 * TILE_BORDER;
const int FULL_COUNT = FULL_SIZE * FULL_SIZE;
const int STORED_SIZE = FULL_SIZE - 2 * MIN_KERNEL_RADIUS;
const int STORED_COUNT = STORED_SIZE * STORED_SIZE;
#if VARIABLE_RADIUS
const int ESTIMATE_COUNT = STORED_COUNT;
#else
const int ESTIMATE_COUNT = FULL_COUNT;
#endif
const int MAX_KERNEL_WIDTH = 2 * MAX_KERNEL_RADIUS + 1;
const int MAX_KERNEL_COUNT = MAX_KERNEL_WIDTH * MAX_KERNEL_WIDTH;
// RawTherapee Color::RGB2Y coefficients used by capture sharpening.
const vec3 RT_Y = vec3(0.2627, 0.6780, 0.0593);
const float RT_NORMALIZED_EPSILON = 0.00001 / 65535.0;
const float MIN_BLEND = 0.01;

// RT leaves each kernel-width outer ring unchanged (estimate = original,
// ratio = 1). Do not store that ring. This keeps both work arrays float32 and
// fits even the variable-radius 13x13 path in GLES's guaranteed 16 KiB.
shared float estimateTile[ESTIMATE_COUNT];
shared float ratioTile[STORED_COUNT];
shared float kernel[MAX_KERNEL_COUNT];
shared int tileHasDetail;
shared int tileKernelRadius;
shared int tileRunsDeconvolution;
shared int tileStop;

ivec2 clampCoord(ivec2 point) {
    return clamp(point, ivec2(0), size - ivec2(1));
}

int storedIndex(ivec2 point) {
    ivec2 storedPoint = point - MIN_KERNEL_RADIUS;
    return storedPoint.y * STORED_SIZE + storedPoint.x;
}

int fullIndex(ivec2 point) {
    return point.y * FULL_SIZE + point.x;
}

bool isStored(ivec2 point) {
    return all(greaterThanEqual(point, ivec2(MIN_KERNEL_RADIUS)))
        && all(lessThan(point, ivec2(FULL_SIZE - MIN_KERNEL_RADIUS)));
}

float originalLuma(ivec2 point);

float estimateAt(ivec2 point, ivec2 tileOrigin) {
#if VARIABLE_RADIUS
    return isStored(point) ? estimateTile[storedIndex(point)]
        : originalLuma(tileOrigin + point);
#else
    return estimateTile[fullIndex(point)];
#endif
}

float ratioAt(ivec2 point) {
    bool inActiveRegion = all(greaterThanEqual(point, ivec2(tileKernelRadius)))
        && all(lessThan(point, ivec2(FULL_SIZE - tileKernelRadius)));
    return inActiveRegion ? ratioTile[storedIndex(point)] : 1.0;
}

void setRatio(ivec2 point, float value) {
    ratioTile[storedIndex(point)] = value;
}

void multiplyEstimate(ivec2 point, float value) {
#if VARIABLE_RADIUS
    int index = storedIndex(point);
#else
    int index = fullIndex(point);
#endif
    estimateTile[index] = max(estimateTile[index] * value, 0.0);
}

float originalLuma(ivec2 point) {
    return dot(max(texelFetch(OriginalBuffer, clampCoord(point), 0).rgb, 0.0), RT_Y);
}

int radiusForSigma(float sigma) {
    // With corner offset RT never selects the 3x3 path; <= 0.84 is 5x5.
    if (cornerBoost != 0.0) {
        if (sigma <= 0.84) return 2;
    } else if (sigma < 0.6) {
        return 1;
    } else if (sigma <= 0.84) {
        return 2;
    }
    if (sigma <= 1.15) return 3;
    if (sigma <= 1.5) return 4;
    return 6;
}

float supportForRadius(int kernelRadius) {
    return kernelRadius == 1 ? 2.0 : kernelRadius == 2 ? 6.3504
        : kernelRadius == 3 ? 11.9025 : kernelRadius == 4 ? 20.25 : 36.0;
}

int kernelIndex(int x, int y) {
    return (y + MAX_KERNEL_RADIUS) * MAX_KERNEL_WIDTH + x + MAX_KERNEL_RADIUS;
}

float convolveEstimate(ivec2 center, ivec2 tileOrigin) {
#if MAX_KERNEL_RADIUS == 1
    float c11 = kernel[kernelIndex(-1, -1)];
    float c10 = kernel[kernelIndex(-1, 0)];
    float c00 = kernel[kernelIndex(0, 0)];
    return c11 * (estimateAt(center + ivec2(-1, -1), tileOrigin) + estimateAt(center + ivec2(1, -1), tileOrigin)
            + estimateAt(center + ivec2(-1, 1), tileOrigin) + estimateAt(center + ivec2(1, 1), tileOrigin))
        + c10 * (estimateAt(center + ivec2(0, -1), tileOrigin) + estimateAt(center + ivec2(-1, 0), tileOrigin)
            + estimateAt(center + ivec2(1, 0), tileOrigin) + estimateAt(center + ivec2(0, 1), tileOrigin))
        + c00 * estimateAt(center, tileOrigin);
#elif MAX_KERNEL_RADIUS == 2
    float c21 = kernel[kernelIndex(-2, -1)];
    float c20 = kernel[kernelIndex(-2, 0)];
    float c11 = kernel[kernelIndex(-1, -1)];
    float c10 = kernel[kernelIndex(-1, 0)];
    float c00 = kernel[kernelIndex(0, 0)];
    return c21 * (estimateAt(center + ivec2(-2, -1), tileOrigin) + estimateAt(center + ivec2(-2, 1), tileOrigin)
            + estimateAt(center + ivec2(-1, -2), tileOrigin) + estimateAt(center + ivec2(-1, 2), tileOrigin)
            + estimateAt(center + ivec2(1, -2), tileOrigin) + estimateAt(center + ivec2(1, 2), tileOrigin)
            + estimateAt(center + ivec2(2, -1), tileOrigin) + estimateAt(center + ivec2(2, 1), tileOrigin))
        + c20 * (estimateAt(center + ivec2(-2, 0), tileOrigin) + estimateAt(center + ivec2(0, -2), tileOrigin)
            + estimateAt(center + ivec2(0, 2), tileOrigin) + estimateAt(center + ivec2(2, 0), tileOrigin))
        + c11 * (estimateAt(center + ivec2(-1, -1), tileOrigin) + estimateAt(center + ivec2(-1, 1), tileOrigin)
            + estimateAt(center + ivec2(1, -1), tileOrigin) + estimateAt(center + ivec2(1, 1), tileOrigin))
        + c10 * (estimateAt(center + ivec2(-1, 0), tileOrigin) + estimateAt(center + ivec2(0, -1), tileOrigin)
            + estimateAt(center + ivec2(0, 1), tileOrigin) + estimateAt(center + ivec2(1, 0), tileOrigin))
        + c00 * estimateAt(center, tileOrigin);
#else
    float sum = 0.0;
    for (int y = -MAX_KERNEL_RADIUS; y <= MAX_KERNEL_RADIUS; ++y) {
        for (int x = -MAX_KERNEL_RADIUS; x <= MAX_KERNEL_RADIUS; ++x) {
            float weight = kernel[kernelIndex(x, y)];
            if (weight == 0.0) continue;
            sum += estimateAt(center + ivec2(x, y), tileOrigin) * weight;
        }
    }
    return sum;
#endif
}

float convolveRatio(ivec2 center) {
#if MAX_KERNEL_RADIUS == 1
    float c11 = kernel[kernelIndex(-1, -1)];
    float c10 = kernel[kernelIndex(-1, 0)];
    float c00 = kernel[kernelIndex(0, 0)];
    return c11 * (ratioAt(center + ivec2(-1, -1)) + ratioAt(center + ivec2(1, -1))
            + ratioAt(center + ivec2(-1, 1)) + ratioAt(center + ivec2(1, 1)))
        + c10 * (ratioAt(center + ivec2(0, -1)) + ratioAt(center + ivec2(-1, 0))
            + ratioAt(center + ivec2(1, 0)) + ratioAt(center + ivec2(0, 1)))
        + c00 * ratioAt(center);
#elif MAX_KERNEL_RADIUS == 2
    float c21 = kernel[kernelIndex(-2, -1)];
    float c20 = kernel[kernelIndex(-2, 0)];
    float c11 = kernel[kernelIndex(-1, -1)];
    float c10 = kernel[kernelIndex(-1, 0)];
    float c00 = kernel[kernelIndex(0, 0)];
    return c21 * (ratioAt(center + ivec2(-2, -1)) + ratioAt(center + ivec2(-2, 1))
            + ratioAt(center + ivec2(-1, -2)) + ratioAt(center + ivec2(-1, 2))
            + ratioAt(center + ivec2(1, -2)) + ratioAt(center + ivec2(1, 2))
            + ratioAt(center + ivec2(2, -1)) + ratioAt(center + ivec2(2, 1)))
        + c20 * (ratioAt(center + ivec2(-2, 0)) + ratioAt(center + ivec2(0, -2))
            + ratioAt(center + ivec2(0, 2)) + ratioAt(center + ivec2(2, 0)))
        + c11 * (ratioAt(center + ivec2(-1, -1)) + ratioAt(center + ivec2(-1, 1))
            + ratioAt(center + ivec2(1, -1)) + ratioAt(center + ivec2(1, 1)))
        + c10 * (ratioAt(center + ivec2(-1, 0)) + ratioAt(center + ivec2(0, -1))
            + ratioAt(center + ivec2(0, 1)) + ratioAt(center + ivec2(1, 0)))
        + c00 * ratioAt(center);
#else
    float sum = 0.0;
    for (int y = -MAX_KERNEL_RADIUS; y <= MAX_KERNEL_RADIUS; ++y) {
        for (int x = -MAX_KERNEL_RADIUS; x <= MAX_KERNEL_RADIUS; ++x) {
            float weight = kernel[kernelIndex(x, y)];
            if (weight == 0.0) continue;
            sum += ratioAt(center + ivec2(x, y)) * weight;
        }
    }
    return sum;
#endif
}

void writeOriginal(ivec2 blockOrigin, int outputIndex) {
    ivec2 localPoint = ivec2(outputIndex % OUTPUT_SIZE, outputIndex / OUTPUT_SIZE);
    ivec2 point = blockOrigin + localPoint;
    if (any(greaterThanEqual(point, size))) return;
    vec3 original = texelFetch(OriginalBuffer, point, 0).rgb;
    imageStore(OutputBuffer, point, vec4(original, 1.0));
}

void main() {
    int lane = int(gl_LocalInvocationIndex);
    ivec2 blockOrigin = ivec2(gl_WorkGroupID.xy) * OUTPUT_SIZE;

    if (lane == 0) tileHasDetail = 0;
    memoryBarrierShared();
    barrier();

    // RT computes the blend mask before deconvolution, then skips a complete
    // tile when its maximum blend is below one percent.
    for (int i = lane; i < OUTPUT_COUNT; i += LOCAL_SIZE * LOCAL_SIZE) {
        ivec2 localPoint = ivec2(i % OUTPUT_SIZE, i / OUTPUT_SIZE);
        ivec2 point = blockOrigin + localPoint;
        float blend = all(lessThan(point, size))
            ? texelFetch(BlendBuffer, point, 0).r : 0.0;
        if (blend >= MIN_BLEND) atomicOr(tileHasDetail, 1);
    }
    memoryBarrierShared();
    barrier();

    if (tileHasDetail == 0) {
        for (int i = lane; i < OUTPUT_COUNT; i += LOCAL_SIZE * LOCAL_SIZE) {
            writeOriginal(blockOrigin, i);
        }
        return;
    }

    if (lane == 0) {
        // RT corner radius is constant over a processing tile.
        vec2 tileCenter = vec2(blockOrigin) + vec2(float(OUTPUT_SIZE) * 0.5);
        vec2 imageCenter = vec2(size) * 0.5;
        float cornerRadius = min(2.0, radius + cornerBoost);
        float cornerDistance = length(imageCenter);
        float distanceFactor = (cornerRadius - radius) / cornerDistance;
        float sigma = radius + distanceFactor * length(tileCenter - imageCenter);

        tileRunsDeconvolution = (cornerBoost == 0.0 || sigma >= 0.4) ? 1 : 0;
        tileKernelRadius = min(radiusForSigma(sigma), MAX_KERNEL_RADIUS);
        float support2 = supportForRadius(tileKernelRadius);
        float twoSigma2 = 2.0 * max(sigma * sigma, 0.0004);
        float weightSum = 0.0;
        for (int y = -MAX_KERNEL_RADIUS; y <= MAX_KERNEL_RADIUS; ++y) {
            for (int x = -MAX_KERNEL_RADIUS; x <= MAX_KERNEL_RADIUS; ++x) {
                int distance2 = x * x + y * y;
                bool inside = abs(x) <= tileKernelRadius && abs(y) <= tileKernelRadius
                    && float(distance2) <= support2;
                float weight = inside ? exp(-float(distance2) / twoSigma2) : 0.0;
                kernel[kernelIndex(x, y)] = weight;
                weightSum += weight;
            }
        }
        for (int i = 0; i < MAX_KERNEL_COUNT; ++i) kernel[i] /= weightSum;
    }

    ivec2 tileOrigin = blockOrigin - TILE_BORDER;
    for (int i = lane; i < ESTIMATE_COUNT; i += LOCAL_SIZE * LOCAL_SIZE) {
#if VARIABLE_RADIUS
        ivec2 localPoint = ivec2(i % STORED_SIZE, i / STORED_SIZE)
            + MIN_KERNEL_RADIUS;
#else
        ivec2 localPoint = ivec2(i % FULL_SIZE, i / FULL_SIZE);
#endif
        float luminance = originalLuma(tileOrigin + localPoint);
        estimateTile[i] = luminance;
    }
    for (int i = lane; i < STORED_COUNT; i += LOCAL_SIZE * LOCAL_SIZE) {
        ratioTile[i] = 1.0;
    }
    memoryBarrierShared();
    barrier();

    if (tileRunsDeconvolution != 0) {
        int activeWidth = FULL_SIZE - 2 * tileKernelRadius;
        int activeCount = activeWidth * activeWidth;
        for (int iteration = 0; iteration < iterations; ++iteration) {
            // gaussNxNdiv: tmp = original / max(G * estimate, epsilon)
            for (int i = lane; i < activeCount; i += LOCAL_SIZE * LOCAL_SIZE) {
                ivec2 activePoint = ivec2(i % activeWidth, i / activeWidth);
                ivec2 localPoint = activePoint + tileKernelRadius;
                setRatio(localPoint, originalLuma(tileOrigin + localPoint)
                    / max(convolveEstimate(localPoint, tileOrigin), epsilon));
            }
            memoryBarrierShared();
            barrier();

            // gaussNxNmult: estimate *= G * tmp
            for (int i = lane; i < activeCount; i += LOCAL_SIZE * LOCAL_SIZE) {
                ivec2 activePoint = ivec2(i % activeWidth, i / activeWidth);
                ivec2 localPoint = activePoint + tileKernelRadius;
                multiplyEstimate(localPoint, convolveRatio(localPoint));
            }
            memoryBarrierShared();
            barrier();

            // RawTherapee's deconvitercheck stops the entire tile as soon as
            // one output estimate falls below oldLuminance * blend * 0.5.
            if (iterationCheck != 0 && iteration < iterations - 1) {
                if (lane == 0) tileStop = 0;
                memoryBarrierShared();
                barrier();
                for (int i = lane; i < OUTPUT_COUNT; i += LOCAL_SIZE * LOCAL_SIZE) {
                    ivec2 localPoint = ivec2(i % OUTPUT_SIZE, i / OUTPUT_SIZE);
                    ivec2 point = blockOrigin + localPoint;
                    if (all(lessThan(point, size))) {
                        float blend = texelFetch(BlendBuffer, point, 0).r;
                        float threshold = originalLuma(point) * blend * 0.5;
                        if (estimateAt(localPoint + TILE_BORDER, tileOrigin) < threshold) {
                            atomicOr(tileStop, 1);
                        }
                    }
                }
                memoryBarrierShared();
                barrier();
                if (tileStop != 0) break;
            }
        }
    }

    // RT blends only after all RL iterations, then restores RGB by Ynew/Yold.
    for (int i = lane; i < OUTPUT_COUNT; i += LOCAL_SIZE * LOCAL_SIZE) {
        ivec2 localPoint = ivec2(i % OUTPUT_SIZE, i / OUTPUT_SIZE);
        ivec2 point = blockOrigin + localPoint;
        if (any(greaterThanEqual(point, size))) continue;
        vec3 original = texelFetch(OriginalBuffer, point, 0).rgb;
        float oldLuminance = max(dot(max(original, 0.0), RT_Y), RT_NORMALIZED_EPSILON);
        float estimate = estimateAt(localPoint + TILE_BORDER, tileOrigin);
        float blend = texelFetch(BlendBuffer, point, 0).r;
        float newLuminance = mix(oldLuminance, estimate, blend);
        imageStore(OutputBuffer, point,
            vec4(original * (newLuminance / oldLuminance), 1.0));
    }
}
