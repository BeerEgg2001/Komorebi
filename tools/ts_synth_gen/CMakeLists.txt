# ts_synth_gen: 合成VBR TSファイル生成CLI(テスト用)。
#
# Android NDKは不要。macOS/Linux/WindowsいずれでもCMake+C++17コンパイラがあればビルドできる。
# tools/ts_pmt_monitor と同じ方針で、app/src/main/cpp をコピーせず直接参照する。

cmake_minimum_required(VERSION 3.15)
project(ts_synth_gen CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

set(KOMOREBI_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp")

add_library(ts_synth_gen_core STATIC
    synth.cpp
    "${KOMOREBI_CPP_DIR}/util.cpp"
)
target_include_directories(ts_synth_gen_core PUBLIC
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${KOMOREBI_CPP_DIR}"
)

add_executable(ts_synth_gen main.cpp)
target_link_libraries(ts_synth_gen PRIVATE ts_synth_gen_core)
