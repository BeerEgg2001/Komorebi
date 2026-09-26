# ts_pmt_monitor

録画TSファイルの PMT（音声トラック等のPID構成）が、時間の経過とともにどう変化したかを調査するための診断ツールです。

放送波の途中で音声ストリームが追加・削除されるケース（例: 番組途中から副音声が始まる等）を、実機やTSファイル本体を共有してもらうことなく調査できるようにする目的で作成しました。**出力されるのはPID構成・タイムスタンプ等のメタ情報のみで、映像・音声データそのものは一切出力・保存しません。**

## 特徴

- Android NDK 不要。Android実機/エミュレータも不要です。
- macOS / Linux / Windows のいずれでも、CMake と一般的なC++コンパイラ（clang++ / g++ / MSVC）があればビルドできます。
- アプリ本体（`app/src/main/cpp/servicefilter.cpp` / `util.cpp`）をそのまま参照しているため、実際のKomorebiアプリが解決するPID構成と完全に一致する形で診断できます。

## ビルド方法

```sh
cd tools/ts_pmt_monitor
mkdir build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release
cmake --build .
```

Windows (Visual Studio) の場合:

```sh
cd tools/ts_pmt_monitor
mkdir build && cd build
cmake .. -G "Visual Studio 17 2022"
cmake --build . --config Release
```

## 使い方

```sh
./ts_pmt_monitor <録画TSファイル>
```

### オプション

| オプション | 説明 |
|---|---|
| `--service-id <N>` | 対象サービス(番組)の service_id / program_number を指定。省略時はPAT内の先頭のプログラムを自動選択します。 |
| `--audio1-mode <N>` | tsreadexの `-a` と同じ意味の値。既定値は `13`（アプリ本体のデフォルトと同一）。 |
| `--audio2-mode <N>` | tsreadexの `-b` と同じ意味の値。既定値は `5`（アプリ本体のデフォルトと同一）。 |

### 出力例

```
[変化 #0] byte=0 (0%)  経過時間(PCR基準)=不明(PCR未取得)
    video=(なし)  audio1=(なし)  audio2=(なし)  caption=(なし)  dualMono=false
[変化 #1] byte=188 (0.11%)  経過時間(PCR基準)=不明(PCR未取得)
    video=0x0101 (257)  audio1=0x0102 (258)  audio2=(なし)  caption=(なし)  dualMono=false
[変化 #2] byte=75388 (44.56%)  経過時間(PCR基準)=00:01:39.00
    video=0x0101 (257)  audio1=0x0102 (258)  audio2=0x0103 (259)  caption=(なし)  dualMono=false
```

この例では、再生開始から約99秒後（ファイルの44.56%地点）で音声2本目（PID 0x0103）が新たに現れたことが分かります。

## 動作確認

- macOS上でのビルド・実行確認済み。
- 音声構成が途中で1本→2本に変化する合成TSファイルで、変化の検出（バイト位置・PCR経過時間とも）が正しく行われることを確認済み。
- ランダムバイト列（TS同期パターンが存在しないデータ）に対してもクラッシュしないことを確認済み。
- Windows / Linux でのビルドは未確認です（CMakeLists.txtは標準的な構成のため動作するはずですが、実機確認が取れ次第このREADMEを更新してください）。
