# Komorebi

**Komorebi** は、Mirakurun および KonomiTV バックエンドに対応した、Android TV 向けの高機能視聴クライアントアプリです。
モダンな UI と直感的なリモコン操作、市販のハイエンドレコーダーを凌駕する高度なストリーミング制御を組み合わせ、これまでにない快適なテレビ視聴体験を提供します。

---

## 🚀 はじめに
**重要**: インストール後の初回起動時は、**KonomiTV** および **Mirakurun（オプション）** のサーバー設定が必要です。
画面の指示に従って、バックエンドのアドレス情報（IPアドレスやポート番号）を入力してください。
また、バックエンドにMirakurunを使用していない場合（EDCBなどを利用中の場合）は、MirakurunのIPアドレスとポート番号の入力は不要です。

---

## 動作環境

以下の環境で動作確認しております。Android 8.0、Fire OS 7以上のOSが必要です。（おおよそ2021年以降の機種であれば利用できると思います）
機種ごとの確認報告などは作者X([@tamago0602](https://x.com/tamago0602)))へご連絡いただければ、とても嬉しいです

* REGZA 55X8900K (Android TV 10)
* Fire TV Stick 4K Max 第一世代 (Fire OS 7 Android 9ベース)
* Fire TV Stick 4K Max 第二世代 (Fire OS 8 Android 11ベース)
---

## 📱 実装済み機能

### 🏠 ホームタブ
アプリ起動時に最初に表示される、Komorebiのポータル画面です。
テレビの大画面に最適化されたリッチなUIで、今すぐ見たいコンテンツへ直感的にアクセスできます。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/73a372f0-40dc-45a1-bead-eed42006ac8c" />

* **ダイナミック・ヒーローダッシュボード**: リモコンのフォーカス移動に合わせて、背景画像や番組のあらすじ、視聴プログレスバーがシームレスなアニメーションとともに切り替わります。
* **コンテンツセクション**: 
  * 「前回視聴したチャンネル」や、ニコニコ実況のコメント勢いを解析した「今、盛り上がっているチャンネル (Hot Channels)」を提案。
  * レジューム機能による「録画の視聴履歴（続きから再生）」や、直近の「これからの録画予約」へ素早くアクセスできます。
* **季節・テーマ連動**: 起動時は、季節や設定テーマ（MONOTONE, SPRING, SUMMER等）に合わせたウェルカム背景と装飾アイコンでお出迎えします。

### 📡 ライブタブ
現在放送中のテレビ番組を、直感的な操作で快適に探して視聴するための画面です。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/3eb77f47-1847-4ac9-8a36-0000c88b0169" />

* **リッチな番組情報ダッシュボード**: 現在放送中の番組進行状況や、「🔥（Hot）」アイコンによるニコニコ実況の盛り上がり度、次番組（NEXT）のプレビューを大画面で確認できます。
* **ジャンル別コンパクトチャンネルリスト**: 放送中の番組をジャンルごとに分類し、コンパクトなカードUIで表示。決定ボタンを押す前に、フォーカスを合わせるだけで上部のダッシュボードが切り替わり、番組詳細を確認できます。

### 🎬 ビデオタブ
録画した膨大な番組ライブラリから、見たい番組を素早く見つけて再生するためのポータル画面です。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/b339b4af-1d9c-41ed-8f39-95f76c8d9ecf" />

* **インタラクティブ・ヒーローバナー**: 下部のカードリストを移動するたびに、バックグラウンドで高速にAPIと通信し、最新のあらすじや高画質なサムネイルを取得してバナーに反映させます。
* **最近の録画 ＆ レジューム再生**: 直近で録画された番組や、「メタデータ解析中」「録画中」のステータスバッジをリアルタイム表示。途中まで見た番組はプログレスバー付きで表示され、ワンボタンで続きから再生できます。
* **ジャンル別・シリーズ別整理**: AI名寄せ機能によってグループ化された「シリーズ」単位でのカード表示に対応しており、話数が多いアニメやドラマもスッキリと探すことができます。

### 🗂️ 録画リスト画面
数千〜数万件に及ぶ膨大な録画ライブラリを管理・検索する総合画面です。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/320c8e2f-2230-4ef9-8102-06f29efcde63" />

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/32198e8b-676b-42a1-9aeb-aa6fa1698ee3" />

* **多角的なカテゴリ・フィルター**: サイドナビゲーションから「未視聴」「シリーズ別」「ジャンル別」「チャンネル別」「曜日別」など、様々な切り口で瞬時に絞り込みが可能。
* **強力な検索機能**: 検索バーと過去の検索履歴（ドロップダウン）を記憶し、リモコン入力の手間を省きます。
* **ストレスフリーなフォーカス制御**: 独自のフォーカス管理システム（`FocusTicketManager`）と半透明オーバーレイにより、複雑な階層メニューでも操作を見失いません（フォーカス迷子の防止）。
### 📅 番組表タブ
テレビの大画面で一覧性が高く、かつサクサクと快適に動作する電子番組表（EPG）です。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/207c969a-5fbf-4cad-9cc2-74cbf2291a9a" />

* **高速・なめらかな描画エンジン**: Canvasを用いた独自の描画エンジンを採用し、数日分・数十チャンネルの巨大なグリッドでもカクつくことなくシームレスにスクロール可能です。
* **日時指定ジャンプ**: 日付と時間帯をグリッドUIで素早く指定し、見たい時間の番組表へ一瞬でワープできます。キーワードによる番組検索にも対応。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/1e77cc0b-1715-4051-814b-facef6fdb9cf" />

* **リッチな番組詳細**: 放送中・未来・過去を自動判定し、「視聴する」「録画する」「EPG予約する」等のアクションボタンが動的に切り替わります。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/01981537-a84c-4fd4-a785-e3273f3b3066" />

### ⏰ 録画予約タブ
市販のハイエンドレコーダーを凌駕する、高度な録画予約管理システムです。

* **EPG自動予約 (キーワード・連ドラ予約)**: 番組表から「EPG予約する」を選ぶだけで自動予約条件を作成。後からキーワード、対象曜日、時間帯、有効/無効スイッチなどを細かく編集でき、右画面には「実際に予約される番組リスト」がリアルタイムでプレビューされます。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/c6c434e6-d692-4a65-9703-90b235b77304" />

* **詳細設定**: 除外キーワード、あいまい検索、番組名のみ検索、重複録画の回避（しない/同一チャンネル/全チャンネル）、優先度設定、イベントリレー追従、ぴったり録画、録画後実行バッチの指定など、あらゆるマニアックな設定をテレビ画面から行えます。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/abb75b5a-3a77-4938-8bce-73fdd9f7a14a" />

## 📺 視聴体験 (プレイヤー画面)

### 📡 ライブ視聴画面 (Live Player)
テレビの大画面でリアルタイムの放送を快適に楽しむための専用プレイヤー画面です。
単なる視聴にとどまらず、スムーズなザッピングやマニアックな情報表示など、多彩な機能を備えています。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/dce9c7be-019d-485f-91be-dae8223b7cce" />

* **直感的なザッピングUI**: 
  * 「上キー」で高度な設定を行うサブメニューを開閉。
  * 「下キー」で画面上にチャンネルのミニリスト（ザッピングUI）を呼び出し。
  * 「左右キー」で視聴を止めることなくチャンネルを切り替えが可能です。
 
<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/94818de5-1f46-4134-84d8-947d66405dc4" />

* **ニコニコ実況コメントのリアルタイム描画**: KonomiTVのWebSocket APIと連携し、専用のDanmakuエンジンでテレビ画面上にコメントを滑らかに流します。速度、文字サイズ、透明度などのカスタマイズも自在です。
* **充実のサブメニュー**: 再生を止めずに、主音声/副音声の切り替え、配信ソースの切り替え（KonomiTV / Mirakurun直接）、字幕表示、ストリーミング画質（1080p〜240p）の動的変更が行えます。
* **マニアックな信号情報表示**: 特定のキー操作で、現在の映像解像度、フレームレート、音声フォーマット等を画面左上に表示する「REGZA風オーバーレイ」を搭載。配信品質のチェックに最適です。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/9d65ef86-da41-4b62-bb5d-f052f71db976" />


### 🎬 録画視聴画面 (Video Player)
録画番組を、快適に視聴・操作するためのプレイヤーです。

* **スマートスキップ機能**: 番組にCM検出などのチャプター情報が含まれているかどうかで、リモコン操作が自動的に最適化されます。
  * **短押し**: 30秒スキップ（右） / 10秒戻し（左）
  * **長押し（チャプターあり）**: 次のチャプター / 前のチャプターへ瞬時にジャンプ。
  * **長押し（チャプターなし）**: 3分スキップ（右） / 1分戻し（左）として機能し、CM飛ばしに大活躍します。
* **シーンサーチ（チャプター一覧）**: 下キーを長押しすると、画面下部にチャプターのサムネイル画像とタイムラインを一覧表示。見たいシーンへ一気にシームレスなジャンプが可能です。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/66341a78-96a4-49bd-9977-9d59853920fd" />

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/af6bbb3e-e210-49e7-9f6f-556e4e6d50c1" />

* **過去ログ実況コメントの同期再生**: 録画された時間帯のニコニコ実況コメント（過去ログ）を取得し、現在の再生位置（ミリ秒単位）に合わせて完璧に同期して画面上に流します。
* **高度な再生設定（音ズレ対策済）**: `0.8x`〜`2.0x` の倍速再生に対応。Android OSネイティブのAudioTrackAPIに処理を委譲することで、CPU負荷を抑え、ピッチ（音程）を維持したまま「音ズレ」のない滑らかな倍速再生を実現しています。

---

## ⚡ パフォーマンス・設計へのこだわり

Komorebiは、Fire TV Stick 4K Max等の限られたメモリ（RAM 2GB）の端末でも、数万件のデータを快適に扱えるよう極限までチューニングされています。

* **堅牢なバックグラウンド同期 (OOM対策済)**
  膨大な録画データをKonomiTVから同期する際、メモリ不足（Out Of Memory）によるクラッシュを防ぐため、API通信とDB保存のスコープを細かく分離し、自発的にガベージコレクション（GC）と待機（delay）を挟むことで、1万件オーバーのライブラリでも落ちない堅牢な同期エンジンを実装しています。
* **シリーズ名寄せ辞書の自動構築**
  WikipediaAPIと独自の正規表現を用いた名寄せエンジンがバックグラウンドで稼働し、「第1期」「特番」「2nd Season」などで表記揺れする番組名を自動で1つのシリーズに整理します。
* **フォーカス迷子の徹底排除**
  Compose for TV におけるD-Padフォーカス制御の難しさを解決するため、独自の `FocusTicketManager` と半透明のオーバーレイ機構を開発。複雑なメニュー展開時も、OSレベルで意図しないフォーカス移動をブロックします。

---

## 🛠 技術構成

本アプリは、最新のAndroid開発標準技術（Modern Android Development）を採用し、オールKotlin・Jetpack Composeで構築されています。

* **UI**: Compose for TV (TV Material3)
* **アーキテクチャ**: MVVM + Clean Architecture 
* **DI**: Dagger Hilt
* **非同期処理 / データストリーム**: Kotlin Coroutines & Flow
* **ローカルDB**: Room Database
* **ネットワーク**: Retrofit + OkHttp
* **メディア再生**: Media3 (ExoPlayer) + FFmpeg Extension
* **画像読み込み**: Coil
* **コメント描画**: DanmakuFlameMaster (カスタム調整版)
* **バックグラウンド処理**: WorkManager

---

## ビルド方法
※v0.4.0-betaからFFMpeg関連のバイナリを使用しないように変更しています。0.4.0-beta以降の場合はフォントファイルの追加のみでビルドできると思います。それ以前のバージョンをビルドする際は以下を参考にしてください。

## ビルド前の準備

フォントファイルと FFmpeg のバイナリは `.gitignore` に含まれているため、ビルド前に手動での準備が必要です。

### 1. フォントファイルの準備

フォントファイルは、手動でのダウンロードが必要です。

```sh
FONT_CSS=$(curl -fsSL "https://fonts.googleapis.com/css2?family=Noto+Sans+JP:wght@100;300;400;500;600;700" -A "Mozilla/5.0")
URLS=($(echo "$FONT_CSS" | grep -oP 'url\(\K[^)]+'))
curl -fsSL "${URLS[0]}" -o app/src/main/res/font/notosansjp_thin.ttf
curl -fsSL "${URLS[1]}" -o app/src/main/res/font/notosansjp_light.ttf
curl -fsSL "${URLS[2]}" -o app/src/main/res/font/notosansjp_regular.ttf
curl -fsSL "${URLS[3]}" -o app/src/main/res/font/notosansjp_medium.ttf
curl -fsSL "${URLS[4]}" -o app/src/main/res/font/notosansjp_semibold.ttf
curl -fsSL "${URLS[5]}" -o app/src/main/res/font/notosansjp_bold.ttf
```

### 2. media-decoder-ffmpeg のセットアップ

FFmpeg デコーダーは以下の手順での準備が必要です。プロジェクトルート直下の `media/` に配置します（`.gitignore` に含まれています）。

#### 2.1 AndroidX Media3 のクローン

プロジェクトのルートディレクトリで実行してください。

```sh
git clone --branch 1.4.1 --depth 1 https://github.com/androidx/media.git media
```

#### 2.2 Media3 1.4.1 の不足ファイルを補完

Media3 1.4.1 にはいくつかのファイルが欠落しているため、スタブを作成します。

```sh
# datasource_httpengine ディレクトリが欠落しているため作成
mkdir -p media/libraries/datasource_httpengine

# 多くのモジュールで proguard-rules.txt が欠落しているため空ファイルを作成
for dir in media/libraries/*/; do
    [ ! -f "$dir/proguard-rules.txt" ] && touch "$dir/proguard-rules.txt"
done
```

#### 2.3 FFmpeg のクロスコンパイル

NDK のパスは環境によって異なります。Android Studio の場合は SDK Manager > SDK Tools > NDK (Side by side) からインストールでき、インストール先は以下が一般的です。

| OS | 一般的なパス |
|---|---|
| Linux | `~/Android/Sdk/ndk/<version>` |
| Mac | `~/Library/Android/sdk/ndk/<version>` |

コマンドラインで確認する場合:
```sh
ls ~/Android/Sdk/ndk/        # Linux
ls ~/Library/Android/sdk/ndk/  # Mac
```

```sh
# FFmpeg ソースを decoder_ffmpeg が参照する場所に直接クローン
git clone --depth 1 https://git.ffmpeg.org/ffmpeg.git media/libraries/decoder_ffmpeg/src/main/jni/ffmpeg

# クロスコンパイル（NDK_PATH は自身の環境に合わせて変更してください）
NDK_PATH=/path/to/ndk/<version>  # 例: ~/Android/Sdk/ndk/28.2.13676358
HOST_PLATFORM=$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)  # linux-x86_64 / darwin-x86_64 / darwin-arm64
MODULE_PATH=media/libraries/decoder_ffmpeg/src/main

bash "${MODULE_PATH}/jni/build_ffmpeg.sh" \
    "${MODULE_PATH}" \
    "${NDK_PATH}" \
    ${HOST_PLATFORM} \
    21 \
    vorbis opus flac mp3 ac3 eac3
```

---

## 🤝 SpecialThanks!
本アプリの開発にあたり、以下の素晴らしいプロジェクトと成果物を活用させていただいております。

* **[tsreadex](https://github.com/xtne6f/tsreadex)**: TS ストリーム解析および読み込み処理の基盤。
* **[aribb24.js](https://github.com/monyone/aribb24.js)**: 高精度な字幕描画ロジックの提供。
* **[KonomiTV](https://github.com/tsukumijima/KonomiTV)**: 強力な API バックエンドおよび配信プラットフォーム。
* **[Mirakurun](https://github.com/Chinachu/Mirakurun)**: チューナー管理および配信 API。
* **[DanmakuFlameMaster](https://github.com/bilibili/DanmakuFlameMaster)**: ニコニコ実況およびNX-Jikkyoのコメント表示。
* **[SCRename](https://github.com/rigaya/SCRenamePy)**: シリーズから探すの正規表現の参考にさせていただきました。


---
**Komorebi** の最新の進化をぜひお楽しみください！
さらなる改善案やバグ報告、特定のデバイスでの動作報告も、GitHub Issue や X にてお待ちしております。
