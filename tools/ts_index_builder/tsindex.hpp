// tsindex: PTS/PCRベース シーク索引のコアロジック(Phase 0 実装)
//
// docs/design/ts_seek_index.md §2-1(索引フォーマット)・§6-2(関数シグネチャ)に対応する。
//
// ★ 設計書との相違点(重要): 設計書§6-2はこの内容を app/src/main/cpp/tsindex.hpp/.cpp に
// 置く想定(Phase 1でJNIブリッジと組み合わせてアプリに組み込む)。しかし今回のタスク
// (Phase 0)は「アプリ本体・app/src/main/cpp には util.hpp/util.cpp の追記以外一切触れない」
// 制約があるため、このファイルは tools/ts_index_builder/ 配下に置いている。
// フォーマット・アルゴリズムは設計書と完全に同一で、Android/JNI依存は一切無い
// (C++17機能は使うが、Phase1移設時にC++11相当へ書き換えるのは容易な範囲に留めてある)。
// Phase 1では、このファイルの中身をほぼそのまま app/src/main/cpp/tsindex.hpp/.cpp に
// 移設し、JNIブリッジ(tsindex-jni.cpp)を追加する想定。

#ifndef TS_INDEX_BUILDER_TSINDEX_HPP
#define TS_INDEX_BUILDER_TSINDEX_HPP

#include "util.hpp"  // app/src/main/cpp/util.hpp (PSI/PAT構造体, resync_ts, extract_pcr等) を直接参照

#include <cstdint>
#include <vector>

// ---- 軽量PCRアンカー -------------------------------------------------------
struct TsAnchor {
    int64_t pcr90k;
    int64_t rawByteOffset;  // 生ファイル先頭からのバイト位置(パケット先頭)
};

// ---- 軽量PCRスキャナ --------------------------------------------------------
// CServiceFilter を使わず、PAT/PMT解析(util.cpp)とPCR抽出(extract_pcr)だけを行う。
// 映像/音声のリマックスを一切しないため、CPUコストは極めて小さい。
class CTsPcrScanner {
public:
    CTsPcrScanner();

    // CServiceFilter と同じ意味(正: service_id、負: PAT内のN番目、既定は先頭)
    void SetProgramNumberOrIndex(int n);
    // アンカー記録間隔(90kHz単位)。この値以上PCRが進んだときだけアンカーを積む。既定90000(=1秒)
    void SetAnchorIntervalPcr(int64_t interval);

    // データを逐次投入する。baseRawOffset は data[0] の生ファイル上のバイト位置。
    // 呼び出し間でパケット境界がまたがっても内部で持ち越す(1バイト単位の投入にも対応)。
    // 呼び出し側は必ず連続領域を順序通りに渡すこと(baseRawOffsetの整合性はチェックしない)。
    void AddData(const uint8_t *data, int size, int64_t baseRawOffset);

    const std::vector<TsAnchor> &GetAnchors() const { return m_anchors; }
    void ClearAnchors() { m_anchors.clear(); }

    int GetPcrPid() const { return m_pcrPid; }
    int GetVideoPid() const { return m_videoPid; }
    int GetAudio1Pid() const { return m_audio1Pid; }
    int GetAudio2Pid() const { return m_audio2Pid; }
    int GetUnitSize() const { return m_unitSize; }  // 188 / 192 / 204 / 0(未確定)
    int64_t GetFirstPcr() const { return m_firstPcr; }
    int64_t GetLastPcr() const { return m_lastPcr; }
    int64_t GetFirstPts() const { return m_firstPts; }  // 最初に観測したPES PTS(ptsPcrBias算出用)
    int64_t GetPidChangeCount() const { return m_pidChangeCount; }

private:
    void ProcessPacket(const uint8_t *packet, int64_t byteOffset);
    void ResolvePmt(const PSI &psi);

    int m_programNumberOrIndex;
    int64_t m_anchorIntervalPcr;

    std::vector<uint8_t> m_residual;
    int64_t m_residualStartOffset;  // m_residual[0] の生ファイル上のバイト位置
    int m_unitSize;

    PAT m_pat;
    PSI m_pmtPsi;
    int m_pmtPid;
    int m_videoPid, m_audio1Pid, m_audio2Pid;
    int m_pcrPid;
    int64_t m_pcr;

    int64_t m_firstPcr;
    int64_t m_lastPcr;
    int64_t m_firstPts;
    bool m_hasFirstPts;

    bool m_hasLastAnchorPcr;
    int64_t m_lastAnchorPcr;
    int64_t m_pidChangeCount;
    int m_lastVideoPid, m_lastAudio1Pid, m_lastAudio2Pid;

    std::vector<TsAnchor> m_anchors;
};

// ---- 単発プローブ(Tier2用の便宜関数) --------------------------------------
// バッファ内の最初のPCRとその位置を返す。pcrPidHint < 0 ならPAT/PMTから自動解決を試みる。
bool ts_probe_first_pcr(const uint8_t *data, int size, int pcrPidHint,
                         int programNumberOrIndex, TsAnchor *out, int *outPcrPid);

// ---- 索引ファイル(.tsidx) ---------------------------------------------------
// docs/design/ts_seek_index.md §2-1 のバイナリレイアウトに対応する。
struct TsIndexHeader {
    int32_t formatVersion = 1;
    int32_t flags = 0;  // bit0: 不連続あり, bit1: PID構成テーブルあり(v1では未使用)
    int32_t unitSize = 188;
    int32_t gridIntervalMs = 1000;
    int64_t fileSize = 0;
    int64_t firstPcr90k = 0;
    int64_t lastPcr90k = 0;
    int64_t durationUsMeasured = 0;
    int64_t ptsPcrBiasUs = 0;
    int32_t pcrPid = 0;
    int32_t videoPid = 0;
    int32_t audio1Pid = 0;
    int32_t audio2Pid = 0;
    int32_t programNumber = 0;
    int32_t gridCount = 0;
    int32_t pidTimelineOffset = 0;  // v1では常に0(将来用の予約)
    int32_t pidTimelineLength = 0;
};

enum TsIndexFlags : int32_t {
    kTsIndexFlagDiscontinuity = 1 << 0,
    kTsIndexFlagPidTimeline = 1 << 1,
};

// アンカー列 → 1秒グリッドへ変換(floorアンカー・強制単調化)。
// packetIndex[i] = 「経過時刻が i 秒以下の最後のPCRパケット」のパケット番号(rawByteOffset/unitSize)。
// 非単調点を検出したら *outDiscontinuityCount に件数を積む(呼び出し側でflagsに反映する)。
bool ts_index_build_grid(const std::vector<TsAnchor> &anchors, int unitSize, int gridIntervalMs,
                          std::vector<uint32_t> *outGrid, int *outDiscontinuityCount);

// 末尾にCRC32を付与してシリアライズする。
bool ts_index_serialize(const TsIndexHeader &hdr, const std::vector<uint32_t> &grid,
                         std::vector<uint8_t> *out);

// CRC検証込みでデシリアライズする。壊れている/切り詰められている場合は false を返す。
bool ts_index_deserialize(const uint8_t *data, int size, TsIndexHeader *hdr,
                           std::vector<uint32_t> *grid);

// 構築済みグリッドから、指定時刻(us)に対応する生バイト位置を解決する(floorアンカー + 区間内線形補間)。
// 索引の対象外の時刻(t=0未満やgridCountを超える等)は境界値にクランプする。
int64_t ts_index_resolve_byte_position(const TsIndexHeader &hdr, const std::vector<uint32_t> &grid,
                                        int64_t timeUs);

#endif
