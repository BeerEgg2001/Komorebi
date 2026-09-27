// selftest: docs/design/ts_seek_index.md §7-2 の T1〜T12 を実装する。

#include "selftest.hpp"

#include "indexio.hpp"
#include "synth.hpp"
#include "tsindex.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <vector>

#if defined(__APPLE__) || defined(__linux__)
#include <sys/resource.h>
#endif

namespace {

// ---- 小さなテストフレームワーク --------------------------------------------

struct TestContext {
    bool failed = false;
    std::vector<std::string> messages;

    void Check(bool cond, const std::string &msg) {
        if (cond) {
            messages.push_back("  ok: " + msg);
        } else {
            failed = true;
            messages.push_back("  NG: " + msg);
        }
    }
    void Note(const std::string &msg) { messages.push_back("  .. " + msg); }
};

// ---- ユーティリティ ---------------------------------------------------------

std::string JoinPath(const std::string &dir, const std::string &name) {
    if (dir.empty()) return name;
    if (dir.back() == '/') return dir + name;
    return dir + "/" + name;
}

std::string EffectiveTmpDir(const std::string &tmpDir) {
    return tmpDir.empty() ? std::string("/tmp") : tmpDir;
}

// 合成TSを一時ファイルへ書き出す。戻り値は生成結果(truth含む)。
synth::SynthResult GenerateToFile(const std::string &path, const synth::SynthOptions &opts) {
    std::ofstream ofs(path, std::ios::binary | std::ios::trunc);
    synth::SynthResult result = synth::GenerateSyntheticTs(opts, [&](const uint8_t *data, size_t len) {
        ofs.write(reinterpret_cast<const char *>(data), static_cast<std::streamsize>(len));
    });
    ofs.close();
    return result;
}

long GetMaxRssKb() {
#if defined(__APPLE__)
    struct rusage usage;
    if (getrusage(RUSAGE_SELF, &usage) != 0) return -1;
    return usage.ru_maxrss / 1024;  // macOSはbyte単位
#elif defined(__linux__)
    struct rusage usage;
    if (getrusage(RUSAGE_SELF, &usage) != 0) return -1;
    return usage.ru_maxrss;  // LinuxはKB単位
#else
    return -1;
#endif
}

// ---- T1: 索引正確性(floor性の検証) -----------------------------------------

void Test_T1_GridAccuracy(TestContext &tc, const std::string &tmpDir) {
    std::string path = JoinPath(tmpDir, "t1_vbr.ts");
    synth::SynthOptions opts;
    opts.totalDurationSec = 65.0;
    synth::ParseBitrateProfile("4M:30,24M:30", &opts.profile);
    GenerateToFile(path, opts);

    BuildIndexResult built = BuildIndexFromFile(path, -1);
    tc.Check(built.ok, "索引構築に成功する: " + built.error);
    if (!built.ok) return;

    std::ifstream ifs(path, std::ios::binary);
    int violations = 0;
    int checked = 0;
    for (int i = 0; i < built.hdr.gridCount; ++i) {
        int64_t bytePos = static_cast<int64_t>(built.grid[static_cast<size_t>(i)]) * built.hdr.unitSize;
        double actualTime = ReadPcrElapsedSecAtByteOffset(ifs, bytePos, built.hdr.unitSize,
                                                            built.hdr.pcrPid, built.hdr.firstPcr90k);
        if (actualTime < 0) {
            ++violations;
            continue;
        }
        double err = static_cast<double>(i) - actualTime;
        ++checked;
        // floor性: 0 <= (i秒 - 実PCR時刻) < 1秒 (浮動小数の丸め用に小さな許容誤差を入れる)
        if (err < -0.01 || err >= 1.01) {
            ++violations;
        }
    }
    tc.Check(checked > 0, "検証可能なグリッドエントリが存在する (checked=" + std::to_string(checked) + ")");
    tc.Check(violations == 0,
              "floor性違反が無い (violations=" + std::to_string(violations) + "/" +
                  std::to_string(built.hdr.gridCount) + ")");
}

// ---- T2: 線形補間 vs 索引の誤差分布(定量比較。最重要) ------------------------

void Test_T2_CompareLinear(TestContext &tc, const std::string &tmpDir) {
    std::string path = JoinPath(tmpDir, "t2_vbr.ts");
    synth::SynthOptions opts;
    opts.totalDurationSec = 65.0;
    synth::ParseBitrateProfile("4M:30,24M:30", &opts.profile);
    synth::SynthResult synthResult = GenerateToFile(path, opts);

    BuildIndexResult built = BuildIndexFromFile(path, -1);
    tc.Check(built.ok, "索引構築に成功する: " + built.error);
    if (!built.ok) return;

    std::ifstream ifs(path, std::ios::binary);
    std::ifstream ifs2(path, std::ios::binary);

    std::vector<double> indexErrors, linearErrors;
    for (const auto &truth : synthResult.truth) {
        int64_t timeUs = static_cast<int64_t>(truth.timeSec * 1000000.0);
        int64_t indexPos = ts_index_resolve_byte_position(built.hdr, built.grid, timeUs);
        int64_t linearPos =
            LinearFallbackBytePosition(timeUs, built.hdr.durationUsMeasured, built.hdr.fileSize);

        double indexActual = ReadPcrElapsedSecAtByteOffset(ifs, indexPos, built.hdr.unitSize,
                                                             built.hdr.pcrPid, built.hdr.firstPcr90k);
        double linearActual = ReadPcrElapsedSecAtByteOffset(ifs2, linearPos, built.hdr.unitSize,
                                                              built.hdr.pcrPid, built.hdr.firstPcr90k);
        if (indexActual >= 0) indexErrors.push_back(std::fabs(truth.timeSec - indexActual));
        if (linearActual >= 0) linearErrors.push_back(std::fabs(truth.timeSec - linearActual));
    }

    ErrorStats indexStats = ComputeErrorStats(indexErrors, synthResult.truth.size() - indexErrors.size());
    ErrorStats linearStats = ComputeErrorStats(linearErrors, synthResult.truth.size() - linearErrors.size());

    std::cout << "  --- T2 誤差分布(合成VBR 4M<->24M/30秒, duration=65s) ---\n";
    PrintErrorStats("索引", indexStats);
    PrintErrorStats("線形補間(現行)", linearStats);

    tc.Check(indexStats.maxAbsErrorSec < 1.5,
              "索引の最大誤差が1.5秒未満 (実測=" + std::to_string(indexStats.maxAbsErrorSec) + "s)");
    tc.Check(linearStats.maxAbsErrorSec > indexStats.maxAbsErrorSec,
              "線形補間の最大誤差が索引より明確に大きい(設計の妥当性の数値的証拠): 線形=" +
                  std::to_string(linearStats.maxAbsErrorSec) +
                  "s vs 索引=" + std::to_string(indexStats.maxAbsErrorSec) + "s");
}

// ---- T3: スパースプローブ収束シミュレーション --------------------------------

void Test_T3_SparseProbeConvergence(TestContext &tc, const std::string &tmpDir) {
    std::string path = JoinPath(tmpDir, "t3_vbr.ts");
    synth::SynthOptions opts;
    opts.totalDurationSec = 60.0;
    synth::ParseBitrateProfile("4M:20,24M:20,4M:20", &opts.profile);
    synth::SynthResult synthResult = GenerateToFile(path, opts);

    std::ifstream ifs(path, std::ios::binary | std::ios::ate);
    int64_t fileSize = static_cast<int64_t>(ifs.tellg());
    ifs.seekg(0);

    constexpr int kSparseN = 128;
    constexpr int64_t kProbeBufSize = 256 * 1024;

    // 粗いプローブ(N分割)でアンカーを集める
    std::vector<TsAnchor> coarseAnchors;
    std::vector<uint8_t> probeBuf(kProbeBufSize);
    for (int i = 0; i < kSparseN; ++i) {
        int64_t pos = fileSize * i / kSparseN;
        ifs.clear();
        ifs.seekg(pos);
        ifs.read(reinterpret_cast<char *>(probeBuf.data()), static_cast<std::streamsize>(probeBuf.size()));
        std::streamsize got = ifs.gcount();
        if (got <= 0) continue;
        TsAnchor anchor;
        int pcrPid = -1;
        if (ts_probe_first_pcr(probeBuf.data(), static_cast<int>(got), -1, -1, &anchor, &pcrPid)) {
            anchor.rawByteOffset += pos;  // バッファ内オフセット→ファイル内オフセットへ補正
            coarseAnchors.push_back(anchor);
        }
    }
    tc.Check(coarseAnchors.size() > static_cast<size_t>(kSparseN / 2),
              "粗いプローブで十分な数のアンカーが得られる (n=" + std::to_string(coarseAnchors.size()) + ")");
    if (coarseAnchors.size() < 2) return;

    int64_t firstPcr = coarseAnchors.front().pcr90k;
    auto elapsedSecOf = [&](const TsAnchor &a) {
        return static_cast<double>(pcr_diff(firstPcr, a.pcr90k)) / 90000.0;
    };

    // いくつかの目標時刻について、粗いアンカーで挟んでから二分探索で精密化する
    std::vector<double> targets = {5.0, 15.0, 25.0, 35.0, 45.0, 55.0};
    int convergedCount = 0;
    for (double target : targets) {
        // targetを挟む粗いアンカーを探す
        size_t lo = 0, hi = coarseAnchors.size() - 1;
        bool bracketed = false;
        for (size_t k = 0; k + 1 < coarseAnchors.size(); ++k) {
            if (elapsedSecOf(coarseAnchors[k]) <= target && elapsedSecOf(coarseAnchors[k + 1]) >= target) {
                lo = k;
                hi = k + 1;
                bracketed = true;
                break;
            }
        }
        if (!bracketed) continue;

        int64_t loByte = coarseAnchors[lo].rawByteOffset;
        int64_t hiByte = coarseAnchors[hi].rawByteOffset;
        double loTime = elapsedSecOf(coarseAnchors[lo]);
        double hiTime = elapsedSecOf(coarseAnchors[hi]);

        int iterations = 0;
        for (; iterations < 6 && hiByte - loByte > kProbeBufSize; ++iterations) {
            int64_t midByte = (loByte + hiByte) / 2;
            ifs.clear();
            ifs.seekg(midByte);
            ifs.read(reinterpret_cast<char *>(probeBuf.data()),
                      static_cast<std::streamsize>(probeBuf.size()));
            std::streamsize got = ifs.gcount();
            if (got <= 0) break;
            TsAnchor midAnchor;
            int pcrPid = -1;
            if (!ts_probe_first_pcr(probeBuf.data(), static_cast<int>(got), -1, -1, &midAnchor, &pcrPid)) {
                break;
            }
            midAnchor.rawByteOffset += midByte;
            double midTime = elapsedSecOf(midAnchor);
            if (midTime <= target) {
                loByte = midAnchor.rawByteOffset;
                loTime = midTime;
            } else {
                hiByte = midAnchor.rawByteOffset;
                hiTime = midTime;
            }
        }
        double resultErr = std::min(std::fabs(target - loTime), std::fabs(target - hiTime));
        if (resultErr < 0.5 && iterations <= 6) ++convergedCount;
        tc.Note("target=" + std::to_string(target) + "s iterations=" + std::to_string(iterations) +
                 " err=" + std::to_string(resultErr) + "s");
    }
    tc.Check(convergedCount == static_cast<int>(targets.size()),
              "全ターゲットが6回以内の二分探索で誤差0.5秒未満に収束する (converged=" +
                  std::to_string(convergedCount) + "/" + std::to_string(targets.size()) + ")");
}

// ---- T4: PCRラップアラウンド ------------------------------------------------

void Test_T4_PcrWraparound(TestContext &tc, const std::string &tmpDir) {
    std::string path = JoinPath(tmpDir, "t4_wrap.ts");
    synth::SynthOptions opts;
    opts.totalDurationSec = 30.0;
    opts.pcrStart = 0x200000000LL - 900000LL;  // ラップまで残り10秒
    GenerateToFile(path, opts);

    BuildIndexResult built = BuildIndexFromFile(path, -1);
    tc.Check(built.ok, "索引構築に成功する: " + built.error);
    if (!built.ok) return;

    bool monotonic = true;
    for (size_t i = 1; i < built.grid.size(); ++i) {
        if (built.grid[i] < built.grid[i - 1]) monotonic = false;
    }
    tc.Check(monotonic, "ラップアラウンドを跨いでもグリッドが単調である");

    double durationSec = built.hdr.durationUsMeasured / 1000000.0;
    tc.Check(durationSec > 28.0 && durationSec < 32.0,
              "ラップアラウンド後もdurationが正しく計算される (実測=" + std::to_string(durationSec) + "s)");
}

// ---- T5: PCR不連続 ----------------------------------------------------------

void Test_T5_PcrDiscontinuity(TestContext &tc, const std::string &tmpDir) {
    std::string path = JoinPath(tmpDir, "t5_disc.ts");
    synth::SynthOptions opts;
    opts.totalDurationSec = 20.0;
    opts.pcrDiscontinuityAtSec = 10.0;
    GenerateToFile(path, opts);

    BuildIndexResult built = BuildIndexFromFile(path, -1);
    tc.Check(built.ok, "不連続があってもクラッシュせず索引構築に成功する: " + built.error);
    if (!built.ok) return;

    bool monotonic = true;
    for (size_t i = 1; i < built.grid.size(); ++i) {
        if (built.grid[i] < built.grid[i - 1]) monotonic = false;
    }
    tc.Check(monotonic, "不連続後も強制的に単調化される");
    tc.Check(built.discontinuityCount > 0,
              "不連続が検出される (discontinuityCount=" + std::to_string(built.discontinuityCount) + ")");
    tc.Check((built.hdr.flags & kTsIndexFlagDiscontinuity) != 0, "flags bit0(不連続あり)が立つ");
}

// ---- T6: PID構成変化 ---------------------------------------------------------

void Test_T6_PidChange(TestContext &tc, const std::string &tmpDir) {
    std::string path = JoinPath(tmpDir, "t6_pidchange.ts");
    synth::SynthOptions opts;
    opts.totalDurationSec = 20.0;
    opts.pidChangeAtSec = 10.0;
    GenerateToFile(path, opts);

    BuildIndexResult built = BuildIndexFromFile(path, -1);
    tc.Check(built.ok, "PID構成変化があっても索引構築に成功する(索引が破綻しない): " + built.error);
    if (!built.ok) return;

    bool monotonic = true;
    for (size_t i = 1; i < built.grid.size(); ++i) {
        if (built.grid[i] < built.grid[i - 1]) monotonic = false;
    }
    tc.Check(monotonic, "PID変化後もグリッドが単調である");
    tc.Check(built.pidChangeCount >= 1,
              "PID構成変化がスキャナで検出される (pidChangeCount=" + std::to_string(built.pidChangeCount) + ")");
    tc.Check(built.hdr.audio2Pid == 0x0111,
              "変化後の最終PID構成がヘッダに反映される (audio2Pid=0x" +
                  [&] { std::ostringstream o; o << std::hex << built.hdr.audio2Pid; return o.str(); }() + ")");
}

// ---- T7: シリアライズ往復 ----------------------------------------------------

void Test_T7_SerializeRoundTrip(TestContext &tc, const std::string &tmpDir) {
    std::string path = JoinPath(tmpDir, "t7_small.ts");
    synth::SynthOptions opts;
    opts.totalDurationSec = 10.0;
    GenerateToFile(path, opts);

    BuildIndexResult built = BuildIndexFromFile(path, -1);
    tc.Check(built.ok, "索引構築に成功する: " + built.error);
    if (!built.ok) return;

    std::vector<uint8_t> serialized;
    tc.Check(ts_index_serialize(built.hdr, built.grid, &serialized), "シリアライズに成功する");

    TsIndexHeader hdr2;
    std::vector<uint32_t> grid2;
    bool deOk = ts_index_deserialize(serialized.data(), static_cast<int>(serialized.size()), &hdr2, &grid2);
    tc.Check(deOk, "デシリアライズに成功する");
    if (deOk) {
        tc.Check(hdr2.fileSize == built.hdr.fileSize && hdr2.unitSize == built.hdr.unitSize &&
                      hdr2.gridCount == built.hdr.gridCount && hdr2.firstPcr90k == built.hdr.firstPcr90k &&
                      hdr2.lastPcr90k == built.hdr.lastPcr90k,
                  "デシリアライズしたヘッダが完全一致する");
        tc.Check(grid2 == built.grid, "デシリアライズしたグリッドが完全一致する");
    }

    // 破損: グリッド部分の1バイトを反転させる → CRC不一致で拒否されるはず
    std::vector<uint8_t> corrupted = serialized;
    if (corrupted.size() > 100) corrupted[90] ^= 0xFF;
    TsIndexHeader hdrC;
    std::vector<uint32_t> gridC;
    bool corruptedOk =
        ts_index_deserialize(corrupted.data(), static_cast<int>(corrupted.size()), &hdrC, &gridC);
    tc.Check(!corruptedOk, "CRC破損が検出され、デシリアライズが拒否される");

    // 切り詰め: 末尾を10バイト削る → サイズ不整合で拒否されるはず
    std::vector<uint8_t> truncated(serialized.begin(), serialized.end() - 10);
    TsIndexHeader hdrT;
    std::vector<uint32_t> gridT;
    bool truncatedOk =
        ts_index_deserialize(truncated.data(), static_cast<int>(truncated.size()), &hdrT, &gridT);
    tc.Check(!truncatedOk, "切り詰められたファイルが拒否される");
}

// ---- T8: 境界値 --------------------------------------------------------------

void Test_T8_BoundaryValues(TestContext &tc, const std::string &tmpDir) {
    // t=0 / t=duration / t>duration
    {
        std::string path = JoinPath(tmpDir, "t8_normal.ts");
        synth::SynthOptions opts;
        opts.totalDurationSec = 10.0;
        GenerateToFile(path, opts);
        BuildIndexResult built = BuildIndexFromFile(path, -1);
        tc.Check(built.ok, "通常ファイルの索引構築に成功する: " + built.error);
        if (built.ok) {
            int64_t posAtZero = ts_index_resolve_byte_position(built.hdr, built.grid, 0);
            int64_t posAtDuration =
                ts_index_resolve_byte_position(built.hdr, built.grid, built.hdr.durationUsMeasured);
            int64_t posBeyond =
                ts_index_resolve_byte_position(built.hdr, built.grid, built.hdr.durationUsMeasured + 999999999);
            tc.Check(posAtZero >= 0, "t=0が例外なく解決できる");
            tc.Check(posAtDuration >= posAtZero, "t=durationがt=0以降の位置に解決される");
            tc.Check(posBeyond == posAtDuration, "t>durationはdurationにクランプされる");
        }
    }
    // 0バイトファイル
    {
        std::string path = JoinPath(tmpDir, "t8_empty.ts");
        std::ofstream ofs(path, std::ios::binary | std::ios::trunc);
        ofs.close();
        BuildIndexResult built = BuildIndexFromFile(path, -1);
        tc.Check(!built.ok, "0バイトファイルはエラーとして安全に拒否される(クラッシュしない)");
    }
    // PCR皆無(PAT/PMTはあるがPCR_PID=0x1FFFでPCRが一切埋め込まれない状況を模す:
    // ここでは単純にPAT/PMTすら無い全NULLパケットで代用し、次のケースと合わせて検証する)
    {
        std::string path = JoinPath(tmpDir, "t8_allnull.ts");
        std::ofstream ofs(path, std::ios::binary | std::ios::trunc);
        uint8_t nullPacket[188] = {0};
        nullPacket[0] = 0x47;
        nullPacket[1] = 0x1F;  // PID上位: 0x1FFF (NULLパケット)
        nullPacket[2] = 0xFF;
        nullPacket[3] = 0x10;
        for (int i = 0; i < 1000; ++i) {
            ofs.write(reinterpret_cast<const char *>(nullPacket), sizeof(nullPacket));
        }
        ofs.close();
        BuildIndexResult built = BuildIndexFromFile(path, -1);
        tc.Check(!built.ok, "PAT/PMT/PCRが皆無のファイル(全NULLパケット)は例外を出さず安全に失敗する");
    }
}

// ---- T9: チャンク分割耐性(Tier0の正しさの根幹) -------------------------------

void Test_T9_ChunkResilience(TestContext &tc, const std::string &tmpDir) {
    std::string path = JoinPath(tmpDir, "t9_small.ts");
    synth::SynthOptions opts;
    opts.totalDurationSec = 3.0;
    synth::ParseBitrateProfile("2M:1,6M:1,2M:1", &opts.profile);
    GenerateToFile(path, opts);

    std::vector<size_t> chunkSizes = {1, 187, 997 /* 素数 */, 4 * 1024 * 1024 /* 一括相当 */};
    std::vector<BuildIndexResult> results;
    for (size_t cs : chunkSizes) {
        results.push_back(BuildIndexFromFile(path, -1, 1000, cs));
    }

    for (size_t i = 0; i < results.size(); ++i) {
        tc.Check(results[i].ok,
                  "chunkSize=" + std::to_string(chunkSizes[i]) + " で索引構築に成功する: " + results[i].error);
    }
    if (!results[0].ok) return;

    for (size_t i = 1; i < results.size(); ++i) {
        bool sameGrid = results[i].ok && results[i].grid == results[0].grid;
        bool sameHeader = results[i].ok && results[i].hdr.firstPcr90k == results[0].hdr.firstPcr90k &&
                           results[i].hdr.lastPcr90k == results[0].hdr.lastPcr90k &&
                           results[i].hdr.unitSize == results[0].hdr.unitSize &&
                           results[i].hdr.pcrPid == results[0].hdr.pcrPid &&
                           results[i].hdr.videoPid == results[0].hdr.videoPid &&
                           results[i].hdr.audio1Pid == results[0].hdr.audio1Pid &&
                           results[i].hdr.gridCount == results[0].hdr.gridCount;
        tc.Check(sameGrid, "chunkSize=" + std::to_string(chunkSizes[i]) +
                                 " のグリッドが一括投入(chunkSize=4MB)と完全一致する");
        tc.Check(sameHeader, "chunkSize=" + std::to_string(chunkSizes[i]) +
                                   " のヘッダ主要項目が一括投入と完全一致する");
    }
}

// ---- T10: unitSize 192(M2TS) -------------------------------------------------

void Test_T10_UnitSize192(TestContext &tc, const std::string &tmpDir) {
    std::string path = JoinPath(tmpDir, "t10_m2ts.ts");
    synth::SynthOptions opts;
    opts.totalDurationSec = 20.0;
    opts.unitSize = 192;
    synth::ParseBitrateProfile("4M:10,24M:10", &opts.profile);
    GenerateToFile(path, opts);

    BuildIndexResult built = BuildIndexFromFile(path, -1);
    tc.Check(built.ok, "unitSize=192(M2TS)の索引構築に成功する: " + built.error);
    if (!built.ok) return;
    tc.Check(built.hdr.unitSize == 192, "unitSizeが192として自動検出される(実測=" +
                                              std::to_string(built.hdr.unitSize) + ")");

    std::ifstream ifs(path, std::ios::binary);
    int violations = 0, checked = 0;
    for (int i = 0; i < built.hdr.gridCount; ++i) {
        int64_t bytePos = static_cast<int64_t>(built.grid[static_cast<size_t>(i)]) * built.hdr.unitSize;
        double actualTime = ReadPcrElapsedSecAtByteOffset(ifs, bytePos, built.hdr.unitSize,
                                                            built.hdr.pcrPid, built.hdr.firstPcr90k);
        if (actualTime < 0) {
            ++violations;
            continue;
        }
        double err = static_cast<double>(i) - actualTime;
        ++checked;
        if (err < -0.01 || err >= 1.01) ++violations;
    }
    tc.Check(checked > 0 && violations == 0,
              "188バイト版と同等の精度が得られる(floor性違反=" + std::to_string(violations) + ")");
}

// ---- T11: フォールバック回帰(現行の線形補間とのビット一致) --------------------

void Test_T11_FallbackRegression(TestContext &tc, const std::string & /*tmpDir*/) {
    // VideoPlayerManager.kt:224-241 の式:
    //   safeTime = timeUs.coerceIn(0, durationUs)
    //   position = (safeTime.toDouble() / durationUs * size).toLong()
    struct Case {
        int64_t timeUs, durationUs, fileSize, expected;
    };
    std::vector<Case> cases = {
        {5'000'000, 10'000'000, 1'000'000, 500'000},
        {0, 10'000'000, 1'000'000, 0},
        {10'000'000, 10'000'000, 1'000'000, 1'000'000},
        {-1'000'000, 10'000'000, 1'000'000, 0},        // 負値はcoerceInで0にクランプ
        {20'000'000, 10'000'000, 1'000'000, 1'000'000},  // durationを超える値はクランプ
        {3'333'333, 10'000'000, 12'345'678, 4'115'225},  // 端数丸め(toLongは切り捨て)
    };
    for (const auto &c : cases) {
        int64_t actual = LinearFallbackBytePosition(c.timeUs, c.durationUs, c.fileSize);
        tc.Check(actual == c.expected, "timeUs=" + std::to_string(c.timeUs) +
                                             " durationUs=" + std::to_string(c.durationUs) +
                                             " fileSize=" + std::to_string(c.fileSize) +
                                             " -> " + std::to_string(actual) +
                                             " (期待値=" + std::to_string(c.expected) + ")");
    }
}

// ---- T12: 大容量(ストリーミング処理の確認) ------------------------------------

void Test_T12_LargeFile(TestContext &tc, const std::string &tmpDir) {
    std::string path = JoinPath(tmpDir, "t12_large.ts");
    synth::SynthOptions opts;
    // 20Mbps x 150秒 ≒ 375MB。「数百MB」規模の実用的なサイズで検証する(15GB全部は生成しない)。
    opts.totalDurationSec = 150.0;
    opts.profile = {{20.0e6, 150.0}};

    auto genStart = std::chrono::steady_clock::now();
    GenerateToFile(path, opts);
    auto genEnd = std::chrono::steady_clock::now();

    std::ifstream sizeCheck(path, std::ios::binary | std::ios::ate);
    int64_t fileSize = static_cast<int64_t>(sizeCheck.tellg());
    sizeCheck.close();
    tc.Note("生成ファイルサイズ=" + std::to_string(fileSize / (1024 * 1024)) + "MB, 生成時間=" +
             std::to_string(std::chrono::duration<double>(genEnd - genStart).count()) + "s");

    long rssBefore = GetMaxRssKb();
    auto buildStart = std::chrono::steady_clock::now();
    BuildIndexResult built = BuildIndexFromFile(path, -1);
    auto buildEnd = std::chrono::steady_clock::now();
    long rssAfter = GetMaxRssKb();

    tc.Check(built.ok, "大容量ファイルの索引構築に成功する: " + built.error);

    double buildSec = std::chrono::duration<double>(buildEnd - buildStart).count();
    tc.Note("索引構築時間=" + std::to_string(buildSec) + "s (" +
             std::to_string(fileSize / (1024.0 * 1024.0) / buildSec) + " MB/s)");

    if (rssBefore >= 0 && rssAfter >= 0) {
        long deltaKb = rssAfter - rssBefore;
        long fileSizeKb = fileSize / 1024;
        tc.Note("RSS: before=" + std::to_string(rssBefore) + "KB after=" + std::to_string(rssAfter) +
                 "KB delta=" + std::to_string(deltaKb) + "KB (ファイルサイズ=" +
                 std::to_string(fileSizeKb) + "KB)");
        // ストリーミング処理であれば、RSS増分はファイルサイズに比例しない(定数オーダー)はず。
        // ファイルサイズの半分を超えて増えていたら、全データをメモリ上に保持している疑いがある。
        tc.Check(deltaKb < fileSizeKb / 2,
                  "メモリ使用量がファイルサイズに比例して増えていない(ストリーミング処理であることの傍証)");
    } else {
        tc.Note("この環境ではgetrusageによるRSS計測がサポートされていません(スキップ)");
    }

    // 削除してディスクを解放する(数百MBの一時ファイルを残さない)
    std::remove(path.c_str());
}

}  // namespace

int RunSelfTest(const std::string &tmpDirIn, bool runLargeTest) {
    std::string tmpDir = EffectiveTmpDir(tmpDirIn);
    std::error_code ec;
    std::filesystem::create_directories(tmpDir, ec);
    if (ec) {
        std::cerr << "一時ディレクトリの作成に失敗しました: " << tmpDir << " (" << ec.message() << ")\n";
        return 1;
    }

    struct NamedTest {
        std::string name;
        void (*fn)(TestContext &, const std::string &);
    };
    std::vector<NamedTest> tests = {
        {"T1  索引正確性(floor性)", Test_T1_GridAccuracy},
        {"T2  線形補間vs索引 定量比較", Test_T2_CompareLinear},
        {"T3  スパースプローブ収束", Test_T3_SparseProbeConvergence},
        {"T4  PCRラップアラウンド", Test_T4_PcrWraparound},
        {"T5  PCR不連続", Test_T5_PcrDiscontinuity},
        {"T6  PID構成変化", Test_T6_PidChange},
        {"T7  シリアライズ往復", Test_T7_SerializeRoundTrip},
        {"T8  境界値", Test_T8_BoundaryValues},
        {"T9  チャンク分割耐性", Test_T9_ChunkResilience},
        {"T10 unitSize192(M2TS)", Test_T10_UnitSize192},
        {"T11 フォールバック回帰", Test_T11_FallbackRegression},
    };
    if (runLargeTest) {
        tests.push_back({"T12 大容量(ストリーミング確認)", Test_T12_LargeFile});
    }

    int failCount = 0;
    for (const auto &t : tests) {
        std::cout << "=== " << t.name << " ===\n";
        TestContext tc;
        try {
            t.fn(tc, tmpDir);
        } catch (const std::exception &e) {
            tc.failed = true;
            tc.messages.push_back(std::string("  NG: 例外が送出された: ") + e.what());
        } catch (...) {
            tc.failed = true;
            tc.messages.push_back("  NG: 不明な例外が送出された");
        }
        for (const auto &m : tc.messages) std::cout << m << "\n";
        std::cout << (tc.failed ? "  => FAIL\n" : "  => PASS\n");
        if (tc.failed) ++failCount;
    }

    std::cout << "\n=== 結果サマリ ===\n";
    std::cout << (tests.size() - failCount) << "/" << tests.size() << " passed\n";
    if (!runLargeTest) {
        std::cout << "(T12は--no-large指定によりスキップされました)\n";
    }
    return failCount == 0 ? 0 : 1;
}
