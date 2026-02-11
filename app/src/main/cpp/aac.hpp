#ifndef INCLUDE_AAC_HPP
#define INCLUDE_AAC_HPP

#include <stddef.h>
#include <stdint.h>
#include <vector>

namespace Aac
{
bool TransmuxDualMono(std::vector<uint8_t> &destLeft, std::vector<uint8_t> &destRight, std::vector<uint8_t> &workspace,
                      bool muxLeftToStereo, bool muxRightToStereo, const uint8_t *payload, size_t lenBytes);
bool TransmuxMonoToStereo(std::vector<uint8_t> &dest, std::vector<uint8_t> &workspace, const uint8_t *payload, size_t lenBytes);
}

#endif
