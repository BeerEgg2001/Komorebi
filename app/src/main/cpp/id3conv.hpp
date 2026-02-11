#ifndef INCLUDE_ID3CONV_HPP
#define INCLUDE_ID3CONV_HPP

#include "util.hpp"
#include <stdint.h>
#include <unordered_set>
#include <utility>
#include <vector>

class CID3Converter
{
public:
    CID3Converter();
    void AddPacket(const uint8_t *packet);
    void SetOption(int flags);
    const std::vector<uint8_t> &GetPackets() const { return m_packets; }
    void ClearPackets() { m_packets.clear(); }

private:
    void AddPmt(int pid, const PSI &psi);
    void CheckPrivateDataPes(const std::vector<uint8_t> &pes);

    bool m_enabled;
    bool m_treatUnknownPrivateDataAsSuperimpose;
    bool m_insertInappropriate5BytesIntoPesPayload;
    bool m_forceMonotonousPts;
    int64_t m_lastID3Pts;
    std::vector<uint8_t> m_packets;
    PAT m_pat;
    int m_firstPmtPid;
    PSI m_firstPmtPsi;
    std::unordered_set<int> m_removePidSet;
    int m_captionPid;
    int m_superimposePid;
    std::pair<int, std::vector<uint8_t>> m_captionPes;
    std::pair<int, std::vector<uint8_t>> m_superimposePes;
    int m_pcrPid;
    int64_t m_pcr;
    int m_id3Pid;
    uint8_t m_id3Counter;
    uint8_t m_pmtCounter;
    std::vector<uint8_t> m_buf;
};

#endif
