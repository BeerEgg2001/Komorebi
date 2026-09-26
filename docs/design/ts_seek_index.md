# PTS/PCRベース シーク索引 — 実装直前設計書

作成日: 2026-08-24
関連: 既知課題2(チャプタースキップ時のシーク位置ズレ)、EDCB直接再生の音声トラック切替時の音ズレ調査([[komorebi-audio-desync-investigation-20260823]])
設計担当: Plan agent (Opus) — ユーザー依頼によりOpusモデルで実施

---

まず、調査で新たに判明した**設計を左右する事実**を3つ先に出す。これらは前提を一部修正する。

## 0. 設計前に確定させた追加事実（重要）

### 0-1. `TsReadExDataSource.open()` が `LENGTH_UNSET` を返すことが、すべての起点

`app/src/main/java/com/beeregg2001/komorebi/util/TsReadExDataSource.kt:87`

```kotlin
// ★ 核心: ExoPlayer の暴走する末尾シークを完全に封殺するため、常に LENGTH_UNSET を返す
return C.LENGTH_UNSET.toLong()
```

`TsExtractor.read()`（`androidx/media3/extractor/ts/TsExtractor.java:429`）は

```java
boolean canReadDuration = inputLength != C.LENGTH_UNSET && !isModeHls;
```

なので `LENGTH_UNSET` だと `TsDurationReader` が一切走らず、`durationReader.getDurationUs()` が `TIME_UNSET` のまま → `maybeOutputSeekMap()`（同 539行）が `output.seekMap(new SeekMap.Unseekable(...))` を出す → **Media3が本来持っている `TsBinarySearchSeeker`（＝PCRベースの二分探索シーカー）が生成されない**。

つまり「PCRベースの正確なシーク」機能はMedia3に既に存在するが、DataSourceが長さを隠しているため死んでいる。`VideoPlayerManager.kt` の自前SeekMapはその穴埋めである。

### 0-2. だが `TsBinarySearchSeeker` を復活させる案は**採用できない**（バイト空間の不整合）

`TsReadExDataSource` は「生ファイルのバイト位置」を `Range` ヘッダで受け取り、「tsreadexフィルタ後のバイト列」を返す。したがって：

- `dataSpec.position` … **生ファイル空間**
- `ExtractorInput.getPosition()` = `dataSpec.position + 読んだフィルタ後バイト数` … **混在空間**

Media3内部のバイト演算（`TsBinarySearchSeeker` の二分探索、`TsDurationReader` の末尾シーク、`ExtractingLoadable.load()` のリトライ時 `positionHolder.position` 再オープン）はすべてこの混在空間で行われるため**構造的に信用できない**。

→ **結論: シーク位置の解決はMedia3の外（自前SeekMap）で、純粋な生ファイル空間で行う。この設計方針は必然であり、正しい。**

なお副次的に、`ProgressiveMediaPeriod.configureRetry()`（`ProgressiveMediaPeriod.java:930`付近）はロードエラー時 `true` を返して同じ `positionHolder.position`（混在空間）で再オープンするため、**ネットワーク瞬断のたびに生ファイル上のズレた位置へ飛ぶ潜在バグが既にある**。これは索引とは別件だが、同じ根から出ている（リスク項6）。

### 0-3. 音ズレの真因が索引精度**ではない**可能性が高い（要確認・最重要フラグ）

`app/src/main/cpp/servicefilter.cpp:133-137`

```cpp
if ((m_audio2Mode == 1 || ...) && m_audio2Pid == 0 && !m_isAudio1DualMono) {
    if (m_audio2PtsPcrDiff < 0) {
        m_audio2PtsPcrDiff = m_audio1PtsPcrDiff;   // ← ここで一度だけラッチされる
    }
    AddAudioPesPackets(1, (m_pcr + m_audio2PtsPcrDiff) & 0x1ffffffff, m_audio2Pts, m_audio2PesCounter);
}
```

実行順序を追うと：

1. `AddPmt()` が `m_pcrPid` と `m_audio1Pid` を確定
2. **次のPCRパケット**で上記が走る。この時点では実音声PESはまだ1本も処理されていないため `m_audio1PtsPcrDiff` は**コンストラクタ初期値の 0**（`servicefilter.cpp:34`）
3. `m_audio2PtsPcrDiff` は 0 に**永久ラッチ**（以後 `< 0` にならないため二度と更新されない）
4. 実音声の `m_audio1PtsPcrDiff` は後から `servicefilter.cpp:209` で正しい値（PTS−PCR ≒ +0.1〜0.5秒）に学習される

結果、**副音声(0x0111)側は PTS = PCR、主音声(0x0110)側は PTS = PCR + 0.1〜0.5秒** という固定オフセットが生じる。

さらに Media3 パッチ版の `DefaultAudioSink.java:1000-1018` は Komorebi 独自パッチが当たっており：

```java
if (!startMediaTimeUsNeedsSync
    && Math.abs(expectedPresentationTimeUs - presentationTimeUs) > 200000) {
    // (onAudioSinkError はコメントアウト済み)
    android.util.Log.w("DefaultAudioSink", "Ignoring audio discontinuity. expected: " ...);
    startMediaTimeUsNeedsSync = true;
}
```

**200ms 未満の PTS 不連続は完全に無視され、AudioSink は古いタイムラインを維持し続ける＝そのオフセットが恒久的な音ズレとして残る。** 上記の 0.1〜0.5秒 という値はちょうどこの閾値をまたぐ範囲。

そして重要なのは、**`CServiceFilter` は `TsReadExDataSource.open()` のたびに新規生成される**（`TsReadExDataSource.kt:72`）ため、この「未学習期間」がシークのたびに再発すること。ライブ視聴は `open()` が1回きりなので再発しない — **報告されている「ライブでは出ない／録画で音声切替時に出る」という現象と完全に一致する。**

