# ts_index_builder: PTS/PCRベース シーク索引の生成・検証CLI(テスト基盤 Phase 0)。
#
# Android NDKは不要。macOS/Linux/WindowsいずれでもCMake+C++17コンパイラがあればビルドできる。
# tools/ts_pmt_monitor と同じ方針で、app/src/main/cpp / tools/ts_synth_gen をコピーせず直接参照する。

cmake_minimum_required(VERSION 3.15)
project(ts_index_builder CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

set(KOMOREBI_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp")
set(TS_SYNTH_GEN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../ts_synth_gen")

add_executable(ts_index_builder
    main.cpp
    tsindex.cpp
    indexio.cpp
    selftest.cpp
    "${TS_SYNTH_GEN_DIR}/synth.cpp"
    "${KOMOREBI_CPP_DIR}/util.cpp"
)

target_include_directories(ts_index_builder PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${TS_SYNTH_GEN_DIR}"
    "${KOMOREBI_CPP_DIR}"
)
