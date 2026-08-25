# Komorebi アーキテクチャ詳細 (overview)

このドキュメントは、リポジトリルートの `CLAUDE.md` に書かれた高レベルなアーキテクチャ概要を前提とし、
「実際のコード上でそれがどう実現されているか」を具体的なクラス名・メソッド・データフローのレベルまで
掘り下げたものです。`CLAUDE.md` の内容（レイヤー構成、マルチバックエンド抽象化、動画再生エンジンが2種類ある点、
ネイティブ層の存在）は繰り返しません。

想定読者は、今後このコードベースを引き継ぐ開発者（人間または AI エージェント）です。

**基準リビジョン**: ブランチ `features/1.1.0-beta7` / コミット `acbf31a`（`versionName = "1.1.0-beta7"`）

**このドキュメントで扱わない領域**:
- MPEG-TS 処理層・tsreadex・ネイティブ C++ 層（`app/src/main/cpp/`）の詳細
  → `docs/architecture/ts_processing_layer.md` を参照してください。ここでは接点のみ軽く触れます。
- TS シーク index の設計 → `docs/design/ts_seek_index.md`

---

## 0. 全体像とパッケージ構成

パッケージルートは `com.beeregg2001.komorebi`（`applicationId` は `com.beeregg2001.Komorebi`、
大文字小文字が異なる点に注意）。Kotlin ソースは約 55,000 行。

```
app/src/main/java/com/beeregg2001/komorebi/
├── MainActivity.kt          単一 Activity。ナビゲーションは持たず MainRootScreen に丸投げ
├── MainApplication.kt       @HiltAndroidApp。Coil ImageLoader と WorkManager の初期化
├── NativeLib.kt             JNI 境界（tsreadex / servicefilter へのブリッジ）
├── common/                  AppStrings（文字列定数）, SafeFocus, UrlBuilder（URL 組み立ての集約点）
├── data/
│   ├── api/                 KonomiApi(Retrofit) / edcb(TCP バイナリ) / interceptor
│   ├── db/                  Room の TypeConverters
│   ├── jikkyo/               NX-Jikkyo / ニコニコ実況の WebSocket クライアント
│   ├── local/                Room（AppDatabase / dao / entity）
│   ├── mapper/                API モデル ⇔ Entity ⇔ ドメインモデルの変換
│   ├── model/                ドメイン/API モデル、StreamSource, BackendConfig 等
│   ├── repository/            Provider インターフェース群と各バックエンド実装
│   ├── sync/                RecordSyncEngine / EpgSyncEngine
│   ├── util/                EpgUtils
│   └── worker/               WorkManager の CoroutineWorker
├── di/                       Hilt モジュール 4 つ
├── extractor/                ※空ディレクトリ（過去の名残）
├── ui/                       Compose 画面。main / home / live / video / epg / reserve / setting / components / theme
├── util/                     AppUpdater, TsReadExDataSource, TitleNormalizer, WikipediaNormalizer 等
└── viewmodel/                @HiltViewModel 10 個
```

### 0.1 ナビゲーションは Navigation-Compose ではない

`MainActivity.kt:41` で `setContent { KomorebiTheme { MainRootScreen(...) } }` を呼ぶだけで、
`NavHost` / `NavController` は一切使われていません。画面遷移はすべて
`ui/main/MainRootState.kt` の `MainRootState`（`@Stable` な State Holder クラス）が持つ
`mutableStateOf` フラグの組み合わせで表現されます。

- タブ: `MainRootState.currentTabIndex`（`getVisibleTabs()` = ホーム / ライブ / ビデオ / 番組表 / 録画予約。
  `MainRootScreen.kt:120-124` で「お気に入り球団」設定がある場合のみ「プロ野球」タブが追加される）
- 全画面判定: `MainRootState.isFullScreen(...)`（`MainRootState.kt:128-145`）が
  `selectedChannel != null || selectedProgram != null || ...` を OR で畳み込んでタブバーの表示可否を決める
- 戻る操作: `BackHandler` + `MainRootState.canProcessBackPress()`（500ms の連打ガード）

この設計のため「画面」は URL/ルートを持たず、**新しい画面を追加するときは `MainRootState` にフラグを増やして
`MainRootScreen` の条件分岐に足す**というのが既存の流儀です。

### 0.2 ViewModel の生成場所

`MainActivity` が `by viewModels()` で 4 つ（`ChannelViewModel` / `EpgViewModel` / `HomeViewModel` /
`RecordViewModel`）を Activity スコープで生成し `MainRootScreen` に引数で渡します
（`MainActivity.kt:26-29`, `MainActivity.kt:53-58`）。残りの
`SettingsViewModel` / `ReserveViewModel` / `AiConciergeViewModel` は `MainRootScreen` のデフォルト引数で
`hiltViewModel()` から取得されます（`MainRootScreen.kt:49-53`）。
`LivePlayerViewModel` / `VideoPlayerViewModel` / `SmbViewModel` は各プレイヤー画面側で取得します。

---

## 1. レイヤー構成の詳細

`CLAUDE.md` の `ui/ → viewmodel/ → data/repository/ → data/api or data/local` を実クラスに落とすと以下になります。

| レイヤー | 代表クラス | 備考 |
|---|---|---|
| UI | `ui/main/MainRootScreen.kt`, `ui/home/HomeLauncherScreen.kt`, `ui/live/LivePlayerScreen.kt`, `ui/video/player/VideoPlayerScreen.kt`, `ui/epg/EpgNavigationContainer.kt`, `ui/reserve/ReserveListScreen.kt`, `ui/setting/SettingScreen.kt` | Compose for TV。State Holder は `*State.kt` に分離 |
| ViewModel | `viewmodel/*.kt`（10 個） | すべて `@HiltViewModel`。DI では **Provider インターフェース**を受け取る |
| Repository（抽象） | `data/repository/DtvProviders.kt` の 4 インターフェース | 実体は常に `DtvProviderProxy` |
| Repository（実装） | `KonomiRepository`, `data/repository/edcb/*`, `EpgStationRepository`(スタブ) | |
| データソース | `data/api/KonomiApi.kt`(Retrofit), `data/api/edcb/EdcbApi.kt`(独自TCP), `data/local/*`(Room), DataStore | |

補助的に「Provider を経由しない」リポジトリも存在します。
`EpgRepository`（EPG のメモリ+ファイルキャッシュ層）、`WatchHistoryRepository`、`LastChannelRepository`、
`SettingsRepository`、`AiNormalizationRepository` の 5 つです。

### 1.1 Hilt モジュール一覧（`di/`）

すべて `@InstallIn(SingletonComponent::class)`。

#### `di/NetworkModule.kt`（object モジュール）

| provides | 型 | 内容 |
|---|---|---|
| `provideOkHttpClient` | `OkHttpClient` | 下記参照 |
| `provideGson` | `Gson` | 素の `Gson()` |
| `provideRetrofit` | `Retrofit` | `baseUrl` はダミー（`NetworkModule.kt:112`）。実際のホストは Interceptor が書き換える |
| `provideKonomiApi` | `KonomiApi` | `retrofit.create(KonomiApi::class.java)` |

`OkHttpClient` の構成が特殊なので要注意です（`NetworkModule.kt:34-99`）:

1. **全証明書を無条件に信頼する `X509TrustManager` と、常に true を返す `hostnameVerifier`**
   （`NetworkModule.kt:38-66`）。KonomiTV の自己署名証明書 / `*.local.konomi.tv` 対応のためですが、
   MITM に対して無防備です。変更時は影響範囲を必ず確認してください。
2. **動的ホスト書き換え Interceptor**（`NetworkModule.kt:71-93`）。
   `runBlocking` で `settingsRepository.konomiIp` / `konomiPort` を読み、リクエストの scheme/host/port を
   毎回差し替えます。`ip` が `http://` / `https://` で始まる場合はそのまま、そうでなければ `http://` を前置。
   → **この Retrofit クライアントは KonomiTV 専用**であり、EDCB / EPGStation は別系統で通信します。
3. `CloudflareAccessInterceptor`（後述）
4. `HttpLoggingInterceptor(Level.BODY)`。`redactHeader(CF_ACCESS_CLIENT_SECRET_HEADER)` により
   シークレットが logcat に出ないようにしています（`NetworkModule.kt:58-62`）。