> **→ ユーザー確認事項（最優先）**: 音声切替直後の logcat に `DefaultAudioSink: Ignoring audio discontinuity` が出ていないか。出ていれば**索引を正確にしても音ズレは直らない**。この場合、後述の「フィルタ状態の持ち越し（§4-B）」が本命の修正になる。

---

## 1. 索引の構築方式

### 1-1. 4段構えのティア構成

単一方式では「サーバ側ツール未導入ユーザー」と「15GBファイル」の両立ができないため、**同一の `TsSeekIndex` オブジェクトに4つの供給源をマージする**設計にする。

| Tier | 供給源 | コスト | 精度 | 有効条件 |
|---|---|---|---|---|
| **0** | 再生中に流れるバイトから実測（PCR観測） | **ゼロ**（既に読んでいるデータ） | 完全 | 常時。**再生済み区間のみ** |
| **1** | サーバ側生成のサイドカー `.tsidx` | HTTP 1リクエスト・約30〜90KB | 完全（1秒グリッド） | ツール導入済み |
| **2** | 端末からのスパースRangeプローブ＋オンデマンド二分探索 | 初期64MB/約2〜5秒 → 以後1シーク約100ms | 精密化後 <0.5秒 | 常にフォールバック可 |
| **3** | 現行の線形補間 | ゼロ | 最大±数秒 | 最終フォールバック（回帰防止） |

**Tier 0 が音声切替問題に対する決定打**である。隠れシークの目標時刻＝現在の再生位置＝必ず再生済み＝Tier 0 のアンカーが密に存在する区間、だから追加I/Oゼロで完全精度が出る。

### 1-2. Tier 0: 再生中の無償アンカー収集

`TsReadExDataSource.read()` は生バイトを `tempArray` に読み、`nativeLib.pushDataBuffer()` へ渡している。この生バイトを**同時に軽量PCRスキャナへも渡す**（PIDヘッダ4バイト＋アダプテーションフィールドのみを見るので数ns/パケット、16Mbpsで約1万パケット/秒 → CPU負荷は無視可能）。

スキャナは `(pcr90k, rawByteOffset)` のペアを、生成のたびではなく **PCR値が前回記録から1秒以上進んだときだけ** 蓄積する（メモリ上限は自然に `duration秒 × 12バイト` = 2時間で86KB）。

`rawByteOffset` は「`open()` 時の `dataSpec.position` + 当該pushバッファ先頭までの累積生バイト数 + バッファ内オフセット」でネイティブ側が正確に計算する（Kotlin側でチャンク境界を推測しない）。

### 1-3. Tier 1: サイドカー `.tsidx`（サーバ側全走査）

`KomorebiThumbnailer` の `.tile.webp` / `.tile.json` と完全に同じ配布モデル。`KomorebiConfigurator/komorebi_resolver.lua:72-79` は

```lua
"video_url": "%s",
"tile_image_url": "%s.tile.webp",
```

と `baseUrl` に拡張子を足すだけなので、**v1では resolver.lua を変更せずクライアント側で `videoUrl + ".tsidx"` を組み立てれば取得できる**（Phase 2 で `index_url` を追加して正式化）。

**全走査コスト試算**（地デジ/BS ≒ 16Mbps ⇒ 1時間 ≒ 7.2GB。15GB ≒ 2時間番組）:

| 環境 | 15GBの走査時間 |
|---|---|
| HDD 150MB/s（サーバローカル） | 約100秒 |
| SATA SSD 500MB/s | 約30秒 |
| NVMe 2GB/s | 約8秒（CPUがボトルネック: 約80Mパケット × 約2ns = 0.2秒なので実質I/O律速） |
| **端末からHTTP経由**（Wi-Fi5実効45MB/s） | **約5.5分 ← 却下** |

→ **全走査はサーバ側でのみ行う。**録画終了後バッチ（`PostRecEnd.bat`）で `.tile.webp` と同時に生成すれば、ユーザー体感コストはゼロ。

### 1-4. Tier 2: 端末側スパースプローブ＋オンデマンド精密化

**初期プローブ**: ファイルを N 等分し、各点で `Range: bytes=X-X+262143`（256KB）を取得して最初のPCRを読む。

- 256KBの根拠: ARIB規定でPCR間隔は最大100ms。20Mbpsでも 100ms = 250KB。**256KBなら必ず1個以上のPCRを含む。**
- N=256 のとき転送量 64MB、LAN実効45MB/s で転送1.5秒＋RTT 256×15ms=3.8秒 → 逐次で約5秒、4並列で約2秒。
- 2時間番組で N=256 ⇒ アンカー間隔28秒。この区間内の線形補間誤差は、シーン変化による局所ビットレート変動次第で最悪±5秒程度になり得る。**これ単体では不十分。**

**オンデマンド精密化**: 目標時刻 t を挟む2アンカー間で、追加のRangeプローブによる二分探索を4〜6回。1回あたり RTT 15ms + 256KB転送5.7ms ≒ 21ms ⇒ **約100〜130ms で誤差 <0.5秒**。発見したアンカーは索引にマージされるので、同じ区間への再シークは即答になる。

**重要な実装制約**: `SeekMap.getSeekPoints()` は `ProgressiveMediaPeriod.startLoading()`（`ProgressiveMediaPeriod.java:911`）からプレイバックスレッドで同期呼び出しされる。**ここでネットワークをブロックしてはならない。**

対策:
- `getSeekPoints()` は**常に即座に現時点のベストアンサーを返す**（ブロックしない）。同時に、その時刻周辺の非同期精密化ジョブを投げる。
- ユーザー操作起点のシーク（シークバー / チャプタースキップ）は、`player.seekTo()` を呼ぶ**前に** `TsSeekIndexLoader.ensureRefined(timeUs, timeoutMs = 300)` を await する（既存のバッファリングスピナーの裏に隠れる）。
- 隠れシーク（音声切替）は Tier 0 が担保するので精密化不要。

