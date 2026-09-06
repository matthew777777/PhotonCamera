//
// RAW16 -> RGBA8 viewfinder downscaler.
//
// Samples the direct camera buffer in place (row/pixel strides honoured, no
// de-stride copy) and combines each 2x2 Bayer tile into one RGB pixel. The
// output is linear (black-level subtracted, white-level normalized), then
// neutral-anchored: each channel is divided by its white point and scaled by
// the smallest white point, so all channels share one scale below neutral
// without clamping at the uint8 ceiling and shadows keep their code range.
// StreamedColor multiplies the white point ratios back before the matrix
// chain; gamma encoding and white-balance gains are also applied there.
//

#include <jni.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <mutex>
#include <thread>
#include <vector>
#include <android/log.h>
#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

#define OUTPUT_WIDTH 320
#define OUTPUT_HEIGHT 240
#define HISTOGRAM_BINS 256
// The vectorized histogram scan is tiny; running it inline avoids the
// per-frame thread-spawn overhead that dominated its wall time.
#define HISTOGRAM_THREADS 1
// Output-row bands of the downscale; the main thread runs band 0.
#define DOWNSCALE_THREADS 4

static std::mutex gToneMutex;
static std::array<float, 4> gPreviousToneParameters;
static bool gToneInitialized = false;

// Last histogram scan duration, published by buildToneParameters for the
// caller's timing window.
static std::atomic<int64_t> gHistogramScanUs{0};

// Debug cadence: one summary line per second of wall time, mirroring the
// GL-side "3D LUT ..." window in MainRenderer.
static std::mutex gTimingMutex;
static uint64_t gWindowSuperPixelUs = 0;
static uint64_t gWindowHistogramUs = 0;
static uint32_t gWindowFrames = 0;
static std::chrono::steady_clock::time_point gWindowStarted{};
static bool gWindowValid = false;

static void logSuperPixelTiming(uint64_t superPixelUs, uint64_t histogramUs) {
    std::lock_guard<std::mutex> lock(gTimingMutex);
    const auto now = std::chrono::steady_clock::now();
    if (!gWindowValid) {
        gWindowStarted = now;
        gWindowValid = true;
    }
    gWindowSuperPixelUs += superPixelUs;
    gWindowHistogramUs += histogramUs;
    gWindowFrames++;
    const int64_t elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(
            now - gWindowStarted).count();
    if (elapsed < 1000000000LL) return;
    __android_log_print(ANDROID_LOG_DEBUG, "RawSuperPixel",
            "SuperPixel 320x240 avg %.2f ms, histogram avg %.2f ms, %.1f frames/s",
            (double) gWindowSuperPixelUs / 1000.0 / gWindowFrames,
            (double) gWindowHistogramUs / 1000.0 / gWindowFrames,
            (double) gWindowFrames * 1.0e9 / (double) elapsed);
    gWindowSuperPixelUs = 0;
    gWindowHistogramUs = 0;
    gWindowFrames = 0;
    gWindowStarted = now;
}

static inline uint8_t toLinear8(float x) {
    if (x < 0.0f) x = 0.0f;
    if (x > 1.0f) x = 1.0f;
    return (uint8_t) (x * 255.0f + 0.5f);
}

static inline float sourceForBin(int bin) {
    return exp2f(-12.0f + 14.0f * (float) bin / (HISTOGRAM_BINS - 1));
}

#if defined(__ARM_NEON) || defined(__aarch64__)
// log2 via exponent bits plus a degree-3 Taylor of log2(mantissa) around 1.5.
// Worst-case error ~0.006 stops; the 255-bin statistics only need ~1/500
// absolute accuracy and are temporally smoothed afterwards anyway.
static inline float32x4_t neonLog2(float32x4_t x) {
    const int32x4_t bits = vreinterpretq_s32_f32(x);
    const int32x4_t exponent = vsubq_s32(
            vshrq_n_s32(vandq_s32(bits, vdupq_n_s32(0x7f800000)), 23),
            vdupq_n_s32(127));
    const float32x4_t mantissa = vreinterpretq_f32_s32(vorrq_s32(
            vandq_s32(bits, vdupq_n_s32(0x007fffff)), vdupq_n_s32(0x3f800000)));
    const float32x4_t u = vsubq_f32(mantissa, vdupq_n_f32(1.5f));
    const float32x4_t u2 = vmulq_f32(u, u);
    const float32x4_t poly = vmlaq_f32(
            vmlaq_f32(vmlaq_f32(vdupq_n_f32(0.5849625f), u, vdupq_n_f32(0.9617967f)),
                      u2, vdupq_n_f32(-0.3205994f)),
            vmulq_f32(u2, u), vdupq_n_f32(0.1424885f));
    return vaddq_f32(poly, vcvtq_f32_s32(exponent));
}
#endif

static int percentileBin(const std::array<uint32_t, HISTOGRAM_BINS>& histogram,
                         uint32_t total, float percentile) {
    const uint32_t threshold = std::max(1u, (uint32_t) ceilf(total * percentile));
    uint32_t cumulative = 0;
    for (int i = 0; i < HISTOGRAM_BINS; ++i) {
        cumulative += histogram[i];
        if (cumulative >= threshold) return i;
    }
    return HISTOGRAM_BINS - 1;
}

