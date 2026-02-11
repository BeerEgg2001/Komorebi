#ifndef INCLUDE_HUFFMAN_HPP
#define INCLUDE_HUFFMAN_HPP

#include <stddef.h>
#include <stdint.h>

namespace Huffman
{
const size_t MAX_CODEWORD_LEN = 19;
int DecodeScalefactorBits(const uint8_t *data, size_t &pos);
void DecodeSpectrumQuadBits(int codebook, const uint8_t *data, size_t &pos, int &unsigned_, int &w, int &x, int &y, int &z);
void DecodeSpectrumPairBits(int codebook, const uint8_t *data, size_t &pos, int &unsigned_, int &y, int &z);
}

#endif