---

## 2. 索引フォーマットと保存/キャッシュ戦略

### 2-1. `.tsidx` バイナリフォーマット v1

**1秒固定グリッドの直接アドレス表**にする。時刻を格納する必要がなく、二分探索も不要（O(1)ルックアップ）。

```
オフセット サイズ  フィールド
  0        4   magic "TSIX"
  4        2   formatVersion = 1
  6        2   flags (bit0: 不連続あり, bit1: PID構成テーブルあり)
  8        8   fileSize          … 生ファイルサイズ。キャッシュ無効化キー
 16        4   unitSize          … 188 または 192
 20        4   gridIntervalMs    … = 1000
 24        8   firstPcr90k       … 最初に観測したPCR(33bit)
 32        8   lastPcr90k
 40        8   durationUsMeasured… (lastPcr-firstPcr) をusに換算
 48        8   ptsPcrBiasUs      … firstPts − firstPcr（診断用。§3-3参照）
 56        4   pcrPid, 60: videoPid, 64: audio1Pid, 68: audio2Pid（初期値）
 72        4   programNumber
 76        4   gridCount
 80        4   pidTimelineOffset … PID構成タイムライン(将来用)へのオフセット。v1では0
 84        4   pidTimelineLength
 88     4*gridCount   uint32 packetIndex[]  (LE)
 末尾      4   crc32（先頭からの全バイト）
```

- `packetIndex[i]` の意味: **「経過時刻が `i` 秒**以下**の最後のPCRパケット」のパケット番号（＝floorアンカー）**。生バイト位置は `packetIndex[i] * unitSize`。決して遅い位置に着地しない（安全側）。
- `uint32` で足りる根拠: 15GB/188 = 8000万パケット。上限 42.9億パケット ≒ 806GB まで表現可能。
- サイズ: 2時間 = 7200エントリ × 4B = **28.8KB**、6時間で86KB。gzip転送すればさらに小さい。
- 単調性: 構築時に強制単調化し、非単調点（PCR不連続 / 録画結合）は `flags` bit0 と将来の PID タイムラインに記録。

**`pidTimelineOffset` の予約は意図的**。`tools/ts_pmt_monitor` が既に出せる「PID構成の変化点タイムライン」をここに載せると、PID Pinning 設計（§5）がフォーマット変更なしで乗る。

### 2-2. 保存先とライフサイクル

| 層 | 場所 | 無効化条件 |
|---|---|---|
| メモリ | `AtomicReference<TsSeekIndex>`（再生セッション単位） | 画面破棄時 |
| 端末ディスク | `context.cacheDir/tsindex/{sha1(videoUrl)}_{fileSize}.tsidx` | ①`fileSize` 不一致 ②`formatVersion` 不一致 ③CRC不一致 ④LRUで合計32MB超過分を削除 |
| サーバ | 録画ファイル隣接の `<name>.ts.tsidx` | ツール側で `.ts` の更新日時/サイズと比較 |

`cacheDir` を使うのは `EpgRepository.kt:52` / `EdcbLiveRepository.kt:380` と同じ既存パターンに揃えるため。`fileSize` をファイル名に含めることで、録画ファイル差し替え時に自動で別キーになる（stale読み込み事故を構造的に排除）。

Tier 2 で構築した索引も同じ形式で `cacheDir` に保存する（次回同じ番組を再生したときプローブ不要）。

---

## 3. Media3への組み込み

### 3-1. 新規ファイル構成（既存ファイルへの変更を最小化）

すべて新規パッケージ `com.beeregg2001.komorebi.util.tsindex` に隔離：

```
app/src/main/java/com/beeregg2001/komorebi/util/tsindex/
  TsSeekIndex.kt            索引本体（immutable snapshot）。resolveBytePosition(timeUs): Long
  TsSeekIndexBuilder.kt     アンカーのマージ・単調化・スナップショット生成
  TsSeekIndexStore.kt       cacheDir 読み書き・LRU・CRC検証
  TsSeekIndexLoader.kt      サイドカー取得 → 失敗時スパースプローブ → 精密化(coroutine)
  TsIndexSeekMap.kt         SeekMap 実装（索引なし時は現行の線形補間に完全フォールバック）
  TsIndexExtractorsFactory.kt  ★ VideoPlayerManager.kt:198-261 をそのまま移設
  TsIndexNativeLib.kt       JNI宣言（NativeLib.kt は触らない）
```

```
app/src/main/cpp/
  tsindex.hpp / tsindex.cpp        ポータブル（JNI非依存）: PCRスキャナ・索引ビルダ・シリアライズ
  tsindex-jni.cpp                  JNIブリッジ（native-lib.cpp は触らない）
```

### 3-2. `VideoPlayerManager.kt` の差分（衝突ゼロを確認済み）

**削除**: 198〜261行（自前SeekMap注入ブロック、64行）
**追加**: 約8行

```kotlin
// 索引はロードスレッドとプレイバックスレッドの両方から触られるため AtomicReference で公開する
val seekIndexRef = remember { AtomicReference<TsSeekIndex?>(null) }
// ...
val customExtractorsFactory = createTsIndexExtractorsFactory(
    isEdcbDirect = isEdcbDirect,
    programDurationUs = programDurationUs,
    fileSizeBytesRef = fileSizeBytesRef,
    seekIndexRef = seekIndexRef,
)
```
＋ 索引ロードを起動する `LaunchedEffect(program)` 3〜4行。

**PR衝突の実測確認**:
- PR#100 の `VideoPlayerManager.kt` へのハンクは `@@ -4,13 / -50,6 / -71,7 / -81,6 / -343,22 / -386,4` → **198〜261 とは一切重ならない**（3行コンテキストを考慮しても余裕あり）。
- PR#103 は `VideoPlayerManager.kt` を変更していない（変更ファイル一覧に無し）。

