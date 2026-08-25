//
// RAW16 -> RGBA8 viewfinder downscaler.
//
// Samples the direct camera buffer in place (row/pixel strides honoured, no
// de-stride copy), combines each 2x2 Bayer tile into one RGB pixel and writes
// gamma-encoded RGBA into a pre-allocated direct output buffer.
//

#include <jni.h>
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

    // Source column per output column, snapped to the 2x2 grid.
    int xs[OUTPUT_WIDTH];
    for (int ox = 0; ox < OUTPUT_WIDTH; ox++)
        xs[ox] = cropLeft + (((ox * (cropWidth - 2)) / (OUTPUT_WIDTH - 1)) & ~1);

    for (int oy = 0; oy < OUTPUT_HEIGHT; oy++) {
        const int sy = cropTop + (((oy * (cropHeight - 2)) / (OUTPUT_HEIGHT - 1)) & ~1);
        const uint8_t *row0 = base + (size_t) sy * rowStride;
        const uint8_t *row1 = base + (size_t) (sy + 1) * rowStride;
        uint8_t *dst = out + (size_t) oy * OUTPUT_WIDTH * 4;
        for (int ox = 0; ox < OUTPUT_WIDTH; ox++) {
            const size_t o0 = (size_t) xs[ox] * pixelStride;
            const size_t o1 = o0 + pixelStride;
            const float p[4] = {
                    fmaxf(((float) (*(const uint16_t *) (row0 + o0)) - black[0]) * invRange[0], 0.0f),
                    fmaxf(((float) (*(const uint16_t *) (row0 + o1)) - black[1]) * invRange[1], 0.0f),
                    fmaxf(((float) (*(const uint16_t *) (row1 + o0)) - black[2]) * invRange[2], 0.0f),
                    fmaxf(((float) (*(const uint16_t *) (row1 + o1)) - black[3]) * invRange[3], 0.0f),
            };
            const float r = p[ir];
            const float g = (p[ig[0]] + p[ig[1]]) * 0.5f;
            const float b = p[ib];
            dst[0] = gammaLut(r * gainR);
            dst[1] = gammaLut(g * gainG);
            dst[2] = gammaLut(b * gainB);
            dst[3] = 255;
            dst += 4;
        }
    }
}
