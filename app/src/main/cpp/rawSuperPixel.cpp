//
// RAW16 -> RGBA8 viewfinder downscaler.
//
// Samples the direct camera buffer in place (row/pixel strides honoured, no
// de-stride copy), combines each 2x2 Bayer tile into one RGB pixel and writes
// gamma-encoded RGBA into a pre-allocated direct output buffer.
//

#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <cmath>

#define OUTPUT_WIDTH 512
#define OUTPUT_HEIGHT 384
#define GAMMA_LUT_SIZE 4096

static uint8_t gGammaLut[GAMMA_LUT_SIZE];
// Benign race: every thread writes the same values.
static bool gGammaInit = false;

static void ensureGammaLut() {
    if (gGammaInit) return;
    for (int i = 0; i < GAMMA_LUT_SIZE; i++) {
        gGammaLut[i] = (uint8_t) lroundf(
                powf((float) i / (float) (GAMMA_LUT_SIZE - 1), 1.0f / 2.2f) * 255.0f);
    }
    gGammaInit = true;
}

static inline uint8_t gammaLut(float x) {
    int i = (int) (x * (float) (GAMMA_LUT_SIZE - 1) + 0.5f);
    if (i < 0) i = 0;
    if (i >= GAMMA_LUT_SIZE) i = GAMMA_LUT_SIZE - 1;
    return gGammaLut[i];
}

extern "C"
JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_processing_live_RawSuperPixel_process(
        JNIEnv *env, jclass clazz, jobject rawBuffer, jobject outBuffer,
        jint rowStride, jint pixelStride,
        jint cropLeft, jint cropTop, jint cropWidth, jint cropHeight,
        jint cfa, jfloatArray blackArr, jint whiteLevel,
        jfloat gainR, jfloat gainG, jfloat gainB) {
    ensureGammaLut();

    const uint8_t *base = static_cast<const uint8_t *>(env->GetDirectBufferAddress(rawBuffer));
    uint8_t *out = static_cast<uint8_t *>(env->GetDirectBufferAddress(outBuffer));
    jfloat black[4];
    env->GetFloatArrayRegion(blackArr, 0, 4, black);
    if (base == nullptr || out == nullptr) return;

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

    float invRange[4];
    for (int i = 0; i < 4; i++) {
        float range = (float) whiteLevel - black[i];
        invRange[i] = 1.0f / (range > 1.0f ? range : 1.0f);
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
            dst[0] = gammaLut(r * gainR);
            dst[1] = gammaLut(g * gainG);
            dst[2] = gammaLut(b * gainB);
            dst[3] = 255;
            dst += 4;
        }
    }
}