> ⚠️ **課題文の前提を1点修正**: 「`native-lib.cpp` はどちらのPRとも無関係」は誤りで、**PR#100 は `native-lib.cpp`（1〜19行の include ブロック＋末尾）と `NativeLib.kt`（末尾）と `app/src/main/cpp/CMakeLists.txt` を変更している**。上記のとおり両ファイルを一切触らない構成にしてある。`CMakeLists.txt` だけは `TSREADEX_CORE_SRC`（5〜13行）に2行追加するが、PR#100 のハンクは `@@ -1,7` と `@@ -23,10` なので**その中間に入り衝突しない**。

### 3-3. `TsIndexSeekMap` の解決アルゴリズム

```
getSeekPoints(timeUs):
    idx = seekIndexRef.get()
    t   = timeUs.coerceIn(0, durationUs)          // durationUs は EDCB メタデータ値のまま（UI不変）
    if (idx == null || !idx.covers(t)) {
        return 線形補間フォールバック(t)             // 現行と完全同一の挙動
    }
    tAdj = max(0, t - PRE_ROLL_US)                 // PRE_ROLL_US = 500_000
    pos  = idx.resolveBytePosition(tAdj)           // 1秒グリッド直接参照＋区間内線形補間
    pos  = pos - (pos % idx.unitSize)              // パケット境界へ切り下げ
    return SeekMap.SeekPoints(SeekPoint(t, pos))   // 時刻は要求値をそのまま返す
```

**設計上の3つの決定と根拠**:

1. **`durationUs` は EDCB メタデータの値を変えない。** 索引の時間軸は「先頭PCRからの経過」、ExoPlayer の時間軸は「先頭PTSからの経過」で、両者は**定数バイアス `B = firstPts − firstPcr`（0〜500ms）だけずれる。スケールはずれない。** よって EDCB duration と実測 duration が食い違っていてもシーク精度には影響しない。シークバーやサムネイルタイルの表示も変わらないので UI 回帰リスクゼロ。

2. **必ず 500ms 手前に着地させる（PRE_ROLL）。** `SampleQueue`（`SampleQueue.java:632`）は `allSamplesAreSyncSamples`（＝音声）なら `startTimeUs` より前のサンプルを書き込み側で捨て、映像は `reset()` 後 `upstreamKeyframeRequired` により最初のキーフレームまで捨てる。つまり**早く着地するのは自己修復するが、遅く着地すると前方ジャンプとして必ず見える。** 上記の `B ≥ 0` も PRE_ROLL が吸収する。

3. **`SeekPoint.timeUs` は要求時刻をそのまま返す。** `ProgressiveMediaPeriod.startLoading()` は `first.position` だけをバイト位置に使い、`pendingResetPositionUs`（要求値）を `sampleQueue.setStartTimeUs()` に渡すため、時刻を書き換えると `getAdjustedSeekPositionUs()` の意味が崩れる。現行挙動を維持する。

### 3-4. スレッド安全性

`getSeekPoints()` はプレイバックスレッド、アンカー追加は Loader スレッド、プローブは IO ディスパッチャ。`TsSeekIndex` を **immutable にし、更新は `AtomicReference.set()` によるスナップショット差し替え**とする（コピーコストは 2時間で 29KB なので毎秒差し替えても無視できる）。

---

## 4. 音声切替時の「隠れシーク」への対処

### 判明している連鎖（再掲＋実装位置）

`ProgressiveMediaPeriod.discardBuffer()`（`ProgressiveMediaPeriod.java:389`）:
```java
sampleQueues[i].discardTo(positionUs, toKeyframe, trackEnabledStates[i]);
//                                                 ↑ 非選択トラックは false
```
→ 非選択トラックでも `readIndex` が進む → `selectTracks()`（同 337行）で
```java
seekRequired = sampleQueue.getReadIndex() != 0 && !sampleQueue.seekTo(positionUs, true);
```
が真になりやすい → `seekToUs()` → `pendingResetPositionUs` → `startLoading()` で SeekMap 参照 → **`TsReadExDataSource.open()` 再実行 → `CServiceFilter` 再生成**。

### 対処案の比較

| 案 | 内容 | 効果 | コスト/リスク | 推奨 |
|---|---|---|---|---|
| **A: 索引精度向上（本設計の主軸）** | Tier 0 により隠れシークが正しい位置に着地 | 位置ズレは解消。**§0-3の音ズレは解消しない可能性大** | 低。PR衝突なし | **必須（土台）** |
| **B: フィルタ状態の持ち越し** | 再オープン時に PID / `audio*PtsPcrDiff` / `isAudio1DualMono` を引き継ぐ | **§0-3 の音ズレを直接叩く。PID未解決期間も消える** | 中。変更は `servicefilter.*` / `TsReadExDataSource.kt` / 新JNIのみ＝**PR衝突ゼロ** | **本命** |
| **C: Media3 パッチ（隠れシーク自体を消す）** | `selectTracks` の `getReadIndex() != 0 &&` を削り、`seekTo()` の成否だけで判定 | 隠れシークが起きなくなる（バッファ内で解決） | フォーク再ビルド手順が不明（リスク項2）。1行だが検証負荷高 | 条件付き |
| **D: tsreadex 内で音声を差し替え** | ExoPlayer は常に 0x0110 固定。ネイティブ側で 0x0110 の中身を主/副で切替 | 切替がトラック選択を伴わない＝隠れシーク完全消滅。再オープンもなし | `VideoPlayerScreen.kt` / `applyAudioSelectionAndMatrix` の変更必須＝**PR衝突大** | **PRマージ後** |

### 4-B の具体設計（本命・今すぐ着手可能）

`servicefilter.hpp` に読み取り専用アクセサを追加済みの流れをそのまま延長する。