タイムアウトは connect/read/write すべて 30 秒。

#### `di/DatabaseModule.kt`（object モジュール）

- `provideAppDatabase`: `Room.databaseBuilder(context, AppDatabase::class.java, "komorebi.db")` に
  **`fallbackToDestructiveMigration(dropAllTables = true)`**（`DatabaseModule.kt:29`）。
  マイグレーションは一切定義されておらず、**スキーマ変更時は DB が全消去され再同期が走る**設計です。
- DAO の provides: `WatchHistoryDao`, `LastChannelDao`, `EpgCacheDao`, `RecordedProgramDao`,
  `SyncMetaDao`, `AiSeriesDictionaryDao`
  （`EpgDao` だけ provides が無く、利用側は `AppDatabase.epgDao()` を直接呼びます）

#### `di/RepositoryModule.kt`（object モジュール）

実質 `SettingsRepository` の provides 1 つだけ（`RepositoryModule.kt:16-22`）。
コメントにある通り `@Inject constructor` があるので本来不要ですが、明示的に残されています。

#### `di/DtvProviderModule.kt`（abstract + `@Binds`）

```kotlin
@Binds abstract fun bindLiveProvider(impl: DtvProviderProxy): LiveProvider
@Binds abstract fun bindRecordProvider(impl: DtvProviderProxy): RecordProvider
@Binds abstract fun bindReserveProvider(impl: DtvProviderProxy): ReserveProvider
@Binds abstract fun bindEpgProvider(impl: DtvProviderProxy): EpgProvider
```

4 インターフェースすべてが**常に**同一の `DtvProviderProxy`（`@Singleton`）にバインドされます。
バックエンドごとの Hilt Qualifier や条件分岐は DI 層には存在せず、切り替えは実行時に Proxy 内部で行われます。

### 1.2 `data/repository/DtvProviders.kt` — 4 つの Provider インターフェース

ファイル全体で 72 行。実装者はこの 4 つのシグネチャを常に意識する必要があります。

#### `LiveProvider`（`DtvProviders.kt:9-15`）

| メソッド | 戻り値 |
|---|---|
| `getChannels()` | `ChannelApiResponse` |
| `getLiveStreamUrl(channelId: String, quality: String, streamNumber: Int = 0)` | `String` |
| `getChannelLogoUrl(channelId: String)` | `String` |

`streamNumber` は 2 画面同時視聴（`ui/live/LiveDualPlayer.kt`）でメイン=0 / サブ=1 を区別するために
後付けされた引数です（`DtvProviders.kt:12` のコメント参照）。

#### `RecordProvider`（`DtvProviders.kt:20-40`）

| メソッド | 戻り値 |
|---|---|
| `getRecordedPrograms(page: Int = 1)` | `RecordedApiResponse` |
| `getRecordedProgram(videoId: Int)` | `Result<RecordedProgram>` |
| `searchRecordedPrograms(keyword: String, page: Int = 1)` | `RecordedApiResponse` |
| `getRecordStreamUrl(videoId, quality, sessionId, offsetSeconds = 0.0)` | `String` |
| `getArchivedJikkyo(videoId: Int)` | `Result<List<ArchivedComment>>` |
| `keepAlive(videoId, quality, sessionId)` | `Unit`（`@UnstableApi`） |
| `getTiledThumbnailUrl(videoId: Int)` | `String?` |
| `getStreamQualities()` | `List<StreamQuality>`（**デフォルト実装で `emptyList()`**） |

`getStreamQualities()` だけインターフェース側にデフォルト実装があり、実装しないバックエンドは
空リストを返します（`DtvProviders.kt:39`）。

#### `ReserveProvider`（`DtvProviders.kt:45-59`）

単発予約系: `getReserves()` / `addReserve(ReserveRequest)` / `updateReserve(reservationId, ReserveRequest)` /
`deleteReservation(reservationId)`
自動予約（キーワード予約）系: `getReservationConditions()` / `addReservationCondition(...)` /
`updateReservationCondition(conditionId, ...)`（戻り値のみ `Result<ReservationCondition>`） /
`deleteReservationCondition(conditionId)`

#### `EpgProvider`（`DtvProviders.kt:64-72`）

- `getEpgPrograms(startTime: String?, endTime: String?, channelType: String?)` → `List<EpgChannelWrapper>`
- `getPinnedEpgPrograms(pinnedChannelIds: String)` → `List<EpgChannelWrapper>`

時刻は ISO_OFFSET_DATE_TIME の文字列で渡す規約（呼び出し側の `EpgRepository.kt:255-257` 等が整形）。

### 1.3 `data/repository/DtvProviderProxy.kt` — ルーティングの実体

`DtvProviderProxy` は 4 インターフェースすべてを実装し、コンストラクタで
`KonomiRepository` / `EpgStationRepository` / `EdcbLiveRepository` / `EdcbRecordRepository` /
`EdcbReserveRepository` / `EdcbEpgRepository` を注入されます（`DtvProviderProxy.kt:21-30`）。
EDCB だけが機能ごとに 4 クラスへ分割されている点が非対称です。

分岐は `settingsRepository.backendType.first()` を **メソッド呼び出しのたびに読み直す**
4 つのプライベート関数に集約されています（`DtvProviderProxy.kt:34-64`）:

```kotlin
private suspend fun getLiveProvider(): LiveProvider = when (settingsRepository.backendType.first()) {
    "EDCB"       -> edcbLiveRepository
    "EPGSTATION" -> epgStationRepository
    else         -> konomiRepository
}
// getRecordProvider() / getReserveProvider() / getEpgProvider() も同じ形で
// EDCB -> edcb{Record,Reserve,Epg}Repository, EPGSTATION -> epgStationRepository, else -> konomiRepository
```

**重要な非対称性**: 例外の握り潰しが `LiveProvider` の 3 メソッドにしか実装されていません。

- `getChannels()`（`DtvProviderProxy.kt:70-80`）は `NotImplementedError` と `Exception` を捕捉して
  空の `ChannelApiResponse()` を返す
- `getLiveStreamUrl()` / `getChannelLogoUrl()` は例外時に空文字を返す
- **`RecordProvider` / `ReserveProvider` / `EpgProvider` の実装は単なる委譲**で、例外はそのまま呼び出し元へ伝播

そのため、未実装バックエンドで `getRecordedPrograms()` を呼ぶと `TODO()` の `NotImplementedError` が
ViewModel まで飛びます。新バックエンド追加時はここが最初の落とし穴になります。

### 1.4 バックエンド追加時の作業チェックリスト

`CLAUDE.md` に「4 インターフェース＋各実装＋Proxy 分岐を揃える」とありますが、実際にはもう少し広いです。
PR #103（EPGStation）の差分から逆算すると、最低限これらに手が入ります:

1. `data/repository/<backend>/` に Live / Record / Reserve / Epg 実装（またはまとめて 1 クラス）
2. `DtvProviderProxy` のコンストラクタ引数と 4 つの `get*Provider()` の `when` 分岐
3. `SettingsRepository` に接続情報キー（IP/ポート）と `Flow` を追加、`isInitialized` の `when` にも追加
4. `data/model/ChannelModel.kt` の `StreamSource` enum と `BackendConfig` sealed class
5. `common/UrlBuilder.kt` のロゴ/サムネイル/ストリーム URL 分岐
6. `ui/setting/SettingScreen.kt` / `SettingContents.kt` のバックエンド選択肢と接続設定 UI
7. `viewmodel/LivePlayerViewModel.kt` / `VideoPlayerViewModel.kt` の画質リスト・ストリーム種別分岐
8. `CloudflareAccessInterceptor` の保護ホスト一覧

### 1.5 サポート済みバックエンドの現状（2026-08 時点）

`SettingsRepository.backendType` が取りうる値は 4 つで、`isInitialized`（`SettingsRepository.kt:106-120`）の
`when` がその一覧になっています。

| `backendType` | 状態 | 実体 |
|---|---|---|
| `"KONOMITV"`（デフォルト） | 完全対応 | `KonomiRepository`（Retrofit / HTTP REST） |
| `"EDCB"` | 完全対応 | `data/repository/edcb/*`（独自 TCP バイナリ + EMWUI の HTTP） |
| `"MIRAKURUN_ONLY"` | ライブ視聴のみ対応（録画なし） | Proxy 上は `else` に落ちて `KonomiRepository` へ。実際の分岐は ViewModel 側 |
| `"EPGSTATION"` | **未マージ（PR #103 が OPEN）** | 現ブランチでは全メソッドがスタブ |

