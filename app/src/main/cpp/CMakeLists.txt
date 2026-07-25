cmake_minimum_required(VERSION 3.13)
project(komorebi-native)

set(CMAKE_CXX_STANDARD 17)

set(ARIBCC_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ARIBCC_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(ARIBCC_NO_RENDERER OFF CACHE BOOL "" FORCE)
set(ARIBCC_USE_EMBEDDED_FREETYPE ON CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POLICY_VERSION_MINIMUM 3.5 CACHE STRING "" FORCE)

add_subdirectory(third_party/libaribcaption EXCLUDE_FROM_ALL)

# 1. tsreadex のコアロジック（ソースファイル群）を定義
set(TSREADEX_CORE_SRC
    util.cpp
    id3conv.cpp
    servicefilter.cpp
    aac.cpp
    huffman.cpp
    traceb24.cpp
)

# 2. JNI の橋渡し役となる native-lib.cpp とコアロジックをまとめてライブラリにする
add_library(
    komorebi-native
    SHARED
    native-lib.cpp
    ${TSREADEX_CORE_SRC}
)

# 3. ログ出力用のライブラリをリンク（Android の Logcat 用）
find_library(log-lib log)

target_link_libraries(
    komorebi-native
    ${log-lib}
    aribcaption::aribcaption
)

# インクルードディレクトリの設定（カレントディレクトリを参照）
target_include_directories(komorebi-native PRIVATE .)
