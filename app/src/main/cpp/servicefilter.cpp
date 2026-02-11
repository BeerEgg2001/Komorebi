#include "servicefilter.hpp"
#include "aac.hpp"
#include <algorithm>

CServiceFilter::CServiceFilter()
    : m_programNumberOrIndex(0)
    , m_audio1Mode(0)
    , m_audio2Mode(0)
    , m_audio1MuxToStereo(false)
    , m_audio2MuxToStereo(false)
    , m_audio1MuxDualMono(false)
    , m_captionMode(0)
    , m_superimposeMode(0)
    , m_captionInsertManagementPacket(false)
    , m_superimposeInsertManagementPacket(false)
    , m_videoPid(0)
    , m_audio1Pid(0)
    , m_audio2Pid(0)
    , m_audio1StreamType(0)
    , m_audio2StreamType(0)
    , m_captionPid(0)
    , m_superimposePid(0)
    , m_pcrPid(0)
    , m_pcr(-1)
    , m_patCounter(0)
    , m_pmtCounter(0)
    , m_audio1PesCounter(0)
    , m_audio2PesCounter(0)
    , m_captionPesCounter(0xff)
    , m_superimposePesCounter(0xff)
    , m_isAudio1DualMono(false)
    , m_audio1Pts(-1)
    , m_audio2Pts(-1)
    , m_audio1PtsPcrDiff(0)
    , m_audio2PtsPcrDiff(-1)
    , m_captionManagementPcr(-1)
    , m_superimposeManagementPcr(-1)
{
    static const PAT zeroPat = {};
    m_pat = zeroPat;
    m_pmtPsi = zeroPat.psi;
}

void CServiceFilter::SetAudio1Mode(int mode)
{
    m_audio1Mode = mode % 4;
    m_audio1MuxToStereo = !!(mode & 4);
    m_audio1MuxDualMono = !!(mode & 8);
}

void CServiceFilter::SetAudio2Mode(int mode)
{
    m_audio2Mode = mode % 4;
    m_audio2MuxToStereo = !!(mode & 4);
}

void CServiceFilter::SetCaptionMode(int mode)
{
    m_captionMode = mode % 4;
    m_captionInsertManagementPacket = !!(mode & 4);
}

void CServiceFilter::SetSuperimposeMode(int mode)
{
    m_superimposeMode = mode % 4;
    m_superimposeInsertManagementPacket = !!(mode & 4);
}