#### MIRAKURUN_ONLY について

`DtvProviderProxy` には `"MIRAKURUN"` の分岐が無いため `konomiRepository` に落ちます。
Mirakurun 固有の挙動は以下で個別にハンドリングされています:

- `KonomiRepository.getChannelLogoUrl()`（`KonomiRepository.kt:296-314`）:
  `backendType == "MIRAKURUN_ONLY"` のとき `mirakurun_<nid>_<sid>` 形式の ID を分解して
  `UrlBuilder.getMirakurunLogoUrl()` を使う
- `LivePlayerViewModel.getInitialStreamSource()`（`LivePlayerViewModel.kt:287`）:
  `"MIRAKURUN_ONLY", "MIRAKURUN" -> StreamSource.MIRAKURUN`
- `HomeViewModel.kt:385`, `LivePlayerViewModel.kt:181` で KonomiTV と同列に扱う分岐
- `ui/setting/SettingContents.kt:303-338` で「Mirakurun (録画なし)」というラベルと接続欄の切り替え

Mirakurun ストリームは生 TS のため、`util/TsReadExDataSource` を通してネイティブ層で処理されます。

#### EPGStation の現状（重要）

- GitHub PR **#103「EPGStationのサポート追加とリモコンのメディアキーが動作しない問題の修正」は
  2026-08-24 時点で OPEN（未マージ）**。作成者は `stuayu`、ブランチは `stuayu:features/1.1.0-beta7`。
- 現ブランチ `features/1.1.0-beta7` に存在する
  `data/repository/EpgStationRepository.kt` は **74 行のスタブ**です。
  `getChannels()` / `getRecordedPrograms()` / `searchRecordedPrograms()` は `TODO("EPGStation: Not implemented yet")`、
  他は空文字 / `emptyList()` / `Result.failure(NotImplementedError())` を返すのみ
  （`EpgStationRepository.kt:14-74`）。このファイル自体は EDCB 対応の初期コミットで
  プレースホルダとして追加されたものです。
- 一方で **UI・設定・URL 生成には EPGStation の受け皿が既にマージ済み**です:
  - `SettingsRepository` の `EPGSTATION_IP` / `EPGSTATION_PORT`（デフォルトポート `8888`）
    （`SettingsRepository.kt:30-31`, `128-129`）
  - `isInitialized` の `"EPGSTATION" -> !preferences[EPGSTATION_IP].isNullOrBlank()`
  - `UrlBuilder.getThumbnailUrl()` の `"EPGSTATION" -> "$baseUrl/api/thumbnails/$videoId"`
  - `DtvProviderProxy` の `"EPGSTATION" -> epgStationRepository` 分岐
- PR #103 側（ローカルブランチ `pr-103-latest`、HEAD `bbceda4`）では
  `EpgStationRepository.kt` が削除され、代わりに
  `data/repository/epgstation/` 配下に `EpgStationLiveRepository` /
  `EpgStationRecordRepository` / `EpgStationReserveRepository` / `EpgStationEpgRepository` /
  `EpgStationDataMapper` / `EpgStationChannelCache` / `EpgStationSeriesDictionary` が新設され、
  `data/model/EpgStationModels.kt`、`di/NetworkQualifiers.kt` も追加されます
  （app 配下だけで 68 ファイル / +4,514 行 -827 行）。

**したがって「EPGStation 対応済み」と書かれた記述を見かけたら、それは PR #103 マージ後の話です。**
main / features ブランチのコードだけを読む場合、EPGStation を選択しても録画一覧取得で
`NotImplementedError` になります。

---

## 2. ローカル DB（Room）とデータ同期

### 2.1 Room 構成

`data/local/AppDatabase.kt`: **`version = 13` / `exportSchema = false` / `@TypeConverters(Converters::class)`**。
DB ファイル名は `komorebi.db`。

| Entity | テーブル名 | 役割 |
|---|---|---|
| `WatchHistoryEntity` | `watch_history` | 視聴履歴（再生位置含む）。最新 30 件を Flow で取得 |
| `LastChannelEntity` | `last_watched_channel` | 直近視聴チャンネル。最新 10 件 |
| `EpgCacheEntity` | `epg_cache` | EPG キャッシュの**メタ情報のみ**（後述） |
| `RecordedProgramEntity` | `recorded_programs` | 録画番組のローカルミラー。Paging3 の元データ |
| `SyncMetaEntity` | `sync_meta` | 同期の進捗（`lastSyncedPage` / `lastSyncedAt` / `isInitialBuildCompleted`）。単一行（`id = 1`） |
| `AiSeriesDictionaryEntity` | `ai_series_dictionary` | 番組タイトル → 正規化シリーズ名の辞書 |
| `EpgChannelEntity` | `epg_channel` | EPG チャンネル（`EpgSyncEngine` 用。**現状未使用**） |
| `EpgProgramEntity` | `epg_program` | EPG 番組（同上） |

DAO は 7 つ（`AppDatabase.kt:26-37`）。
`SyncMetaDao` だけは独立ファイルではなく `data/local/dao/RecordedProgramDao.kt:176` に同居しています。

- `RecordedProgramDao` が最大のファイル。**`PagingSource<Int, RecordedProgramEntity>` を返すクエリを
  ソート順 × フィルタの組み合わせ分だけベタ書き**しています
  （`getAll_DateDesc` / `getAll_TitleAsc` / `getUnwatched_DurationDesc` … 、
  さらに `getPagingSourceByChannel` / `ByGenre` / `ByDayOfWeek` / `searchPagingSource`）。
  未視聴の判定は `playback_position <= 10`（秒）という単純な閾値です。
- `EpgDao.insertEpgData(channels, programs)` は `@Transaction` 相当のデフォルトメソッド。
  `deleteOldPrograms(thresholdEpoch)` で古い番組を削除します。

### 2.2 `data/sync/RecordSyncEngine.kt` — 録画一覧の同期戦略

`@Singleton`。`RecordProvider`（= Proxy）/ `AppDatabase` / `SettingsRepository` /
`AiSeriesDictionaryDao` / `Context` を注入されます（`RecordSyncEngine.kt:57-64`）。

進捗は `StateFlow<SyncProgress>` で公開され（`RecordSyncEngine.kt:65-66`）、
`RecordViewModel.syncProgress` 経由で UI に表示されます。
`SyncProgress` は `isSyncing / isInitialBuild / isInitialSyncPhase / message / current / total / error` を持ち、
`progressText` で「メッセージ (現在 / 全体)」という表示文字列を生成します。

#### 端末スペックによるプロファイル分岐（現ブランチ）

現ブランチの分岐は `ActivityManager.isLowRamDevice` の 1 軸のみです
（`RecordSyncEngine.kt:76-82`, `179-185`, `289`）:

| パラメータ | 低 RAM 端末 | 通常端末 |
|---|---|---|
| `BATCH_SIZE`（DB 一括 upsert の閾値） | 30 | 100 |
| `GC_DELAY_MS`（初期構築時のページ間ウェイト） | 2000ms | 1200ms |
| 通常更新時のページ間ウェイト | 500ms | 300ms |
| 初期構築時の辞書プリロード | スキップ（メモリ節約） | 実施 |

初期構築中は 1 ページごとに `System.gc()` を明示的に呼んでいます（`RecordSyncEngine.kt:286`）。

> **PR #103 でここが大きく書き換わります。**
> `SyncProfile(name, parallelism, fetchLimit, batchSize, initialDelayMs, normalDelayMs)` という
> データクラスが導入され、`ActivityManager.MemoryInfo.totalMem`（MB）・`availableProcessors()`・
> `isLowRamDevice` の 3 つから `low` / 中間 / 高性能の 3 プロファイルを選択します
> （低: `totalMem < 1200MB` 等で parallelism=2、高: `totalMem >= 3072MB` 等で parallelism=6、中間は 4）。
> あわせて `Semaphore` によるページ並列取得、再生中のスロットリング（`isThrottled`）、
> プロファイル名と所要時間のログ出力が入ります。
> コミットメッセージによれば 17,081 件の同期が約 17 分 → 約 23 秒に短縮されたとのことです。

