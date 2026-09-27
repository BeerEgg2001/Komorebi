#include "tsindex.hpp"

#include <algorithm>
#include <cstring>

// ---- CTsPcrScanner ---------------------------------------------------------

CTsPcrScanner::CTsPcrScanner()
    : m_programNumberOrIndex(-1),
      m_anchorIntervalPcr(90000),
      m_residual(),
      m_residualStartOffset(0),
      m_unitSize(0),
      m_pat(),
      m_pmtPsi(),
      m_pmtPid(-1),
      m_videoPid(0),
      m_audio1Pid(0),
      m_audio2Pid(0),
      m_pcrPid(0),
      m_pcr(-1),
      m_firstPcr(-1),
      m_lastPcr(-1),
      m_firstPts(-1),
      m_hasFirstPts(false),
      m_hasLastAnchorPcr(false),
      m_lastAnchorPcr(0),
      m_pidChangeCount(0),
      m_lastVideoPid(0),
      m_lastAudio1Pid(0),
      m_lastAudio2Pid(0),
      m_anchors() {
}

void CTsPcrScanner::SetProgramNumberOrIndex(int n) { m_programNumberOrIndex = n; }
void CTsPcrScanner::SetAnchorIntervalPcr(int64_t interval) { m_anchorIntervalPcr = interval; }

void CTsPcrScanner::ResolvePmt(const PSI &psi) {
    if (psi.section_length < 9) return;
    const uint8_t *table = psi.data;
    int pcrPid = ((table[8] & 0x1F) << 8) | table[9];
    if (pcrPid == 0x1FFF) {
        m_pcrPid = 0;
        m_pcr = -1;
    } else {
        m_pcrPid = pcrPid;
    }
    int programInfoLength = ((table[10] & 0x03) << 8) | table[11];
    int pos = 3 + 9 + programInfoLength;
    int tableLen = 3 + psi.section_length - 4;  // CRC32分を除く
    if (pos > tableLen) return;

    m_videoPid = 0;
    m_audio1Pid = 0;
    m_audio2Pid = 0;

    // 簡略化した解決ロジック: component_tagは見ず、stream_typeの並び順だけで
    // video/audio1/audio2を割り当てる(Phase 0のスキャナはPID発見が目的で、
    // CServiceFilterのような厳密なリマックスは行わない)。
    while (pos + 4 < tableLen) {
        uint8_t streamType = table[pos];
        int esPid = ((table[pos + 1] & 0x1F) << 8) | table[pos + 2];
        int esInfoLength = ((table[pos + 3] & 0x03) << 8) | table[pos + 4];
        if (pos + 5 + esInfoLength > tableLen) break;

        bool isVideo = (streamType == 0x02 || streamType == 0x1B || streamType == 0x24);
        bool isAudio = (streamType == 0x0F || streamType == 0x04);
        if (isVideo && m_videoPid == 0) {
            m_videoPid = esPid;
        } else if (isAudio) {
            if (m_audio1Pid == 0) {
                m_audio1Pid = esPid;
            } else if (m_audio2Pid == 0 && esPid != m_audio1Pid) {
                m_audio2Pid = esPid;
            }
        }
        pos += 5 + esInfoLength;
    }
}

