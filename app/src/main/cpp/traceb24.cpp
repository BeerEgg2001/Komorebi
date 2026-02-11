#include "traceb24.hpp"
#include <algorithm>

CTraceB24Caption::CTraceB24Caption()
    : m_fp(nullptr)
    , m_firstPmtPid(0)
    , m_captionPid(0)
    , m_superimposePid(0)
    , m_pcrPid(0)
    , m_pcr(-1)
{
    static const PAT zeroPat = {};
    m_pat = zeroPat;
    m_firstPmtPsi = zeroPat.psi;
}

void CTraceB24Caption::AddPacket(const uint8_t *packet)
{
    if (!m_fp) {
        return;
    }

    int unitStart = extract_ts_header_unit_start(packet);
    int pid = extract_ts_header_pid(packet);
    int adaptation = extract_ts_header_adaptation(packet);
    int counter = extract_ts_header_counter(packet);
    int payloadSize = get_ts_payload_size(packet);
    const uint8_t *payload = packet + 188 - payloadSize;

    if (pid == 0) {
        extract_pat(&m_pat, payload, payloadSize, unitStart, counter);
        auto itFirstPmt = std::find_if(m_pat.pmt.begin(), m_pat.pmt.end(), [](const PMT_REF &pmt) { return pmt.program_number != 0; });
        if (m_firstPmtPid != 0 && (itFirstPmt == m_pat.pmt.end() || itFirstPmt->pmt_pid != m_firstPmtPid)) {
            m_firstPmtPid = 0;
            static const PSI zeroPsi = {};
            m_firstPmtPsi = zeroPsi;
        }
        if (itFirstPmt != m_pat.pmt.end()) {
            m_firstPmtPid = itFirstPmt->pmt_pid;
        }
    }
    else if (pid == m_firstPmtPid) {
        int done;
        do {
            done = extract_psi(&m_firstPmtPsi, payload, payloadSize, unitStart, counter);
            if (m_firstPmtPsi.version_number && m_firstPmtPsi.table_id == 2) {
                CheckPmt(m_firstPmtPsi);
            }
        }
        while (!done);
    }
    else if (pid == m_pcrPid) {
        if (adaptation & 2) {
            int adaptationLength = packet[4];
            if (adaptationLength >= 6 && !!(packet[5] & 0x10)) {
                bool firstPcr = m_pcr < 0;
                m_pcr = (packet[10] >> 7) |
                        (packet[9] << 1) |
                        (packet[8] << 9) |
                        (packet[7] << 17) |
                        (static_cast<int64_t>(packet[6]) << 25);
                if (firstPcr) {
                    fprintf(m_fp, "pcrpid=0x%04X;pcr=%010lld\n", m_pcrPid, static_cast<long long>(m_pcr));
                    fflush(m_fp);
                }
            }
        }
    }
    else if (pid == m_captionPid || pid == m_superimposePid) {
        auto &pesPair = pid == m_captionPid ? m_captionPes : m_superimposePes;
        int &pesCounter = pesPair.first;
        std::vector<uint8_t> &pes = pesPair.second;
        if (unitStart) {
            pesCounter = counter;
            pes.assign(payload, payload + payloadSize);
        }
        else if (!pes.empty()) {
            pesCounter = (pesCounter + 1) & 0x0f;
            if (pesCounter == counter) {
                pes.insert(pes.end(), payload, payload + payloadSize);
            }
            else {
                // Ignore packets until the next unit-start
                pes.clear();
            }
        }
        if (pes.size() >= 6) {
            size_t pesPacketLength = (pes[4] << 8) | pes[5];
            if (pes.size() >= 6 + pesPacketLength) {
                // PES has been accumulated
                pes.resize(6 + pesPacketLength);
                OutputPrivateDataPes(pes, pid == m_captionPid ? m_captionDrcsList : m_superimposeDrcsList,
                                     pid == m_captionPid ? m_captionLangTags : m_superimposeLangTags);
                pes.clear();
            }
        }
    }
}

void CTraceB24Caption::CheckPmt(const PSI &psi)
{
    const uint8_t PES_PRIVATE_DATA = 0x06;

    if (psi.section_length < 9) {
        return;
    }
    const uint8_t *table = psi.data;
    m_pcrPid = ((table[8] & 0x1f) << 8) | table[9];
    if (m_pcrPid == 0x1fff) {
        m_pcr = -1;
    }
    int programInfoLength = ((table[10] & 0x03) << 8) | table[11];
    int pos = 3 + 9 + programInfoLength;
    if (psi.section_length < pos) {
        return;
    }

    int captionPid = 0;
    int superimposePid = 0;
    int tableLen = 3 + psi.section_length - 4/*CRC32*/;
    while (pos + 4 < tableLen) {
        int streamType = table[pos];
        int esPid = ((table[pos + 1] & 0x1f) << 8) | table[pos + 2];
        int esInfoLength = ((table[pos + 3] & 0x03) << 8) | table[pos + 4];
        if (pos + 5 + esInfoLength <= tableLen) {
            int componentTag = 0xff;
            for (int i = pos + 5; i + 2 < pos + 5 + esInfoLength; i += 2 + table[i + 1]) {
                // stream_identifier_descriptor
                if (table[i] == 0x52) {
                    componentTag = table[i + 2];
                    break;
                }
            }
            // ARIB caption/superimpose ("A-Profile" only)
            if (streamType == PES_PRIVATE_DATA &&
                (componentTag == 0x30 || componentTag == 0x38)) {
                if (componentTag == 0x30) {
                    captionPid = esPid;
                }
                else {
                    superimposePid = esPid;
                }
            }
        }
        pos += 5 + esInfoLength;
    }

    if (m_captionPid != captionPid) {
        m_captionPid = captionPid;
        m_captionPes.second.clear();
        m_captionDrcsList.clear();
        std::fill_n(m_captionLangTags, 8, LANG_TAG_ABSENT);
    }
    if (m_superimposePid != superimposePid) {
        m_superimposePid = superimposePid;
        m_superimposePes.second.clear();
        m_superimposeDrcsList.clear();
        std::fill_n(m_superimposeLangTags, 8, LANG_TAG_ABSENT);
    }
}

void CTraceB24Caption::OutputPrivateDataPes(const std::vector<uint8_t> &pes,
                                            std::vector<uint16_t> &drcsList, LANG_TAG_TYPE (&langTags)[8])
{
    const uint8_t PRIVATE_STREAM_1 = 0xbd;
    const uint8_t PRIVATE_STREAM_2 = 0xbf;

    size_t payloadPos = 0;
    int64_t pts = -1;
    if (pes[0] == 0 && pes[1] == 0 && pes[2] == 1) {
        int streamID = pes[3];
        if (streamID == PRIVATE_STREAM_1 && pes.size() >= 9) {
            int ptsDtsFlags = pes[7] >> 6;
            payloadPos = 9 + pes[8];
            if (ptsDtsFlags >= 2 && pes.size() >= 14) {
                pts = (pes[13] >> 1) |
                      (pes[12] << 7) |
                      ((pes[11] & 0xfe) << 14) |
                      (pes[10] << 22) |
                      (static_cast<int64_t>(pes[9] & 0x0e) << 29);
            }
        }
        else if (streamID == PRIVATE_STREAM_2) {
            payloadPos = 6;
            if (m_pcr >= 0) {
                pts = m_pcr;
            }
        }
    }
    if (payloadPos == 0 || payloadPos + 1 >= pes.size() || pts < 0) {
        return;
    }
    int dataIdentifier = pes[payloadPos];
    int privateStreamID = pes[payloadPos + 1];
    if ((dataIdentifier != 0x80 && dataIdentifier != 0x81) ||
        privateStreamID != 0xff) {
        // Not an ARIB Synchronized/Asynchronous PES data
        return;
    }

    PARSE_PRIVATE_DATA_RESULT ret = ParsePrivateData(m_buf, m_intBuf, pes.data() + payloadPos, pes.size() - payloadPos, drcsList, langTags);
    if (ret != PARSE_PRIVATE_DATA_FAILED_NEED_MANAGEMENT) {
        int64_t ptsPcrDiff = (0x200000000 + pts - m_pcr) & 0x1ffffffff;
        if (ptsPcrDiff >= 0x100000000) {
            ptsPcrDiff -= 0x200000000;
        }
        fprintf(m_fp, "pts=%010lld;pcrrel=%+08d",
                static_cast<long long>(pts),
                static_cast<int>(m_pcr < 0 ? -9999999 : std::min<int64_t>(std::max<int64_t>(ptsPcrDiff, -9999999), 9999999)));
        if (ret == PARSE_PRIVATE_DATA_SUCCEEDED) {
            for (size_t i = 0; i + 1 < m_intBuf.size(); ++i) {
                fprintf(m_fp, "%s%d", i == 0 ? ";text=" : ",", m_intBuf[i + 1] - m_intBuf[i]);
            }
        }
        fprintf(m_fp, ";b24%s", dataIdentifier == 0x81 ? "superimpose" : "caption");
        if (ret == PARSE_PRIVATE_DATA_SUCCEEDED) {
            m_buf.push_back('\n');
            fwrite(m_buf.data(), 1, m_buf.size(), m_fp);
        }
        else {
            fprintf(m_fp, "err=%s\n",
                    ret == PARSE_PRIVATE_DATA_FAILED_CRC ? "crc" :
                    ret == PARSE_PRIVATE_DATA_FAILED_UNSUPPORTED ? "unsupported" : "unknown");
        }
        fflush(m_fp);
    }
}

