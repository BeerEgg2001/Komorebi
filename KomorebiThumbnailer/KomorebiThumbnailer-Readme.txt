KomorebiThumbnailer ユーザーガイド

KomorebiThumbnailer は、録画ファイル（.ts、.mp4 など）から、Komorebi アプリのシークバーで表示される「サムネイルタイル画像（.tile.webp）」と「メタデータ（.tile.json）」を超高速で生成する専用ツールです。

---

■ 共通仕様・オプション

このツールはコマンドライン（ターミナル）から実行します。基本的なコマンドの形式は以下の通りです。

KomorebiThumbnailer [入力ファイルのパス] [オプション]

【利用可能なオプション】
・--ffmpeg-dir <パス>: ffmpeg および ffprobe があるディレクトリを指定します（省略時はツールと同じフォルダ、またはシステムの PATH から探索します）。
・--out-dir <パス>: サムネイルの保存先フォルダを指定します（省略時は録画ファイルと同じ場所に保存されます）。

【生成されるファイル】
・[元のファイル名].tile.webp: タイル画像本体（320x180 px、10秒間隔）
・[元のファイル名].tile.json: 画像サイズや間隔などの情報が格納されたメタデータ

---

■ Windows 環境での使い方

Windows では、配布された KomorebiThumbnailer.exe を使用します。

【前提条件】
・ffmpeg.exe と ffprobe.exe が必要です。
・最も簡単な方法は、KomorebiThumbnailer.exe と同じフォルダにこれらを配置することです。

1. EDCB で録画終了後に自動生成する（推奨）
EDCB の録画終了後実行バッチ（PostRecEnd.bat）に以下の1行を追記することで、録画完了の瞬間に自動でサムネイルが生成されます。

"C:\EDCB\Tools\KomorebiThumbnailer.exe" "$FilePath$" --ffmpeg-dir "C:\EDCB\Tools"
(※ パスはご自身の環境に合わせて適宜変更してください)

2. 過去の録画ファイルを一括で処理する
既存の録画ファイルをまとめて処理する場合は、以下の内容を GenerateAllThumbnails.bat という名前で保存し、KomorebiThumbnailer.exe と同じフォルダに置いて実行してください。

@echo off
setlocal enabledelayedexpansion
echo ==================================================
echo   Komorebi サムネイル一括生成バッチ (Windows版)
echo ==================================================
set TARGET_DIR=D:\Recorded
set THUMBNAILER=%~dp0KomorebiThumbnailer.exe
set FFMPEG_DIR=%~dp0
for /r "%TARGET_DIR%" %%i in (*.ts *.m2ts *.mp4) do (
    if exist "%%i.tile.webp" (
        echo [スキップ] %%~nxi
    ) else (
        echo [処理中] %%~nxi
        "%THUMBNAILER%" "%%i" --ffmpeg-dir "%FFMPEG_DIR%"
    )
)
echo 完了しました。
pause

---

■ Linux 環境での使い方

Linux では、ビルドされたバイナリファイル（拡張子なしの KomorebiThumbnailer）を使用します。

【前提条件】
・システムに ffmpeg がインストールされていること（sudo apt install ffmpeg など）。
・バイナリファイルに実行権限が付与されていること。
  chmod +x KomorebiThumbnailer

1. 基本的な使い方（単一ファイルの処理）
ターミナルから以下のように実行します。

./KomorebiThumbnailer "/path/to/recorded/video.ts"

2. 過去の録画ファイルを一括で処理する
以下の内容を generate_thumbnails.sh として保存し、実行権限（chmod +x）を付けて実行してください。

#!/bin/bash
# ==================================================
#   Komorebi サムネイル一括生成スクリプト (Linux版)
# ==================================================
TARGET_DIR="/mnt/data/recorded"
THUMBNAILER="$(dirname "$0")/KomorebiThumbnailer"
find "$TARGET_DIR" -type f \( -name "*.ts" -o -name "*.m2ts" -o -name "*.mp4" \) -print0 | while read -d $'\0' FILE; do
    OUTPUT_FILE="$FILE.tile.webp"
    if [ -f "$OUTPUT_FILE" ]; then
        echo "[スキップ] $(basename "$FILE")"
    else
        echo "[処理中] $(basename "$FILE")"
        "$THUMBNAILER" "$FILE" < /dev/null
    fi
done
echo "すべての処理が完了しました。"

---

■ トラブルシューティング

・「Error: FFmpeg not found」と表示される
  ffmpeg.exe（または ffprobe.exe）が見つかっていません。ツールと同じフォルダに配置するか、--ffmpeg-dir オプションで正しいパスを指定してください。

・一括処理バッチが途中で止まってしまう / 非常に遅い
  ネットワークドライブ（VPN経由のSMBなど）越しに実行すると、通信速度や遅延により極端にパフォーマンスが低下します。可能な限り、録画ファイルが保存されている物理マシン上で直接実行することをお勧めします。

■ ビルド方法

ビルドにはPythonとpipのインストールが必要です。以下のコマンドを実行することで、ビルドできます。

---

pip install pyinstaller
pyinstaller --onefile KomorebiThumbnailer.py

---