#### 同期の 3 モード

1. **`syncAllRecords(forceFullSync: Boolean = false)`**（`RecordSyncEngine.kt:114`）
   - `forceFullSync` なら `activeSyncJob?.cancel()` → `join()` してから
     `recorded_programs` と `ai_series_dictionary` を全削除し、`lastSyncedPage = 0` に戻す
   - `page = 1` から降順ページングで `recordProvider.getRecordedPrograms(page)` を繰り返す
   - **中断再開**: 初期構築が未完了かつ `lastSyncedPage > 0` のときのみ `lastSyncedPage + 1` から再開
     （`RecordSyncEngine.kt:166-172`）。完了済みの通常更新は必ず 1 ページ目から
   - **早期打ち切り**: `hasRecordChanged()`（`title` と `isRecording` の一致だけを見る、
     `RecordSyncEngine.kt:84-91`）でローカルと一致し、かつ録画中でないレコードが
     `KNOWN_RECORD_STOP_THRESHOLD = 1` 件連続したらそのページで終了（`RecordSyncEngine.kt:216-239`）
   - **孤児削除**: 初期構築 or 完全同期のときだけ、取得済み ID 集合とローカル ID の差分を
     900 件ずつ chunk して削除（`RecordSyncEngine.kt:308-317`）
   - 各エンティティに `TitleNormalizer.extractDisplayTitle()` または辞書引き結果を `seriesName` として付与
   - 完了後に `startDictionaryResolutionLoop()` を起動
2. **`smartSync()`**（`RecordSyncEngine.kt:381`）
   - 初期構築が完了していなければ何もしない
   - **1 ページ目だけ**取得し、件数一致 + `title` / `isRecording` 一致をすべて満たせばスキップ。
     不一致、またはローカルに録画中レコードがある場合のみ upsert（`RecordSyncEngine.kt:417-444`）
   - 排他は `smartSyncMutex.tryLock()` による多重起動防止
3. **`startDictionaryResolutionLoop()`**（`RecordSyncEngine.kt:474`）
   - `recorded_programs` から `seriesName` 未解決のタイトルを 100 件ずつ取得
   - `TitleNormalizer.extractDisplayTitle()` でベースタイトルへ重複排除してから
     **`WikipediaNormalizer.getCanonicalTitle()` を直列に**呼ぶ（並列化しない）
   - API 呼び出し間に 300ms、チャンクの間に 500ms の「ポライトディレイ」
     （`RecordSyncEngine.kt:518-537`、コメントに Wikipedia の規約遵守の意図が明記されています）
   - 解決結果を `ai_series_dictionary` に保存し、同一トランザクション内で
     `recorded_programs.seriesName` も更新

排他制御に `syncMutex` / `jobMutex` / `dictionaryMutex` / `smartSyncMutex` の 4 つの `Mutex` を
使い分けている点に注意してください。

#### `data/worker/RecordSyncWorker.kt`

- `MainApplication.onCreate()`（`MainApplication.kt:45`）から `RecordSyncWorker.schedule(this)` で
  **15 分間隔の `PeriodicWorkRequest`** を `ExistingPeriodicWorkPolicy.KEEP` で登録
- 実行時に 2 段のガード:
  1. `syncEngine.isInitialBuildCompleted()` が false ならスキップ
  2. **`AudioManager.isMusicActive` が true（＝視聴中）ならスキップ**（`RecordSyncWorker.kt:37-45`）。
     `Result.retry()` ではなく `Result.success()` を返して次の 15 分に回す、という意図的な実装
- 通ったら `smartSync()` のみを呼ぶ（全件同期はしない）

### 2.3 `data/sync/EpgSyncEngine.kt` — 現状デッドコード

`EpgSyncEngine` と `EpgSyncWorker` は実装されていますが、**リポジトリ全体を grep しても
どこからも参照されていません**（`MainApplication` は `RecordSyncWorker.schedule()` しか呼ばない）。
連動する `EpgChannelEntity` / `EpgProgramEntity` / `EpgDao` / `EpgDataMapper` も同様に未使用です。

実装内容は以下の通りで、将来復活させる場合の参考になります（`EpgSyncEngine.kt:36-113`）:
- チャンネル種別 `["GR", "BS", "CS", "BS4K", "SKY"]` をループ
- API 負荷とタイムアウトを避けるため、各種別を **3 日分 × 2 回（計 6 日分）** に分割取得
- `flatMap` で全番組を一度に展開せず、チャンネル単位で変換＋トランザクション挿入してピークメモリを抑制
- 同期後に `deleteOldPrograms(now - 7日)` で 1 週間より古い番組を削除
- `EpgSyncWorker` は 12 時間間隔の定期実行を想定（`EpgSyncWorker.kt:42-47`）

**実際に動いている EPG のデータ経路は `data/repository/EpgRepository.kt` です**（次節）。

### 2.4 EPG のキャッシュ実装（`EpgRepository`）

Room ではなく **メモリ + gzip ファイル**の 2 段キャッシュです。

- メモリ: `ConcurrentHashMap<String, List<EpgChannelWrapper>>`（キーはチャンネル種別）
- ファイル: `context.cacheDir/epg_cache_<channelType>.json.gz`。
  Gson で JSON 化 → `GZIPOutputStream` で直接圧縮書き込み（`EpgRepository.kt:50-72`）
- Room の `epg_cache` テーブルには **`dataJson = "FILE_BASED"` という文字列とタイムスタンプだけ**を保存。
  これは CursorWindow の 2MB 制限を回避するための措置です（`EpgRepository.kt:61-68` のコメント）。
  過去の巨大 BLOB が残っていて `SQLiteException` が出た場合は握り潰して再取得させます
  （`EpgRepository.kt:77-83`）
- 鮮度判定は「キャッシュ内に 24 時間より先の番組が 1 件でもあるか」（`EpgRepository.kt:238-249`）
- `getEpgDataStream()` は Flow で「メモリ → ファイル → API」の順に段階的に emit する
  stale-while-revalidate 型（`EpgRepository.kt:277-317`）
- `searchFuturePrograms()`（`EpgRepository.kt:112-216`）はメモリキャッシュ上での全文検索。
  `Normalizer.Form.NFKC` + `lowercase()` で正規化、`,` や `、` を含むクエリは OR 検索、
  空白区切りは AND 検索。「テレビ的な日付」は 4 時始まりで計算されます（`EpgRepository.kt:157-159`）

EDCB では別途 `data/repository/edcb/EdcbEpgCacheManager.kt` が
EpgTimerSrv からの EPG データ取得とチャンネル種別判定（`getChannelType(onid)` /
`isSubChannel()` / `formatChannelNumber()`）を担当します。

### 2.5 `data/mapper/` — 変換の対応表

| ファイル | 変換内容 |
|---|---|
| `RecordDataMapper.kt` | `RecordedProgram`（ドメイン） ⇄ `RecordedProgramEntity`（Room）。`toEntity()` では `TitleNormalizer` を使ってシリーズ名の初期値を作る |
| `KonomiDataMapper.kt` | `KonomiHistoryProgram`（KonomiTV API） ⇄ `WatchHistoryEntity` ⇄ `RecordedProgram`。`toUiModel()` / `toDomainModel()` の 4 方向 |
| `EpgDataMapper.kt` | `EpgChannel` → `EpgChannelEntity`、`EpgProgram` → `EpgProgramEntity`（**`EpgSyncEngine` 専用のため現状未使用**） |
| `ReserveMapper.kt` | `ReserveItem` → `EpgProgram`。予約情報を番組表の番組として表示するための片方向変換 |

これらとは別に、バックエンド固有の変換は各ディレクトリ内に置かれています:
`data/repository/edcb/EdcbDataMapper.kt`（403 行、EDCB のバイナリ構造体 → 共通モデル）、
PR #103 では `data/repository/epgstation/EpgStationDataMapper.kt`（414 行）。

---

## 3. 設定・認証まわり

### 3.1 `SettingsRepository`

**パッケージに注意**: ファイルは `data/repository/SettingsRepository.kt` にありますが、
`package` 宣言は `com.beeregg2001.komorebi.data` です（`SettingsRepository.kt:1`）。
import は `com.beeregg2001.komorebi.data.SettingsRepository` になります。

