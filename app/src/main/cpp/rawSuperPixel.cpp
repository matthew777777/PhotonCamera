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
#include <cstdint>
#include <cmath>
#include <mutex>
#include <thread>

#define OUTPUT_WIDTH 512
#define OUTPUT_HEIGHT 384
#define HISTOGRAM_BINS 256
#define HISTOGRAM_THREADS 4

static std::mutex gCurveMutex;
static std::array<float, HISTOGRAM_BINS> gPreviousCurve;
static bool gCurveInitialized = false;

static inline uint8_t toLinear8(float x) {
    if (x < 0.0f) x = 0.0f;
    if (x > 1.0f) x = 1.0f;
    return (uint8_t) (x * 255.0f + 0.5f);
}

static inline float sourceForBin(int bin) {
    return exp2f(-12.0f + 14.0f * (float) bin / (HISTOGRAM_BINS - 1));
}

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

static void buildToneCurve(const uint8_t* rgba, float* curve,
                           float gainR, float gainG, float gainB,
                           float exposureCompensation, float compressor) {
    std::array<std::array<uint32_t, HISTOGRAM_BINS>, HISTOGRAM_THREADS> local{};
    std::array<std::thread, HISTOGRAM_THREADS> workers;
    for (int worker = 0; worker < HISTOGRAM_THREADS; ++worker) {
        workers[worker] = std::thread([=, &local]() {
            const int beginY = worker * OUTPUT_HEIGHT / HISTOGRAM_THREADS;
            const int endY = (worker + 1) * OUTPUT_HEIGHT / HISTOGRAM_THREADS;
            auto& histogram = local[worker];
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
        });
    }
    for (auto& worker : workers) worker.join();

    std::array<uint32_t, HISTOGRAM_BINS> histogram{};
    for (const auto& localHistogram : local)
        for (int i = 0; i < HISTOGRAM_BINS; ++i) histogram[i] += localHistogram[i];
    const uint32_t total = OUTPUT_WIDTH * OUTPUT_HEIGHT;
    const float black = sourceForBin(percentileBin(histogram, total, 0.005f));
    const float middle = sourceForBin(percentileBin(histogram, total, 0.50f));
    const float white = sourceForBin(percentileBin(histogram, total, 0.995f));
    float exposure = 0.18f / std::max(middle - black, 1.0e-4f);
    exposure *= exp2f(std::max(-4.0f, std::min(exposureCompensation, 4.0f)));
    float whiteMapped = std::max((white - black) * exposure, 1.0f);
    whiteMapped *= 1.0f + std::max(compressor, 0.0f) * 0.20f;

    std::lock_guard<std::mutex> lock(gCurveMutex);
    for (int i = 0; i < HISTOGRAM_BINS; ++i) {
        const float source = sourceForBin(i);
        const float x = std::max(source - black, 0.0f) * exposure;
        const float mapped = std::max(0.0f, std::min(
                x * (1.0f + x / (whiteMapped * whiteMapped)) / (1.0f + x), 1.0f));
        if (!gCurveInitialized) gPreviousCurve[i] = std::min(source, 1.0f);
        const float smoothed = gPreviousCurve[i] * 0.85f + mapped * 0.15f;
        curve[i] = smoothed;
        gPreviousCurve[i] = smoothed;
    }
    gCurveInitialized = true;
}

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

    const int tiles_x = cropWidth / 2;
    const int tiles_y = cropHeight / 2;
    for (int oy = 0; oy < OUTPUT_HEIGHT; oy++) {
        const int tile_y0 = oy * tiles_y / OUTPUT_HEIGHT;
        const int tile_y1 = std::max(tile_y0 + 1, (oy + 1) * tiles_y / OUTPUT_HEIGHT);
        const int tile_count_y = tile_y1 - tile_y0;
        // Four stratified Bayer tiles per output pixel provide a useful box
        // prefilter without spending the camera callback budget on 16 tiles.
        const int samples_y = std::min(tile_count_y, 2);
        uint8_t *dst = out + (size_t) oy * OUTPUT_WIDTH * 4;
        for (int ox = 0; ox < OUTPUT_WIDTH; ox++) {
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
    // The stored pixels carry the minWhite/whitePoint encoding, so undo it in
    // the histogram gains to keep the curve anchored to the same luminances
    // the shader sees after reconstructing brightness.
    buildToneCurve(out, toneCurve,
                   gainR * whiteR / minWhite,
                   gainG * whiteG / minWhite,
                   gainB * whiteB / minWhite,
                   exposureCompensation, compressor);
}
