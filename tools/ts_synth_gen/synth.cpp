#include "synth.hpp"

#include "util.hpp"  // app/src/main/cpp/util.hpp (calc_crc32) を直接参照

#include <cmath>
#include <cstring>
#include <sstream>

namespace synth {

namespace {

// ---- PID割り当て(固定) --------------------------------------------------
constexpr int PAT_PID = 0x0000;
constexpr int PMT_PID = 0x1000;
constexpr int VIDEO_PID = 0x0100;
constexpr int AUDIO1_PID = 0x0110;
constexpr int AUDIO2_PID = 0x0111;  // --pid-change 注入後にのみ出現

// ---- タイミング定数 -------------------------------------------------------
// PCR挿入間隔: 50ms (ARIB規定の上限100msの半分。設計書§7-1の指定通り)
constexpr double kTickSec = 0.05;
constexpr int64_t kTickPcrTicks = 4500;  // 90000Hz * 0.05s
// PAT/PMTの再送間隔: 実放送に近い100ms相当(2tick)
constexpr int kPatPmtIntervalTicks = 2;
// 映像キーフレーム間隔: 0.5秒相当(10tick)
constexpr int kKeyframeIntervalTicks = 10;
// PTSはPCRよりわずかに先行させる(現実のエンコーダに近い挙動。§3-3のptsPcrBias相当)
constexpr int64_t kPtsPcrBiasTicks = 4500;  // 50ms

constexpr uint64_t kPcr33Mask = 0x1FFFFFFFFULL;

// ---- バイト書き込みヘルパ --------------------------------------------------

void WriteTimestamp5Bytes(uint8_t *out, int prefix4, int64_t ts33) {
    out[0] = static_cast<uint8_t>((prefix4 << 4) | (((ts33 >> 30) & 0x7) << 1) | 1);
    out[1] = static_cast<uint8_t>((ts33 >> 22) & 0xFF);
    out[2] = static_cast<uint8_t>((((ts33 >> 15) & 0x7F) << 1) | 1);
    out[3] = static_cast<uint8_t>((ts33 >> 7) & 0xFF);
    out[4] = static_cast<uint8_t>(((ts33 & 0x7F) << 1) | 1);
}

// TSヘッダ(4バイト)を書き込む。payloadがあるパケットのみ counter を進める(規格通り)。
void WriteTsHeader(uint8_t *pkt, int pid, bool unitStart, int adaptationFieldControl, uint8_t &counter) {
    pkt[0] = 0x47;
    pkt[1] = static_cast<uint8_t>((unitStart ? 0x40 : 0x00) | ((pid >> 8) & 0x1F));
    pkt[2] = static_cast<uint8_t>(pid & 0xFF);
    pkt[3] = static_cast<uint8_t>((adaptationFieldControl << 4) | (counter & 0x0F));
    if (adaptationFieldControl & 1) {
        counter = (counter + 1) & 0x0F;
    }
}

// アダプテーションフィールドにPCRを書き込む。長さは常に7(flags1 + PCR6)固定(スタッフィング無し)。
// 戻り値: アダプテーションフィールドが占有した総バイト数(length自身のバイトを含む)= 8
int WriteAdaptationWithPcr(uint8_t *pkt, int64_t pcr33, bool randomAccess, bool discontinuity) {
    pkt[4] = 7;  // adaptation_field_length
    uint8_t flags = 0x10;  // PCR_flag
    if (randomAccess) flags |= 0x40;
    if (discontinuity) flags |= 0x80;
    pkt[5] = flags;
    uint64_t pcr = static_cast<uint64_t>(pcr33) & kPcr33Mask;
    // PCR(48bit) = base(33bit) + reserved(6bit, 全て1) + extension(9bit, 常に0)
    pkt[6] = static_cast<uint8_t>((pcr >> 25) & 0xFF);
    pkt[7] = static_cast<uint8_t>((pcr >> 17) & 0xFF);
    pkt[8] = static_cast<uint8_t>((pcr >> 9) & 0xFF);
    pkt[9] = static_cast<uint8_t>((pcr >> 1) & 0xFF);
    pkt[10] = static_cast<uint8_t>(((pcr & 1) << 7) | 0x7E);  // 下位1bit + reserved(6bit=111111) + extension上位1bit(0)
    pkt[11] = 0x00;  // extension下位8bit
    return 8;
}

// 188バイトのTSパケットを1個組み立てる。payloadFillStart は payload を
// 決定的なパターン(デバッグ用途)で埋めるための開始値。
void FillPayloadPattern(uint8_t *dst, int len, uint32_t seed) {
    for (int i = 0; i < len; ++i) {
        dst[i] = static_cast<uint8_t>((seed + i) & 0xFF);
    }
}

// PSI(PAT/PMT)セクションを1パケットに詰める(pointer_field付き、スタッフィングで188バイトに充填)。
void BuildPsiPacket(uint8_t *pkt, int pid, const std::vector<uint8_t> &section, uint8_t &counter) {
    WriteTsHeader(pkt, pid, /*unitStart=*/true, /*adaptationFieldControl=*/1, counter);
    pkt[4] = 0x00;  // pointer_field
    size_t copyLen = section.size();
    std::memcpy(pkt + 5, section.data(), copyLen);
    std::memset(pkt + 5 + copyLen, 0xFF, 188 - 5 - copyLen);
}

std::vector<uint8_t> BuildPatSection(int transportStreamId, int versionNumber,
                                      int programNumber, int pmtPid) {
    std::vector<uint8_t> table(12, 0);
    table[0] = 0x00;  // table_id = PAT
    // section_length は末尾で確定
    table[3] = static_cast<uint8_t>(transportStreamId >> 8);
    table[4] = static_cast<uint8_t>(transportStreamId);
    table[5] = static_cast<uint8_t>(0xC0 | ((versionNumber & 0x1F) << 1) | 0x01);  // current_next=1
    table[6] = 0x00;  // section_number
    table[7] = 0x00;  // last_section_number
    table[8] = static_cast<uint8_t>(programNumber >> 8);
    table[9] = static_cast<uint8_t>(programNumber);
    table[10] = static_cast<uint8_t>(0xE0 | ((pmtPid >> 8) & 0x1F));
    table[11] = static_cast<uint8_t>(pmtPid);

    int sectionLength = static_cast<int>(table.size()) - 3 + 4 /*CRC32*/;
    table[1] = static_cast<uint8_t>(0xB0 | ((sectionLength >> 8) & 0x0F));
    table[2] = static_cast<uint8_t>(sectionLength);

    uint32_t crc = calc_crc32(table.data(), static_cast<int>(table.size()));
    table.push_back(static_cast<uint8_t>((crc >> 24) & 0xFF));
    table.push_back(static_cast<uint8_t>((crc >> 16) & 0xFF));
    table.push_back(static_cast<uint8_t>((crc >> 8) & 0xFF));
    table.push_back(static_cast<uint8_t>(crc & 0xFF));
    return table;
}

struct EsEntry {
    uint8_t streamType;
    int pid;
};

std::vector<uint8_t> BuildPmtSection(int programNumber, int versionNumber, int pcrPid,
                                      const std::vector<EsEntry> &streams) {
    std::vector<uint8_t> table(12, 0);
    table[0] = 0x02;  // table_id = PMT
    table[3] = static_cast<uint8_t>(programNumber >> 8);
    table[4] = static_cast<uint8_t>(programNumber);
    table[5] = static_cast<uint8_t>(0xC0 | ((versionNumber & 0x1F) << 1) | 0x01);
    table[6] = 0x00;  // section_number
    table[7] = 0x00;  // last_section_number
    table[8] = static_cast<uint8_t>(0xE0 | ((pcrPid >> 8) & 0x1F));
    table[9] = static_cast<uint8_t>(pcrPid);
    table[10] = 0xF0;  // program_info_length = 0
    table[11] = 0x00;

    for (const auto &es : streams) {
        table.push_back(es.streamType);
        table.push_back(static_cast<uint8_t>(0xE0 | ((es.pid >> 8) & 0x1F)));
        table.push_back(static_cast<uint8_t>(es.pid));
        table.push_back(0xF0);  // ES_info_length = 0
        table.push_back(0x00);
    }

    int sectionLength = static_cast<int>(table.size()) - 3 + 4;
    table[1] = static_cast<uint8_t>(0xB0 | ((sectionLength >> 8) & 0x0F));
    table[2] = static_cast<uint8_t>(sectionLength);

    uint32_t crc = calc_crc32(table.data(), static_cast<int>(table.size()));
    table.push_back(static_cast<uint8_t>((crc >> 24) & 0xFF));
    table.push_back(static_cast<uint8_t>((crc >> 16) & 0xFF));
    table.push_back(static_cast<uint8_t>((crc >> 8) & 0xFF));
    table.push_back(static_cast<uint8_t>(crc & 0xFF));
    return table;
}

// 映像PES開始パケット(PCR同梱)を組み立てる。PES_packet_length=0(不定長、映像PESで許容)。
void BuildVideoPesStartPacket(uint8_t *pkt, int64_t pts33, int64_t pcr33, bool keyframe,
                               bool discontinuity, uint32_t fillSeed, uint8_t &counter) {
    WriteTsHeader(pkt, VIDEO_PID, /*unitStart=*/true, /*adaptationFieldControl=*/3, counter);
    int adaptBytes = WriteAdaptationWithPcr(pkt, pcr33, keyframe, discontinuity);
    uint8_t *p = pkt + 4 + adaptBytes;
    // PES header
    p[0] = 0x00; p[1] = 0x00; p[2] = 0x01; p[3] = 0xE0;  // video stream_id
    p[4] = 0x00; p[5] = 0x00;  // PES_packet_length = 0 (unbounded, 映像PESで許容)
    p[6] = 0x80;               // '10' + flags(すべて0)
    p[7] = 0x80;               // PTS_DTS_flags = '10' (PTSのみ)
    p[8] = 0x05;                // PES_header_data_length
    WriteTimestamp5Bytes(p + 9, 0x2, pts33 & 0x1FFFFFFFF);
    uint8_t *payload = p + 14;
    int payloadLen = 188 - (4 + adaptBytes) - 14;
    FillPayloadPattern(payload, payloadLen, fillSeed);
}

void BuildContinuationPacket(uint8_t *pkt, int pid, uint32_t fillSeed, uint8_t &counter) {
    WriteTsHeader(pkt, pid, /*unitStart=*/false, /*adaptationFieldControl=*/1, counter);
    FillPayloadPattern(pkt + 4, 184, fillSeed);
}

// 音声PES開始パケット(単一パケットで完結する短いダミーフレーム)を組み立てる。
void BuildAudioPesStartPacket(uint8_t *pkt, int pid, int64_t pts33, uint32_t fillSeed, uint8_t &counter) {
    WriteTsHeader(pkt, pid, /*unitStart=*/true, /*adaptationFieldControl=*/1, counter);
    uint8_t *p = pkt + 4;
    constexpr int kAudioPayloadLen = 100;
    p[0] = 0x00; p[1] = 0x00; p[2] = 0x01; p[3] = 0xC0;  // audio stream_id
    int pesPacketLength = 3 + 5 + kAudioPayloadLen;       // flags(3) + PTS(5) + payload
    p[4] = static_cast<uint8_t>((pesPacketLength >> 8) & 0xFF);
    p[5] = static_cast<uint8_t>(pesPacketLength & 0xFF);
    p[6] = 0x80;
    p[7] = 0x80;  // PTS_DTS_flags = '10'
    p[8] = 0x05;
    WriteTimestamp5Bytes(p + 9, 0x2, pts33 & 0x1FFFFFFFF);
    uint8_t *payload = p + 14;
    FillPayloadPattern(payload, kAudioPayloadLen, fillSeed);
    // 184バイトのペイロード領域のうち 14+100=114 バイトを使用。残りはスタッフィング(0xFF)。
    std::memset(payload + kAudioPayloadLen, 0xFF, 184 - 14 - kAudioPayloadLen);
}

double CycleLength(const std::vector<BitrateSegment> &profile) {
    double total = 0.0;
    for (const auto &seg : profile) total += seg.durationSec;
    return total;
}

double BitrateAt(const std::vector<BitrateSegment> &profile, double elapsedTotalSec) {
    if (profile.empty()) return 8.0e6;  // 既定: 8Mbps一定
    double cycle = CycleLength(profile);
    if (cycle <= 0.0) return profile.front().bitsPerSec;
    double t = std::fmod(elapsedTotalSec, cycle);
    double acc = 0.0;
    for (const auto &seg : profile) {
        acc += seg.durationSec;
        if (t < acc) return seg.bitsPerSec;
    }
    return profile.back().bitsPerSec;
}

}  // namespace

bool ParseBitrateProfile(const std::string &text, std::vector<BitrateSegment> *out) {
    out->clear();
    std::stringstream ss(text);
    std::string token;
    while (std::getline(ss, token, ',')) {
        if (token.empty()) continue;
        auto colonPos = token.find(':');
        if (colonPos == std::string::npos) return false;
        std::string bitratePart = token.substr(0, colonPos);
        std::string durationPart = token.substr(colonPos + 1);
        if (bitratePart.empty() || durationPart.empty()) return false;

        double multiplier = 1.0;
        char suffix = bitratePart.back();
        if (suffix == 'k' || suffix == 'K') {
            multiplier = 1e3;
            bitratePart.pop_back();
        } else if (suffix == 'm' || suffix == 'M') {
            multiplier = 1e6;
            bitratePart.pop_back();
        } else if (suffix == 'g' || suffix == 'G') {
            multiplier = 1e9;
            bitratePart.pop_back();
        }
        try {
            double bps = std::stod(bitratePart) * multiplier;
            double dur = std::stod(durationPart);
            if (bps <= 0.0 || dur <= 0.0) return false;
            out->push_back({bps, dur});
        } catch (...) {
            return false;
        }
    }
    return !out->empty();
}

SynthResult GenerateSyntheticTs(const SynthOptions &opts, const PacketSink &sink) {
    SynthResult result;
    result.unitSize = opts.unitSize;

    const int pmtVersionBase = 1;
    uint8_t patCounter = 0, pmtCounter = 0, videoCounter = 0, audio1Counter = 0, audio2Counter = 0;

    // pcrLogicalは33bitでラップしない「本当の」経過量を保持し、書き込み時のみ33bitにマスクする。
    int64_t pcrLogical = opts.pcrStart;
    int64_t videoPtsLogical = opts.pcrStart + kPtsPcrBiasTicks;
    int64_t audioPtsLogical = opts.pcrStart + kPtsPcrBiasTicks;

    bool audio2Enabled = false;
    bool discontinuityApplied = false;
    bool pidChangeApplied = false;
    int pmtVersion = pmtVersionBase;

    int64_t byteOffset = 0;
    int tickIndex = 0;
    double elapsedTotal = 0.0;

    auto emitPacket = [&](const uint8_t *pkt188) {
        if (opts.unitSize == 192) {
            uint8_t prefix[4] = {0, 0, 0, 0};  // M2TSのタイムスタンプ相当。値は使わないためゼロ固定
            sink(prefix, 4);
            byteOffset += 4;
        }
        sink(pkt188, 188);
        byteOffset += 188;
    };

    while (elapsedTotal < opts.totalDurationSec) {
        // --- 不連続/PID変化の注入判定(1回だけ) -------------------------------
        bool injectDiscontinuityThisTick = false;
        if (!discontinuityApplied && opts.pcrDiscontinuityAtSec >= 0.0 &&
            elapsedTotal >= opts.pcrDiscontinuityAtSec) {
            // 過去方向に大きくジャンプさせる(録画結合の典型例)。
            // wirePCRの単調性が崩れることを意図的に発生させる。
            pcrLogical -= 5'000'000;  // 90000Hz換算で約55.6秒分
            discontinuityApplied = true;
            injectDiscontinuityThisTick = true;
            result.discontinuityByteOffset = byteOffset;
        }
        if (!pidChangeApplied && opts.pidChangeAtSec >= 0.0 && elapsedTotal >= opts.pidChangeAtSec) {
            audio2Enabled = true;
            pidChangeApplied = true;
            pmtVersion = (pmtVersion + 1) & 0x1F;
            result.pidChangeByteOffset = byteOffset;
        }

        bool keyframeTick = (tickIndex % kKeyframeIntervalTicks) == 0;
        bool patPmtDueTick = (tickIndex % kPatPmtIntervalTicks) == 0;

        double bitrate = BitrateAt(opts.profile, elapsedTotal);
        double bytesThisTick = bitrate / 8.0 * kTickSec;
        int unitPayloadSize = opts.unitSize;  // 188 or 192。VBR配分の基準はワイヤ上の総バイト数
        int nPackets = static_cast<int>(bytesThisTick / unitPayloadSize + 0.5);
        if (nPackets < 1) nPackets = 1;

        int packetsUsed = 0;

        if (patPmtDueTick) {
            std::vector<uint8_t> patSection = BuildPatSection(1, 1, opts.programNumber, PMT_PID);
            uint8_t pkt[188];
            BuildPsiPacket(pkt, PAT_PID, patSection, patCounter);
            emitPacket(pkt);
            ++packetsUsed;

            std::vector<EsEntry> streams;
            streams.push_back({0x1b, VIDEO_PID});  // AVC video
            streams.push_back({0x0f, AUDIO1_PID});  // ADTS audio
            if (audio2Enabled) {
                streams.push_back({0x0f, AUDIO2_PID});
            }
            std::vector<uint8_t> pmtSection =
                BuildPmtSection(opts.programNumber, pmtVersion, VIDEO_PID, streams);
            uint8_t pkt2[188];
            BuildPsiPacket(pkt2, PMT_PID, pmtSection, pmtCounter);
            emitPacket(pkt2);
            ++packetsUsed;
        }

        // 1. PCRを載せた映像PES開始パケット(このtickの正解データ)
        {
            uint8_t pkt[188];
            int64_t wirePcr = pcrLogical & static_cast<int64_t>(kPcr33Mask);
            BuildVideoPesStartPacket(pkt, videoPtsLogical, wirePcr, keyframeTick,
                                      injectDiscontinuityThisTick,
                                      static_cast<uint32_t>(tickIndex * 7 + 1), videoCounter);
            int64_t thisPacketOffset = byteOffset;
            emitPacket(pkt);
            ++packetsUsed;

            result.truth.push_back({elapsedTotal, thisPacketOffset, wirePcr});
            if (result.firstPcr90k < 0) result.firstPcr90k = wirePcr;
            result.lastPcr90k = wirePcr;
        }

        // 2. 音声PES開始パケット(主音声は毎tick、副音声は有効化後のみ)
        {
            uint8_t pkt[188];
            BuildAudioPesStartPacket(pkt, AUDIO1_PID, audioPtsLogical,
                                      static_cast<uint32_t>(tickIndex * 13 + 3), audio1Counter);
            emitPacket(pkt);
            ++packetsUsed;
        }
        if (audio2Enabled) {
            uint8_t pkt[188];
            BuildAudioPesStartPacket(pkt, AUDIO2_PID, audioPtsLogical,
                                      static_cast<uint32_t>(tickIndex * 17 + 5), audio2Counter);
            emitPacket(pkt);
            ++packetsUsed;
        }

        // 3. 残りの帯域はすべて映像の続きパケットで消費する(VBRの実体)。
        while (packetsUsed < nPackets) {
            uint8_t pkt[188];
            BuildContinuationPacket(pkt, VIDEO_PID, static_cast<uint32_t>(tickIndex * 3 + packetsUsed), videoCounter);
            emitPacket(pkt);
            ++packetsUsed;
        }

        pcrLogical += kTickPcrTicks;
        videoPtsLogical += kTickPcrTicks;
        audioPtsLogical += kTickPcrTicks;
        elapsedTotal += kTickSec;
        ++tickIndex;
    }

    result.totalBytes = byteOffset;
    return result;
}

}  // namespace synth