static void buildToneParameters(const uint8_t* rgba, float* parameters,
                           float gainR, float gainG, float gainB,
                           float exposureCompensation, float compressor) {
    const auto scanStarted = std::chrono::steady_clock::now();
    std::array<std::array<uint32_t, HISTOGRAM_BINS>, HISTOGRAM_THREADS> local{};
    std::array<std::thread, HISTOGRAM_THREADS> workers;
    auto scanBand = [&](int worker) {
            const int beginY = worker * OUTPUT_HEIGHT / HISTOGRAM_THREADS;
            const int endY = (worker + 1) * OUTPUT_HEIGHT / HISTOGRAM_THREADS;
            auto& histogram = local[worker];
#if defined(__ARM_NEON) || defined(__aarch64__)
            const float32x4_t gainRv = vdupq_n_f32(gainR / 255.0f);
            const float32x4_t gainGv = vdupq_n_f32(gainG / 255.0f);
            const float32x4_t gainBv = vdupq_n_f32(gainB / 255.0f);
            const float32x4_t lumaR = vdupq_n_f32(0.2126f);
            const float32x4_t lumaG = vdupq_n_f32(0.7152f);
            const float32x4_t lumaB = vdupq_n_f32(0.0722f);
            const float32x4_t minLuma = vdupq_n_f32(0.000244140625f);
            const float32x4_t domainScale = vdupq_n_f32(1.0f / 14.0f);
            const float32x4_t zeroV = vdupq_n_f32(0.0f);
            const float32x4_t oneV = vdupq_n_f32(1.0f);
            const float32x4_t binScale = vdupq_n_f32((float) (HISTOGRAM_BINS - 1));
            const float32x4_t halfV = vdupq_n_f32(0.5f);
            const uint32x4_t channelMask = vdupq_n_u32(255u);
            for (int y = beginY; y < endY; ++y) {
                const uint8_t* pixel = rgba + (size_t) y * OUTPUT_WIDTH * 4;
                int x = 0;
                for (; x + 4 <= OUTPUT_WIDTH; x += 4, pixel += 16) {
                    const uint32x4_t packed = vld1q_u32(
                            reinterpret_cast<const uint32_t*>(pixel));
                    const float32x4_t r = vmulq_f32(vcvtq_f32_u32(
                            vandq_u32(packed, channelMask)), gainRv);
                    const float32x4_t g = vmulq_f32(vcvtq_f32_u32(
                            vandq_u32(vshrq_n_u32(packed, 8), channelMask)), gainGv);
                    const float32x4_t b = vmulq_f32(vcvtq_f32_u32(
                            vandq_u32(vshrq_n_u32(packed, 16), channelMask)), gainBv);
                    float32x4_t luminance = vmlaq_f32(vmulq_f32(r, lumaR), g, lumaG);
                    luminance = vmlaq_f32(luminance, b, lumaB);
                    luminance = vmaxq_f32(luminance, minLuma);
                    float32x4_t coordinate = vmulq_f32(
                            vaddq_f32(neonLog2(luminance), vdupq_n_f32(12.0f)),
                            domainScale);
                    coordinate = vminq_f32(vmaxq_f32(coordinate, zeroV), oneV);
                    const uint32x4_t bin = vcvtq_u32_f32(
                            vmlaq_f32(halfV, coordinate, binScale));
                    uint32_t bins[4];
                    vst1q_u32(bins, bin);
                    ++histogram[bins[0]];
                    ++histogram[bins[1]];
                    ++histogram[bins[2]];
                    ++histogram[bins[3]];
                }
                for (; x < OUTPUT_WIDTH; ++x, pixel += 4) {
                    const float r = pixel[0] * (gainR / 255.0f);
                    const float g = pixel[1] * (gainG / 255.0f);
                    const float b = pixel[2] * (gainB / 255.0f);
                    const float luminance = std::max(
                            0.2126f * r + 0.7152f * g + 0.0722f * b,
                            0.000244140625f);
                    const float coordinate = std::max(0.0f, std::min(
                            (log2f(luminance) + 12.0f) / 14.0f, 1.0f));
                    const int bin = (int) (coordinate * (HISTOGRAM_BINS - 1) + 0.5f);
                    ++histogram[bin];
                }
            }
#else
            for (int y = beginY; y < endY; ++y) {
                const uint8_t* pixel = rgba + (size_t) y * OUTPUT_WIDTH * 4;
                for (int x = 0; x < OUTPUT_WIDTH; ++x, pixel += 4) {
                    const float r = pixel[0] * (gainR / 255.0f);
                    const float g = pixel[1] * (gainG / 255.0f);
                    const float b = pixel[2] * (gainB / 255.0f);
                    const float luminance = std::max(
                            0.2126f * r + 0.7152f * g + 0.0722f * b,
                            0.000244140625f);
                    const float coordinate = std::max(0.0f, std::min(
                            (log2f(luminance) + 12.0f) / 14.0f, 1.0f));
                    const int bin = (int) (coordinate * (HISTOGRAM_BINS - 1) + 0.5f);
                    ++histogram[bin];
                }
            }
#endif
    };
    for (int worker = 1; worker < HISTOGRAM_THREADS; ++worker)
        workers[worker] = std::thread(scanBand, worker);
    scanBand(0);
    for (int worker = 1; worker < HISTOGRAM_THREADS; ++worker)
        workers[worker].join();
    gHistogramScanUs.store(std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - scanStarted).count(),
            std::memory_order_relaxed);

    std::array<uint32_t, HISTOGRAM_BINS> histogram{};
    for (const auto& localHistogram : local)
        for (int i = 0; i < HISTOGRAM_BINS; ++i) histogram[i] += localHistogram[i];
    const uint32_t total = OUTPUT_WIDTH * OUTPUT_HEIGHT;
    const float black = sourceForBin(percentileBin(histogram, total, 0.005f));
    const float middle = sourceForBin(percentileBin(histogram, total, 0.50f));
    const float meter = sourceForBin(percentileBin(histogram, total, 0.60f));
    const float white = sourceForBin(percentileBin(histogram, total, 0.995f));
    // Camera AE has already selected the sensor exposure. Use the histogram
    // only for a restrained display correction around that result; treating
    // every frame's global median as a photographed 18% card badly lifts
    // dark-heavy scenes and produces the characteristic hazy preview.
    const float meteredSignal = std::max(meter - black, 1.0e-4f);
    const float correctionEv = std::max(-0.75f, std::min(
            log2f(0.18f / meteredSignal), 0.75f));
    float exposure = exp2f(correctionEv
            + std::max(-4.0f, std::min(exposureCompensation, 4.0f)));
    // Keep the 99.5th percentile inside AgX's upper log2 domain. Compressor
    // reserves progressively more highlight headroom without changing the
    // shape of the AgX contrast curve itself.
    const float maxEv = 4.026069f
            - std::max(0.0f, std::min(compressor, 8.0f)) * 0.20f;
    const float maxExposure = exp2f(maxEv) / std::max(white - black, 1.0e-4f);
    exposure = std::max(0.03125f, std::min(exposure, maxExposure));

    const std::array<float, 4> measured = {exposure, black, middle, white};
    std::lock_guard<std::mutex> lock(gToneMutex);
    if (!gToneInitialized) gPreviousToneParameters = measured;
    // Smooth exposure in stops, where a fixed blend has perceptually uniform
    // behavior. Percentiles remain linear and deliberately react slowly to
    // bright objects crossing the small preview histogram.
    parameters[0] = exp2f(0.85f * log2f(std::max(gPreviousToneParameters[0], 1.0e-6f))
            + 0.15f * log2f(std::max(measured[0], 1.0e-6f)));
    for (int i = 1; i < 4; ++i)
        parameters[i] = 0.85f * gPreviousToneParameters[i] + 0.15f * measured[i];
    for (int i = 0; i < 4; ++i) gPreviousToneParameters[i] = parameters[i];
    gToneInitialized = true;
}