void CTsPcrScanner::ProcessPacket(const uint8_t *packet, int64_t unitByteOffset) {
    if (m_programNumberOrIndex == 0) {
        return;  // フィルタしない設定(スキャナとしては意味を持たないため何もしない)
    }

    int unitStart = extract_ts_header_unit_start(packet);
    int pid = extract_ts_header_pid(packet);
    int adaptation = extract_ts_header_adaptation(packet);
    int counter = extract_ts_header_counter(packet);
    int payloadSize = get_ts_payload_size(packet);
    const uint8_t *payload = packet + 188 - payloadSize;
    (void)adaptation;

    if (pid == 0) {
        extract_pat(&m_pat, payload, payloadSize, unitStart, counter);
        int newPmtPid = -1;
        if (m_programNumberOrIndex > 0) {
            for (const auto &ref : m_pat.pmt) {
                if (ref.program_number == m_programNumberOrIndex) {
                    newPmtPid = ref.pmt_pid;
                    break;
                }
            }
        } else {
            // 負値: PAT内のN番目(NITを除く、1-indexed)。既定(-1)は先頭。
            // CServiceFilter::FindTargetPmtRef (servicefilter.cpp:277-291) と同じ規則。
            int index = -m_programNumberOrIndex;
            for (const auto &ref : m_pat.pmt) {
                if (ref.program_number == 0) continue;  // NITを除く
                if (--index == 0) {
                    newPmtPid = ref.pmt_pid;
                    break;
                }
            }
        }
        if (newPmtPid < 0) {
            m_videoPid = m_audio1Pid = m_audio2Pid = 0;
            m_pcrPid = 0;
            m_pcr = -1;
        }
        m_pmtPid = newPmtPid;
    } else if (m_pmtPid >= 0 && pid == m_pmtPid) {
        int done;
        do {
            done = extract_psi(&m_pmtPsi, payload, payloadSize, unitStart, counter);
            if (m_pmtPsi.version_number && m_pmtPsi.table_id == 2 && m_pmtPsi.current_next_indicator) {
                ResolvePmt(m_pmtPsi);
            }
        } while (!done);
    } else if (m_pcrPid > 0 && pid == m_pcrPid) {
        int64_t pcr = extract_pcr(packet);
        if (pcr >= 0) {
            m_pcr = pcr;
            if (m_firstPcr < 0) m_firstPcr = pcr;
            m_lastPcr = pcr;

            bool shouldAdd;
            if (!m_hasLastAnchorPcr) {
                shouldAdd = true;
            } else {
                int64_t diff = pcr_diff(m_lastAnchorPcr, pcr);
                shouldAdd = diff >= m_anchorIntervalPcr;
            }
            if (shouldAdd) {
                m_anchors.push_back(TsAnchor{pcr, unitByteOffset});
                m_hasLastAnchorPcr = true;
                m_lastAnchorPcr = pcr;
            }
        }
    }

    // 最初のPES PTS(ptsPcrBias算出用。映像 or 主音声のどちらか先に見つかった方)
    if (!m_hasFirstPts && unitStart && (pid == m_videoPid || pid == m_audio1Pid) && payloadSize >= 14 &&
        payload[0] == 0 && payload[1] == 0 && payload[2] == 1) {
        int streamId = payload[3];
        bool isVideoStream = (streamId & 0xF0) == 0xE0;
        bool isAudioStream = (streamId & 0xE0) == 0xC0;
        if (isVideoStream || isAudioStream) {
            int ptsDtsFlags = payload[7] >> 6;
            if (ptsDtsFlags >= 2) {
                int64_t pts = (payload[13] >> 1) |
                               (payload[12] << 7) |
                               ((payload[11] & 0xFE) << 14) |
                               (payload[10] << 22) |
                               (static_cast<int64_t>(payload[9] & 0x0E) << 29);
                m_firstPts = pts;
                m_hasFirstPts = true;
            }
        }
    }

    // PID構成変化の検知(T6用の簡易カウンタ。索引ビルド自体はこれを使わないが、
    // --selftest / --verify で「破綻していないか」の傍証として利用する)。
    if (m_videoPid != m_lastVideoPid || m_audio1Pid != m_lastAudio1Pid || m_audio2Pid != m_lastAudio2Pid) {
        if (m_lastVideoPid != 0 || m_lastAudio1Pid != 0 || m_lastAudio2Pid != 0) {
            ++m_pidChangeCount;
        }
        m_lastVideoPid = m_videoPid;
        m_lastAudio1Pid = m_audio1Pid;
        m_lastAudio2Pid = m_audio2Pid;
    }
}

