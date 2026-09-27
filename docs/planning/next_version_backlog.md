# 次バージョンに向けたタスク洗い出し（2026-09-08時点 / 2026-09-26追記 / 2026-09-27方針決定）

`features/1.1.0` の次バージョンで実現したい項目を洗い出し、それぞれの実現可能性を調査した結果をまとめる。各項目の詳細調査はこのドキュメント作成時点でのコードベース・外部情報に基づく。着手前に該当箇所の現状を再確認すること。

実現度は 高 / 中 / 低 の3段階。「高」は設計変更なしで局所的に実装できるもの、「中」は複数レイヤーに手を入れる必要があるが技術的障壁はないもの、「低」は技術的難易度そのものが非常に高いもの。

> **2026-09-26 追記**: 項目8〜12（BDオーサリング / denpa対応 / 低スペック機の高速化 / デコーダ判別機構 / フォーカス管理の整理）を追加し、既存の項目1・5に再確認結果を反映した。項目8〜12の調査は `develop`（`7df6618`）時点のコードベースと、denpa・Honorebiの公開リポジトリに基づく。
>
> **2026-09-27 方針決定**: 本バージョン（1.2.0-beta）で着手する項目・見送る項目を決定した。R8/リソース圧縮は見送り、項目8（BDオーサリング）と項目9（denpa対応）は保留。詳細は末尾の「[着手方針（2026-09-27 決定）](#着手方針2026-09-27-決定)」を参照。各項目内の「【2026-09-27 方針決定: …】」見出しにも個別の判断を記載している。

---

## 1. 4K等の高解像度画面への動的レイアウト対応

**実現度: 高**（設計次第で段階導入可能）

### 現状
- 画面サイズ・密度への動的対応はほぼ皆無。`LocalConfiguration`/`screenWidthDp` を参照しているのは番組表(EPG)関連の2ファイルのみで、それも Canvas 描画領域の計算用途にとどまる。
- EPGのフォントサイズ・列数（`epgFontSizeScale`, `epgColumnCount`）は密度に応じた自動調整ではなく、設定画面でユーザーが手動指定するDataStore値。
- ホーム画面・ライブタブなどは `.dp`/`.sp` が全て直値でハードコード。
- グリッド列数も3箇所（`RecordGridContent.kt:107`, `RecordSeriesGridContent.kt:97`, `SmbListContent.kt:312`）で `GridCells.Fixed(4)` と固定。`GridCells.Adaptive` は未使用。
- `res/values-sw*dp` 等の画面サイズqualifier、`dimens.xml` は存在せず、Manifest/Gradleにも密度・解像度対応の設定なし。

### 原因の推定
Android TVは本来dp単位で組めば密度換算により1080p/4Kで見た目サイズが揃うはずだが、(a) TV機種メーカーの密度設定が推奨通りでないことがある、(b) このアプリが画面のdp幅に応じて表示項目数を増やすロジックを一切持たないため、4Kの広い物理領域を使っても情報密度が変わらない、の両方が「4Kなのに窮屈」の原因と考えられる。

### 対応方針
- **A. 項目数の動的変更**: `GridCells.Fixed(4)` → `GridCells.Adaptive(minSize=...)` への置き換え（3箇所、低リスク）。加えて `screenWidthDp` に基づくブレークポイントで列数・カードサイズ・フォントスケールを切り替える共通CompositionLocal（`LocalTvSizeClass`案）を新設し、ホーム画面等に適用。
- **B. 密度の正規化**: `CompositionLocalProvider(LocalDensity provides Density(...))` をルートに挟み、OS側の密度設定のバラつきを吸収して常に一定のdp論理空間を保証する。

### 次のステップ
1. `GridCells.Fixed(4)` → `Adaptive` の置き換えで効果を実機確認（1080p機・4K機の両方で比較）
2. 共通スケールレイヤーの導入をホーム画面に適用して検証
3. 密度正規化（B）は影響範囲が全画面に及ぶため、A確立後に実機検証込みで検討

### 【2026-09-26 再確認】

- コードの現状は2026-09-08時点から変化なし。`GridCells.Fixed(4)` は3箇所（`RecordGridContent.kt:107`, `RecordSeriesGridContent.kt:97`, `SmbListContent.kt:312`）のまま、`GridCells.Adaptive` は未使用、`res/values-sw*dp` / `dimens.xml` も未作成。
- 「画面解像度に合わせて文字サイズ・表示量を最適化したい」という要望に対する移行手順の補足: EPGのフォントスケール（`epgFontSizeScale`）・列数（`epgColumnCount`）は既にユーザーが設定画面で手動指定するDataStore値なので、これを一気に自動算出へ置き換えると**既存ユーザーが自分で調整した値が無視されて体感が悪化する**。「自動（既定）/ 手動」の選択肢に拡張し、既に手動値を持っているユーザーは手動扱いで維持するのが安全。
- ブレークポイントの持ち方は、`res/values-sw*dp` のリソースqualifierよりも `LocalConfiguration.screenWidthDp` を見る CompositionLocal（`LocalTvSizeClass` 案）のほうが Compose for TV では素直。特にEPGはCanvas描画（`ModernEpgCanvasEngine`）なのでリソースqualifierの恩恵を受けられず、どちらにせよコード側でスケール値を持つ必要がある。
- 項目5・項目12と触る画面が重なるため、着手順を揃えると実機回帰検証が1回で済む。

#### 【2026-09-27 方針決定: 小さく入れて様子を見る】

一度に全画面へ適用せず、`GridCells.Fixed(4)` → `GridCells.Adaptive` の置き換えのように**影響範囲が閉じた変更から入れ、実機で様子を見ながら段階的に広げる**。共通スケールレイヤー（`LocalTvSizeClass` 案）の導入は、この段階適用で得た知見（どの画面でどの基準値が妥当か）を踏まえてから設計する。密度正規化（方針B）は引き続き後回し。

---

## 2. AIコンシェルジュのキーワード自動予約が動作しない問題

**実現度: 高（原因特定済み・低リスクな修正で対応可能）**

### 原因
`MainRootScreen.kt:265` の `AiConciergeAction.ReserveAuto` ハンドラが `reserveViewModel.addEpgReserve()` を呼ぶ際、`networkId=0, transportStreamId=0, serviceId=0` を**常に固定値として**渡している。

`ReserveViewModel.addEpgReserve()`（`ReserveViewModel.kt:275`）はこれを無条件に `ProgramSearchCondition.serviceRanges = listOf(serviceRange)` として**必ず非nullの1要素リスト**にしてしまう。

`EdcbDataMapper.encodeSearchKeyInfo()`（`EdcbDataMapper.kt:217`）では
```kotlin
val serviceList = cond.serviceRanges?.map { ... } ?: cachedServices.map { ... }
```
と「`serviceRanges` が null なら全チャンネルにフォールバック」という設計になっているが、上記の理由で `serviceRanges` は常に非null（`(0,0,0)` という実在しないサービスIDの単一要素）になるため、**全チャンネルへのフォールバックが発動せず、EDCB側はサービスID=0だけを対象に検索する条件として登録してしまう**。実在するどの放送サービスもID=0ではないため、自動録画条件が永遠にヒットしない。

「キーワード単体では動作せず対象サービス・チャンネルを指定する必要があるのでは」という予想は方向としては正しいが、正確には「チャンネル指定が必要」なのではなく「**指定なし（全チャンネル対象）を意味するはずの0がプレースホルダーではなく実際のフィルタ値として扱われてしまっている**」というバグ。

### 修正方針
`ReserveViewModel.addEpgReserve()` または呼び出し元で、`networkId/transportStreamId/serviceId` が全て0（＝チャンネル未指定）の場合は `serviceRanges = null` を渡すように分岐する。低リスク・影響範囲が狭い一点集中の修正。

### 次のステップ
1. `addEpgReserve` のシグネチャ、または `AiConciergeAction.ReserveAuto` ハンドラ側で null 分岐を追加
2. EDCB実機で自動予約条件を登録し、番組表検索が全チャンネルに対して走ることを確認
3. KonomiTV/EPGStationバックエンドでも同じ呼び出し経路を使っているか確認（他バックエンドの `addReservationCondition` 実装も同様の問題を抱えていないか要チェック）

---

## 3. KonomiTVユーザー連携機能（視聴履歴・チャンネルピン留め・マイリスト）

**実現度: 中**

### 現状の重大な発見
`KonomiApi.kt` には `GET api/mylists` / `GET・POST api/histories` / `GET api/users/me`(pinned_channel_ids) が定義され、`KonomiRepository.kt` にも呼び出し実装があるが、**これらのエンドポイントはKonomiTV実サーバー（`tsukumijima/KonomiTV`）には存在しない**。例外を握りつぶす実装のため今まで気づかれず残っていたデッドコード／実質バグ。

実際のKonomiTV APIでは、マイリスト・視聴履歴・ピン留めチャンネルは独立エンドポイントではなく、`GET/PUT /api/settings/client`（JWT認証必須）で読み書きする**単一のクライアント設定JSONブロブ**（`IClientSettings`、約50項目、字幕・コメントミュート設定なども同居）の一部として実装されている。

また、**ログイン機能（JWT取得）自体が皆無**。`POST /api/users/token`（OAuth2 password grant）を叩く導線、トークン保存、`Authorization: Bearer` ヘッダー付与、失効時の再ログインが全て未実装。

### 実装規模
- 認証基盤（ログインUI＋JWT保存＋Interceptor）: 中
- `GET/PUT /api/settings/client` の fetch→マージ→PUT ラッパー実装（既存の誤った `api/mylists`/`api/histories` 呼び出しの置き換え、他クライアントとの設定競合・データ消失に注意）: 中〜大
- マイリスト/視聴履歴/ピン留めUI: 中〜大
- アーキテクチャ的には `DtvProviderProxy` の4インターフェースは汚さず、`KonomiRepository` 固有メソッド＋`backendType == "KONOMITV"` ガードで実装するのが既存パターン（`HomeViewModel` の視聴履歴同期）と一貫する。EDCB/EPGStationにはアカウント概念が無いため、該当UIはバックエンドに応じて非表示にする。

### 次のステップ
1. `POST /api/users/token` によるログイン画面＋JWT基盤の構築
2. `IClientSettings` 相当のモデル定義と安全な fetch-merge-put ラッパー実装
3. マイリスト・視聴履歴（書き戻し）・ピン留めチャンネルUIを順次追加

---

## 4. 視聴中UI・ホーム画面・各種タブのUI刷新

**実現度: 高（技術的障壁なし、純粋に工数の大きいデザイン/実装作業）**

### 規模感
`ui/home` + `ui/live` + `ui/main` の3ディレクトリだけで計約12,600行・26ファイル。代表的な大型ファイル: `HomeLauncherScreen.kt`(1117行), `LivePlayerScreen.kt`(1047行), `MainRootScreen.kt`(789行)。

技術的な制約は特にないため、まずデザイン方針・優先度（どの画面から着手するか）を固めてから段階的リファクタが必要。既存のフォーカス管理・キー処理（項目5と関連）を壊さないよう、画面単位で切り出しながら進めるのが安全。

### 次のステップ
1. 刷新対象画面の優先順位付け（ホーム / ライブ視聴中UI / 各タブ、のどこから着手するか）
2. デザイン方向性の合意（現行の見た目を踏襲した整理か、大幅刷新か）
3. 項目1（解像度対応）と合わせて設計するとレイアウトの手戻りが少ない

---

## 5. リモコンキーを使った選局・各種ショートカットキー

**実現度: 高**

### 現状
キー処理はActivity層になく、各Composable画面が個別に`onKeyEvent`を持つ設計。ただしライブ視聴（`LivePlayerState.handleKeyEvent()`）と動画再生（`VideoPlayerState.handleKeyEvent()`）の2大画面は状態クラスに一元化済みで、拡張の差し込み口として扱いやすい。

**既に実装済み**: 左右キーでのチャンネル前後切替、決定キー長押しでのPinP移行、戻るキー長押しでのPinP、メディアキーによる再生制御・チャプタースキップ・クイックシークなど。

**未実装（TVリモコン標準キーコードが一切コード中に登場しない）**:
- 数字キー（`KEYCODE_0`〜`9`）による直接選局
- `KEYCODE_CHANNEL_UP`/`KEYCODE_CHANNEL_DOWN`
- `KEYCODE_GUIDE`（番組表）、`KEYCODE_INFO`（番組情報）、`KEYCODE_CAPTIONS`（字幕トグル）
- `KEYCODE_PROG_RED/GREEN/YELLOW/BLUE`（データ放送カラーボタン、項目6に依存）
- 専用ショートカットとしての再生速度変更（現状はサブメニュー経由のみ）

### 優先実装案（コスト小さい順）
1. **`KEYCODE_CHANNEL_UP`/`DOWN` の割当**（小）— 既存の左右キーチャンネル切替ロジックに1行分岐を足すだけ
2. **数字キーによる3桁チャンネル直接選局**（小〜中）— `LivePlayerState` に入力バッファ＋タイムアウトデバウンスを追加、桁数表示オーバーレイが必要
3. **`KEYCODE_GUIDE` での番組表直接ジャンプ**（中）— 既存の「決定長押し→サブメニュー→番組表」導線を1キーに短縮

### 次のステップ
1. 上記優先案から着手（`LivePlayerState.kt`, `LivePlayerOverlays.kt` が主な変更対象）
2. `VideoPlayerState.kt` 側にも同様のショートカット体系を展開するか検討

### 【2026-09-26 再確認】キーコードの機種差は「網羅」ではなく「学習」で解く

全KEYCODE参照を再確認した結果、2026-09-08時点の調査から変化なし。使用されているのは DPAD系 / `BACK` / `ESCAPE` / `ENTER` / `PAGE_UP`・`PAGE_DOWN` / `MEDIA_*` のみで、`KEYCODE_CHANNEL_UP`・`CHANNEL_DOWN`、数字キー、`KEYCODE_GUIDE` / `INFO` / `CAPTIONS` / `PROG_RED`系は依然として1箇所も登場しない。

「機種によってKEYCODEの実装が違うので対応は大変そう」という懸念について、**対応機種を実装側で網羅する方針は原理的に破綻する**（未知の独自キーコードに追従できず、機種が増えるたびに実装を足し続けることになる）。そこで方針を変える。

#### 代案: キー割り当て学習UI + 論理アクション層

1. **論理アクション層の新設** — 「選局UP」「選局DOWN」「番組表を開く」「番組情報」「字幕トグル」「再生速度変更」等の**論理アクション**を定義し、`KeyBindingRepository`（新設）が 論理アクション ↔ `keyCode` の対応をDataStoreで保持する。`LivePlayerState.handleKeyEvent()` / `VideoPlayerState.handleKeyEvent()` は生の`keyCode`ではなく論理アクションで分岐する形に書き換える。
2. **学習UI** — 設定画面に「このアクションに割り当てたいキーを押してください」を置き、受け取った生の`keyCode`をそのまま保存する。これにより**Komorebiが知らない独自キーコードでもユーザー自身が割り当てられる**ようになり、機種対応が実装の問題ではなくなる。
3. **未割当キーの可視化** — 割り当てのないキーが押されたときの`keyCode`を記録し、設定画面に「最後に押された未割当キー: keyCode=◯◯」として表示する。ユーザーが自分のリモコンのキーコードを自力で調べられるようになり、問い合わせ対応のコストも下がる。
4. **専用キー前提のUI整理** — 「専用キーが使える機種では画面を整理したい」という要望は、この論理アクション層に対して「そのアクションが割り当て済みか」を問い合わせれば実現できる。割り当て済みならサブメニューから該当項目を省く／オンスクリーンにキーヒントを出す、といった出し分けが可能。

#### 影響範囲の注意

`onKeyEvent` は2大プレイヤー（`LivePlayerState` / `VideoPlayerState`）以外にも散在している（`HomeContents.kt`, `VideoTabContent.kt`, `EpgNavigationContainer.kt`, `ModernEpgCanvasEngine.kt`, `SceneSearchOverlay.kt`, `VideoPlayerSubMenu.kt`, `VideoPlayerOverlays.kt`）。論理アクション層を導入するならこれらの整理も同時に行うことになるため、**項目12（フォーカス管理の時間依存の整理）と合わせて1本の設計案件として扱うのが効率的**。

---

## 6. リモコンキー対応込みのデータ放送（BML）表示

**実現度: 低（フル実装は非推奨）**

### 現状
データ放送関連の処理は皆無。`servicefilter.cpp` のPMT再構成ロジックが、データカルーセル（stream_type 0x0D の DSM-CC セクション）のPIDをそもそも黙って破棄しており、プレイヤー層まで到達しない設計。

既存の字幕実装（libaribcaption、PR#100関連）とは技術的に別次元。字幕は「文字コード列を定型レイアウトで描画するだけ」の閉じた仕組みだが、BMLは「XMLベースの文書＋モノメディア＋独自CSS＋ECMAScript風スクリプトがDSM-CCカルーセルに多重化された、事実上のテレビ向け簡易ブラウザ」であり、字幕デコーダの流用はできない。

### 技術的難易度
1. DSM-CCデータカルーセル抽出（PIDパススルー追加＋DII/DDB再構築＋疑似ファイルシステム）: 中〜高
2. BMLパース・レイアウト・スクリプト実行系: **事実上「テレビ向けの小さなWebブラウザを1個作る」に等しい規模**。オープンソース実装は2022年公開の [web-bml](https://github.com/otya128/web-bml)（MIT）が唯一の先行例で、それ以前はOSSでの実装例が存在しなかったことからも参入障壁の高さが伺える。
3. リモコンキー⇄BMLイベントモデルの連携: 相対的に軽い
4. 双方向通信（電話回線前提）: 現在ほぼ使われておらず無視して良い

### 現実的な落としどころ
- **フル実装は見送り**を推奨。
- 選択肢A: web-bmlのクライアント側（MIT）をAndroid WebViewへ移植し、Komorebi側はTS→データカルーセル抽出の「窓口」役に徹する（それでも数か月規模）
- 選択肢B: BML本体はレンダリングせず、既存の字幕/文字スーパーパイプラインで拾える範囲のテキスト情報のみ表示する「なんちゃってデータ放送」に留める
- 選択肢C: 対応自体を見送り、非対応と明記する（TVer等の主要配信アプリも同様に非対応）

### 次のステップ
このバージョンでの着手は非推奨。次々バージョン以降の検討事項として保留し、必要なら選択肢Bの限定対応から着手を検討。

---

## 7. Forkリポジトリから発掘した取り込み候補機能

**実現度: 高（個別移植前提）**

10件のforkを調査。うち大半は独自コミットなし、または既に本家へPRマージ済み（`stuayu`のCloudflare Zero Trust対応→PR#99、`ysnst`のIssue#90修正→PR#93など）。

### 最有力: `hiperjack/Komorebi` の `custom` ブランチ
`features/1.1.0` 比で未取込みの11コミット・48ファイル差分。マージベースが2026-06-18頃と古いため `git merge` はコンフリクト過多で非現実的、**機能単位での個別移植**が現実的。

含まれる機能（抜粋）:
- **CM自動スキップ**（自然再生でCM区間先頭を跨いだ場合のみ発動、手動シークでは非発動という設計が丁寧）
- **再生速度の永続化**（DataStoreに保存、ファイル・再起動を跨いで維持）
- **字幕フォント追加**（「Rounded M+ 1m for ARIB」同梱）
- **EPGStation風「ブルー」テーマ**（ダーク/ライト両対応）
- **サブメニュー操作性改善**（オーバーレイ表示中のCH+/-・早送り巻き戻し有効化、**play/pauseメディアキー対応（本家未実装と明記あり）**、無操作5秒での自動クローズ）
- **番組表拡充**（メディアキーで前日/翌日ジャンプ、過去7日分の追加読み込み、番組詳細から同時間帯録画への「録画を再生」導線、番組表グリッド上の録画済み番組の赤枠表示）
- **KonomiTV互換性改善**（`has_key_frames`廃止（2026-05-30〜）への追従）
- **`app/src/test` にJUnit4テストを新規追加**（EPG関連の純粋ロジックを`ui/epg/logic`へ分離してテスト化）— 合意済みの「テスト基盤方針」実例として参考価値あり

### 次点
`babizo/Komorebi` の `personal/komorebi-custom` に残存する1コミット「Preserve playback and subtitle improvements」（SMB再生・字幕関連）。内容精査は未実施。

### 次のステップ
1. `hiperjack/custom` の各機能を個別にdiff確認し、価値の高いもの（CM自動スキップ、再生速度永続化、メディアキー対応あたりが特に有望）から移植を検討
2. `babizo` の残存コミットの内容確認（優先度低）

---

## 8. バックエンド側BDオーサリング機能（BackupBDAV相当）とKomorebiからの操作

**実現度: 中**（Komorebi側は軽い。本体はサーバ側ツールの新規開発であり、Androidアプリの案件というより付属ツールの案件）

### 既存インフラとの噛み合わせ

EDCB連携は既に「EDCB内蔵HTTPサーバ（EMWUI, mongoose + Lua）上に置いた `KomorebiConfigurator/komorebi_resolver.lua` をKomorebiがHTTPで叩いてJSONを受け取る」という経路を持っている（`EdcbLiveRepository.kt:59` の `$baseUrl/komorebi/resolver.lua`、`EdcbRecordRepository.kt:91` の共通設定取得）。

したがって**BDオーサリングのためにEDCBのTCPバイナリプロトコル（`data/api/edcb/`）を触る必要はない**。`resolver.lua` に以下3つの窓口を増設すれば、Komorebi側はRetrofitで叩くだけで済む。

| 窓口 | 役割 |
|---|---|
| ジョブ投入 | 録画ファイルのリスト + 出力先 + メディア容量を受け取り、ジョブIDを返す |
| 進捗照会 | ジョブIDに対して 状態（待機/変換中/書き込み中/完了/失敗）・進捗率・エラー文言を返す |
| キャンセル | 実行中ジョブの中断 |

### 実処理をどこに置くか

Luaで重処理（TS解析・多重化・ディスク書き込み）は現実的でないため、実変換は **`KomorebiThumbnailer` / `KomorebiConfigurator` と同系統の独立ツール**（仮称 `KomorebiDiscWriter`）に持たせ、`resolver.lua` はジョブキューへの投入と状態ファイルの読み出しだけを担う。CLAUDE.mdに記載のある「付属ツールはアプリ本体とビルド系統が異なる独立ツール」という既存構成に素直に乗る形。

実装言語は、Windows前提でよければ `KomorebiConfigurator` と同じ .NET が有力（IMAPI2 による書き込みにも直結する）。クロスプラットフォームを意識するなら `KomorebiThumbnailer` と同じ Python も選択肢。

### 技術的な難所: 変換そのもの

BDAVは BDMV と異なり**音声をAACのまま格納できる**のが録画用途での最大の利点で、再エンコードを避けられる。一方で自動化に使えるツールが弱い。

- **tsMuxeR** — CLIあり。ただし BDMV / AVCHD 出力が主体で、BDAV（`BDAV/STREAM/*.m2ts` + `CLIPINF/*.clpi` + `PLAYLIST/*.mpls` + `info.bdav`）の生成には向かない。
- **MakeBDAV** — BDAVを作れるが Windows GUI ツールで、コマンドラインからの無人実行に乗せづらい。
- **自前実装** — CLPI / MPLS / info.bdav の規格実装が必要。ここが本項目で最も重い部分であり、実質「小さなオーサリングライブラリを1本書く」に相当する。

この3択のどれを取るかが、本項目の工数を決める分岐点。既存ツールをラップできるならサーバ側ツールは数百行で済むが、自前実装に踏み込むと規模が一桁変わる。**着手前にまず「無人実行可能なBDAV生成手段が本当に存在するか」の検証から入るべき**。

### 段階を切る（失敗コストが非対称なため必須）

| 段階 | 内容 | 失敗時のコスト |
|---|---|---|
| **v1** | 録画を選択して **BDAVフォルダ / ISOイメージを生成**するところまで | ディスク容量のみ。やり直し自由 |
| **v2** | 実ディスクへの書き込み（Windows: IMAPI2、Linux: cdrecord 等） | **BD-Rメディアを1枚消費し、リトライ不可** |

v1で「生成物を別途手動で焼く」運用が成立するため、v1だけでも実用価値がある。v2は失敗が物理的に不可逆なので、v1が安定してから分離して着手するのが妥当。

### Komorebi側のUI

- **録画一覧の複数選択UIが現状存在しない**（`RecordListScreen` / `RecordGridContent` は単一選択前提）。これが唯一のまとまったアプリ側実装。
- 選択した録画の合計サイズと BD-R 25GB / BD-R DL 50GB の残容量を表示するビュー。
- ジョブ進捗の表示。ライブ/再生と違い長時間かかるため、画面を離れても進捗を追える場所（設定画面配下か、ホームの通知領域）が要る。
- **機能はEDCBバックエンド限定にする**のが既存パターンと整合（`backendType == "EDCB"` ガード。KonomiTV / EPGStation では非表示）。`DtvProviderProxy` の4インターフェースは汚さない。

### 次のステップ

1. **無人実行可能なBDAV生成手段の有無を検証**（tsMuxeRのBDAV可否、MakeBDAVのCLI可否、または自前実装の規模見積り）。ここで結論が変わると以降の計画が全部変わるため最優先。
2. `resolver.lua` に3窓口を追加し、ダミージョブを返すだけのモックで Komorebi 側の疎通と進捗UIを先に作る（サーバ側ツールの完成を待たずに並行できる）。
3. `KomorebiDiscWriter` の実装（v1: BDAVフォルダ/ISO生成まで）。
4. Komorebi側に録画複数選択UIと容量計算を実装。
5. v2（実書き込み）は v1 の安定後に分離して検討。

### 【2026-09-27 方針決定: 保留】

実装難易度が高いため、本バージョンでは着手しない。特に「無人実行可能なBDAV生成手段が存在するか」が未検証で、ここで自前実装が必要と判明すると規模が一桁変わるリスクを抱えている。**着手する場合は、まず上記「次のステップ」の1（生成手段の調査）だけを単独の調査タスクとして切り出し、その結果を見てから計画を立て直すこと。**

---

## 9. 新バックエンド `denpa` への対応

**実現度: 低〜中**（現状のdenpa APIのままでは実質困難。上流へのAPI追加要望が前提）

対象: <https://github.com/danything/denpa> — 「チューナーを挿して起動すれば設定ファイルを1行も書かずに使える自宅用テレビ録画サーバ」。Mirakurun / EDCB を必要としない自己完結型。構成は TypeScript + SvelteKit（サーバ本体）と C#（チューナーエージェント）。

### 調査した公開API（`src/routes/api` 配下の全量）

```
api/bml/{confirm,post,proxy}
api/events                     (SSE想定)
api/font
api/health
api/live/ticket                (POST: 使い捨てチケット発行)
api/programs/[id]              (番組individual)
api/recordings/[id]            (録画individual)
api/recordings/[id]/file       (mkv本体)
api/recordings/[id]/playlist
api/recordings/[id]/chapters
api/recordings/[id]/poster
api/recordings/[id]/frame
api/recordings/[id]/resume
api/recordings/[id]/share
api/recordings/[id]/captions.sup
api/recordings/[id]/databroadcast
api/services/[serviceId]/{logo,logo-data}
api/sync                       (POST: 番組表の即時再取得)
```

### 障壁(a): 一覧取得APIが存在しない

`programs/[id]` / `recordings/[id]` は**個別ID取得のみ**で、一覧エンドポイントがない。denpaのUI側は SvelteKit の `+page.server.ts` がサーバ内でDBを直接読んでいるため、外部クライアント向けの一覧APIが必要とされていない。

Komorebiが `EpgProvider` / `RecordProvider` を実装するには**番組表の期間取得と録画一覧の取得が必須**なので、**denpa側への一覧API追加なしには対応そのものが成立しない**。

### 障壁(b): ライブ配信方式が徹底的に独自

`docs/stream.md` によれば、HLS/DASHを一切採用せず、**単一WebSocket上に独自バイナリフレーミングで多重化**したものを MSE（Media Source Extensions）へ直結する設計。

```
[1byte channel][8bytes timestamp(90kHz)][payload]
  0x00/0x01: 映像 fMP4 init / media segment
  0x10/0x11: 音声 fMP4 init / media segment
  0x20:      字幕 PNG（座標+時刻付き）
  0x30:      データ放送モジュール（JSON）
```

コンテナは fMP4 のみ、コーデックは AV1+Opus（推奨・6.6Mbps）または H.264+AAC（既定・14.5Mbps）を設定で選択。**ドキュメントに「VLC等の外部プレイヤーは非対応」と明記されている**。

Media3から扱うには「WebSocket受信 → チャネル分離 → init+mediaセグメントの再構成 → 独自DataSourceとしてExoPlayerへ給餌」＋「字幕PNGのサイドチャネル処理」を全て自作することになり、大規模。**ライブ対応は現実的でない。**

ただし副作用として興味深い点がある: denpaは**サーバ側で libaribcaption に字幕を描かせて絵と時刻だけを送る**設計なので、このバックエンドに限ってはKomorebi側の字幕デコード処理が不要になる。

### 現実的な段階案: 録画再生のみ先行

録画側は逆にかなり筋が良い。

- `api/recordings/[id]/file?token=…` は **mkv（H.264 or AV1 + Opus）のHTTP GET**。Media3 の `MatroskaExtractor` でそのまま再生できる（署名リンクは24時間有効、実装は `share.ts`、DBは `share_links`）。
- `chapters` / `poster` / `frame` / `resume` が揃っているため、**チャプター・サムネイル・レジューム再生が全部APIで取れる**。Komorebiが独自に `KomorebiThumbnailer` で用意している領域がサーバ側で解決済み。
- 字幕は `captions.sup` = **PGS形式**で返る。Media3にはPGSデコーダが存在するため、libaribcaptionを通さずに字幕を出せる。
- AV1はデバイス依存なので、**H.264設定のサーバでのみ確実**。項目11のデコーダ判別機構があれば「この端末でAV1が再生可能か」を事前判定できる（項目11との相乗効果）。

### 認証

- **署名付きリンク** — `?token=…`（24時間有効）。録画ファイル取得用。
- **ライブ用チケット** — `POST /api/live/ticket` で30秒有効の使い捨て札を取得し、URLに付けてWebSocket接続。
- **信頼ネットワーク** — 環境変数 `TRUSTED_NETWORKS`（CIDR、例 `10.10.0.0/16`）に含まれる送信元IPは認証をスキップ。**宅内TV運用ならこれで実装を大幅に簡略化できる。**
- リバースプロキシ経由では `ADDRESS_HEADER=x-forwarded-for` の設定が必要。
- OIDCやAPIキーの実装記述はドキュメントに見当たらなかった。

### アーキテクチャ上の位置づけ

KonomiTV / EDCB / EPGStation に続く**第5のバックエンド**（Mirakurunを含めれば）になる。CLAUDE.mdに記載の通り、`DtvProviders.kt` の4インターフェースへの追加と `DtvProviderProxy` のルーティング分岐、`SettingsRepository.backendType` への値追加（`"DENPA"`）が必要。バックエンド追加そのものの作業量は EPGStation対応（PR#103 / #112）で実績があり見積り可能。

### 次のステップ

1. **上流（danything/denpa）にIssueを立て、一覧API追加の意向を確認する。** これが通らない限り着手できないため最優先。同時に「外部クライアント向けにHLS出力を用意する予定があるか」も聞いておくと、ライブ対応の見通しが立つ。
2. 返答を待つ間は他項目を進める（本項目は外部依存でブロックされる性質のため、スケジュールのクリティカルパスに置かない）。
3. 一覧APIが用意された場合、**録画再生のみのバックエンドとして先行実装**（`RecordProvider` + `EpgProvider` の一部）。ライブは非対応として明示する。
4. `captions.sup`（PGS）→ Media3 PGSデコーダ経路の疎通確認は、他バックエンドに影響しない独立検証として先に試せる。

### 【2026-09-27 方針決定: 保留】

実装難易度が高く、かつ**denpa側への一覧API追加という外部依存でブロックされる**ため、本バージョンでは着手しない。着手する場合も、まず上流へのIssue（上記「次のステップ」の1）だけを先行させ、返答が得られてから計画すること。返答が得られない／一覧APIの追加予定がない場合は、対応を断念する判断も含めて検討する。

---

## 10. 低スペック機での動作速度の高速化

**実現度: 高**（効果の大きい未実施項目が2つ特定できている。かつ計測手段が既にリポジトリ内にある）

### 既に手が入っている部分（重複して着手しないための整理）

- `MainApplication.newImageLoader()` — Coilのメモリキャッシュを25%固定（Coil既定は `isLowRamDevice` 判定で15%まで落ち、TV端末でロゴが押し出されて毎回デコードし直す問題があった）、ディスクキャッシュ256MB、`respectCacheHeaders(false)`（ロゴエンドポイントのno-cacheヘッダを無視）、`crossfade(false)`（TVでのフェード合成は描画コストが高い）。
- `MainApplication.onCreate()` — `WorkManager.getInstance()` の初回初期化（Room DB構成・ForceStopRunnable起動）がメインスレッドを占有して初回フレーム描画を遅らせるため、最低優先度の専用スレッドへ退避済み。
- `ColdStartDiag` — プロセス開始からの経過時間を主要通過点でログ出力する計測コード（`adb logcat -s ColdStartDiag:I`）が既に入っている。
- `baselineprofile` モジュール（`alias(libs.plugins.baselineprofile)`、`"baselineProfile"(project(":baselineprofile"))`）が導入済み。

つまり「よく言われる一般的な最適化」は概ね済んでいる。残っているのは以下2点。

### 未実施(a): R8（コード圧縮）とリソース圧縮が無効のまま

`app/build.gradle.kts` の `buildTypes.release` が以下の状態でコメントアウトされている。

```kotlin
release {
//            isMinifyEnabled = true       // コード圧縮を有効化
//            isShrinkResources = true     // 未使用の画像やリソースも削除
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
}
```

`proguard-rules.pro` は28行・`-keep` 5件のみで、本格運用の準備はされていない。有効化すればメソッド数とDEXサイズが減り、クラスロード時間と起動時間が改善する。**Baseline Profile が既に入っている一方でR8が無効という組み合わせは、最適化の効果を取り逃している状態**。

ただしリスクは小さくない。

- **Room / Retrofit / Gson / Hilt** — リフレクションとアノテーション処理に依存するため keep ルールの整備が必須。
- **JNI（`NativeLib`）** — `native-lib.cpp` から参照されるKotlin側のクラス・メソッド名が難読化されると実行時に `NoSuchMethodError` になる。JNIブリッジは `-keepclasseswithmembernames` 等で明示保護が必要。
- **パッチ版Media3（1.7.1-komorebi）** — `ExtractorsFactory` 等のリフレクション経路で keep 漏れが出やすい。公式リリース版と挙動が異なる可能性がある点（CLAUDE.md記載）も踏まえ、慎重な回帰確認が必要。
- **libVLC（`libs/*.aar`）** — SMB再生パスもJNI依存。
- 難読化後のクラッシュはスタックトレースが読めなくなるため、`mapping.txt` の保管運用もセットで決める必要がある。

**作業ブランチで「有効化 → keepルール整備 → 全画面の実機回帰 → 計測」を1サイクル回してから判断する案件。** 一度に `isMinifyEnabled` と `isShrinkResources` の両方を入れず、まずコード圧縮のみで様子を見るのが安全。

#### 【2026-09-27 方針決定: 本バージョンでは見送り】

**過去に有効化を試したところ、アプリがほぼ機能しなくなった実績がある**（`build.gradle.kts` にコメントアウトが残っているのはその痕跡）。上記のkeepルール整備コストが高く、得られる効果も未計測でリスクに対する見返りが読めないため、**本バージョンでは着手しない**。

ただし将来の再挑戦に備えて、手順を記録しておく。

1. **まず `StartupBenchmarks` によるベースライン計測を行う。** 「R8で何ms速くなるのか」が分からないまま整備コストを払う判断はできない。計測して効果が小さいと分かれば、この項目は恒久的に見送って構わない。
2. **一括有効化ではなく、`isMinifyEnabled = true` + `-dontobfuscate` から始める。** R8には「不要コードの削除・最適化」と「難読化（クラス名・メソッド名のリネーム）」の2つの働きがあり、**過去に機能しなくなった原因は後者である可能性が高い**。Room / Retrofit / Hilt はクラス名やフィールド名を実行時に参照し、JNI（`native-lib.cpp` ↔ `NativeLib`）はメソッド名で結合しているため、リネームされると壊れる。`-dontobfuscate` を付ければ**削除・最適化の効果だけを安全に取れる**ので、まずこの状態で全画面の動作を確認する。
3. それが通ってから難読化を有効化し、壊れた箇所を keep ルールで1つずつ潰す。最低限 JNIブリッジ（`-keepclasseswithmembernames`）、Roomのエンティティ・DAO、Retrofitのインターフェースとレスポンスモデル、Hiltの生成物、パッチ版Media3 と libVLC のネイティブ結合部が対象。
4. `isShrinkResources` はさらに後。リソース削除の失敗は実行時のリソース未検出として現れ、原因の特定が難読化より面倒。
5. `mapping.txt` の保管運用（リリースごとにGitHub Releasesへ添付する等）を難読化の有効化前に決めておく。これがないと難読化後のクラッシュ報告を読めない。

### 未実施(b): ライブ信号情報の毎秒ポーリングがパネル非表示中も回り続ける

既知課題として以前から挙がっていた項目（`memory` の既知課題6）の実体を特定した。

`LivePlayerViewModel.kt:1336-1379` の `startSignalPolling()` が `while (true) { … delay(1000) }` で回り続け、その中で

- `String.format` を1周あたり6回以上（ビットレート・垂直周波数・バッファ長・音声情報…）
- `SignalMetadata` データクラスを毎秒新規生成して `_mainSignalInfo` StateFlow へ書き込み → **購読側の再コンポーズを毎秒誘発**

を行っている。デュアル再生時は `_dualPlayer` 側も含めて2系統。**信号情報パネルを表示していないときもこれが動き続ける**ため、低スペック機では常時の無駄な負荷になる。

修正は軽い。パネルの表示状態を条件にして、購読されている間だけ値を流す形（`stateIn(started = WhileSubscribed())` 相当、またはパネル表示フラグでジョブを起動/停止）にすれば済む。**一点修正で済み、かつ効果が体感しやすい**ため費用対効果が最も高い。

なお `HomeLauncherScreen.kt:113` の `delay(60000)` ループ、`EpgViewModel.kt:304` の `delay(10000)` は間隔が長く、優先度は低い。

#### 【2026-09-27 方針決定: 実施する】

負荷そのものはさほど高くないと見られるが、修正が一点で済み効果も見込めるため実施する。**本項目で唯一着手するのはこの(b)のみ**（(a) R8は見送り）。

実施時の注意: 信号情報パネルは表示/非表示が頻繁に切り替わるため、ジョブの起動と停止を繰り返してもフラつかないこと（パネルを開いた直後に値が空欄のまま数秒待たされないよう、停止中も最後の値を保持する）を確認する。デュアル再生時は `_mainSignalInfo` / `_dualSignalInfo` の2系統があるため片方だけの対応漏れに注意。

### 「推測で直す」から「測って直す」へ

本項目で最も重要なのは、**計測手段が既に揃っていること**。

- `baselineprofile/src/main/java/com/example/baselineprofile/StartupBenchmarks.kt` が存在するため、(a)(b) の before/after を起動時間の数値として出せる。
- 同じ Macrobenchmark で `frameTimings` を取れば、EPG Canvas描画（`ModernEpgCanvasEngine`）やホーム画面スクロールのジャンクを定量化できる。これは項目1・項目4・項目12の効果測定にもそのまま使える。
- `ColdStartDiag` のログと合わせれば、コールドブート時のどの区間が支配的かを特定できる。

既知課題6が「実測未検証」のまま長く残っていた原因は測る手段の不在だったが、現在は解消している。**まず計測してから優先順位を決め直すべき**。

### 次のステップ

1. `StartupBenchmarks` を実機（対象の低スペック機）で走らせ、現状のベースライン数値を記録する。
2. (b) 信号情報ポーリングの条件化を実施し、同じベンチで差分を確認。frameTimings も併せて取る。
3. 上記で不足なら、frameTimings の結果から次の対象（EPG Canvas / ホーム画面）を選ぶ。
4. (a) R8有効化は**本バージョンでは実施しない**（上記の方針決定を参照）。1.の計測結果次第で、将来的に上記5手順に沿って再検討する。

---

## 11. デコーダ対応状況の確認・動作判別機構

**実現度: 高**（Honorebiに完成品があり、ほぼそのまま移植できる）

### 現状

Komorebi本体には `MediaCodec` 関連の参照が実質存在しない（`CustomPlayerManager.kt:20` のコメントで「システム標準(MediaCodec)よりも拡張(FFmpeg)を優先」と触れているだけ）。端末が何を再生できるかを事前に知る手段がなく、再生してみて失敗するまで分からない。

### 参考実装: Honorebi（`makeding/Honorebi` の `h-dev` ブランチ）

該当ファイルは2つ。

- `app/src/main/java/com/beeregg2001/komorebi/ui/setting/DeviceCapabilityDetector.kt`（**137行、完全に自己完結**）
- `app/src/main/java/com/beeregg2001/komorebi/ui/setting/DeviceCapabilitiesScreen.kt`（表示UI）

`DeviceCapabilityDetector` が返す `DeviceCapabilityReport` の内容:

| フィールド | 取得方法 |
|---|---|
| `deviceName` | `Build.MANUFACTURER` + `Build.MODEL` |
| `androidVersion` | `Build.VERSION.RELEASE` + API level |
| `hevcDecoders` | `MediaCodecList(ALL_CODECS)` から非エンコーダかつHEVC対応のコーデック名を列挙 |
| `maxVerifiedMode` | 8K60 → 8K30 → 4096×2160@60 → 4K60 → 4K30 → 1080p60 の順に `videoCapabilities.areSizeAndRateSupported()` で総当たりし、最初に通ったモードのラベル |
| `supportsHevcMain10` | `profileLevels` に `HEVCProfileMain10` / `Main10HDR10`(API24+) / `Main10HDR10Plus`(API29+) が含まれるか |
| `supports4k60` / `supports8k30` / `supports8k60` | 同じ `areSizeAndRateSupported()` 判定の個別結果 |
| `hdrTypes` | `Display.HdrCapabilities.supportedHdrTypes` → HLG / HDR10 / HDR10+ / Dolby Vision のラベル |
| `maxReportedAudioChannels` | `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` の `channelCounts` 最大値 |

さらに派生プロパティとして

```kotlin
val supportsBs4kDirect: Boolean get() = supports4k60 && supportsHevcMain10
val supportsBs8kDirect: Boolean get() = supports8k60 && supportsHevcMain10
```

を持つ。**すべての取得箇所が `runCatching` で囲われており例外安全**、依存はAndroid標準API（`MediaCodecList` / `MediaCodecInfo` / `MediaFormat` / `Display` / `AudioManager` / `WindowManager`）のみで、Honorebi固有のクラスを一切参照していない。API23以前への配慮（`Build.VERSION.SDK_INT` 分岐、`defaultDisplay` のdeprecated経路）も入っており、Komorebiの `minSdk = 24` でそのまま動く。

### Komorebiに取り込む価値

2026-09-24のBS4K Original画質対応（PR#111でマージ済み）と直接噛み合う。`supportsBs4kDirect` があれば、

- 設定画面に「この端末でBS4Kを直接再生できるか」を事前表示できる
- 再生できない端末では原画質を選択肢から外す、または警告を出す
- ユーザーからの「BS4Kが映らない」問い合わせに対し、端末側の能力かサーバ側の問題かを切り分けられる

項目9（denpa対応）でも「この端末でAV1が再生できるか」の判定に使える。

### 移植時に足したい点

1. **HEVC以外のコーデックを追加** — 現状の実装はHEVC専用。**MPEG-2（地上波・BSの直接再生に直結）と H.264 の判定を追加すべき**。さらに AV1 を足せば項目9に効く。
2. **「対応と報告される」と「実際に再生できる」は別** — `areSizeAndRateSupported()` が true でも、コーデックインスタンス数の上限、セキュアバッファの要求、Media3側のフォーマット判定で失敗することがある。**短いサンプルを実際にデコードしてみる「実走テスト」を併設する**と信頼できる値になる（設定画面から手動実行する診断ボタンの形が妥当）。
3. **音声の判定範囲** — `maxReportedAudioChannels` は出力デバイスが報告する最大チャンネル数だけを見ている。AAC 5.1 / Dolby Digital のパススルー可否（`AudioManager.getDevices` の `encodings`）まで見ると、2026-09-24に保留とした22.2ch無音化問題の切り分けにも使える。
4. **ライセンス・出所** — 他人のフォークのコードなので、取り込み時にソースコード上へ出所を明記する（既に `libaribcaption` 導入時に同様の判断を行っている）。

### 次のステップ

1. `DeviceCapabilityDetector.kt` / `DeviceCapabilitiesScreen.kt` をKomorebiへ移植し、設定画面に「端末情報・デコーダ対応状況」として追加。
2. MPEG-2 / H.264（+ AV1）の判定を追加。
3. BS4K原画質の選択肢と `supportsBs4kDirect` を連動させる（警告表示または選択肢の非表示）。
4. 「実走テスト」ボタンの追加を検討。

---

## 12. フォーカス管理の時間依存の整理（追加提案）

**実現度: 中**（局所修正では済まない設計案件。ただし低スペック機の体感に最も直結する）

### 現状

`safeRequestFocus` の呼び出しが**214箇所**あり、その多くが `delay(...)` の直後に置かれている。

```
LivePlayerScreen.kt:573   delay(100)
LivePlayerScreen.kt:586   delay(100); mainFocusRequester.safeRequestFocus(TAG)
LivePlayerScreen.kt:594   delay(200)
LivePlayerScreen.kt:737   delay(200); mainFocusRequester.safeRequestFocus(...)
LivePlayerScreen.kt:859   delay(200); mainFocusRequester.safeRequestFocus(...)
HomeLauncherScreen.kt:303,315,348,373,380,418,438,548  delay(80)〜delay(300)
RecordListContent.kt:159  delay(100); detailPanelFocusRequester.safeRequestFocus(...)
```

### なぜ問題か

これは「Composableがまだ配置されていない時点で `requestFocus()` しても効かない」というタイミング問題を、**待ち時間の決め打ちで回避している**状態。結果として、

- **低スペック機では待ち時間が足りない** — レイアウト完了が遅れるとフォーカスが当たらず、「リモコンが効かない」「フォーカスが意図しない場所へ飛ぶ」という症状になる。低スペック機のUX不満（項目10）の一部は、処理速度そのものよりこちらが原因である可能性がある。
- **高速機では純粋な待ち時間の損失** — 画面遷移ごとに100〜300msの無駄が積み上がる。
- **リモコン連打への耐性が低い** — 待ち時間中に次のキーが来た場合の振る舞いが保証されない。
- **回帰しやすい** — 定数を変えると別の画面が壊れる、という相互依存が生まれやすい。既に `HomeLauncherScreen.kt:54` に「以前は各タブで一律 `delay(500)`〜`delay(800)` を挟んでいた」という改善履歴のコメントが残っており、この方向の調整が繰り返されてきたことが分かる。

### 方針

時間で待つのをやめ、**対象が配置されたことを検知してからフォーカスを要求する**形に置き換える。`onPlaced` / `onGloballyPositioned` でレイアウト完了を待つ、あるいは `BringIntoViewRequester` や Compose 1.7以降のフォーカス関連APIを使う。214箇所を一括で書き換えるのは非現実的なので、**共通ヘルパー（既に `common/SafeFocus.kt` が存在する）の内部実装を差し替え、呼び出し側から `delay` を段階的に剥がしていく**のが現実的な進め方。

### 項目4・項目5との関係

項目5（キー論理アクション層）で `onKeyEvent` の散在を整理する際に触る画面と、本項目で触る画面がほぼ一致する。さらに項目4（UI刷新）でも同じ画面を触る。**この3つは別々に実施すると同じ画面を3回壊して3回直すことになる**ため、画面単位でまとめて着手する計画にすべき。

### 次のステップ

1. まず1画面（`RecordListScreen` 等、影響範囲が閉じているもの）で `delay` なしのフォーカス制御に置き換え、低スペック機と高速機の両方で挙動を確認する。
2. うまくいけば `common/SafeFocus.kt` に共通化し、画面単位で順次移行。
3. 移行の効果は項目10の frameTimings / 起動ベンチで測る。

---

## 全体まとめ（実現度一覧）

### 2026-09-08 洗い出し分

| # | 項目 | 実現度 | 特記事項 |
|---|---|---|---|
| 1 | 4K等高解像度対応 | 高 | 段階導入可。項目4・5・12と合わせて設計すると効率的（2026-09-26再確認済み） |
| 2 | AIコンシェルジュ自動予約バグ | 高 | 原因特定済み、修正箇所は一点集中 |
| 3 | KonomiTVユーザー連携 | 中 | 既存コードはデッドコード、JWT認証基盤からの構築が必要 |
| 4 | UI刷新 | 高 | 技術的障壁なし、工数(約12,600行規模)が大きいのみ |
| 5 | リモコンキー拡充 | 高 | 機種差は「学習UI＋論理アクション層」で解く方針に更新（2026-09-26） |
| 6 | データ放送(BML) | 低 | フル実装は非推奨、限定対応または見送りを提案 |
| 7 | Fork機能移植 | 高 | `hiperjack/custom`から個別移植、CM自動スキップ等が有望 |

### 2026-09-26 追加分

| # | 項目 | 実現度 | 特記事項 |
|---|---|---|---|
| 8 | BDオーサリング（BackupBDAV相当） | 中 | **保留（2026-09-27）**。`resolver.lua`拡張＋独立ツール新設。BDAV生成手段の調査が最初の関門 |
| 9 | denpaバックエンド対応 | 低〜中 | **保留（2026-09-27）**。上流への一覧API追加要望が前提。ライブは独自WebSocket方式のため非現実的 |
| 10 | 低スペック機の高速化 | 高 | **(b)ポーリング条件化のみ実施、(a)R8は見送り（2026-09-27）**。計測手段（StartupBenchmarks）は既存 |
| 11 | デコーダ対応状況の判別機構 | 高 | Honorebiに137行の完成品あり。ほぼコピーで動く。BS4K対応と噛み合う |
| 12 | フォーカス管理の時間依存の整理 | 中 | `safeRequestFocus`が214箇所、多くが`delay`依存。低スペック機の体感に直結。項目4・5と合わせて設計フェーズを先に置く |

---

## 着手方針（2026-09-27 決定）

2026-09-26の提案に対して検討し、以下のとおり決定した。

### 作業ブランチの運用

- 作業ベースは **`features/1.2.0-beta`**（`develop` から作成）。
- 各機能は個別ブランチ（`fix/<内容>-1.2.0-beta` / `feat/<内容>-1.2.0-beta`）で実装し、PRで `features/1.2.0-beta` へ取り込む。
- CLAUDE.md のリポジトリエチケット（ブランチ命名、`fix:` / `feat:` + `・` 区切り箇条書きのコミットメッセージ）に準拠する。

### 着手する項目

| 順 | 項目 | 方針 |
|---|---|---|
| 1 | **11. デコーダ対応状況の判別機構** | 提案どおり実施。Honorebiの `DeviceCapabilityDetector` / `DeviceCapabilitiesScreen` を移植し、MPEG-2 / H.264 判定を追加、BS4K原画質の可否表示と連動させる |
| 2 | **10(b). ライブ信号情報ポーリングの条件化** | 実施。負荷そのものは高くないと見られるが、一点修正で効果が見込めるため |
| 3 | **1. 解像度に合わせたUI最適化** | 少しずつ入れて様子を見る。`GridCells.Adaptive` など影響範囲が閉じた変更から段階的に |
| 4 | **5 + 12 + 4. キー論理アクション層 / フォーカス整理 / UI刷新** | **設計を固めてから着手**（下記参照） |
| — | **2. AIコンシェルジュ自動予約バグ** | 原因特定済み・修正一点集中のため、上記の順序と独立にいつでも差し込める |
| — | **7. Fork機能移植** | 個別移植なので、上記の合間に価値の高いものから順次差し込む |

### 項目5 + 12 + 4 の設計フェーズ

3項目は触る画面がほぼ一致するため（`LivePlayerScreen` / `HomeLauncherScreen` / `VideoPlayerScreen` / EPG関連）、**実装前に設計を固める**。別々に進めると同じ画面を3回壊して3回直すことになる。

設計時に決めるべき事項:

1. **論理アクションの一覧** — 選局UP/DOWN、直接選局（数字）、番組表、番組情報、字幕トグル、再生速度、チャプタースキップ、PinP… と、それぞれの既定 keyCode 割り当て。
2. **`KeyBindingRepository` のデータ構造とDataStoreスキーマ** — 1アクションに複数keyCodeを許すか、逆に1keyCodeへ複数アクションを割り当てられてしまう衝突をどう防ぐか、既定へのリセット手段。
3. **キー処理の責務分担** — どこまでを State 層（`LivePlayerState` / `VideoPlayerState`）に集約し、どこからを各Composableの `onKeyEvent` に残すか。現状 `HomeContents` / `VideoTabContent` / `EpgNavigationContainer` / `ModernEpgCanvasEngine` / `SceneSearchOverlay` / `VideoPlayerSubMenu` / `VideoPlayerOverlays` に散在している分の扱い。
4. **フォーカス制御の新インターフェース** — `common/SafeFocus.kt` を `delay` 依存から「配置完了を待ってから要求する」方式へ差し替える際のAPI形。214箇所を一括変更はできないため、**新旧が共存できるインターフェースにすること**が要件。
5. **UI刷新の対象画面と優先順位、デザイン方向性** — 現行の見た目を踏襲した整理か大幅刷新か。項目1の解像度対応と同時に設計する。
6. **移行順序とリグレッション確認範囲** — 画面単位での移行手順と、各段階で実機確認すべき操作の一覧。
7. **計測方法** — 項目10で整備する `StartupBenchmarks` / frameTimings を、フォーカス整理の効果測定にも流用する。

設計の成果物は `docs/design/` 配下に置く（`docs/design/ts_seek_index.md` の前例に倣う）。

### 見送り・保留する項目

| 項目 | 判断 | 理由 |
|---|---|---|
| **10(a). R8 / リソース圧縮** | **見送り** | 過去の試行でアプリがほぼ機能しなくなった実績があり、keepルール整備のコストが高い。再挑戦する場合の手順（`-dontobfuscate` から始める等）は項目10に記録済み |
| **8. BDオーサリング** | **保留** | 実装難易度が高い。BDAV生成手段の調査だけを単独タスクとして切り出すのは可 |
| **9. denpa対応** | **保留** | 実装難易度が高く、上流へのAPI追加要望という外部依存もある |
| **3. KonomiTVユーザー連携** | 保留 | JWT認証基盤の新設が必要（2026-09-08の判断を継続） |
| **6. データ放送（BML）** | 保留 | フル実装は非推奨（同上） |