namespace
{
enum GS_CLASS
{
    GS_1BYTE_G,
    GS_2BYTE_G,
    GS_1BYTE_DRCS,
    GS_2BYTE_DRCS,
};

const uint8_t GS_HIRAGANA = 0x30;
const uint8_t GS_KATAKANA = 0x31;
const uint8_t GS_PROP_ASCII = 0x36;
const uint8_t GS_PROP_HIRAGANA = 0x37;
const uint8_t GS_PROP_KATAKANA = 0x38;
const uint8_t GS_JIS_KANJI1 = 0x39;
const uint8_t GS_JIS_KANJI2 = 0x3a;
const uint8_t GS_ADDITIONAL_SYMBOLS = 0x3b;
const uint8_t GS_KANJI = 0x42;
const uint8_t GS_JISX_KATAKANA = 0x49;
const uint8_t GS_ASCII = 0x4a;
const uint8_t GS_LATIN_EXTENSION = 0x4b;
const uint8_t GS_LATIN_SPECIAL = 0x4c;
const uint8_t GS_DRCS_0 = 0x40;
const uint8_t GS_DRCS_1 = 0x41;
const uint8_t GS_DRCS_15 = 0x4f;
const uint8_t GS_MACRO = 0x70;

extern const char16_t FullwidthAsciiTable[94];
extern const char16_t HiraganaTable[94];
extern const char16_t KatakanaTable[94];
extern const char16_t JisXKatakanaTable[94];
extern const char16_t LatinExtensionTable[94];
extern const char16_t LatinSpecialTable[94];
extern const char16_t JisTable[84 * 94 + 1];
extern const char32_t GaijiTable[7 * 94 + 1];
extern const uint8_t DefaultMacro[16][20];

void AddEscapedChar(std::vector<uint8_t> &buf, uint8_t c)
{
    buf.push_back('%');
    buf.push_back(((c >> 4) > 9 ? '7' : '0') + (c >> 4));
    buf.push_back(((c & 15) > 9 ? '7' : '0') + (c & 15));
}

void AddChar(std::vector<uint8_t> &buf, uint8_t c)
{
    // control characters or %
    if (c < 0x20 || c == 0x25 || c == 0x7f) {
        AddEscapedChar(buf, c);
    }
    else {
        buf.push_back(c);
    }
}

void AddChar32(std::vector<uint8_t> &buf, char32_t x)
{
    if (x < 0x80) {
        AddChar(buf, static_cast<uint8_t>(x));
    }
    else if (x < 0x800) {
        AddChar(buf, static_cast<uint8_t>(0xc0 | (x >> 6)));
        AddChar(buf, static_cast<uint8_t>(0x80 | (x & 0x3f)));
    }
    else if (x < 0x10000) {
        AddChar(buf, static_cast<uint8_t>(0xe0 | (x >> 12)));
        AddChar(buf, static_cast<uint8_t>(0x80 | ((x >> 6) & 0x3f)));
        AddChar(buf, static_cast<uint8_t>(0x80 | (x & 0x3f)));
    }
    else {
        AddChar(buf, static_cast<uint8_t>(0xf0 | (x >> 18)));
        AddChar(buf, static_cast<uint8_t>(0x80 | ((x >> 12) & 0x3f)));
        AddChar(buf, static_cast<uint8_t>(0x80 | ((x >> 6) & 0x3f)));
        AddChar(buf, static_cast<uint8_t>(0x80 | (x & 0x3f)));
    }
}

void AddAscii(std::vector<uint8_t> &buf, uint8_t c)
{
    if (c < 0x80) {
        AddChar(buf, c);
    }
    else {
        AddChar32(buf, U'\uFFFD');
    }
}

uint8_t ReadByte(const uint8_t *&data, const uint8_t *dataEnd)
{
    if (data == dataEnd) {
        return 0;
    }
    return *(data++);
}

void InitializeArib8(std::pair<GS_CLASS, uint8_t> (&gbuf)[4], int &gl, int &gr, bool isLatin)
{
    if (isLatin) {
        gbuf[0] = std::make_pair(GS_1BYTE_G, GS_ASCII);
        gbuf[2] = std::make_pair(GS_1BYTE_G, GS_LATIN_EXTENSION);
        gbuf[3] = std::make_pair(GS_1BYTE_G, GS_LATIN_SPECIAL);
    }
    else {
        gbuf[0] = std::make_pair(GS_2BYTE_G, GS_KANJI);
        gbuf[2] = std::make_pair(GS_1BYTE_G, GS_HIRAGANA);
        gbuf[3] = std::make_pair(GS_1BYTE_DRCS, GS_MACRO);
    }
    gbuf[1] = std::make_pair(GS_1BYTE_G, GS_ASCII);
    gl = 0;
    gr = 2;
}

void CheckReadableTextPosList(std::vector<int> &textPosList, const std::vector<uint8_t> &buf, bool isNextReadable)
{
    if ((textPosList.size() % 2 != 0) == isNextReadable) {
        int codeCount = 0;
        for (size_t i = 0; i < buf.size(); ++i) {
            if ((buf[i] & 0xc0) != 0x80) {
                ++codeCount;
            }
        }
        textPosList.push_back(codeCount);
    }
}

void AnalizeArib8(std::vector<uint8_t> &buf, std::vector<int> &textPosList, const uint8_t *&data, const uint8_t *dataEnd,
                  const std::vector<uint16_t> &drcsList, std::pair<GS_CLASS, uint8_t> (&gbuf)[4], int &gl, int &gr, bool isLatin)
{
    std::pair<GS_CLASS, uint8_t> *gss = nullptr;
    while (data != dataEnd) {
        uint8_t b = ReadByte(data, dataEnd);
        if (b <= 0x20) {
            // C0
            gss = nullptr;
            if (b == 0x0e) {
                // LS1
                gl = 1;
            }
            else if (b == 0x0f) {
                // LS0
                gl = 0;
            }
            else if (b == 0x19) {
                // SS2
                gss = &gbuf[2];
            }
            else if (b == 0x1b) {
                // ESC
                b = ReadByte(data, dataEnd);
                if (b == 0x24) {
                    b = ReadByte(data, dataEnd);
                    if (0x28 <= b && b <= 0x2b) {
                        uint8_t c = ReadByte(data, dataEnd);
                        if (c == 0x20) {
                            gbuf[b - 0x28] = std::make_pair(GS_2BYTE_DRCS, ReadByte(data, dataEnd));
                        }
                        else if (0x29 <= b && b <= 0x2b) {
                            gbuf[b - 0x28] = std::make_pair(GS_2BYTE_G, c);
                        }
                    }
                    else {
                        gbuf[0] = std::make_pair(GS_2BYTE_G, b);
                    }
                }
                else if (0x28 <= b && b <= 0x2b) {
                    uint8_t c = ReadByte(data, dataEnd);
                    if (c == 0x20) {
                        gbuf[b - 0x28] = std::make_pair(GS_1BYTE_DRCS, ReadByte(data, dataEnd));
                    }
                    else {
                        gbuf[b - 0x28] = std::make_pair(GS_1BYTE_G, c);
                    }
                }
                else if (b == 0x6e) {
                    gl = 2;
                }
                else if (b == 0x6f) {
                    gl = 3;
                }
                else if (b == 0x7c) {
                    gr = 3;
                }
                else if (b == 0x7d) {
                    gr = 2;
                }
                else if (b == 0x7e) {
                    gr = 1;
                }
            }
            else if (b == 0x1d) {
                // SS3
                gss = &gbuf[3];
            }
            else if (b != 0) {
                CheckReadableTextPosList(textPosList, buf, b == 0x20);
                AddChar(buf, b);
                if (b == 0x0c) {
                    // CS
                    InitializeArib8(gbuf, gl, gr, isLatin);
                }
                else if (b == 0x16) {
                    // PAPF
                    AddAscii(buf, ReadByte(data, dataEnd));
                }
                else if (b == 0x1c) {
                    // APS
                    AddAscii(buf, ReadByte(data, dataEnd));
                    AddAscii(buf, ReadByte(data, dataEnd));
                }
            }
        }
        else if (0x7f <= b && b <= 0xa0) {
            // C1
            gss = nullptr;
            if (b == 0x95) {
                // MACRO (unsupported)
                b = ReadByte(data, dataEnd);
                while (data != dataEnd) {
                    uint8_t c = ReadByte(data, dataEnd);
                    if (b == 0x95 && c == 0x4f) {
                        break;
                    }
                    b = c;
                }
            }
            else {
                if (b == 0x7f) {
                    CheckReadableTextPosList(textPosList, buf, false);
                    AddChar(buf, b);
                }
                else if (b == 0xa0) {
                    CheckReadableTextPosList(textPosList, buf, true);
                    AddChar32(buf, U'\u00A0');
                }
                else {
                    // caret notation
                    CheckReadableTextPosList(textPosList, buf, false);
                    buf.push_back('%');
                    AddChar(buf, '^');
                    AddChar(buf, b - 0x40);
                }
                if (b == 0x8b || b == 0x91 || b == 0x93 || b == 0x94 || b == 0x97 || b == 0x98) {
                    // SZX, FLC, POL, WMM, HLC, RPC
                    AddAscii(buf, ReadByte(data, dataEnd));
                }
                else if (b == 0x90) {
                    // COL
                    b = ReadByte(data, dataEnd);
                    AddAscii(buf, b);
                    if (b == 0x20) {
                        AddAscii(buf, ReadByte(data, dataEnd));
                    }
                }
                else if (b == 0x9d) {
                    // TIME
                    b = ReadByte(data, dataEnd);
                    AddAscii(buf, b);
                    if (b == 0x20) {
                        AddAscii(buf, ReadByte(data, dataEnd));
                    }
                    else {
                        while (data != dataEnd && (b < 0x40 || 0x43 < b)) {
                            b = ReadByte(data, dataEnd);
                            AddAscii(buf, b);
                        }
                    }
                }
                else if (b == 0x9b) {
                    // CSI
                    while (data != dataEnd && b != 0x20) {
                        b = ReadByte(data, dataEnd);
                        AddAscii(buf, b);
                    }
                    b = ReadByte(data, dataEnd);
                    AddAscii(buf, b);
                    if (b == 0x53) {
                        // SWF
                        InitializeArib8(gbuf, gl, gr, isLatin);
                    }
                }
            }
        }
        else if (b < 0xff) {
            // GL, GR
            std::pair<GS_CLASS, uint8_t> g = gss ? *gss : b < 0x7f ? gbuf[gl] : gbuf[gr];
            gss = nullptr;
            b &= 0x7f;
            if (g.first == GS_1BYTE_G) {
                CheckReadableTextPosList(textPosList, buf, true);
                if (g.second == GS_ASCII || g.second == GS_PROP_ASCII) {
                    if (isLatin) {
                        AddChar(buf, b);
                    }
                    else {
                        AddChar32(buf, static_cast<char32_t>(FullwidthAsciiTable[b - 0x21]));
                    }
                }
                else {
                    char16_t x = g.second == GS_HIRAGANA || g.second == GS_PROP_HIRAGANA ? HiraganaTable[b - 0x21] :
                                 g.second == GS_KATAKANA || g.second == GS_PROP_KATAKANA ? KatakanaTable[b - 0x21] :
                                 g.second == GS_JISX_KATAKANA ? JisXKatakanaTable[b - 0x21] :
                                 g.second == GS_LATIN_EXTENSION ? LatinExtensionTable[b - 0x21] :
                                 g.second == GS_LATIN_SPECIAL ? LatinSpecialTable[b - 0x21] : u'\uFFFD';
                    AddChar32(buf, static_cast<char32_t>(x));
                }
            }
            else if (g.first == GS_2BYTE_G) {
                CheckReadableTextPosList(textPosList, buf, true);
                uint8_t c = ReadByte(data, dataEnd) & 0x7f;
                if (g.second == GS_JIS_KANJI1 ||
                    g.second == GS_JIS_KANJI2 ||
                    g.second == GS_ADDITIONAL_SYMBOLS ||
                    g.second == GS_KANJI) {
                    if (b < 0x21 + 84 && 0x21 <= c && c < 0x21 + 94) {
                        AddChar32(buf, static_cast<char32_t>(JisTable[(b - 0x21) * 94 + (c - 0x21)]));
                    }
                    else {
                        int x = (b << 8) | c;
                        AddChar32(buf, 0x7521 <= x && x < 0x7521 + 94 ? GaijiTable[5 * 94 + x - 0x7521] :
                                       0x7621 <= x && x < 0x7621 + 94 ? GaijiTable[6 * 94 + x - 0x7621] :
                                       0x7a21 <= x && x < 0x7a21 + 94 ? GaijiTable[x - 0x7a21] :
                                       0x7b21 <= x && x < 0x7b21 + 94 ? GaijiTable[94 + x - 0x7b21] :
                                       0x7c21 <= x && x < 0x7c21 + 94 ? GaijiTable[2 * 94 + x - 0x7c21] :
                                       0x7d21 <= x && x < 0x7d21 + 94 ? GaijiTable[3 * 94 + x - 0x7d21] :
                                       0x7e21 <= x && x < 0x7e21 + 94 ? GaijiTable[4 * 94 + x - 0x7e21] : U'\uFFFD');
                    }
                }
                else {
                    AddChar32(buf, U'\uFFFD');
                }
            }
            else if (g.first == GS_1BYTE_DRCS) {
                if (GS_DRCS_1 <= g.second && g.second <= GS_DRCS_15) {
                    // 1-byte DRCS mapping
                    uint16_t charCode = ((g.second - GS_DRCS_0) << 8) | b;
                    char32_t x = U'\uFFFD';
                    if (!drcsList.empty()) {
                        auto it = std::find(drcsList.begin() + 1, drcsList.end(), charCode);
                        if (it != drcsList.end()) {
                            x = static_cast<char32_t>(it - 1 - drcsList.begin() + 0xec00);
                        }
                    }
                    CheckReadableTextPosList(textPosList, buf, true);
                    AddChar32(buf, x);
                }
                else if (g.second == GS_MACRO) {
                    if (0x60 <= b && b <= 0x6f) {
                        const uint8_t *macro = DefaultMacro[b & 0x0f];
                        AnalizeArib8(buf, textPosList, macro, macro + sizeof(DefaultMacro[0]), drcsList, gbuf, gl, gr, isLatin);
                    }
                    else {
                        AddChar32(buf, U'\uFFFD');
                    }
                }
                else {
                    AddChar32(buf, U'\uFFFD');
                }
            }
            else if (g.first == GS_2BYTE_DRCS) {
                uint8_t c = ReadByte(data, dataEnd) & 0x7f;
                if (g.second == GS_DRCS_0) {
                    // 2-byte DRCS mapping
                    uint16_t charCode = (b << 8) | c;
                    char32_t x = U'\uFFFD';
                    if (!drcsList.empty()) {
                        auto it = std::find(drcsList.begin() + 1, drcsList.end(), charCode);
                        if (it != drcsList.end()) {
                            x = static_cast<char32_t>(it - 1 - drcsList.begin() + 0xec00);
                        }
                    }
                    CheckReadableTextPosList(textPosList, buf, true);
                    AddChar32(buf, x);
                }
                else {
                    AddChar32(buf, U'\uFFFD');
                }
            }
        }
        else {
            gss = nullptr;
        }
    }
}

void AddArib8AsUtf8(std::vector<uint8_t> &buf, std::vector<int> &textPosList, const uint8_t *data, size_t dataSize,
                    const std::vector<uint16_t> &drcsList, bool isLatin)
{
    std::pair<GS_CLASS, uint8_t> gbuf[4];
    int gl, gr;
    InitializeArib8(gbuf, gl, gr, isLatin);
    AnalizeArib8(buf, textPosList, data, data + dataSize, drcsList, gbuf, gl, gr, isLatin);
    CheckReadableTextPosList(textPosList, buf, false);
}

size_t AddEscapedData(std::vector<uint8_t> &buf, const uint8_t *data, size_t dataSize)
{
    for (size_t i = 0; i < dataSize; ++i) {
        AddEscapedChar(buf, data[i]);
    }
    return dataSize;
}

size_t AddBase64EncodedData(std::vector<uint8_t> &buf, const uint8_t *data, size_t dataSize)
{
    static const char code[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
    for (size_t i = 0; i < dataSize; i += 3) {
        buf.push_back(code[data[i] >> 2]);
        buf.push_back(code[((data[i] & 0x3) << 4) | (i + 1 < dataSize ? data[i + 1] >> 4 : 0)]);
        buf.push_back(code[i + 1 < dataSize ? ((data[i + 1] & 0xf) << 2) | (i + 2 < dataSize ? data[i + 2] >> 6 : 0) : 64]);
        buf.push_back(code[i + 2 < dataSize ? data[i + 2] & 0x3f : 64]);
    }
    return dataSize;
}

void AddUcs(std::vector<uint8_t> &buf, const uint8_t *data, size_t dataSize)
{
    if (dataSize > 0 && (data[0] == 0xfe || data[0] == 0xff)) {
        AddEscapedData(buf, data, dataSize);
    }
    else {
        // UTF-8
        for (size_t i = 0; i < dataSize; ++i) {
            if (data[i] == 0xc2 && i + 1 < dataSize && 0x80 <= data[i + 1] && data[i + 1] <= 0x9f) {
                // C1, caret notation
                buf.push_back('%');
                AddChar(buf, '^');
                AddChar(buf, data[++i] - 0x40);
            }
            else {
                AddChar(buf, data[i]);
            }
        }
    }
}
}

CTraceB24Caption::PARSE_PRIVATE_DATA_RESULT
CTraceB24Caption::ParsePrivateData(std::vector<uint8_t> &buf, std::vector<int> &textPosList, const uint8_t *data, size_t dataSize,
                                   std::vector<uint16_t> &drcsList, LANG_TAG_TYPE (&langTags)[8])
{
    const uint8_t BEGIN_UNIT_BRACE[] = {'%', '=', '{'};
    const uint8_t END_UNIT_BRACE[] = {'%', '=', '}'};
    const uint8_t BEGIN_BASE64_BRACE[] = {'%', '+', '{'};
    const uint8_t END_BASE64_BRACE[] = {'%', '+', '}'};

    if (dataSize < 3) {
        return PARSE_PRIVATE_DATA_FAILED;
    }
    size_t pos = 3 + (data[2] & 0x0f);
    if (pos + 4 >= dataSize) {
        return PARSE_PRIVATE_DATA_FAILED;
    }

    // data_group()
    uint8_t dgiType = (data[pos] >> 2) & 0x1f;
    size_t dataGroupEnd = pos + 5 + ((data[pos + 3] << 8) | data[pos + 4]);
    if (dgiType > 8) {
        return PARSE_PRIVATE_DATA_FAILED_UNSUPPORTED;
    }
    if (dgiType != 0 && langTags[dgiType - 1] == LANG_TAG_ABSENT) {
        return PARSE_PRIVATE_DATA_FAILED_NEED_MANAGEMENT;
    }
    if (dataGroupEnd + 2 > dataSize) {
        return PARSE_PRIVATE_DATA_FAILED;
    }
    if (calc_crc16_ccitt(data + pos, static_cast<int>(dataGroupEnd + 2 - pos)) != 0) {
        return PARSE_PRIVATE_DATA_FAILED_CRC;
    }
    buf.clear();
    buf.push_back('0' + dgiType);
    buf.push_back('=');
    textPosList.clear();
    CheckReadableTextPosList(textPosList, buf, false);
    pos += AddEscapedData(buf, data + pos, 3);
    // omit data_group_size
    pos += 2;
    if (pos + 3 >= dataGroupEnd) {
        return PARSE_PRIVATE_DATA_FAILED;
    }

    // caption_management_data() or caption_data()
    int tmd = data[pos] >> 6;
    AddEscapedChar(buf, data[pos++]);
    if ((dgiType != 0 && tmd == 1) || tmd == 2) {
        if (pos + 7 >= dataGroupEnd) {
            return PARSE_PRIVATE_DATA_FAILED;
        }
        pos += AddEscapedData(buf, data + pos, 5);
    }

    LANG_TAG_TYPE lang = LANG_TAG_UNKNOWN;
    if (dgiType == 0) {
        // caption_management_data()
        std::fill_n(langTags, 8, LANG_TAG_ABSENT);
        int numLanguages = data[pos];
        AddEscapedChar(buf, data[pos++]);

        for (int i = 0; i < numLanguages && pos + 7 < dataGroupEnd; ++i) {
            int tag = data[pos] >> 5;
            int dmf = data[pos] & 0x0f;
            AddEscapedChar(buf, data[pos++]);
            if (12 <= dmf && dmf <= 14) {
                if (pos + 7 >= dataGroupEnd) {
                    return PARSE_PRIVATE_DATA_FAILED;
                }
                AddEscapedChar(buf, data[pos++]);
            }
            int tcs = (data[pos + 3] >> 2) & 0x03;
            lang = tcs == 1 ? LANG_TAG_UCS :
                   tcs != 0 ? LANG_TAG_UNKNOWN :
                   (data[pos] == 'p' && data[pos + 1] == 'o' && data[pos + 2] == 'r') ? LANG_TAG_ARIB8_LATIN :
                   (data[pos] == 's' && data[pos + 1] == 'p' && data[pos + 2] == 'a') ? LANG_TAG_ARIB8_LATIN : LANG_TAG_ARIB8;
            langTags[tag] = lang;
            for (int j = 0; j < 3; ++j) {
                if (data[pos] < 0x80) {
                    AddChar(buf, data[pos++]);
                }
                else {
                    AddEscapedChar(buf, data[pos++]);
                }
            }
            // tcs 0->1
            AddEscapedChar(buf, data[pos++] | (lang == LANG_TAG_ARIB8 || lang == LANG_TAG_ARIB8_LATIN ? 0x04 : 0));
        }
    }
    else {
        // caption_data()
        lang = langTags[dgiType - 1];
    }
    size_t dataUnitLoopEnd = pos + 3 + ((data[pos] << 16) | (data[pos + 1] << 8) | data[pos + 2]);
    if (dataUnitLoopEnd > dataGroupEnd) {
        return PARSE_PRIVATE_DATA_FAILED;
    }
    pos += 3;

    // Repleace data_unit_loop_length with "%={"
    buf.insert(buf.end(), BEGIN_UNIT_BRACE, BEGIN_UNIT_BRACE + sizeof(BEGIN_UNIT_BRACE));

    while (pos + 4 < dataUnitLoopEnd) {
        // data_unit()
        AddEscapedChar(buf, data[pos++]);
        int unitParameter = data[pos];
        if (unitParameter == 0x30 && (lang == LANG_TAG_ARIB8 || lang == LANG_TAG_ARIB8_LATIN)) {
            // "shall be the DRCS-0 set" (STD-B24)
            AddEscapedChar(buf, 0x31);
            ++pos;
        }
        else {
            AddEscapedChar(buf, data[pos++]);
        }
        size_t dataUnitSize = (data[pos] << 16) | (data[pos + 1] << 8) | data[pos + 2];
        pos += 3;
        if (pos + dataUnitSize > dataUnitLoopEnd) {
            return PARSE_PRIVATE_DATA_FAILED;
        }
        // Repleace data_unit_size with "%={"
        buf.insert(buf.end(), BEGIN_UNIT_BRACE, BEGIN_UNIT_BRACE + sizeof(BEGIN_UNIT_BRACE));

        if (unitParameter == 0x20) {
            // Statement body
            if (lang == LANG_TAG_ARIB8 || lang == LANG_TAG_ARIB8_LATIN) {
                AddArib8AsUtf8(buf, textPosList, data + pos, dataUnitSize, drcsList, lang == LANG_TAG_ARIB8_LATIN);
                pos += dataUnitSize;
            }
            else if (lang == LANG_TAG_UCS) {
                AddUcs(buf, data + pos, dataUnitSize);
                pos += dataUnitSize;
            }
            else {
                pos += AddEscapedData(buf, data + pos, dataUnitSize);
            }
        }
        else if (unitParameter == 0x30 || unitParameter == 0x31) {
            // Drcs_data_structure()
            size_t drcsDataEnd = pos + dataUnitSize;
            if (pos < drcsDataEnd) {
                int numberOfCode = data[pos];
                AddEscapedChar(buf, data[pos++]);
                for (int i = 0; i < numberOfCode && pos + 2 < drcsDataEnd; ++i) {
                    uint16_t charCode = unitParameter == 0x31 ? (data[pos] << 8) | data[pos + 1] :
                                                                ((data[pos] - GS_DRCS_0) << 8) | data[pos + 1];
                    if (drcsList.empty()) {
                        // drcsList[0] points to the last mapping index.
                        drcsList.push_back(0);
                    }
                    auto it = std::find(drcsList.begin() + 1, drcsList.end(), charCode);
                    if (it == drcsList.end()) {
                        // mapping
                        drcsList[0] = drcsList[0] % 1024 + 1;
                        if (drcsList[0] >= drcsList.size()) {
                            drcsList.push_back(0);
                        }
                        it = drcsList.begin() + drcsList[0];
                        *it = charCode;
                    }
                    // U+EC00 - U+EFFF (1024 characters)
                    AddEscapedChar(buf, static_cast<uint8_t>(0xec + ((it - 1 - drcsList.begin()) >> 8)));
                    AddEscapedChar(buf, static_cast<uint8_t>(it - 1 - drcsList.begin()));
                    pos += 2;

                    int numberOfFont = data[pos];
                    AddEscapedChar(buf, data[pos++]);
                    for (int j = 0; j < numberOfFont && pos < drcsDataEnd; ++j) {
                        int mode = data[pos] & 0x0f;
                        AddEscapedChar(buf, data[pos++]);
                        size_t drcsHead = drcsDataEnd - pos;
                        size_t drcsBody = 0;
                        if (mode <= 1) {
                            if (drcsHead >= 3) {
                                uint8_t depth = data[pos];
                                drcsBody = ((depth == 0 ? 1 : depth <= 2 ? 2 : depth <= 6 ? 3 :
                                             depth <= 14 ? 4 : depth <= 30 ? 5 : depth <= 62 ? 6 :
                                             depth <= 126 ? 7 : depth <= 254 ? 8 : 9) * data[pos + 1] * data[pos + 2] + 7) / 8;
                                drcsBody = std::min(drcsBody, drcsHead - 3);
                                drcsHead = 3;
                            }
                        }
                        else {
                            if (drcsHead >= 4) {
                                drcsBody = (data[pos + 2] << 8) | data[pos + 3];
                                drcsBody = std::min(drcsBody, drcsHead - 4);
                                drcsHead = 4;
                            }
                        }
                        pos += AddEscapedData(buf, data + pos, drcsHead);
                        if (drcsBody > 0) {
                            buf.insert(buf.end(), BEGIN_BASE64_BRACE, BEGIN_BASE64_BRACE + sizeof(BEGIN_BASE64_BRACE));
                            pos += AddBase64EncodedData(buf, data + pos, drcsBody);
                            buf.insert(buf.end(), END_BASE64_BRACE, END_BASE64_BRACE + sizeof(END_BASE64_BRACE));
                        }
                    }
                }
            }
        }
        else {
            pos += AddEscapedData(buf, data + pos, dataUnitSize);
        }
        buf.insert(buf.end(), END_UNIT_BRACE, END_UNIT_BRACE + sizeof(END_UNIT_BRACE));
    }
    buf.insert(buf.end(), END_UNIT_BRACE, END_UNIT_BRACE + sizeof(END_UNIT_BRACE));

    // omit CRC_16
    return PARSE_PRIVATE_DATA_SUCCEEDED;
}

namespace
{
const char16_t FullwidthAsciiTable[94] =
{
    u'！', u'＂', u'＃', u'＄', u'％', u'＆', u'＇',
    u'（', u'）', u'＊', u'＋', u'，', u'－', u'．', u'／',
    u'０', u'１', u'２', u'３', u'４', u'５', u'６', u'７',
    u'８', u'９', u'：', u'；', u'＜', u'＝', u'＞', u'？',
    u'＠', u'Ａ', u'Ｂ', u'Ｃ', u'Ｄ', u'Ｅ', u'Ｆ', u'Ｇ',
    u'Ｈ', u'Ｉ', u'Ｊ', u'Ｋ', u'Ｌ', u'Ｍ', u'Ｎ', u'Ｏ',
    u'Ｐ', u'Ｑ', u'Ｒ', u'Ｓ', u'Ｔ', u'Ｕ', u'Ｖ', u'Ｗ',
    u'Ｘ', u'Ｙ', u'Ｚ', u'［', u'￥', u'］', u'＾', u'＿',
    u'｀', u'ａ', u'ｂ', u'ｃ', u'ｄ', u'ｅ', u'ｆ', u'ｇ',
    u'ｈ', u'ｉ', u'ｊ', u'ｋ', u'ｌ', u'ｍ', u'ｎ', u'ｏ',
    u'ｐ', u'ｑ', u'ｒ', u'ｓ', u'ｔ', u'ｕ', u'ｖ', u'ｗ',
    u'ｘ', u'ｙ', u'ｚ', u'｛', u'｜', u'｝', u'￣'
};

const char16_t HiraganaTable[94] =
{
    u'ぁ', u'あ', u'ぃ', u'い', u'ぅ', u'う', u'ぇ',
    u'え', u'ぉ', u'お', u'か', u'が', u'き', u'ぎ', u'く',
    u'ぐ', u'け', u'げ', u'こ', u'ご', u'さ', u'ざ', u'し',
    u'じ', u'す', u'ず', u'せ', u'ぜ', u'そ', u'ぞ', u'た',
    u'だ', u'ち', u'ぢ', u'っ', u'つ', u'づ', u'て', u'で',
    u'と', u'ど', u'な', u'に', u'ぬ', u'ね', u'の', u'は',
    u'ば', u'ぱ', u'ひ', u'び', u'ぴ', u'ふ', u'ぶ', u'ぷ',
    u'へ', u'べ', u'ぺ', u'ほ', u'ぼ', u'ぽ', u'ま', u'み',
    u'む', u'め', u'も', u'ゃ', u'や', u'ゅ', u'ゆ', u'ょ',
    u'よ', u'ら', u'り', u'る', u'れ', u'ろ', u'ゎ', u'わ',
    u'ゐ', u'ゑ', u'を', u'ん', u'　', u'　', u'　', u'ゝ',
    u'ゞ', u'ー', u'。', u'「', u'」', u'、', u'・'
};

const char16_t KatakanaTable[94] =
{
    u'ァ', u'ア', u'ィ', u'イ', u'ゥ', u'ウ', u'ェ',
    u'エ', u'ォ', u'オ', u'カ', u'ガ', u'キ', u'ギ', u'ク',
    u'グ', u'ケ', u'ゲ', u'コ', u'ゴ', u'サ', u'ザ', u'シ',
    u'ジ', u'ス', u'ズ', u'セ', u'ゼ', u'ソ', u'ゾ', u'タ',
    u'ダ', u'チ', u'ヂ', u'ッ', u'ツ', u'ヅ', u'テ', u'デ',
    u'ト', u'ド', u'ナ', u'ニ', u'ヌ', u'ネ', u'ノ', u'ハ',
    u'バ', u'パ', u'ヒ', u'ビ', u'ピ', u'フ', u'ブ', u'プ',
    u'ヘ', u'ベ', u'ペ', u'ホ', u'ボ', u'ポ', u'マ', u'ミ',
    u'ム', u'メ', u'モ', u'ャ', u'ヤ', u'ュ', u'ユ', u'ョ',
    u'ヨ', u'ラ', u'リ', u'ル', u'レ', u'ロ', u'ヮ', u'ワ',
    u'ヰ', u'ヱ', u'ヲ', u'ン', u'ヴ', u'ヵ', u'ヶ', u'ヽ',
    u'ヾ', u'ー', u'。', u'「', u'」', u'、', u'・'
};

const char16_t JisXKatakanaTable[94] =
{
    u'。', u'「', u'」', u'、', u'・', u'ヲ', u'ァ',
    u'ィ', u'ゥ', u'ェ', u'ォ', u'ャ', u'ュ', u'ョ', u'ッ',
    u'ー', u'ア', u'イ', u'ウ', u'エ', u'オ', u'カ', u'キ',
    u'ク', u'ケ', u'コ', u'サ', u'シ', u'ス', u'セ', u'ソ',
    u'タ', u'チ', u'ツ', u'テ', u'ト', u'ナ', u'ニ', u'ヌ',
    u'ネ', u'ノ', u'ハ', u'ヒ', u'フ', u'ヘ', u'ホ', u'マ',
    u'ミ', u'ム', u'メ', u'モ', u'ヤ', u'ユ', u'ヨ', u'ラ',
    u'リ', u'ル', u'レ', u'ロ', u'ワ', u'ン', u'゛', u'゜',
    u'�', u'�', u'�', u'�', u'�', u'�', u'�', u'�',
    u'�', u'�', u'�', u'�', u'�', u'�', u'�', u'�',
    u'�', u'�', u'�', u'�', u'�', u'�', u'�', u'�',
    u'�', u'�', u'�', u'�', u'�', u'�', u'�'
};

const char16_t LatinExtensionTable[94] =
{
    u'¡', u'¢', u'£', u'€', u'¥', u'Š', u'§',
    u'š', u'©', u'ª', u'«', u'¬', u'ÿ', u'®', u'¯',
    u'°', u'±', u'²', u'³', u'Ž', u'μ', u'¶', u'·',
    u'ž', u'¹', u'º', u'»', u'Œ', u'œ', u'Ÿ', u'¿',
    u'À', u'Á', u'Â', u'Ã', u'Ä', u'Å', u'Æ', u'Ç',
    u'È', u'É', u'Ê', u'Ë', u'Ì', u'Í', u'Î', u'Ï',
    u'Ð', u'Ñ', u'Ò', u'Ó', u'Ô', u'Õ', u'Ö', u'×',
    u'Ø', u'Ù', u'Ú', u'Û', u'Ü', u'Ý', u'Þ', u'ß',
    u'à', u'á', u'â', u'ã', u'ä', u'å', u'æ', u'ç',
    u'è', u'é', u'ê', u'ë', u'ì', u'í', u'î', u'ï',
    u'ð', u'ñ', u'ò', u'ó', u'ô', u'õ', u'ö', u'÷',
    u'ø', u'ù', u'ú', u'û', u'ü', u'ý', u'þ'
};

const char16_t LatinSpecialTable[94] =
{
    u'♪', u'�', u'�', u'�', u'�', u'�', u'�',
    u'�', u'�', u'�', u'�', u'�', u'�', u'�', u'�',
    u'¤', u'¦', u'¨', u'´', u'¸', u'¼', u'½', u'¾',
    u'�', u'�', u'�', u'�', u'�', u'�', u'�', u'�',
    u'…', u'█', u'‘', u'’', u'“', u'”', u'•', u'™',
    u'⅛', u'⅜', u'⅝', u'⅞', u'�', u'�', u'�', u'�',
    u'�', u'�', u'�', u'�', u'�', u'�', u'�', u'�',
    u'�', u'�', u'�', u'�', u'�', u'�', u'�', u'�',
    u'�', u'�', u'�', u'�', u'�', u'�', u'�', u'�',
    u'�', u'�', u'�', u'�', u'�', u'�', u'�', u'�',
    u'�', u'�', u'�', u'�', u'�', u'�', u'�', u'�',
    u'�', u'�', u'�', u'�', u'�', u'�', u'�'
};

const char16_t JisTable[84 * 94 + 1] =
    // row 1-84 (with STD-B24 Table E-1 Conversion)
    u"　、。，．・：；？！゛゜̲́̀̈̂̅ヽヾゝゞ〃仝々〆〇ー―‐／＼～∥｜…‥‘’“”（）〔〕［］｛｝〈〉《》「」『』【】＋－±×÷＝≠＜＞≦≧∞∴♂♀°′″℃￥＄￠￡％＃＆＊＠§☆★○●◎◇"
    u"◆□■△▲▽▼※〒→←↑↓〓�����������∈∋⊆⊇⊂⊃∪∩��������∧∨￢⇒⇔∀∃�����������∠⊥⌒∂∇≡≒≪≫√∽∝∵∫∬�������Å‰♯♭♪†‡¶����⃝"
    u"���������������０１２３４５６７８９�������ＡＢＣＤＥＦＧＨＩＪＫＬＭＮＯＰＱＲＳＴＵＶＷＸＹＺ������ａｂｃｄｅｆｇｈｉｊｋｌｍｎｏｐｑｒｓｔｕｖｗｘｙｚ����"
    u"ぁあぃいぅうぇえぉおかがきぎくぐけげこごさざしじすずせぜそぞただちぢっつづてでとどなにぬねのはばぱひびぴふぶぷへべぺほぼぽまみむめもゃやゅゆょよらりるれろゎわゐゑをん�����������"
    u"ァアィイゥウェエォオカガキギクグケゲコゴサザシジスズセゼソゾタダチヂッツヅテデトドナニヌネノハバパヒビピフブプヘベペホボポマミムメモャヤュユョヨラリルレロヮワヰヱヲンヴヵヶ��������"
    u"ΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩ��������αβγδεζηθικλμνξοπρστυφχψω��������������������������������������"
    u"АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ���������������абвгдеёжзийклмнопрстуфхцчшщъыьэюя�������������"
    u"─│┌┐┘└├┬┤┴┼━┃┏┓┛┗┣┳┫┻╋┠┯┨┷┿┝┰┥┸╂��������������������������������������������������������������"
    u"����������������������������������������������������������������������������������������������"
    u"����������������������������������������������������������������������������������������������"
    u"����������������������������������������������������������������������������������������������"
    u"����������������������������������������������������������������������������������������������"
    u"����������������������������������������������������������������������������������������������"
    u"����������������������������������������������������������������������������������������������"
    u"����������������������������������������������������������������������������������������������"
    u"亜唖娃阿哀愛挨姶逢葵茜穐悪握渥旭葦芦鯵梓圧斡扱宛姐虻飴絢綾鮎或粟袷安庵按暗案闇鞍杏以伊位依偉囲夷委威尉惟意慰易椅為畏異移維緯胃萎衣謂違遺医井亥域育郁磯一壱溢逸稲茨芋鰯允印咽員因姻引飲淫胤蔭"
    u"院陰隠韻吋右宇烏羽迂雨卯鵜窺丑碓臼渦嘘唄欝蔚鰻姥厩浦瓜閏噂云運雲荏餌叡営嬰影映曳栄永泳洩瑛盈穎頴英衛詠鋭液疫益駅悦謁越閲榎厭円園堰奄宴延怨掩援沿演炎焔煙燕猿縁艶苑薗遠鉛鴛塩於汚甥凹央奥往応"
    u"押旺横欧殴王翁襖鴬鴎黄岡沖荻億屋憶臆桶牡乙俺卸恩温穏音下化仮何伽価佳加可嘉夏嫁家寡科暇果架歌河火珂禍禾稼箇花苛茄荷華菓蝦課嘩貨迦過霞蚊俄峨我牙画臥芽蛾賀雅餓駕介会解回塊壊廻快怪悔恢懐戒拐改"
    u"魁晦械海灰界皆絵芥蟹開階貝凱劾外咳害崖慨概涯碍蓋街該鎧骸浬馨蛙垣柿蛎鈎劃嚇各廓拡撹格核殻獲確穫覚角赫較郭閣隔革学岳楽額顎掛笠樫橿梶鰍潟割喝恰括活渇滑葛褐轄且鰹叶椛樺鞄株兜竃蒲釜鎌噛鴨栢茅萱"
    u"粥刈苅瓦乾侃冠寒刊勘勧巻喚堪姦完官寛干幹患感慣憾換敢柑桓棺款歓汗漢澗潅環甘監看竿管簡緩缶翰肝艦莞観諌貫還鑑間閑関陥韓館舘丸含岸巌玩癌眼岩翫贋雁頑顔願企伎危喜器基奇嬉寄岐希幾忌揮机旗既期棋棄"
    u"機帰毅気汽畿祈季稀紀徽規記貴起軌輝飢騎鬼亀偽儀妓宜戯技擬欺犠疑祇義蟻誼議掬菊鞠吉吃喫桔橘詰砧杵黍却客脚虐逆丘久仇休及吸宮弓急救朽求汲泣灸球究窮笈級糾給旧牛去居巨拒拠挙渠虚許距鋸漁禦魚亨享京"
    u"供侠僑兇競共凶協匡卿叫喬境峡強彊怯恐恭挟教橋況狂狭矯胸脅興蕎郷鏡響饗驚仰凝尭暁業局曲極玉桐粁僅勤均巾錦斤欣欽琴禁禽筋緊芹菌衿襟謹近金吟銀九倶句区狗玖矩苦躯駆駈駒具愚虞喰空偶寓遇隅串櫛釧屑屈"
    u"掘窟沓靴轡窪熊隈粂栗繰桑鍬勲君薫訓群軍郡卦袈祁係傾刑兄啓圭珪型契形径恵慶慧憩掲携敬景桂渓畦稽系経継繋罫茎荊蛍計詣警軽頚鶏芸迎鯨劇戟撃激隙桁傑欠決潔穴結血訣月件倹倦健兼券剣喧圏堅嫌建憲懸拳捲"
    u"検権牽犬献研硯絹県肩見謙賢軒遣鍵険顕験鹸元原厳幻弦減源玄現絃舷言諺限乎個古呼固姑孤己庫弧戸故枯湖狐糊袴股胡菰虎誇跨鈷雇顧鼓五互伍午呉吾娯後御悟梧檎瑚碁語誤護醐乞鯉交佼侯候倖光公功効勾厚口向"
    u"后喉坑垢好孔孝宏工巧巷幸広庚康弘恒慌抗拘控攻昂晃更杭校梗構江洪浩港溝甲皇硬稿糠紅紘絞綱耕考肯肱腔膏航荒行衡講貢購郊酵鉱砿鋼閤降項香高鴻剛劫号合壕拷濠豪轟麹克刻告国穀酷鵠黒獄漉腰甑忽惚骨狛込"
    u"此頃今困坤墾婚恨懇昏昆根梱混痕紺艮魂些佐叉唆嵯左差査沙瑳砂詐鎖裟坐座挫債催再最哉塞妻宰彩才採栽歳済災采犀砕砦祭斎細菜裁載際剤在材罪財冴坂阪堺榊肴咲崎埼碕鷺作削咋搾昨朔柵窄策索錯桜鮭笹匙冊刷"
    u"察拶撮擦札殺薩雑皐鯖捌錆鮫皿晒三傘参山惨撒散桟燦珊産算纂蚕讃賛酸餐斬暫残仕仔伺使刺司史嗣四士始姉姿子屍市師志思指支孜斯施旨枝止死氏獅祉私糸紙紫肢脂至視詞詩試誌諮資賜雌飼歯事似侍児字寺慈持時"
    u"次滋治爾璽痔磁示而耳自蒔辞汐鹿式識鴫竺軸宍雫七叱執失嫉室悉湿漆疾質実蔀篠偲柴芝屡蕊縞舎写射捨赦斜煮社紗者謝車遮蛇邪借勺尺杓灼爵酌釈錫若寂弱惹主取守手朱殊狩珠種腫趣酒首儒受呪寿授樹綬需囚収周"
    u"宗就州修愁拾洲秀秋終繍習臭舟蒐衆襲讐蹴輯週酋酬集醜什住充十従戎柔汁渋獣縦重銃叔夙宿淑祝縮粛塾熟出術述俊峻春瞬竣舜駿准循旬楯殉淳準潤盾純巡遵醇順処初所暑曙渚庶緒署書薯藷諸助叙女序徐恕鋤除傷償"
    u"勝匠升召哨商唱嘗奨妾娼宵将小少尚庄床廠彰承抄招掌捷昇昌昭晶松梢樟樵沼消渉湘焼焦照症省硝礁祥称章笑粧紹肖菖蒋蕉衝裳訟証詔詳象賞醤鉦鍾鐘障鞘上丈丞乗冗剰城場壌嬢常情擾条杖浄状畳穣蒸譲醸錠嘱埴飾"
    u"拭植殖燭織職色触食蝕辱尻伸信侵唇娠寝審心慎振新晋森榛浸深申疹真神秦紳臣芯薪親診身辛進針震人仁刃塵壬尋甚尽腎訊迅陣靭笥諏須酢図厨逗吹垂帥推水炊睡粋翠衰遂酔錐錘随瑞髄崇嵩数枢趨雛据杉椙菅頗雀裾"
    u"澄摺寸世瀬畝是凄制勢姓征性成政整星晴棲栖正清牲生盛精聖声製西誠誓請逝醒青静斉税脆隻席惜戚斥昔析石積籍績脊責赤跡蹟碩切拙接摂折設窃節説雪絶舌蝉仙先千占宣専尖川戦扇撰栓栴泉浅洗染潜煎煽旋穿箭線"
    u"繊羨腺舛船薦詮賎践選遷銭銑閃鮮前善漸然全禅繕膳糎噌塑岨措曾曽楚狙疏疎礎祖租粗素組蘇訴阻遡鼠僧創双叢倉喪壮奏爽宋層匝惣想捜掃挿掻操早曹巣槍槽漕燥争痩相窓糟総綜聡草荘葬蒼藻装走送遭鎗霜騒像増憎"
    u"臓蔵贈造促側則即息捉束測足速俗属賊族続卒袖其揃存孫尊損村遜他多太汰詑唾堕妥惰打柁舵楕陀駄騨体堆対耐岱帯待怠態戴替泰滞胎腿苔袋貸退逮隊黛鯛代台大第醍題鷹滝瀧卓啄宅托択拓沢濯琢託鐸濁諾茸凧蛸只"
    u"叩但達辰奪脱巽竪辿棚谷狸鱈樽誰丹単嘆坦担探旦歎淡湛炭短端箪綻耽胆蛋誕鍛団壇弾断暖檀段男談値知地弛恥智池痴稚置致蜘遅馳築畜竹筑蓄逐秩窒茶嫡着中仲宙忠抽昼柱注虫衷註酎鋳駐樗瀦猪苧著貯丁兆凋喋寵"
    u"帖帳庁弔張彫徴懲挑暢朝潮牒町眺聴脹腸蝶調諜超跳銚長頂鳥勅捗直朕沈珍賃鎮陳津墜椎槌追鎚痛通塚栂掴槻佃漬柘辻蔦綴鍔椿潰坪壷嬬紬爪吊釣鶴亭低停偵剃貞呈堤定帝底庭廷弟悌抵挺提梯汀碇禎程締艇訂諦蹄逓"
    u"邸鄭釘鼎泥摘擢敵滴的笛適鏑溺哲徹撤轍迭鉄典填天展店添纏甜貼転顛点伝殿澱田電兎吐堵塗妬屠徒斗杜渡登菟賭途都鍍砥砺努度土奴怒倒党冬凍刀唐塔塘套宕島嶋悼投搭東桃梼棟盗淘湯涛灯燈当痘祷等答筒糖統到"
    u"董蕩藤討謄豆踏逃透鐙陶頭騰闘働動同堂導憧撞洞瞳童胴萄道銅峠鴇匿得徳涜特督禿篤毒独読栃橡凸突椴届鳶苫寅酉瀞噸屯惇敦沌豚遁頓呑曇鈍奈那内乍凪薙謎灘捺鍋楢馴縄畷南楠軟難汝二尼弐迩匂賑肉虹廿日乳入"
    u"如尿韮任妊忍認濡禰祢寧葱猫熱年念捻撚燃粘乃廼之埜嚢悩濃納能脳膿農覗蚤巴把播覇杷波派琶破婆罵芭馬俳廃拝排敗杯盃牌背肺輩配倍培媒梅楳煤狽買売賠陪這蝿秤矧萩伯剥博拍柏泊白箔粕舶薄迫曝漠爆縛莫駁麦"
    u"函箱硲箸肇筈櫨幡肌畑畠八鉢溌発醗髪伐罰抜筏閥鳩噺塙蛤隼伴判半反叛帆搬斑板氾汎版犯班畔繁般藩販範釆煩頒飯挽晩番盤磐蕃蛮匪卑否妃庇彼悲扉批披斐比泌疲皮碑秘緋罷肥被誹費避非飛樋簸備尾微枇毘琵眉美"
    u"鼻柊稗匹疋髭彦膝菱肘弼必畢筆逼桧姫媛紐百謬俵彪標氷漂瓢票表評豹廟描病秒苗錨鋲蒜蛭鰭品彬斌浜瀕貧賓頻敏瓶不付埠夫婦富冨布府怖扶敷斧普浮父符腐膚芙譜負賦赴阜附侮撫武舞葡蕪部封楓風葺蕗伏副復幅服"
    u"福腹複覆淵弗払沸仏物鮒分吻噴墳憤扮焚奮粉糞紛雰文聞丙併兵塀幣平弊柄並蔽閉陛米頁僻壁癖碧別瞥蔑箆偏変片篇編辺返遍便勉娩弁鞭保舗鋪圃捕歩甫補輔穂募墓慕戊暮母簿菩倣俸包呆報奉宝峰峯崩庖抱捧放方朋"
    u"法泡烹砲縫胞芳萌蓬蜂褒訪豊邦鋒飽鳳鵬乏亡傍剖坊妨帽忘忙房暴望某棒冒紡肪膨謀貌貿鉾防吠頬北僕卜墨撲朴牧睦穆釦勃没殆堀幌奔本翻凡盆摩磨魔麻埋妹昧枚毎哩槙幕膜枕鮪柾鱒桝亦俣又抹末沫迄侭繭麿万慢満"
    u"漫蔓味未魅巳箕岬密蜜湊蓑稔脈妙粍民眠務夢無牟矛霧鵡椋婿娘冥名命明盟迷銘鳴姪牝滅免棉綿緬面麺摸模茂妄孟毛猛盲網耗蒙儲木黙目杢勿餅尤戻籾貰問悶紋門匁也冶夜爺耶野弥矢厄役約薬訳躍靖柳薮鑓愉愈油癒"
    u"諭輸唯佑優勇友宥幽悠憂揖有柚湧涌猶猷由祐裕誘遊邑郵雄融夕予余与誉輿預傭幼妖容庸揚揺擁曜楊様洋溶熔用窯羊耀葉蓉要謡踊遥陽養慾抑欲沃浴翌翼淀羅螺裸来莱頼雷洛絡落酪乱卵嵐欄濫藍蘭覧利吏履李梨理璃"
    u"痢裏裡里離陸律率立葎掠略劉流溜琉留硫粒隆竜龍侶慮旅虜了亮僚両凌寮料梁涼猟療瞭稜糧良諒遼量陵領力緑倫厘林淋燐琳臨輪隣鱗麟瑠塁涙累類令伶例冷励嶺怜玲礼苓鈴隷零霊麗齢暦歴列劣烈裂廉恋憐漣煉簾練聯"
    u"蓮連錬呂魯櫓炉賂路露労婁廊弄朗楼榔浪漏牢狼篭老聾蝋郎六麓禄肋録論倭和話歪賄脇惑枠鷲亙亘鰐詫藁蕨椀湾碗腕�������������������������������������������"
    u"弌丐丕个丱丶丼丿乂乖乘亂亅豫亊舒弍于亞亟亠亢亰亳亶从仍仄仆仂仗仞仭仟价伉佚估佛佝佗佇佶侈侏侘佻佩佰侑佯來侖儘俔俟俎俘俛俑俚俐俤俥倚倨倔倪倥倅伜俶倡倩倬俾俯們倆偃假會偕偐偈做偖偬偸傀傚傅傴傲"
    u"僉僊傳僂僖僞僥僭僣僮價僵儉儁儂儖儕儔儚儡儺儷儼儻儿兀兒兌兔兢竸兩兪兮冀冂囘册冉冏冑冓冕冖冤冦冢冩冪冫决冱冲冰况冽凅凉凛几處凩凭凰凵凾刄刋刔刎刧刪刮刳刹剏剄剋剌剞剔剪剴剩剳剿剽劍劔劒剱劈劑辨"
    u"辧劬劭劼劵勁勍勗勞勣勦飭勠勳勵勸勹匆匈甸匍匐匏匕匚匣匯匱匳匸區卆卅丗卉卍凖卞卩卮夘卻卷厂厖厠厦厥厮厰厶參簒雙叟曼燮叮叨叭叺吁吽呀听吭吼吮吶吩吝呎咏呵咎呟呱呷呰咒呻咀呶咄咐咆哇咢咸咥咬哄哈咨"
    u"咫哂咤咾咼哘哥哦唏唔哽哮哭哺哢唹啀啣啌售啜啅啖啗唸唳啝喙喀咯喊喟啻啾喘喞單啼喃喩喇喨嗚嗅嗟嗄嗜嗤嗔嘔嗷嘖嗾嗽嘛嗹噎噐營嘴嘶嘲嘸噫噤嘯噬噪嚆嚀嚊嚠嚔嚏嚥嚮嚶嚴囂嚼囁囃囀囈囎囑囓囗囮囹圀囿圄圉"
    u"圈國圍圓團圖嗇圜圦圷圸坎圻址坏坩埀垈坡坿垉垓垠垳垤垪垰埃埆埔埒埓堊埖埣堋堙堝塲堡塢塋塰毀塒堽塹墅墹墟墫墺壞墻墸墮壅壓壑壗壙壘壥壜壤壟壯壺壹壻壼壽夂夊夐夛梦夥夬夭夲夸夾竒奕奐奎奚奘奢奠奧奬奩"
    u"奸妁妝佞侫妣妲姆姨姜妍姙姚娥娟娑娜娉娚婀婬婉娵娶婢婪媚媼媾嫋嫂媽嫣嫗嫦嫩嫖嫺嫻嬌嬋嬖嬲嫐嬪嬶嬾孃孅孀孑孕孚孛孥孩孰孳孵學斈孺宀它宦宸寃寇寉寔寐寤實寢寞寥寫寰寶寳尅將專對尓尠尢尨尸尹屁屆屎屓"
    u"屐屏孱屬屮乢屶屹岌岑岔妛岫岻岶岼岷峅岾峇峙峩峽峺峭嶌峪崋崕崗嵜崟崛崑崔崢崚崙崘嵌嵒嵎嵋嵬嵳嵶嶇嶄嶂嶢嶝嶬嶮嶽嶐嶷嶼巉巍巓巒巖巛巫已巵帋帚帙帑帛帶帷幄幃幀幎幗幔幟幢幤幇幵并幺麼广庠廁廂廈廐廏"
    u"廖廣廝廚廛廢廡廨廩廬廱廳廰廴廸廾弃弉彝彜弋弑弖弩弭弸彁彈彌彎弯彑彖彗彙彡彭彳彷徃徂彿徊很徑徇從徙徘徠徨徭徼忖忻忤忸忱忝悳忿怡恠怙怐怩怎怱怛怕怫怦怏怺恚恁恪恷恟恊恆恍恣恃恤恂恬恫恙悁悍惧悃悚"
    u"悄悛悖悗悒悧悋惡悸惠惓悴忰悽惆悵惘慍愕愆惶惷愀惴惺愃愡惻惱愍愎慇愾愨愧慊愿愼愬愴愽慂慄慳慷慘慙慚慫慴慯慥慱慟慝慓慵憙憖憇憬憔憚憊憑憫憮懌懊應懷懈懃懆憺懋罹懍懦懣懶懺懴懿懽懼懾戀戈戉戍戌戔戛"
    u"戞戡截戮戰戲戳扁扎扞扣扛扠扨扼抂抉找抒抓抖拔抃抔拗拑抻拏拿拆擔拈拜拌拊拂拇抛拉挌拮拱挧挂挈拯拵捐挾捍搜捏掖掎掀掫捶掣掏掉掟掵捫捩掾揩揀揆揣揉插揶揄搖搴搆搓搦搶攝搗搨搏摧摯摶摎攪撕撓撥撩撈撼"
    u"據擒擅擇撻擘擂擱擧舉擠擡抬擣擯攬擶擴擲擺攀擽攘攜攅攤攣攫攴攵攷收攸畋效敖敕敍敘敞敝敲數斂斃變斛斟斫斷旃旆旁旄旌旒旛旙无旡旱杲昊昃旻杳昵昶昴昜晏晄晉晁晞晝晤晧晨晟晢晰暃暈暎暉暄暘暝曁暹曉暾暼"
    u"曄暸曖曚曠昿曦曩曰曵曷朏朖朞朦朧霸朮朿朶杁朸朷杆杞杠杙杣杤枉杰枩杼杪枌枋枦枡枅枷柯枴柬枳柩枸柤柞柝柢柮枹柎柆柧檜栞框栩桀桍栲桎梳栫桙档桷桿梟梏梭梔條梛梃檮梹桴梵梠梺椏梍桾椁棊椈棘椢椦棡椌棍"
    u"棔棧棕椶椒椄棗棣椥棹棠棯椨椪椚椣椡棆楹楷楜楸楫楔楾楮椹楴椽楙椰楡楞楝榁楪榲榮槐榿槁槓榾槎寨槊槝榻槃榧樮榑榠榜榕榴槞槨樂樛槿權槹槲槧樅榱樞槭樔槫樊樒櫁樣樓橄樌橲樶橸橇橢橙橦橈樸樢檐檍檠檄檢檣"
    u"檗蘗檻櫃櫂檸檳檬櫞櫑櫟檪櫚櫪櫻欅蘖櫺欒欖鬱欟欸欷盜欹飮歇歃歉歐歙歔歛歟歡歸歹歿殀殄殃殍殘殕殞殤殪殫殯殲殱殳殷殼毆毋毓毟毬毫毳毯麾氈氓气氛氤氣汞汕汢汪沂沍沚沁沛汾汨汳沒沐泄泱泓沽泗泅泝沮沱沾"
    u"沺泛泯泙泪洟衍洶洫洽洸洙洵洳洒洌浣涓浤浚浹浙涎涕濤涅淹渕渊涵淇淦涸淆淬淞淌淨淒淅淺淙淤淕淪淮渭湮渮渙湲湟渾渣湫渫湶湍渟湃渺湎渤滿渝游溂溪溘滉溷滓溽溯滄溲滔滕溏溥滂溟潁漑灌滬滸滾漿滲漱滯漲滌"
    u"漾漓滷澆潺潸澁澀潯潛濳潭澂潼潘澎澑濂潦澳澣澡澤澹濆澪濟濕濬濔濘濱濮濛瀉瀋濺瀑瀁瀏濾瀛瀚潴瀝瀘瀟瀰瀾瀲灑灣炙炒炯烱炬炸炳炮烟烋烝烙焉烽焜焙煥煕熈煦煢煌煖煬熏燻熄熕熨熬燗熹熾燒燉燔燎燠燬燧燵燼"
    u"燹燿爍爐爛爨爭爬爰爲爻爼爿牀牆牋牘牴牾犂犁犇犒犖犢犧犹犲狃狆狄狎狒狢狠狡狹狷倏猗猊猜猖猝猴猯猩猥猾獎獏默獗獪獨獰獸獵獻獺珈玳珎玻珀珥珮珞璢琅瑯琥珸琲琺瑕琿瑟瑙瑁瑜瑩瑰瑣瑪瑶瑾璋璞璧瓊瓏瓔珱"
    u"瓠瓣瓧瓩瓮瓲瓰瓱瓸瓷甄甃甅甌甎甍甕甓甞甦甬甼畄畍畊畉畛畆畚畩畤畧畫畭畸當疆疇畴疊疉疂疔疚疝疥疣痂疳痃疵疽疸疼疱痍痊痒痙痣痞痾痿痼瘁痰痺痲痳瘋瘍瘉瘟瘧瘠瘡瘢瘤瘴瘰瘻癇癈癆癜癘癡癢癨癩癪癧癬癰"
    u"癲癶癸發皀皃皈皋皎皖皓皙皚皰皴皸皹皺盂盍盖盒盞盡盥盧盪蘯盻眈眇眄眩眤眞眥眦眛眷眸睇睚睨睫睛睥睿睾睹瞎瞋瞑瞠瞞瞰瞶瞹瞿瞼瞽瞻矇矍矗矚矜矣矮矼砌砒礦砠礪硅碎硴碆硼碚碌碣碵碪碯磑磆磋磔碾碼磅磊磬"
    u"磧磚磽磴礇礒礑礙礬礫祀祠祗祟祚祕祓祺祿禊禝禧齋禪禮禳禹禺秉秕秧秬秡秣稈稍稘稙稠稟禀稱稻稾稷穃穗穉穡穢穩龝穰穹穽窈窗窕窘窖窩竈窰窶竅竄窿邃竇竊竍竏竕竓站竚竝竡竢竦竭竰笂笏笊笆笳笘笙笞笵笨笶筐"
    u"筺笄筍笋筌筅筵筥筴筧筰筱筬筮箝箘箟箍箜箚箋箒箏筝箙篋篁篌篏箴篆篝篩簑簔篦篥籠簀簇簓篳篷簗簍篶簣簧簪簟簷簫簽籌籃籔籏籀籐籘籟籤籖籥籬籵粃粐粤粭粢粫粡粨粳粲粱粮粹粽糀糅糂糘糒糜糢鬻糯糲糴糶糺紆"
    u"紂紜紕紊絅絋紮紲紿紵絆絳絖絎絲絨絮絏絣經綉絛綏絽綛綺綮綣綵緇綽綫總綢綯緜綸綟綰緘緝緤緞緻緲緡縅縊縣縡縒縱縟縉縋縢繆繦縻縵縹繃縷縲縺繧繝繖繞繙繚繹繪繩繼繻纃緕繽辮繿纈纉續纒纐纓纔纖纎纛纜缸缺"
    u"罅罌罍罎罐网罕罔罘罟罠罨罩罧罸羂羆羃羈羇羌羔羞羝羚羣羯羲羹羮羶羸譱翅翆翊翕翔翡翦翩翳翹飜耆耄耋耒耘耙耜耡耨耿耻聊聆聒聘聚聟聢聨聳聲聰聶聹聽聿肄肆肅肛肓肚肭冐肬胛胥胙胝胄胚胖脉胯胱脛脩脣脯腋"
    u"隋腆脾腓腑胼腱腮腥腦腴膃膈膊膀膂膠膕膤膣腟膓膩膰膵膾膸膽臀臂膺臉臍臑臙臘臈臚臟臠臧臺臻臾舁舂舅與舊舍舐舖舩舫舸舳艀艙艘艝艚艟艤艢艨艪艫舮艱艷艸艾芍芒芫芟芻芬苡苣苟苒苴苳苺莓范苻苹苞茆苜茉苙"
    u"茵茴茖茲茱荀茹荐荅茯茫茗茘莅莚莪莟莢莖茣莎莇莊荼莵荳荵莠莉莨菴萓菫菎菽萃菘萋菁菷萇菠菲萍萢萠莽萸蔆菻葭萪萼蕚蒄葷葫蒭葮蒂葩葆萬葯葹萵蓊葢蒹蒿蒟蓙蓍蒻蓚蓐蓁蓆蓖蒡蔡蓿蓴蔗蔘蔬蔟蔕蔔蓼蕀蕣蕘蕈"
    u"蕁蘂蕋蕕薀薤薈薑薊薨蕭薔薛藪薇薜蕷蕾薐藉薺藏薹藐藕藝藥藜藹蘊蘓蘋藾藺蘆蘢蘚蘰蘿虍乕虔號虧虱蚓蚣蚩蚪蚋蚌蚶蚯蛄蛆蚰蛉蠣蚫蛔蛞蛩蛬蛟蛛蛯蜒蜆蜈蜀蜃蛻蜑蜉蜍蛹蜊蜴蜿蜷蜻蜥蜩蜚蝠蝟蝸蝌蝎蝴蝗蝨蝮蝙"
    u"蝓蝣蝪蠅螢螟螂螯蟋螽蟀蟐雖螫蟄螳蟇蟆螻蟯蟲蟠蠏蠍蟾蟶蟷蠎蟒蠑蠖蠕蠢蠡蠱蠶蠹蠧蠻衄衂衒衙衞衢衫袁衾袞衵衽袵衲袂袗袒袮袙袢袍袤袰袿袱裃裄裔裘裙裝裹褂裼裴裨裲褄褌褊褓襃褞褥褪褫襁襄褻褶褸襌褝襠襞"
    u"襦襤襭襪襯襴襷襾覃覈覊覓覘覡覩覦覬覯覲覺覽覿觀觚觜觝觧觴觸訃訖訐訌訛訝訥訶詁詛詒詆詈詼詭詬詢誅誂誄誨誡誑誥誦誚誣諄諍諂諚諫諳諧諤諱謔諠諢諷諞諛謌謇謚諡謖謐謗謠謳鞫謦謫謾謨譁譌譏譎證譖譛譚譫"
    u"譟譬譯譴譽讀讌讎讒讓讖讙讚谺豁谿豈豌豎豐豕豢豬豸豺貂貉貅貊貍貎貔豼貘戝貭貪貽貲貳貮貶賈賁賤賣賚賽賺賻贄贅贊贇贏贍贐齎贓賍贔贖赧赭赱赳趁趙跂趾趺跏跚跖跌跛跋跪跫跟跣跼踈踉跿踝踞踐踟蹂踵踰踴蹊"
    u"蹇蹉蹌蹐蹈蹙蹤蹠踪蹣蹕蹶蹲蹼躁躇躅躄躋躊躓躑躔躙躪躡躬躰軆躱躾軅軈軋軛軣軼軻軫軾輊輅輕輒輙輓輜輟輛輌輦輳輻輹轅轂輾轌轉轆轎轗轜轢轣轤辜辟辣辭辯辷迚迥迢迪迯邇迴逅迹迺逑逕逡逍逞逖逋逧逶逵逹迸"
    u"遏遐遑遒逎遉逾遖遘遞遨遯遶隨遲邂遽邁邀邊邉邏邨邯邱邵郢郤扈郛鄂鄒鄙鄲鄰酊酖酘酣酥酩酳酲醋醉醂醢醫醯醪醵醴醺釀釁釉釋釐釖釟釡釛釼釵釶鈞釿鈔鈬鈕鈑鉞鉗鉅鉉鉤鉈銕鈿鉋鉐銜銖銓銛鉚鋏銹銷鋩錏鋺鍄錮"
    u"錙錢錚錣錺錵錻鍜鍠鍼鍮鍖鎰鎬鎭鎔鎹鏖鏗鏨鏥鏘鏃鏝鏐鏈鏤鐚鐔鐓鐃鐇鐐鐶鐫鐵鐡鐺鑁鑒鑄鑛鑠鑢鑞鑪鈩鑰鑵鑷鑽鑚鑼鑾钁鑿閂閇閊閔閖閘閙閠閨閧閭閼閻閹閾闊濶闃闍闌闕闔闖關闡闥闢阡阨阮阯陂陌陏陋陷陜陞"
    u"陝陟陦陲陬隍隘隕隗險隧隱隲隰隴隶隸隹雎雋雉雍襍雜霍雕雹霄霆霈霓霎霑霏霖霙霤霪霰霹霽霾靄靆靈靂靉靜靠靤靦靨勒靫靱靹鞅靼鞁靺鞆鞋鞏鞐鞜鞨鞦鞣鞳鞴韃韆韈韋韜韭齏韲竟韶韵頏頌頸頤頡頷頽顆顏顋顫顯顰"
    u"顱顴顳颪颯颱颶飄飃飆飩飫餃餉餒餔餘餡餝餞餤餠餬餮餽餾饂饉饅饐饋饑饒饌饕馗馘馥馭馮馼駟駛駝駘駑駭駮駱駲駻駸騁騏騅駢騙騫騷驅驂驀驃騾驕驍驛驗驟驢驥驤驩驫驪骭骰骼髀髏髑髓體髞髟髢髣髦髯髫髮髴髱髷"
    u"髻鬆鬘鬚鬟鬢鬣鬥鬧鬨鬩鬪鬮鬯鬲魄魃魏魍魎魑魘魴鮓鮃鮑鮖鮗鮟鮠鮨鮴鯀鯊鮹鯆鯏鯑鯒鯣鯢鯤鯔鯡鰺鯲鯱鯰鰕鰔鰉鰓鰌鰆鰈鰒鰊鰄鰮鰛鰥鰤鰡鰰鱇鰲鱆鰾鱚鱠鱧鱶鱸鳧鳬鳰鴉鴈鳫鴃鴆鴪鴦鶯鴣鴟鵄鴕鴒鵁鴿鴾鵆鵈"
    u"鵝鵞鵤鵑鵐鵙鵲鶉鶇鶫鵯鵺鶚鶤鶩鶲鷄鷁鶻鶸鶺鷆鷏鷂鷙鷓鷸鷦鷭鷯鷽鸚鸛鸞鹵鹹鹽麁麈麋麌麒麕麑麝麥麩麸麪麭靡黌黎黏黐黔黜點黝黠黥黨黯黴黶黷黹黻黼黽鼇鼈皷鼕鼡鼬鼾齊齒齔齣齟齠齡齦齧齬齪齷齲齶龕龜龠"
    u"堯槇遙瑤凜熙����������������������������������������������������������������������������������������";

const char32_t GaijiTable[7 * 94 + 1] =
    // row 90-94
    U"⛌⛍❗⛏⛐⛑⛒⛕⛓⛔🅿🆊⛖⛗⛘⛙⛚⛛⛜⛝⛞⛟⛠⛡⭕㉈㉉㉊㉋㉌㉍㉎㉏⒑⒒⒓🅊🅌🄿🅆🅋🈐🈑🈒🈓🅂🈔🈕🈖🅍🄱🄽⬛⬤🈗🈘🈙🈚🈛⚿🈜🈝🈞🈟🈠🈡🈢🈣🈤🈥🅎㊙🈀"
    U"⛣⭖⭗⭘⭙☓㊋〒⛨㉆㉅⛩࿖⛪⛫⛬♨⛭⛮⛯⚓✈⛰⛱⛲⛳⛴⛵🅗ⒹⓈ⛶🅟🆋🆍🆌🅹⛷⛸⛹⛺🅻☎⛻⛼⛽⛾🅼⛿"
    U"➡⬅⬆⬇⬯⬮㎡㎥㎝㎠㎤🄀⒈⒉⒊⒋⒌⒍⒎⒏⒐🄁🄂🄃🄄🄅🄆🄇🄈🄉🄊㈳㈶㈲㈱㈹㉄▶◀〖〗⟐²³🄭🄬🄫㉇🆐🈦℻"
    U"㈪㈫㈬㈭㈮㈯㈰㈷㍾㍽㍼㍻№℡〶⚾🉀🉁🉂🉃🉄🉅🉆🉇🉈🄪🈧🈨🈩🈔🈪🈫🈬🈭🈮🈯🈰🈱ℓ㎏㎐㏊㎞㎢㍱½↉⅓⅔¼¾⅕⅖⅗⅘⅙⅚⅐⅛⅑⅒☀☁☂⛄☖☗⛉⛊♦♥♣♠⛋⨀‼⁉⛅☔⛆☃⛇⚡⛈⚞⚟♬"
    U"ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫ⑰⑱⑲⑳⑴⑵⑶⑷⑸⑹⑺⑻⑼⑽⑾⑿㉑㉒㉓㉔🄐🄑🄒🄓🄔🄕🄖🄗🄘🄙🄚🄛🄜🄝🄞🄟🄠🄡🄢🄣🄤🄥🄦🄧🄨🄩㉕㉖㉗㉘㉙㉚①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯❶❷❸❹❺❻❼❽❾❿⓫⓬㉛"
    // row 85-86
    U"㐂𠅘份仿侚俉傜儞冼㔟匇卡卬詹𠮷呍咖咜咩唎啊噲囤圳圴塚墀姤娣婕寬﨑㟢庬弴彅德怗恵愰昤曈曙曺曻桒鿄椑椻橅檑櫛𣏌𣏾𣗄毱泠洮海涿淊淸渚潞濹灤𤋮𤋮煇燁爀玟玨珉珖琛琡琢琦琪琬琹瑋㻚畵疁睲䂓磈磠祇禮鿆䄃"
    U"鿅秚稞筿簱䉤綋羡脘脺舘芮葛蓜蓬蕙藎蝕蟬蠋裵角諶跎辻迶郝鄧鄭醲鈳銈錡鍈閒雞餃饀髙鯖鷗麴麵";

const uint8_t DefaultMacro[16][20] =
{
    {0x1b, 0x24, 0x42, 0x1b, 0x29, 0x4a, 0x1b, 0x2a, 0x30, 0x1b, 0x2b, 0x20, 0x70, 0x0f, 0x1b, 0x7d},
    {0x1b, 0x24, 0x42, 0x1b, 0x29, 0x31, 0x1b, 0x2a, 0x30, 0x1b, 0x2b, 0x20, 0x70, 0x0f, 0x1b, 0x7d},
    {0x1b, 0x24, 0x42, 0x1b, 0x29, 0x20, 0x41, 0x1b, 0x2a, 0x30, 0x1b, 0x2b, 0x20, 0x70, 0x0f, 0x1b, 0x7d},
    {0x1b, 0x28, 0x32, 0x1b, 0x29, 0x34, 0x1b, 0x2a, 0x35, 0x1b, 0x2b, 0x20, 0x70, 0x0f, 0x1b, 0x7d},
    {0x1b, 0x28, 0x32, 0x1b, 0x29, 0x33, 0x1b, 0x2a, 0x35, 0x1b, 0x2b, 0x20, 0x70, 0x0f, 0x1b, 0x7d},
    {0x1b, 0x28, 0x32, 0x1b, 0x29, 0x20, 0x41, 0x1b, 0x2a, 0x35, 0x1b, 0x2b, 0x20, 0x70, 0x0f, 0x1b, 0x7d},
    {0x1b, 0x28, 0x20, 0x41, 0x1b, 0x29, 0x20, 0x42, 0x1b, 0x2a, 0x20, 0x43, 0x1b, 0x2b, 0x20, 0x70, 0x0f, 0x1b, 0x7d},
    {0x1b, 0x28, 0x20, 0x44, 0x1b, 0x29, 0x20, 0x45, 0x1b, 0x2a, 0x20, 0x46, 0x1b, 0x2b, 0x20, 0x70, 0x0f, 0x1b, 0x7d},
    {0x1b, 0x28, 0x20, 0x47, 0x1b, 0x29, 0x20, 0x48, 0x1b, 0x2a, 0x20, 0x49, 0x1b, 0x2b, 0x20, 0x70, 0x0f, 0x1b, 0x7d},
    {0x1b, 0x28, 0x20, 0x4a, 0x1b, 0x29, 0x20, 0x4b, 0x1b, 0x2a, 0x20, 0x4c, 0x1b, 0x2b, 0x20, 0x70, 0x0f, 0x1b, 0x7d},
    {0x1b, 0x28, 0x20, 0x4d, 0x1b, 0x29, 0x20, 0x4e, 0x1b, 0x2a, 0x20, 0x4f, 0x1b, 0x2b, 0x20, 0x70, 0x0f, 0x1b, 0x7d},
    {0x1b, 0x24, 0x42, 0x1b, 0x29, 0x20, 0x42, 0x1b, 0x2a, 0x30, 0x1b, 0x2b, 0x20, 0x70, 0x0f, 0x1b, 0x7d},
    {0x1b, 0x24, 0x42, 0x1b, 0x29, 0x20, 0x43, 0x1b, 0x2a, 0x30, 0x1b, 0x2b, 0x20, 0x70, 0x0f, 0x1b, 0x7d},
    {0x1b, 0x24, 0x42, 0x1b, 0x29, 0x20, 0x44, 0x1b, 0x2a, 0x30, 0x1b, 0x2b, 0x20, 0x70, 0x0f, 0x1b, 0x7d},
    {0x1b, 0x28, 0x31, 0x1b, 0x29, 0x30, 0x1b, 0x2a, 0x4a, 0x1b, 0x2b, 0x20, 0x70, 0x0f, 0x1b, 0x7d},
    {0x1b, 0x28, 0x4a, 0x1b, 0x29, 0x32, 0x1b, 0x2a, 0x20, 0x41, 0x1b, 0x2b, 0x20, 0x70, 0x0f, 0x1b, 0x7d}
};
}
