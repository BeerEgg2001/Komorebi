// ts_synth_gen: 合成VBR TSストリーム生成器 (コアロジック)
//
// docs/design/ts_seek_index.md §7-1「tools/ts_synth_gen/」の要件を満たす。
// PAT/PMT/PCR/映像PES/音声PESを持つ、構造的に妥当なMPEG-TSストリームを
// 生成する。映像・音声の中身は実際にデコードできるものではなく、
// PCR/PTS/PSIパースのテストに必要な構造のみを持つダミーデータである。
//
// このヘッダは tools/ts_index_builder/ の --selftest からも直接参照される
// (tools/ts_pmt_monitor と同様、コピーせず直接参照するCMake構成)。

#ifndef TS_SYNTH_GEN_SYNTH_HPP
#define TS_SYNTH_GEN_SYNTH_HPP

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace synth {

// --bitrate-profile "4M:30,24M:30" の1区間
struct BitrateSegment {
    double bitsPerSec;
    double durationSec;
};

struct SynthOptions {
    double totalDurationSec = 60.0;
    std::vector<BitrateSegment> profile;  // 空なら既定の一定ビットレート(8Mbps)を使う
    int programNumber = 1;                // PAT/PMTのprogram_number
    int unitSize = 188;                   // 188 または 192 (M2TS: 4バイトのタイムスタンプ前置)
    int64_t pcrStart = 0;                 // 開始PCR値(90kHz, 33bitラップアラウンドの検証に使う)
    double pcrDiscontinuityAtSec = -1.0;  // -1なら注入しない。指定秒経過時点でPCRを不連続にする
    double pidChangeAtSec = -1.0;         // -1なら注入しない。指定秒経過時点で副音声PIDを追加する(PMT差し替え)
};

// 生成されたバイト列(TSパケット単位)を受け取るコールバック。
// ファイルへの直接書き込み・メモリ上への蓄積のどちらにも使えるようにするため、
// GenerateSyntheticTs() 自体はメモリ上に全データを保持しない(ストリーミング生成)。
using PacketSink = std::function<void(const uint8_t *data, size_t len)>;

// 正解データ(グラウンドトゥルース)の1エントリ。
// timeSec: ウォールクロック上の経過時間(生成器が意図した「本当の」時刻。
//          --pcr-discontinuity 注入後もPCR不連続の影響を受けない値)
// byteOffset: その時刻に相当するPCR保持パケットの、ファイル先頭からのバイト位置
// pcr90k: そのパケットに実際に書き込まれたPCR値(不連続注入後は timeSec と整合しなくなる)
struct TruthEntry {
    double timeSec;
    int64_t byteOffset;
    int64_t pcr90k;
};

struct SynthResult {
    std::vector<TruthEntry> truth;
    int64_t firstPcr90k = -1;
    int64_t lastPcr90k = -1;
    int unitSize = 188;
    int64_t totalBytes = 0;
    // 実際に発生した不連続/PID変化のバイト位置(0なら発生していないか--optionsで無効化)
    int64_t discontinuityByteOffset = -1;
    int64_t pidChangeByteOffset = -1;
};

// 合成TSストリームを生成し、188(or192)バイト単位で sink に渡す。
SynthResult GenerateSyntheticTs(const SynthOptions &opts, const PacketSink &sink);

// "4M:30,24M:30" のようなプロファイル文字列をパースする。
// 単位: K/M/G (bps, 10進)。省略時は素のbpsとして扱う。
// 例: "4000000:30,24M:30" も可。パース失敗時は false を返す。
bool ParseBitrateProfile(const std::string &text, std::vector<BitrateSegment> *out);

}  // namespace synth

#endif
