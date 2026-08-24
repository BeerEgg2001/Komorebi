# TS(MPEG-TS)処理層 アーキテクチャ

作成日: 2026-08-24
対象リビジョン: `bddfcf8` 時点の作業ツリー
位置づけ: **現状把握・リファレンス**。「いま実装がどうなっているか」「なぜそうなったか」を記述する。
関連文書: [`docs/design/ts_seek_index.md`](../design/ts_seek_index.md) — こちらは**解決策の提案(未実装)**。本書と内容が重なる箇所があるが、本書は「現状の説明」、設計書は「これからどう直すか」という役割分担になっている。

---

## 0. この層が存在する理由

Komorebi は KonomiTV / EDCB / EPGStation という複数バックエンドに対応しているが、**EDCB バックエンドで「録画再生方式 = DIRECT(直接アクセス)」を選んだ場合と、EDCB/Mirakurun のライブ視聴の場合だけ**、生の MPEG-TS(ARIB 地上波/BS の放送そのままのストリーム)がアプリに届く。

放送 TS はそのままでは ExoPlayer(Media3)で扱いにくい:

- 音声が **デュアルモノ**(1本のAACストリームに主音声/副音声が左右チャンネルとして入る)で送られることがあり、Media3 はこれを2トラックとして認識できない。
- 副音声が**番組の途中から現れる**ことがある。ExoPlayer はトラック構成が途中で変わると再構築が必要になる。
- 字幕(ARIB B24)は PES private data であり、Media3 の標準デコーダでは扱えない。
- PSI/SI(EIT 等)が大量に混ざり、PID 構成も局ごとにばらつく。

