# ts_pmt_monitor: 録画TSファイルのPMT(音声構成等)変化を調査する診断ツール。
#
# Android NDKは不要。macOS/Linux/Windowsいずれでも、CMakeと一般的なC++コンパイラ
# (clang/g++/MSVC)があればビルドできる。
#
# アプリ本体(app/src/main/cpp)のservicefilter.cpp/util.cppをそのまま参照しており、
# 実際のアプリと同じPID解決ロジックで診断する。

cmake_minimum_required(VERSION 3.15)
project(ts_pmt_monitor CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# app/src/main/cpp を参照(このツール専用のコピーは持たない)
set(KOMOREBI_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp")

add_executable(ts_pmt_monitor
    main.cpp
    "${KOMOREBI_CPP_DIR}/util.cpp"
    "${KOMOREBI_CPP_DIR}/servicefilter.cpp"
    "${KOMOREBI_CPP_DIR}/aac.cpp"
    "${KOMOREBI_CPP_DIR}/huffman.cpp"
)

target_include_directories(ts_pmt_monitor PRIVATE "${KOMOREBI_CPP_DIR}")