void CServiceFilter::AddPacket(const uint8_t *packet)
{
    if (m_programNumberOrIndex == 0) {
        m_packets.insert(m_packets.end(), packet, packet + 188);
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
        auto itPmt = FindTargetPmtRef(m_pat.pmt);
        if (itPmt != m_pat.pmt.end()) {
            if (unitStart) {
                AddPat(m_pat.transport_stream_id, itPmt->program_number, FindNitRef(m_pat.pmt) != m_pat.pmt.end());
            }
        }
        else {
            m_videoPid = 0;
            m_audio1Pid = 0;
            m_audio2Pid = 0;
            m_captionPid = 0;
            m_superimposePid = 0;
            m_pcrPid = 0;
            m_pcr = -1;
        }
    }
    else {
        auto itPmt = FindTargetPmtRef(m_pat.pmt);
        if (itPmt != m_pat.pmt.end()) {
            if (pid == itPmt->pmt_pid) {
                int done;
                do {
                    done = extract_psi(&m_pmtPsi, payload, payloadSize, unitStart, counter);
                    if (m_pmtPsi.version_number && m_pmtPsi.table_id == 2 && m_pmtPsi.current_next_indicator) {
                        AddPmt(m_pmtPsi);
                    }
                }
                while (!done);
            }
            if (pid == m_pcrPid) {
                if (adaptation & 2) {
                    int adaptationLength = packet[4];
                    if (adaptationLength >= 6 && !!(packet[5] & 0x10)) {
                        if (pid != m_videoPid &&
                            pid != m_audio1Pid &&
                            pid != m_audio2Pid &&
                            pid != m_captionPid &&
                            pid != m_superimposePid) {
                            AddPcrAdaptation(packet + 6);
                        }
                        m_pcr = (packet[10] >> 7) |
                                (packet[9] << 1) |
                                (packet[8] << 9) |
                                (packet[7] << 17) |
                                (static_cast<int64_t>(packet[6]) << 25);
                        if (m_audio1Mode == 1 && m_audio1Pid == 0) {
                            AddAudioPesPackets(0, (m_pcr + m_audio1PtsPcrDiff) & 0x1ffffffff, m_audio1Pts, m_audio1PesCounter);
                        }
                        if ((m_audio2Mode == 1 || (m_audio2Mode == 3 && m_audio1Pid == 0)) && m_audio2Pid == 0 && !m_isAudio1DualMono) {
                            if (m_audio2PtsPcrDiff < 0) {
                                m_audio2PtsPcrDiff = m_audio1PtsPcrDiff;
                            }
                            AddAudioPesPackets(1, (m_pcr + m_audio2PtsPcrDiff) & 0x1ffffffff, m_audio2Pts, m_audio2PesCounter);
                        }

                        static const int INSERT_MANAGEMENT_DETERMINE_ABSENCE_SEC = 15;
                        static const int INSERT_MANAGEMENT_INTERVAL_SEC = INSERT_MANAGEMENT_DETERMINE_ABSENCE_SEC - 5;
                        if (m_captionManagementPcr >= 0 &&
                            m_captionInsertManagementPacket &&
                            (m_captionPid != 0 || m_captionMode == 1)) {
                            int64_t pcrDiff = (0x200000000 + m_pcr - m_captionManagementPcr) & 0x1ffffffff;
                            if (pcrDiff > 90000 * INSERT_MANAGEMENT_DETERMINE_ABSENCE_SEC) {
                                if (pcrDiff < 90000 * INSERT_MANAGEMENT_DETERMINE_ABSENCE_SEC * 2) {
                                    m_captionPesCounter = (m_captionPesCounter + 1) & 0x0f;
                                    AddCaptionManagementPesPacket(m_pcr, m_captionPesCounter);
                                }
                                m_captionManagementPcr = (0x200000000 + m_pcr - 90000 * INSERT_MANAGEMENT_INTERVAL_SEC) & 0x1ffffffff;
                            }
                        }
                        else {
                            m_captionManagementPcr = m_pcr;
                        }
                        if (m_superimposeManagementPcr >= 0 &&
                            m_superimposeInsertManagementPacket &&
                            (m_superimposePid != 0 || m_superimposeMode == 1)) {
                            int64_t pcrDiff = (0x200000000 + m_pcr - m_superimposeManagementPcr) & 0x1ffffffff;
                            if (pcrDiff > 90000 * INSERT_MANAGEMENT_DETERMINE_ABSENCE_SEC) {
                                if (pcrDiff < 90000 * INSERT_MANAGEMENT_DETERMINE_ABSENCE_SEC * 2) {
                                    m_superimposePesCounter = (m_superimposePesCounter + 1) & 0x0f;
                                    AddSuperimposeManagementPesPacket(m_superimposePesCounter);
                                }
                                m_superimposeManagementPcr = (0x200000000 + m_pcr - 90000 * INSERT_MANAGEMENT_INTERVAL_SEC) & 0x1ffffffff;
                            }
                        }
                        else {
                            m_superimposeManagementPcr = m_pcr;
                        }
                    }
                }
            }
            if (pid == m_videoPid) {
                ChangePidAndAddPacket(packet, 0x0100);
            }
            else if (pid == m_audio1Pid) {
                if (AccumulatePesPackets(m_audio1UnitPackets, packet, unitStart)) {
                    bool passthroughAudio1 = false;
                    bool copyToAudio2 = false;
                    m_isAudio1DualMono = m_audio1MuxDualMono && m_audio1StreamType == ADTS_TRANSPORT && TransmuxDualMono(m_audio1UnitPackets);
                    if (m_isAudio1DualMono) {
                        // Already added
                        m_audio1UnitPackets.clear();
                    }
                    else {
                        passthroughAudio1 = !m_audio1MuxToStereo || m_audio1StreamType != ADTS_TRANSPORT ||
                                            !TransmuxMonoToStereo(m_audio1UnitPackets, m_audio1MuxWorkspace, 0x0110, m_audio1PesCounter, m_audio1PtsPcrDiff);
                        // Copy audio1 to audio2 if needed
                        copyToAudio2 = m_audio2Mode == 3 && m_audio2Pid == 0;
                        if (copyToAudio2 && m_audio2MuxToStereo && m_audio1StreamType == ADTS_TRANSPORT &&
                            TransmuxMonoToStereo(m_audio1UnitPackets, m_audio2MuxWorkspace, 0x0111, m_audio2PesCounter, m_audio2PtsPcrDiff)) {
                            // Already added
                            copyToAudio2 = false;
                        }
                        if (!passthroughAudio1 && !copyToAudio2) {
                            m_audio1UnitPackets.clear();
                        }
                    }
                    // Add packets
                    for (size_t i = 0; i + 188 <= m_audio1UnitPackets.size(); i += 188) {
                        const uint8_t *packet_ = m_audio1UnitPackets.data() + i;
                        int payloadSize_ = get_ts_payload_size(packet_);
                        const uint8_t *payload_ = packet_ + 188 - payloadSize_;
                        int64_t pts = GetAudioPresentationTimeStamp(i == 0, payload_, payloadSize_);
                        if (passthroughAudio1) {
                            if (pts >= 0 && m_pcr >= 0) {
                                m_audio1PtsPcrDiff = 0x200000000 + pts - m_pcr;
                            }
                            m_audio1PesCounter = (m_audio1PesCounter + 1) & 0x0f;
                            ChangePidAndAddPacket(packet_, 0x0110, m_audio1PesCounter);
                        }
                        if (copyToAudio2) {
                            // Copy audio1 to audio2
                            if (pts >= 0 && m_pcr >= 0) {
                                m_audio2PtsPcrDiff = 0x200000000 + pts - m_pcr;
                            }
                            m_audio2PesCounter = (m_audio2PesCounter + 1) & 0x0f;
                            ChangePidAndAddPacket(packet_, 0x0111, m_audio2PesCounter);
                        }
                    }
                    m_audio1UnitPackets.clear();
                }
            }
            else if (pid == m_audio2Pid) {
                if (AccumulatePesPackets(m_audio2UnitPackets, packet, unitStart)) {
                    if (m_audio2MuxToStereo && m_audio2StreamType == ADTS_TRANSPORT &&
                        TransmuxMonoToStereo(m_audio2UnitPackets, m_audio2MuxWorkspace, 0x0111, m_audio2PesCounter, m_audio2PtsPcrDiff)) {
                        // Already added
                        m_audio2UnitPackets.clear();
                    }
                    // Add packets
                    for (size_t i = 0; i + 188 <= m_audio2UnitPackets.size(); i += 188) {
                        const uint8_t *packet_ = m_audio2UnitPackets.data() + i;
                        int payloadSize_ = get_ts_payload_size(packet_);
                        const uint8_t *payload_ = packet_ + 188 - payloadSize_;
                        int64_t pts = GetAudioPresentationTimeStamp(i == 0, payload_, payloadSize_);
                        if (pts >= 0 && m_pcr >= 0) {
                            m_audio2PtsPcrDiff = 0x200000000 + pts - m_pcr;
                        }
                        m_audio2PesCounter = (m_audio2PesCounter + 1) & 0x0f;
                        ChangePidAndAddPacket(packet_, 0x0111, m_audio2PesCounter);
                    }
                    m_audio2UnitPackets.clear();
                }
            }
            else if (pid == m_captionPid) {
                m_captionManagementPcr = m_pcr;
                m_captionPesCounter = m_captionPesCounter > 0x0f ? 0x10 | (counter & 0x0f) : (m_captionPesCounter + 1) & 0x0f;
                ChangePidAndAddPacket(packet, 0x0130, m_captionPesCounter & 0x0f);
            }
            else if (pid == m_superimposePid) {
                m_superimposeManagementPcr = m_pcr;
                m_superimposePesCounter = m_superimposePesCounter > 0x0f ? 0x10 | (counter & 0x0f) : (m_superimposePesCounter + 1) & 0x0f;
                ChangePidAndAddPacket(packet, 0x0138, m_superimposePesCounter & 0x0f);
            }
            else if (pid < 0x0030) {
                m_packets.insert(m_packets.end(), packet, packet + 188);
            }
            else {
                auto itNit = FindNitRef(m_pat.pmt);
                if (itNit != m_pat.pmt.end() && pid == itNit->pmt_pid) {
                    // NIT pid should be 0x0010. This case is unusual.
                    ChangePidAndAddPacket(packet, 0x0010);
                }
            }
        }
    }
}

