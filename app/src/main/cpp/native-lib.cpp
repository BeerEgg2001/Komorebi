#include <jni.h>
#include <android/bitmap.h>
#include <string>
#include <vector>
#include <unordered_set>
#include <algorithm>
#include <cstring>
#include <mutex>
#include <deque>

#include <aribcaption/aribcaption.h>

// tsreadex コアヘッダ
#include "servicefilter.hpp"
#include "id3conv.hpp"
#include "util.hpp"
#include "traceb24.hpp"

namespace {

constexpr int ARIBCC_RENDER_FRAME_WIDTH = 1920;
constexpr int ARIBCC_RENDER_FRAME_HEIGHT = 1080;

struct AribCaptionDecoderContext {
    aribcc_context_t* context = nullptr;
    aribcc_decoder_t* decoder = nullptr;
    aribcc_renderer_t* renderer = nullptr;
    std::mutex mutex;
};

jobject createAndroidBitmapFromRgba(
    JNIEnv* env,
    int width,
    int height,
    int sourceStride,
    const uint8_t* source,
    size_t sourceSize
) {
    if (width <= 0 || height <= 0 || source == nullptr || sourceStride < width * 4) {
        return nullptr;
    }

    const size_t rowBytes = static_cast<size_t>(width) * 4;
    const size_t requiredSize =
        static_cast<size_t>(height - 1) * static_cast<size_t>(sourceStride) + rowBytes;
    if (sourceSize < requiredSize) {
        return nullptr;
    }

    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    if (bitmapClass == nullptr || env->ExceptionCheck()) {
        return nullptr;
    }
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (configClass == nullptr || env->ExceptionCheck()) {
        if (bitmapClass != nullptr) env->DeleteLocalRef(bitmapClass);
        if (configClass != nullptr) env->DeleteLocalRef(configClass);
        return nullptr;
    }

    jfieldID argb8888Field = env->GetStaticFieldID(
        configClass,
        "ARGB_8888",
        "Landroid/graphics/Bitmap$Config;");
    jmethodID createBitmapMethod = env->GetStaticMethodID(
        bitmapClass,
        "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (argb8888Field == nullptr || createBitmapMethod == nullptr || env->ExceptionCheck()) {
        env->DeleteLocalRef(configClass);
        env->DeleteLocalRef(bitmapClass);
        return nullptr;
    }
    jobject config = argb8888Field == nullptr
        ? nullptr
        : env->GetStaticObjectField(configClass, argb8888Field);
    if (config == nullptr || env->ExceptionCheck()) {
        if (config != nullptr) env->DeleteLocalRef(config);
        env->DeleteLocalRef(configClass);
        env->DeleteLocalRef(bitmapClass);
        return nullptr;
    }
    jobject bitmap = createBitmapMethod == nullptr || config == nullptr
        ? nullptr
        : env->CallStaticObjectMethod(
            bitmapClass,
            createBitmapMethod,
            static_cast<jint>(width),
            static_cast<jint>(height),
            config);

    if (config != nullptr) env->DeleteLocalRef(config);
    env->DeleteLocalRef(configClass);
    env->DeleteLocalRef(bitmapClass);
    if (bitmap == nullptr || env->ExceptionCheck()) {
        if (bitmap != nullptr) env->DeleteLocalRef(bitmap);
        return nullptr;
    }

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        info.width != static_cast<uint32_t>(width) ||
        info.height != static_cast<uint32_t>(height) ||
        info.stride < rowBytes) {
        env->DeleteLocalRef(bitmap);
        return nullptr;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        pixels == nullptr) {
        env->DeleteLocalRef(bitmap);
        return nullptr;
    }

