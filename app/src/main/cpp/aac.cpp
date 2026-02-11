#include "aac.hpp"
#include "huffman.hpp"
#include "util.hpp"
#include <assert.h>
#include <algorithm>

namespace
{
const int ONLY_LONG_SEQUENCE = 0;
const int LONG_START_SEQUENCE = 1;
const int EIGHT_SHORT_SEQUENCE = 2;
const int LONG_STOP_SEQUENCE = 3;

const uint16_t SWB_OFFSET_LONG_WINDOW_48KHZ[64] =
{
    0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 48, 56, 64, 72, 80, 88, 96, 108, 120, 132, 144, 160, 176, 196,
    216, 240, 264, 292, 320, 352, 384, 416, 448, 480, 512, 544, 576, 608, 640, 672, 704, 736, 768, 800, 832, 864, 896, 928, 1024,
    // padding
    1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024,
};

const uint16_t SWB_OFFSET_LONG_WINDOW_32KHZ[64] =
{
    0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 48, 56, 64, 72, 80, 88, 96, 108, 120, 132, 144, 160, 176, 196, 216,
    240, 264, 292, 320, 352, 384, 416, 448, 480, 512, 544, 576, 608, 640, 672, 704, 736, 768, 800, 832, 864, 896, 928, 960, 992, 1024,
    // padding
    1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024,
};

const uint16_t SWB_OFFSET_SHORT_WINDOW_48KHZ[16] =
{
    0, 4, 8, 12, 16, 20, 28, 36, 44, 56, 68, 80, 96, 112, 128,
    // padding
    128,
};

const int PRED_SFB_MAX_48KHZ = 40;

const int ZERO_HCB = 0;
const int FIRST_PAIR_HCB = 5;
const int ESC_HCB = 11;

const int ESC_FLAG = 16;

const int EXT_DYNAMIC_RANGE = 11;
const int EXT_SBR_DATA = 13;
const int EXT_SBR_DATA_CRC = 14;

const int ID_SCE = 0;
const int ID_CPE = 1;
const int ID_DSE = 4;
const int ID_PCE = 5;
const int ID_FIL = 6;
const int ID_END = 7;

const size_t EXTRA_WORKSPACE_BYTES = 16;

inline bool CheckOverrun(size_t lenBytes, size_t pos)
{
    assert(pos <= lenBytes * 8);
    return pos <= lenBytes * 8;
}

void ByteAlignment(size_t &pos)
{
    pos = (pos + 7) / 8 * 8;
}

bool SingleChannelElement(const uint8_t *aac, size_t lenBytes, size_t &pos, bool is32khz)
{
    pos += 4;

    // individual_channel_stream(0)
    pos += 8;

    // ics_info
    ++pos;
    int windowSequence = read_bits(aac, pos, 2);
    ++pos;
    int maxSfb;
    int numWindowGroups = 1;
    int windowGroupLength[8];
    windowGroupLength[0] = 1;
    if (windowSequence == EIGHT_SHORT_SEQUENCE) {
        maxSfb = read_bits(aac, pos, 4);
        int scaleFactorGrouping = read_bits(aac, pos, 7);
        for (int i = 6; i >= 0; --i) {
            if ((scaleFactorGrouping >> i) & 1) {
                ++windowGroupLength[numWindowGroups - 1];
            }
            else {
                windowGroupLength[numWindowGroups++] = 1;
            }
        }
    }
    else {
        maxSfb = read_bits(aac, pos, 6);
        bool predictorDataPresent = read_bool(aac, pos);
        if (predictorDataPresent) {
            bool predictorReset = read_bool(aac, pos);
            if (predictorReset) {
                pos += 5;
            }
            pos += std::min(maxSfb, PRED_SFB_MAX_48KHZ);
        }
    }

    // Determine sect_sfb_offset
    int numWindows;
    int sectSfbOffset[8][64];
    if (windowSequence == EIGHT_SHORT_SEQUENCE) {
        numWindows = 8;
        for (int g = 0; g < numWindowGroups; ++g) {
            int offset = 0;
            for (int i = 0; i < maxSfb; ++i) {
                sectSfbOffset[g][i] = offset;
                offset += (SWB_OFFSET_SHORT_WINDOW_48KHZ[i + 1] - SWB_OFFSET_SHORT_WINDOW_48KHZ[i]) * windowGroupLength[g];
            }
            sectSfbOffset[g][maxSfb] = offset;
        }
    }
    else {
        numWindows = 1;
        if (is32khz) {
            std::copy(SWB_OFFSET_LONG_WINDOW_32KHZ, SWB_OFFSET_LONG_WINDOW_32KHZ + maxSfb + 1, sectSfbOffset[0]);
        }
        else {
            std::copy(SWB_OFFSET_LONG_WINDOW_48KHZ, SWB_OFFSET_LONG_WINDOW_48KHZ + maxSfb + 1, sectSfbOffset[0]);
        }
    }

    // section_data
    int numSec[8];
    int sectCb[8][64];
    int sectEnd[8][64];
    int sfbCb[8][64];
    for (int g = 0; g < numWindowGroups; ++g) {
        int sectLenIncrBits = windowSequence == EIGHT_SHORT_SEQUENCE ? 3 : 5;
        int sectEscVal = windowSequence == EIGHT_SHORT_SEQUENCE ? 7 : 31;
        int i = 0;
        for (int k = 0; k < maxSfb; ++i) {
            if (!CheckOverrun(lenBytes, pos)) {
                return false;
            }
            sectCb[g][i] = read_bits(aac, pos, 4);
            int sectLen = 0;
            for (;;) {
                if (!CheckOverrun(lenBytes, pos)) {
                    return false;
                }
                int sectLenIncr = read_bits(aac, pos, sectLenIncrBits);
                sectLen += sectLenIncr;
                if (k + sectLen > maxSfb) {
                    assert(false);
                    return false;
                }
                if (sectLenIncr != sectEscVal) {
                    break;
                }
            }
            for (int sfb = k; sfb < k + sectLen; ++sfb) {
                sfbCb[g][sfb] = sectCb[g][i];
            }
            k += sectLen;
            sectEnd[g][i] = k;
        }
        numSec[g] = i;
    }

    // scale_factor_data (ISO/IEC 14496-3 extended)
    bool noisePcmFlag = true;
    for (int g = 0; g < numWindowGroups; ++g) {
        for (int sfb = 0; sfb < maxSfb; ++sfb) {
            if (sfbCb[g][sfb] != ZERO_HCB) {
                if (!CheckOverrun(lenBytes, pos)) {
                    return false;
                }
                if (sfbCb[g][sfb] == 13 && noisePcmFlag) {
                    noisePcmFlag = false;
                    pos += 9;
                }
                else {
                    Huffman::DecodeScalefactorBits(aac, pos);
                }
            }
        }
    }

    if (!CheckOverrun(lenBytes, pos)) {
        return false;
    }
    bool pulseDataPresent = read_bool(aac, pos);
    if (pulseDataPresent) {
        // pulse_data
        int numberPulse = read_bits(aac, pos, 2);
        pos += 6 + 9 * (numberPulse + 1);
    }

    if (!CheckOverrun(lenBytes, pos)) {
        return false;
    }
    bool tnsDataPresent = read_bool(aac, pos);
    if (tnsDataPresent) {
        // tns_data
        int nFiltBits = windowSequence == EIGHT_SHORT_SEQUENCE ? 1 : 2;
        int lengthBits = windowSequence == EIGHT_SHORT_SEQUENCE ? 4 : 6;
        int orderBits = windowSequence == EIGHT_SHORT_SEQUENCE ? 3 : 5;
        for (int w = 0; w < numWindows; ++w) {
            if (!CheckOverrun(lenBytes, pos)) {
                return false;
            }
            int nFilt = read_bits(aac, pos, nFiltBits);
            int coefRes = 0;
            if (nFilt) {
                coefRes = read_bits(aac, pos, 1);
            }
            for (int f = 0; f < nFilt; ++f) {
                pos += lengthBits;
                if (!CheckOverrun(lenBytes, pos)) {
                    return false;
                }
                int order = read_bits(aac, pos, orderBits);
                if (order) {
                    ++pos;
                    int coefCompress = read_bits(aac, pos, 1);
                    pos += (3 + coefRes - coefCompress) * order;
                }
            }
        }
    }

    if (!CheckOverrun(lenBytes, pos)) {
        return false;
    }
    bool gainControlDataPresent = read_bool(aac, pos);
    if (gainControlDataPresent) {
        // gain_control_data
        int maxBand = read_bits(aac, pos, 2);
        int wdCount = ONLY_LONG_SEQUENCE ? 1 : EIGHT_SHORT_SEQUENCE ? 8 : 2;
        for (int bd = 1; bd <= maxBand; ++bd) {
            for (int wd = 0; wd < wdCount; ++wd) {
                if (!CheckOverrun(lenBytes, pos)) {
                    return false;
                }
                int adjustNum = read_bits(aac, pos, 3);
                int adjustBits = ONLY_LONG_SEQUENCE ? 9 :
                                 EIGHT_SHORT_SEQUENCE ? 6 :
                                 LONG_START_SEQUENCE ? (wd == 0 ? 8 : 6) : (wd == 0 ? 8 : 9);
                pos += adjustBits * adjustNum;
            }
        }
    }

    if (!CheckOverrun(lenBytes, pos)) {
        return false;
    }
    // spectral_data
    for (int g = 0; g < numWindowGroups; ++g) {
        int sectStart = 0;
        for (int i = 0; i < numSec[g]; ++i) {
            int codebook = sectCb[g][i];
            if (codebook == ZERO_HCB || codebook > ESC_HCB) {
                sectStart = sectEnd[g][i];
                continue;
            }
            int coefEnd = sectSfbOffset[g][sectEnd[g][i]];
            for (int k = sectSfbOffset[g][sectStart]; k < coefEnd; ) {
                if (!CheckOverrun(lenBytes, pos)) {
                    return false;
                }
                if (codebook < FIRST_PAIR_HCB) {
                    int unsigned_, w, x, y, z;
                    Huffman::DecodeSpectrumQuadBits(codebook - 1, aac, pos, unsigned_, w, x, y, z);
                    if (unsigned_) {
                        if (w) ++pos;
                        if (x) ++pos;
                        if (y) ++pos;
                        if (z) ++pos;
                    }
                    k += 4;
                }
                else {
                    int unsigned_, y, z;
                    Huffman::DecodeSpectrumPairBits(codebook - 1, aac, pos, unsigned_, y, z);
                    if (unsigned_) {
                        if (y) ++pos;
                        if (z) ++pos;
                    }
                    k += 2;
                    if (codebook == ESC_HCB) {
                        if (y == ESC_FLAG) {
                            int count = 0;
                            while (read_bool(aac, pos)) {
                                if (++count > 8) {
                                    assert(false);
                                    return false;
                                }
                            }
                            pos += count + 4;
                        }
                        if (z == ESC_FLAG) {
                            int count = 0;
                            while (read_bool(aac, pos)) {
                                if (++count > 8) {
                                    assert(false);
                                    return false;
                                }
                            }
                            pos += count + 4;
                        }
                    }
                }
            }
            sectStart = sectEnd[g][i];
        }
    }
    return true;
}

void DataStreamElement(const uint8_t *aac, size_t &pos)
{
    pos += 4;
    bool dataByteAlignFlag = read_bool(aac, pos);
    int cnt = read_bits(aac, pos, 8);
    if (cnt == 255) {
        cnt += read_bits(aac, pos, 8);
    }
    if (dataByteAlignFlag) {
        ByteAlignment(pos);
    }
    pos += 8 * cnt;
}

bool ProgramConfigElement(const uint8_t *aac, size_t lenBytes, size_t &pos)
{
    pos += 10;
    int numFrontChannelElements = read_bits(aac, pos, 4);
    int numSideChannelElements = read_bits(aac, pos, 4);
    int numBackChannelElements = read_bits(aac, pos, 4);
    int numLfeChannelElements = read_bits(aac, pos, 2);
    int numAssocDataElements = read_bits(aac, pos, 3);
    int numValidCcElements = read_bits(aac, pos, 4);
    bool monoMixdownPresent = read_bool(aac, pos);
    if (monoMixdownPresent) {
        pos += 4;
    }
    bool stereoMixdownPresent = read_bool(aac, pos);
    if (stereoMixdownPresent) {
        pos += 4;
    }
    bool matrixMixdownIdxPresent = read_bool(aac, pos);
    if (matrixMixdownIdxPresent) {
        pos += 3;
    }
    pos += 5 * numFrontChannelElements;
    pos += 5 * numSideChannelElements;
    pos += 5 * numBackChannelElements;
    pos += 4 * numLfeChannelElements;
    pos += 4 * numAssocDataElements;
    pos += 5 * numValidCcElements;

    if (!CheckOverrun(lenBytes, pos)) {
        return false;
    }
    ByteAlignment(pos);
    int commentFieldBytes = read_bits(aac, pos, 8);
    pos += 8 * commentFieldBytes;
    return true;
}

bool FillElement(const uint8_t *aac, size_t &pos)
{
    int cnt = read_bits(aac, pos, 4);
    if (cnt == 15) {
        cnt += read_bits(aac, pos, 8) - 1;
    }
    if (cnt > 0) {
        // extension_payload
        int extensionType = read_bits(aac, pos, 4);
        if (extensionType == EXT_DYNAMIC_RANGE ||
            extensionType == EXT_SBR_DATA ||
            extensionType == EXT_SBR_DATA_CRC) {
            return false;
        }
        pos += 8 * (cnt - 1) + 4;
    }
    return true;
}

int RawDataBlock(const uint8_t *aac, size_t lenBytes, size_t &pos, bool is32khz)
{
    if (!CheckOverrun(lenBytes, pos)) {
        return -1;
    }
    int id = read_bits(aac, pos, 3);
    if (id == ID_SCE) {
        if (SingleChannelElement(aac, lenBytes, pos, is32khz)) {
            return id;
        }
    }
    else if (id == ID_DSE) {
        DataStreamElement(aac, pos);
        return id;
    }
    else if (id == ID_PCE) {
        if (ProgramConfigElement(aac, lenBytes, pos)) {
            return id;
        }
    }
    else if (id == ID_FIL) {
        if (FillElement(aac, pos)) {
            return id;
        }
    }
    else if (id == ID_END) {
        return id;
    }
    return -1;
}

bool SyncPayload(std::vector<uint8_t> &workspace, const uint8_t *payload, size_t lenBytes)
{
    if (!workspace.empty() && workspace[0] == 0) {
        // No need to resync
        workspace.insert(workspace.end(), payload, payload + lenBytes);
        workspace[0] = 0xff;
    }
    else {
        // Resync
        workspace.insert(workspace.end(), payload, payload + lenBytes);
        size_t i = 0;
        for (; i < workspace.size(); ++i) {
            if (workspace[i] == 0xff && (i + 1 >= workspace.size() || (workspace[i + 1] & 0xf0) == 0xf0)) {
                break;
            }
        }
        workspace.erase(workspace.begin(), workspace.begin() + i);
        if (workspace.size() < 2) {
            return false;
        }
    }
    assert(workspace[0] == 0xff);
    return true;
}

void SkipPayload(std::vector<uint8_t> &workspace, size_t workspaceLenBytes)
{
    workspace.resize(workspaceLenBytes);
    size_t i = 0;
    while (workspaceLenBytes - i > 0) {
        if (workspace[i] != 0xff) {
            // Need to resync
            workspace.clear();
            return;
        }
        if (workspaceLenBytes - i < 7) {
            break;
        }
        if ((workspace[i + 1] & 0xf0) != 0xf0) {
            workspace.clear();
            return;
        }
        size_t pos = 30;
        size_t frameLenBytes = read_bits(workspace.data() + i, pos, 13);
        if (frameLenBytes < 7) {
            workspace.clear();
            return;
        }
        if (workspaceLenBytes - i < frameLenBytes) {
            break;
        }
        i += frameLenBytes;
    }

    // Carry over the remaining payload.
    workspace.erase(workspace.begin(), workspace.begin() + i);
    if (!workspace.empty()) {
        assert(workspace[0] == 0xff);
        // This 0 means synchronized 0xff.
        workspace[0] = 0;
    }
}
}