std::vector<PMT_REF>::const_iterator CServiceFilter::FindNitRef(const std::vector<PMT_REF> &pmt)
{
    return std::find_if(pmt.begin(), pmt.end(), [](const PMT_REF &a) { return a.program_number == 0; });
}

std::vector<PMT_REF>::const_iterator CServiceFilter::FindTargetPmtRef(const std::vector<PMT_REF> &pmt) const
{
    if (m_programNumberOrIndex < 0) {
        int index = -m_programNumberOrIndex;
        for (auto it = pmt.begin(); it != pmt.end(); ++it) {
            if (it->program_number != 0) {
                if (--index == 0) {
                    return it;
                }
            }
        }
        return pmt.end();
    }
    return std::find_if(pmt.begin(), pmt.end(), [=](const PMT_REF &a) { return a.program_number == m_programNumberOrIndex; });
}

void CServiceFilter::AddPat(int transportStreamID, int programNumber, bool addNit)
{
    // Create PAT
    m_buf.assign(9, 0);
    m_buf[1] = 0x00;
    m_buf[2] = 0xb0;
    m_buf[3] = addNit ? 17 : 13;
    m_buf[4] = static_cast<uint8_t>(transportStreamID >> 8);
    m_buf[5] = static_cast<uint8_t>(transportStreamID);
    m_buf[6] = m_lastPat.size() > 6 ? m_lastPat[6] : 0xc1;
    if (addNit) {
        m_buf.push_back(0);
        m_buf.push_back(0);
        m_buf.push_back(0xe0);
        m_buf.push_back(0x10);
    }
    m_buf.push_back(static_cast<uint8_t>(programNumber >> 8));
    m_buf.push_back(static_cast<uint8_t>(programNumber));
    // PMT_PID=0x01f0
    m_buf.push_back(0xe1);
    m_buf.push_back(0xf0);
    if (m_lastPat.size() == m_buf.size() + 4 &&
        std::equal(m_buf.begin(), m_buf.end(), m_lastPat.begin())) {
        // Copy CRC
        m_buf.insert(m_buf.end(), m_lastPat.end() - 4, m_lastPat.end());
    }
    else {
        // Increment version number
        m_buf[6] = 0xc1 | (((m_buf[6] >> 1) + 1) & 0x1f) << 1;
        uint32_t crc = calc_crc32(m_buf.data() + 1, static_cast<int>(m_buf.size() - 1));
        m_buf.push_back(crc >> 24);
        m_buf.push_back((crc >> 16) & 0xff);
        m_buf.push_back((crc >> 8) & 0xff);
        m_buf.push_back(crc & 0xff);
        m_lastPat = m_buf;
    }

    // Create TS packet
    m_packets.push_back(0x47);
    m_packets.push_back(0x40);
    m_packets.push_back(0x00);
    m_patCounter = (m_patCounter + 1) & 0x0f;
    m_packets.push_back(0x10 | m_patCounter);
    m_packets.insert(m_packets.end(), m_buf.begin(), m_buf.end());
    m_packets.resize((m_packets.size() / 188 + 1) * 188, 0xff);
}

