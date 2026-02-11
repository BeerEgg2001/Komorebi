#include "util.hpp"
#include <algorithm>

uint16_t calc_crc16_ccitt(const uint8_t *data, int data_size, uint16_t crc)
{
    for (int i = 0; i < data_size; ++i) {
        uint16_t c = ((crc >> 8) ^ data[i]) << 8;
        for (int j = 0; j < 8; ++j) {
            c = (c << 1) ^ (c & 0x8000 ? 0x1021 : 0);
        }
        crc = (crc << 8) ^ c;
    }
    return crc;
}

uint32_t calc_crc32(const uint8_t *data, int data_size, uint32_t crc)
{
    for (int i = 0; i < data_size; ++i) {
        uint32_t c = ((crc >> 24) ^ data[i]) << 24;
        for (int j = 0; j < 8; ++j) {
            c = (c << 1) ^ (c & 0x80000000 ? 0x04c11db7 : 0);
        }
        crc = (crc << 8) ^ c;
    }
    return crc;
}

int extract_psi(PSI *psi, const uint8_t *payload, int payload_size, int unit_start, int counter)
{
    int copy_pos = 0;
    int copy_size = payload_size;
    int done = 1;
    if (unit_start) {
        if (payload_size < 1) {
            psi->continuity_counter = psi->data_count = psi->version_number = 0;
            return 1;
        }
        int pointer = payload[0];
        psi->continuity_counter = (psi->continuity_counter + 1) & 0x2f;
        if (pointer > 0 && psi->continuity_counter == (0x20 | counter)) {
            copy_pos = 1;
            copy_size = pointer;
            // Call the function again
            done = 0;
        }
        else {
            psi->continuity_counter = 0x20 | counter;
            psi->data_count = psi->version_number = 0;
            copy_pos = 1 + pointer;
            copy_size -= copy_pos;
        }
    }
    else {
        psi->continuity_counter = (psi->continuity_counter + 1) & 0x2f;
        if (psi->continuity_counter != (0x20 | counter)) {
            psi->continuity_counter = psi->data_count = psi->version_number = 0;
            return 1;
        }
    }
    if (copy_size > 0 && copy_pos + copy_size <= payload_size) {
        copy_size = std::min(copy_size, static_cast<int>(sizeof(psi->data)) - psi->data_count);
        std::copy(payload + copy_pos, payload + copy_pos + copy_size, psi->data + psi->data_count);
        psi->data_count += copy_size;
    }

    // If psi->version_number != 0, these fields are valid.
    if (psi->data_count >= 3) {
        int section_length = ((psi->data[1] & 0x03) << 8) | psi->data[2];
        if (psi->data_count >= 3 + section_length &&
            calc_crc32(psi->data, 3 + section_length) == 0 &&
            section_length >= 3)
        {
            psi->table_id = psi->data[0];
            psi->section_length = section_length;
            psi->version_number = 0x20 | ((psi->data[5] >> 1) & 0x1f);
            psi->current_next_indicator = psi->data[5] & 0x01;
        }
    }
    return done;
}

void extract_pat(PAT *pat, const uint8_t *payload, int payload_size, int unit_start, int counter)
{
    int done;
    do {
        done = extract_psi(&pat->psi, payload, payload_size, unit_start, counter);
        if (pat->psi.version_number &&
            pat->psi.current_next_indicator &&
            pat->psi.table_id == 0 &&
            pat->psi.section_length >= 5)
        {
            // Update PAT
            const uint8_t *table = pat->psi.data;
            pat->transport_stream_id = (table[3] << 8) | table[4];
            pat->version_number = pat->psi.version_number;

            // Update PMT list
            pat->pmt.clear();
            int pos = 3 + 5;
            while (pos + 3 < 3 + pat->psi.section_length - 4/*CRC32*/) {
                // Including NIT (program_number == 0)
                pat->pmt.resize(pat->pmt.size() + 1);
                pat->pmt.back().pmt_pid = ((table[pos + 2] & 0x1f) << 8) | table[pos + 3];
                pat->pmt.back().program_number = (table[pos] << 8) | (table[pos + 1]);
                pos += 4;
            }
        }
    }
    while (!done);
}

int get_ts_payload_size(const uint8_t *packet)
{
    int adaptation = extract_ts_header_adaptation(packet);
    if (adaptation & 1) {
        if (adaptation == 3) {
            int adaptation_length = packet[4];
            if (adaptation_length <= 183) {
                return 183 - adaptation_length;
            }
        }
        else {
            return 184;
        }
    }
    return 0;
}

int resync_ts(const uint8_t *data, int data_size, int *unit_size)
{
    if (*unit_size == 188 || *unit_size == 192 || *unit_size == 204) {
        for (int offset = 0; offset < data_size && offset < *unit_size; ++offset) {
            int i = offset;
            for (; i < data_size; i += *unit_size) {
                if (data[i] != 0x47) {
                    break;
                }
            }
            if (i >= data_size) {
                return offset;
            }
        }
    }
    else {
        // Unknown unit size
        for (int i = 0; i < 3; ++i) {
            *unit_size = i == 0 ? 188 : i == 1 ? 192 : 204;
            int offset = resync_ts(data, data_size, unit_size);
            if (offset < data_size) {
                return offset;
            }
        }
        *unit_size = 0;
    }
    // Failed
    return data_size;
}
