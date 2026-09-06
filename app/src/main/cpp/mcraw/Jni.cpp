#include "Encoder.h"
#include <jni.h>
#include <cstring>
#include <stdexcept>

extern "C" JNIEXPORT jint JNICALL
Java_com_particlesdevs_photoncamera_processing_mcraw_McrawEncoder_encode(
        JNIEnv* env, jclass, jobject input, jint size, jint width, jint height, jint stride,
        jboolean raw10, jint top, jint cropHeight, jboolean bin, jobject output) {
    try {
        auto* src = static_cast<const uint8_t*>(env->GetDirectBufferAddress(input));
        auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(output));
        if (!src || !dst || size < 0 || env->GetDirectBufferCapacity(input) < size)
            throw std::invalid_argument("Invalid direct RAW buffer");
        // Reuse capacity on this recording worker; TLS is released when the worker exits.
        thread_local std::vector<uint8_t> encoded;
        photon_mcraw::encode(src,size,width,height,stride,raw10,top,cropHeight,bin,encoded);
        if (encoded.size() > static_cast<size_t>(env->GetDirectBufferCapacity(output)))
            throw std::invalid_argument("Encoded buffer too small");
        std::memcpy(dst, encoded.data(), encoded.size());
        return static_cast<jint>(encoded.size());
    } catch (const std::exception& e) {
        env->ThrowNew(env->FindClass("java/io/IOException"),e.what());
        return 0;
    }
}