実装は Jetpack DataStore Preferences（`preferencesDataStore(name = "settings")`）。
**キーは `booleanPreferencesKey` が 2 つ（`RECEIVE_BETA_UPDATES` / `HIDE_SUB_CHANNELS`）以外、
すべて `stringPreferencesKey`** です。真偽値も `"ON"` / `"OFF"`、数値も `"1.0"` のような文字列で保存されます。

公開されるのは `Flow<String>`（または `Flow<Boolean>`）のプロパティ群と、
書き込み用の `saveString(key, value)` / `saveBoolean(key, value)` の 2 つだけです。

#### 主要設定項目の分類

**バックエンド接続情報**（`SettingsRepository.kt:24-36`, `122-134`）

| プロパティ | キー | デフォルト |
|---|---|---|
| `backendType` | `backend_type` | `"KONOMITV"` |
| `konomiIp` / `konomiPort` | `konomi_ip` / `konomi_port` | `""`（プレースホルダ判定あり） / `"7000"` |
| `edcbIp` / `edcbPort` / `edcbHttpPort` | `edcb_*` | `""` / `"4510"`（TCP） / `"5510"`（EMWUI HTTP） |
| `epgStationIp` / `epgStationPort` | `epgstation_*` | `""` / `"8888"` |
| `mirakurunIp` / `mirakurunPort` | `mirakurun_*` | `""` / `"40772"` |
| `preferredStreamSource` | `preferred_stream_source` | ライブ視聴時に優先するソース |
| `edcbRecordPlayMethod` | `edcb_record_play_method` | `"API"`(api/xcode 経由) / `"DIRECT"`(直接アクセス・高速シーク可) |

**Cloudflare Access 関連**（`SettingsRepository.kt:86-103`, `203-206`）
- `cfAccessClientId` / `cfAccessClientSecret`
- 定数 `CF_ACCESS_CLIENT_ID_HEADER = "CF-Access-Client-Id"` / `CF_ACCESS_CLIENT_SECRET_HEADER = "CF-Access-Client-Secret"`
- `companion object` の `buildCfAccessHeaders(clientId, clientSecret)`: 空白・改行を `Regex("\\s+")` で除去し、
  どちらかが空なら `emptyMap()` を返す。OkHttp の `header()` が不正文字で例外を投げるのを防ぐ意図が
  コメントに明記されています（`SettingsRepository.kt:92-103`）

**プレイヤー設定**
`liveQuality`（既定 `"1080p-60fps"`） / `videoQuality` / `liveSubtitleDefault` / `videoSubtitleDefault` /
`subtitleCommentLayer` / `audioOutputMode` / `playerUiMode`（既定 `"MODERN"`） / `autoCmSkip`（既定 `"OFF"`） /
`availableStreamQualities`（EDCB/EPGStation から取得した画質リストの JSON キャッシュ）

**コメント（実況）設定**
`commentSpeed` / `commentFontSize` / `commentOpacity` / `commentMaxLines`（`"0"` = 無制限） / `commentDefaultDisplay`

**番組表設定**（`SettingsRepository.kt:81-83`, `198-200`）
`epgColumnCount`（既定 `"7"`） / `epgFontSizeScale`（既定 `"1.0"`） / `epgVisibleHours`（既定 `"6"`）

**ホーム・表示・起動**
`homePickupGenre` / `homePickupTime`（既定 `"自動"`） / `excludePaidBroadcasts` / `startupTab`（既定 `"ホーム"`） /
`startupChannel` / `timeFormat`（`"24H"` / `"12H"`） / `appTheme`（既定 `"MONOTONE"`） /
`defaultRecordListView` / `hideSubChannels` / `favoriteBaseballTeams`

**AI 関連**
`geminiApiKey` / `geminiApiKeyStatus` / `enableAiNormalization`

**Lab（実験機能）**
`labAnnictIntegration` / `labShobocalIntegration` / `labAllowMirakurunDual`

**その他**
`smbServerList`(SMB サーバー設定の JSON 配列。既定 `"[]"`) /
`defaultPostCommand` / `postRecordingBatchList` / `receiveBetaUpdates`

#### ヘルパーメソッド

| メソッド | 内容 |
|---|---|
| `isInitialized: Flow<Boolean>` | `backendType` ごとに必要な IP が入っているかを判定。初回セットアップ画面の出し分けに使う（`SettingsRepository.kt:106-120`）。KonomiTV はプレースホルダ `https://192-168-xxx-xxx.local.konomi.tv` も未設定扱い |
| `getStreamSourceUrl(StreamSource)` | `StreamSource` に応じた `http(s)://ip:port` を組み立て（`:222-246`） |
| `getEdcbFullUrl()` | EMWUI 用。ポートが `5511` または `s` 終端なら `https://` を選ぶ（`:248-260`） |
| `getCfAccessHeaders()` | `buildCfAccessHeaders` のラッパー（`:263-269`） |
| `getMirakurunBaseUrl()` | 未設定なら `null`（`:272-281`） |
| `getStartupTabOnce()` | 起動タブを 1 回だけ読む |
| `getBackendConfig(StreamSource)` | `BackendConfig.KonomiTv` / `.Mirakurun` / `.Edcb` の sealed class を返す（`:288-302`） |

`BackendConfig`（`data/model/ChannelModel.kt:61-69`）は `ip` / `port` と `isValid`（両方非空）を持ち、
`LivePlayerViewModel` がフォールバック順の決定に使います。

### 3.2 Cloudflare Zero Trust（Cloudflare Access）対応

サービストークン方式（`CF-Access-Client-Id` / `CF-Access-Client-Secret` ヘッダー）に対応しています。
PR #99（マージ済み、`07280d0` / `a740940`）で導入されました。

**適用ポイントは 4 系統あり、それぞれ別々に実装されています。**

1. **Retrofit / OkHttp**: `data/api/interceptor/CloudflareAccessInterceptor.kt`（`@Singleton`）。
   `NetworkModule.provideOkHttpClient` に登録（`NetworkModule.kt:94`）
2. **Coil（画像読み込み）**: `MainApplication` が `ImageLoaderFactory` を実装し、
   `CloudflareAccessInterceptor` だけを積んだ専用 `OkHttpClient` を渡す（`MainApplication.kt:25-33`）
3. **ExoPlayer / SSE / 生 TS ストリーム**: `LivePlayerViewModel` が
   `settingsRepository.getCfAccessHeaders()` を毎回取得し、
   `buildStreamUrl(...)` / `startPlayback(...)` / `startMainSse(...)` に `cfAccessHeaders` を引き回す
   （`LivePlayerViewModel.kt:449`, `:541` 付近）
4. **シークバー用タイル画像**: `ui/video/player/SceneSearchOverlay.kt` の `TileSheetLoader(context, requestHeaders)`。
   `VideoPlayerScreen.kt:134` が `SettingsRepository.buildCfAccessHeaders(...)` で組み立てて渡す

#### Interceptor の挙動（`CloudflareAccessInterceptor.kt:26-60`）

```
1. getCfAccessHeaders() が空 Map なら素通し
2. 保護対象ホストのリストを作る:
   - KonomiTV の baseUrl のホスト
   - getMirakurunBaseUrl() のホスト
3. request.url.host がそのリストに無ければヘッダーを付けずに素通し  ← 認証情報の漏洩防止
4. 一致した場合のみ 2 ヘッダーを付与
```

**注意点**: 保護ホストの列挙に **EDCB / EPGStation のホストは含まれていません**
（`CloudflareAccessInterceptor.kt:39-43`）。ただし EDCB / EPGStation は
`NetworkModule` の `OkHttpClient` を経由せず独自にリクエストを組むパスが多く、
例えば `EdcbRecordRepository` は `okHttpClient.newBuilder().apply { interceptors().clear() }` で
**Interceptor を全部剥がしたクライアント**を使っています（`EdcbRecordRepository.kt:43-45`）。
PR #103 のコミット `dafd67a` に「Cloudflare Access のトークンを EPGStation 宛にも付与するよう修正」とあり、
この領域は PR 側で拡張されます。