```cpp
// servicefilter.hpp に追加（既存の動作には一切影響しない）
struct ServiceFilterState {
    int videoPid, audio1Pid, audio2Pid, captionPid, superimposePid, pcrPid;
    uint8_t audio1StreamType, audio2StreamType;
    int64_t audio1PtsPcrDiff, audio2PtsPcrDiff;
    bool isAudio1DualMono;
    bool valid;
};
void ExportState(ServiceFilterState *s) const;
// シーク直後の新規フィルタに、直前のフィルタが学習済みの値を注入する。
// PAT/PMT を拾い直すまでの間、合成無音PESが誤ったPTSで出力されるのを防ぐ。
void ImportState(const ServiceFilterState &s);
```

`ImportState` は `m_audio1PtsPcrDiff` / `m_audio2PtsPcrDiff` を負でない学習済み値で初期化するため、`servicefilter.cpp:134` の `if (m_audio2PtsPcrDiff < 0)` ラッチが**発火せず**、副音声の合成無音が最初から正しいPTSで出る。

**さらに踏み込むなら（推奨）**: `TsReadExDataSource` が `openFilter()` をシークのたびに呼ぶのをやめ、**フィルタハンドルを `DataSource.Factory` のクロージャで持ち回して再利用する**。`fileSizeBytesRef: AtomicLong` を渡している既存パターン（`VideoPlayerManager.kt:142,170`）とまったく同じ方法で `filterHandleRef: AtomicLong` を追加でき、`VideoPlayerManager.kt` の差分は1〜2行で済む。新JNI:

```cpp
// ストリーム位置だけをリセットし、PID/PTS学習状態は保持する
JNIEXPORT void JNICALL ..._resetFilterStream(JNIEnv*, jobject, jlong handle);
```
（`residualBuffer.clear()` / `unitSize = 0` / `outputQueue.clear()` / PES蓄積バッファのクリアのみ）

**トレードオフ**: フィルタを持ち回すと、シーク先が「PID構成が変わった区間」だった場合に古いPIDを引きずるリスクがある。ただし `AddPmt()` は PMT を受信し次第すべて上書きするので、誤りは最大でも数百ms（PAT/PMT周期）で自動修復される。かつ実測データ（`ts_pmt_monitor` の3件解析）で「番組単位の録画ではPID構成はほぼ一定」が確認済みなので、実害はほぼない。

---

## 5. 課題1（シークのたびのフィルタ再作成）との関係整理

**結論: 索引精度とPID Pinningは直交しており、両方必要。**

- **索引** = 「どの生バイトから読み直すか」を決める
- **Pinning** = 「そのバイトから読み始めたとき、最初の出力から正しいPTS/PIDのストリームを出せるか」を決める

索引だけを正確にしても、再オープン直後の `CServiceFilter` は PAT/PMT を拾うまで（生データで最大200〜400KB ≒ 0.1〜0.2秒相当、ドロップがあればもっと）**何も出力できず、かつその間 §0-3 の誤PTS合成無音を吐く**。逆に Pinning だけ入れても、着地バイト位置が線形補間のままなら数秒ズレる。

**連携ポイント（今フォーマットを決める上で重要）**:

1. `.tsidx` ヘッダに `pidTimelineOffset/Length` を予約済み（§2-1）。ここに `ts_pmt_monitor` が既に出力している「PID構成の変化点（byte, pcr, video/audio1/audio2/caption PID, dualMono）」を載せる。
2. Pinning 実装時、シーク先時刻から「その区間で正しいPID構成」を索引から引いて `ImportState()` に渡せる → **PMTを待たずに1パケット目から正しくフィルタできる**。
3. これが入ると `openFilter()` の再作成コスト（数KBのアロケーション＝元々軽い）ではなく、**「復帰待ち時間（現状 数百ms〜数秒）」が消える**のが実利。

つまり**索引フォーマットは Pinning のための PID タイムライン置き場を兼ねる**。この一点だけは Phase 0 の段階でフォーマットに織り込んでおく必要がある（後付けだと `formatVersion` を上げてサイドカー再生成が必要になる）。

---

## 6. ネイティブ側（C++）の追加・変更（関数シグネチャレベル）

### 6-1. `app/src/main/cpp/util.hpp` / `util.cpp`（追記のみ・非破壊）

```cpp
// TSパケット1個からPCR(90kHz, 33bit)を取り出す。PCRが無ければ -1 を返す。
// 27MHz拡張(pcr_extension)は無視する。
int64_t extract_pcr(const uint8_t *packet);

// PCRの33bitラップアラウンドを考慮した差分(prev → curr)を返す。
int64_t pcr_diff(int64_t prev, int64_t curr);
```

### 6-2. `app/src/main/cpp/tsindex.hpp` / `tsindex.cpp`（新規・完全ポータブル）

