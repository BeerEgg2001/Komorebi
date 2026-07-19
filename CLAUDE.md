# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## プロジェクト概要

**Komorebi** は、KonomiTV / EDCB バックエンド / Mirakurun（オプション）に対応した、Android TV 向けテレビ視聴クライアントアプリ。オール Kotlin・Jetpack Compose (Compose for TV) で構築されている。

## コーディング規約

- コード中のコメントは日本語で記述する。
- 常に日本語で会話する
- 技術的な説明も日本語で行う
- コード内のコメントは日本語で記述
- エラーメッセージの解説は日本語で
- README.mdなどのドキュメントも日本語で作成

## ビルド・実行コマンド

```sh
./gradlew assembleDebug    # デバッグビルド
./gradlew assembleRelease  # リリースビルド（ABI分割: armeabi-v7a / arm64-v8a + Universal APK）
./gradlew installDebug     # 接続中のAndroid TVデバイス/エミュレータへインストール
./gradlew lint
./gradlew clean
```

- `app/src/test` / `app/src/androidTest` は現状存在しない（自動テストなし）。ktlint/detekt 等の静的解析設定も未導入。テストコードや静的解析タスクを前提とした指示は行わないこと。
- ビルド前に、`.gitignore` 対象のため未コミットのフォントファイル（Noto Sans JP）を手動で `app/src/main/res/font/` に配置する必要がある（手順は README.md 参照）。

## リポジトリのエチケット

- ブランチは `features/<バージョン名>`（例: `features/1.1.0-beta7`）を作業ベースとし、個別の修正は `fix/<内容>-<バージョン名>` のようなブランチから PR で取り込む。
- コミットメッセージは `fix: ・変更点1 ・変更点2` のように、先頭に種別（`fix`/`feat` 等）を付け、複数の変更点は `・` 区切りの箇条書きにする慣習がある。

## アーキテクチャ

### レイヤー構成（MVVM + Clean Architecture）

```
ui/ (Compose画面 + State)  →  viewmodel/  →  data/repository/ (Provider抽象)  →  data/api/ (Konomi / EDCB) or data/local/ (Room)
```

DI は Dagger Hilt。モジュールは `di/`（`NetworkModule`, `DatabaseModule`, `RepositoryModule`, `DtvProviderModule`）に集約。

### マルチバックエンド抽象化（最重要ポイント）

このアプリの中核設計は「KonomiTV / EDCB / EPGStation という異なるバックエンドを、ユーザー設定に応じて透過的に切り替える」こと。コードを読むだけでは気づきにくいため明記する。

- `data/repository/DtvProviders.kt` に `LiveProvider` / `RecordProvider` / `ReserveProvider` / `EpgProvider` という機能別インターフェースを定義。
- `data/repository/DtvProviderProxy.kt`（`DtvProviderProxy`）が全インターフェースを実装し、`SettingsRepository.backendType`（"EDCB" / "EPGSTATION" / それ以外=KonomiTV）を見て、実体（`KonomiRepository` / `data/repository/edcb/*` / `EpgStationRepository`）へ動的にルーティングする「代理人（Proxy）」パターン。Hilt の `DtvProviderModule` は各インターフェースを常に `DtvProviderProxy` にバインドする。
- **新しいバックエンド機能を追加する際は、まずこの4つのインターフェースのどれかにメソッドを足し、各実装（Konomi/EDCB/EPGStation）＋Proxyのルーティング分岐を揃える必要がある。**
- EDCB は独自の TCP バイナリプロトコル（`data/api/edcb/`）、KonomiTV/EPGStation は HTTP REST（Retrofit）と、通信方式そのものが異なる点に注意。

### 動画再生エンジンが2種類ある点に注意

- 通常再生（KonomiTV/EDCB経由のHLS等）は Media3 (ExoPlayer)。ただし `local_repo/` のカスタムパッチ版（`androidx.media3:*:1.7.1-komorebi`、`app/build.gradle.kts` の `resolutionStrategy.force(...)` で全モジュール強制上書き）を使用しているため、公式リリースの Media3 とは挙動が異なる場合がある。
- SMB（NAS）上のファイル直接再生（`ui/video/smb/`）は ExoPlayer ではなく libVLC（`libs/*.aar`）を採用。エンジンが違うため、片方の修正がもう片方に影響しない。

### ネイティブ層（C++）

`app/src/main/cpp/` に tsreadex 由来の TS ストリーム処理を CMake でビルドしている。`abiFilters` は `armeabi-v7a` / `arm64-v8a` に意図的に限定（Android TV 端末を想定した制限であり、対応漏れではない）。

## 付属ツール

- `KomorebiConfigurator/` — .NET(Avalonia)製のセットアップツール。EDCB連携（`resolver.lua`等）を配置する。
- `KomorebiThumbnailer/` — 録画ファイルからシークバー用サムネイル（`.tile.webp`）を生成するツール。

これらは Android アプリ本体（`app/`）とはビルド系統が異なる独立ツール。