    auto* destination = static_cast<uint8_t*>(pixels);
    for (int row = 0; row < height; ++row) {
        std::memcpy(
            destination + static_cast<size_t>(row) * info.stride,
            source + static_cast<size_t>(row) * static_cast<size_t>(sourceStride),
            rowBytes);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}

jobject renderResultToCue(
    JNIEnv* env,
    aribcc_render_result_t& result,
    int64_t fallbackPtsMs,
    bool clearScreen
) {
    int64_t duration = result.duration == ARIBCC_DURATION_INDEFINITE ? -1 : result.duration;
    int64_t pts = result.pts < 0 ? fallbackPtsMs : result.pts;
    if (result.image_count == 0 && !clearScreen) return nullptr;

    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    if (arrayListClass == nullptr || env->ExceptionCheck()) return nullptr;
    jmethodID arrayListCtor = env->GetMethodID(arrayListClass, "<init>", "(I)V");
    jmethodID arrayListAdd = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");
    if (arrayListCtor == nullptr || arrayListAdd == nullptr || env->ExceptionCheck()) {
        env->DeleteLocalRef(arrayListClass);
        return nullptr;
    }
    jobject images = env->NewObject(arrayListClass, arrayListCtor, static_cast<jint>(result.image_count));
    if (images == nullptr || env->ExceptionCheck()) {
        if (images != nullptr) env->DeleteLocalRef(images);
        env->DeleteLocalRef(arrayListClass);
        return nullptr;
    }

    jclass imageClass = env->FindClass("com/beeregg2001/komorebi/ui/subtitle/NativeCaptionImage");
    if (imageClass == nullptr || env->ExceptionCheck()) {
        env->DeleteLocalRef(images);
        env->DeleteLocalRef(arrayListClass);
        return nullptr;
    }
    jmethodID imageCtor = env->GetMethodID(
        imageClass,
        "<init>",
        "(IIIILandroid/graphics/Bitmap;)V");
    if (imageCtor == nullptr || env->ExceptionCheck()) {
        env->DeleteLocalRef(imageClass);
        env->DeleteLocalRef(images);
        env->DeleteLocalRef(arrayListClass);
        return nullptr;
    }

    for (uint32_t i = 0; i < result.image_count; ++i) {
        aribcc_image_t& image = result.images[i];
        jobject bitmap = createAndroidBitmapFromRgba(
            env,
            image.width,
            image.height,
            image.stride,
            image.bitmap,
            image.bitmap_size);
        if (bitmap == nullptr) {
            env->DeleteLocalRef(imageClass);
            env->DeleteLocalRef(images);
            env->DeleteLocalRef(arrayListClass);
            return nullptr;
        }
        jobject captionImage = env->NewObject(
            imageClass,
            imageCtor,
            static_cast<jint>(image.dst_x),
            static_cast<jint>(image.dst_y),
            static_cast<jint>(image.width),
            static_cast<jint>(image.height),
            bitmap);
        if (captionImage == nullptr || env->ExceptionCheck()) {
            if (captionImage != nullptr) env->DeleteLocalRef(captionImage);
            env->DeleteLocalRef(bitmap);
            env->DeleteLocalRef(imageClass);
            env->DeleteLocalRef(images);
            env->DeleteLocalRef(arrayListClass);
            return nullptr;
        }
        env->CallBooleanMethod(images, arrayListAdd, captionImage);
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(captionImage);
            env->DeleteLocalRef(bitmap);
            env->DeleteLocalRef(imageClass);
            env->DeleteLocalRef(images);
            env->DeleteLocalRef(arrayListClass);
            return nullptr;
        }
        env->DeleteLocalRef(captionImage);
        env->DeleteLocalRef(bitmap);
    }

    jclass cueClass = env->FindClass("com/beeregg2001/komorebi/ui/subtitle/NativeCaptionCue");
    if (cueClass == nullptr || env->ExceptionCheck()) {
        env->DeleteLocalRef(imageClass);
        env->DeleteLocalRef(images);
        env->DeleteLocalRef(arrayListClass);
        return nullptr;
    }
    jmethodID cueCtor = env->GetMethodID(cueClass, "<init>", "(JJZIILjava/util/List;)V");
    if (cueCtor == nullptr || env->ExceptionCheck()) {
        env->DeleteLocalRef(cueClass);
        env->DeleteLocalRef(imageClass);
        env->DeleteLocalRef(images);
        env->DeleteLocalRef(arrayListClass);
        return nullptr;
    }
    jobject cue = env->NewObject(
        cueClass,
        cueCtor,
        static_cast<jlong>(pts),
        static_cast<jlong>(duration),
        clearScreen ? JNI_TRUE : JNI_FALSE,
        static_cast<jint>(ARIBCC_RENDER_FRAME_WIDTH),
        static_cast<jint>(ARIBCC_RENDER_FRAME_HEIGHT),
        images);
    if (cue == nullptr || env->ExceptionCheck()) {
        if (cue != nullptr) env->DeleteLocalRef(cue);
        cue = nullptr;
    }

