#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>
#include <zlib.h>

#define LOG_TAG "LrecEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const int  ZLIB_OFFSET       = 12;
static const int  PIXEL_DATA_OFFSET = 21;
static const int  FULL_WIDTH_OFFSET = 9;
static const int  FULL_HEIGHT_OFFSET = 13;
static const int  DEFAULT_WIDTH     = 1187;
static const int  DEFAULT_HEIGHT    = 834;
static const size_t MAX_DECOMP_SIZE = 1500000;

static inline uint16_t readU16LE(const uint8_t* data, int offset) {
    return (uint16_t)(data[offset] | (data[offset + 1] << 8));
}

static int zlibDecompress(
    const uint8_t* input, int inputLen,
    uint8_t* output, int outputCapacity)
{
    if (inputLen <= ZLIB_OFFSET + 2) return -1;

    uint8_t b12 = input[ZLIB_OFFSET];
    uint8_t b13 = input[ZLIB_OFFSET + 1];
    bool hasZlib = (b12 == 0x78) &&
                   (b13 == 0x01 || b13 == 0x5E || b13 == 0x9C || b13 == 0xDA);
    if (!hasZlib) return -1;

    z_stream stream;
    memset(&stream, 0, sizeof(stream));
    stream.next_in   = (Bytef*)(input + ZLIB_OFFSET);
    stream.avail_in  = (uInt)(inputLen - ZLIB_OFFSET);
    stream.next_out  = (Bytef*)output;
    stream.avail_out = (uInt)outputCapacity;

    if (inflateInit(&stream) != Z_OK) return -1;
    int ret   = inflate(&stream, Z_FINISH);
    int total = (int)stream.total_out;
    inflateEnd(&stream);

    if (ret == Z_STREAM_END || ret == Z_OK || ret == Z_BUF_ERROR)
        return (total > 0) ? total : -1;

    // المحاولة الثانية: raw deflate
    memset(&stream, 0, sizeof(stream));
    stream.next_in   = (Bytef*)(input + ZLIB_OFFSET + 2);
    stream.avail_in  = (uInt)(inputLen - ZLIB_OFFSET - 2);
    stream.next_out  = (Bytef*)output;
    stream.avail_out = (uInt)outputCapacity;

    if (inflateInit2(&stream, -15) != Z_OK) return -1;
    ret   = inflate(&stream, Z_FINISH);
    total = (int)stream.total_out;
    inflateEnd(&stream);

    return (total > 0) ? total : -1;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_lrec_operator_LrecEngine_decompressBlock(
    JNIEnv* env, jobject, jbyteArray blockData)
{
    if (!blockData) return nullptr;
    jsize    inputLen  = env->GetArrayLength(blockData);
    jbyte*   inputBytes = env->GetByteArrayElements(blockData, nullptr);
    if (!inputBytes) return nullptr;

    std::vector<uint8_t> output(MAX_DECOMP_SIZE);
    int size = zlibDecompress((uint8_t*)inputBytes, (int)inputLen,
                              output.data(), (int)output.size());
    env->ReleaseByteArrayElements(blockData, inputBytes, JNI_ABORT);

    if (size <= 0) return nullptr;
    jbyteArray result = env->NewByteArray(size);
    if (result) env->SetByteArrayRegion(result, 0, size, (jbyte*)output.data());
    return result;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_lrec_operator_LrecEngine_open(JNIEnv*, jobject, jstring) { return 0L; }

extern "C"
JNIEXPORT void JNICALL
Java_com_lrec_operator_LrecEngine_close(JNIEnv*, jobject, jlong) { }

extern "C"
JNIEXPORT jint JNICALL
Java_com_lrec_operator_LrecEngine_getWidth(JNIEnv*, jobject, jlong) { return DEFAULT_WIDTH; }

extern "C"
JNIEXPORT jint JNICALL
Java_com_lrec_operator_LrecEngine_getHeight(JNIEnv*, jobject, jlong) { return DEFAULT_HEIGHT; }

extern "C"
JNIEXPORT jint JNICALL
Java_com_lrec_operator_LrecEngine_getTotalFrames(JNIEnv*, jobject, jlong) { return 0; }

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_lrec_operator_LrecEngine_decodeFrameNative(JNIEnv*, jobject, jlong, jint) { return nullptr; }