診断用に `mask()` でトークンの先頭 4 文字 + 末尾 4 文字だけを logcat に出す実装が入っています
（`CloudflareAccessInterceptor.kt:48-54`, `62-65`）。

### 3.3 認証まわりのその他

- KonomiTV のユーザーセッションは `KonomiRepository.refreshUser()`（`KonomiRepository.kt:40-43`）が
  `GET api/users/me` を叩いて `StateFlow<KonomiUser?>` を更新。セッション維持も兼ねます
- EDCB は認証機構を持たず、TCP（既定 4510）と EMWUI HTTP（既定 5510）へ直接接続
- Gemini API キーは保存時に `countTokens()` で実際に検証し、結果を
  `geminiApiKeyStatus`（確認中 / 設定済み / 未確認 / 無効）に保存します（コミット `09a654b`）

---

## 4. UI 層の構成

すべて Compose for TV（`androidx.tv:tv-material` 系）。フォーカス制御が最重要関心事で、
`common/SafeFocus.kt` や各所の `FocusRequester` 管理コードが大きな比重を占めます。

### 4.1 ディレクトリごとの責務

| ディレクトリ | 主なファイル | 担当 |
|---|---|---|
| `ui/main/` | `MainRootScreen.kt`(756行), `MainRootState.kt`, `MainRootComponents.kt`, `MainRootDialogs.kt`, `MainDialogs.kt`, `MainRootBackground.kt`, `SeasonalDecor.kt` | アプリのルート。タブバー、全画面判定、BackHandler、ライフサイクル監視、AI コンシェルジュの起動、季節装飾（`SeasonalDecor` は 662 行あり、時期に応じた背景演出を描画） |
| `ui/home/` | `HomeLauncherScreen.kt`(703行), `HomeContents.kt`, `LiveContents.kt`, `VideoTabContent.kt`, `BaseballDashboard.kt`, `LoadingScreen.kt`, `components/`(`HomeCards`, `HomeHeroDashboard`, `HomeSections`, `LibraryCards`) | ホームタブ（ヒーロー領域 + 各種行）、ライブタブのチャンネル一覧、ビデオタブの入り口、プロ野球ダッシュボード |
| `ui/live/` | `LivePlayerScreen.kt`(920行), `LivePlayerState.kt`, `LivePlayerFactory.kt`, `LiveDualPlayer.kt`, `LivePlayerOverlays.kt`, `LivePlayerSubMenu.kt`, `LivePlayerSubtitleLogic.kt`, `LiveCommentOverlay.kt`, `LiveJikkyoManager.kt`, `ChannelListOverlay.kt` | ライブ視聴。ExoPlayer 生成、2 画面同時視聴、字幕、実況コメント、チャンネルリストオーバーレイ |
| `ui/video/` | `RecordListScreen.kt`(941行), `RecordListState.kt`, `CustomPlayerManager.kt`, `components/`(13ファイル), `player/`(9ファイル), `smb/`(4ファイル + `player/`4ファイル) | 録画視聴。一覧・詳細・再生・シーンサーチ・SMB ライブラリ |
| `ui/epg/` | `EpgNavigationContainer.kt`, `ModernEpgCanvasEngine.kt`, `ModernEpgCanvasEngine_NoAnime.kt`, `EpgDataConverter.kt`, `EpgJumpMenu.kt`, `ProgramDetailScreen.kt`, `engine/`(`EpgConfig`, `EpgDrawer`, `EpgState`), `components/`(検索結果) | 番組表。Compose の Canvas に直接描画する自前レンダリングエンジン |
| `ui/reserve/` | `ReserveListScreen.kt`(682行), `ConditionEditDialog.kt`(875行), `EpgReserveDialog.kt`, `ReserveSettingsDialog.kt`, `AdvancedSettingsDialog.kt`, `ReserveSharedComponents.kt` | 録画予約。単発予約と自動予約（キーワード予約）条件の編集 |
| `ui/setting/` | `SettingScreen.kt`(1184行), `SettingContents.kt`(1258行), `SettingComponents.kt`(1472行), `SettingState.kt`, `OpenSourceLicensesScreen.kt`(1241行) | 設定画面。10 カテゴリの 2 ペイン構成 |
| `ui/components/` | `AiConciergePanel.kt`, `RecordedCard.kt`, `ReserveCard.kt`, `ChannelLogo.kt`, `KeywordConditionCard.kt`, `ExitDialog.kt`, `InputDialog.kt`, `GlobalToast.kt`, `ChannelExtensions.kt` | 画面横断の共通部品 |
| `ui/theme/` | `Theme.kt`, `Color.kt`, `Type.kt`, `Typography.kt` | `KomorebiTheme` / `KomorebiColors` / `NotoSansJP`。`appTheme` 設定（既定 `"MONOTONE"`）で切り替え |

### 4.2 番組表の描画エンジン

`ui/epg/engine/` は Compose の通常コンポーザブルではなく、**Canvas への直接描画**で実装されています。

- `EpgConfig`（`ui/epg/engine/EpgConfig.kt`）: 密度・画面サイズ・`columnCount` / `fontSizeScale` /
  `visibleHours` / `hideSubChannels` を受け取り、時刻カラム幅 60dp、チャンネル幅
  `(screenWidthPx - 60dp) / columnCount`、1 時間あたりの高さ
  `(screenHeight - tabHeight - headerHeight) / visibleHours` などのピクセル値を事前計算
- `EpgDrawer`（669 行）が実際の描画、`EpgState` がスクロール位置やフォーカスを保持
- `ModernEpgCanvasEngine.kt` と `ModernEpgCanvasEngine_NoAnime.kt` の 2 系統があり、
  後者はアニメーション無しの軽量版

チャンネル数・番組数が多い日本の EPG を TV の低スペック端末で滑らかに描くための選択です。

### 4.3 Modern UI と Classic UI

**`playerUiMode` はプレイヤー画面（ライブ / 録画 / SMB）の操作方式のみを切り替える設定**であり、
アプリ全体のテーマではありません（テーマは別の `appTheme`）。

- キー: `SettingsRepository.PLAYER_UI_MODE = "player_ui_mode"`、既定値 `"MODERN"`
  （`SettingsRepository.kt:50`, `:155`）
- 取りうる値と UI 上のラベル（`ui/setting/SettingScreen.kt:658-659`）:
  - `"MODERN"` → 「モダン (オンスクリーン操作)」
  - `"CLASSIC"` → 「クラシック (D-Pad完結)」
  - `SettingContents.kt:697` で現在値のラベルを表示
- 参照側はすべて同じパターンで、`isModern` ブール値を作って分岐します:
  - `ui/live/LivePlayerScreen.kt:124-125`
  - `ui/video/player/VideoPlayerScreen.kt:85-86`
  - `ui/video/smb/player/SmbVlcPlayerScreen.kt:99-100`
- 設定値の公開は `SettingsViewModel.playerUiMode: StateFlow<String>`（`SettingsViewModel.kt:230-233`）

つまり Modern = 画面上のボタン UI をフォーカス移動で操作する方式、
Classic = D-Pad のキー入力だけで完結する方式、という違いです。

### 4.4 設定画面の構成

`SettingScreen.kt:96-107` の `categories` が左ペインの一覧（10 個）:

1. 一般設定（`GeneralSettingsContent`）
2. 接続設定（`ConnectionSettingsContent`）— バックエンド選択、各サーバーの IP/ポート、
   EDCB 再生方式、SMB サーバー、Cloudflare Access トークン
3. 再生設定（`PlaybackSettingsContent`）— 画質、字幕、音声出力、`playerUiMode`、自動 CM スキップ
4. 録画設定（`RecordingSettingsContent`）
5. ホーム画面設定（`HomeDisplaySettingsContent`）
6. 表示設定（`DisplaySettingsContent`）— テーマ、時刻表記
7. 番組表設定（`EpgSettingsContent`）
8. コメント設定（`CommentSettingsContent`）
9. Lab（`LabSettingsContent`）— Annict / しょぼいカレンダー連携、Mirakurun 2 画面許可
10. アプリ情報（`AppInfoContent`）+ `OpenSourceLicensesScreen`

`MainRootState.settingsInitialCategoryIndex` / `settingsInitialFocusItemIndex` により、
他画面から特定の設定項目へ直接ジャンプする「ディープリンク」が可能です（`MainRootState.kt:68-70`）。

