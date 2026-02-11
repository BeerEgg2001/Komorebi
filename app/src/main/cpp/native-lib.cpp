#include <jni.h>
#include <string>
#include <vector>
#include <unordered_set>
#include <memory>
#include <algorithm>
#include <cstring>
#include <mutex>
#include <deque>

// tsreadex コアヘッダ
#include "servicefilter.hpp"
#include "id3conv.hpp"
#include "util.hpp"
#include "traceb24.hpp"

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
Java_com_example_komorebi_NativeLib_openFilter(JNIEnv *env, jobject thiz, jobjectArray args) {
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
Java_com_example_komorebi_NativeLib_pushDataBuffer(JNIEnv *env, jobject thiz, jlong handle, jobject inputBuf, jint inputLen) {
    auto* ctx = reinterpret_cast<TsReadExContext*>(handle);
    if (!ctx) return;
    uint8_t* inPtr = (uint8_t*)env->GetDirectBufferAddress(inputBuf);
    if (inPtr) ctx->pushData(inPtr, inputLen);
}

JNIEXPORT jint JNICALL
Java_com_example_komorebi_NativeLib_popDataBuffer(JNIEnv *env, jobject thiz, jlong handle, jobject outputBuf, jint maxLen) {
    auto* ctx = reinterpret_cast<TsReadExContext*>(handle);
    if (!ctx) return -1;
    uint8_t* outPtr = (uint8_t*)env->GetDirectBufferAddress(outputBuf);
    if (!outPtr) return -1;
    return ctx->popData(outPtr, maxLen);
}

JNIEXPORT jint JNICALL
Java_com_example_komorebi_NativeLib_processDataBuffer(JNIEnv *env, jobject thiz, jlong handle, jobject inputBuf, jint inputLen, jobject outputBuf) {
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
Java_com_example_komorebi_NativeLib_closeFilter(JNIEnv *env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<TsReadExContext*>(handle);
    if (ctx) delete ctx;
}

}