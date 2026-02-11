#ifndef INCLUDE_SERVICEFILTER_HPP
#define INCLUDE_SERVICEFILTER_HPP

#include "util.hpp"
#include <stdint.h>
#include <vector>

class CServiceFilter
{
public:
    CServiceFilter();
    void SetProgramNumberOrIndex(int n) { m_programNumberOrIndex = n; }
    void SetAudio1Mode(int mode);
    void SetAudio2Mode(int mode);
    void SetCaptionMode(int mode);
    void SetSuperimposeMode(int mode);
    void AddPacket(const uint8_t *packet);
    const std::vector<uint8_t> &GetPackets() const { return m_packets; }
    void ClearPackets() { m_packets.clear(); }

private:
    const uint8_t H_262_VIDEO = 0x02;
    const uint8_t MPEG2_AUDIO = 0x04;
    const uint8_t PES_PRIVATE_DATA = 0x06;
    const uint8_t ADTS_TRANSPORT = 0x0f;
    const uint8_t AVC_VIDEO = 0x1b;
    const uint8_t H_265_VIDEO = 0x24;

    static std::vector<PMT_REF>::const_iterator FindNitRef(const std::vector<PMT_REF> &pmt);
    std::vector<PMT_REF>::const_iterator FindTargetPmtRef(const std::vector<PMT_REF> &pmt) const;
    void AddPat(int transportStreamID, int programNumber, bool addNit);
    void AddPmt(const PSI &psi);
    void AddPcrAdaptation(const uint8_t *pcr);
    void ChangePidAndAddPacket(const uint8_t *packet, int pid, uint8_t counter = 0xff);
    void AddAudioPesPackets(uint8_t index, int64_t targetPts, int64_t &pts, uint8_t &counter);
    void Add64MsecAudioPesPacket(uint8_t index, int64_t pts, uint8_t &counter);
    static int64_t GetAudioPresentationTimeStamp(int unitStart, const uint8_t *payload, int payloadSize);
    static bool AccumulatePesPackets(std::vector<uint8_t> &unitPackets, const uint8_t *packet, int unitStart);
    static void ConcatenatePayload(std::vector<uint8_t> &dest, const std::vector<uint8_t> &unitPackets, bool &pcrFlag, uint8_t (&pcr)[6]);
    void AddCaptionManagementPesPacket(int64_t pts, uint8_t counter);
    void AddSuperimposeManagementPesPacket(uint8_t counter);
    void AddAudioPesPackets(const std::vector<uint8_t> &pes, int pid, uint8_t &counter, int64_t &ptsPcrDiff, const uint8_t *pcr);
    bool TransmuxMonoToStereo(const std::vector<uint8_t> &unitPackets, std::vector<uint8_t> &workspace,
                              int pid, uint8_t &counter, int64_t &ptsPcrDiff);
    bool TransmuxDualMono(const std::vector<uint8_t> &unitPackets);

    int m_programNumberOrIndex;
    int m_audio1Mode;
    int m_audio2Mode;
    bool m_audio1MuxToStereo;
    bool m_audio2MuxToStereo;
    bool m_audio1MuxDualMono;
    int m_captionMode;
    int m_superimposeMode;
    bool m_captionInsertManagementPacket;
    bool m_superimposeInsertManagementPacket;
    std::vector<uint8_t> m_packets;
    PAT m_pat;
    PSI m_pmtPsi;
    int m_videoPid;
    int m_audio1Pid;
    int m_audio2Pid;
    uint8_t m_audio1StreamType;
    uint8_t m_audio2StreamType;
    int m_captionPid;
    int m_superimposePid;
    int m_pcrPid;
    int64_t m_pcr;
    uint8_t m_patCounter;
    uint8_t m_pmtCounter;
    uint8_t m_audio1PesCounter;
    uint8_t m_audio2PesCounter;
    uint8_t m_captionPesCounter;
    uint8_t m_superimposePesCounter;
    bool m_isAudio1DualMono;
    std::vector<uint8_t> m_audio1UnitPackets;
    std::vector<uint8_t> m_audio2UnitPackets;
    std::vector<uint8_t> m_audio1MuxWorkspace;
    std::vector<uint8_t> m_audio2MuxWorkspace;
    std::vector<uint8_t> m_audio1MuxDualMonoWorkspace;
    int64_t m_audio1Pts;
    int64_t m_audio2Pts;
    int64_t m_audio1PtsPcrDiff;
    int64_t m_audio2PtsPcrDiff;
    int64_t m_captionManagementPcr;
    int64_t m_superimposeManagementPcr;
    std::vector<uint8_t> m_buf;
    std::vector<uint8_t> m_destLeftBuf;
    std::vector<uint8_t> m_destRightBuf;
    std::vector<uint8_t> m_lastPat;
    std::vector<uint8_t> m_lastPmt;
};

#endif