#if defined(__ARM_NEON) || defined(__aarch64__)
// One uint16 group of the Bayer downscale, 8 output pixels wide: subtract the
// slot's black level, scale by its inverse range, clamp at zero and
// accumulate into the two 4-lane halves.
static inline void neonAccumulate(float32x4_t &lo, float32x4_t &hi,
                                  const uint16_t *values,
                                  float32x4_t black, float32x4_t range) {
    const uint16x8_t packed = vld1q_u16(values);
    const float32x4_t zero = vdupq_n_f32(0.0f);
    lo = vaddq_f32(lo, vmaxq_f32(vmulq_f32(
            vsubq_f32(vcvtq_f32_u32(vmovl_u16(vget_low_u16(packed))), black),
            range), zero));
    hi = vaddq_f32(hi, vmaxq_f32(vmulq_f32(
            vsubq_f32(vcvtq_f32_u32(vmovl_u16(vget_high_u16(packed))), black),
            range), zero));
}

// Paired-load variant for packed RAW16 (pixelStride 2): the tile's two
// adjacent Bayer pixels arrive as one uint32, halving the load count and
// doubling the bytes consumed per cache line.
static inline void neonAccumulateU32(float32x4_t &lo, float32x4_t &hi,
                                     uint32x4_t vLo, uint32x4_t vHi,
                                     float32x4_t black, float32x4_t range) {
    const float32x4_t zero = vdupq_n_f32(0.0f);
    lo = vaddq_f32(lo, vmaxq_f32(vmulq_f32(
            vsubq_f32(vcvtq_f32_u32(vLo), black), range), zero));
    hi = vaddq_f32(hi, vmaxq_f32(vmulq_f32(
            vsubq_f32(vcvtq_f32_u32(vHi), black), range), zero));
}

// toLinear8 for 8 lanes: clamp to [0,1], *255, +0.5, narrow to bytes.
static inline uint8x8_t neonLinear8(float32x4_t lo, float32x4_t hi) {
    const float32x4_t zero = vdupq_n_f32(0.0f);
    const float32x4_t one = vdupq_n_f32(1.0f);
    const float32x4_t scale = vdupq_n_f32(255.0f);
    const float32x4_t half = vdupq_n_f32(0.5f);
    const uint16x8_t narrowed = vcombine_u16(
            vmovn_u32(vcvtq_u32_f32(vmlaq_f32(half, vmaxq_f32(vminq_f32(lo, one), zero), scale))),
            vmovn_u32(vcvtq_u32_f32(vmlaq_f32(half, vmaxq_f32(vminq_f32(hi, one), zero), scale))));
    return vmovn_u16(narrowed);
}
#endif

