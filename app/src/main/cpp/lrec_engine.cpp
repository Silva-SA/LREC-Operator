#include <jni.h>
#include <string>
#include <vector>

extern "C"
JNIEXPORT jlong JNICALL
Java_com_lrec_operator_LrecEngine_open(JNIEnv *env, jobject thiz, jstring path) {

    return 1;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_lrec_operator_LrecEngine_close(JNIEnv *env, jobject thiz, jlong handle) {

}

extern "C"
JNIEXPORT jint JNICALL
Java_com_lrec_operator_LrecEngine_getWidth(JNIEnv *env, jobject thiz, jlong handle) {

    return 1024;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_lrec_operator_LrecEngine_getHeight(JNIEnv *env, jobject thiz, jlong handle) {

    return 768;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_lrec_operator_LrecEngine_getTotalFrames(JNIEnv *env, jobject thiz, jlong handle) {

    return 1000;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_lrec_operator_LrecEngine_decodeFrameNative(
        JNIEnv *env,
        jobject thiz,
        jlong handle,
        jint frame) {

    int width = 1024;
    int height = 768;

    int size = width * height * 2;

    std::vector<uint8_t> buffer(size);

    for (int i = 0; i < size; i++) {
        buffer[i] = (uint8_t)(frame % 255);
    }

    jbyteArray result = env->NewByteArray(size);

    env->SetByteArrayRegion(
            result,
            0,
            size,
            reinterpret_cast<jbyte *>(buffer.data())
    );

    return result;
}
