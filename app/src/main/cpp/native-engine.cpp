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