void CTsPcrScanner::AddData(const uint8_t *data, int size, int64_t baseRawOffset) {
    if (size <= 0) return;
    if (m_residual.empty()) {
        m_residualStartOffset = baseRawOffset;
    }
    m_residual.insert(m_residual.end(), data, data + size);

    if (m_unitSize == 0) {
        // 1バイトずつ投入されても誤検出しないよう、十分な量が溜まるまで判定を待つ
        // (resync_ts自体は少量データでも「たまたま」誤判定しうるため)。
        constexpr size_t kMinResyncBytes = 4096;
        if (m_residual.size() < kMinResyncBytes) {
            return;
        }
        int guessedUnitSize = 0;
        int offset = resync_ts(m_residual.data(), static_cast<int>(m_residual.size()), &guessedUnitSize);
        if (guessedUnitSize == 0) {
            // 同期パターンを確立できなかった。際限なく溜め込まないよう上限を設ける。
            constexpr size_t kMaxResyncBuffer = 1 << 20;  // 1MB
            if (m_residual.size() > kMaxResyncBuffer) {
                size_t drop = m_residual.size() - kMaxResyncBuffer;
                m_residual.erase(m_residual.begin(), m_residual.begin() + drop);
                m_residualStartOffset += static_cast<int64_t>(drop);
            }
            return;
        }
        m_unitSize = guessedUnitSize;
        if (offset > 0) {
            m_residual.erase(m_residual.begin(), m_residual.begin() + offset);
            m_residualStartOffset += offset;
        }
    }

    int numFullUnits = static_cast<int>(m_residual.size()) / m_unitSize;
    for (int i = 0; i < numFullUnits; ++i) {
        // resync_ts()が返すoffsetは常に「同期バイト(0x47)そのものの位置」であり、
        // 呼び出し元でresidualを既にそのoffsetまで捨てているため、ここでの unit は
        // unitSize(188/192/204)によらず常にTSパケット本体(同期バイト)を指す。
        // (M2TSの4バイトタイムスタンプは同期バイトの「手前」にあるため、ここで
        // 追加のオフセット調整は不要。誤って+4する実装ミスがあったので注記として残す。)
        const uint8_t *packet = m_residual.data() + static_cast<size_t>(i) * m_unitSize;
        int64_t unitOffset = m_residualStartOffset + static_cast<int64_t>(i) * m_unitSize;
        if (packet[0] == 0x47) {
            ProcessPacket(packet, unitOffset);
        }
        // 同期バイトが崩れているユニットは黙ってスキップする(壊れたファイルへの耐性。T8)。
    }
    int consumed = numFullUnits * m_unitSize;
    if (consumed > 0) {
        m_residual.erase(m_residual.begin(), m_residual.begin() + consumed);
        m_residualStartOffset += consumed;
    }
}

// ---- ts_probe_first_pcr ----------------------------------------------------

bool ts_probe_first_pcr(const uint8_t *data, int size, int pcrPidHint,
                         int programNumberOrIndex, TsAnchor *out, int *outPcrPid) {
    CTsPcrScanner scanner;
    scanner.SetProgramNumberOrIndex(programNumberOrIndex);
    scanner.SetAnchorIntervalPcr(0);  // 最初の1個をすぐアンカー化させる
    scanner.AddData(data, size, 0);
    const auto &anchors = scanner.GetAnchors();
    if (!anchors.empty()) {
        *out = anchors.front();
        if (outPcrPid) *outPcrPid = scanner.GetPcrPid();
        return true;
    }

    // PAT/PMTがバッファ内に見つからなかった場合のフォールバック:
    // pcrPidHintが分かっていれば、そのPIDに対して直接PCRを探す。
    if (pcrPidHint >= 0) {
        int unitSize = 0;
        int offset = resync_ts(data, size, &unitSize);
        if (unitSize == 0) return false;
        for (int i = offset; i + unitSize <= size; i += unitSize) {
            // resync_ts()が返すoffsetは同期バイトそのものの位置なので、+4は不要
            // (AddData()内の同種のバグ修正と同じ理由。tsindex.cpp内のAddData実装コメント参照)。
            const uint8_t *packet = data + i;
            if (packet[0] != 0x47) continue;
            if (extract_ts_header_pid(packet) != pcrPidHint) continue;
            int64_t pcr = extract_pcr(packet);
            if (pcr >= 0) {
                out->pcr90k = pcr;
                out->rawByteOffset = i;
                if (outPcrPid) *outPcrPid = pcrPidHint;
                return true;
            }
        }
    }
    return false;
}

// ---- グリッド構築 -----------------------------------------------------------

