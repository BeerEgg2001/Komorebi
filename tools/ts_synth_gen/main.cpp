// ts_synth_gen: 合成VBR TSファイル生成CLI
//
// docs/design/ts_seek_index.md §7-1 の要件を満たすホスト側ツール。
// PCRベースのシーク索引(tools/ts_index_builder)のテスト用データを生成する。
// 生成と同時に正解データ(<name>.truth.csv)を出力する。

#include "synth.hpp"

#include <cstdio>
#include <fstream>
#include <iostream>
#include <string>

namespace {

void PrintUsage(const char *argv0) {
    std::cerr
        << "使い方: " << argv0 << " -o <出力.ts> --duration <秒> [オプション]\n"
        << "\n"
        << "  合成VBR MPEG-TSファイルと、正解データ(<出力>.truth.csv)を生成します。\n"
        << "\n"
        << "必須:\n"
        << "  -o, --output <path>          出力TSファイルパス\n"
        << "  --duration <秒>               生成する総時間(秒)\n"
        << "\n"
        << "オプション:\n"
        << "  --bitrate-profile <spec>      例: \"4M:30,24M:30\" (30秒毎に4Mbps/24Mbpsを往復)\n"
        << "                                省略時は一定8Mbps\n"
        << "  --program-number <N>          既定 1\n"
        << "  --unit-size <188|192>         既定 188 (192はM2TS、4バイトタイムスタンプ前置)\n"
        << "  --pcr-start <値>              開始PCR(90kHz)。0x接頭辞で16進指定可。既定 0\n"
        << "  --pcr-discontinuity <秒>      指定秒経過時点でPCR不連続を注入\n"
        << "  --pid-change <秒>             指定秒経過時点で副音声PIDを追加(PMT差し替え)\n";
}

int64_t ParseInt64(const std::string &s) {
    return std::strtoll(s.c_str(), nullptr, 0);  // 0 = "0x"接頭辞を自動判定
}

}  // namespace

int main(int argc, char **argv) {
    std::string outputPath;
    synth::SynthOptions opts;
    std::string bitrateProfileText;

    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        auto needValue = [&](const char *name) -> std::string {
            if (i + 1 >= argc) {
                std::cerr << name << " には値が必要です\n";
                std::exit(1);
            }
            return argv[++i];
        };
        if (arg == "-o" || arg == "--output") {
            outputPath = needValue(arg.c_str());
        } else if (arg == "--duration") {
            opts.totalDurationSec = std::stod(needValue(arg.c_str()));
        } else if (arg == "--bitrate-profile") {
            bitrateProfileText = needValue(arg.c_str());
        } else if (arg == "--program-number") {
            opts.programNumber = std::stoi(needValue(arg.c_str()));
        } else if (arg == "--unit-size") {
            opts.unitSize = std::stoi(needValue(arg.c_str()));
        } else if (arg == "--pcr-start") {
            opts.pcrStart = ParseInt64(needValue(arg.c_str()));
        } else if (arg == "--pcr-discontinuity") {
            opts.pcrDiscontinuityAtSec = std::stod(needValue(arg.c_str()));
        } else if (arg == "--pid-change") {
            opts.pidChangeAtSec = std::stod(needValue(arg.c_str()));
        } else if (arg == "-h" || arg == "--help") {
            PrintUsage(argv[0]);
            return 0;
        } else {
            std::cerr << "不明な引数: " << arg << "\n";
            PrintUsage(argv[0]);
            return 1;
        }
    }

    if (outputPath.empty() || opts.totalDurationSec <= 0.0) {
        PrintUsage(argv[0]);
        return 1;
    }
    if (opts.unitSize != 188 && opts.unitSize != 192) {
        std::cerr << "--unit-size は 188 または 192 のみ対応しています\n";
        return 1;
    }
    if (!bitrateProfileText.empty()) {
        if (!synth::ParseBitrateProfile(bitrateProfileText, &opts.profile)) {
            std::cerr << "--bitrate-profile の形式が不正です: " << bitrateProfileText << "\n";
            return 1;
        }
    }

    std::ofstream ofs(outputPath, std::ios::binary | std::ios::trunc);
    if (!ofs) {
        std::cerr << "出力ファイルを開けませんでした: " << outputPath << "\n";
        return 1;
    }

    synth::SynthResult result = synth::GenerateSyntheticTs(
        opts, [&](const uint8_t *data, size_t len) {
            ofs.write(reinterpret_cast<const char *>(data), static_cast<std::streamsize>(len));
        });
    ofs.close();

    std::string truthPath = outputPath + ".truth.csv";
    std::ofstream truthOfs(truthPath, std::ios::trunc);
    if (!truthOfs) {
        std::cerr << "正解データファイルを開けませんでした: " << truthPath << "\n";
        return 1;
    }
    truthOfs << "time_sec,byte_offset,pcr90k\n";
    for (const auto &entry : result.truth) {
        truthOfs << entry.timeSec << "," << entry.byteOffset << "," << entry.pcr90k << "\n";
    }
    truthOfs.close();

    std::cout << "生成完了: " << outputPath << " (" << result.totalBytes << " bytes)\n"
              << "正解データ: " << truthPath << " (" << result.truth.size() << " エントリ)\n"
              << "firstPcr90k=" << result.firstPcr90k << " lastPcr90k=" << result.lastPcr90k << "\n";
    if (result.discontinuityByteOffset >= 0) {
        std::cout << "PCR不連続注入位置: byte=" << result.discontinuityByteOffset << "\n";
    }
    if (result.pidChangeByteOffset >= 0) {
        std::cout << "PID変化注入位置: byte=" << result.pidChangeByteOffset << "\n";
    }
    return 0;
}