void CServiceFilter::AddPmt(const PSI &psi)
{
    if (psi.section_length < 9) {
        return;
    }
    const uint8_t *table = psi.data;
    int programNumber = (table[3] << 8) | table[4];
    m_pcrPid = ((table[8] & 0x1f) << 8) | table[9];
    if (m_pcrPid == 0x1fff) {
        m_pcr = -1;
    }
    int programInfoLength = ((table[10] & 0x03) << 8) | table[11];
    int pos = 3 + 9 + programInfoLength;
    if (psi.section_length < pos) {
        return;
    }

    // Create PMT
    m_buf.assign(13, 0);
    m_buf[1] = 0x02;
    m_buf[4] = static_cast<uint8_t>(programNumber >> 8);
    m_buf[5] = static_cast<uint8_t>(programNumber);
    m_buf[6] = m_lastPmt.size() > 6 ? m_lastPmt[6] : 0xc1;
    // PCR_PID=0x01ff
    m_buf[9] = 0xe1;
    m_buf[10] = 0xff;
    m_buf[11] = 0xf0 | static_cast<uint8_t>(programInfoLength >> 8);
    m_buf[12] = static_cast<uint8_t>(programInfoLength);
    // Copy 1st descriptor loop
    m_buf.insert(m_buf.end(), table + 12, table + pos);

    int lastAudio1Pid = m_audio1Pid;
    int lastAudio2Pid = m_audio2Pid;
    m_videoPid = 0;
    m_audio1Pid = 0;
    m_audio2Pid = 0;
    m_captionPid = 0;
    m_superimposePid = 0;
    m_audio1StreamType = ADTS_TRANSPORT;
    m_audio2StreamType = ADTS_TRANSPORT;
    int videoDescPos = 0;
    int audio1DescPos = 0;
    int audio2DescPos = 0;
    int captionDescPos = 0;
    int superimposeDescPos = 0;
    bool maybeCProfile = false;
    bool audio1ComponentTagUnknown = true;

    int tableLen = 3 + psi.section_length - 4/*CRC32*/;
    while (pos + 4 < tableLen) {
        uint8_t streamType = table[pos];
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
            if (streamType == H_262_VIDEO ||
                streamType == AVC_VIDEO ||
                streamType == H_265_VIDEO) {
                if ((m_videoPid == 0 && componentTag == 0xff) || componentTag == 0x00 || componentTag == 0x81) {
                    m_videoPid = esPid;
                    videoDescPos = pos;
                    maybeCProfile = componentTag == 0x81;
                }
            }
            else if (streamType == ADTS_TRANSPORT) {
                if ((m_audio1Pid == 0 && componentTag == 0xff) || componentTag == 0x10 || componentTag == 0x83 || componentTag == 0x85) {
                    m_audio1Pid = esPid;
                    m_audio1StreamType = streamType;
                    audio1DescPos = pos;
                    audio1ComponentTagUnknown = componentTag == 0xff;
                }
                else if (componentTag == 0x11) {
                    if (m_audio2Mode != 2) {
                        m_audio2Pid = esPid;
                        m_audio2StreamType = streamType;
                        audio2DescPos = pos;
                    }
                }
            }
            else if (streamType == MPEG2_AUDIO) {
                if (m_audio1Pid == 0) {
                    m_audio1Pid = esPid;
                    m_audio1StreamType = streamType;
                    audio1DescPos = pos;
                    audio1ComponentTagUnknown = false;
                }
                else if (m_audio2Pid == 0) {
                    if (m_audio2Mode != 2) {
                        m_audio2Pid = esPid;
                        m_audio2StreamType = streamType;
                        audio2DescPos = pos;
                    }
                }
            }
            else if (streamType == PES_PRIVATE_DATA) {
                if (componentTag == 0x30 || componentTag == 0x87) {
                    if (m_captionMode != 2) {
                        m_captionPid = esPid;
                        captionDescPos = pos;
                    }
                }
                else if (componentTag == 0x38 || componentTag == 0x88) {
                    if (m_superimposeMode != 2) {
                        m_superimposePid = esPid;
                        superimposeDescPos = pos;
                    }
                }
            }
        }
        pos += 5 + esInfoLength;
    }

    if (m_audio1Pid != lastAudio1Pid) {
        m_audio1Pts = -1;
        m_isAudio1DualMono = false;
        m_audio1UnitPackets.clear();
        m_audio1MuxWorkspace.clear();
        m_audio1MuxDualMonoWorkspace.clear();
    }
    if (m_audio2Pid != lastAudio2Pid) {
        m_audio2Pts = -1;
        m_audio2UnitPackets.clear();
        m_audio2MuxWorkspace.clear();
    }

    if (m_videoPid != 0) {
        m_buf.push_back(table[videoDescPos]);
        // PID=0x0100
        m_buf.push_back(0xe1);
        m_buf.push_back(0x00);
        int esInfoLength = ((table[videoDescPos + 3] & 0x03) << 8) | table[videoDescPos + 4];
        m_buf.insert(m_buf.end(), table + videoDescPos + 3, table + videoDescPos + 5 + esInfoLength);
        if (m_pcrPid == m_videoPid) {
            m_buf[9] = 0xe1;
            m_buf[10] = 0x00;
        }
    }
    bool addAudio2 = m_audio2Pid != 0 || m_audio2Mode == 1 || m_audio2Mode == 3 || (m_audio2Mode != 2 && m_isAudio1DualMono);
    if (m_audio2Mode == 3 && m_audio1Pid != 0 && m_audio2Pid == 0) {
        // Copy stream type
        m_audio2StreamType = m_audio1StreamType;
    }

    if (m_audio1Pid != 0 || m_audio1Mode == 1) {
        m_buf.push_back(m_audio1StreamType);
        // PID=0x0110
        m_buf.push_back(0xe1);
        m_buf.push_back(0x10);
        if (m_audio1Pid != 0) {
            int esInfoLength = ((table[audio1DescPos + 3] & 0x03) << 8) | table[audio1DescPos + 4];
            if (audio1ComponentTagUnknown && addAudio2) {
                int esInfoNewLength = esInfoLength + 3;
                m_buf.push_back(0xf0 | static_cast<uint8_t>(esInfoNewLength >> 8));
                m_buf.push_back(static_cast<uint8_t>(esInfoNewLength));
                m_buf.push_back(0x52);
                m_buf.push_back(1);
                m_buf.push_back(maybeCProfile ? 0x83 : 0x10);
            }
            else {
                m_buf.push_back(0xf0 | static_cast<uint8_t>(esInfoLength >> 8));
                m_buf.push_back(static_cast<uint8_t>(esInfoLength));
            }
            m_buf.insert(m_buf.end(), table + audio1DescPos + 5, table + audio1DescPos + 5 + esInfoLength);
            if (m_pcrPid == m_audio1Pid) {
                m_buf[9] = 0xe1;
                m_buf[10] = 0x10;
            }
        }
        else {
            m_buf.push_back(0xf0);
            m_buf.push_back(3);
            m_buf.push_back(0x52);
            m_buf.push_back(1);
            m_buf.push_back(maybeCProfile ? 0x83 : 0x10);
        }
    }
    if (addAudio2) {
        m_buf.push_back(m_audio2StreamType);
        // PID=0x0111
        m_buf.push_back(0xe1);
        m_buf.push_back(0x11);
        if (m_audio2Pid != 0) {
            int esInfoLength = ((table[audio2DescPos + 3] & 0x03) << 8) | table[audio2DescPos + 4];
            m_buf.insert(m_buf.end(), table + audio2DescPos + 3, table + audio2DescPos + 5 + esInfoLength);
            if (m_pcrPid == m_audio2Pid) {
                m_buf[9] = 0xe1;
                m_buf[10] = 0x11;
            }
        }
        else {
            m_buf.push_back(0xf0);
            m_buf.push_back(3);
            m_buf.push_back(0x52);
            m_buf.push_back(1);
            m_buf.push_back(maybeCProfile ? 0x85 : 0x11);
        }
    }
    if (m_captionPid != 0 || m_captionMode == 1) {
        m_buf.push_back(PES_PRIVATE_DATA);
        // PID=0x0130
        m_buf.push_back(0xe1);
        m_buf.push_back(0x30);
        if (m_captionPid != 0) {
            int esInfoLength = ((table[captionDescPos + 3] & 0x03) << 8) | table[captionDescPos + 4];
            m_buf.insert(m_buf.end(), table + captionDescPos + 3, table + captionDescPos + 5 + esInfoLength);
            if (m_pcrPid == m_captionPid) {
                m_buf[9] = 0xe1;
                m_buf[10] = 0x30;
            }
        }
        else {
            m_buf.push_back(0xf0);
            m_buf.push_back(3 + (maybeCProfile ? 0 : 5));
            m_buf.push_back(0x52);
            m_buf.push_back(1);
            m_buf.push_back(maybeCProfile ? 0x87 : 0x30);
            if (!maybeCProfile) {
                // data_component_descriptor
                m_buf.push_back(0xfd);
                m_buf.push_back(3);
                m_buf.push_back(0x00);
                m_buf.push_back(0x08);
                m_buf.push_back(0x3d);
            }
        }
    }
    if (m_superimposePid != 0 || m_superimposeMode == 1) {
        m_buf.push_back(PES_PRIVATE_DATA);
        // PID=0x0138
        m_buf.push_back(0xe1);
        m_buf.push_back(0x38);
        if (m_superimposePid != 0) {
            int esInfoLength = ((table[superimposeDescPos + 3] & 0x03) << 8) | table[superimposeDescPos + 4];
            m_buf.insert(m_buf.end(), table + superimposeDescPos + 3, table + superimposeDescPos + 5 + esInfoLength);
            if (m_pcrPid == m_superimposePid) {
                m_buf[9] = 0xe1;
                m_buf[10] = 0x38;
            }
        }
        else {
            m_buf.push_back(0xf0);
            m_buf.push_back(3 + (maybeCProfile ? 0 : 5));
            // component_tag=0x38
            m_buf.push_back(0x52);
            m_buf.push_back(1);
            m_buf.push_back(maybeCProfile ? 0x88 : 0x38);
            if (!maybeCProfile) {
                // data_component_descriptor
                m_buf.push_back(0xfd);
                m_buf.push_back(3);
                m_buf.push_back(0x00);
                m_buf.push_back(0x08);
                m_buf.push_back(0x3c);
            }
        }
    }

    m_buf[2] = 0xb0 | static_cast<uint8_t>((m_buf.size() + 4 - 4) >> 8);
    m_buf[3] = static_cast<uint8_t>(m_buf.size() + 4 - 4);

    if (m_lastPmt.size() == m_buf.size() + 4 &&
        std::equal(m_buf.begin(), m_buf.end(), m_lastPmt.begin())) {
        // Copy CRC
        m_buf.insert(m_buf.end(), m_lastPmt.end() - 4, m_lastPmt.end());
    }
    else {
        // Increment version number
        m_buf[6] = 0xc1 | (((m_buf[6] >> 1) + 1) & 0x1f) << 1;
        uint32_t crc = calc_crc32(m_buf.data() + 1, static_cast<int>(m_buf.size() - 1));
        m_buf.push_back(crc >> 24);
        m_buf.push_back((crc >> 16) & 0xff);
        m_buf.push_back((crc >> 8) & 0xff);
        m_buf.push_back(crc & 0xff);
        m_lastPmt = m_buf;
    }

    // Create TS packets
    for (size_t i = 0; i < m_buf.size(); i += 184) {
        m_packets.push_back(0x47);
        // PMT_PID=0x01f0
        m_packets.push_back((i == 0 ? 0x40 : 0) | 0x01);
        m_packets.push_back(0xf0);
        m_pmtCounter = (m_pmtCounter + 1) & 0x0f;
        m_packets.push_back(0x10 | m_pmtCounter);
        m_packets.insert(m_packets.end(), m_buf.begin() + i, m_buf.begin() + std::min(i + 184, m_buf.size()));
        m_packets.resize(((m_packets.size() - 1) / 188 + 1) * 188, 0xff);
    }
}