```cpp
// ---- 軽量PCRスキャナ ----------------------------------------------------
// CServiceFilter を使わず、PAT/PMT解析(util.cpp)と PCR 抽出だけを行う。
// 映像/音声のリマックスを一切しないため、CPUコストは数ns/パケット。
struct TsAnchor {
    int64_t pcr90k;
    int64_t rawByteOffset;   // 生ファイル先頭からのバイト位置(パケット先頭)
};

class CTsPcrScanner {
public:
    CTsPcrScanner();
    // CServiceFilter と同じ意味（正: service_id、負: PAT内のN番目）
    void SetProgramNumberOrIndex(int n);
    // 記録間隔。この値(90kHz単位)以上PCRが進んだときだけアンカーを積む。既定 90000(=1秒)
    void SetAnchorIntervalPcr(int64_t interval);

    // データを逐次投入する。baseRawOffset は data[0] の生ファイル上のバイト位置。
    // 呼び出し間でパケット境界がまたがっても内部で持ち越す。
    void AddData(const uint8_t *data, int size, int64_t baseRawOffset);

    const std::vector<TsAnchor> &GetAnchors() const;
    void ClearAnchors();                 // Tier0 用: 取り出したら捨てる
    int  GetPcrPid()  const;
    int  GetVideoPid() const;
    int  GetAudio1Pid() const;
    int  GetAudio2Pid() const;
    int  GetUnitSize() const;            // 188 / 192 / 0(未確定)
    int64_t GetFirstPcr() const;
    int64_t GetLastPcr()  const;
    int64_t GetFirstPts() const;         // 最初に観測したPES PTS（ptsPcrBias算出用）
private:
    ...
};

// ---- 単発プローブ（Tier2 用の便宜関数）---------------------------------
// バッファ内の最初のPCRとその位置を返す。pcrPid < 0 ならPAT/PMTから自動解決を試みる。
bool ts_probe_first_pcr(const uint8_t *data, int size, int pcrPid,
                        int programNumberOrIndex, TsAnchor *out, int *outPcrPid);

// ---- 索引ファイル(.tsidx) ----------------------------------------------
struct TsIndexHeader {
    int32_t formatVersion, flags, unitSize, gridIntervalMs;
    int64_t fileSize, firstPcr90k, lastPcr90k, durationUsMeasured, ptsPcrBiasUs;
    int32_t pcrPid, videoPid, audio1Pid, audio2Pid, programNumber, gridCount;
    int32_t pidTimelineOffset, pidTimelineLength;
};

// アンカー列 → 1秒グリッドへ変換（floorアンカー・強制単調化）。
// 非単調点を検出したら flags に記録し、outDiscontinuityCount へ件数を返す。
bool ts_index_build_grid(const std::vector<TsAnchor> &anchors, int unitSize,
                         int gridIntervalMs, std::vector<uint32_t> *outGrid,
                         int *outDiscontinuityCount);

bool ts_index_serialize(const TsIndexHeader &hdr, const std::vector<uint32_t> &grid,
                        std::vector<uint8_t> *out);          // 末尾にCRC32を付与
bool ts_index_deserialize(const uint8_t *data, int size,
                          TsIndexHeader *hdr, std::vector<uint32_t> *grid); // CRC検証込み
```

### 6-3. `app/src/main/cpp/tsindex-jni.cpp`（新規）

`native-lib.cpp` を**一切触らない**ため、JNI クラス名は `com.beeregg2001.komorebi.util.tsindex.TsIndexNativeLib`。

```cpp
// スキャナのライフサイクル
JNIEXPORT jlong JNICALL ..._TsIndexNativeLib_openScanner(JNIEnv*, jobject, jint programNumberOrIndex);
JNIEXPORT void  JNICALL ..._TsIndexNativeLib_closeScanner(JNIEnv*, jobject, jlong handle);

// 生バイトを投入する。TsReadExDataSource が pushDataBuffer と同じバッファを渡す。
JNIEXPORT void  JNICALL ..._TsIndexNativeLib_scannerAddData(
        JNIEnv*, jobject, jlong handle, jobject directBuf, jint len, jlong baseRawOffset);

// 蓄積されたアンカーを (pcr90k, rawByteOffset) のペア列として long[] で取り出し、内部をクリアする。
// 戻り値の長さは 2 * アンカー数。
JNIEXPORT jlongArray JNICALL ..._TsIndexNativeLib_scannerPopAnchors(JNIEnv*, jobject, jlong handle);

// スキャナが把握しているメタ情報を int[]{unitSize, pcrPid, videoPid, audio1Pid, audio2Pid} で返す。
JNIEXPORT jintArray  JNICALL ..._TsIndexNativeLib_scannerGetInfo(JNIEnv*, jobject, jlong handle);

// Tier2 プローブ: 256KBのバッファから最初のPCRを探す。
// 戻り値 long[]{pcr90k, offsetInBuf, resolvedPcrPid} / 見つからなければ null。
JNIEXPORT jlongArray JNICALL ..._TsIndexNativeLib_probeFirstPcr(
        JNIEnv*, jobject, jbyteArray buf, jint len, jint pcrPidHint, jint programNumberOrIndex);

// .tsidx のパース（サイドカー/キャッシュ読み込み用）
// 戻り値 long[]: [0..N] にヘッダ値、以降にグリッド。失敗時 null。
JNIEXPORT jlongArray JNICALL ..._TsIndexNativeLib_parseIndex(JNIEnv*, jobject, jbyteArray data, jint len);
// Tier0/Tier2 で作った索引の保存用
JNIEXPORT jbyteArray JNICALL ..._TsIndexNativeLib_serializeIndex(
        JNIEnv*, jobject, jlongArray header, jintArray grid);
```

### 6-4. `servicefilter.hpp` / `servicefilter.cpp`（§4-B 用、Phase 1 後半）

```cpp
struct ServiceFilterState { /* §4-B 参照 */ };
void ExportState(ServiceFilterState *s) const;
void ImportState(const ServiceFilterState &s);
```

### 6-5. `native-lib.cpp` / `NativeLib.kt` / `CMakeLists.txt`

- `native-lib.cpp`: **変更なし**（PR#100 との衝突回避）。§4-B の `resetFilterStream` を入れる段階になったら PR#100 マージ後に追加する。
- `NativeLib.kt`: **変更なし**。
- `app/src/main/cpp/CMakeLists.txt`: `TSREADEX_CORE_SRC` に `tsindex.cpp` を、`add_library` に `tsindex-jni.cpp` を追加（PR#100 のハンク間に入るため衝突しない）。なお PR#100 は `CMAKE_CXX_STANDARD` を 11→17 に上げるので、**`tsindex.cpp` は C++11 の範囲で書いておく**（マージ前後どちらでもビルドできる）。

---

## 7. テスト計画

