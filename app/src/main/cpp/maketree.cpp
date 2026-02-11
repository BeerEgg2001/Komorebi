#include <stdint.h>
#include <stdio.h>
#include <stdexcept>
#include <utility>
#include <vector>

namespace
{
// ISO/IEC 13818-7 Huffman Codebook Tables

const uint32_t SCALEFACTOR_TABLE[][2]
{
    {0x3ffe8, 18},
    ...
};

const uint32_t SPECTRUM1_TABLE[][2]
{
    {0x7f8, 11},
    ...
};

const uint32_t SPECTRUM2_TABLE[][2]
{
   ...
};

const uint32_t SPECTRUM3_TABLE[][2]
{
   ...
};

const uint32_t SPECTRUM4_TABLE[][2]
{
   ...
};

const uint32_t SPECTRUM5_TABLE[][2]
{
   ...
};

const uint32_t SPECTRUM6_TABLE[][2]
{
   ...
};

const uint32_t SPECTRUM7_TABLE[][2]
{
   ...
};

const uint32_t SPECTRUM8_TABLE[][2]
{
   ...
};

const uint32_t SPECTRUM9_TABLE[][2]
{
   ...
};

const uint32_t SPECTRUM10_TABLE[][2]
{
   ...
};

const uint32_t SPECTRUM11_TABLE[][2]
{
   ...
};

const uint32_t (*const CODEBOOK_TABLES[])[2]
{
    SCALEFACTOR_TABLE,
    SPECTRUM1_TABLE,
    SPECTRUM2_TABLE,
    SPECTRUM3_TABLE,
    SPECTRUM4_TABLE,
    SPECTRUM5_TABLE,
    SPECTRUM6_TABLE,
    SPECTRUM7_TABLE,
    SPECTRUM8_TABLE,
    SPECTRUM9_TABLE,
    SPECTRUM10_TABLE,
    SPECTRUM11_TABLE,
};

const int CODEBOOK_TABLE_LENS[]
{
    sizeof(SCALEFACTOR_TABLE) / sizeof(SCALEFACTOR_TABLE[0]),
    sizeof(SPECTRUM1_TABLE) / sizeof(SPECTRUM1_TABLE[0]),
    sizeof(SPECTRUM2_TABLE) / sizeof(SPECTRUM2_TABLE[0]),
    sizeof(SPECTRUM3_TABLE) / sizeof(SPECTRUM3_TABLE[0]),
    sizeof(SPECTRUM4_TABLE) / sizeof(SPECTRUM4_TABLE[0]),
    sizeof(SPECTRUM5_TABLE) / sizeof(SPECTRUM5_TABLE[0]),
    sizeof(SPECTRUM6_TABLE) / sizeof(SPECTRUM6_TABLE[0]),
    sizeof(SPECTRUM7_TABLE) / sizeof(SPECTRUM7_TABLE[0]),
    sizeof(SPECTRUM8_TABLE) / sizeof(SPECTRUM8_TABLE[0]),
    sizeof(SPECTRUM9_TABLE) / sizeof(SPECTRUM9_TABLE[0]),
    sizeof(SPECTRUM10_TABLE) / sizeof(SPECTRUM10_TABLE[0]),
    sizeof(SPECTRUM11_TABLE) / sizeof(SPECTRUM11_TABLE[0]),
};

const int CODEBOOK_NUM = 12;

void AppendCodeword(std::vector<std::pair<int, int>> &tree, int codeIndex, uint32_t codeword, uint32_t len)
{
    int current = 0;
    while (len > 0) {
        // 0: unset, <500: node, else: leaf
        int &next = (codeword >> (--len)) & 1 ? tree[current].second : tree[current].first;
        if (next == 0) {
            if (len == 0) {
                next = 500 + codeIndex;
            }
            else {
                current = next = static_cast<int>(tree.size());
                tree.emplace_back(0, 0);
            }
        }
        else if (next >= 500) {
            throw std::runtime_error("leaf overwritten");
        }
        else {
            current = next;
        }
    }
}
}

int main()
{
    int maxLen = 0;
    for (int i = 0; i < CODEBOOK_NUM; ++i) {
        std::vector<std::pair<int, int>> tree;
        tree.emplace_back(0, 0);
        for (int j = 0; j < CODEBOOK_TABLE_LENS[i]; ++j) {
            AppendCodeword(tree, j, CODEBOOK_TABLES[i][j][0], CODEBOOK_TABLES[i][j][1]);
            if (maxLen < CODEBOOK_TABLES[i][j][1]) {
                maxLen = CODEBOOK_TABLES[i][j][1];
            }
        }
        printf("const uint16_t SPECTRUM%d_TREE[][2] =\n{", i);
        for (size_t j = 0; j < tree.size(); ++j) {
            if (tree[j].first == 0 || tree[j].second == 0) {
                throw std::runtime_error("tree has unset node");
            }
            printf("%s{%3d, %3d},", j % 5 ? " " : "\n    ", tree[j].first, tree[j].second);
        }
        printf("\n};\n\n");
    }
    printf("const size_t MAX_CODEWORD_LEN = %d;\n", maxLen);
    return 0;
}
