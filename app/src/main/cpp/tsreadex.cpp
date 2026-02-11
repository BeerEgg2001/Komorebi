#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <io.h>
#else
#ifndef _FILE_OFFSET_BITS
#define _FILE_OFFSET_BITS 64
#endif
#include <errno.h>
#include <sys/select.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <thread>
#endif
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <algorithm>
#include <chrono>
#include <memory>
#include <unordered_set>
#include "id3conv.hpp"
#include "servicefilter.hpp"
#include "traceb24.hpp"
#include "util.hpp"

namespace
{
void SleepFor(std::chrono::milliseconds rel)
{
#ifdef _WIN32
    // MSVC sleep_for() is buggy
    Sleep(static_cast<DWORD>(rel.count()));
#else
    std::this_thread::sleep_for(rel);
#endif
}

#ifdef _WIN32
const char *GetSmallString(const wchar_t *s)
{
    static char ss[32];
    size_t i = 0;
    for (; i < sizeof(ss) - 1 && s[i]; ++i) {
        ss[i] = 0 < s[i] && s[i] <= 127 ? static_cast<char>(s[i]) : '?';
    }
    ss[i] = '\0';
    return ss;
}

int64_t SeekFile(HANDLE file, int64_t offset)
{
    LARGE_INTEGER li;
    li.QuadPart = offset < 0 ? offset + 1 : offset;
    return SetFilePointerEx(file, li, &li, offset < 0 ? FILE_END : FILE_BEGIN) ? li.QuadPart : -1;
}

template<class P>
int ReadFileToBuffer(HANDLE file, uint8_t *buf, size_t count, HANDLE asyncContext, P asyncCancelProc)
{
    OVERLAPPED ol = {};
    DWORD nRead;
    if (asyncContext) {
        // For asynchronous, returns -1 for EOF
        ol.hEvent = asyncContext;
        if (ReadFile(file, buf, static_cast<DWORD>(count), nullptr, &ol)) {
            return GetOverlappedResult(file, &ol, &nRead, FALSE) ? nRead : -1;
        }
        else if (GetLastError() == ERROR_IO_PENDING) {
            while (!asyncCancelProc()) {
                if (WaitForSingleObject(asyncContext, 1000) != WAIT_TIMEOUT) {
                    return GetOverlappedResult(file, &ol, &nRead, FALSE) ? nRead : -1;
                }
            }
            CancelIo(file);
            WaitForSingleObject(asyncContext, INFINITE);
        }
    }
    else if (ReadFile(file, buf, static_cast<DWORD>(count), &nRead, nullptr)) {
        // For synchronous, 0 means EOF
        return nRead;
    }
    return -1;
}

void CloseFile(HANDLE file, HANDLE asyncContext)
{
    if (file != INVALID_HANDLE_VALUE) {
        CloseHandle(file);
    }
    if (asyncContext) {
        CloseHandle(asyncContext);
    }
}
#else
const char *GetSmallString(const char *s)
{
    return s;
}

int64_t SeekFile(int file, int64_t offset)
{
    return lseek(file, offset < 0 ? offset + 1 : offset, offset < 0 ? SEEK_END : SEEK_SET);
}

template<class P>
int ReadFileToBuffer(int file, uint8_t *buf, size_t count, int &asyncContext, P asyncCancelProc)
{
    for (;;) {
        int ret = static_cast<int>(read(file, buf, count));
        if (ret >= 0 || !asyncContext || (errno != EAGAIN && errno != EWOULDBLOCK) || asyncCancelProc()) {
            // Asynchronous FIFO reading can be opened before a writer opens it, then read() returns 0.
            if (asyncContext && ret > 0) {
                // Connected.
                asyncContext = 2;
            }
            // For asynchronous, returns -1 for EOF
            // For synchronous, 0 means EOF
            return asyncContext == 2 && ret == 0 ? -1 : ret;
        }
        fd_set rfd;
        FD_ZERO(&rfd);
        FD_SET(file, &rfd);
        timeval tv = {};
        tv.tv_sec = 1;
        if (select(file + 1, &rfd, nullptr, nullptr, &tv) < 0) {
            return -1;
        }
    }
}

void CloseFile(int file, int asyncContext)
{
    static_cast<void>(asyncContext);
    if (file >= 0) {
        close(file);
    }
}
#endif
}