void CServiceFilter::AddPcrAdaptation(const uint8_t *pcr)
{
    // Create TS packet
    m_packets.push_back(0x47);
    // PCR_PID=0x01ff
    m_packets.push_back(0x01);
    m_packets.push_back(0xff);
    m_packets.push_back(0x20);
    m_packets.push_back(183);
    m_packets.push_back(0x10);
    m_packets.insert(m_packets.end(), pcr, pcr + 4);
    // pcr_extension=0
    m_packets.push_back((pcr[4] & 0x80) | 0x7e);
    m_packets.push_back(0);
    m_packets.resize((m_packets.size() / 188 + 1) * 188, 0xff);
}

void CServiceFilter::ChangePidAndAddPacket(const uint8_t *packet, int pid, uint8_t counter)
{
    m_packets.push_back(0x47);
    m_packets.push_back((packet[1] & 0xe0) | static_cast<uint8_t>(pid >> 8));
    m_packets.push_back(static_cast<uint8_t>(pid));
    m_packets.push_back(counter > 0x0f ? packet[3] : ((packet[3] & 0xf0) | counter));
    m_packets.insert(m_packets.end(), packet + 4, packet + 188);
}

void CServiceFilter::AddCaptionManagementPesPacket(int64_t pts, uint8_t counter)
{
    static const uint8_t SYNCHRONOUS_PES_JPN_MANAGEMENT[20] = {
        0x80, 0xff, 0xf0, 0x80, 0x00, 0x00, 0x00, 0x0a,
        0x3f, 0x01, 0x1a, 0x6a, 0x70, 0x6e, 0x80, 0x00, 0x00, 0x00,
        0xe4, 0x6a
    };
    m_packets.push_back(0x47);
    // PID=0x0130
    m_packets.push_back(0x41);
    m_packets.push_back(0x30);
    m_packets.push_back(0x30 | counter);
    m_packets.push_back(188 - 5 - (6 + 28));
    m_packets.push_back(0x00);
    // stuffing
    m_packets.resize(m_packets.size() + 188 - 6 - (6 + 28), 0xff);
    // PES
    m_packets.push_back(0);
    m_packets.push_back(0);
    m_packets.push_back(1);
    m_packets.push_back(0xbd);
    m_packets.push_back(0);
    m_packets.push_back(28);
    m_packets.push_back(0x80);
    // has PTS
    m_packets.push_back(0x80);
    m_packets.push_back(5);
    m_packets.push_back(static_cast<uint8_t>(pts >> 29) | 0x21); // 3 bits
    m_packets.push_back(static_cast<uint8_t>(pts >> 22)); // 8 bits
    m_packets.push_back(static_cast<uint8_t>(pts >> 14) | 1); // 7 bits
    m_packets.push_back(static_cast<uint8_t>(pts >> 7)); // 8 bits
    m_packets.push_back(static_cast<uint8_t>(pts << 1) | 1); // 7 bits
    m_packets.insert(m_packets.end(), SYNCHRONOUS_PES_JPN_MANAGEMENT, SYNCHRONOUS_PES_JPN_MANAGEMENT + 20);
}