bool ts_index_build_grid(const std::vector<TsAnchor> &anchors, int unitSize, int gridIntervalMs,
                          std::vector<uint32_t> *outGrid, int *outDiscontinuityCount) {
    outGrid->clear();
    if (outDiscontinuityCount) *outDiscontinuityCount = 0;
    if (anchors.empty() || unitSize <= 0 || gridIntervalMs <= 0) return false;

    // 1. 各アンカーの「経過時間(秒)」を累積で求める。非単調点(PCR不連続)は検知してならす。
    std::vector<double> elapsedSec(anchors.size());
    elapsedSec[0] = 0.0;

    // アンカーは「前回から一定PCR以上進んだとき」に積まれる設計のため、通常は
    // ほぼ一定間隔になる。この上限を大きく超える/負になる場合は不連続とみなす。
    constexpr double kMaxPlausibleStepSec = 30.0;
    constexpr double kNominalStepSecFallback = 1.0;

    int discontinuityCount = 0;
    for (size_t i = 1; i < anchors.size(); ++i) {
        int64_t diff90k = pcr_diff(anchors[i - 1].pcr90k, anchors[i].pcr90k);
        double stepSec = static_cast<double>(diff90k) / 90000.0;
        if (stepSec < 0.0 || stepSec > kMaxPlausibleStepSec) {
            ++discontinuityCount;
            stepSec = kNominalStepSecFallback;
        }
        elapsedSec[i] = elapsedSec[i - 1] + stepSec;
    }
    if (outDiscontinuityCount) *outDiscontinuityCount = discontinuityCount;

    double totalDurationSec = elapsedSec.back();
    int gridCount = static_cast<int>(totalDurationSec * 1000.0 / gridIntervalMs) + 1;
    if (gridCount < 1) gridCount = 1;

    outGrid->assign(static_cast<size_t>(gridCount), 0);
    double gridIntervalSec = gridIntervalMs / 1000.0;

    // floorアンカー: グリッド時刻 g*interval 以下の最後のアンカーを2ポインタで単調に求める。
    size_t anchorIdx = 0;
    for (int g = 0; g < gridCount; ++g) {
        double t = g * gridIntervalSec;
        while (anchorIdx + 1 < anchors.size() && elapsedSec[anchorIdx + 1] <= t) {
            ++anchorIdx;
        }
        (*outGrid)[static_cast<size_t>(g)] =
            static_cast<uint32_t>(anchors[anchorIdx].rawByteOffset / unitSize);
    }
    return true;
}

// ---- シリアライズ/デシリアライズ --------------------------------------------

namespace {

void PutU16LE(std::vector<uint8_t> &buf, uint16_t v) {
    buf.push_back(static_cast<uint8_t>(v & 0xFF));
    buf.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
}
void PutU32LE(std::vector<uint8_t> &buf, uint32_t v) {
    for (int i = 0; i < 4; ++i) buf.push_back(static_cast<uint8_t>((v >> (8 * i)) & 0xFF));
}
void PutU64LE(std::vector<uint8_t> &buf, uint64_t v) {
    for (int i = 0; i < 8; ++i) buf.push_back(static_cast<uint8_t>((v >> (8 * i)) & 0xFF));
}
uint32_t GetU32LE(const uint8_t *p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}
uint64_t GetU64LE(const uint8_t *p) {
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i) v |= static_cast<uint64_t>(p[i]) << (8 * i);
    return v;
}

}  // namespace

bool ts_index_serialize(const TsIndexHeader &hdr, const std::vector<uint32_t> &grid,
                         std::vector<uint8_t> *out) {
    if (hdr.gridCount != static_cast<int32_t>(grid.size())) return false;
    out->clear();
    out->reserve(88 + grid.size() * 4 + 4);

    out->push_back('T');
    out->push_back('S');
    out->push_back('I');
    out->push_back('X');
    PutU16LE(*out, static_cast<uint16_t>(hdr.formatVersion));
    PutU16LE(*out, static_cast<uint16_t>(hdr.flags));
    PutU64LE(*out, static_cast<uint64_t>(hdr.fileSize));
    PutU32LE(*out, static_cast<uint32_t>(hdr.unitSize));
    PutU32LE(*out, static_cast<uint32_t>(hdr.gridIntervalMs));
    PutU64LE(*out, static_cast<uint64_t>(hdr.firstPcr90k));
    PutU64LE(*out, static_cast<uint64_t>(hdr.lastPcr90k));
    PutU64LE(*out, static_cast<uint64_t>(hdr.durationUsMeasured));
    PutU64LE(*out, static_cast<uint64_t>(hdr.ptsPcrBiasUs));
    PutU32LE(*out, static_cast<uint32_t>(hdr.pcrPid));
    PutU32LE(*out, static_cast<uint32_t>(hdr.videoPid));
    PutU32LE(*out, static_cast<uint32_t>(hdr.audio1Pid));
    PutU32LE(*out, static_cast<uint32_t>(hdr.audio2Pid));
    PutU32LE(*out, static_cast<uint32_t>(hdr.programNumber));
    PutU32LE(*out, static_cast<uint32_t>(hdr.gridCount));
    PutU32LE(*out, static_cast<uint32_t>(hdr.pidTimelineOffset));
    PutU32LE(*out, static_cast<uint32_t>(hdr.pidTimelineLength));

    if (out->size() != 88) return false;  // オフセット計算の自己検証(設計書§2-1)

    for (uint32_t v : grid) PutU32LE(*out, v);

    uint32_t crc = calc_crc32(out->data(), static_cast<int>(out->size()));
    PutU32LE(*out, crc);
    return true;
}