extern "C"
JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_processing_live_RawSuperPixel_process(
        JNIEnv *env, jclass clazz, jobject rawBuffer, jobject outBuffer,
        jobject toneCurveBuffer,
        jint rowStride, jint pixelStride,
        jint cropLeft, jint cropTop, jint cropWidth, jint cropHeight,
        jint cfa, jfloatArray blackArr, jint whiteLevel,
        jfloat whiteR, jfloat whiteG, jfloat whiteB,
        jfloat gainR, jfloat gainG, jfloat gainB,
        jfloat exposureCompensation, jfloat compressor) {
    const uint8_t *base = static_cast<const uint8_t *>(env->GetDirectBufferAddress(rawBuffer));
    uint8_t *out = static_cast<uint8_t *>(env->GetDirectBufferAddress(outBuffer));
    float *toneCurve = static_cast<float *>(env->GetDirectBufferAddress(toneCurveBuffer));
    jfloat black[4];
    env->GetFloatArrayRegion(blackArr, 0, 4, black);
    if (base == nullptr || out == nullptr || toneCurve == nullptr) return;

    // Bayer offset: position of red inside the 2x2 tile, derived from the CFA
    // arrangement without per-pixel branching. Blue sits diagonally opposite,
    // the remaining two slots are green.
    const int rx = cfa & 1;
    const int ry = (cfa >> 1) & 1;
    const int ir = ry * 2 + rx;
    const int ib = (1 - ry) * 2 + (1 - rx);
    int ig[2];
    int n = 0;
    for (int i = 0; i < 4; i++)
        if (i != ir && i != ib) ig[n++] = i;

    // Neutral-anchored encoding: divide by the channel's white point, scaled
    // by the smallest one so the largest stored value stays <= 1.0 and
    // toLinear8 never clamps. Invalid white points (unfilled Parameters)
    // fall back to identity.
    if (!(whiteR > 0.0f)) whiteR = 1.0f;
    if (!(whiteG > 0.0f)) whiteG = 1.0f;
    if (!(whiteB > 0.0f)) whiteB = 1.0f;
    const float minWhite = std::min(whiteR, std::min(whiteG, whiteB));
    float whiteBySlot[4];
    for (int i = 0; i < 4; i++)
        whiteBySlot[i] = i == ir ? whiteR : i == ib ? whiteB : whiteG;

    float invRange[4];
    for (int i = 0; i < 4; i++) {
        float range = (float) whiteLevel - black[i];
        range = range > 1.0f ? range : 1.0f;
        invRange[i] = (minWhite / whiteBySlot[i]) / range;
    }

    const auto downscaleStarted = std::chrono::steady_clock::now();
    const int tiles_x = cropWidth / 2;
    const int tiles_y = cropHeight / 2;
#if defined(__ARM_NEON) || defined(__aarch64__)
    // The NEON loop always sums four stratified samples per output pixel,
    // duplicating a sample when the tile run holds only one: summing a tile
    // twice under the fixed 1/4 weight reproduces the scalar single-sample
    // average exactly, so both paths compute the same pixels. Byte offsets of
    // each output column's two x-sample tiles are hoisted out of the loop.
    int32_t tileOffsetX[2][OUTPUT_WIDTH];
    for (int ox = 0; ox < OUTPUT_WIDTH; ox++) {
        const int tile_x0 = ox * tiles_x / OUTPUT_WIDTH;
        const int tile_x1 = std::max(tile_x0 + 1, (ox + 1) * tiles_x / OUTPUT_WIDTH);
        const int tile_count_x = tile_x1 - tile_x0;
        const int samples_x = std::min(tile_count_x, 2);
        for (int s = 0; s < 2; s++) {
            const int sample_x = samples_x == 2 ? s : 0;
            const int tile_x = tile_x0
                    + ((2 * sample_x + 1) * tile_count_x) / (2 * samples_x);
            tileOffsetX[s][ox] = (int32_t) ((size_t) (cropLeft + tile_x * 2) * pixelStride);
        }
    }
    const float32x4_t blackV[4] = {
            vdupq_n_f32(black[0]), vdupq_n_f32(black[1]),
            vdupq_n_f32(black[2]), vdupq_n_f32(black[3])};
    const float32x4_t rangeV[4] = {
            vdupq_n_f32(invRange[0]), vdupq_n_f32(invRange[1]),
            vdupq_n_f32(invRange[2]), vdupq_n_f32(invRange[3])};
    const float32x4_t quarterV = vdupq_n_f32(0.25f);
    const float32x4_t eighthV = vdupq_n_f32(0.125f);
    const uint8x8_t alphaV = vdup_n_u8(255);
    const bool pairedLoads = pixelStride == 2;
    const uint32x4_t halfMask = vdupq_n_u32(0xffffu);
#endif
    // Output-row bands are independent - shared tables are read-only and
    // output rows are disjoint - so the downscale fans out over threads.
    auto renderRows = [&](int yBegin, int yEnd) {
    for (int oy = yBegin; oy < yEnd; oy++) {
        const int tile_y0 = oy * tiles_y / OUTPUT_HEIGHT;
        const int tile_y1 = std::max(tile_y0 + 1, (oy + 1) * tiles_y / OUTPUT_HEIGHT);
        const int tile_count_y = tile_y1 - tile_y0;
        // Four stratified Bayer tiles per output pixel provide a useful box
        // prefilter without spending the camera callback budget on 16 tiles.
        const int samples_y = std::min(tile_count_y, 2);
        const uint8_t *sampleRow[2];
        for (int s = 0; s < 2; s++) {
            const int sample_y = samples_y == 2 ? s : 0;
            const int tile_y = tile_y0
                    + ((2 * sample_y + 1) * tile_count_y) / (2 * samples_y);
            sampleRow[s] = base + (size_t) (cropTop + tile_y * 2) * rowStride;
        }
        uint8_t *dst = out + (size_t) oy * OUTPUT_WIDTH * 4;
        int ox = 0;
#if defined(__ARM_NEON) || defined(__aarch64__)
        uint16_t loaded[8];
        uint32_t pairs[8];
        for (; ox + 8 <= OUTPUT_WIDTH; ox += 8) {
            float32x4_t sumLo[4] = {vdupq_n_f32(0.0f), vdupq_n_f32(0.0f),
                                    vdupq_n_f32(0.0f), vdupq_n_f32(0.0f)};
            float32x4_t sumHi[4] = {vdupq_n_f32(0.0f), vdupq_n_f32(0.0f),
                                    vdupq_n_f32(0.0f), vdupq_n_f32(0.0f)};
            for (int sy = 0; sy < 2; sy++) {
                const uint8_t *row0 = sampleRow[sy];
                const uint8_t *row1 = row0 + rowStride;
                for (int sx = 0; sx < 2; sx++) {
                    const int32_t *offsets = tileOffsetX[sx];
                    // Slot layout inside a 2x2 tile: 0/1 top row, 2/3 bottom.
                    // Packed RAW16 makes the tile's two Bayer pixels one
                    // aligned uint32; other strides keep the two-load form.
                    if (pairedLoads) {
                        if (ox + 16 <= OUTPUT_WIDTH) {
                            __builtin_prefetch(row0 + offsets[ox + 8]);
                            __builtin_prefetch(row1 + offsets[ox + 8]);
                        }
                        for (int row = 0; row < 2; row++) {
                            const uint8_t *source = row == 0 ? row0 : row1;
                            for (int i = 0; i < 8; ++i)
                                pairs[i] = *(const uint32_t *) (source + offsets[ox + i]);
                            const uint32x4_t packLow = vld1q_u32(pairs);
                            const uint32x4_t packHigh = vld1q_u32(pairs + 4);
                            neonAccumulateU32(sumLo[row * 2], sumHi[row * 2],
                                              vandq_u32(packLow, halfMask),
                                              vandq_u32(packHigh, halfMask),
                                              blackV[row * 2], rangeV[row * 2]);
                            neonAccumulateU32(sumLo[row * 2 + 1], sumHi[row * 2 + 1],
                                              vshrq_n_u32(packLow, 16),
                                              vshrq_n_u32(packHigh, 16),
                                              blackV[row * 2 + 1], rangeV[row * 2 + 1]);
                        }
                    } else {
                        for (int row = 0; row < 2; row++) {
                            const uint8_t *source = row == 0 ? row0 : row1;
                            for (int i = 0; i < 8; ++i)
                                loaded[i] = *(const uint16_t *) (source + offsets[ox + i]);
                            neonAccumulate(sumLo[row * 2], sumHi[row * 2], loaded,
                                           blackV[row * 2], rangeV[row * 2]);
                            for (int i = 0; i < 8; ++i)
                                loaded[i] = *(const uint16_t *) (source + offsets[ox + i] + pixelStride);
                            neonAccumulate(sumLo[row * 2 + 1], sumHi[row * 2 + 1], loaded,
                                           blackV[row * 2 + 1], rangeV[row * 2 + 1]);
                        }
                    }
                }
            }
            const float32x4_t rLo = vmulq_f32(sumLo[ir], quarterV);
            const float32x4_t rHi = vmulq_f32(sumHi[ir], quarterV);
            const float32x4_t gLo = vmulq_f32(vaddq_f32(sumLo[ig[0]], sumLo[ig[1]]), eighthV);
            const float32x4_t gHi = vmulq_f32(vaddq_f32(sumHi[ig[0]], sumHi[ig[1]]), eighthV);
            const float32x4_t bLo = vmulq_f32(sumLo[ib], quarterV);
            const float32x4_t bHi = vmulq_f32(sumHi[ib], quarterV);
            uint8x8x4_t rgba;
            rgba.val[0] = neonLinear8(rLo, rHi);
            rgba.val[1] = neonLinear8(gLo, gHi);
            rgba.val[2] = neonLinear8(bLo, bHi);
            rgba.val[3] = alphaV;
            vst4_u8(dst + (size_t) ox * 4, rgba);
        }
#endif
        for (; ox < OUTPUT_WIDTH; ox++) {
            const int tile_x0 = ox * tiles_x / OUTPUT_WIDTH;
            const int tile_x1 = std::max(tile_x0 + 1, (ox + 1) * tiles_x / OUTPUT_WIDTH);
            const int tile_count_x = tile_x1 - tile_x0;
            const int samples_x = std::min(tile_count_x, 2);
            float sums[4] = {};
            for (int sample_y = 0; sample_y < samples_y; sample_y++) {
                const int tile_y = tile_y0
                        + ((2 * sample_y + 1) * tile_count_y) / (2 * samples_y);
                const int sy = cropTop + tile_y * 2;
                const uint8_t *row0 = base + (size_t) sy * rowStride;
                const uint8_t *row1 = row0 + rowStride;
                for (int sample_x = 0; sample_x < samples_x; sample_x++) {
                    const int tile_x = tile_x0
                            + ((2 * sample_x + 1) * tile_count_x) / (2 * samples_x);
                    const size_t o0 = (size_t) (cropLeft + tile_x * 2) * pixelStride;
                    const size_t o1 = o0 + pixelStride;
                    sums[0] += fmaxf(((float) (*(const uint16_t *) (row0 + o0)) - black[0]) * invRange[0], 0.0f);
                    sums[1] += fmaxf(((float) (*(const uint16_t *) (row0 + o1)) - black[1]) * invRange[1], 0.0f);
                    sums[2] += fmaxf(((float) (*(const uint16_t *) (row1 + o0)) - black[2]) * invRange[2], 0.0f);
                    sums[3] += fmaxf(((float) (*(const uint16_t *) (row1 + o1)) - black[3]) * invRange[3], 0.0f);
                }
            }
            const float inv_samples = 1.0f / (samples_x * samples_y);
            const float r = sums[ir] * inv_samples;
            const float g = (sums[ig[0]] + sums[ig[1]]) * (0.5f * inv_samples);
            const float b = sums[ib] * inv_samples;
            dst[0] = toLinear8(r);
            dst[1] = toLinear8(g);
            dst[2] = toLinear8(b);
            dst[3] = 255;
            dst += 4;
        }
    }
    };
    std::thread downWorkers[DOWNSCALE_THREADS > 1 ? DOWNSCALE_THREADS - 1 : 1];
    for (int worker = 1; worker < DOWNSCALE_THREADS; ++worker)
        downWorkers[worker - 1] = std::thread(renderRows,
                worker * OUTPUT_HEIGHT / DOWNSCALE_THREADS,
                (worker + 1) * OUTPUT_HEIGHT / DOWNSCALE_THREADS);
    renderRows(0, OUTPUT_HEIGHT / DOWNSCALE_THREADS);
    for (auto& worker : downWorkers) if (worker.joinable()) worker.join();
    // The stored pixels carry the minWhite/whitePoint encoding, so undo it in
    // the histogram gains to keep the curve anchored to the same luminances
    // the shader sees after reconstructing brightness.
    buildToneParameters(out, toneCurve,
                   gainR * whiteR / minWhite,
                   gainG * whiteG / minWhite,
                   gainB * whiteB / minWhite,
                   exposureCompensation, compressor);
    logSuperPixelTiming(
            (uint64_t) std::chrono::duration_cast<std::chrono::microseconds>(
                    std::chrono::steady_clock::now() - downscaleStarted).count(),
            (uint64_t) gHistogramScanUs.load(std::memory_order_relaxed));
}

