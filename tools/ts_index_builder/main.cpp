// ts_index_builder: PTS/PCRベース シーク索引の生成・検証CLI
//
// docs/design/ts_seek_index.md §7-1「tools/ts_index_builder/」に対応する。
// Android NDK不要。tools/ts_pmt_monitor と同じ方針で app/src/main/cpp を直接参照する。

#include "indexio.hpp"
#include "selftest.hpp"
#include "tsindex.hpp"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <string>

namespace {

void PrintUsage(const char *argv0) {
    std::cerr
        << "使い方:\n"
        << "  " << argv0 << " <in.ts> <out.tsidx> [--program-number N]\n"
        << "      TSファイルから索引(.tsidx)を生成する。\n"
        << "\n"
        << "  " << argv0 << " --verify <in.ts> <in.tsidx>\n"
        << "      索引の全グリッドエントリについて、実際にバイト位置からPCRを読み直し誤差を検証する。\n"
        << "\n"
        << "  " << argv0 << " --compare-linear <in.ts> [--program-number N] [--truth <csv>]\n"
        << "      同一ファイルに対する現行の線形補間と、新索引の誤差分布を並べて出力する。\n"
        << "      --truth を指定すると ts_synth_gen が出力した正解データで検証する(合成データ向け)。\n"
        << "      省略時は索引自身の1秒グリッド全点を目標時刻として使う(実録画向け)。\n"
        << "\n"
        << "  " << argv0 << " --selftest [--tmpdir <dir>] [--no-large]\n"
        << "      T1〜T12の内部テストを実行する。--no-large でT12(大容量)を省略できる。\n";
}

bool ReadFile(const std::string &path, std::vector<uint8_t> *out) {
    std::ifstream ifs(path, std::ios::binary | std::ios::ate);
    if (!ifs) return false;
    std::streamsize size = ifs.tellg();
    ifs.seekg(0);
    out->resize(static_cast<size_t>(size));
    ifs.read(reinterpret_cast<char *>(out->data()), size);
    return true;
}

int RunBuild(const std::string &tsPath, const std::string &outPath, int programNumberOrIndex) {
    BuildIndexResult built = BuildIndexFromFile(tsPath, programNumberOrIndex);
    if (!built.ok) {
        std::cerr << "索引構築に失敗しました: " << built.error << "\n";
        return 1;
    }
    std::vector<uint8_t> serialized;
    if (!ts_index_serialize(built.hdr, built.grid, &serialized)) {
        std::cerr << "シリアライズに失敗しました\n";
        return 1;
    }
    std::ofstream ofs(outPath, std::ios::binary | std::ios::trunc);
    if (!ofs) {
        std::cerr << "出力ファイルを開けませんでした: " << outPath << "\n";
        return 1;
    }
    ofs.write(reinterpret_cast<const char *>(serialized.data()),
              static_cast<std::streamsize>(serialized.size()));
    ofs.close();

    std::cout << "索引を書き出しました: " << outPath << " (" << serialized.size() << " bytes)\n"
              << "  fileSize=" << built.hdr.fileSize << " unitSize=" << built.hdr.unitSize
              << " gridCount=" << built.hdr.gridCount << "\n"
              << "  pcrPid=0x" << std::hex << built.hdr.pcrPid << " videoPid=0x" << built.hdr.videoPid
              << " audio1Pid=0x" << built.hdr.audio1Pid << " audio2Pid=0x" << built.hdr.audio2Pid
              << std::dec << "\n"
              << "  durationUsMeasured=" << built.hdr.durationUsMeasured
              << " (discontinuityCount=" << built.discontinuityCount << ")\n";
    return 0;
}

int RunVerify(const std::string &tsPath, const std::string &tsidxPath) {
    std::vector<uint8_t> idxData;
    if (!ReadFile(tsidxPath, &idxData)) {
        std::cerr << "索引ファイルを開けませんでした: " << tsidxPath << "\n";
        return 1;
    }
    TsIndexHeader hdr;
    std::vector<uint32_t> grid;
    if (!ts_index_deserialize(idxData.data(), static_cast<int>(idxData.size()), &hdr, &grid)) {
        std::cerr << "索引ファイルのデシリアライズに失敗しました(壊れているか非対応形式です)\n";
        return 1;
    }
    std::ifstream ifs(tsPath, std::ios::binary);
    if (!ifs) {
        std::cerr << "TSファイルを開けませんでした: " << tsPath << "\n";
        return 1;
    }

    std::vector<double> errors;
    size_t notFound = 0;
    for (int i = 0; i < hdr.gridCount; ++i) {
        int64_t bytePos = static_cast<int64_t>(grid[static_cast<size_t>(i)]) * hdr.unitSize;
        double actualTime = ReadPcrElapsedSecAtByteOffset(ifs, bytePos, hdr.unitSize, hdr.pcrPid, hdr.firstPcr90k);
        if (actualTime < 0) {
            ++notFound;
            continue;
        }
        errors.push_back(std::fabs(static_cast<double>(i) - actualTime));
    }
    ErrorStats stats = ComputeErrorStats(errors, notFound);
    std::cout << "--- --verify: " << tsPath << " (" << tsidxPath << ") ---\n";
    PrintErrorStats("索引(floor誤差)", stats);
    return 0;
}

int RunCompareLinear(const std::string &tsPath, int programNumberOrIndex, const std::string &truthPath) {
    BuildIndexResult built = BuildIndexFromFile(tsPath, programNumberOrIndex);
    if (!built.ok) {
        std::cerr << "索引構築に失敗しました: " << built.error << "\n";
        return 1;
    }

    std::ifstream ifsIndex(tsPath, std::ios::binary);
    std::ifstream ifsLinear(tsPath, std::ios::binary);

    std::vector<double> indexErrors, linearErrors;
    size_t indexNotFound = 0, linearNotFound = 0;

    auto evalAt = [&](double timeSec) {
        int64_t timeUs = static_cast<int64_t>(timeSec * 1000000.0);
        int64_t indexPos = ts_index_resolve_byte_position(built.hdr, built.grid, timeUs);
        int64_t linearPos =
            LinearFallbackBytePosition(timeUs, built.hdr.durationUsMeasured, built.hdr.fileSize);
        double indexActual = ReadPcrElapsedSecAtByteOffset(ifsIndex, indexPos, built.hdr.unitSize,
                                                             built.hdr.pcrPid, built.hdr.firstPcr90k);
        double linearActual = ReadPcrElapsedSecAtByteOffset(ifsLinear, linearPos, built.hdr.unitSize,
                                                              built.hdr.pcrPid, built.hdr.firstPcr90k);
        if (getenv("TSIDX_DEBUG")) {
            std::cerr << "t=" << timeSec << " indexPos=" << indexPos << " indexActual=" << indexActual
                      << " linearPos=" << linearPos << " linearActual=" << linearActual << "\n";
        }
        if (indexActual >= 0) {
            indexErrors.push_back(std::fabs(timeSec - indexActual));
        } else {
            ++indexNotFound;
        }
        if (linearActual >= 0) {
            linearErrors.push_back(std::fabs(timeSec - linearActual));
        } else {
            ++linearNotFound;
        }
    };

    if (!truthPath.empty()) {
        std::ifstream truthIfs(truthPath);
        if (!truthIfs) {
            std::cerr << "正解データファイルを開けませんでした: " << truthPath << "\n";
            return 1;
        }
        std::string line;
        std::getline(truthIfs, line);  // header
        while (std::getline(truthIfs, line)) {
            if (line.empty()) continue;
            double timeSec = std::stod(line);
            evalAt(timeSec);
        }
    } else {
        double durationSec = built.hdr.durationUsMeasured / 1000000.0;
        for (double t = 0.0; t <= durationSec; t += 1.0) {
            evalAt(t);
        }
    }

    ErrorStats indexStats = ComputeErrorStats(indexErrors, indexNotFound);
    ErrorStats linearStats = ComputeErrorStats(linearErrors, linearNotFound);

    std::cout << "--- --compare-linear: " << tsPath << " ---\n"
              << "  durationUsMeasured=" << built.hdr.durationUsMeasured
              << " fileSize=" << built.hdr.fileSize << "\n";
    PrintErrorStats("索引", indexStats);
    PrintErrorStats("線形補間(現行のVideoPlayerManager.kt相当)", linearStats);
    return 0;
}

}  // namespace

