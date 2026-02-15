# Komorebi

**Komorebi** は、Mirakurun および KonomiTV バックエンドに対応した、Android TV 向けの高機能視聴クライアントアプリです。
モダンな UI と PS3 ライクなシーンサーチ、高度なストリーミング制御を組み合わせ、これまでにない快適なテレビ視聴体験を提供します。

---

## 🚀 はじめに
**重要**: インストール後の初回起動時は、**KonomiTV** および **Mirakurun（オプション）** のサーバー設定が必要です。
画面の指示に従って、バックエンドのアドレス情報（IPアドレスやポート番号）を入力してください。
また、バックエンドにMirakurunを使用していない場合（EDCBなどを利用中の場合）は、MirakurunのIPアドレスとポート番号の入力は不要です。

---

## 動作環境

以下の環境で動作確認しております。Android 8.0、Fire OS 7以上のOSが必要です。（おおよそ2021年以降の機種であれば利用できると思います）
機種ごとの確認報告などはX(@tamago0602)へご連絡いただければ、とても嬉しいです

* REGZA 55X8900K (Android TV 10)
* Fire Tv Stick 4K Max 第一世代 (Fire OS 7 Android 9ベース)

## ✨ 実装済み機能

### 🏠 ホームタブ
<img width="1920" height="1080" alt="Image" src="https://github.com/user-attachments/assets/2b7122d4-e878-4c05-8213-630aad3624dd" />

**チャンネル視聴記録**: 視聴した放送局の履歴をローカル DB に自動記録します。

**録画視聴履歴の保存**: 録画番組の再生位置を自動保存し、いつでも続きから再生可能です。

### 📡 ライブタブ
<img width="1920" height="1080" alt="Image" src="https://github.com/user-attachments/assets/8e22cebe-2b37-4346-86e1-9f3d9b412bc8" />

**放送波別チャンネルリスト**: 地デジ、BS、CS、BS4K、スカパーを種別ごとに整理して表示します。

### 📅 番組表タブ
<img width="1920" height="1080" alt="Image" src="https://github.com/user-attachments/assets/f5c026a2-c2fa-4619-a98b-c5497b813781" />

**マルチ放送波対応**: 放送波種別ごとに最適化された番組表をシームレスに閲覧できます。また、番組表画面にて戻るキーを長押しすることで、現在時刻に即座に戻ることができます。

<img width="1920" height="1080" alt="Image" src="https://github.com/user-attachments/assets/9e756fd4-5757-44ad-8f6b-c4807674afcb" />

**詳細表示 & 即視聴**: 番組詳細を確認でき、放送中の番組であればそのままライブ視聴画面へ遷移可能です。

<img width="1920" height="1080" alt="Image" src="https://github.com/user-attachments/assets/c46b497a-a94e-4282-8eea-7044967e3158" />

**日時指定ジャンプ**: 任意の日時へ素早く移動し、番組チェックが可能です。

### 🎥 ビデオタブ

<img width="1920" height="1080" alt="Image" src="https://github.com/user-attachments/assets/0eb334bc-e8a8-4dfb-b51f-b381a3a6eee1" />

**視聴履歴 & 最近の録画**: 直近の視聴番組や新着録画をすぐに確認できます。

<img width="1920" height="1080" alt="Image" src="https://github.com/user-attachments/assets/203338f5-bba8-4c31-a51e-aa855049aa3e" />

**録画一覧 & 高速検索**: 膨大な録画ライブラリからタイトルによるインクリメンタルサーチが可能です。

### ⚙️ 設定タブ
<img width="1920" height="1080" alt="Image" src="https://github.com/user-attachments/assets/33c0c8d3-a164-48c0-924f-ac77e03da923" />

**接続設定**: KonomiTV および Mirakurun のアドレス指定が可能です。Mirakurunについてはダイレクトストリームを使いたい場合に記入してください。

---

## 📺 視聴体験

### 🔴 ライブ視聴画面
<img width="1920" height="1080" alt="Image" src="https://github.com/user-attachments/assets/2ed64c36-0541-44df-b637-bca534348b68" />

**情報オーバーレイ**: 決定ボタンで番組詳細、進行状況、放送局情報を一括表示します。決定ボタン２度押しで右上に固定オーバーレイを表示します。
<img width="1920" height="1080" alt="Image" src="https://github.com/user-attachments/assets/9eebd487-18e6-4a90-90cb-484bd4ce1e69" />

**ミニチャンネルリスト**: 下キーで視聴を継続しながら他局をザッピングできます。
<img width="1920" height="1080" alt="Image" src="https://github.com/user-attachments/assets/591ec573-ad7a-466a-a08b-fda996f4cae2" />

**高度な音声設定**: デュアルモノ、主音声/副音声の切り替えに対応（上キーのサブメニュー）。

**字幕表示**: aribb24 ロジックによる高品質な字幕表示に対応。

**ソース切り替え**: KonomiTV と Mirakurun の配信ソースを動的に切り替え可能です。

<img width="1920" height="1080" alt="Image" src="https://github.com/user-attachments/assets/c41b7847-2955-49c7-b55e-86653624d110" />

**ニコニコ実況表示**: ニコニコ実況とNX-Jikkyoのコメント表示に対応しています。サブメニューより表示非表示の切り替え可能です。また、設定画面からデフォルトで表示するかの選択も可能です。

※録画番組での実況表示については、後日アップデートで対応予定です

### 🎞️ ビデオ視聴画面
**直感的シーク**: 左右キーによる 30 秒スキップ / 10 秒戻しに対応。
<img width="1920" height="1080" alt="Image" src="https://github.com/user-attachments/assets/e77d784f-1cdd-4441-adcb-6c14bcfa6526" />
**PS3 風シーンサーチ**: 下キーで起動。10 秒間隔のタイル画像から 1 コマを鮮明に切り出し、直感的な場面探しを実現します。
**マーキー表示**: 長い番組名も自動スクロールで全て表示します。

---

## 🚀 将来実装予定の機能
* **画質・パフォーマンス**: KonomiTV 画質変更、Amatsukaze 出力 txt によるチャプタースキップ。
* **録画・予約**: 録画予約機能、ライブ画面からの即時録画開始。
* **ソーシャル & UI**: ニコニコ実況表示、カスタムテーマ、L 字クロップ、KonomiTV ユーザー連携。
* **管理機能**: チャンネルピン留め、録画番組マイリスト、ミニ番組表。
* **操作性改善**: ライブ視聴中の左右キー選局、その他 KonomiTV 拡張への対応。

## バグ対応等
* 操作中にアプリクラッシュなどが発生した場合は、Githubのissueへ記載してください。動作環境と操作した履歴、現象を詳しく記載いただけると助かります。

---

## 🤝 SpecialThanks!
本アプリの開発にあたり、以下の素晴らしいプロジェクトと成果物を活用させていただいております。

* **[tsreadex](https://github.com/xtne6f/tsreadex)**: TS ストリーム解析および読み込み処理の基盤。
* **[aribb24.js](https://github.com/monyone/aribb24.js)**: 高精度な字幕描画ロジックの提供。
* **[KonomiTV](https://github.com/tsukumijima/KonomiTV)**: 強力な API バックエンドおよび配信プラットフォーム。
* **[Mirakurun](https://github.com/Chinachu/Mirakurun)**: チューナー管理および配信 API。

---

## 🛠️ 技術構成
* **UI**: Jetpack Compose for TV
* **Player**: ExoPlayer / Media3
* **Image**: Coil (Custom Transformation for Scene Search)