#ifdef _WIN32
int wmain(int argc, wchar_t **argv)
#else
int main(int argc, char **argv)
#endif
{
    int64_t seekOffset = 0;
    int limitReadBytesPerSec = 0;
    int timeoutSec = 0;
    int timeoutMode = 0;
    std::unordered_set<int> excludePidSet;
    std::unique_ptr<FILE, decltype(&fclose)> traceFile(nullptr, fclose);
    CServiceFilter servicefilter;
    CTraceB24Caption traceb24;
    CID3Converter id3conv;
#ifdef _WIN32
    const wchar_t *srcName = L"";
    const wchar_t *traceName = L"";
#else
    const char *srcName = "";
    const char *traceName = "";
#endif

    for (int i = 1; i < argc; ++i) {
        char c = '\0';
        const char *ss = GetSmallString(argv[i]);
        if (ss[0] == '-' && ss[1] && !ss[2]) {
            c = ss[1];
        }
        if (c == 'h') {
            fprintf(stderr, "Usage: tsreadex [-z ignored][-s seek][-l limit][-t timeout][-m mode][-x pids][-n prog_num_or_index][-a aud1][-b aud2][-c cap][-u sup][-r trace][-d flags] src\n");
            return 2;
        }
        bool invalid = false;
        if (i < argc - 1) {
            if (c == 'z') {
                ++i;
            }
            else if (c == 's') {
                seekOffset = strtoll(GetSmallString(argv[++i]), nullptr, 10);
            }
            else if (c == 'l') {
                limitReadBytesPerSec = static_cast<int>(strtol(GetSmallString(argv[++i]), nullptr, 10) * 1024);
                invalid = !(0 <= limitReadBytesPerSec && limitReadBytesPerSec <= 32 * 1024 * 1024);
            }
            else if (c == 't') {
                timeoutSec = static_cast<int>(strtol(GetSmallString(argv[++i]), nullptr, 10));
                invalid = !(0 <= timeoutSec && timeoutSec <= 600);
            }
            else if (c == 'm') {
                timeoutMode = static_cast<int>(strtol(GetSmallString(argv[++i]), nullptr, 10));
                invalid = !(0 <= timeoutMode && timeoutMode <= 2);
            }
            else if (c == 'x') {
                excludePidSet.clear();
                ++i;
                for (size_t j = 0; argv[i][j];) {
                    ss = GetSmallString(argv[i] + j);
                    char *endp;
                    int pid = static_cast<int>(strtol(ss, &endp, 10));
                    excludePidSet.emplace(pid);
                    invalid = !(0 <= pid && pid <= 8191 && ss != endp && (!*endp || *endp == '/'));
                    if (invalid || !*endp) {
                        break;
                    }
                    j += endp - ss + 1;
                }
            }
            else if (c == 'n') {
                int n = static_cast<int>(strtol(GetSmallString(argv[++i]), nullptr, 10));
                invalid = !(-256 <= n && n <= 65535);
                servicefilter.SetProgramNumberOrIndex(n);
            }
            else if (c == 'a' || c == 'b' || c == 'c' || c == 'u') {
                int mode = static_cast<int>(strtol(GetSmallString(argv[++i]), nullptr, 10));
                if (c == 'a') {
                    invalid = !(0 <= mode && mode <= 13 && mode % 4 <= 1);
                    servicefilter.SetAudio1Mode(mode);
                }
                else if (c == 'b') {
                    invalid = !(0 <= mode && mode <= 7 && mode % 4 <= 3);
                    servicefilter.SetAudio2Mode(mode);
                }
                else {
                    invalid = !(0 <= mode && mode <= 6 && mode % 4 <= 2);
                    if (c == 'c') {
                        servicefilter.SetCaptionMode(mode);
                    }
                    else {
                        servicefilter.SetSuperimposeMode(mode);
                    }
                }
            }
            else if (c == 'r') {
                traceName = argv[++i];
            }
            else if (c == 'd') {
                id3conv.SetOption(static_cast<int>(strtol(GetSmallString(argv[++i]), nullptr, 10)));
            }
        }
        else {
            srcName = argv[i];
            invalid = !srcName[0];
        }
        if (invalid) {
            fprintf(stderr, "Error: argument %d is invalid.\n", i);
            return 1;
        }
    }
    if (!srcName[0]) {
        fprintf(stderr, "Error: not enough arguments.\n");
        return 1;
    }
    if (timeoutMode == 2) {
        if (timeoutSec == 0) {
            fprintf(stderr, "Error: timeout must not be 0 in non-blocking mode.\n");
            return 1;
        }
        if (seekOffset != 0) {
            fprintf(stderr, "Error: cannot seek file in non-blocking mode.\n");
        }
    }

#ifdef _WIN32
    bool traceToStdout = traceName[0] == L'-' && !traceName[1];
    if (!traceToStdout && _setmode(_fileno(stdout), _O_BINARY) < 0) {
        fprintf(stderr, "Error: _setmode.\n");
        return 1;
    }
    HANDLE asyncContext = nullptr;
    if (timeoutMode == 2) {
        asyncContext = CreateEvent(nullptr, TRUE, FALSE, nullptr);
        if (!asyncContext) {
            fprintf(stderr, "Error: unexpected.\n");
            return 1;
        }
    }
    HANDLE file;
    HANDLE openedFile = INVALID_HANDLE_VALUE;
    if (srcName[0] == L'-' && !srcName[1]) {
        file = GetStdHandle(STD_INPUT_HANDLE);
        if (asyncContext) {
            file = ReOpenFile(file, GENERIC_READ, FILE_SHARE_READ, FILE_FLAG_OVERLAPPED);
        }
    }
    else {
        file = CreateFileW(srcName, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING,
                           FILE_ATTRIBUTE_NORMAL | FILE_FLAG_SEQUENTIAL_SCAN | (asyncContext ? FILE_FLAG_OVERLAPPED : 0), nullptr);
        openedFile = file;
    }
    if (file == INVALID_HANDLE_VALUE) {
        fprintf(stderr, "Error: cannot open file.\n");
        CloseFile(openedFile, asyncContext);
        return 1;
    }
    if (!traceToStdout && traceName[0]) {
        traceFile.reset(_wfopen(traceName, L"w"));
        if (!traceFile) {
            fprintf(stderr, "Warning: cannot open tracefile.\n");
        }
    }
#else
    bool traceToStdout = traceName[0] == '-' && !traceName[1];
    // 0: synchronous, 1: not connected yet, 2: connected.
    int asyncContext = timeoutMode == 2;
    int file;
    int openedFile = -1;
    if (srcName[0] == '-' && !srcName[1]) {
        file = fileno(stdin);
        if (asyncContext) {
            if (fcntl(file, F_SETFL, O_NONBLOCK) == -1) {
                file = -1;
            }
        }
    }
    else {
        file = open(srcName, O_RDONLY | (asyncContext ? O_NONBLOCK : 0));
        openedFile = file;
    }
    if (file < 0) {
        fprintf(stderr, "Error: cannot open file.\n");
        return 1;
    }
    if (!traceToStdout && traceName[0]) {
        traceFile.reset(fopen(traceName, "w"));
        if (!traceFile) {
            fprintf(stderr, "Warning: cannot open tracefile.\n");
        }
    }
#endif
    traceb24.SetFile(traceToStdout ? stdout : traceFile.get());

    int64_t filePos = 0;
    if (seekOffset != 0) {
        filePos = SeekFile(file, seekOffset);
        if (filePos < 0) {
            fprintf(stderr, "Error: seek failed.\n");
            CloseFile(openedFile, asyncContext);
            return 1;
        }
    }

    static uint8_t buf[65536];
    int bufCount = 0;
    int unitSize = 0;
    size_t bufSize = sizeof(buf) / 8;
    int measurementReadCount = 0;
    auto lastWriteTime = std::chrono::steady_clock::now();
    auto lastMeasurementTime = lastWriteTime;
    auto limitReadTime = lastWriteTime + std::chrono::seconds(1);
    int64_t limitReadFilePos = filePos;
    for (;;) {
        // If timeoutMode == 1, read between "next to the syncword (buf[0])" and syncword.
        size_t bufMax = unitSize == 0 ? bufSize : bufSize / unitSize * unitSize - (timeoutMode == 1 ? unitSize - 1 : 0);
        int n = ReadFileToBuffer(file, buf + bufCount, bufMax - bufCount, asyncContext, [=]() {
                    return std::chrono::duration_cast<std::chrono::seconds>(std::chrono::steady_clock::now() - lastWriteTime).count() >= timeoutSec; });
        bool retry = false;
        bool completed = false;
        int bufPos = -1;
        if (timeoutMode == 0) {
            // Synchronous, normal (may be appended) file/pipe
            retry = n <= 0;
            if (!retry) {
                bufCount += n;
                filePos += n;
            }
        }
        else if (timeoutMode == 1) {
            // Synchronous, may be preallocated file
            if (n <= 0) {
                filePos += bufCount - (unitSize == 0 ? 0 : 1);
                timeoutMode = 0;
            }
            else {
                bufCount += n;
                if (bufCount == static_cast<int>(bufMax)) {
                    if (unitSize == 0) {
                        bufPos = resync_ts(buf, bufCount, &unitSize);
                        if (unitSize == 0) {
                            retry = true;
                            bufCount = 0;
                            bufPos = -1;
                        }
                        else {
                            // Keep bufPos always 0
                            filePos += bufPos + 1;
                            buf[0] = buf[bufPos];
                            bufCount = 1;
                            bufPos = 0;
                            if (SeekFile(file, filePos) != filePos) {
                                fprintf(stderr, "Warning: seek failed.\n");
                                completed = true;
                            }
                        }
                    }
                    else {
                        // Keep bufPos always 0
                        bufPos = resync_ts(buf, bufCount, &unitSize);
                        if (bufPos != 0) {
                            retry = true;
                            bufCount = 1;
                            bufPos = 0;
                        }
                        else {
                            filePos += bufCount - 1;
                        }
                    }
                }
            }
        }
        else {
            // Asynchronous, pipe
            if (n < 0) {
                completed = true;
            }
#ifndef _WIN32
            else if (n <= 0) {
                retry = true;
            }
#endif
            else {
                bufCount += n;
                filePos += n;
            }
        }

        if (retry) {
            if (timeoutSec == 0 ||
                std::chrono::duration_cast<std::chrono::seconds>(std::chrono::steady_clock::now() - lastWriteTime).count() >= timeoutSec) {
                completed = true;
            }
            else {
                SleepFor(std::chrono::milliseconds(200));
                if (timeoutMode != 2 && SeekFile(file, filePos) != filePos) {
                    fprintf(stderr, "Warning: seek failed.\n");
                    completed = true;
                }
            }
        }

        if (bufCount == static_cast<int>(bufMax) || completed) {
            if (bufPos < 0) {
                bufPos = resync_ts(buf, bufCount, &unitSize);
            }
            for (int i = bufPos; unitSize != 0 && i + unitSize <= bufCount; i += unitSize) {
                if (excludePidSet.count(extract_ts_header_pid(buf + i)) == 0) {
                    servicefilter.AddPacket(buf + i);
                }
            }
            for (auto it = servicefilter.GetPackets().cbegin(); it != servicefilter.GetPackets().end(); it += 188) {
                traceb24.AddPacket(&*it);
                id3conv.AddPacket(&*it);
            }
            servicefilter.ClearPackets();

            auto nowTime = std::chrono::steady_clock::now();
            if (++measurementReadCount >= 500) {
                // Maximize buffer size
                bufSize = sizeof(buf);
            }
            if (std::chrono::duration_cast<std::chrono::seconds>(nowTime - lastMeasurementTime).count() >= 1) {
                // Decrease/Increase buffer size
                bufSize = measurementReadCount < 10 ? std::max(bufSize - sizeof(buf) / 8, sizeof(buf) / 8) :
                                                      std::min(bufSize + sizeof(buf) / 8, sizeof(buf));
                measurementReadCount = 0;
                lastMeasurementTime = nowTime;
            }
            if (!id3conv.GetPackets().empty()) {
                if (!traceToStdout) {
                    if (fwrite(id3conv.GetPackets().data(), 1, id3conv.GetPackets().size(), stdout) != id3conv.GetPackets().size()) {
                        completed = true;
                    }
                }
                id3conv.ClearPackets();
                lastWriteTime = std::chrono::steady_clock::now();
            }
            else if (timeoutSec != 0 &&
                     std::chrono::duration_cast<std::chrono::seconds>(nowTime - lastWriteTime).count() >= timeoutSec) {
                completed = true;
            }
            if (completed) {
                break;
            }
            if (unitSize == 0) {
                bufCount = 0;
            }
            else {
                if ((bufPos != 0 || bufCount >= unitSize) && (bufCount - bufPos) % unitSize != 0) {
                    std::copy(buf + bufPos + (bufCount - bufPos) / unitSize * unitSize, buf + bufCount, buf);
                }
                bufCount = (bufCount - bufPos) % unitSize;
            }
        }

        if (limitReadBytesPerSec != 0) {
            if (filePos - limitReadFilePos > limitReadBytesPerSec) {
                // Too fast
                auto nowTime = std::chrono::steady_clock::now();
                if (limitReadTime > nowTime) {
                    SleepFor(std::chrono::duration_cast<std::chrono::milliseconds>(limitReadTime - nowTime));
                }
            }
            auto nowTime = std::chrono::steady_clock::now();
            if (nowTime >= limitReadTime) {
                limitReadTime = nowTime + std::chrono::seconds(1);
                limitReadFilePos = filePos;
            }
        }
    }

    CloseFile(openedFile, asyncContext);
    return 0;
}
