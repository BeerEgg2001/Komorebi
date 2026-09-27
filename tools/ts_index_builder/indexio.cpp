#include "indexio.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>

BuildIndexResult BuildIndexFromFile(const std::string &tsPath, int programNumberOrIndex,
                                     int gridIntervalMs, size_t chunkSize) {
    BuildIndexResult result;

    std::ifstream ifs(tsPath, std::ios::binary | std::ios::ate);
    if (!ifs) {
        result.error = "ファイルを開けませんでした: " + tsPath;
        return result;
    }
    int64_t fileSize = static_cast<int64_t>(ifs.tellg());
    ifs.seekg(0, std::ios::beg);
    if (fileSize <= 0) {
        result.error = "ファイルサイズの取得に失敗、または0バイトです";
        return result;
    }

    CTsPcrScanner scanner;
    scanner.SetProgramNumberOrIndex(programNumberOrIndex);
    scanner.SetAnchorIntervalPcr(90000);  // 1秒間隔

    std::vector<uint8_t> chunk(chunkSize > 0 ? chunkSize : 1);
    int64_t offset = 0;
    while (ifs) {
        ifs.read(reinterpret_cast<char *>(chunk.data()), static_cast<std::streamsize>(chunk.size()));
        std::streamsize got = ifs.gcount();
        if (got <= 0) break;
        scanner.AddData(chunk.data(), static_cast<int>(got), offset);
        offset += got;
    }

    const auto &anchors = scanner.GetAnchors();
    if (anchors.empty()) {
        result.error = "PCRアンカーが1つも見つかりませんでした(PMT/PCR PIDが解決できないか、"
                        "PCRが存在しないファイルです)";
        return result;
    }
    if (scanner.GetUnitSize() == 0) {
        result.error = "TS同期パターンを確立できませんでした";
        return result;
    }

    int discontinuityCount = 0;
    bool gridOk = ts_index_build_grid(anchors, scanner.GetUnitSize(), gridIntervalMs, &result.grid,
                                       &discontinuityCount);
    if (!gridOk) {
        result.error = "グリッド構築に失敗しました";
        return result;
    }

    TsIndexHeader hdr;
    hdr.formatVersion = 1;
    hdr.flags = discontinuityCount > 0 ? kTsIndexFlagDiscontinuity : 0;
    hdr.unitSize = scanner.GetUnitSize();
    hdr.gridIntervalMs = gridIntervalMs;
    hdr.fileSize = fileSize;
    hdr.firstPcr90k = scanner.GetFirstPcr();
    hdr.lastPcr90k = scanner.GetLastPcr();
    int64_t durationUs = 0;
    if (hdr.firstPcr90k >= 0 && hdr.lastPcr90k >= 0) {
        int64_t diff = pcr_diff(hdr.firstPcr90k, hdr.lastPcr90k);
        durationUs = diff * 1000000LL / 90000LL;
    }
    hdr.durationUsMeasured = durationUs;
    int64_t firstPts = scanner.GetFirstPts();
    hdr.ptsPcrBiasUs = (firstPts >= 0 && hdr.firstPcr90k >= 0)
                            ? pcr_diff(hdr.firstPcr90k, firstPts) * 1000000LL / 90000LL
                            : 0;
    hdr.pcrPid = scanner.GetPcrPid();
    hdr.videoPid = scanner.GetVideoPid();
    hdr.audio1Pid = scanner.GetAudio1Pid();
    hdr.audio2Pid = scanner.GetAudio2Pid();
    hdr.programNumber = programNumberOrIndex;
    hdr.gridCount = static_cast<int32_t>(result.grid.size());
    hdr.pidTimelineOffset = 0;
    hdr.pidTimelineLength = 0;

    result.hdr = hdr;
    result.discontinuityCount = discontinuityCount;
    result.pidChangeCount = scanner.GetPidChangeCount();
    result.ok = true;
    return result;
}