int main(int argc, char **argv) {
    if (argc < 2) {
        PrintUsage(argv[0]);
        return 1;
    }

    std::vector<std::string> args(argv + 1, argv + argc);

    if (args[0] == "-h" || args[0] == "--help") {
        PrintUsage(argv[0]);
        return 0;
    }

    if (args[0] == "--selftest") {
        std::string tmpDir;
        bool runLarge = true;
        for (size_t i = 1; i < args.size(); ++i) {
            if (args[i] == "--tmpdir" && i + 1 < args.size()) {
                tmpDir = args[++i];
            } else if (args[i] == "--no-large") {
                runLarge = false;
            }
        }
        return RunSelfTest(tmpDir, runLarge);
    }

    if (args[0] == "--verify") {
        if (args.size() < 3) {
            PrintUsage(argv[0]);
            return 1;
        }
        return RunVerify(args[1], args[2]);
    }

    if (args[0] == "--compare-linear") {
        if (args.size() < 2) {
            PrintUsage(argv[0]);
            return 1;
        }
        std::string tsPath = args[1];
        int programNumberOrIndex = -1;
        std::string truthPath;
        for (size_t i = 2; i < args.size(); ++i) {
            if (args[i] == "--program-number" && i + 1 < args.size()) {
                programNumberOrIndex = std::stoi(args[++i]);
            } else if (args[i] == "--truth" && i + 1 < args.size()) {
                truthPath = args[++i];
            }
        }
        return RunCompareLinear(tsPath, programNumberOrIndex, truthPath);
    }

    // 既定: 索引生成
    if (args.size() < 2) {
        PrintUsage(argv[0]);
        return 1;
    }
    std::string tsPath = args[0];
    std::string outPath = args[1];
    int programNumberOrIndex = -1;
    for (size_t i = 2; i < args.size(); ++i) {
        if (args[i] == "--program-number" && i + 1 < args.size()) {
            programNumberOrIndex = std::stoi(args[++i]);
        }
    }
    return RunBuild(tsPath, outPath, programNumberOrIndex);
}