namespace {
inline float clamp01(float v) { return std::max(0.0f, std::min(v, 1.0f)); }

constexpr int kCurveLutSize = 4096;
struct PreviewCurveLuts {
    std::array<float, kCurveLutSize + 1> agx;
    std::array<float, kCurveLutSize + 1> display;
    std::array<float, kCurveLutSize + 1> squareRoot;
    std::array<float, kCurveLutSize + 1> contrast;
};

inline void mul3(const float *m, float &r, float &g, float &b) {
    const float nr = m[0] * r + m[1] * g + m[2] * b;
    const float ng = m[3] * r + m[4] * g + m[5] * b;
    const float nb = m[6] * r + m[7] * g + m[8] * b;
    r = nr; g = ng; b = nb;
}

inline float agx_contrast(float x) {
    const float x2 = x * x;
    const float x4 = x2 * x2;
    return 15.5f * x4 * x2 - 40.14f * x4 * x + 31.96f * x4
           - 6.868f * x2 * x + 0.4298f * x2 + 0.1191f * x - 0.00232f;
}

inline float linear_to_srgb(float x) {
    return x < 0.0031308f ? 12.92f * x
                         : 1.055f * std::pow(std::max(x, 0.0f), 1.0f / 2.4f) - 0.055f;
}

const PreviewCurveLuts& preview_curve_luts() {
    static const PreviewCurveLuts luts = [] {
        PreviewCurveLuts result{};
        for (int i = 0; i <= kCurveLutSize; ++i) {
            const float unit = static_cast<float>(i) / kCurveLutSize;
            const float agxInput = unit * 16.5f;
            result.agx[i] = agx_contrast(clamp01(
                    (std::log2(std::max(agxInput, 1e-10f)) + 12.47393f) / 16.5f));
            result.display[i] = linear_to_srgb(std::pow(unit, 2.2f));
            result.squareRoot[i] = std::sqrt(unit);
            const float contrastInput = unit * 7.0f - 3.0f;
            result.contrast[i] = .5f + .5f * std::sin(
                    (2.0f * contrastInput - 1.0f) * 1.57079632679f);
        }
        return result;
    }();
    return luts;
}

inline float lookup(const std::array<float, kCurveLutSize + 1>& table,
                    float value, float minimum, float maximum) {
    const float position = clamp01((value - minimum) / (maximum - minimum)) * kCurveLutSize;
    const int index = std::min(static_cast<int>(position), kCurveLutSize - 1);
    const float fraction = position - index;
    return table[index] + (table[index + 1] - table[index]) * fraction;
}

struct GainAxisSample { int first, second; float fraction; };

struct PreviewCpuWorkspace {
    std::vector<float> gainMap;
    std::vector<float> shadingRgb;
    uint64_t shadingKey = 0;
    int shadingWidth = 0;
    int shadingHeight = 0;
};

thread_local PreviewCpuWorkspace previewCpuWorkspace;

inline uint64_t hash_bytes(uint64_t hash, const void *data, size_t size) {
    const auto *bytes = static_cast<const uint8_t *>(data);
    for (size_t i = 0; i < size; ++i) {
        hash ^= bytes[i];
        hash *= 1099511628211ULL;
    }
    return hash;
}

inline void gain_sample_rgba(const float *map, int width,
                             const GainAxisSample& xs, const GainAxisSample& ys,
                             float *rgba) {
    const float *p00 = map + (ys.first * width + xs.first) * 4;
    const float *p10 = map + (ys.first * width + xs.second) * 4;
    const float *p01 = map + (ys.second * width + xs.first) * 4;
    const float *p11 = map + (ys.second * width + xs.second) * 4;
    for (int channel = 0; channel < 4; ++channel) {
        const float top = p00[channel] + (p10[channel] - p00[channel]) * xs.fraction;
        const float bottom = p01[channel] + (p11[channel] - p01[channel]) * xs.fraction;
        rgba[channel] = top + (bottom - top) * ys.fraction;
    }
}
}

