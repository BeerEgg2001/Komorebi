#ifndef INCLUDE_TRACEB24_HPP
#define INCLUDE_TRACEB24_HPP

#include "util.hpp"
#include <stdint.h>
#include <stdio.h>
#include <utility>
#include <vector>

class CTraceB24Caption
{
public:
    CTraceB24Caption();
    void AddPacket(const uint8_t *packet);
    void SetFile(FILE *fp) { m_fp = fp; }

private:
    enum LANG_TAG_TYPE
    {
        LANG_TAG_ABSENT,
        LANG_TAG_UNKNOWN,
        LANG_TAG_UCS,
        LANG_TAG_ARIB8,
        LANG_TAG_ARIB8_LATIN,
    };

    enum PARSE_PRIVATE_DATA_RESULT
    {
        PARSE_PRIVATE_DATA_SUCCEEDED,
        PARSE_PRIVATE_DATA_FAILED,
        PARSE_PRIVATE_DATA_FAILED_CRC,
        PARSE_PRIVATE_DATA_FAILED_NEED_MANAGEMENT,
        PARSE_PRIVATE_DATA_FAILED_UNSUPPORTED,
    };

    void CheckPmt(const PSI &psi);
    void OutputPrivateDataPes(const std::vector<uint8_t> &pes,
                              std::vector<uint16_t> &drcsList, LANG_TAG_TYPE (&langTags)[8]);
    static PARSE_PRIVATE_DATA_RESULT ParsePrivateData(std::vector<uint8_t> &buf, std::vector<int> &textPosList, const uint8_t *data, size_t dataSize,
                                                      std::vector<uint16_t> &drcsList, LANG_TAG_TYPE (&langTags)[8]);

    FILE *m_fp;
    PAT m_pat;
    int m_firstPmtPid;
    PSI m_firstPmtPsi;
    int m_captionPid;
    int m_superimposePid;
    std::pair<int, std::vector<uint8_t>> m_captionPes;
    std::pair<int, std::vector<uint8_t>> m_superimposePes;
    std::vector<uint16_t> m_captionDrcsList;
    std::vector<uint16_t> m_superimposeDrcsList;
    LANG_TAG_TYPE m_captionLangTags[8];
    LANG_TAG_TYPE m_superimposeLangTags[8];
    int m_pcrPid;
    int64_t m_pcr;
    std::vector<uint8_t> m_buf;
    std::vector<int> m_intBuf;
};

#endif