double ReadPcrElapsedSecAtByteOffset(std::ifstream &ifs, int64_t byteOffset, int unitSize, int pcrPid,
                                      int64_t firstPcr90k) {
    ifs.clear();
    if (byteOffset < 0) byteOffset = 0;
    ifs.seekg(byteOffset, std::ios::beg);
    if (!ifs) return -1.0;

    // 索引が返す位置は常にパケット境界だが、線形補間(--compare-linear の比較対象)は
    // パケット境界に丸めていない(現行のVideoPlayerManager.kt自体がそうなので、忠実に
    // 再現している)。そのため実際のプレイヤー(TsExtractor)がそうするように、まず
    // byteOffsetから同期バイト(0x47)を探し直す(resync_ts)。
    //
    // 探索対象のバイト位置から、次のPCR(このツールではvideo PIDのアダプテーションに載る)
    // まで1tick分(最大50ms)の実データを読み進める必要がある。高ビットレート(例: 32Mbps)
    // では50ms ≒ 200KBになるため、余裕を持って2MB確保する。
    constexpr size_t kProbeBytes = 2 * 1024 * 1024;
    size_t probePackets = std::max<size_t>(1, kProbeBytes / static_cast<size_t>(unitSize));
    std::vector<uint8_t> buf(static_cast<size_t>(unitSize) * probePackets);
    ifs.read(reinterpret_cast<char *>(buf.data()), static_cast<std::streamsize>(buf.size()));
    std::streamsize got = ifs.gcount();

    int guessedUnitSize = unitSize;  // 既に分かっているunitSizeをヒントとして渡す(自動判定を省略できる)
    int syncOffset = resync_ts(buf.data(), static_cast<int>(got), &guessedUnitSize);
    if (guessedUnitSize == 0 || syncOffset >= got) return -1.0;

    // resync_ts()が返すsyncOffsetは同期バイトそのものの位置。以降unitSize刻みで
    // 直接パケット先頭を指すため、追加のオフセット調整は不要
    // (tools/ts_index_builder/tsindex.cpp の同種の実装ミス修正時と同じ理由)。
    for (int64_t off = syncOffset; off + unitSize <= got; off += unitSize) {
        const uint8_t *packet = buf.data() + off;
        if (packet[0] != 0x47) continue;
        if (extract_ts_header_pid(packet) != pcrPid) continue;
        int64_t pcr = extract_pcr(packet);
        if (pcr < 0) continue;
        int64_t diff = pcr_diff(firstPcr90k, pcr);
        return static_cast<double>(diff) / 90000.0;
    }
    return -1.0;
}

ErrorStats ComputeErrorStats(const std::vector<double> &absErrorsSec, size_t notFoundCount) {
    ErrorStats stats;
    stats.absErrorsSec = absErrorsSec;
    std::sort(stats.absErrorsSec.begin(), stats.absErrorsSec.end());
    stats.count = stats.absErrorsSec.size();
    stats.notFoundCount = notFoundCount;
    if (stats.count == 0) return stats;

    stats.maxAbsErrorSec = stats.absErrorsSec.back();
    double sum = 0.0;
    for (double v : stats.absErrorsSec) sum += v;
    stats.meanAbsErrorSec = sum / static_cast<double>(stats.count);

    size_t p95Index = static_cast<size_t>(std::ceil(0.95 * stats.count)) - 1;
    if (p95Index >= stats.count) p95Index = stats.count - 1;
    stats.p95AbsErrorSec = stats.absErrorsSec[p95Index];
    return stats;
}

void PrintErrorStats(const std::string &label, const ErrorStats &stats) {
    std::printf("[%s] n=%zu (見つからず=%zu) max=%.3fs p95=%.3fs mean=%.3fs\n", label.c_str(),
                stats.count, stats.notFoundCount, stats.maxAbsErrorSec, stats.p95AbsErrorSec,
                stats.meanAbsErrorSec);
    // 簡易ヒストグラム(0.1s刻み、2s以降は「over」にまとめる)
    constexpr double kBucketWidth = 0.1;
    constexpr int kBucketCount = 20;  // 0.0-2.0s
    std::vector<int> buckets(kBucketCount + 1, 0);
    for (double v : stats.absErrorsSec) {
        int idx = static_cast<int>(v / kBucketWidth);
        if (idx > kBucketCount) idx = kBucketCount;
        buckets[idx]++;
    }
    for (int i = 0; i <= kBucketCount; ++i) {
        if (buckets[i] == 0) continue;
        if (i == kBucketCount) {
            std::printf("  >= %.1fs : %d\n", kBucketCount * kBucketWidth, buckets[i]);
        } else {
            std::printf("  %.1f-%.1fs : %d\n", i * kBucketWidth, (i + 1) * kBucketWidth, buckets[i]);
        }
    }
}

int64_t LinearFallbackBytePosition(int64_t timeUs, int64_t durationUs, int64_t fileSizeBytes) {
    if (fileSizeBytes <= 0) return 0;
    int64_t safeTime = timeUs;
    if (safeTime < 0) safeTime = 0;
    if (durationUs > 0 && safeTime > durationUs) safeTime = durationUs;
    if (durationUs <= 0) return 0;
    double ratio = static_cast<double>(safeTime) / static_cast<double>(durationUs);
    return static_cast<int64_t>(ratio * static_cast<double>(fileSizeBytes));
}