CLAUDE.md のとおり `app/src/test` は存在せず、テスト基盤の導入は別議論。よって **ホスト側CLIの自己テストとして完結させる**（Gradle にテスト基盤を追加しない）。`tools/ts_pmt_monitor` で実証済みの「`app/src/main/cpp` を直接参照する CMake プロジェクト」方式をそのまま踏襲する。

### 7-1. 新規ツール2本

**`tools/ts_synth_gen/`** — 合成VBR TS生成器
- PAT/PMT（program_number 指定可）、video PID 0x0100 (stream_type 0x1b)、audio PID 0x0110 (0x0f)
- PCR は video PID のアダプテーションに 50ms 間隔で埋め込み
- **VBRプロファイルを引数で指定**: `--bitrate-profile "4M:30,24M:30"` のように、30秒ごとに 4Mbps ⇔ 24Mbps を往復させる（NULLパケットで埋めず、単位時間あたりの出力パケット数そのものを変える＝EDCBのサービス絞り録画と同じ形）。これが線形補間にとって最悪ケース。
- PES に妥当な PTS/DTS、映像キーフレームを 0.5秒間隔
- オプション: `--pcr-start 0x1FFF00000`（ラップアラウンド誘発）、`--pcr-discontinuity 3600`（不連続注入）、`--pid-change 1800`（PMT差し替え）、`--unit-size 192`（M2TS）
- 生成と同時に **正解データ `<name>.truth.csv`（時刻, バイト位置）** を出力する ← これがテストのグラウンドトゥルース

**`tools/ts_index_builder/`** — 索引生成CLI ＋ `--selftest` ＋ `--verify`
- 通常: `.ts` → `.tsidx`
- `--verify <ts> <tsidx> [--truth <csv>]`: 全グリッドエントリについて、そのバイト位置から実際にPCRを読み直し誤差を検証。誤差のヒストグラム / 最大 / p95 を出力
- `--compare-linear`: 同一ファイルに対する**現行の線形補間の誤差分布**と索引の誤差分布を並べて出力
- `--selftest`: 下記のユニットテスト群を実行（CTest 連携）

### 7-2. テストケース一覧

| # | 分類 | 内容 | 合格基準 |
|---|---|---|---|
| T1 | 索引正確性 | 合成VBR(4M⇔24M/30秒)に対し全グリッド検証 | 全エントリで `0 ≤ (i秒 − 実PCR) < 1秒`（floor性） |
| T2 | **定量比較** | 同一ファイルで線形補間 vs 索引の誤差分布 | 線形の最大誤差を数値で記録。索引は最大 <1秒。**この数値が設計の妥当性証明になるので最初に取る** |
| T3 | スパースプローブ収束 | N=64/128/256 + 二分探索を模擬 | 二分探索5回以内で誤差 <0.5秒 |
| T4 | PCRラップ | `--pcr-start 0x1FFF00000` | グリッドが単調。duration が正しい |
| T5 | PCR不連続 | `--pcr-discontinuity` | 単調化される。`flags` bit0 が立つ。クラッシュしない |
| T6 | PID構成変化 | `--pid-change` | 索引が破綻しない。将来のPIDタイムラインに記録 |
| T7 | シリアライズ往復 | Serialize→Deserialize | 完全一致。CRC破損を検出。切り詰めファイルを拒否 |
| T8 | 境界値 | t=0 / t=duration / t>duration / 0バイト / PCR皆無 / PMT皆無 / 全部NULLパケット | 例外を出さず安全にフォールバック |
| T9 | **チャンク分割耐性** | `AddData` に 1バイト単位／187バイト単位／素数バイト単位で投入 | 一括投入時と `rawByteOffset` が完全一致（Tier0 の正しさの根幹） |
| T10 | unitSize 192 | M2TS 形式 | 188 と同じ精度 |
| T11 | フォールバック回帰 | `seekIndexRef = null` | 現行の線形補間と**完全にビット一致する位置**を返す |
| T12 | 大容量 | 15GB相当（スパースファイル or 生成）で走査 | メモリ使用が一定（ストリーミング処理であること）。時間を実測 |

### 7-3. 実機検証（Phase 1 完了時）

1. **チャプタースキップ精度**: スキップ実行後の `player.currentPosition` とチャプター時刻の差を logcat に出す。修正前後で比較。
2. **音声切替時の logcat**: `DefaultAudioSink: Ignoring audio discontinuity. expected: X, got: Y` の有無と `|X−Y|`。**§0-3 の仮説の可否がここで確定する。**
3. **リップシンク目視**: 音声切替後、口の動きと音声のズレ。
4. **再オープン回数**: `TsReadExDataSource.open()` にログを入れ、音声切替1回あたり何回 open されるかを計測（隠れシークの実在確認）。

---

## 8. 段階的な実装ステップ

### Phase 0 — PRマージ待ち不要 / アプリ本体に一切触らない（最優先）

1. `tools/ts_synth_gen/` 実装（合成VBR TS生成＋正解CSV）
2. `app/src/main/cpp/tsindex.hpp/.cpp` 実装（スキャナ＋グリッド構築＋シリアライズ）
   ＋ `util.hpp/.cpp` に `extract_pcr` / `pcr_diff` 追記
3. `tools/ts_index_builder/` 実装（`--selftest` / `--verify` / `--compare-linear`）
4. **T1〜T12 を通す**
5. **実録画3件に対し `--compare-linear` を実行し、「現行の線形補間の誤差は最大N秒」という実測値を得る**

> Phase 0 が終わった時点で、アプリのコードを1行も変えずに設計の妥当性が数値で証明される。**手戻り防止の観点でここが最重要。**

### Phase 1 — PRマージ待ち不要 / 既存ファイルへの変更は最小

