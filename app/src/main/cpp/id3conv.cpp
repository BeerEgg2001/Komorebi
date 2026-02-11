#include "id3conv.hpp"
#include <algorithm>

CID3Converter::CID3Converter()
    : m_enabled(false)
    , m_treatUnknownPrivateDataAsSuperimpose(false)
    , m_insertInappropriate5BytesIntoPesPayload(false)
    , m_forceMonotonousPts(false)
    , m_lastID3Pts(-1)
    , m_firstPmtPid(0)
    , m_captionPid(0)
    , m_superimposePid(0)
    , m_pcrPid(0)
    , m_pcr(-1)
    , m_id3Pid(0)
    , m_id3Counter(0)
    , m_pmtCounter(0)
{
    static const PAT zeroPat = {};
    m_pat = zeroPat;
    m_firstPmtPsi = zeroPat.psi;
}

void CID3Converter::SetOption(int flags)
{
    m_enabled = !!(flags & 1);
    m_treatUnknownPrivateDataAsSuperimpose = !!(flags & 2);
    m_insertInappropriate5BytesIntoPesPayload = !!(flags & 4);
    m_forceMonotonousPts = !!(flags & 8);
}

void CID3Converter::AddPacket(const uint8_t *packet)
{
    if (!m_enabled) {
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
        auto itFirstPmt = std::find_if(m_pat.pmt.begin(), m_pat.pmt.end(), [](const PMT_REF &pmt) { return pmt.program_number != 0; });
        if (m_firstPmtPid != 0 && (itFirstPmt == m_pat.pmt.end() || itFirstPmt->pmt_pid != m_firstPmtPid)) {
            m_firstPmtPid = 0;
            static const PSI zeroPsi = {};
            m_firstPmtPsi = zeroPsi;
        }
        if (itFirstPmt != m_pat.pmt.end()) {
            m_firstPmtPid = itFirstPmt->pmt_pid;
        }
        m_packets.insert(m_packets.end(), packet, packet + 188);
    }
    else if (pid == m_firstPmtPid) {
        int done;
        do {
            done = extract_psi(&m_firstPmtPsi, payload, payloadSize, unitStart, counter);
            if (m_firstPmtPsi.version_number && m_firstPmtPsi.table_id == 2) {
                AddPmt(pid, m_firstPmtPsi);
            }
        }
        while (!done);
    }
    else if (pid == m_pcrPid) {
        if (adaptation & 2) {
            int adaptationLength = packet[4];
            if (adaptationLength >= 6 && !!(packet[5] & 0x10)) {
                m_pcr = (packet[10] >> 7) |
                        (packet[9] << 1) |
                        (packet[8] << 9) |
                        (packet[7] << 17) |
                        (static_cast<int64_t>(packet[6]) << 25);
            }
        }
        m_packets.insert(m_packets.end(), packet, packet + 188);
    }
    else if (m_removePidSet.count(pid)) {
        if (pid == m_captionPid || pid == m_superimposePid) {
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
                    CheckPrivateDataPes(pes);
                    pes.clear();
                }
            }
        }
    }
    else {
        m_packets.insert(m_packets.end(), packet, packet + 188);
    }
}

