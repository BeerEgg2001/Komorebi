// ts_pmt_monitor
//
// 録画TSファイルを解析し、PMT(音声等のPID構成)が時間の経過とともにどう変化したかを
// 標準出力にログとして出力する診断ツール。
//
// 目的:
//   放送波の途中で音声ストリームが追加/削除されるケースを、実機や動画本体を送ってもらう
//   ことなく調査できるようにするため。出力されるのはPID構成やタイムスタンプ等の
//   メタ情報のみで、映像・音声データそのものは一切出力・保存しない。
//
// 実装方針:
//   アプリ本体(app/src/main/cpp)のservicefilter.cpp/util.cppをそのまま再利用し、
//   実際のアプリが解決するPID構成と完全に一致する形で診断する。

#include "servicefilter.hpp"
#include "util.hpp"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

namespace {

// アプリ本体(VideoPlayerManager.kt の dynamicTsArgs)が使っているデフォルト値。
// -a 13: 主音声モード1 + ステレオ化 + デュアルモノ分離
// -b 5 : 副音声モード1 + ステレオ化
constexpr int kDefaultAudio1Mode = 13;
constexpr int kDefaultAudio2Mode = 5;

// 読み込みチャンク単位。大きすぎるとメモリを圧迫し、小さすぎるとI/Oが増えるため折衷。
constexpr size_t kReadChunkSize = 4 * 1024 * 1024;

struct Composition {
    int videoPid = -1;
    int audio1Pid = -1;
    int audio2Pid = -1;
    int captionPid = -1;
    bool isDualMono = false;

    bool operator!=(const Composition &other) const {
        return videoPid != other.videoPid ||
               audio1Pid != other.audio1Pid ||
               audio2Pid != other.audio2Pid ||
               captionPid != other.captionPid ||
               isDualMono != other.isDualMono;
    }
};

Composition CaptureComposition(const CServiceFilter &filter) {
    Composition c;
    c.videoPid = filter.GetVideoPid();
    c.audio1Pid = filter.GetAudio1Pid();
    c.audio2Pid = filter.GetAudio2Pid();
    c.captionPid = filter.GetCaptionPid();
    c.isDualMono = filter.IsAudio1DualMono();
    return c;
}

std::string FormatPid(int pid) {
    if (pid <= 0) return "(なし)";
    char buf[32];
    std::snprintf(buf, sizeof(buf), "0x%04X (%d)", pid, pid);
    return buf;
}

std::string FormatElapsed(double seconds) {
    if (seconds < 0) return "不明(PCR未取得)";
    int h = static_cast<int>(seconds) / 3600;
    int m = (static_cast<int>(seconds) % 3600) / 60;
    double s = seconds - h * 3600 - m * 60;
    char buf[64];
    std::snprintf(buf, sizeof(buf), "%02d:%02d:%05.2f", h, m, s);
    return buf;
}

void PrintUsage(const char *argv0) {
    std::cerr
        << "使い方: " << argv0 << " <録画TSファイル> [オプション]\n"
        << "\n"
        << "  録画TSファイルのPMT(音声構成等)が時間経過でどう変化したかをログ出力します。\n"
        << "  映像・音声データそのものは一切出力しません。\n"
        << "\n"
        << "オプション:\n"
        << "  --service-id <N>   対象サービス(番組)のservice_id/program_numberを指定。\n"
        << "                     省略時はPATの先頭のプログラムを自動選択します。\n"
        << "  --audio1-mode <N>  tsreadexの -a と同じ意味の値。既定値: " << kDefaultAudio1Mode << "\n"
        << "  --audio2-mode <N>  tsreadexの -b と同じ意味の値。既定値: " << kDefaultAudio2Mode << "\n";
}

}  // namespace