void CServiceFilter::AddSuperimposeManagementPesPacket(uint8_t counter)
{
    static const uint8_t ASYNCHRONOUS_PES_JPN_MANAGEMENT[20] = {
        0x81, 0xff, 0xf0, 0x00, 0x00, 0x00, 0x00, 0x0a,
        0x3f, 0x01, 0x12, 0x6a, 0x70, 0x6e, 0x80, 0x00, 0x00, 0x00,
        0xae, 0xa2
    };
    m_packets.push_back(0x47);
    // PID=0x0138
    m_packets.push_back(0x41);
    m_packets.push_back(0x38);
    m_packets.push_back(0x30 | counter);
    m_packets.push_back(188 - 5 - (6 + 20));
    m_packets.push_back(0x00);
    // stuffing
    m_packets.resize(m_packets.size() + 188 - 6 - (6 + 20), 0xff);
    // PES
    m_packets.push_back(0);
    m_packets.push_back(0);
    m_packets.push_back(1);
    m_packets.push_back(0xbf);
    m_packets.push_back(0);
    m_packets.push_back(20);
    m_packets.insert(m_packets.end(), ASYNCHRONOUS_PES_JPN_MANAGEMENT, ASYNCHRONOUS_PES_JPN_MANAGEMENT + 20);
}

void CServiceFilter::AddAudioPesPackets(uint8_t index, int64_t targetPts, int64_t &pts, uint8_t &counter)
{
    static const int ACCEPTABLE_PTS_DIFF_SEC = 10;

    int64_t ptsDiff = (0x200000000 + targetPts - pts) & 0x1ffffffff;
    if (pts < 0 || (90000 * ACCEPTABLE_PTS_DIFF_SEC < ptsDiff && ptsDiff < 0x200000000 - 90000 * ACCEPTABLE_PTS_DIFF_SEC)) {
        pts = targetPts;
    }
    for (;;) {
        int64_t nextPts = (pts + 90000 * 64 / 1000) & 0x1ffffffff;
        if (((0x200000000 + targetPts - nextPts) & 0x1ffffffff) > 900000) {
            break;
        }
        Add64MsecAudioPesPacket(index, pts, counter);
        pts = nextPts;
    }
}