### 4.5 プレイヤーまわりの補足（TS 処理層との接点）

詳細は `docs/architecture/ts_processing_layer.md` に譲りますが、UI 層からの入口だけ記しておきます。

- `NativeLib`（`openFilter` / `pushDataBuffer` / `popDataBuffer` / `processDataBuffer` / `closeFilter`）を
  ラップするのが `util/TsReadExDataSource.kt`（Media3 の `DataSource` 実装）と
  `util/TsReadExDataSourceFactory.kt`
- 生 TS を流すソース（Mirakurun、EDCB の DIRECT、EPGStation の m2ts）はこの DataSource を通ります
- EDCB の TCP `NW再生` 用には別途 `data/api/edcb/EdcbNwPlayDataSource.kt` があります
- 字幕は ARIB。`app/src/main/assets/subtitle_renderer.html` + `aribb24.js` を **WebView にロードして
  オーバーレイ表示**する方式です（`LivePlayerScreen.kt:613`, `LiveDualPlayer.kt:215`/`:344`,
  `VideoPlayerScreen.kt:550`）。ExoPlayer 側は ID3 の `PrivFrame`（owner に `aribb24` を含むもの）から
  Base64 の字幕データを抜き出して WebView に渡します（`LivePlayerFactory.kt:131`,
  `LivePlayerSubtitleLogic.kt:74`, `VideoPlayerManager.kt:350`）。
  ※ PR #100（OPEN）はこれを libaribcaption へ置き換える提案です
- SMB は `ui/video/smb/player/SmbVlcPlayerScreen.kt` が libVLC を使う完全に独立した経路

---

## 5. AI 機能

Google Gemini を使った機能が 2 つあります。依存は `app/build.gradle.kts:199` の
`com.google.ai.client.generativeai:generativeai:0.7.0`。API キーはユーザーが設定画面で入力します
（`SettingsRepository.geminiApiKey`）。

### 5.1 AI コンシェルジュ（`viewmodel/AiConciergeViewModel.kt`）

対話でチャンネル切替・番組検索・録画予約まで行うエージェント的機能。UI は
`ui/components/AiConciergePanel.kt`（581 行）、開閉は `MainRootState.isAiConciergeOpen`。

- 依存は `SettingsRepository` のみ（`AiConciergeViewModel.kt:81-83`）。
  データ取得は行わず、**必要なコンテキストは UI 側から `AiContextData` として渡される**設計です
  （`liveChannels: Map<String, List<Channel>>` と `groupedSeries: Map<String, List<SeriesInfo>>`）
- モデル: `modelName = "gemini-3-flash-preview"`（`AiConciergeViewModel.kt:97`）。
  日本語の長大な `systemInstruction` をハードコードしています（`:99-129`）
- **コマンドタグ方式**: モデルに `[PLAY_LIVE: id]` のようなタグを出力させ、
  `handleAiResponse()`（`:275`）がパースして `AiConciergeAction` を `MutableSharedFlow` に流します。
  `MainRootScreen.kt:135-` の `aiConciergeViewModel.pendingAction.collect { ... }` が受けて画面遷移を実行

  `AiConciergeAction`（`AiConciergeViewModel.kt:33-65`）:
  - 実行系: `PlayLive(channelId)` / `PlayRecorded(videoId)` / `ReserveSingle(programId)` / `ReserveAuto(keyword)`
  - UI 遷移系: `SearchEpg(...)` / `SearchRecord(...)`
  - 裏側検索（RAG）系: `ReqEpgSearch(...)` / `ReqRecSearch(...)`
- **2 フェーズ制約**: システムプロンプトで「検索結果を返した直後に実行タグを出してはいけない。
  ユーザーの明確な承諾があってから実行せよ」と指示されています
- **裏側検索（RAG）**: `[REQ_REC_SEARCH: ...]` を受け取ったアプリ側が検索を実行し、
  結果を `submitSilentSearchResult()` / `submitSilentRecordSearchResult()`（`:206`, `:224`）で
  隠しメッセージとしてモデルに戻す往復構造。`ChatMessage.isHidden` で UI からは隠されます
- 音声入力にも対応（`sendAudioWithContext()`、`util/AudioRecorderHelper.kt`）
- フォーカス制御が難しいため `AiFocusTicketManager`（`MainRootState.kt:9-26`）という
  専用のチケット機構が用意されています

### 5.2 AI によるシリーズ名正規化（`data/repository/AiNormalizationRepository.kt`）

録画番組のタイトルからシリーズ名を推定する機能（設定 `enableAiNormalization` で有効化）。

- `normalizeTitles(titles: List<String>): Result<Map<String, String>>`（`:24`）
- モデルを **`"gemini-3-flash"` → `"gemini-2.5-flash"` → `"gemini-1.5-flash"` の順にフォールバック**
  しながら試行（`AiNormalizationRepository.kt:60-93`）

なお、`RecordSyncEngine` のデフォルトのシリーズ辞書生成は AI ではなく
`util/WikipediaNormalizer.kt`（Wikipedia の正式タイトル解決）と
`util/TitleNormalizer.kt`（`[新]`『』等の装飾除去・話数抽出）を使います。
AI 正規化はそれとは別のオプション経路です。

---

## 6. 実況コメント機能（NX-Jikkyo 連携）

「ライブ実況（リアルタイム）」と「録画実況（過去ログ）」の 2 系統があり、実装が完全に分かれています。

### 6.1 ライブ実況

- WebSocket クライアント: `data/jikkyo/jikkyoClient.kt` の `JikkyoClient`
  （ファイル名が小文字始まりなので grep 時に注意）
  - `watchSessionUrl` に接続 → `{"type":"startWatching"}` 送信 →
    `"room"` イベントで `threadId` / `yourPostKey` / コメントサーバー URI を受領 →
    コメントサーバーへ 2 本目の WebSocket を張る（`jikkyoClient.kt:64-158`）
  - ヘッダー `Sec-WebSocket-Protocol: msg.nicovideo.jp#json`、
    初期化メッセージの `res_from = -20` で直近 20 件の過去ログを取得（`:125`）
  - `ping` 受信時に `pong` と `keepSeat` を返す
  - `stop()` は `close(1000)` ではなく `cancel()` で即断し、
    発生する `SocketException` を `onFailure` で握り潰す（終了時のもたつき防止、`:41-49`）
- 管理クラス: `ui/live/LiveJikkyoManager.kt`（`@Singleton`、227 行）
  - **URL の決め方が `StreamSource` で分岐します**（`LiveJikkyoManager.kt:89-118`）:
    - `StreamSource.KONOMITV` → KonomiTV の `GET {base}/api/channels/{displayChannelId}/jikkyo` を叩き、
      レスポンスの `watch_session_url` をそのまま使う
    - **それ以外（EDCB / Mirakurun）** → ローカルの
      `app/src/main/assets/jikkyo_channels.json` から `network_id` / `service_id` で `jikkyo_id` を引き、
      `wss://nx-jikkyo.tsukumijima.net/api/v1/channels/jk{N}/ws/watch` を直接組み立てる
      （`LiveJikkyoManager.kt:114`）
  - `getJikkyoId()`（`:133-164`）は完全一致に加え、
    `networkId in 0x7880..0x7FEF`（地上波）の場合に `jcNid == 15` かつ
    `serviceId` が ±2 までズレていてもマッチさせる緩和ロジックを持ちます
  - コメントの色・位置・サイズは `getCommentColor()` / `getCommentPosition()` / `getCommentSize()` で
    ニコニコのコマンド文字列から変換（`:203-227`）
  - 重複表示防止に `processedCommentIds` を保持
- 表示: `ui/live/LiveCommentOverlay.kt`。速度・フォントサイズ・不透明度・最大行数は
  `SettingsRepository` の `comment*` 設定を反映

### 6.2 録画実況（過去ログ）

`RecordProvider.getArchivedJikkyo(videoId): Result<List<ArchivedComment>>` として抽象化され、
バックエンドごとに取得元が異なります。

