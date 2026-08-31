#include <jni.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <vector>

namespace {
constexpr int kSize = 17;
constexpr int kCells = kSize * kSize * kSize;
constexpr float kIdentityWeight = 0.25f;
thread_local std::vector<float> sums(kCells * 4);
thread_local int64_t last_us;

inline int index(int r, int g, int b) { return (b * kSize + g) * kSize + r; }

void fail(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalArgumentException");
    if (type) env->ThrowNew(type, message);
}
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_particlesdevs_photoncamera_ui_camera_views_viewfinder_ColorLutEstimator_nativeEstimate(
        JNIEnv* env, jclass, jobject input_buffer, jobject target_buffer, jint width, jint height) {
    auto* input = static_cast<const uint8_t*>(env->GetDirectBufferAddress(input_buffer));
    auto* target = static_cast<const uint8_t*>(env->GetDirectBufferAddress(target_buffer));
    if (!input || !target || width < 1 || height < 1) {
        fail(env, "LUT estimator requires direct paired RGBA8 buffers");
        return nullptr;
    }
    const auto started = std::chrono::steady_clock::now();
    std::fill(sums.begin(), sums.end(), 0.0f);
    const int pixels = width * height;
    for (int p = 0; p < pixels; ++p) {
        const uint8_t* src = input + static_cast<size_t>(p) * 4;
        const uint8_t* dst = target + static_cast<size_t>(p) * 4;
        const float x = src[0] * ((kSize - 1) / 255.0f);
        const float y = src[1] * ((kSize - 1) / 255.0f);
        const float z = src[2] * ((kSize - 1) / 255.0f);
        const int x0 = std::min(static_cast<int>(x), kSize - 2);
        const int y0 = std::min(static_cast<int>(y), kSize - 2);
        const int z0 = std::min(static_cast<int>(z), kSize - 2);
        const float fx = x - x0, fy = y - y0, fz = z - z0;
        for (int dz = 0; dz < 2; ++dz) for (int dy = 0; dy < 2; ++dy)
            for (int dx = 0; dx < 2; ++dx) {
                const float w = (dx ? fx : 1-fx) * (dy ? fy : 1-fy) * (dz ? fz : 1-fz);
                float* cell = sums.data() + index(x0+dx, y0+dy, z0+dz) * 4;
                cell[0] += w * dst[0] / 255.0f;
                cell[1] += w * dst[1] / 255.0f;
                cell[2] += w * dst[2] / 255.0f;
                cell[3] += w;
            }
    }
    std::vector<float> lut(kCells * 3);
    for (int b = 0; b < kSize; ++b) for (int g = 0; g < kSize; ++g)
        for (int r = 0; r < kSize; ++r) {
            const int i = index(r,g,b); const float* cell = sums.data() + i*4;
            const float weight = cell[3] + kIdentityWeight;
            lut[i*3] = (cell[0] + kIdentityWeight*r/(kSize-1)) / weight;
            lut[i*3+1] = (cell[1] + kIdentityWeight*g/(kSize-1)) / weight;
            lut[i*3+2] = (cell[2] + kIdentityWeight*b/(kSize-1)) / weight;
        }
    last_us = std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - started).count();
    jfloatArray result = env->NewFloatArray(lut.size());
    if (result) env->SetFloatArrayRegion(result, 0, lut.size(), lut.data());
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_particlesdevs_photoncamera_ui_camera_views_viewfinder_ColorLutEstimator_nativeGetLastTimeUs(
        JNIEnv*, jclass) { return last_us; }