void CServiceFilter::Add64MsecAudioPesPacket(uint8_t index, int64_t pts, uint8_t &counter)
{
    static const uint8_t ADTS_2CH_48KHZ_SILENT[13] = {
        0xff, 0xf1, 0x4c, 0x80, 0x01, 0xbf, 0xfc, 0x21, 0x10, 0x04, 0x60, 0x8c, 0x1c
    };
    m_packets.push_back(0x47);
    // PID=0x0110+index
    m_packets.push_back(0x41);
    m_packets.push_back(0x10 | index);
    counter = (counter + 1) & 0x0f;
    m_packets.push_back(0x30 | counter);
    m_packets.push_back(188 - 5 - (6 + 8 + 13 * 3));
    m_packets.push_back(0x40);
    // stuffing
    m_packets.resize(m_packets.size() + 188 - 6 - (6 + 8 + 13 * 3), 0xff);
    // PES
    m_packets.push_back(0);
    m_packets.push_back(0);
    m_packets.push_back(1);
    m_packets.push_back(0xc0 | index);
    m_packets.push_back(0);
    m_packets.push_back(8 + 13 * 3);
    // alignment by audio sync word
    m_packets.push_back(0x84);
    // has PTS
    m_packets.push_back(0x80);
    m_packets.push_back(5);
    m_packets.push_back(static_cast<uint8_t>(pts >> 29) | 0x21); // 3 bits
    m_packets.push_back(static_cast<uint8_t>(pts >> 22)); // 8 bits
    m_packets.push_back(static_cast<uint8_t>(pts >> 14) | 1); // 7 bits
    m_packets.push_back(static_cast<uint8_t>(pts >> 7)); // 8 bits
    m_packets.push_back(static_cast<uint8_t>(pts << 1) | 1); // 7 bits
    // 1024samples(1frame) / 48000hz * 3 = 0.064sec
    for (int i = 0; i < 3; ++i) {
        m_packets.insert(m_packets.end(), ADTS_2CH_48KHZ_SILENT, ADTS_2CH_48KHZ_SILENT + 13);
    }
}

int64_t CServiceFilter::GetAudioPresentationTimeStamp(int unitStart, const uint8_t *payload, int payloadSize)
{
    if (unitStart && payloadSize >= 6 && payload[0] == 0 && payload[1] == 0 && payload[2] == 1) {
        int streamID = payload[3];
        size_t pesPacketLength = (payload[4] << 8) | payload[5];
        // audio stream
        if ((streamID & 0xe0) == 0xc0 && pesPacketLength >= 3 && payloadSize >= 9) {
            int ptsDtsFlags = payload[7] >> 6;
            if (ptsDtsFlags >= 2 && pesPacketLength >= 8 && payloadSize >= 14) {
                return (payload[13] >> 1) |
                       (payload[12] << 7) |
                       ((payload[11] & 0xfe) << 14) |
                       (payload[10] << 22) |
                       (static_cast<int64_t>(payload[9] & 0x0e) << 29);
            }
        }
    }
    return -1;
}

bool CServiceFilter::AccumulatePesPackets(std::vector<uint8_t> &unitPackets, const uint8_t *packet, int unitStart)
{
    if (unitStart) {
        unitPackets.assign(packet, packet + 188);
    }
    // Cancel accumulations that are not possible for a regular (with a valid length field) PES
    else if (!unitPackets.empty() && unitPackets.size() < 0x20000) {
        unitPackets.insert(unitPackets.end(), packet, packet + 188);
    }

    // Check if PES has accumulated
    int lastCounter = -1;
    int entireSize = 0;
    uint8_t head[6];
    for (size_t i = 0; i + 188 <= unitPackets.size(); i += 188) {
        const uint8_t *packet_ = unitPackets.data() + i;
        int counter = extract_ts_header_counter(packet_);
        if (lastCounter >= 0 && ((lastCounter + 1) & 0x0f) != counter) {
            unitPackets.clear();
            return false;
        }
        lastCounter = counter;

        int payloadSize = get_ts_payload_size(packet_);
        const uint8_t *payload = packet_ + 188 - payloadSize;
        for (int j = 0; j < payloadSize && entireSize + j < 6; ++j) {
            head[entireSize + j] = payload[j];
        }
        entireSize += payloadSize;
        if (entireSize >= 6) {
            if (head[0] != 0 || head[1] != 0 || head[2] != 1) {
                unitPackets.clear();
                return false;
            }
            int pesPacketLength = (head[4] << 8) | head[5];
            if (entireSize >= 6 + pesPacketLength) {
                return true;
            }
        }
    }
    return false;
}

void CServiceFilter::ConcatenatePayload(std::vector<uint8_t> &dest, const std::vector<uint8_t> &unitPackets, bool &pcrFlag, uint8_t (&pcr)[6])
{
    dest.clear();
    pcrFlag = false;
    for (size_t i = 0; i + 188 <= unitPackets.size(); i += 188) {
        const uint8_t *packet = unitPackets.data() + i;
        int adaptation = extract_ts_header_adaptation(packet);
        if (adaptation & 2) {
            int adaptationLength = packet[4];
            if (adaptationLength >= 7 && !!(packet[5] & 0x10)) {
                pcrFlag = true;
                std::copy(packet + 6, packet + 12, pcr);
            }
        }
        int payloadSize = get_ts_payload_size(packet);
        const uint8_t *payload = packet + 188 - payloadSize;
        dest.insert(dest.end(), payload, payload + payloadSize);
    }
}