    env->DeleteLocalRef(images);
    env->DeleteLocalRef(imageClass);
    env->DeleteLocalRef(arrayListClass);
    env->DeleteLocalRef(cueClass);
    return cue;
}

} // namespace

class TsReadExContext {
public:
    int64_t seekOffset = 0;
    int limitReadBytesPerSec = 0;
    int timeoutSec = 0;
    int timeoutMode = 0;
    std::unordered_set<int> excludePidSet;

    CServiceFilter servicefilter;
    CTraceB24Caption traceb24;
    CID3Converter id3conv;

    int unitSize = 0;
    std::vector<uint8_t> residualBuffer;

    // 非同期キュー
    std::mutex mtx;
    std::deque<uint8_t> outputQueue;
    const size_t MAX_QUEUE_SIZE = 1024 * 1024 * 8; // 8MB

    TsReadExContext(int argc, char **argv) {
        for (int i = 0; i < argc; ++i) {
            std::string ss = argv[i];
            if (ss.length() < 2 || ss[0] != '-') continue;
            char c = ss[1];
            if (i < argc - 1) {
                if (c == 'z') { i++; }
                else if (c == 's') { seekOffset = std::atoll(argv[++i]); }
                else if (c == 'l') { limitReadBytesPerSec = std::atoi(argv[++i]) * 1024; }
                else if (c == 't') { timeoutSec = std::atoi(argv[++i]); }
                else if (c == 'm') { timeoutMode = std::atoi(argv[++i]); }
                else if (c == 'x') {
                    excludePidSet.clear();
                    char* pid_list = argv[++i];
                    char* token = std::strtok(pid_list, "/");
                    while (token != nullptr) {
                        excludePidSet.insert(std::atoi(token));
                        token = std::strtok(nullptr, "/");
                    }
                }
                else if (c == 'n') { servicefilter.SetProgramNumberOrIndex(std::atoi(argv[++i])); }
                else if (c == 'a') { servicefilter.SetAudio1Mode(std::atoi(argv[++i])); }
                else if (c == 'b') { servicefilter.SetAudio2Mode(std::atoi(argv[++i])); }
                else if (c == 'c') { servicefilter.SetCaptionMode(std::atoi(argv[++i])); }
                else if (c == 'u') { servicefilter.SetSuperimposeMode(std::atoi(argv[++i])); }
                else if (c == 'd') { id3conv.SetOption(std::atoi(argv[++i])); }
                else if (c == 'r') { i++; }
            }
        }
    }

    void pushData(const uint8_t* input, int inputLen) {
        std::vector<uint8_t> data;
        if (!residualBuffer.empty()) {
            data.insert(data.end(), residualBuffer.begin(), residualBuffer.end());
            residualBuffer.clear();
        }
        data.insert(data.end(), input, input + inputLen);

        const uint8_t* p = data.data();
        int size = (int)data.size();
        int pos = 0;

        if (unitSize == 0) {
            pos = resync_ts(p, size, &unitSize);
            if (unitSize == 0) {
                residualBuffer.insert(residualBuffer.end(), p, p + size);
                return;
            }
        }

        for (int i = pos; i + unitSize <= size; i += unitSize) {
            if (excludePidSet.find(extract_ts_header_pid(p + i)) == excludePidSet.end()) {
                servicefilter.AddPacket(p + i);
            }
        }

        int processedEnd = pos + ((size - pos) / unitSize) * unitSize;
        if (processedEnd < size) {
            residualBuffer.insert(residualBuffer.end(), p + processedEnd, p + size);
        }

        const auto& filtered = servicefilter.GetPackets();
        for (auto it = filtered.cbegin(); it != filtered.cend(); it += 188) {
            id3conv.AddPacket(&*it);
        }
        servicefilter.ClearPackets();

        const auto& finalOutput = id3conv.GetPackets();
        if (!finalOutput.empty()) {
            std::lock_guard<std::mutex> lock(mtx);
            if (outputQueue.size() + finalOutput.size() < MAX_QUEUE_SIZE) {
                outputQueue.insert(outputQueue.end(), finalOutput.begin(), finalOutput.end());
            }
            id3conv.ClearPackets();
        }
    }

