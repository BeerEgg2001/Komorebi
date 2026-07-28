#include <jni.h>
#include <string>
#include <vector>
#include <unordered_set>
#include <memory>
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

jobject renderResultToCue(JNIEnv* env, aribcc_render_result_t& result, int64_t fallbackPtsMs) {
    int64_t duration = result.duration == ARIBCC_DURATION_INDEFINITE ? -1 : result.duration;
    int64_t pts = result.pts < 0 ? fallbackPtsMs : result.pts;

    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID arrayListCtor = env->GetMethodID(arrayListClass, "<init>", "(I)V");
    jmethodID arrayListAdd = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");
    jobject images = env->NewObject(arrayListClass, arrayListCtor, static_cast<jint>(result.image_count));

    jclass imageClass = env->FindClass("com/beeregg2001/komorebi/ui/subtitle/NativeCaptionImage");
    jmethodID imageCtor = env->GetMethodID(imageClass, "<init>", "(IIIII[B)V");

    for (uint32_t i = 0; i < result.image_count; ++i) {
        aribcc_image_t& image = result.images[i];
        jsize bitmapSize = image.bitmap && image.bitmap_size > 0
            ? static_cast<jsize>(image.bitmap_size)
            : 0;
        jbyteArray rgba = env->NewByteArray(bitmapSize);
        if (bitmapSize > 0) {
            env->SetByteArrayRegion(
                rgba,
                0,
                bitmapSize,
                reinterpret_cast<const jbyte*>(image.bitmap));
        }
        jobject captionImage = env->NewObject(
            imageClass,
            imageCtor,
            static_cast<jint>(image.dst_x),
            static_cast<jint>(image.dst_y),
            static_cast<jint>(image.width),
            static_cast<jint>(image.height),
            static_cast<jint>(image.stride),
            rgba);
        env->CallBooleanMethod(images, arrayListAdd, captionImage);
        env->DeleteLocalRef(captionImage);
        env->DeleteLocalRef(rgba);
    }

    jclass cueClass = env->FindClass("com/beeregg2001/komorebi/ui/subtitle/NativeCaptionCue");
    jmethodID cueCtor = env->GetMethodID(cueClass, "<init>", "(JJZIILjava/util/List;)V");
    jobject cue = env->NewObject(
        cueClass,
        cueCtor,
        static_cast<jlong>(pts),
        static_cast<jlong>(duration),
        result.image_count == 0 ? JNI_TRUE : JNI_FALSE,
        static_cast<jint>(ARIBCC_RENDER_FRAME_WIDTH),
        static_cast<jint>(ARIBCC_RENDER_FRAME_HEIGHT),
        images);

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
    if (length <= 0) return nullptr;

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

                // A CS-only caption intentionally renders no bitmap. Preserve it as an empty
                // cue so the Kotlin timeline can clear the previous indefinite caption.
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

    jobject cue = renderResultToCue(env, renderResult, ptsMs);
    aribcc_render_result_cleanup(&renderResult);
    aribcc_caption_cleanup(&caption);
    return cue;
}

JNIEXPORT jintArray JNICALL
Java_com_beeregg2001_komorebi_NativeLib_getCaptionLanguageCodes(JNIEnv *env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<AribCaptionDecoderContext*>(handle);
    if (!ctx || !ctx->decoder) return env->NewIntArray(0);

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
    if (count > 0) env->SetIntArrayRegion(result, 0, count, codes);
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