6. `app/src/main/cpp/tsindex-jni.cpp` 追加 ＋ `app/src/main/cpp/CMakeLists.txt` に2行
7. `util/tsindex/` の Kotlin 6ファイルを新規作成
8. `TsReadExDataSource.kt` に Tier 0 アンカー収集を追加（両PRと無関係なファイル）
9. `VideoPlayerManager.kt`: 198〜261行を削除 → 呼び出し8行に置換
10. **実機検証 1〜4 を実施 → §0-3 の仮説を確定させる**
11. `servicefilter.hpp/.cpp` に `ExportState/ImportState` 追加（§4-B）※`servicefilter.*` は両PRと無関係
12. Tier 2（スパースプローブ＋オンデマンド精密化）を追加

### Phase 2 — PR#100 / #103 マージ後

13. `native-lib.cpp` に `resetFilterStream` 追加、`TsReadExDataSource` をフィルタ再利用方式へ（§4-B 後半）
14. サーバ側 `.tsidx` 生成の配布（`KomorebiThumbnailer` への統合 or 単体CLI）＋ `komorebi_resolver.lua` に `index_url` 追加 ＋ `KomorebiConfigurator` 対応
15. 案D（tsreadex 内での音声差し替え）— `VideoPlayerScreen.kt` / `applyAudioSelectionAndMatrix` を変更するため必ずマージ後
16. Media3 パッチ（案C）— フォーク運用手順が確定してから

### Phase 3 — 任意/将来

17. `.tsidx` v2: I-frame 索引（完全フレーム精度のチャプタースキップ）
18. `.tsidx` に PID構成タイムラインを載せ、PID Pinning と統合

**後回しにできるもの**: Tier 1（サーバ側サイドカー）は Tier 0 + Tier 2 で実用精度に達するため、実は最後でよい。逆に **Tier 0 が最も費用対効果が高く、最初に作るべき**。

---

## 9. リスク・未解決の疑問点（実装前にユーザー確認が必要）

| # | 重要度 | 内容 |
|---|---|---|
| **1** | **最高** | **音ズレの真因**（§0-3）。索引を正確にしても直らない可能性が高い。**確認依頼: 音声切替直後の logcat に `DefaultAudioSink: Ignoring audio discontinuity` が出るか、およびそのとき `expected` と `got` の差は何μsか。** また、問題が出る番組の副音声は「実在する第2音声（二カ国語）」か「デュアルモノ」か「実在しない（tsreadexの合成無音）」か。 |
| **2** | 高 | **Media3フォークのビルド運用が不明。** `local_repo/androidx/media3/*/1.7.1-komorebi/*.aar` をどう再生成しているのか（ソースツリーの所在、ビルド手順、CI）。案C（`selectTracks` 1行パッチ）を採るなら必須。 |
| 3 | 高 | **EDCB HTTPサーバ（civetweb）の Range 対応と同時接続耐性。** Tier 2 は 256回の Range リクエスト（最大4並列）を投げる。`ViewCount` 上限や civetweb の `num_threads` に抵触しないか実測が必要。抵触するなら並列度1・プローブ数128に落とす。 |
| 4 | 中 | **サイドカーツールの配布形態。** `KomorebiThumbnailer` に統合すべきか独立CLIか。既存ユーザーは `.tile.webp` すら未導入のケースがあるため、**Tier 2 が実質の主経路になる前提で設計している**が、その想定でよいか。 |
| 5 | 中 | **`getDurationUs()` を実測値に変えないでよいか。** 本設計では EDCB メタデータ値を維持し UI 回帰をゼロにしている。もし「録画冒頭が欠けていてシークバーの目盛りがズレる」という既知の不満があるなら方針を変える必要がある。 |
| 6 | 中 | **既存の潜在バグ**（§0-2）: `ProgressiveMediaPeriod.configureRetry()` がロードエラー時に混在バイト空間の `positionHolder.position` で再オープンする。ネットワーク瞬断のたびに生ファイル上のズレた位置に飛ぶ。索引とは別に対処するか、今回まとめて扱うか。 |
| 7 | 中 | **`.chapter.txt` の生成元と時間軸。** どのCM検出ツールが生成しているか。ファイル先頭起点（＝PCR軸）なら本設計と整合するが、放送時刻起点だと別途オフセットが要る。 |
| 8 | 低 | **192バイトTS（M2TS）の実在性。** `VideoPlayerManager.kt:157` が `.m2ts` を再生対象に含めている。EDCB直接再生で実際に192バイトTSが来るケースがあるか（フォーマットには `unitSize` を持たせて対応済み）。 |
| 9 | 低 | **`setTsExtractorTimestampSearchBytes(2 * 1024 * 1024)`（`VideoPlayerManager.kt:204`）は現状デッドコード。** `LENGTH_UNSET` により `TsDurationReader` も `TsBinarySearchSeeker` も生成されないため効果がない。削除してよいか（挙動は変わらないが、`d049700` のコミットメッセージが「シーク精度を改善」と述べているため、認識合わせが必要）。 |

---

## Critical Files for Implementation

- `app/src/main/java/com/beeregg2001/komorebi/ui/video/player/VideoPlayerManager.kt`（198〜261行の置換。PR#100 のハンクと非重複を確認済み）
- `app/src/main/java/com/beeregg2001/komorebi/util/TsReadExDataSource.kt`（Tier 0 アンカー収集、フィルタ再利用。両PRと無関係）
- `app/src/main/cpp/servicefilter.cpp` / `servicefilter.hpp`（`ExportState`/`ImportState`、および §0-3 の `m_audio2PtsPcrDiff` ラッチ。133〜137行・209行）
- `app/src/main/cpp/util.hpp` / `util.cpp`（`extract_pcr` / `pcr_diff` 追加。新規 `tsindex.hpp/.cpp` の土台）
- `tools/ts_pmt_monitor/main.cpp` および `CMakeLists.txt`（`tools/ts_synth_gen` / `tools/ts_index_builder` のテンプレート）