    int popData(uint8_t* output, int maxOutputLen) {
        std::lock_guard<std::mutex> lock(mtx);
        if (outputQueue.empty()) return 0;

        int available = (int)outputQueue.size();
        int copySize = std::min(available, maxOutputLen);
        std::copy(outputQueue.begin(), outputQueue.begin() + copySize, output);
        outputQueue.erase(outputQueue.begin(), outputQueue.begin() + copySize);
        return copySize;
    }
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_beeregg2001_komorebi_NativeLib_openFilter(JNIEnv *env, jobject thiz, jobjectArray args) {
    int argc = env->GetArrayLength(args);
    std::vector<std::string> arg_strings;
    std::vector<char*> argv_ptrs;
    for (int i = 0; i < argc; ++i) {
        jstring js = (jstring)env->GetObjectArrayElement(args, i);
        const char* s = env->GetStringUTFChars(js, nullptr);
        arg_strings.push_back(s);
        env->ReleaseStringUTFChars(js, s);
    }
    for (auto& s : arg_strings) { argv_ptrs.push_back(const_cast<char*>(s.c_str())); }
    return reinterpret_cast<jlong>(new TsReadExContext((int)argv_ptrs.size(), argv_ptrs.data()));
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_pushDataBuffer(JNIEnv *env, jobject thiz, jlong handle, jobject inputBuf, jint inputLen) {
    auto* ctx = reinterpret_cast<TsReadExContext*>(handle);
    if (!ctx) return;
    uint8_t* inPtr = (uint8_t*)env->GetDirectBufferAddress(inputBuf);
    if (inPtr) ctx->pushData(inPtr, inputLen);
}

JNIEXPORT jint JNICALL
Java_com_beeregg2001_komorebi_NativeLib_popDataBuffer(JNIEnv *env, jobject thiz, jlong handle, jobject outputBuf, jint maxLen) {
    auto* ctx = reinterpret_cast<TsReadExContext*>(handle);
    if (!ctx) return -1;
    uint8_t* outPtr = (uint8_t*)env->GetDirectBufferAddress(outputBuf);
    if (!outPtr) return -1;
    return ctx->popData(outPtr, maxLen);
}

JNIEXPORT jint JNICALL
Java_com_beeregg2001_komorebi_NativeLib_processDataBuffer(JNIEnv *env, jobject thiz, jlong handle, jobject inputBuf, jint inputLen, jobject outputBuf) {
    auto* ctx = reinterpret_cast<TsReadExContext*>(handle);
    if (!ctx) return -1;
    uint8_t* inPtr = (uint8_t*)env->GetDirectBufferAddress(inputBuf);
    uint8_t* outPtr = (uint8_t*)env->GetDirectBufferAddress(outputBuf);
    jlong outCap = env->GetDirectBufferCapacity(outputBuf);
    if (inPtr) ctx->pushData(inPtr, inputLen);
    if (outPtr) return ctx->popData(outPtr, (int)outCap);
    return 0;
}

// ★ 録画TS直接再生のシークで CServiceFilter が作り直されるたびに音声のPTS-PCR学習が
// リセットされる問題への対処。ExportState/ImportState の内容を固定長 jlongArray でやり取りする
// (フィールド順は servicefilter.hpp の State と一致させること)。
static const int FILTER_STATE_FIELD_COUNT = 11;

JNIEXPORT jlongArray JNICALL
Java_com_beeregg2001_komorebi_NativeLib_exportFilterState(JNIEnv *env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<TsReadExContext*>(handle);
    jlongArray result = env->NewLongArray(FILTER_STATE_FIELD_COUNT);
    if (!ctx || !result) return result;

    CServiceFilter::State state = ctx->servicefilter.ExportState();
    jlong buf[FILTER_STATE_FIELD_COUNT] = {
        state.valid ? 1 : 0,
        state.videoPid,
        state.audio1Pid,
        state.audio2Pid,
        state.captionPid,
        state.superimposePid,
        state.pcrPid,
        state.audio1StreamType,
        state.audio2StreamType,
        state.audio1PtsPcrDiff,
        state.audio2PtsPcrDiff,
    };
    env->SetLongArrayRegion(result, 0, FILTER_STATE_FIELD_COUNT, buf);
    return result;
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_importFilterState(JNIEnv *env, jobject thiz, jlong handle, jlongArray stateArray) {
    auto* ctx = reinterpret_cast<TsReadExContext*>(handle);
    if (!ctx || !stateArray || env->GetArrayLength(stateArray) < FILTER_STATE_FIELD_COUNT) return;

    jlong buf[FILTER_STATE_FIELD_COUNT];
    env->GetLongArrayRegion(stateArray, 0, FILTER_STATE_FIELD_COUNT, buf);

    CServiceFilter::State state;
    state.valid = buf[0] != 0;
    state.videoPid = (int)buf[1];
    state.audio1Pid = (int)buf[2];
    state.audio2Pid = (int)buf[3];
    state.captionPid = (int)buf[4];
    state.superimposePid = (int)buf[5];
    state.pcrPid = (int)buf[6];
    state.audio1StreamType = (uint8_t)buf[7];
    state.audio2StreamType = (uint8_t)buf[8];
    state.audio1PtsPcrDiff = buf[9];
    state.audio2PtsPcrDiff = buf[10];
    // isAudio1DualMono は AccumulatePesPackets() が実データから即座に再判定するため引き継がない。
    ctx->servicefilter.ImportState(state);
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_closeFilter(JNIEnv *env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<TsReadExContext*>(handle);
    if (ctx) delete ctx;
}

JNIEXPORT jlong JNICALL
Java_com_beeregg2001_komorebi_NativeLib_openCaptionDecoder(JNIEnv *env, jobject thiz) {
    auto* ctx = new AribCaptionDecoderContext();
    ctx->context = aribcc_context_alloc();
    if (!ctx->context) {
        delete ctx;
        return 0;
    }
    ctx->decoder = aribcc_decoder_alloc(ctx->context);
    if (!ctx->decoder) {
        aribcc_context_free(ctx->context);
        delete ctx;
        return 0;
    }
    if (!aribcc_decoder_initialize(
            ctx->decoder,
            ARIBCC_ENCODING_SCHEME_ARIB_STD_B24_JIS,
            ARIBCC_CAPTIONTYPE_CAPTION,
            ARIBCC_PROFILE_A,
            ARIBCC_LANGUAGEID_FIRST)) {
        aribcc_decoder_free(ctx->decoder);
        aribcc_context_free(ctx->context);
        delete ctx;
        return 0;
    }
    ctx->renderer = aribcc_renderer_alloc(ctx->context);
    if (!ctx->renderer) {
        aribcc_decoder_free(ctx->decoder);
        aribcc_context_free(ctx->context);
        delete ctx;
        return 0;
    }
    if (!aribcc_renderer_initialize(
            ctx->renderer,
            ARIBCC_CAPTIONTYPE_CAPTION,
            ARIBCC_FONTPROVIDER_TYPE_AUTO,
            ARIBCC_TEXTRENDERER_TYPE_AUTO)) {
        aribcc_renderer_free(ctx->renderer);
        aribcc_decoder_free(ctx->decoder);
        aribcc_context_free(ctx->context);
        delete ctx;
        return 0;
    }
    aribcc_renderer_set_frame_size(ctx->renderer, ARIBCC_RENDER_FRAME_WIDTH, ARIBCC_RENDER_FRAME_HEIGHT);
    aribcc_renderer_set_margins(ctx->renderer, 0, 0, 0, 0);
    aribcc_renderer_set_storage_policy(ctx->renderer, ARIBCC_CAPTION_STORAGE_POLICY_MINIMUM, 0);
    aribcc_renderer_set_force_stroke_text(ctx->renderer, true);
    aribcc_renderer_set_replace_drcs(ctx->renderer, true);
    aribcc_renderer_set_merge_region_images(ctx->renderer, false);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jobject JNICALL
Java_com_beeregg2001_komorebi_NativeLib_decodeCaption(JNIEnv *env, jobject thiz, jlong handle, jbyteArray data, jlong ptsMs) {
    auto* ctx = reinterpret_cast<AribCaptionDecoderContext*>(handle);
    if (!ctx || !ctx->decoder || !ctx->renderer || !data) return nullptr;

    jsize length = env->GetArrayLength(data);
    if (length <= 0 || env->ExceptionCheck()) return nullptr;

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return nullptr;

    aribcc_caption_t caption = {};
    int status;
    aribcc_render_result_t renderResult = {};
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        status = aribcc_decoder_decode(
            ctx->decoder,
            reinterpret_cast<const uint8_t*>(bytes),
            static_cast<size_t>(length),
            static_cast<int64_t>(ptsMs),
            &caption);
        if (status == ARIBCC_DECODE_STATUS_GOT_CAPTION) {
            aribcc_renderer_append_caption(ctx->renderer, &caption);
            aribcc_render_status_t renderStatus = aribcc_renderer_render(
                ctx->renderer,
                static_cast<int64_t>(ptsMs),
                &renderResult);
            if (renderStatus != ARIBCC_RENDER_STATUS_GOT_IMAGE &&
                renderStatus != ARIBCC_RENDER_STATUS_GOT_IMAGE_UNCHANGED) {
                const bool isClearOnlyCaption =
                    renderStatus == ARIBCC_RENDER_STATUS_NO_IMAGE &&
                    (caption.flags & ARIBCC_CAPTIONFLAGS_CLEARSCREEN) != 0 &&
                    caption.region_count == 0;
                if (!isClearOnlyCaption) {
                    aribcc_caption_cleanup(&caption);
                    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                    return nullptr;
                }

                // CS 単独の字幕は意図的に bitmap を生成しない。Kotlin の時間線へ
                // 明示的な消去 cue として渡し、直前の無期限字幕を消去する。
                renderResult.pts = caption.pts;
                renderResult.duration = caption.wait_duration;
                renderResult.images = nullptr;
                renderResult.image_count = 0;
            }
        }
    }
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    if (status != ARIBCC_DECODE_STATUS_GOT_CAPTION) {
        return nullptr;
    }

    const bool clearScreen = (caption.flags & ARIBCC_CAPTIONFLAGS_CLEARSCREEN) != 0;
    jobject cue = renderResultToCue(env, renderResult, ptsMs, clearScreen);
    aribcc_render_result_cleanup(&renderResult);
    aribcc_caption_cleanup(&caption);
    return cue;
}

JNIEXPORT jintArray JNICALL
Java_com_beeregg2001_komorebi_NativeLib_getCaptionLanguageCodes(JNIEnv *env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<AribCaptionDecoderContext*>(handle);
    if (!ctx || !ctx->decoder) {
        jintArray empty = env->NewIntArray(0);
        return env->ExceptionCheck() ? nullptr : empty;
    }

    jint codes[ARIBCC_LANGUAGEID_MAX] = {};
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        codes[0] = static_cast<jint>(aribcc_decoder_query_iso6392_language_code(
            ctx->decoder,
            ARIBCC_LANGUAGEID_FIRST));
        codes[1] = static_cast<jint>(aribcc_decoder_query_iso6392_language_code(
            ctx->decoder,
            ARIBCC_LANGUAGEID_SECOND));
    }

    jsize count = codes[1] != 0 ? 2 : (codes[0] != 0 ? 1 : 0);
    jintArray result = env->NewIntArray(count);
    if (result == nullptr || env->ExceptionCheck()) return nullptr;
    if (count > 0) {
        env->SetIntArrayRegion(result, 0, count, codes);
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(result);
            return nullptr;
        }
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_switchCaptionLanguage(JNIEnv *env, jobject thiz, jlong handle, jint languageId) {
    auto* ctx = reinterpret_cast<AribCaptionDecoderContext*>(handle);
    if (!ctx || !ctx->decoder || languageId < ARIBCC_LANGUAGEID_FIRST || languageId > ARIBCC_LANGUAGEID_MAX) return;

    std::lock_guard<std::mutex> lock(ctx->mutex);
    aribcc_decoder_switch_language(
        ctx->decoder,
        static_cast<aribcc_languageid_t>(languageId));
    if (ctx->renderer) aribcc_renderer_flush(ctx->renderer);
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_flushCaptionDecoder(JNIEnv *env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<AribCaptionDecoderContext*>(handle);
    if (!ctx || !ctx->decoder) return;
    std::lock_guard<std::mutex> lock(ctx->mutex);
    aribcc_decoder_flush(ctx->decoder);
    if (ctx->renderer) aribcc_renderer_flush(ctx->renderer);
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_closeCaptionDecoder(JNIEnv *env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<AribCaptionDecoderContext*>(handle);
    if (!ctx) return;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        if (ctx->renderer) aribcc_renderer_free(ctx->renderer);
        if (ctx->decoder) aribcc_decoder_free(ctx->decoder);
        if (ctx->context) aribcc_context_free(ctx->context);
        ctx->renderer = nullptr;
        ctx->decoder = nullptr;
        ctx->context = nullptr;
    }
    delete ctx;
}

}
