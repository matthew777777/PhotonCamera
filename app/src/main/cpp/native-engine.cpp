#include <jni.h>
#include <string>
#include <android/log.h>
#include <dlfcn.h>
#include <thread>
#include <future>
#include <algorithm>
#include <cmath>

#define LOG_TAG "NativeEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* gJavaVM = nullptr;
static void* gLibArtHandle = nullptr;

static JNIEnv* attachCurrentThread() {
    JNIEnv* env = nullptr;
    if (gJavaVM != nullptr) {
        gJavaVM->AttachCurrentThread(&env, nullptr);
    }
    return env;
}

static void detachCurrentThread() {
    if (gJavaVM != nullptr) {
        gJavaVM->DetachCurrentThread();
    }
}

static void initLibArtHandle() {
    if (gLibArtHandle == nullptr) {
        gLibArtHandle = dlopen("libart.so", RTLD_NOW);
    }
}

static bool disableHiddenApiEnforcementNative() {
    if (gLibArtHandle == nullptr) return false;
    void* apiPolicy = dlsym(gLibArtHandle, "_ZN3art9hiddenapi6detail19g_hiddenapi_policyE");
    if (apiPolicy != nullptr) {
        *reinterpret_cast<int*>(apiPolicy) = 0;
        return true;
    }
    return false;
}

