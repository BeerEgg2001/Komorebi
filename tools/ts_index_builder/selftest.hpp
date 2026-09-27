#ifndef TS_INDEX_BUILDER_SELFTEST_HPP
#define TS_INDEX_BUILDER_SELFTEST_HPP

#include <string>

// docs/design/ts_seek_index.md §7-2 の T1〜T12 を実行する。
// tmpDir: 生成した合成TSファイル等の一時置き場(空なら環境依存の一時ディレクトリを使う)。
// runLargeTest: T12(大容量)を実行するか。既定true。時間がかかるためオプションで無効化できる。
// 戻り値: 全テストがpassしたら0、1つでもfailしたら1。
int RunSelfTest(const std::string &tmpDir, bool runLargeTest);

#endif
