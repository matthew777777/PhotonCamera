#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <vector>
#include <chrono>

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "deps/stb_image_write.h"

int stbiw_jpg_force_444 = 0;

#define LOG_TAG "JpegEncoder"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_processing_JpegEncoder_nativeEncodeJpeg(
        JNIEnv* env, jclass, jobject bitmap, jstring path, jint quality, jboolean use444) {

    auto startTime = std::chrono::high_resolution_clock::now();

    AndroidBitmapInfo info;
    void* pixels = nullptr;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LOGE("AndroidBitmap_getInfo failed");
        return JNI_FALSE;
    }

    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Unsupported bitmap format: %d. Expected RGBA_8888", info.format);
        return JNI_FALSE;
    }

    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGE("AndroidBitmap_lockPixels failed");
        return JNI_FALSE;
    }

    const char* pathStr = env->GetStringUTFChars(path, nullptr);

    int width = info.width;
    int height = info.height;

    std::vector<uint8_t> rgbBuffer(width * height * 3);
    uint8_t* rgba = static_cast<uint8_t*>(pixels);
    for (uint32_t i = 0; i < width * height; ++i) {
        rgbBuffer[i * 3 + 0] = rgba[i * 4 + 0];
        rgbBuffer[i * 3 + 1] = rgba[i * 4 + 1];
        rgbBuffer[i * 3 + 2] = rgba[i * 4 + 2];
    }

    stbiw_jpg_force_444 = use444 ? 1 : 0;
    int result = stbi_write_jpg(pathStr, width, height, 3, rgbBuffer.data(), quality);

    auto endTime = std::chrono::high_resolution_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();

    LOGD("ExperimentalJPEG: quality=%d, subsampling=%s, encodingTime=%lld ms",
         quality, use444 ? "4:4:4" : "4:2:0", duration);

    env->ReleaseStringUTFChars(path, pathStr);
    AndroidBitmap_unlockPixels(env, bitmap);

    if (result == 0) {
        LOGE("stbi_write_jpg failed");
        return JNI_FALSE;
    }

    return JNI_TRUE;
}