| バックエンド | 実装 | 取得元 |
|---|---|---|
| KonomiTV | `KonomiRepository.getArchivedJikkyo()`（`:150-163`） | KonomiTV の `GET api/videos/{videoId}/jikkyo`（`KonomiApi.kt:61-62`）。サーバー側が過去ログを解決 |
| EDCB | `EdcbRecordRepository.getArchivedJikkyo()`（`:590-`） | **アプリが自前で過去ログ API を叩く**（下記） |
| EPGStation | 現ブランチでは `Result.success(emptyList())`（スタブ） | PR #103 で実装（後述） |

EDCB 版の流れ（`EdcbRecordRepository.kt:590-660` 付近）:

1. TCP で `EdcbApi.getRecInfo(videoId)` から録画情報（`onid` / `sid` / `startTime` / `durationSec`）を取得
2. `liveRepository.getJikkyoId(onid, sid)` で `jk` 番号を解決（できなければ「対応していません」でエラー）
3. `startTime` を `yyyy/MM/dd HH:mm:ss` としてパースし、`Asia/Tokyo` で Unix 秒に変換。
   終了時刻は `startUnix + durationSec`
4. `https://jikkyo.tsukumijima.net/api/kakolog/{jkId}?starttime=&endtime=&format=json` を GET
   （`EdcbRecordRepository.kt:611-612`）
5. `packet[].chat` を走査し、`content` が空のもの・`deleted == "1"` のものを除外して
   `ArchivedComment` のリストにする

表示は `ui/video/player/ArchivedCommentOverlay.kt` で、再生位置に同期してコメントを流します。

### 6.3 EPGStation の実況対応状況

**現ブランチでは未対応**（`EpgStationRepository.getArchivedJikkyo()` は空リストを返すだけ、
ライブ実況も `LiveJikkyoManager` が `StreamSource.EPGSTATION` を知らないため動きません）。

PR #103 では以下のコミットで対応が入っています（いずれも未マージ）:
- `c6275f0` 「EPGStationの実況再生と録画予約を修正」
- `eba0e62` 「EPGStationで実況の勢いが表示されない問題を修正」
- `2fa1e52` / `bbceda4` 「EPGStationの録画実況が表示されない・時刻がズレる問題を修正」
- `LiveJikkyoManager.kt` も PR 側で 72 行分変更されています

つまり **「EDCB / EPGStation 双方に実況実装があるか」への回答は、
2026-08-24 時点では『EDCB のみマージ済み、EPGStation は PR #103 内に存在するが未マージ』** です。

---

## 7. 付属ツール一覧

Android アプリ本体（`app/`）とはビルド系統が独立したツール群です。

| ツール | 技術 | 用途 |
|---|---|---|
| `KomorebiConfigurator/` | .NET / Avalonia | EDCB 連携のセットアップツール。`resolver.lua` 等を EDCB 側へ配置する。EDCB 経路では `EdcbRecordRepository.fetchResolverSettings()`（`:78-`）がこの `resolver.lua` から `ctok`（api/xcode・api/view 用トークン）と画質オプションを取得するため、EDCB バックエンドの動作に必須 |
| `KomorebiThumbnailer/` | — | 録画ファイルからシークバー用のタイル画像（`.tile.webp`）を生成。`RecordProvider.getTiledThumbnailUrl()` と `ui/video/player/SceneSearchOverlay.kt` の `TileSheetLoader` が消費する |
| `tools/ts_pmt_monitor/` | C++17 / CMake（NDK 不要） | 録画 TS の PMT（音声トラック等の PID 構成）が時間経過でどう変化するかを調査する診断 CLI。`app/src/main/cpp/` の `servicefilter.cpp` / `util.cpp` / `aac.cpp` / `huffman.cpp` を**コピーせず直接参照**するため、アプリ本体と完全に同一の PID 解決ロジックで診断できる（`tools/ts_pmt_monitor/CMakeLists.txt:21-31`）。映像・音声データは一切出力せず PID 構成とタイムスタンプ等のメタ情報のみをログ出力する設計で、ユーザーから TS ファイルそのものを提供してもらわずに不具合調査ができる |

`ts_pmt_monitor` の詳細（オプション、出力フォーマット、`servicefilter` との関係）は
`tools/ts_pmt_monitor/README.md` および `docs/architecture/ts_processing_layer.md` を参照してください。

---

## 付録 A: ビルド設定の要点（`app/build.gradle.kts`）

| 項目 | 値 | 行 |
|---|---|---|
| `compileSdk` / `targetSdk` / `minSdk` | 35 / 34 / **24** | `:13`, `:27`, `:28` |
| `abiFilters` | `armeabi-v7a`, `arm64-v8a` | `:44` |
| `splits` | ABI 分割 + Universal APK | `:15-` |
| Media3 | `1.7.1-komorebi`（`local_repo/` のカスタムパッチ版） | `:164-168` |
| `resolutionStrategy.force` | media3 の 9 モジュールを全て強制上書き | `:86-97` |
| FFmpeg デコーダ | `org.jellyfin.media3:media3-ffmpeg-decoder:1.6.1+1` | `:169` |
| libVLC | `fileTree("libs", "*.aar")`（Maven 版はコメントアウト） | `:211-212` |
| Gemini | `com.google.ai.client.generativeai:generativeai:0.7.0` | `:199` |

`MainActivity.onCreate()` は `Build.VERSION.SDK_INT >= O`(26) をチェックし、
未満なら `IncompatibleOsDialog` のみを表示してメイン画面を起動しません（`MainActivity.kt:39-47`）。
`minSdk = 24` との差分は意図的なガードです。

## 付録 B: 開発時にハマりやすい点

1. **`SettingsRepository` のパッケージが `data`（`data.repository` ではない）**
2. **`fallbackToDestructiveMigration(dropAllTables = true)`** のため、Entity を変更して
   `AppDatabase` の `version` を上げると既存ユーザーの DB が全消去され、録画同期がやり直しになる
3. **`NetworkModule` の Retrofit は KonomiTV 専用**。EDCB / EPGStation の通信を Retrofit に
   足そうとすると、動的ホスト書き換え Interceptor に食われる
   （PR #103 は `di/NetworkQualifiers.kt` を追加してこれを解決しています）
4. **`DtvProviderProxy` の例外処理が LiveProvider にしか無い**（1.3 節参照）
5. **`EpgSyncEngine` / `EpgSyncWorker` / `EpgDao` / `EpgChannelEntity` / `EpgProgramEntity` /
   `EpgDataMapper` は未使用**。EPG のキャッシュを触るときは `EpgRepository` の
   gzip ファイルキャッシュ側を見ること
6. **`data/api/interceptor/MockRecordInterceptor.kt`（1192 行）は本番コードから参照されていません**。
   40,000 件の録画データを生成してページング・集計性能を検証するための開発用モックで、
   使うときは手動で `OkHttpClient` に差し込みます
7. `app/src/test` / `app/src/androidTest` は存在しません（`CLAUDE.md` 記載どおり）
8. `app/src/main/res/font/` の Noto Sans JP は `.gitignore` 対象。手動配置しないとビルドが通りません

## 付録 C: 参照した主なファイル

- レイヤー / DI: `di/NetworkModule.kt`, `di/DatabaseModule.kt`, `di/RepositoryModule.kt`, `di/DtvProviderModule.kt`
- 抽象化: `data/repository/DtvProviders.kt`, `data/repository/DtvProviderProxy.kt`
- バックエンド実装: `data/repository/KonomiRepository.kt`, `data/repository/edcb/*`, `data/repository/EpgStationRepository.kt`
- DB / 同期: `data/local/AppDatabase.kt`, `data/local/dao/RecordedProgramDao.kt`, `data/sync/RecordSyncEngine.kt`, `data/sync/EpgSyncEngine.kt`, `data/worker/*`
- 設定 / 認証: `data/repository/SettingsRepository.kt`, `data/api/interceptor/CloudflareAccessInterceptor.kt`, `MainApplication.kt`
- UI: `MainActivity.kt`, `ui/main/MainRootScreen.kt`, `ui/main/MainRootState.kt`, `ui/setting/SettingScreen.kt`, `ui/epg/engine/EpgConfig.kt`
- 実況: `data/jikkyo/jikkyoClient.kt`, `ui/live/LiveJikkyoManager.kt`, `data/repository/edcb/EdcbRecordRepository.kt`
- AI: `viewmodel/AiConciergeViewModel.kt`, `data/repository/AiNormalizationRepository.kt`