そこで **tsreadex**(https://github.com/xtne6f/tsreadex)由来の C++ 実装を `app/src/main/cpp/` に取り込み、**TS を ExoPlayer が食べやすい形に正規化してから渡す**構成になっている。正規化の中身は「サービス(番組)1本だけを抽出し、PID を固定値に付け替え、音声を必ずステレオ2トラックに揃え、字幕を ID3 メタデータに変換する」こと。

導入の経緯はコミットログに残っている:

| コミット | 日付 | 内容 |
|---|---|---|
| `bdb7ad3` / `1885555` | 2026-05-22 | 録画視聴時の複数音声ストリーム対応・デュアルモノ左右振り分け |
| `4aba5ab` | 2026-06-14 | **EDCB 直接アクセスの録画再生に tsreadex を通すよう変更**(複数音声/デュアルモノ対応) |
| `55a0d28` | 2026-06-18 | 音声切り替えが正常にできない問題に暫定対応。**この時に `VideoPlayerManager.kt` の自前 SeekMap 注入が入った** |
| `d049700` | 2026-07-19 | `setTsExtractorTimestampSearchBytes(2MB)` 追加(「シーク精度を改善」と記載。ただし §4-6 の通り現状は無効) |

つまり **TS 処理層は「音声トラックまわりの不具合対応」の積み重ねとして生えてきた**もので、最初から設計されたものではない。この経緯を知らないと、`VideoPlayerManager.kt` の SeekMap 注入が唐突に見える。

---

## 1. 全体アーキテクチャ — 3つの再生経路は「別物」

Komorebi には再生経路が3系統ある。**同じ ExoPlayer を使っていても、TS がどこを通るかは経路ごとに違う。**

```
                                                    ┌──────────────────────────────┐
[A] ライブ視聴 (EDCB直接 / Mirakurun)               │ LivePlayerViewModel.kt        │
                                                    └──────────────────────────────┘
   edcb://ip:port/live?onid=..&tsid=..&sid=..
   または http://mirakurun/.../stream
        │
        ▼
   TsReadExDataSourceFactory  (util/TsReadExDataSourceFactory.kt)
        │  createDataSource()
        ▼
   TsReadExDataSource         (util/TsReadExDataSource.kt)
        │  EDCBならTCPソケット / MirakurunならHTTP
        │  読んだ生バイト → JNI push → フィルタ後バイトを pop
        ▼
   ProgressiveMediaSource.Factory(factory, extractorsFactory)
        │  extractorsFactory = 素の TsExtractor を1個だけ new する
        │  (LivePlayerViewModel.kt:725-740)
        ▼
   TsExtractor(MODE_SINGLE_PMT, TimestampAdjuster, DirectSubtitlePayloadReaderFactory, DEFAULT_TIMESTAMP_SEARCH_BYTES)
        │  ★ SeekMap の上書きなし。ライブなのでシークしない
        ▼
   ExoPlayer


                                                    ┌──────────────────────────────┐
[B] 録画再生 (EDCB + DIRECT)                        │ VideoPlayerManager.kt         │
                                                    └──────────────────────────────┘
   http://.../xxx.ts  (EDCB HTTPサーバ / Range対応)
        │
        ▼
   匿名 DataSource.Factory   (VideoPlayerManager.kt:144-196)
        │  open() の中でURIを見て TsReadExDataSource か DefaultHttpDataSource を選ぶ
        │  ★ tsArgs をここで動的に組み立てる (:163-166)
        ▼
   TsReadExDataSource        (fileSizeBytesRef と cfAccessHeaders を渡す)
        │  HTTP Range で dataSpec.position から読む
        ▼
   customExtractorsFactory   (VideoPlayerManager.kt:201-261)
        │  DefaultExtractorsFactory が作った配列から TsExtractor を探して
        │  ★ ExtractorOutput.seekMap() を横取りし、自前の線形補間SeekMapに差し替える
        ▼
   DefaultMediaSourceFactory(dataSourceFactory, customExtractorsFactory)
        ▼
   ExoPlayer


                                                    ┌──────────────────────────────┐
[C] SMB(NAS)再生                                    │ ui/video/smb/                 │
                                                    └──────────────────────────────┘
   smb://...
        ▼
   SmbVlcPlayerScreen.kt  →  libVLC (org.videolan.libvlc.LibVLC / MediaPlayer)
        ★ ExoPlayer も tsreadex も一切通らない完全に独立した経路
```

### 1-1. [A] ライブ経路の該当箇所

`LivePlayerViewModel.kt:90-91` で、DataSource ファクトリを **ViewModel のフィールドとして 1 度だけ生成**している(メイン画面用と2画面モード用の2本)。

```kotlin
private val mainTsDataSourceFactory = TsReadExDataSourceFactory(NativeLib(), emptyArray())
private val dualTsDataSourceFactory = TsReadExDataSourceFactory(NativeLib(), emptyArray())
```

引数は `buildStreamUrl()` の中で、チャンネルを選んだタイミングで**ファクトリのプロパティに後から書き込む**方式(`LivePlayerViewModel.kt:654-669` が EDCB、`:675-690` が Mirakurun)。

MediaSource の構築は `LivePlayerViewModel.kt:724-742`:

```kotlin
if (source == StreamSource.MIRAKURUN || (source == StreamSource.EDCB && isEdcbDirect)) {
    val extractorsFactory = ExtractorsFactory {
        arrayOf(
            TsExtractor(
                TsExtractor.MODE_SINGLE_PMT,
                TimestampAdjuster(C.TIME_UNSET),
                DirectSubtitlePayloadReaderFactory(...),
                TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
            )
        )
    }
    ProgressiveMediaSource.Factory(factory, extractorsFactory)
        .createMediaSource(mediaItem)
}
```

**ポイント:**

- `DefaultExtractorsFactory` を通さず、`TsExtractor` を1個だけ直接 `new` している(sniff コストを省き、必ず TS として扱わせるため)。
- **SeekMap の上書きは一切していない。** ライブはシークしないので不要。
- 字幕は `DirectSubtitlePayloadReaderFactory` という自前の PayloadReader で PES から直接取り出し、`_subtitleEvents` として Flow に流す。録画側([B])が ID3 メタデータ(`onMetadata` → WebView)経由なのと**方式が違う**点に注意。

### 1-2. [B] 録画(EDCB直接アクセス)経路の該当箇所

判定は `VideoPlayerManager.kt:89`:

```kotlin
val isEdcbDirect = (backendType == "EDCB" && edcbPlayMethod == "DIRECT")
```

DataSource は匿名の `DataSource.Factory`(`VideoPlayerManager.kt:144-196`)。`open()` の中で URI を見て分岐する(`:153-183`):

```kotlin
val isEdcbScheme = dataSpec.uri.scheme == "edcb"
val isDirectTs = dataSpec.uri.path?.endsWith(".ts", ignoreCase = true) == true
              || dataSpec.uri.path?.endsWith("m2ts", ignoreCase = true) == true
...
val source = if (isEdcbScheme || isDirectTs || isEdcbDirect) {
    TsReadExDataSource(nativeLib, dynamicTsArgs, fileSizeBytesRef, cfAccessHeaders)
} else {
    httpDataSourceFactory.createDataSource()
}
```

Extractor は `VideoPlayerManager.kt:201-261`(詳細は §4-2)。

### 1-3. [C] SMB は完全に別世界

`ui/video/smb/player/SmbVlcPlayerScreen.kt:232-233` で `LibVLC` / `MediaPlayer` を直接生成しており、ExoPlayer も tsreadex も通らない。`SmbDataSource.kt` / `SmbDataSourceFactory.kt` は Media3 の `DataSource` 実装として残っているが、`d049700`(2026-07-19)で **ExoPlayer 側の分岐からは削除済み**。`VideoPlayerManager.kt:53-54` に import だけが残っている状態。

> **引き継ぐ人へ**: 「音ズレが直った / 直らない」といった報告を受けたとき、まずどの経路の話かを確定させること。[A]/[B]/[C] は共通コードがほとんどない。

---

## 2. ネイティブ層(C++)

`app/src/main/cpp/` の構成。ビルド対象は `CMakeLists.txt` で決まる:

```cmake
set(TSREADEX_CORE_SRC
    util.cpp id3conv.cpp servicefilter.cpp aac.cpp huffman.cpp traceb24.cpp)
add_library(komorebi-native SHARED native-lib.cpp ${TSREADEX_CORE_SRC})
```

| ファイル | ビルド対象 | 役割 |
|---|---|---|
| `native-lib.cpp` | ○ | JNI ブリッジ。`TsReadExContext` |
| `servicefilter.cpp/.hpp` | ○ | **本体**。サービス抽出・PID付け替え・音声トランスマックス |
| `util.cpp/.hpp` | ○ | TS/PSI パース・CRC・リシンク |
| `aac.cpp/.hpp` | ○ | AAC のモノ→ステレオ / デュアルモノ分離 |
| `huffman.cpp/.hpp` | ○ | AAC ハフマン復号(`aac.cpp` の下請け) |
| `id3conv.cpp/.hpp` | ○ | ARIB字幕 PES → ID3 メタデータ変換 |
| `traceb24.cpp/.hpp` | ○ | 字幕テキストのトレース出力。**Komorebi では実質未使用**(§2-1 参照) |
| `tsreadex.cpp` | **×** | 上流 tsreadex の `main()`。参考として残っているだけ |
| `maketree.cpp` | **×** | `huffman.cpp` のテーブル生成ツール。ビルド対象外 |
| `Makefile` | — | 上流由来。Android ビルドでは使わない |

`abiFilters` は `armeabi-v7a` / `arm64-v8a` に意図的に限定(Android TV 端末想定)。

### 2-1. `native-lib.cpp` — JNI ブリッジ

#### `TsReadExContext`(`native-lib.cpp:17-125`)

1本の再生ストリームに対応するコンテキスト。フィルタ3つを直列に持つ:

```cpp
CServiceFilter servicefilter;   // サービス抽出＋PID正規化＋音声トランスマックス
CTraceB24Caption traceb24;      // ★ メンバとして存在するが pushData から呼ばれていない(デッド)
CID3Converter id3conv;          // 字幕PES → ID3
```

> **注意**: `traceb24` はメンバに宣言されている(`native-lib.cpp:26`)が、`pushData()` 内で `AddPacket()` が呼ばれていない。上流 tsreadex の `-r` オプション(トレースファイル出力)に対応する処理が JNI 版では省かれているため、実質的に**未使用のオブジェクト**。`traceb24.cpp` は 1090 行あるがビルドされるだけで動かない。

その他のメンバ:

- `seekOffset` / `limitReadBytesPerSec` / `timeoutSec` / `timeoutMode`(`:19-22`) — 上流 tsreadex の `-s`/`-l`/`-t`/`-m` に対応してパースされるが、**JNI版では読み捨てられており実際には使われていない**(読み込み自体は Kotlin 側の責務のため)。
- `excludePidSet`(`:23`) — `-x` で指定された除外 PID。**唯一 JNI 版でも機能するグローバルオプション**。
- `unitSize`(`:29`) — 188 / 192 / 204 のいずれか。`resync_ts()` が決める。
- `residualBuffer`(`:30`) — チャンク境界でパケットが分断された分の持ち越し。
- `outputQueue` + `mtx` + `MAX_QUEUE_SIZE = 8MB`(`:33-35`) — 出力を溜める非同期キュー。**8MB を超えると新しい出力を黙って捨てる**(`:108-110`)。

#### コンストラクタ = コマンドライン引数パーサ(`native-lib.cpp:37-66`)

`tsreadex` の argv をそのまま受け取る形になっている。Kotlin 側は文字列配列を渡すだけ。

```cpp
else if (c == 'n') { servicefilter.SetProgramNumberOrIndex(std::atoi(argv[++i])); }
else if (c == 'a') { servicefilter.SetAudio1Mode(std::atoi(argv[++i])); }
else if (c == 'b') { servicefilter.SetAudio2Mode(std::atoi(argv[++i])); }
else if (c == 'c') { servicefilter.SetCaptionMode(std::atoi(argv[++i])); }
else if (c == 'u') { servicefilter.SetSuperimposeMode(std::atoi(argv[++i])); }
else if (c == 'd') { id3conv.SetOption(std::atoi(argv[++i])); }
else if (c == 'r') { i++; }   // 読み捨て
else if (c == 'z') { i++; }   // 読み捨て
```

ループは `for (int i = 0; i < argc; ++i)` で全要素を走査し、`-` で始まる要素だけを見る。したがって **argv[0] にプログラム名があってもなくても動く**。実際、録画側は `"tsreadex"` をダミーで先頭に入れており(`VideoPlayerManager.kt:164`)、ライブ側は入れていない(`LivePlayerViewModel.kt:654-655`)。どちらでも同じ結果になる。

#### `pushData()`(`native-lib.cpp:68-113`) — 処理の中心

1. `residualBuffer` と入力を連結。
2. `unitSize == 0` なら `resync_ts()`(`util.cpp:129`)で同期バイト `0x47` の周期を探し、188/192/204 を決定。決まらなければ全部を residual に積んで return(`:80-86`)。
3. `unitSize` 刻みでパケットを切り出し、除外 PID でなければ `servicefilter.AddPacket()`(`:88-92`)。
4. 端数を `residualBuffer` に退避(`:94-97`)。
5. `servicefilter.GetPackets()` の出力を **188バイト刻み**で `id3conv.AddPacket()` に流す(`:99-103`)。
   → **`CServiceFilter` の出力は常に 188 バイト固定**であることが前提になっている(入力が 192/204 でも出力は 188)。
6. `id3conv.GetPackets()` を `outputQueue` に積む(`:105-112`)。

#### JNI 関数

| 関数 | 行 | 役割 |
|---|---|---|
| `openFilter(String[] args)` → `jlong` | `:129-142` | `TsReadExContext` を `new` してポインタを `jlong` で返す |
| `pushDataBuffer(handle, DirectByteBuffer, len)` | `:144-150` | 生 TS を投入 |
| `popDataBuffer(handle, DirectByteBuffer, maxLen)` → `jint` | `:152-159` | フィルタ後バイトを取り出す。0 = まだ無い、-1 = エラー |
| `processDataBuffer(handle, in, len, out)` → `jint` | `:161-171` | push + pop を1回で行う便利版。**現在 Kotlin 側から呼ばれていない** |
| `closeFilter(handle)` | `:173-177` | `delete` |

いずれも `GetDirectBufferAddress()` を使うため、**Kotlin 側は必ず `ByteBuffer.allocateDirect()` で確保したバッファを渡さなければならない**(`TsReadExDataSource.kt:40-42`)。

> **設計上の注意**: `handle` は生ポインタの `jlong`。`closeFilter()` 後に `pushDataBuffer()` を呼べば即クラッシュする。Kotlin 側は `TsReadExDataSource.close()`(`:286-288`)で `handle = 0L` に落としており、`popDataBuffer` は `handle == 0` で `nullptr` 扱いになるため `-1` が返る。ただし**スレッド間の同期は取られていない**。

### 2-2. `servicefilter.cpp/.hpp` — `CServiceFilter`

TS 処理の心臓部。**「PAT に載っている複数サービスのうち1つだけを抜き出し、PID を固定値に付け替えて出力する」**のが基本動作。

#### 出力 PID の固定表

| ストリーム | 出力PID | 定義箇所 |
|---|---|---|
| PAT | `0x0000` | `servicefilter.cpp:332-333` |
| NIT | `0x0010` | `:265` |
| PMT | `0x01f0` | `:311-313`, `:626-628` |
| PCR(専用パケット) | `0x01ff` | `:363-365`, `:640-641` |
| 映像 | `0x0100` | `:176`, `:474-476` |
| 主音声 | `0x0110` | `:212`, `:493-494` |
| 副音声 | `0x0111` | `:220`, `:243`, `:525-527` |
| 字幕 | `0x0130` | `:251`, `:546-548` |
| 文字スーパー | `0x0138` | `:256`, `:575-576` |

**この固定化が全体の肝**。ExoPlayer 側は放送局ごとの PID 差異を意識せずに済む。逆に言うと、`VideoPlayerManager.kt` の音声トラック選択ロジック(`:91-119`)は「音声トラックが必ず 0x0110 / 0x0111 の順で来る」ことを暗黙に期待している(`getFormat(0).id?.toIntOrNull()` でソートしている `:95-97` がそれ)。

#### 主要メンバ変数

| メンバ | 意味 | 初期値 |
|---|---|---|
| `m_programNumberOrIndex` | **正 = service_id 指定 / 負 = PAT 内の N 番目 / 0 = フィルタせず素通し** | 0 |
| `m_videoPid` / `m_audio1Pid` / `m_audio2Pid` / `m_captionPid` / `m_superimposePid` | **入力側**の解決済み PID。`AddPmt()` が毎回上書き。0 = 未解決/不在 | 0 |
| `m_pcrPid` | PMT の `PCR_PID`。`0x1fff` なら PCR 無しとして `m_pcr = -1` | 0 |
| `m_pcr` | 直近に観測した PCR(90kHz, 33bit)。-1 = 未取得 | -1 |
| `m_audio1StreamType` / `m_audio2StreamType` | `0x0f`(ADTS) or `0x04`(MPEG2 Audio)。PMT に書き戻す | 0 |
| `m_audio1Mode` / `m_audio2Mode` | 0〜3。§2-2-1 参照 | 0 |
| `m_audio1MuxToStereo` / `m_audio2MuxToStereo` | モノ→ステレオ変換を行うか(mode の bit2) | false |
| `m_audio1MuxDualMono` | デュアルモノを左右2本に分離するか(mode の bit3) | false |
| `m_isAudio1DualMono` | **実際に**デュアルモノとして分離できたか。PES 単位で毎回更新(`:182`) | false |
| `m_audio1Pts` / `m_audio2Pts` | **合成無音**を生成するときの「次に出す PTS」。実音声の PTS ではない | -1 |
| `m_audio1PtsPcrDiff` | 実音声の `PTS − PCR`。**初期値が 0**(`:34`) | **0** |
| `m_audio2PtsPcrDiff` | 同上。**初期値が -1**(`:35`)。この非対称が §5-3 の問題の起点 | **-1** |
| `m_lastPat` / `m_lastPmt` | 直前に出力したテーブル。**内容が同じなら CRC を使い回し、違えば version_number をインクリメント**(`:314-328`, `:607-621`) | 空 |
| `m_patCounter` 〜 `m_superimposePesCounter` | 出力側の continuity_counter | 0 / caption・superimpose は 0xff |

> `m_lastPat`/`m_lastPmt` による version 管理は重要。**PMT の中身が変わったときだけ version_number が上がる**ので、ExoPlayer 側は無駄なトラック再構築をしない。逆に副音声が途中から現れると version が上がり、ExoPlayer が `onTracksChanged` を発火する。

#### 2-2-1. `SetAudio1Mode` / `SetAudio2Mode` と、アプリが渡す `-a 13 -b 5` の意味

```cpp
// servicefilter.cpp:44-55
void CServiceFilter::SetAudio1Mode(int mode) {
    m_audio1Mode      = mode % 4;
    m_audio1MuxToStereo  = !!(mode & 4);
    m_audio1MuxDualMono  = !!(mode & 8);
}
void CServiceFilter::SetAudio2Mode(int mode) {
    m_audio2Mode      = mode % 4;
    m_audio2MuxToStereo  = !!(mode & 4);
}
```

`mode % 4` の意味(コードから読み取れる挙動):

| 値 | 挙動 | 根拠となる行 |
|---|---|---|
| 0 | 実在すれば出力、無ければ何もしない | `:484` の `addAudio2` 条件 |
| 1 | **常に出力。実データが無い間は合成無音 PES で埋める** | `:130-138`(無音生成)、`:490`/`:484`(PMT に必ず宣言) |
| 2 | 出力しない(そもそも PID を割り当てない) | `:419`, `:434`, `:443`, `:449` の `!= 2` |
| 3 | 実在しなければ主音声をコピーして副音声にする | `:191`, `:485-488` |

**アプリが実際に渡している値:**

| 経路 | 引数 | 展開 |
|---|---|---|
| 録画(`VideoPlayerManager.kt:163-166`) | `-a 13 -b 5` | audio1: mode=1, MuxToStereo, MuxDualMono / **audio2: mode=1**, MuxToStereo |
| ライブ EDCB(`LivePlayerViewModel.kt:654-669`) | `-a 13 -b 4` | audio1: 同上 / **audio2: mode=0**, MuxToStereo |
| ライブ Mirakurun(`LivePlayerViewModel.kt:675-690`) | `-a 13 -b 4` | 同上 |

計算過程:
- `13 = 0b1101` → `13 % 4 = 1`(mode=1)、`13 & 4 = 4`(MuxToStereo=true)、`13 & 8 = 8`(MuxDualMono=true)
- `5 = 0b0101` → `5 % 4 = 1`(mode=1)、`5 & 4 = 4`(MuxToStereo=true)
- `4 = 0b0100` → `4 % 4 = 0`(**mode=0**)、`4 & 4 = 4`(MuxToStereo=true)

つまり:

> **録画再生では副音声トラック(0x0111)が常に PMT に宣言され、実データが無い区間は PCR 同期した合成無音で埋められる。ライブでは副音声が実在するときだけ出る。**

これが `-b 5` と `-b 4` の唯一かつ決定的な違いで、**録画側でだけ音ズレが報告される現象**の背景にある(§5-3)。録画で `-b 5` を使う理由は、UI の主/副音声トグル(`VideoPlayerManager.kt:91-119`)が常に2トラックあることを前提にしているため、と読める(`55a0d28`「音声切り替えが正常にできない問題に暫定対応」)。

その他の引数:

- `-x 18/38/39` — 除外 PID(10進)。0x12=EIT, 0x26=H-EIT, 0x27=M-EIT。EPG 情報を捨ててパース負荷を下げる。
- `-n <service_id>` — `m_programNumberOrIndex`。録画側は `program?.channel?.serviceId ?: -1`(`VideoPlayerManager.kt:160-161`)なので、**serviceId が取れないと `-1` = PAT 先頭のプログラムにフォールバックする**。
- `-c 5` — caption mode=1(常に字幕 ES を宣言) + `m_captionInsertManagementPacket=true`(字幕管理 PES を定期挿入)。
- `-u 1` — superimpose mode=1(常に宣言)、管理パケット挿入なし。
- `-d 13` — `CID3Converter::SetOption(13)`(`id3conv.cpp:24-30`)。`13 = 0b1101` → 有効化 + `m_insertInappropriate5BytesIntoPesPayload` + `m_forceMonotonousPts`。

#### 2-2-2. `AddPacket()`(`servicefilter.cpp:69-270`) の処理フロー

```
m_programNumberOrIndex == 0 ?
  └─ Yes: 何もせず素通し (:71-74)  ← アプリでは使われない設定
  └─ No:
     pid == 0 (PAT) ?
       └─ extract_pat() で m_pat を更新 (:84)
          FindTargetPmtRef() で対象PMTを探す (:85)
            見つかった & unit_start → AddPat() で自前PATを出力 (:87-89)
            見つからない → 全PIDと m_pcr をリセット (:91-99)
     else:
       pid == 対象PMTのPID ?  → extract_psi() → AddPmt() (:104-113)
       pid == m_pcrPid ?      → PCRを抽出 (:114-174)
                                  ├ PCR専用パケットを 0x01ff で出力 (:118-124)
                                  ├ m_pcr を更新 (:125-129)
                                  ├ 主音声が不在なら合成無音を生成 (:130-132)
                                  ├ 副音声が不在なら合成無音を生成 (:133-138)  ← §5-3
                                  └ 字幕/文字スーパーの管理PESを定期挿入 (:140-171)
       pid == m_videoPid ?    → 0x0100 に付け替えてそのまま出力 (:175-177)
       pid == m_audio1Pid ?   → PESを1本ぶん溜めてからトランスマックス (:178-225)
       pid == m_audio2Pid ?   → 同上 (:226-247)
       pid == m_captionPid ?  → 0x0130 に付け替え (:248-252)
       pid == m_superimposePid?→ 0x0138 に付け替え (:253-257)
       pid < 0x0030 ?         → PSI/SI として素通し (:258-260)
       else                   → NIT なら 0x0010 に付け替え、それ以外は破棄 (:261-267)
```

**破棄されるものが多い**点が重要。対象サービス以外の ES、EIT、SDT の一部などは出力に現れない。

#### 2-2-3. `AddPat()`(`:293-338`)

自前の PAT を組み立てる。中身は「transport_stream_id はそのまま」「プログラムは対象1本のみ(PMT_PID=0x01f0)」「NIT が元 PAT にあれば NIT エントリも付ける」。
出力バイト列が前回と完全に一致すれば CRC を丸ごとコピーし、違えば `version_number` を +1 して CRC を再計算する(`:314-328`)。

#### 2-2-4. `AddPmt()`(`:340-634`) — PID 解決の中枢

**前半: PID 解決(`:371-457`)**

まず全 PID を 0 にリセットしてから(`:373-377`)、ES ループで解決し直す。判定は **stream_type と component_tag の組み合わせ**:

| stream_type | component_tag | 割り当て | 行 |
|---|---|---|---|
| `0x02`(MPEG2) / `0x1b`(AVC) / `0x24`(HEVC) | `0xff`(記述子なし・かつ未割当) / `0x00` / `0x81` | `m_videoPid` | `:402-410` |
| `0x0f`(ADTS/AAC) | `0xff`(未割当時) / `0x10` / `0x83` / `0x85` | `m_audio1Pid` | `:411-417` |
| `0x0f` | `0x11` | `m_audio2Pid`(mode≠2 のとき) | `:418-424` |
| `0x04`(MPEG2 Audio) | — | 未割当なら順に audio1 → audio2 | `:426-440` |
| `0x06`(PES private data) | `0x30` / `0x87` | `m_captionPid` | `:441-447` |
| `0x06` | `0x38` / `0x88` | `m_superimposePid` | `:448-453` |

`0x81`/`0x83`/`0x85`/`0x87`/`0x88` は **Cプロファイル(ワンセグ以外の携帯向け等)**の component_tag。これを拾ったら `maybeCProfile = true` になり(`:408`)、以降の合成 ES 記述子の component_tag も C プロファイル系に切り替わる(`:503`, `:520`, `:541`, `:562`, `:592`)。

**PID が前回と変わったら音声の状態をリセットする**(`:459-470`):

```cpp
if (m_audio1Pid != lastAudio1Pid) {
    m_audio1Pts = -1;
    m_isAudio1DualMono = false;
    m_audio1UnitPackets.clear(); ...
}
```

> ここで `m_audio1PtsPcrDiff` / `m_audio2PtsPcrDiff` は**リセットされない**。意図的かどうかは不明だが、§5-3 の挙動に効いてくる。

**後半: 出力 PMT の組み立て(`:472-633`)**

```cpp
// servicefilter.cpp:484
bool addAudio2 = m_audio2Pid != 0 || m_audio2Mode == 1 || m_audio2Mode == 3
              || (m_audio2Mode != 2 && m_isAudio1DualMono);
```

`m_audio2Mode == 1`(= アプリの `-b 5`)なら **実データの有無に関係なく副音声 ES(0x0111)が常に PMT に載る**。
主音声も同様に `m_audio1Pid != 0 || m_audio1Mode == 1`(`:490`)で常に載る。

実 PID が無い場合(`:536-542`)は、`stream_identifier_descriptor`(0x52)だけを持つ最小の ES 記述子を合成して宣言する:

```cpp
m_buf.push_back(0xf0); m_buf.push_back(3);
m_buf.push_back(0x52); m_buf.push_back(1);
m_buf.push_back(maybeCProfile ? 0x85 : 0x11);   // component_tag
```

また `audio1ComponentTagUnknown && addAudio2` のとき(`:497-504`)、主音声の ES 記述子に **component_tag 0x10(または 0x83)を後付けで挿入する**。副音声(0x11)と区別が付くようにするため。

**PCR_PID の扱い**: 出力 PMT の `PCR_PID` はデフォルト `0x01ff`(`:363-365`)だが、元の PCR_PID が映像/主音声/副音声/字幕/文字スーパーのいずれかと同じ PID だった場合は、その付け替え先を `PCR_PID` に書き換える(`:479-482`, `:510-513`, `:531-534`, `:552-555`, `:581-584`)。この場合 `AddPcrAdaptation()` は呼ばれない(`:118-124` の条件)。

#### 2-2-5. 音声トランスマックス

**PES の再組み立て — `AccumulatePesPackets()`(`:799-840`)**

TS パケットを `unit_start` から `PES_packet_length` 分が揃うまで溜める。continuity_counter が飛んだら**捨ててやり直す**(`:816-819`)。溜め込み上限 `0x20000`(128KB)(`:805`)。

**`TransmuxDualMono()`(`:936-988`)**

デュアルモノ(1本の AAC に L=主音声/R=副音声)を **2本の独立した AAC ストリーム**に分離する。

1. `ConcatenatePayload()`(`:842-860`)で TS ペイロードを連結し、ついでに PCR も拾う。
2. PES ヘッダを検証し、`Aac::TransmuxDualMono()`(`aac.cpp:487`)で左右を分離。`m_audio1MuxToStereo` / `m_audio2MuxToStereo` に応じて各々をステレオ化もする。
3. 左を `stream_id = 0xc0`、PID `0x0110` として出力(`:962-967`)。
4. `m_audio2Pid == 0 && m_audio2Mode != 2` なら、右を `stream_id = 0xc1`、PID `0x0111` として出力(`:969-981`)。
   → **実在の副音声がある場合は右チャンネルを捨てる**(実音声を優先)。

呼び出しは `:182`。**PES 1本ごとに毎回判定される**ので、`m_isAudio1DualMono` は途中で `true`↔`false` を行き来しうる。

**`TransmuxMonoToStereo()`(`:900-934`)**

モノラル AAC を2ch に複製する。`Aac::TransmuxMonoToStereo()`(`aac.cpp:655`)を呼ぶ。成功したら `AddAudioPesPackets(pes, pid, counter, ptsPcrDiff, pcr)`(`:862-898`)で TS パケットに再パケット化する。

`AddAudioPesPackets()` は 184 バイト刻みで分割するが、**元パケットに PCR があった場合は最終パケットのペイロードを 176 バイトに削ってアダプテーションフィールドに PCR を差し込む**(`:870-884`)。PCR を落とさないための配慮。

**トランスマックスしない場合(パススルー)** — `:188-189`, `:202-222`

```cpp
passthroughAudio1 = !m_audio1MuxToStereo || m_audio1StreamType != ADTS_TRANSPORT ||
                    !TransmuxMonoToStereo(...);
```

パススルー時は元 TS パケットを PID だけ付け替えて出力する(`:212`)。このとき `m_audio1PtsPcrDiff` を更新する:

```cpp
// servicefilter.cpp:207-213
if (passthroughAudio1) {
    if (pts >= 0 && m_pcr >= 0) {
        m_audio1PtsPcrDiff = 0x200000000 + pts - m_pcr;   // ← 実音声のPTS-PCR学習
    }
    m_audio1PesCounter = (m_audio1PesCounter + 1) & 0x0f;
    ChangePidAndAddPacket(packet_, 0x0110, m_audio1PesCounter);
}
```

同様に `m_audio2PtsPcrDiff` は `:217`(audio1→audio2 コピー時)、`:240`(実 audio2 処理時)、`:893`(トランスマックス経由)で更新される。

#### 2-2-6. 合成無音の生成 — `AddAudioPesPackets(index, targetPts, pts, counter)`(`:723-739`)

PCR パケットを見るたびに呼ばれ、`targetPts` に追いつくまで **64ms 単位の無音 PES** を吐き続ける。

```cpp
for (;;) {
    int64_t nextPts = (pts + 90000 * 64 / 1000) & 0x1ffffffff;   // 64ms
    if (((0x200000000 + targetPts - nextPts) & 0x1ffffffff) > 900000) break;  // 10秒以上先なら打ち切り
    Add64MsecAudioPesPacket(index, pts, counter);
    pts = nextPts;
}
```

`pts` が未初期化(-1)か、`targetPts` と 10 秒以上ずれていたら `pts = targetPts` にリセットする(`:727-730`)。

`Add64MsecAudioPesPacket()`(`:741-777`)は **48kHz/2ch の無音 ADTS フレームを 3 個(1024 samples × 3 / 48000 = 64ms)**、PTS 付き PES として 1 TS パケットに詰める。無音フレームは 13 バイトの定数テーブル(`:743-745`):

```cpp
static const uint8_t ADTS_2CH_48KHZ_SILENT[13] = {
    0xff, 0xf1, 0x4c, 0x80, 0x01, 0xbf, 0xfc, 0x21, 0x10, 0x04, 0x60, 0x8c, 0x1c
};
```

**呼び出し側(`:130-138`)が本書で最も重要な箇所:**

```cpp
// servicefilter.cpp:130-138
if (m_audio1Mode == 1 && m_audio1Pid == 0) {
    AddAudioPesPackets(0, (m_pcr + m_audio1PtsPcrDiff) & 0x1ffffffff, m_audio1Pts, m_audio1PesCounter);
}
if ((m_audio2Mode == 1 || (m_audio2Mode == 3 && m_audio1Pid == 0)) && m_audio2Pid == 0 && !m_isAudio1DualMono) {
    if (m_audio2PtsPcrDiff < 0) {
        m_audio2PtsPcrDiff = m_audio1PtsPcrDiff;   // ← 一度だけラッチされる
    }
    AddAudioPesPackets(1, (m_pcr + m_audio2PtsPcrDiff) & 0x1ffffffff, m_audio2Pts, m_audio2PesCounter);
}
```

無音の PTS を `PCR + PtsPcrDiff` で決めることで、**実音声が現れたときにシームレスに繋がるはず**、という設計。詳細と既知の問題は §5-3。

### 2-3. `util.cpp/.hpp` — TS/PSI パースの基礎

| 関数 | 行 | 内容 |
|---|---|---|
| `calc_crc16_ccitt` | `util.cpp:4-14` | ARIB 字幕データのCRC検証用 |
| `calc_crc32` | `:16-26` | MPEG-2 セクションの CRC32(多項式 `0x04c11db7`) |
| `extract_psi` | `:28-80` | **PSI セクションの再組み立て**。`pointer_field` と continuity_counter を追跡し、CRC32 が 0 になったら `table_id`/`section_length`/`version_number`/`current_next_indicator` を確定させる。1パケットに2セクション入る場合に備え、戻り値 0 で「もう一度呼べ」を意味する(`:43-44`) |
| `extract_pat` | `:82-110` | `extract_psi` を `done` になるまで回し、PAT から `transport_stream_id` と `(program_number, pmt_pid)` の一覧を構築。**program_number == 0 は NIT** としてリストに含める(`:101`) |
| `get_ts_payload_size` | `:112-127` | アダプテーションフィールドを考慮したペイロード長。0/184/`183-adaptation_length` のいずれか |
| `resync_ts` | `:129-157` | **`unit_size` の自動判定**。188 → 192 → 204 の順に試し、その周期で `0x47` が並ぶオフセットを探す。失敗時は `*unit_size = 0` にして `data_size` を返す |

`util.hpp:40-43` にヘッダ解析の inline 関数(`extract_ts_header_pid` など)、`:45-62` にビット単位リーダ(`read_bits` 等、`aac.cpp` / `traceb24.cpp` が使う)。

`PSI::data` は **1024 バイト固定配列**(`util.hpp:16`)。これを超えるセクションは切り詰められる(`util.cpp:61`)。

> **`version_number` の表現に注意**: `extract_psi` は `psi->version_number = 0x20 | ((data[5] >> 1) & 0x1f)`(`:75`)としており、**bit5 を「有効フラグ」として使っている**。だから `servicefilter.cpp:108` の `if (m_pmtPsi.version_number && ...)` は「一度でも正しい PMT を受け取ったか」の判定になる。0 と実際の version 0 が区別できるようにするための工夫。

### 2-4. `aac.cpp/.hpp` / `huffman.cpp/.hpp` — AAC トランスマックス

`Aac` 名前空間に2関数だけ公開(`aac.hpp:10-12`):

```cpp
bool TransmuxDualMono(std::vector<uint8_t> &destLeft, std::vector<uint8_t> &destRight,
                      std::vector<uint8_t> &workspace,
                      bool muxLeftToStereo, bool muxRightToStereo,
                      const uint8_t *payload, size_t lenBytes);   // aac.cpp:487
bool TransmuxMonoToStereo(std::vector<uint8_t> &dest, std::vector<uint8_t> &workspace,
                          const uint8_t *payload, size_t lenBytes); // aac.cpp:655
```

これらは **ADTS フレームを完全にデコード/再エンコードするわけではない**。AAC のビットストリームを構文レベルで解析し、チャンネル要素(SCE/CPE)の構成だけを組み替えて出力する「ビットストリーム書き換え」を行う。そのために必要なのが `huffman.cpp`:

```cpp
// huffman.hpp:9-12
const size_t MAX_CODEWORD_LEN = 19;
int  DecodeScalefactorBits(const uint8_t *data, size_t &pos);            // huffman.cpp:355
void DecodeSpectrumQuadBits(int codebook, ..., int &w,&x,&y,&z);         // huffman.cpp:361
void DecodeSpectrumPairBits(int codebook, ..., int &y, &z);              // huffman.cpp:373
```

スペクトルデータのハフマン符号長を**読み飛ばすためだけ**に復号している(値そのものはコピーする)。デコード用テーブルは `maketree.cpp`(ビルド対象外)で生成された静的テーブルとして `huffman.cpp` 内の無名 namespace に埋め込まれている。

> **引き継ぐ人へ**: `aac.cpp`(813行)と `huffman.cpp`(383行)は上流 tsreadex からほぼ無改変で持ってきたもの。Komorebi 独自の改変は入っていない。バグを疑う場合は上流の同名ファイルと diff を取るのが早い。

### 2-5. `id3conv.cpp/.hpp` — 字幕 → ID3

`CID3Converter` は、`CServiceFilter` の出力(PID 0x0130 / 0x0138)から字幕/文字スーパーの PES を取り出し、**ID3 タグとして新しい PID に詰め直す**。ExoPlayer 側では `PrivFrame` として `Player.Listener.onMetadata()` に届き、`VideoPlayerManager.kt:345-364` で WebView(aribb24.js)に渡される。

```kotlin
// VideoPlayerManager.kt:349-352
if (entry is PrivFrame && (entry.owner.contains("aribb24", true) || entry.owner.contains("B24", true)))
```

オプション(`id3conv.cpp:24-30`)。アプリは `-d 13` を渡すので:

| ビット | メンバ | `13` での値 |
|---|---|---|
| 1 | `m_enabled` | **true** |
| 2 | `m_treatUnknownPrivateDataAsSuperimpose` | false |
| 4 | `m_insertInappropriate5BytesIntoPesPayload` | **true** |
| 8 | `m_forceMonotonousPts` | **true** |

`m_enabled == false` のときは全パケットを素通しする(`id3conv.cpp:34-36`)。

---

## 3. Kotlin 側の統合層

### 3-1. `NativeLib.kt` — JNI 宣言

`app/src/main/java/com/beeregg2001/komorebi/NativeLib.kt`(全27行)。`companion object` の `init` で `System.loadLibrary("komorebi-native")`。

```kotlin
external fun openFilter(args: Array<String>): Long
external fun processDataBuffer(handle: Long, inputBuffer: ByteBuffer, inputLength: Int, outputBuffer: ByteBuffer): Int
external fun closeFilter(handle: Long)
external fun pushDataBuffer(handle: Long, inputBuffer: ByteBuffer, inputLength: Int)
external fun popDataBuffer(handle: Long, outputBuffer: ByteBuffer, maxLen: Int): Int
```

`NativeLib` インスタンス自体は**状態を持たない**(状態は `handle` が指すネイティブ側にある)。そのため、`VideoPlayerManager.kt:136` では 1 つだけ生成して使い回し、`LivePlayerViewModel.kt:90-91` では 2 つ生成している。どちらでも問題ない。

`processDataBuffer` は現在どこからも呼ばれていない(`push`/`pop` を個別に使っている)。

### 3-2. `TsReadExDataSourceFactory.kt`

わずか 21 行。`tsArgs` と `requestHeaders` を **`var` として公開**しており、外部から差し替えられる。

```kotlin
var tsArgs: Array<String> = initialArgs
var requestHeaders: Map<String, String> = emptyMap()

override fun createDataSource(): DataSource =
    TsReadExDataSource(nativeLib, tsArgs, requestHeaders = requestHeaders)
```

`fileSizeBytesRef` を渡していない(= `null`)ので、**ライブ経路ではファイルサイズが記録されない**。ライブにシークは無いので問題にならない。

ライブ側は「ViewModel が持つ 1 つのファクトリの `tsArgs` を、チャンネル選択時に書き換える」という使い方(`LivePlayerViewModel.kt:654`, `:675`)。**チャンネル切替と `createDataSource()` の呼び出しが競合すると古い引数が使われうる**が、実際には `startPlayback()` が `buildStreamUrl()` の後に呼ばれるため問題は表面化していない。

### 3-3. `TsReadExDataSource.kt` — 中核

`BaseDataSource(true)` を継承。**1 回の `open()` ごとに:**

1. ネイティブフィルタを新規生成(`:72`)
2. URI スキームで入力元を決定(`:77-81`)
3. `C.LENGTH_UNSET` を返す(`:87`)

```kotlin
// TsReadExDataSource.kt:71-88
try {
    handle = nativeLib.openFilter(tsArgs)
} catch (e: Exception) {
    throw IOException("Failed to open native filter", e)
}

if (dataSpec.uri.scheme == "edcb") {
    edcbTunerLock.withLock { openEdcbStream(dataSpec.uri) }
} else {
    openHttpStream(dataSpec)
}

transferStarted(dataSpec)
opened = true

// ★ 核心: ExoPlayer の暴走する末尾シークを完全に封殺するため、常に LENGTH_UNSET を返す
return C.LENGTH_UNSET.toLong()
```

#### バッファ(`:40-42`)

```kotlin
private val inputBuffer  = ByteBuffer.allocateDirect(188 * 20000)   // 約3.76MB
private val tempArray    = ByteArray(188 * 20000)
private val outputBuffer = ByteBuffer.allocateDirect(188 * 30000)   // 約5.64MB
```

**1 インスタンスあたり約 9.4MB の Direct バッファを確保する。** シークのたびに `TsReadExDataSource` が作り直されるため(§3-3-4)、GC 圧が高い設計になっている。

#### 3-3-1. HTTP 経路 — `openHttpStream()`(`:90-121`)

```kotlin
if (dataSpec.position > 0) {
    setRequestProperty("Range", "bytes=${dataSpec.position}-")
}
requestHeaders.forEach { (name, value) -> setRequestProperty(name, value) }   // Cloudflare Access
```

- **404 は `FileNotFoundException` として区別**(`:106-108`)。呼び出し側(`VideoPlayerManager.kt:313-328`)が原因チェーンを辿ってリトライ対象から外し、「録画ファイルが見つかりません」を表示する。
- **`dataSpec.position == 0` のときだけ** `Content-Length` を `fileSizeBytesRef` に格納する(`:115-118`)。Range リクエスト時の `Content-Length` は残りサイズなので、全体サイズとして使えないため。
  → **したがって、初回 `open()` が position 0 でないと `fileSizeBytesRef` が 0 のままになり、SeekMap が常に position 0 を返す**(`VideoPlayerManager.kt:224-230`)。
- 読み出しは `BufferedInputStream(..., 188 * 50000)` = 約 9.4MB バッファ。

#### 3-3-2. EDCB TCP 経路 — `openEdcbStream()`(`:123-195`)

`edcb://ip:port/live?onid=..&tsid=..&sid=..` を受け、EDCB の独自 TCP バイナリプロトコル(ポート既定 4510)でチューナを開く。

コマンド定数(`:48-51`):

```kotlin
CMD_EPG_SRV_RELAY_VIEW_STREAM = 301
CMD_EPG_SRV_NWTV_ID_SET_CH    = 1073
CMD_EPG_SRV_NWTV_ID_CLOSE     = 1074
CMD_SUCCESS                   = 1
```

シーケンス:

1. `cleanupEdcbSessionSynchronous()`(`:133`)で前回の nwtvId セッションを閉じる。
2. **SetCh フェーズ**(`:135-161`): 26 バイトの LE ボディ(`size, ?, onid, tsid, sid, ?, nwtvId, ?`)を送り、`targetProcessId` を得る。10 秒間 1 秒間隔でリトライ。
3. **Relay フェーズ**(`:167-191`): 別ソケットで `targetProcessId` を送り、成功したらそのソケットが TS ストリームになる。同じく 10 秒リトライ。
4. `BufferedInputStream(socket, 188 * 30000)` として保持(`:194`)。

`nwtvId` は 500〜10000 を巡回するプロセス内カウンタ(`:54-55`, `:58-63`)で、**`edcbTunerLock`(`ReentrantLock`)により同時に 1 本しかチューナ操作を行わない**ようになっている(`:78`)。2画面モードで 2 本同時に開く際の競合対策。

`close()` 時のセッション終了は**非同期**(`cleanupEdcbSessionAsynchronous`, `:213-215`)。`lastCloseRequestTime` による 1 秒デバウンス(`:198-199`)もある。

#### 3-3-3. `read()`(`:242-267`) — push/pop ループ

```kotlin
while (total < length) {
    val processed = nativeLib.popDataBuffer(handle, outputBuffer, length - total)
    if (processed > 0) {
        outputBuffer.position(0)
        outputBuffer.get(buffer, offset + total, processed)
        total += processed
    } else {
        val readCount = input.read(tempArray)
        if (readCount == -1) return if (total > 0) total else C.RESULT_END_OF_INPUT
        if (readCount > 0) {
            inputBuffer.clear()
            inputBuffer.put(tempArray, 0, readCount)
            nativeLib.pushDataBuffer(handle, inputBuffer, readCount)
        }
    }
}
```

**「要求された `length` を満たすまでブロックする」**実装であることに注意。Media3 の `DataSource.read()` は「1 バイト以上返せば良い」契約なので、これは仕様より厳しい。ライブでストリームが止まると `read()` がハングする可能性がある(実際には EDCB ソケットの `soTimeout = 15000` が効く)。

また、`InputStream` から `tempArray`(ByteArray)へ読み、それを Direct `ByteBuffer` にコピーしてから JNI に渡すため、**チャンクごとに 1 回の余分なメモリコピー**が発生している。

#### 3-3-4. 【設計上の課題】シークのたびにネイティブフィルタが作り直される

Media3 の `ProgressiveMediaPeriod` はシークのたびに `DataSource.open()` を呼び直す。本クラスは `open()` の先頭(`:72`)で無条件に `nativeLib.openFilter()` を呼ぶので、**シーク1回につき `CServiceFilter` が丸ごと新品になる**。

失われる状態:

- `m_pat` / `m_pmtPsi` — PAT/PMT を受信し直すまで**何も出力できない**
- `m_videoPid` / `m_audio1Pid` / `m_audio2Pid` / `m_pcrPid` — すべて 0 に戻る
- `m_audio1PtsPcrDiff`(0 に戻る) / `m_audio2PtsPcrDiff`(-1 に戻る) — **PTS-PCR の学習がリセットされる**
- `m_isAudio1DualMono` — false に戻る
- `m_lastPat` / `m_lastPmt` — version_number が 1 から振り直しになる
- `unitSize` / `residualBuffer` / `outputQueue`

さらに `VideoPlayerManager.kt:144-196` の匿名 `DataSource.Factory` は `open()` のたびに `TsReadExDataSource` を**新しく生成**しているため、約 9.4MB の Direct バッファも毎回確保し直される。

実害:

1. **復帰までの待ち時間** — PAT/PMT の送出周期(数百 ms 分の生データ)を読むまで映像も音声も出ない。
2. **合成無音の PTS が誤る可能性** — §5-3。
3. **`version_number` の振り直し** — ExoPlayer 側でトラック再構築が起きうる。

対処案は設計書 §4-B / §5 に整理されている(`ExportState`/`ImportState`、あるいはフィルタハンドルの持ち回し)。**本書の時点では未実装。**

---

## 4. `VideoPlayerManager.kt` の SeekMap 注入

### 4-1. なぜ自前 SeekMap が必要になったのか

`TsReadExDataSource.open()` が `C.LENGTH_UNSET` を返すため、`TsExtractor` は以下の連鎖で **`SeekMap.Unseekable` を出力する**:

1. `TsExtractor.read()` の `canReadDuration = inputLength != C.LENGTH_UNSET && !isModeHls` が false
2. → `TsDurationReader` が走らない → `getDurationUs()` が `TIME_UNSET`
3. → `maybeOutputSeekMap()` が `output.seekMap(new SeekMap.Unseekable(...))` を出す
4. → **Media3 が本来持っている `TsBinarySearchSeeker`(PCR ベースの二分探索シーカー)が生成されない**

つまり「PCR ベースの正確なシーク」機能は Media3 に存在するのに、DataSource が長さを隠しているせいで死んでいる。`VideoPlayerManager.kt` の自前 SeekMap はその穴埋め。

> Media3 のソースツリー(`local_repo/androidx/media3/*/1.7.1-komorebi/*.aar` の元)は本リポジトリに含まれていないため、上記の行番号は設計書の調査結果に依拠している。ビルド運用が不明なのは設計書のリスク項2として挙げられている。

### 4-2. 実装箇所(`VideoPlayerManager.kt:198-261`)

```kotlin
// VideoPlayerManager.kt:198-207
// ★ 核心: ExoPlayer の Extractor をラップし、自前の SeekMap を強制注入する
val programDurationUs = ((program?.recordedVideo?.duration ?: 0.0) * 1_000_000.0).toLong()

val customExtractorsFactory = ExtractorsFactory {
    val defaultExtractors = DefaultExtractorsFactory().apply {
        setTsExtractorFlags(FLAG_ALLOW_NON_IDR_KEYFRAMES or FLAG_DETECT_ACCESS_UNITS)
        setTsExtractorTimestampSearchBytes(2 * 1024 * 1024)
        setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
        setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
    }.createExtractors()
```

`isEdcbDirect && programDurationUs > 0L` のときだけ(`:210`)、配列内の `TsExtractor` を匿名 `Extractor` でラップする(`:214-256`)。ラッパは `init(output)` だけを差し替え、`ExtractorOutput.seekMap()` を横取りする:

```kotlin
// VideoPlayerManager.kt:216-246
override fun init(output: ExtractorOutput) {
    extractor.init(object : ExtractorOutput by output {
        override fun seekMap(seekMap: SeekMap) {
            // TsExtractor が算出したエラーの SeekMap を無視し、独自の高精度マップを注入
            val customSeekMap = object : SeekMap {
                override fun isSeekable() = true
                override fun getDurationUs() = programDurationUs
                override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                    val size = fileSizeBytesRef.get()
                    if (size <= 0L) return SeekMap.SeekPoints(SeekPoint(timeUs, 0L))
                    val safeTime = timeUs.coerceIn(0L, programDurationUs)
                    // 時間とファイルサイズから、HTTP Range の要求バイトオフセットを正確に計算する
                    val position = (safeTime.toDouble() / programDurationUs * size).toLong()
                    return SeekMap.SeekPoints(SeekPoint(safeTime, position))
                }
            }
            output.seekMap(customSeekMap)
        }
    })
}
```

`ExtractorOutput by output` という Kotlin のインターフェース委譲を使い、`seekMap()` 以外(`track()`, `endTracks()`)はそのまま透過させている。

### 4-3. なぜ `(時刻 / 総時間) × ファイルサイズ` の線形補間なのか

**この式を選んだ理由は「他に使える情報が無いから」に尽きる。**

- Media3 の `TsBinarySearchSeeker` は §4-1 の理由で生成されない。
- 生ファイルの内容を事前スキャンする仕組みがアプリ側に存在しない。
- 使える数値は「EDCB が持つ番組の `duration`」と「HTTP の `Content-Length`」の 2 つだけ。

この 2 つから位置を出すには、**ビットレートが一定であると仮定する**しかない。

### 4-4. 線形補間の精度上の限界

放送 TS は **VBR(可変ビットレート)**である。特に:

- **番組本編と CM でビットレートが変わる**(CM は動きが激しく高ビットレートになりやすい)。
- **EDCB の「サービス絞り録画」**では不要サービスのパケットが物理的に取り除かれるので、単位時間あたりのバイト数が大きく変動する。
- ドロップやスクランブル解除失敗があると、その区間のバイト数が落ちる。
- 録画の開始/終了マージン(番組前後の余白)が `duration` に含まれるかどうかで、時間軸の原点もずれる。

結果として、**位置誤差は数秒オーダーになりうる**。実測値は取得されていない(設計書 §7-1 の `--compare-linear` で計測する計画がある)。

加えて構造的な問題として:

- `SeekPoint.position` は**生ファイル空間**の値だが、`ExtractorInput.getPosition()` は `dataSpec.position + 読んだフィルタ後バイト数` という**混在空間**の値になる。Media3 内部のバイト演算(リトライ時の `positionHolder.position` 再オープン等)はこの混在空間で行われるため信用できない。
- `fileSizeBytesRef` が 0 のとき(= 初回 `open()` が position 0 でなかったとき)は**常に position 0 を返す**。

### 4-5. 「隠れシーク」— 音声トラック切替が SeekMap を経由してしまう

`VideoPlayerManager.kt:91-119` の `applyAudioSelectionAndMatrix` は `TrackSelectionOverride` でトラックを切り替える。`LaunchedEffect(vs.currentAudioMode)`(`:369-371`)と `onTracksChanged`(`:297-299`)から呼ばれる。

このとき Media3 内部で以下が起きる:

```java
// ProgressiveMediaPeriod.discardBuffer() (ProgressiveMediaPeriod.java:389 付近)
sampleQueues[i].discardTo(positionUs, toKeyframe, trackEnabledStates[i]);
//                                                 ↑ 非選択トラックは false = stopAtReadPosition
```

`stopAtReadPosition = false` だと、`SampleQueue.discardTo()` は **`readIndex` を進めてしまう副作用**がある。そのため次にトラック構成を変えたとき:

```java
// ProgressiveMediaPeriod.selectTracks() (ProgressiveMediaPeriod.java:337 付近)
seekRequired = sampleQueue.getReadIndex() != 0 && !sampleQueue.seekTo(positionUs, true);
```

が真になりやすく、`seekToUs()` → `pendingResetPositionUs` → `startLoading()` → **SeekMap 参照 → `DataSource.open()` 再実行**に至る。

つまり:

> **ユーザーは「音声を主→副に切り替えた」だけなのに、内部では「現在位置へのシーク」が発生し、不正確な線形補間 SeekMap を経由して数秒ずれた生バイト位置から読み直される。同時に `CServiceFilter` も新品になる。**

これが「EDCB 直接アクセスの録画再生で、音声トラックを切り替えると音がズレる」という報告の機構的な説明。**ライブ視聴では `open()` が1回きりなので再現しない。**

> 上記 Media3 の行番号は本リポジトリに無いフォークのソースに対するもので、設計書の調査結果からの引用。

### 4-6. `setTsExtractorTimestampSearchBytes(2 * 1024 * 1024)` は現状デッドコード

`VideoPlayerManager.kt:204`。`d049700`(2026-07-19)で「TSエクストラクタのタイムスタンプ探索範囲を2MBに拡大しシーク精度を改善」として入ったが、この値は `TsDurationReader` が使うもので、§4-1 の通り `LENGTH_UNSET` により `TsDurationReader` 自体が生成されない。**したがって効果が無い。**(設計書リスク項9)

---

## 5. 既知の設計上の課題

本節は 2026-08-24 時点の調査で確認された事項の整理。**解決策の検討は [`docs/design/ts_seek_index.md`](../design/ts_seek_index.md) 側にある。**

### 5-1. `open()` が常に `LENGTH_UNSET` を返す(すべての起点)

`TsReadExDataSource.kt:86-87`

```kotlin
// ★ 核心: ExoPlayer の暴走する末尾シークを完全に封殺するため、常に LENGTH_UNSET を返す
return C.LENGTH_UNSET.toLong()
```

**これは意図的で、かつ現状では正しい判断である。** 理由は「生ファイルのバイト空間」と「tsreadex フィルタ後のバイト空間」が混在するため:

| 値 | 空間 |
|---|---|
| `dataSpec.position` | **生ファイル空間**(HTTP Range にそのまま渡す) |
| `ExtractorInput.getPosition()` | `dataSpec.position + 読んだフィルタ後バイト数` = **混在空間** |

Media3 内部のバイト演算(`TsBinarySearchSeeker` の二分探索、`TsDurationReader` の末尾シーク、`ExtractingLoadable.load()` のリトライ時の再オープン位置)はすべて混在空間で行われるため、**構造的に信用できない**。正しい長さを渡すと Media3 が誤ったバイト位置を要求する。

したがって「シーク位置の解決を Media3 の外(自前 SeekMap)で、純粋な生ファイル空間で行う」という現在の方針は必然である。ただしその代償が §4-3〜4-4 の精度限界。

**副次的な既知バグ**: `ProgressiveMediaPeriod.configureRetry()` はロードエラー時に `true` を返し、混在空間の `positionHolder.position` で再オープンする。**ネットワーク瞬断のたびに生ファイル上のズレた位置へ飛ぶ**潜在バグが既にある(設計書 §0-2 / リスク項6)。

### 5-2. シークのたびに `CServiceFilter` が再生成される

§3-3-4 の通り。`TsReadExDataSource.kt:72` で無条件に `nativeLib.openFilter()` が呼ばれるため、PAT/PMT 解決状態・PID・PTS-PCR 学習値がすべてリセットされる。

### 5-3. 【要確認】`m_audio2PtsPcrDiff` が 0 でラッチされる可能性

> **これは「可能性」であり、断定ではない。実機での logcat 確認が別途必要。**(設計書 §0-3、リスク項1)

`servicefilter.cpp:133-137`:

```cpp
if ((m_audio2Mode == 1 || (m_audio2Mode == 3 && m_audio1Pid == 0)) && m_audio2Pid == 0 && !m_isAudio1DualMono) {
    if (m_audio2PtsPcrDiff < 0) {
        m_audio2PtsPcrDiff = m_audio1PtsPcrDiff;   // ← ここで一度だけラッチされる
    }
    AddAudioPesPackets(1, (m_pcr + m_audio2PtsPcrDiff) & 0x1ffffffff, m_audio2Pts, m_audio2PesCounter);
}
```

コードから読み取れる実行順序:

1. `AddPmt()`(`:340`)が `m_pcrPid` と `m_audio1Pid` を確定させる。
2. **次に来る PCR パケット**で上記ブロックが走る。この時点では実音声 PES がまだ 1 本も処理されていないため、`m_audio1PtsPcrDiff` は**コンストラクタ初期値の 0**(`servicefilter.cpp:34`)。
3. `m_audio2PtsPcrDiff` が **0 にラッチされる**。以後 `< 0` にならないため二度と更新されない。
4. 実音声の `m_audio1PtsPcrDiff` は後から `servicefilter.cpp:209` で正しい値(PTS − PCR ≒ +0.1〜0.5 秒)に学習される。

結果として想定される状態:

> **副音声(0x0111)側は PTS = PCR、主音声(0x0110)側は PTS = PCR + 0.1〜0.5 秒**という固定オフセット。

なお `m_audio2PtsPcrDiff` の初期値だけが `-1` で `m_audio1PtsPcrDiff` が `0` という非対称(`:34-35`)がこの挙動の直接の原因になっている。また `AddPmt()` の PID リセット処理(`:459-470`)は `*PtsPcrDiff` を触らないため、PMT 更新では回復しない。

**この経路に入る条件:**

- `m_audio2Mode == 1` — アプリの `-b 5`。**録画再生のみ**。ライブは `-b 4`(mode=0)なのでこのブロックに入らない。
- `m_audio2Pid == 0` — 副音声が実在しない番組。
- `!m_isAudio1DualMono` — デュアルモノでない。

**そして `CServiceFilter` は `TsReadExDataSource.open()` のたびに新規生成される**(`TsReadExDataSource.kt:72`)ため、この「未学習期間」が**シークのたびに再発する**。ライブは `open()` が 1 回きり。**報告されている「ライブでは出ない / 録画で音声切替時に出る」という現象と整合する。**

### 5-4. Media3 パッチ版 `DefaultAudioSink.java` の独自パッチ

`local_repo/androidx/media3/*/1.7.1-komorebi/*.aar` に含まれるフォークには Komorebi 独自のパッチが当たっている(`DefaultAudioSink.java:1000-1018` 付近):

```java
if (!startMediaTimeUsNeedsSync
    && Math.abs(expectedPresentationTimeUs - presentationTimeUs) > 200000) {
    // (onAudioSinkError はコメントアウト済み)
    android.util.Log.w("DefaultAudioSink", "Ignoring audio discontinuity. expected: " ...);
    startMediaTimeUsNeedsSync = true;
}
```

**200ms 未満の PTS 不連続は完全に無視され、AudioSink は古いタイムラインを維持し続ける。** つまりそのオフセットが恒久的な音ズレとして残る。§5-3 の 0.1〜0.5 秒という値は、ちょうどこの閾値をまたぐ範囲にある。

> **確認方法(最優先)**: 音声切替直後の logcat に `DefaultAudioSink: Ignoring audio discontinuity` が出るか、および `expected` と `got` の差が何 μs か。出ていれば **SeekMap の精度を上げても音ズレは直らない**ことになる。

### 5-5. 音声トラック切替時の「隠れシーク」

§4-5 に詳述。`ProgressiveMediaPeriod.selectTracks()` / `discardBuffer()` における `SampleQueue.discardTo(..., stopAtReadPosition=false)` の副作用が起点。

### 5-6. その他の細かい既知事項

| 事項 | 該当箇所 |
|---|---|
| `outputQueue` が 8MB を超えると出力を黙って捨てる | `native-lib.cpp:108-110` |
| `read()` が要求 `length` を満たすまでブロックする(Media3 の契約より厳しい) | `TsReadExDataSource.kt:247-264` |
| `TsReadExDataSource` 1 個につき Direct バッファ約 9.4MB。シークごとに再確保 | `TsReadExDataSource.kt:40-42` |
| `fileSizeBytesRef` は初回 `open()` が position 0 のときにしか設定されない | `TsReadExDataSource.kt:115-118` |
| `setTsExtractorTimestampSearchBytes(2MB)` はデッドコード | `VideoPlayerManager.kt:204` |
| `traceb24` が生成されるだけで一度も使われない | `native-lib.cpp:26` |
| `processDataBuffer` JNI が未使用 | `NativeLib.kt:16-21` |
| `handle` の生存期間がスレッド間で同期されていない | `TsReadExDataSource.kt:286-288` |
| `program.channel.serviceId` が取れないと `-n -1`(PAT 先頭)にフォールバック | `VideoPlayerManager.kt:160-161` |

---

## 6. 診断ツール `tools/ts_pmt_monitor/`

録画 TS の **PMT(音声トラック等の PID 構成)が時間経過でどう変化したか**を調べる CLI。詳細は [`tools/ts_pmt_monitor/README.md`](../../tools/ts_pmt_monitor/README.md)。

**作成の動機**: 「番組の途中から副音声が始まる」といったケースを、**実機や TS ファイル本体をユーザーから受け取らずに**調査するため。出力されるのは PID 構成とタイムスタンプ等のメタ情報のみで、映像・音声データは一切出力・保存しない。

**設計上の要点:**

- Android NDK / 実機 / エミュレータ不要。CMake と一般的な C++ コンパイラだけでビルドできる(`tools/ts_pmt_monitor/CMakeLists.txt`)。
- **アプリ本体の `app/src/main/cpp/servicefilter.cpp` / `util.cpp` / `aac.cpp` / `huffman.cpp` をそのまま参照している**(ツール専用のコピーを持たない)。つまり**実際の Komorebi が解決する PID 構成と完全に一致する**結果が得られる。
- 既定値はアプリ本体と揃えてある(`main.cpp:31-32`):
  ```cpp
  constexpr int kDefaultAudio1Mode = 13;   // -a 13
  constexpr int kDefaultAudio2Mode = 5;    // -b 5
  ```
  → **録画再生経路(`VideoPlayerManager.kt:163-166`)と同じ条件**で診断される。
- `--service-id` 省略時は `SetProgramNumberOrIndex(-1)` = PAT 先頭のプログラム(`main.cpp:144-145`)。
- 4MB チャンクで読み、`resync_ts()` で unit_size を決めてから 1 パケットずつ `AddPacket()` に流す。PID 構成が変化した時点だけをログ出力する。

このツールを可能にするため、`servicefilter.hpp:21-30` に**読み取り専用アクセサ**が追加されている:

```cpp
// ★ 診断ツール(tools/ts_pmt_monitor)向けに追加した読み取り専用アクセサ。
// AddPmt() が解決した現在のPID構成を外部から観測するためのもので、
// 通常のフィルタ動作(AddPacket/AddPmt)には一切影響しない。
int GetVideoPid() const; int GetAudio1Pid() const; int GetAudio2Pid() const;
int GetCaptionPid() const; int GetPcrPid() const; int64_t GetPcr() const;
bool IsAudio1DualMono() const;
```

**これは上流 tsreadex に対する Komorebi 独自の追加である。** 上流と diff を取るときに引っかかるので覚えておくこと。

出力例(README より):

```
[変化 #2] byte=75388 (44.56%)  経過時間(PCR基準)=00:01:39.00
    video=0x0101 (257)  audio1=0x0102 (258)  audio2=0x0103 (259)  caption=(なし)  dualMono=false
```

> **将来の拡張**: 設計書 §5 では、このツールが出せる「PID 構成の変化点タイムライン」をシーク索引ファイル `.tsidx` に載せる案(PID Pinning)が提案されている。また `tools/ts_pmt_monitor/CMakeLists.txt` の「アプリ本体の cpp を直接参照する」方式は、設計書 §7 の新規ツール(`ts_synth_gen` / `ts_index_builder`)のテンプレートとして参照されている。

---

## 付録: クイックリファレンス

### 引数の対応表

| 引数 | 録画(`VideoPlayerManager.kt:163-166`) | ライブ(`LivePlayerViewModel.kt:654-669`, `:675-690`) | 効果 |
|---|---|---|---|
| argv[0] | `"tsreadex"`(ダミー) | なし | どちらでもパース可 |
| `-x` | `18/38/39` | `18/38/39` | EIT 系 PID を除外 |
| `-n` | `serviceId ?: -1` | `finalSid` / `channel.serviceId` | 対象サービス |
| `-a` | `13` | `13` | 主音声: mode1 + ステレオ化 + デュアルモノ分離 |
| `-b` | **`5`** | **`4`** | 副音声: **録画 mode1(常時) / ライブ mode0(実在時のみ)** |
| `-c` | `5` | `5` | 字幕: mode1 + 管理パケット挿入 |
| `-u` | `1` | `1` | 文字スーパー: mode1 |
| `-d` | `13` | `13` | ID3変換: 有効 + 5バイト挿入 + PTS単調化 |

### 主要ファイル一覧

| ファイル | 行数 | 概要 |
|---|---|---|
| `app/src/main/cpp/native-lib.cpp` | 178 | JNI ブリッジ |
| `app/src/main/cpp/servicefilter.cpp` | 988 | サービス抽出・PID正規化・音声トランスマックス |
| `app/src/main/cpp/servicefilter.hpp` | 105 | 同上のヘッダ(診断用アクセサ含む) |
| `app/src/main/cpp/util.cpp` | 157 | TS/PSI パース |
| `app/src/main/cpp/aac.cpp` | 813 | AAC トランスマックス |
| `app/src/main/cpp/huffman.cpp` | 383 | AAC ハフマン復号 |
| `app/src/main/cpp/id3conv.cpp` | 370 | 字幕 → ID3 |
| `app/src/main/java/.../util/TsReadExDataSource.kt` | 290 | Media3 DataSource + EDCB TCP |
| `app/src/main/java/.../util/TsReadExDataSourceFactory.kt` | 21 | ライブ用ファクトリ |
| `app/src/main/java/.../NativeLib.kt` | 27 | JNI 宣言 |
| `app/src/main/java/.../ui/video/player/VideoPlayerManager.kt` | 388 | 録画再生の ExoPlayer 構築 + SeekMap 注入 |
| `app/src/main/java/.../viewmodel/LivePlayerViewModel.kt` | 993 | ライブ視聴(TS 関連は :636-770) |
| `tools/ts_pmt_monitor/main.cpp` | — | PMT 変化診断 CLI |