void CServiceFilter::AddAudioPesPackets(const std::vector<uint8_t> &pes, int pid, uint8_t &counter, int64_t &ptsPcrDiff, const uint8_t *pcr)
{
    for (size_t i = 0; i < pes.size(); ) {
        m_packets.push_back(0x47);
        m_packets.push_back((i == 0 ? 0x40 : 0) | static_cast<uint8_t>(pid >> 8));
        m_packets.push_back(static_cast<uint8_t>(pid));
        counter = (counter + 1) & 0x0f;
        size_t len = std::min<size_t>(184, pes.size() - i);
        if (pcr && i + len >= pes.size() && len > 176) {
            // Reduce payload in order to insert PCR
            len = 176;
        }
        m_packets.push_back((len < 184 ? 0x30 : 0x10) | counter);
        if (len < 184) {
            m_packets.push_back(static_cast<uint8_t>(183 - len));
            if (len < 183) {
                if (pcr && len <= 176) {
                    // Insert PCR
                    m_packets.push_back(0x10);
                    m_packets.insert(m_packets.end(), pcr, pcr + 6);
                    m_packets.insert(m_packets.end(), 176 - len, 0xff);
                    pcr = nullptr;
                }
                else {
                    m_packets.push_back(0x00);
                    m_packets.insert(m_packets.end(), 182 - len, 0xff);
                }
            }
        }
        int64_t pts = GetAudioPresentationTimeStamp(i == 0, pes.data() + i, static_cast<int>(len));
        if (pts >= 0 && m_pcr >= 0) {
            ptsPcrDiff = 0x200000000 + pts - m_pcr;
        }
        m_packets.insert(m_packets.end(), pes.begin() + i, pes.begin() + i + len);
        i += len;
    }
}

bool CServiceFilter::TransmuxMonoToStereo(const std::vector<uint8_t> &unitPackets, std::vector<uint8_t> &workspace,
                                          int pid, uint8_t &counter, int64_t &ptsPcrDiff)
{
    bool pcrFlag;
    uint8_t pcr[6];
    ConcatenatePayload(m_buf, unitPackets, pcrFlag, pcr);

    if (m_buf.size() >= 6 && m_buf[0] == 0 && m_buf[1] == 0 && m_buf[2] == 1) {
        int streamID = m_buf[3];
        size_t pesPacketLength = (m_buf[4] << 8) | m_buf[5];
        // audio stream
        if ((streamID & 0xe0) == 0xc0 && m_buf.size() >= 6 + pesPacketLength && pesPacketLength >= 3) {
            // PES has been accumulated
            size_t pesPayloadPos = 9 + m_buf[8];
            if (pesPayloadPos < 6 + pesPacketLength) {
                m_buf.resize(6 + pesPacketLength);
                if (Aac::TransmuxMonoToStereo(m_destLeftBuf, workspace, m_buf.data() + pesPayloadPos, m_buf.size() - pesPayloadPos) &&
                    !m_destLeftBuf.empty()) {

                    // Stereo
                    m_buf.resize(pesPayloadPos);
                    m_buf.insert(m_buf.end(), m_destLeftBuf.begin(), m_destLeftBuf.end());

                    // Set length fields
                    size_t pesLen = m_buf.size() - 6;
                    m_buf[4] = static_cast<uint8_t>(pesLen >> 8);
                    m_buf[5] = static_cast<uint8_t>(pesLen);
                    AddAudioPesPackets(m_buf, pid, counter, ptsPcrDiff, pcrFlag ? pcr : nullptr);
                    return true;
                }
            }
        }
    }
    return false;
}

bool CServiceFilter::TransmuxDualMono(const std::vector<uint8_t> &unitPackets)
{
    bool pcrFlag;
    uint8_t pcr[6];
    ConcatenatePayload(m_buf, unitPackets, pcrFlag, pcr);

    if (m_buf.size() >= 6 && m_buf[0] == 0 && m_buf[1] == 0 && m_buf[2] == 1) {
        int streamID = m_buf[3];
        size_t pesPacketLength = (m_buf[4] << 8) | m_buf[5];
        // audio stream
        if ((streamID & 0xe0) == 0xc0 && m_buf.size() >= 6 + pesPacketLength && pesPacketLength >= 3) {
            // PES has been accumulated
            size_t pesPayloadPos = 9 + m_buf[8];
            if (pesPayloadPos < 6 + pesPacketLength) {
                m_buf.resize(6 + pesPacketLength);
                if (Aac::TransmuxDualMono(m_destLeftBuf, m_destRightBuf, m_audio1MuxDualMonoWorkspace,
                                          m_audio1MuxToStereo, m_audio2MuxToStereo,
                                          m_buf.data() + pesPayloadPos, m_buf.size() - pesPayloadPos) &&
                    !m_destLeftBuf.empty() &&
                    !m_destRightBuf.empty()) {

                    // Dual mono left
                    m_buf.resize(pesPayloadPos);
                    m_buf.insert(m_buf.end(), m_destLeftBuf.begin(), m_destLeftBuf.end());

                    // Set stream ID
                    m_buf[3] = 0xc0;
                    // Set length fields
                    size_t pesLen = m_buf.size() - 6;
                    m_buf[4] = static_cast<uint8_t>(pesLen >> 8);
                    m_buf[5] = static_cast<uint8_t>(pesLen);
                    AddAudioPesPackets(m_buf, 0x0110, m_audio1PesCounter, m_audio1PtsPcrDiff, pcrFlag ? pcr : nullptr);

                    if (m_audio2Pid == 0 && m_audio2Mode != 2) {
                        // Dual mono right
                        m_buf.resize(pesPayloadPos);
                        m_buf.insert(m_buf.end(), m_destRightBuf.begin(), m_destRightBuf.end());

                        // Set stream ID
                        m_buf[3] = 0xc1;
                        // Set length fields
                        pesLen = m_buf.size() - 6;
                        m_buf[4] = static_cast<uint8_t>(pesLen >> 8);
                        m_buf[5] = static_cast<uint8_t>(pesLen);
                        AddAudioPesPackets(m_buf, 0x0111, m_audio2PesCounter, m_audio2PtsPcrDiff, nullptr);
                    }
                    return true;
                }
            }
        }
    }
    return false;
}
