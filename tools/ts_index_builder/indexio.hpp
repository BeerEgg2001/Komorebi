// indexio: ファイルからの索引構築、および検証(--verify/--compare-linear)で使う共通ロジック。
#ifndef TS_INDEX_BUILDER_INDEXIO_HPP
#define TS_INDEX_BUILDER_INDEXIO_HPP

#include "tsindex.hpp"

#include <cstdint>
#include <fstream>
#include <string>
#include <vector>

struct BuildIndexResult {
    bool ok = false;
    std::string error;
    TsIndexHeader hdr;
    std::vector<uint32_t> grid;
    int discontinuityCount = 0;
    int64_t pidChangeCount = 0;
};

// .ts/.m2tsファイルをストリーミング読み込みし、CTsPcrScannerに投入して索引を構築する。
// chunkSize: 1回のAddDataに渡すバイト数(既定4MB)。1/187/素数など任意の値でも動作する(T9で検証)。
BuildIndexResult BuildIndexFromFile(const std::string &tsPath, int programNumberOrIndex,
                                     int gridIntervalMs = 1000, size_t chunkSize = 4 * 1024 * 1024);

// 指定バイト位置から実TSファイルを読み直し、最初に見つかったPCRの経過時間(秒, firstPcr90k基準)を返す。
// 見つからなければ -1 を返す。
double ReadPcrElapsedSecAtByteOffset(std::ifstream &ifs, int64_t byteOffset, int unitSize, int pcrPid,
                                      int64_t firstPcr90k);

struct ErrorStats {
    size_t count = 0;
    size_t notFoundCount = 0;
    double maxAbsErrorSec = 0.0;
    double p95AbsErrorSec = 0.0;
    double meanAbsErrorSec = 0.0;
    std::vector<double> absErrorsSec;  // ソート済み
};

ErrorStats ComputeErrorStats(const std::vector<double> &absErrorsSec, size_t notFoundCount);
void PrintErrorStats(const std::string &label, const ErrorStats &stats);

// 現行のVideoPlayerManager.kt(198-241行)の自前SeekMap線形補間と完全に同一の計算式。
// (safeTime / durationUs * fileSize) を Long キャストするだけ(パケット境界への切り下げも無い)。
int64_t LinearFallbackBytePosition(int64_t timeUs, int64_t durationUs, int64_t fileSizeBytes);

#endif