bool ts_index_deserialize(const uint8_t *data, int size, TsIndexHeader *hdr,
                           std::vector<uint32_t> *grid) {
    if (size < 88 + 4) return false;
    if (!(data[0] == 'T' && data[1] == 'S' && data[2] == 'I' && data[3] == 'X')) return false;

    uint32_t crcStored = GetU32LE(data + size - 4);
    uint32_t crcComputed = calc_crc32(data, size - 4);
    if (crcStored != crcComputed) return false;

    TsIndexHeader h;
    h.formatVersion = data[4] | (data[5] << 8);
    h.flags = data[6] | (data[7] << 8);
    h.fileSize = static_cast<int64_t>(GetU64LE(data + 8));
    h.unitSize = static_cast<int32_t>(GetU32LE(data + 16));
    h.gridIntervalMs = static_cast<int32_t>(GetU32LE(data + 20));
    h.firstPcr90k = static_cast<int64_t>(GetU64LE(data + 24));
    h.lastPcr90k = static_cast<int64_t>(GetU64LE(data + 32));
    h.durationUsMeasured = static_cast<int64_t>(GetU64LE(data + 40));
    h.ptsPcrBiasUs = static_cast<int64_t>(GetU64LE(data + 48));
    h.pcrPid = static_cast<int32_t>(GetU32LE(data + 56));
    h.videoPid = static_cast<int32_t>(GetU32LE(data + 60));
    h.audio1Pid = static_cast<int32_t>(GetU32LE(data + 64));
    h.audio2Pid = static_cast<int32_t>(GetU32LE(data + 68));
    h.programNumber = static_cast<int32_t>(GetU32LE(data + 72));
    h.gridCount = static_cast<int32_t>(GetU32LE(data + 76));
    h.pidTimelineOffset = static_cast<int32_t>(GetU32LE(data + 80));
    h.pidTimelineLength = static_cast<int32_t>(GetU32LE(data + 84));

    if (h.gridCount < 0) return false;
    int64_t expectedSize = 88 + static_cast<int64_t>(h.gridCount) * 4 + 4;
    if (expectedSize != size) return false;  // 切り詰め/余剰バイトを拒否

    grid->resize(static_cast<size_t>(h.gridCount));
    for (int i = 0; i < h.gridCount; ++i) {
        (*grid)[static_cast<size_t>(i)] = GetU32LE(data + 88 + i * 4);
    }
    *hdr = h;
    return true;
}

int64_t ts_index_resolve_byte_position(const TsIndexHeader &hdr, const std::vector<uint32_t> &grid,
                                        int64_t timeUs) {
    if (grid.empty() || hdr.unitSize <= 0) return 0;
    double gridIntervalSec = hdr.gridIntervalMs / 1000.0;
    double tSec = static_cast<double>(timeUs) / 1000000.0;
    if (tSec < 0.0) tSec = 0.0;
    double maxTSec = static_cast<double>(grid.size() - 1) * gridIntervalSec;
    if (tSec > maxTSec) tSec = maxTSec;

    double gridPos = tSec / gridIntervalSec;
    int idx0 = static_cast<int>(gridPos);
    if (idx0 >= static_cast<int>(grid.size()) - 1) {
        return static_cast<int64_t>(grid.back()) * hdr.unitSize;
    }
    int idx1 = idx0 + 1;
    double frac = gridPos - idx0;
    int64_t pos0 = static_cast<int64_t>(grid[static_cast<size_t>(idx0)]) * hdr.unitSize;
    int64_t pos1 = static_cast<int64_t>(grid[static_cast<size_t>(idx1)]) * hdr.unitSize;
    if (pos1 < pos0) return pos0;  // 単調性が崩れている場合は安全側(idx0)を返す
    return pos0 + static_cast<int64_t>(static_cast<double>(pos1 - pos0) * frac);
}