static bool setHiddenApiExemptions(JNIEnv* env) {
    jclass vmRuntimeClass = env->FindClass("dalvik/system/VMRuntime");
    if (vmRuntimeClass == nullptr) {
        env->ExceptionClear();
        return false;
    }
    jmethodID getRuntimeMethod = env->GetStaticMethodID(vmRuntimeClass, "getRuntime", "()Ldalvik/system/VMRuntime;");
    if (getRuntimeMethod == nullptr) {
        env->ExceptionClear();
        return false;
    }
    jobject runtime = env->CallStaticObjectMethod(vmRuntimeClass, getRuntimeMethod);
    if (runtime == nullptr) {
        env->ExceptionClear();
        return false;
    }
    jmethodID setHiddenApiExemptionsMethod = env->GetMethodID(vmRuntimeClass, "setHiddenApiExemptions", "([Ljava/lang/String;)V");
    if (setHiddenApiExemptionsMethod == nullptr) {
        env->ExceptionClear();
        return false;
    }
    jobjectArray exemptions = env->NewObjectArray(1, env->FindClass("java/lang/String"), env->NewStringUTF("L"));
    env->CallVoidMethod(runtime, setHiddenApiExemptionsMethod, exemptions);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_api_NativeEngine_nativeInitialize(JNIEnv* env, jclass) {
    initLibArtHandle();
    disableHiddenApiEnforcementNative();
    setHiddenApiExemptions(env);
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    gJavaVM = vm;
    return JNI_VERSION_1_6;
}

static jobject getCameraMethod_internal(jobject clazz, jstring methodName, jobjectArray parameterTypes) {
    JNIEnv* env = attachCurrentThread();
    if (env == nullptr) return nullptr;
    jclass classClass = env->GetObjectClass(clazz);
    jmethodID getDeclaredMethodID = env->GetMethodID(classClass, "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
    jobject method = env->CallObjectMethod(clazz, getDeclaredMethodID, methodName, parameterTypes);
    if (env->ExceptionCheck()) env->ExceptionClear();
    jobject globalMethod = method ? env->NewGlobalRef(method) : nullptr;
    detachCurrentThread();
    return globalMethod;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_particlesdevs_photoncamera_api_NativeEngine_nativeGetCameraMethod(JNIEnv* env, jclass, jclass targetClass, jstring methodName, jobjectArray parameterTypes) {
    jobject globalClass = env->NewGlobalRef(targetClass);
    jstring globalMethodName = (jstring)env->NewGlobalRef(methodName);
    jobjectArray globalParams = parameterTypes ? (jobjectArray)env->NewGlobalRef(parameterTypes) : nullptr;
    auto future = std::async(std::launch::async, &getCameraMethod_internal, globalClass, globalMethodName, globalParams);
    return future.get();
}

static jobject getCameraField_internal(jobject object, jstring fieldName) {
    JNIEnv* env = attachCurrentThread();
    if (env == nullptr) return nullptr;
    jclass classClass = env->GetObjectClass(object);
    jmethodID getDeclaredFieldID = env->GetMethodID(classClass, "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;");
    jobject field = env->CallObjectMethod(object, getDeclaredFieldID, fieldName);
    if (env->ExceptionCheck()) env->ExceptionClear();
    jobject globalField = field ? env->NewGlobalRef(field) : nullptr;
    detachCurrentThread();
    return globalField;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_particlesdevs_photoncamera_api_NativeEngine_nativeGetCameraField(JNIEnv* env, jclass, jclass targetClass, jstring fieldName) {
    jobject globalObject = env->NewGlobalRef(targetClass);
    jstring globalFieldName = (jstring)env->NewGlobalRef(fieldName);
    auto future = std::async(std::launch::async, &getCameraField_internal, globalObject, globalFieldName);
    return future.get();
}

extern "C" JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_api_NativeEngine_nativePrepareRawNINDInput(
        JNIEnv* env, jclass, jobject inputBuffer, jfloatArray outputData, jint inputW, jint inputH, jint rawStride, jfloat whiteLevel, jfloatArray blackLevels) {
    uint16_t* src = (uint16_t*)env->GetDirectBufferAddress(inputBuffer);
    float* dst = env->GetFloatArrayElements(outputData, nullptr);
    float* bl = env->GetFloatArrayElements(blackLevels, nullptr);
    if (!src || !dst || !bl) {
        if (dst) env->ReleaseFloatArrayElements(outputData, dst, 0);
        if (bl) env->ReleaseFloatArrayElements(blackLevels, bl, 0);
        return;
    }
    int planeSize = inputW * inputH;
    for (int y = 0; y < inputH; y++) {
        for (int x = 0; x < inputW; x++) {
            int baseIdx = (y * 2 * rawStride + x * 2);
            int flatIdx = y * inputW + x;
            dst[flatIdx] = ((float)src[baseIdx] - bl[0]) / whiteLevel;
            dst[planeSize + flatIdx] = ((float)src[baseIdx + 1] - bl[1]) / whiteLevel;
            dst[2 * planeSize + flatIdx] = ((float)src[baseIdx + rawStride] - bl[2]) / whiteLevel;
            dst[3 * planeSize + flatIdx] = ((float)src[baseIdx + rawStride + 1] - bl[3]) / whiteLevel;
        }
    }
    env->ReleaseFloatArrayElements(outputData, dst, 0);
    env->ReleaseFloatArrayElements(blackLevels, bl, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_api_NativeEngine_nativeShuffleTileRes(
        JNIEnv* env, jclass, jfloatArray tileData, jobject fullOutputBuf, jobject weightSumBuf,
        jint startX, jint startY, jint tileW, jint tileH, jint fullW, jint fullH, jint tileStride, jint outChannels) {
    float* src = env->GetFloatArrayElements(tileData, nullptr);
    float* dst = (float*)env->GetDirectBufferAddress(fullOutputBuf);
    float* weights = (float*)env->GetDirectBufferAddress(weightSumBuf);
    if (!src || !dst || !weights) {
        if (src) env->ReleaseFloatArrayElements(tileData, src, 0);
        return;
    }
    int srcPlaneSize = tileStride * tileStride;
    int dstPlaneSize = fullW * fullH;
    const int border = 32;
    for (int y = 0; y < tileH; y++) {
        int absY = startY + y;
        if (absY >= fullH) continue;
        for (int x = 0; x < tileW; x++) {
            int absX = startX + x;
            if (absX >= fullW) continue;
            float weight = 1.0f;
            if (startX > 0 && x < border) weight *= (float)x / border;
            if (startY > 0 && y < border) weight *= (float)y / border;
            if (startX + tileW < fullW && x > tileW - border) weight *= (float)(tileW - x) / border;
            if (startY + tileH < fullH && y > tileH - border) weight *= (float)(tileH - y) / border;
            int sy = y * 2, sx = x * 2;
            int dstIdx = absY * fullW + absX;
            int srcIdx = sy * tileStride + sx;
            dst[dstIdx] += src[srcIdx] * weight;
            dst[dstPlaneSize + dstIdx] += src[srcIdx + 1 + srcPlaneSize] * weight;
            dst[2 * dstPlaneSize + dstIdx] += src[srcIdx + tileStride + srcPlaneSize] * weight;
            dst[3 * dstPlaneSize + dstIdx] += src[srcIdx + tileStride + 1 + 2 * srcPlaneSize] * weight;
            weights[dstIdx] += weight;
        }
    }
    env->ReleaseFloatArrayElements(tileData, src, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_api_NativeEngine_nativeApplyRawNINDOutput(
        JNIEnv* env, jclass, jobject inputBuffer, jobject accumulatedBuf, jobject weightSumBuf,
        jint inputW, jint inputH, jint rawStride, jfloat whiteLevel, jfloatArray blackLevels, jfloat strength, jfloat brightnessCorrection) {
    uint16_t* src = (uint16_t*)env->GetDirectBufferAddress(inputBuffer);
    float* out = (float*)env->GetDirectBufferAddress(accumulatedBuf);
    float* weights = (float*)env->GetDirectBufferAddress(weightSumBuf);
    float* bl = env->GetFloatArrayElements(blackLevels, nullptr);
    if (!src || !out || !weights || !bl) {
        if (bl) env->ReleaseFloatArrayElements(blackLevels, bl, 0);
        return;
    }
    int planeSize = inputW * inputH;
    float invStrength = 1.0f - strength;
    float normFactor = (1.0f / 65535.0f) * brightnessCorrection;
    for (int i = 0; i < planeSize; i++) {
        float w = weights[i];
        if (w < 0.0001f) continue;
        int y = i / inputW;
        int x = i % inputW;
        int baseIdx = (y * 2 * rawStride + x * 2);
        float v0 = ((out[i] / w) * normFactor) * whiteLevel + bl[0];
        float v1 = ((out[planeSize + i] / w) * normFactor) * whiteLevel + bl[1];
        float v2 = ((out[2 * planeSize + i] / w) * normFactor) * whiteLevel + bl[2];
        float v3 = ((out[3 * planeSize + i] / w) * normFactor) * whiteLevel + bl[3];
        src[baseIdx] = (uint16_t)std::max(0.0f, std::min(65535.0f, invStrength * src[baseIdx] + strength * v0));
        src[baseIdx + 1] = (uint16_t)std::max(0.0f, std::min(65535.0f, invStrength * src[baseIdx + 1] + strength * v1));
        src[baseIdx + rawStride] = (uint16_t)std::max(0.0f, std::min(65535.0f, invStrength * src[baseIdx + rawStride] + strength * v2));
        src[baseIdx + rawStride + 1] = (uint16_t)std::max(0.0f, std::min(65535.0f, invStrength * src[baseIdx + rawStride + 1] + strength * v3));
    }
    env->ReleaseFloatArrayElements(blackLevels, bl, JNI_ABORT);
}