void CID3Converter::AddPmt(int pid, const PSI &psi)
{
    const uint8_t PES_PRIVATE_DATA = 0x06;

    if (psi.section_length < 9) {
        return;
    }
    const uint8_t *table = psi.data;
    int serviceID = (table[3] << 8) | table[4];
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
    m_buf.clear();
    m_buf.push_back(0);
    m_buf.insert(m_buf.end(), table, table + pos);

    int captionPids[2] = {};
    int superimposePids[2] = {};
    int minRemovePid = 0x2000;
    m_removePidSet.clear();
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
            // ARIB caption/superimpose
            if (streamType == PES_PRIVATE_DATA &&
                (componentTag == 0x30 || componentTag == 0x87 ||
                 componentTag == 0x38 || componentTag == 0x88 ||
                 (componentTag == 0xff && m_treatUnknownPrivateDataAsSuperimpose))) {
                if (componentTag == 0x30 || componentTag == 0x87) {
                    captionPids[componentTag != 0x30] = esPid;
                }
                else {
                    superimposePids[componentTag != 0x38] = esPid;
                }
                // Remove from PMT
                m_removePidSet.emplace(esPid);
                minRemovePid = std::min(esPid, minRemovePid);
            }
            else {
                // Remain
                m_buf.insert(m_buf.end(), table + pos, table + pos + 5 + esInfoLength);
                if (m_id3Pid == esPid) {
                    // Reassign PID, rare case.
                    m_id3Pid = 0;
                }
            }
        }
        pos += 5 + esInfoLength;
    }

    // Prioritize "A-Profile"
    if (m_captionPid != (captionPids[0] ? captionPids[0] : captionPids[1])) {
        m_captionPid = captionPids[0] ? captionPids[0] : captionPids[1];
        m_captionPes.second.clear();
    }
    if (m_superimposePid != (superimposePids[0] ? superimposePids[0] : superimposePids[1])) {
        m_superimposePid = superimposePids[0] ? superimposePids[0] : superimposePids[1];
        m_superimposePes.second.clear();
    }

    if (m_id3Pid == 0 && minRemovePid < 0x2000) {
        m_id3Pid = minRemovePid;
    }
    if (m_id3Pid != 0) {
        // Add ID3 Timed Metadata
        static const uint8_t metadataPointerDesc[] = {
            0x26, 15, 0xff, 0xff, 'I', 'D', '3', ' ', 0xff, 'I', 'D', '3', ' ', 0x00, 0x1f,
            static_cast<uint8_t>(serviceID >> 8),
            static_cast<uint8_t>(serviceID)
        };
        static const uint8_t metadataDesc[] = {
            0x26, 13, 0xff, 0xff, 'I', 'D', '3', ' ', 0xff, 'I', 'D', '3', ' ', 0xff, 0x0f
        };
        // Add to 1st descriptor loop
        programInfoLength += sizeof(metadataPointerDesc);
        if (programInfoLength <= 1023) {
            m_buf[11] = static_cast<uint8_t>(0xf0 | (programInfoLength >> 8));
            m_buf[12] = static_cast<uint8_t>(programInfoLength);
            m_buf.insert(m_buf.begin() + 13, metadataPointerDesc, metadataPointerDesc + sizeof(metadataPointerDesc));
        }
        // Add to 2nd descriptor loop
        m_buf.push_back(0x15);
        m_buf.push_back(static_cast<uint8_t>(0xe0 | (m_id3Pid >> 8)));
        m_buf.push_back(static_cast<uint8_t>(m_id3Pid));
        m_buf.push_back(0xf0);
        m_buf.push_back(static_cast<uint8_t>(sizeof(metadataDesc)));
        m_buf.insert(m_buf.end(), metadataDesc, metadataDesc + sizeof(metadataDesc));
    }
    m_buf[2] = static_cast<uint8_t>((m_buf[2] & 0xf0) | ((m_buf.size() + 4 - 4) >> 8));
    m_buf[3] = static_cast<uint8_t>(m_buf.size() + 4 - 4);
    uint32_t crc = calc_crc32(m_buf.data() + 1, static_cast<int>(m_buf.size() - 1));
    m_buf.push_back(crc >> 24);
    m_buf.push_back((crc >> 16) & 0xff);
    m_buf.push_back((crc >> 8) & 0xff);
    m_buf.push_back(crc & 0xff);

    // Create TS packets
    for (size_t i = 0; i < m_buf.size(); i += 184) {
        m_packets.push_back(0x47);
        m_packets.push_back(static_cast<uint8_t>((i == 0 ? 0x40 : 0) | ((pid >> 8) & 0x1f)));
        m_packets.push_back(static_cast<uint8_t>(pid));
        m_pmtCounter = (m_pmtCounter + 1) & 0x0f;
        m_packets.push_back(0x10 | m_pmtCounter);
        m_packets.insert(m_packets.end(), m_buf.begin() + i, m_buf.begin() + std::min(i + 184, m_buf.size()));
        m_packets.resize(((m_packets.size() - 1) / 188 + 1) * 188, 0xff);
    }
}