namespace Aac
{
bool TransmuxDualMono(std::vector<uint8_t> &destLeft, std::vector<uint8_t> &destRight, std::vector<uint8_t> &workspace,
                      bool muxLeftToStereo, bool muxRightToStereo, const uint8_t *payload, size_t lenBytes)
{
    destLeft.clear();
    destRight.clear();
    if (!SyncPayload(workspace, payload, lenBytes)) {
        // No ADTS frames, done.
        return true;
    }
    size_t workspaceLenBytes = workspace.size();
    workspace.insert(workspace.end(), EXTRA_WORKSPACE_BYTES, 0);

    while (workspaceLenBytes > 0) {
        if (workspace[0] != 0xff) {
            // Need to resync
            workspace.clear();
            return false;
        }
        if (workspaceLenBytes < 7) {
            break;
        }
        if ((workspace[1] & 0xf0) != 0xf0) {
            workspace.clear();
            return false;
        }

        // ADTS header
        const uint8_t *aac = workspace.data();
        size_t pos = 12;
        pos += 3;
        bool protectionAbsent = read_bool(aac, pos);
        pos += 2;
        int samplingFrequencyIndex = read_bits(aac, pos, 4);
        // Frequencies other than 48/44.1/32kHz are not supported.
        if (samplingFrequencyIndex < 3 || samplingFrequencyIndex > 5) {
            SkipPayload(workspace, workspaceLenBytes);
            return false;
        }
        ++pos;
        int channelConfiguration = read_bits(aac, pos, 3);
        // ARIB STD-B32 seems to define "channel_configuration = 0 and exactly 2 SCEs" as "dual mono".
        if (channelConfiguration != 0) {
            SkipPayload(workspace, workspaceLenBytes);
            return false;
        }
        pos += 4;
        size_t frameLenBytes = read_bits(aac, pos, 13);
        if (frameLenBytes < 7) {
            workspace.clear();
            return false;
        }
        if (workspaceLenBytes < frameLenBytes) {
            break;
        }
        pos += 11;
        int blocksInFrame = read_bits(aac, pos, 2);

        if (!protectionAbsent) {
            // adts(_header)_error_check
            pos += (blocksInFrame + 1) * 16;
        }

        size_t sceBegin[4][2];
        size_t sceEnd[4][2];
        for (int i = 0; i <= blocksInFrame; ++i) {
            int sceCount = 0;
            for (;;) {
                size_t beginPos = pos;
                int id = RawDataBlock(aac, frameLenBytes, pos, samplingFrequencyIndex == 5);
                if (id < 0) {
                    SkipPayload(workspace, workspaceLenBytes);
                    return false;
                }
                if (id == ID_END) {
                    break;
                }
                if (id == ID_SCE) {
                    if (sceCount >= 2) {
                        SkipPayload(workspace, workspaceLenBytes);
                        return false;
                    }
                    sceBegin[i][sceCount] = beginPos;
                    sceEnd[i][sceCount++] = pos;
                }
            }
            if (sceCount != 2) {
                SkipPayload(workspace, workspaceLenBytes);
                return false;
            }
            ByteAlignment(pos);
            if (blocksInFrame != 0 && !protectionAbsent) {
                // adts_raw_data_block_error_check
                pos += 16;
            }
        }

        assert(pos == frameLenBytes * 8);
        if (!CheckOverrun(frameLenBytes, pos)) {
            SkipPayload(workspace, workspaceLenBytes);
            return false;
        }

        // Append 2 ADTS
        for (int destIndex = 0; destIndex < 2; ++destIndex) {
            std::vector<uint8_t> &dest = destIndex == 0 ? destLeft : destRight;
            bool muxToStereo = destIndex == 0 ? muxLeftToStereo : muxRightToStereo;

            // ADTS header
            size_t destHeadBytes = dest.size();
            dest.insert(dest.end(), aac, aac + 7);
            // protection_absent = 1
            dest[destHeadBytes + 1] |= 0x01;
            // channel_configuration = 2 or 1
            dest[destHeadBytes + 3] |= muxToStereo ? 0x80 : 0x40;

            for (int i = 0; i <= blocksInFrame; ++i) {
                size_t scePos = sceBegin[i][destIndex];
                size_t sceEndPos = sceEnd[i][destIndex];
                if (muxToStereo) {
                    // CPE
                    scePos += 3;
                    // Copy element_instance_tag, common_window = 0
                    dest.push_back((ID_CPE << 5) | static_cast<uint8_t>(read_bits(aac, scePos, 4) << 1));

                    // Left individual_channel_stream
                    size_t leftPos = scePos;
                    while (leftPos + 7 < sceEndPos) {
                        dest.push_back(static_cast<uint8_t>(read_bits(aac, leftPos, 8)));
                    }
                    int leftRemain = static_cast<int>(sceEndPos - leftPos);
                    if (leftRemain != 0) {
                        dest.push_back(static_cast<uint8_t>(read_bits(aac, leftPos, leftRemain)) << (8 - leftRemain));
                    }
                    // Right individual_channel_stream
                    if (leftRemain != 0) {
                        dest.back() |= static_cast<uint8_t>(read_bits(aac, scePos, 8 - leftRemain));
                    }
                }
                // SCE or Right individual_channel_stream
                while (scePos + 7 < sceEndPos) {
                    dest.push_back(static_cast<uint8_t>(read_bits(aac, scePos, 8)));
                }
                int sceRemain = static_cast<int>(sceEndPos - scePos);
                if (sceRemain != 0) {
                    dest.push_back((static_cast<uint8_t>(read_bits(aac, scePos, sceRemain)) << (8 - sceRemain)) | (0xe0 >> sceRemain));
                }
                if (sceRemain == 0 || sceRemain >= 6) {
                    // ID_END, remaining bits are filled with 0.
                    dest.push_back((0x60e0 >> sceRemain) & 0xe0);
                }
            }

            // aac_frame_length
            size_t destFrameLenBytes = dest.size() - destHeadBytes;
            dest[destHeadBytes + 3] = (dest[destHeadBytes + 3] & 0xfc) | static_cast<uint8_t>(destFrameLenBytes >> 11);
            dest[destHeadBytes + 4] = static_cast<uint8_t>(destFrameLenBytes >> 3);
            dest[destHeadBytes + 5] = static_cast<uint8_t>(destFrameLenBytes << 5) | (dest[destHeadBytes + 5] & 0x1f);
        }

        // Erase current frame.
        workspace.erase(workspace.begin(), workspace.begin() + frameLenBytes);
        workspaceLenBytes -= frameLenBytes;
    }

    SkipPayload(workspace, workspaceLenBytes);
    return true;
}

bool TransmuxMonoToStereo(std::vector<uint8_t> &dest, std::vector<uint8_t> &workspace, const uint8_t *payload, size_t lenBytes)
{
    dest.clear();
    if (!SyncPayload(workspace, payload, lenBytes)) {
        // No ADTS frames, done.
        return true;
    }
    size_t workspaceLenBytes = workspace.size();
    workspace.insert(workspace.end(), EXTRA_WORKSPACE_BYTES, 0);

    while (workspaceLenBytes > 0) {
        if (workspace[0] != 0xff) {
            // Need to resync
            workspace.clear();
            return false;
        }
        if (workspaceLenBytes < 7) {
            break;
        }
        if ((workspace[1] & 0xf0) != 0xf0) {
            workspace.clear();
            return false;
        }

        // ADTS header
        const uint8_t *aac = workspace.data();
        size_t pos = 12;
        pos += 3;
        bool protectionAbsent = read_bool(aac, pos);
        pos += 2;
        int samplingFrequencyIndex = read_bits(aac, pos, 4);
        // Frequencies other than 48/44.1/32kHz are not supported.
        if (samplingFrequencyIndex < 3 || samplingFrequencyIndex > 5) {
            SkipPayload(workspace, workspaceLenBytes);
            return false;
        }
        ++pos;
        int channelConfiguration = read_bits(aac, pos, 3);
        if (channelConfiguration != 1) {
            SkipPayload(workspace, workspaceLenBytes);
            return false;
        }
        pos += 4;
        size_t frameLenBytes = read_bits(aac, pos, 13);
        if (frameLenBytes < 7) {
            workspace.clear();
            return false;
        }
        if (workspaceLenBytes < frameLenBytes) {
            break;
        }
        pos += 11;
        int blocksInFrame = read_bits(aac, pos, 2);

        if (!protectionAbsent) {
            // adts(_header)_error_check
            pos += (blocksInFrame + 1) * 16;
        }

        size_t sceBegin[4];
        size_t sceEnd[4];
        for (int i = 0; i <= blocksInFrame; ++i) {
            bool sceFound = false;
            for (;;) {
                size_t beginPos = pos;
                int id = RawDataBlock(aac, frameLenBytes, pos, samplingFrequencyIndex == 5);
                if (id < 0) {
                    SkipPayload(workspace, workspaceLenBytes);
                    return false;
                }
                if (id == ID_END) {
                    break;
                }
                if (id == ID_SCE) {
                    if (sceFound) {
                        SkipPayload(workspace, workspaceLenBytes);
                        return false;
                    }
                    sceBegin[i] = beginPos;
                    sceEnd[i] = pos;
                    sceFound = true;
                }
            }
            if (!sceFound) {
                SkipPayload(workspace, workspaceLenBytes);
                return false;
            }
            ByteAlignment(pos);
            if (blocksInFrame != 0 && !protectionAbsent) {
                // adts_raw_data_block_error_check
                pos += 16;
            }
        }

        assert(pos == frameLenBytes * 8);
        if (!CheckOverrun(frameLenBytes, pos)) {
            SkipPayload(workspace, workspaceLenBytes);
            return false;
        }

        // ADTS header
        size_t destHeadBytes = dest.size();
        dest.insert(dest.end(), aac, aac + 7);
        // protection_absent = 1
        dest[destHeadBytes + 1] |= 0x01;
        // channel_configuration = 2
        dest[destHeadBytes + 3] = (dest[destHeadBytes + 3] & 0x3f) | 0x80;

        for (int i = 0; i <= blocksInFrame; ++i) {
            size_t scePos = sceBegin[i];
            size_t sceEndPos = sceEnd[i];
            {
                // CPE
                scePos += 3;
                // Copy element_instance_tag, common_window = 0
                dest.push_back((ID_CPE << 5) | static_cast<uint8_t>(read_bits(aac, scePos, 4) << 1));

                // Left individual_channel_stream
                size_t leftPos = scePos;
                while (leftPos + 7 < sceEndPos) {
                    dest.push_back(static_cast<uint8_t>(read_bits(aac, leftPos, 8)));
                }
                int leftRemain = static_cast<int>(sceEndPos - leftPos);
                if (leftRemain != 0) {
                    dest.push_back(static_cast<uint8_t>(read_bits(aac, leftPos, leftRemain)) << (8 - leftRemain));
                }
                // Right individual_channel_stream
                if (leftRemain != 0) {
                    dest.back() |= static_cast<uint8_t>(read_bits(aac, scePos, 8 - leftRemain));
                }
            }
            while (scePos + 7 < sceEndPos) {
                dest.push_back(static_cast<uint8_t>(read_bits(aac, scePos, 8)));
            }
            int sceRemain = static_cast<int>(sceEndPos - scePos);
            if (sceRemain != 0) {
                dest.push_back((static_cast<uint8_t>(read_bits(aac, scePos, sceRemain)) << (8 - sceRemain)) | (0xe0 >> sceRemain));
            }
            if (sceRemain == 0 || sceRemain >= 6) {
                // ID_END, remaining bits are filled with 0.
                dest.push_back((0x60e0 >> sceRemain) & 0xe0);
            }
        }

        // aac_frame_length
        size_t destFrameLenBytes = dest.size() - destHeadBytes;
        dest[destHeadBytes + 3] = (dest[destHeadBytes + 3] & 0xfc) | static_cast<uint8_t>(destFrameLenBytes >> 11);
        dest[destHeadBytes + 4] = static_cast<uint8_t>(destFrameLenBytes >> 3);
        dest[destHeadBytes + 5] = static_cast<uint8_t>(destFrameLenBytes << 5) | (dest[destHeadBytes + 5] & 0x1f);

        // Erase current frame.
        workspace.erase(workspace.begin(), workspace.begin() + frameLenBytes);
        workspaceLenBytes -= frameLenBytes;
    }

    SkipPayload(workspace, workspaceLenBytes);
    return true;
}
}