extern "C"
JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_processing_live_RawSuperPixel_postProcessCpuNative(
        JNIEnv *env, jclass, jobject pixelBuffer, jobject toneBuffer, jint width, jint height,
        jfloatArray sensorArray, jfloatArray outputArray, jfloatArray neutralArray,
        jfloatArray whiteArray, jfloatArray gainArray, jint gainWidth, jint gainHeight,
        jfloat cropScaleX, jfloat cropScaleY, jfloat saturation, jfloat contrast,
        jfloat shadows) {
    auto *pixels = static_cast<uint8_t *>(env->GetDirectBufferAddress(pixelBuffer));
    auto *tone = static_cast<float *>(env->GetDirectBufferAddress(toneBuffer));
    if (!pixels || !tone || width <= 0 || height <= 0 || gainWidth <= 0 || gainHeight <= 0) return;
    float sensor[9], output[9], neutral[3], white[3];
    env->GetFloatArrayRegion(sensorArray, 0, 9, sensor);
    env->GetFloatArrayRegion(outputArray, 0, 9, output);
    env->GetFloatArrayRegion(neutralArray, 0, 3, neutral);
    env->GetFloatArrayRegion(whiteArray, 0, 3, white);
    PreviewCpuWorkspace& workspace = previewCpuWorkspace;
    const bool gainChanged = gainArray != nullptr;
    jsize gainCount = 0;
    if (gainChanged) {
        gainCount = env->GetArrayLength(gainArray);
        workspace.gainMap.resize(static_cast<size_t>(gainCount));
        env->GetFloatArrayRegion(gainArray, 0, gainCount, workspace.gainMap.data());
        if (gainCount < gainWidth * gainHeight * 4) return;
    }
    // GLSL evaluates output * sensor * rgb. Fuse that constant chain once per
    // frame instead of performing two 3x3 multiplies for every pixel.
    float color[9];
    for (int row = 0; row < 3; ++row) for (int column = 0; column < 3; ++column) {
        color[row * 3 + column] = 0.0f;
        for (int k = 0; k < 3; ++k)
            color[row * 3 + column] += output[row * 3 + k] * sensor[k * 3 + column];
    }
    uint64_t shadingKey = workspace.shadingKey;
    if (gainChanged) {
        shadingKey = 1469598103934665603ULL;
        shadingKey = hash_bytes(shadingKey, workspace.gainMap.data(),
                                static_cast<size_t>(gainCount) * sizeof(float));
        const int shadingConfig[] = {width, height, gainWidth, gainHeight};
        shadingKey = hash_bytes(shadingKey, shadingConfig, sizeof(shadingConfig));
        shadingKey = hash_bytes(shadingKey, &cropScaleX, sizeof(cropScaleX));
        shadingKey = hash_bytes(shadingKey, &cropScaleY, sizeof(cropScaleY));
    }
    if (gainChanged || workspace.shadingRgb.empty()
            || workspace.shadingWidth != width || workspace.shadingHeight != height) {
        if (workspace.gainMap.size() < static_cast<size_t>(gainWidth * gainHeight * 4)) return;
        std::vector<GainAxisSample> gainX(static_cast<size_t>(width));
        std::vector<GainAxisSample> gainY(static_cast<size_t>(height));
        auto makeAxis = [](int position, int imageSize, int mapSize, float cropScale) {
            const float uv = ((position + 0.5f) / imageSize) * cropScale
                             + (1.0f - cropScale) * 0.5f;
            const float mapped = clamp01(uv) * mapSize - 0.5f;
            const float floorValue = std::floor(mapped);
            const int first = std::max(0, std::min(static_cast<int>(floorValue), mapSize - 1));
            return GainAxisSample{first, std::min(first + 1, mapSize - 1),
                                  clamp01(mapped - floorValue)};
        };
        for (int x = 0; x < width; ++x) gainX[x] = makeAxis(x, width, gainWidth, cropScaleX);
        for (int y = 0; y < height; ++y) gainY[y] = makeAxis(y, height, gainHeight, cropScaleY);
        workspace.shadingRgb.resize(static_cast<size_t>(width) * height * 3);
        for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) {
            float shading[4];
            gain_sample_rgba(workspace.gainMap.data(), gainWidth, gainX[x], gainY[y], shading);
            const float green = 0.5f * (shading[1] + shading[2]);
            const float mean = std::max((shading[0] + green + shading[3]) / 3.0f, 1.0e-6f);
            float *cached = workspace.shadingRgb.data()
                    + (static_cast<size_t>(y) * width + x) * 3;
            cached[0] = shading[0] / mean;
            cached[1] = green / mean;
            cached[2] = shading[3] / mean;
        }
        workspace.shadingKey = shadingKey;
        workspace.shadingWidth = width;
        workspace.shadingHeight = height;
    }
    const PreviewCurveLuts& luts = preview_curve_luts();
    const float exposure = tone[0];
    const float sat = std::max(0.0f, std::min(saturation, 3.0f));
    const float shadowMix = std::min(std::fabs(shadows) * 0.5f, 1.0f);
    const int workers = height >= 64 ? 2 : 1;
    auto band = [&](int yBegin, int yEnd) {
        for (int y = yBegin; y < yEnd; ++y) {
            for (int xBase = 0; xBase < width; xBase += 4) {
                float decoded[4][3]{};
                const int lanes = std::min(4, width - xBase);
#if defined(__ARM_NEON) || defined(__aarch64__)
                if (lanes == 4) {
                    const uint32x4_t packed = vld1q_u32(reinterpret_cast<const uint32_t *>(
                            pixels + (static_cast<size_t>(y) * width + xBase) * 4));
                    const uint32x4_t mask = vdupq_n_u32(255u);
                    const float32x4_t scale = vdupq_n_f32(1.0f / 255.0f);
                    const float32x4_t vr = vmulq_f32(vcvtq_f32_u32(vandq_u32(packed, mask)), scale);
                    const float32x4_t vg = vmulq_f32(vcvtq_f32_u32(vandq_u32(vshrq_n_u32(packed, 8), mask)), scale);
                    const float32x4_t vb = vmulq_f32(vcvtq_f32_u32(vandq_u32(vshrq_n_u32(packed, 16), mask)), scale);
                    float rs[4], gs[4], bs[4];
                    vst1q_f32(rs, vr); vst1q_f32(gs, vg); vst1q_f32(bs, vb);
                    for (int i = 0; i < 4; ++i) { decoded[i][0] = rs[i]; decoded[i][1] = gs[i]; decoded[i][2] = bs[i]; }
                } else
#endif
                for (int i = 0; i < lanes; ++i) {
                    const uint8_t *p = pixels + (static_cast<size_t>(y) * width + xBase + i) * 4;
                    decoded[i][0] = p[0] / 255.0f; decoded[i][1] = p[1] / 255.0f; decoded[i][2] = p[2] / 255.0f;
                }
                for (int lane = 0; lane < lanes; ++lane) {
                    const int x = xBase + lane;
                    float r = decoded[lane][0] * white[0];
                    float g = decoded[lane][1] * white[1];
                    float b = decoded[lane][2] * white[2];
                    const float *shading = workspace.shadingRgb.data()
                            + (static_cast<size_t>(y) * width + x) * 3;
                    r *= shading[0] * neutral[0];
                    g *= shading[1] * neutral[1];
                    b *= shading[2] * neutral[2];
                    mul3(color, r, g, b);
                    r = std::max(r, 0.0f) * exposure; g = std::max(g, 0.0f) * exposure; b = std::max(b, 0.0f) * exposure;
                    // AgX matrices are written in GLSL column-major order.
                    float ar = .8566271533f*r + .0951212405f*g + .0482516061f*b;
                    float ag = .1373189729f*r + .7612419906f*g + .1014390365f*b;
                    float ab = .1118982130f*r + .0767994186f*g + .8113023684f*b;
                    ar = lookup(luts.agx, ar, 0.0f, 16.5f);
                    ag = lookup(luts.agx, ag, 0.0f, 16.5f);
                    ab = lookup(luts.agx, ab, 0.0f, 16.5f);
                    r = 1.1271005818f*ar - .1106066431f*ag - .0164939387f*ab;
                    g = -.1413297635f*ar + 1.1578237022f*ag - .0164939387f*ab;
                    b = -.1413297635f*ar - .1106066431f*ag + 1.2519364066f*ab;
                    r = lookup(luts.display, clamp01(r), 0.0f, 1.0f);
                    g = lookup(luts.display, clamp01(g), 0.0f, 1.0f);
                    b = lookup(luts.display, clamp01(b), 0.0f, 1.0f);
                    float luma = .299f*r + .587f*g + .114f*b;
                    if (shadowMix > 0.0f && luma > 1e-6f) {
                        const float mapped = shadows >= 0.0f
                                ? lookup(luts.squareRoot, luma, 0.0f, 1.0f) : luma*luma;
                        const float scale = (luma + (mapped-luma)*shadowMix) / luma;
                        r *= scale; g *= scale; b *= scale;
                    }
                    luma = .299f*r + .587f*g + .114f*b;
                    r = luma + (r-luma)*sat; g = luma + (g-luma)*sat; b = luma + (b-luma)*sat;
                    const float weight = clamp01(contrast + shadows + (contrast-(contrast+shadows))*clamp01(.299f*r+.587f*g+.114f*b));
                    const float cr = lookup(luts.contrast, r, -3.0f, 4.0f);
                    const float cg = lookup(luts.contrast, g, -3.0f, 4.0f);
                    const float cb = lookup(luts.contrast, b, -3.0f, 4.0f);
                    r += (cr-r)*weight; g += (cg-g)*weight; b += (cb-b)*weight;
                    uint8_t *dst = pixels + (static_cast<size_t>(y)*width+x)*4;
                    dst[0] = static_cast<uint8_t>(std::lround(clamp01(r)*255.0f));
                    dst[1] = static_cast<uint8_t>(std::lround(clamp01(g)*255.0f));
                    dst[2] = static_cast<uint8_t>(std::lround(clamp01(b)*255.0f)); dst[3] = 255;
                }
            }
        }
    };
    std::thread second;
    if (workers == 2) second = std::thread(band, height / 2, height);
    band(0, workers == 2 ? height / 2 : height);
    if (second.joinable()) second.join();
}