void CID3Converter::CheckPrivateDataPes(const std::vector<uint8_t> &pes)
{
    const uint8_t PRIVATE_STREAM_1 = 0xbd;
    const uint8_t PRIVATE_STREAM_2 = 0xbf;
    const int ACCEPTABLE_PTS_DIFF_SEC = 10;

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
    if (m_forceMonotonousPts) {
        if (m_lastID3Pts >= 0 &&
            ((0x200000000 + m_lastID3Pts - pts) & 0x1ffffffff) < 90000 * ACCEPTABLE_PTS_DIFF_SEC) {
            // Prevent PTS goes back
            pts = m_lastID3Pts;
        }
        m_lastID3Pts = pts;
    }

    // ID3 Timed Metadata
    m_buf.clear();
    m_buf.push_back(0);
    m_buf.push_back(0);
    m_buf.push_back(1);
    m_buf.push_back(PRIVATE_STREAM_1);
    m_buf.resize(m_buf.size() + 2); // PES length
    m_buf.push_back(0x80);
    m_buf.push_back(0x80);
    m_buf.push_back(5);
    m_buf.push_back(static_cast<uint8_t>(pts >> 29) | 0x21); // 3 bits
    m_buf.push_back(static_cast<uint8_t>(pts >> 22)); // 8 bits
    m_buf.push_back(static_cast<uint8_t>(pts >> 14) | 1); // 7 bits
    m_buf.push_back(static_cast<uint8_t>(pts >> 7)); // 8 bits
    m_buf.push_back(static_cast<uint8_t>(pts << 1) | 1); // 7 bits
    if (m_insertInappropriate5BytesIntoPesPayload) {
        m_buf.insert(m_buf.end(), 5, 0);
    }
    m_buf.push_back('I');
    m_buf.push_back('D');
    m_buf.push_back('3');
    m_buf.push_back(4);
    m_buf.push_back(0);
    m_buf.push_back(0x00);
    m_buf.resize(m_buf.size() + 4); // ID3 frame length
    size_t privFramePos = m_buf.size();
    m_buf.push_back('P');
    m_buf.push_back('R');
    m_buf.push_back('I');
    m_buf.push_back('V');
    m_buf.resize(m_buf.size() + 4); // PRIV frame length
    m_buf.push_back(0);
    m_buf.push_back(0);
    size_t privPayloadPos = m_buf.size();
    m_buf.push_back('a');
    m_buf.push_back('r');
    m_buf.push_back('i');
    m_buf.push_back('b');
    m_buf.push_back('b');
    m_buf.push_back('2');
    m_buf.push_back('4');
    m_buf.push_back('.');
    m_buf.push_back('j');
    m_buf.push_back('s');
    m_buf.push_back(0);
    m_buf.insert(m_buf.end(), pes.begin() + payloadPos, pes.end());

    // Set length fields
    size_t privLen = m_buf.size() - privPayloadPos;
    m_buf[privPayloadPos - 6] = (privLen >> 21) & 0x7f;
    m_buf[privPayloadPos - 5] = (privLen >> 14) & 0x7f;
    m_buf[privPayloadPos - 4] = (privLen >> 7) & 0x7f;
    m_buf[privPayloadPos - 3] = privLen & 0x7f;
    size_t id3Len = m_buf.size() - privFramePos;
    m_buf[privFramePos - 4] = (id3Len >> 21) & 0x7f;
    m_buf[privFramePos - 3] = (id3Len >> 14) & 0x7f;
    m_buf[privFramePos - 2] = (id3Len >> 7) & 0x7f;
    m_buf[privFramePos - 1] = id3Len & 0x7f;
    size_t pesLen = m_buf.size() - 6;
    m_buf[4] = static_cast<uint8_t>(pesLen >> 8);
    m_buf[5] = static_cast<uint8_t>(pesLen);

    // Create TS packets
    for (size_t i = 0; i < m_buf.size(); i += 184) {
        m_packets.push_back(0x47);
        m_packets.push_back(static_cast<uint8_t>((i == 0 ? 0x40 : 0) | ((m_id3Pid >> 8) & 0x1f)));
        m_packets.push_back(static_cast<uint8_t>(m_id3Pid));
        m_id3Counter = (m_id3Counter + 1) & 0x0f;
        size_t len = std::min<size_t>(184, m_buf.size() - i);
        m_packets.push_back((len < 184 ? 0x30 : 0x10) | m_id3Counter);
        if (len < 184) {
            m_packets.push_back(static_cast<uint8_t>(183 - len));
            if (len < 183) {
                m_packets.push_back(0x00);
                m_packets.insert(m_packets.end(), 182 - len, 0xff);
            }
        }
        m_packets.insert(m_packets.end(), m_buf.begin() + i, m_buf.begin() + i + len);
    }
}