int main(int argc, char **argv) {
    if (argc < 2) {
        PrintUsage(argv[0]);
        return 1;
    }

    std::string filePath;
    int serviceId = -1;  // 負値 = PAT内のN番目のプログラムを選択(CServiceFilterの仕様)。既定は先頭(-1)。
    int audio1Mode = kDefaultAudio1Mode;
    int audio2Mode = kDefaultAudio2Mode;

    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--service-id" && i + 1 < argc) {
            serviceId = std::atoi(argv[++i]);
        } else if (arg == "--audio1-mode" && i + 1 < argc) {
            audio1Mode = std::atoi(argv[++i]);
        } else if (arg == "--audio2-mode" && i + 1 < argc) {
            audio2Mode = std::atoi(argv[++i]);
        } else if (arg == "-h" || arg == "--help") {
            PrintUsage(argv[0]);
            return 0;
        } else if (filePath.empty()) {
            filePath = arg;
        } else {
            std::cerr << "不明な引数: " << arg << "\n";
            PrintUsage(argv[0]);
            return 1;
        }
    }

    if (filePath.empty()) {
        PrintUsage(argv[0]);
        return 1;
    }

    std::ifstream ifs(filePath, std::ios::binary | std::ios::ate);
    if (!ifs) {
        std::cerr << "ファイルを開けませんでした: " << filePath << "\n";
        return 1;
    }
    const int64_t fileSize = static_cast<int64_t>(ifs.tellg());
    ifs.seekg(0, std::ios::beg);
    if (fileSize <= 0) {
        std::cerr << "ファイルサイズの取得に失敗しました。\n";
        return 1;
    }

    CServiceFilter filter;
    // service-id 未指定時は先頭のプログラムを選択(仕様上、負値は「-N番目のプログラム」)。
    filter.SetProgramNumberOrIndex(serviceId > 0 ? serviceId : -1);
    filter.SetAudio1Mode(audio1Mode);
    filter.SetAudio2Mode(audio2Mode);
    filter.SetCaptionMode(0);
    filter.SetSuperimposeMode(0);

    std::cout << "=== ts_pmt_monitor ===\n"
              << "ファイル: " << filePath << "\n"
              << "サイズ: " << fileSize << " bytes\n"
              << "対象プログラム: " << (serviceId > 0 ? std::to_string(serviceId) : std::string("先頭のプログラムを自動選択"))
              << "\n"
              << "audio1Mode=" << audio1Mode << ", audio2Mode=" << audio2Mode << " (アプリ本体の既定値と同一)\n"
              << "---------------------------------------------------------------\n";

    std::vector<uint8_t> residual;
    std::vector<uint8_t> chunk(kReadChunkSize);

    int unitSize = 0;
    int64_t totalProcessedBytes = 0;

    Composition lastComposition;
    bool hasLoggedAny = false;
    int64_t changeCount = 0;

    // 最初に確立できたPCRを基準時刻(t=0)とし、以降のPCRとの差分から経過時間を出す。
    // 90kHzクロック。33bit(約26.5時間)で一周するため、大幅な巻き戻りを検知したら
    // 基準を張り直す(通常の録画時間ではまず発生しない)。
    bool hasBasePcr = false;
    int64_t basePcr = 0;

    auto elapsedSecondsFromPcr = [&](int64_t pcr) -> double {
        if (!hasBasePcr || pcr < 0) return -1.0;
        int64_t diff = pcr - basePcr;
        if (diff < 0) {
            // 33bitラップアラウンドを大まかに補正する
            diff += (int64_t(1) << 33);
        }
        return static_cast<double>(diff) / 90000.0;
    };

    while (ifs) {
        ifs.read(reinterpret_cast<char *>(chunk.data()), static_cast<std::streamsize>(chunk.size()));
        std::streamsize readCount = ifs.gcount();
        if (readCount <= 0) break;

        std::vector<uint8_t> data;
        if (!residual.empty()) {
            data.insert(data.end(), residual.begin(), residual.end());
            residual.clear();
        }
        data.insert(data.end(), chunk.begin(), chunk.begin() + readCount);

        const uint8_t *p = data.data();
        int size = static_cast<int>(data.size());
        int pos = 0;

        if (unitSize == 0) {
            pos = resync_ts(p, size, &unitSize);
            if (unitSize == 0) {
                // 同期パターンを確立できなかった。次のチャンクと合わせて再挑戦する。
                residual.assign(p, p + size);
                continue;
            }
        }

        for (int i = pos; i + unitSize <= size; i += unitSize) {
            const uint8_t *packet = p + i;
            filter.AddPacket(packet);
            filter.ClearPackets();  // 出力(remux後)のデータは不要なので都度破棄する

            // PCRの更新チェック(GetPcr()は最新のPCR値をそのまま返す)
            int64_t pcr = filter.GetPcr();
            if (pcr >= 0 && !hasBasePcr) {
                hasBasePcr = true;
                basePcr = pcr;
            }

            Composition current = CaptureComposition(filter);
            bool changed = !hasLoggedAny || (current != lastComposition);
            if (changed) {
                int64_t byteOffset = totalProcessedBytes + i;
                double pct = 100.0 * static_cast<double>(byteOffset) / static_cast<double>(fileSize);
                double elapsed = elapsedSecondsFromPcr(pcr);

                std::cout << "[変化 #" << changeCount << "] "
                          << "byte=" << byteOffset << " (" << pct << "%)"
                          << "  経過時間(PCR基準)=" << FormatElapsed(elapsed) << "\n"
                          << "    video=" << FormatPid(current.videoPid)
                          << "  audio1=" << FormatPid(current.audio1Pid)
                          << "  audio2=" << FormatPid(current.audio2Pid)
                          << "  caption=" << FormatPid(current.captionPid)
                          << "  dualMono=" << (current.isDualMono ? "true" : "false") << "\n";

                lastComposition = current;
                hasLoggedAny = true;
                ++changeCount;
            }
        }

        int processedEnd = pos + ((size - pos) / unitSize) * unitSize;
        totalProcessedBytes += processedEnd;
        if (processedEnd < size) {
            residual.assign(p + processedEnd, p + size);
        }
    }

    std::cout << "---------------------------------------------------------------\n";
    if (changeCount <= 1) {
        std::cout << "PID構成の変化は検出されませんでした(ファイル全体で音声構成は一定です)。\n";
    } else {
        std::cout << "PID構成の変化を " << (changeCount - 1) << " 回検出しました"
                  << "(最初の1回は初期状態の記録です)。\n";
    }
    return 0;
}
