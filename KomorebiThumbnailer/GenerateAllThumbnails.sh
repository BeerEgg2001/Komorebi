#!/bin/bash

# ==================================================
#   Komorebi サムネイル一括生成スクリプト (Linux版)
# ==================================================

# --- 設定 ---
# 録画データがあるディレクトリ
TARGET_DIR="YOUR_DIRECTORY"

# ビルドしたバイナリのパス (スクリプトと同じフォルダにある想定)
# 違う場所にある場合はフルパスで書き換えてください
THUMBNAILER="$(dirname "$0")/KomorebiThumbnailer"

# --- チェック ---
if [ ! -f "$THUMBNAILER" ]; then
    echo "エラー: $THUMBNAILER が見つかりません。"
    exit 1
fi

if [ ! -d "$TARGET_DIR" ]; then
    echo "エラー: ディレクトリ $TARGET_DIR が存在しません。"
    exit 1
fi

echo "=================================================="
echo "  探索ディレクトリ: $TARGET_DIR"
echo "  実行バイナリ: $THUMBNAILER"
echo "=================================================="

# --- 処理開始 ---
# findコマンドで対象拡張子を再帰的に検索
# -print0 と read -d $'\0' を使うことで、ファイル名にスペースが含まれていても安全に処理できます
find "$TARGET_DIR" -type f \( -name "*.ts" -o -name "*.m2ts" -o -name "*.mp4" \) -print0 | while read -d $'\0' FILE; do
    
    # 出力予定のファイルパス
    OUTPUT_FILE="$FILE.tile.webp"
    
    # すでにタイル画像が存在するかチェック
    if [ -f "$OUTPUT_FILE" ]; then
        echo "[スキップ] すでに存在します: $(basename "$FILE")"
    else
        echo "[処理中] $(basename "$FILE")"
        
        # バイナリを実行
        # ffmpegはPATHが通っている想定。通っていない場合は --ffmpeg-dir で指定してください
        "$THUMBNAILER" "$FILE" < /dev/null
        
        # 実行結果の確認
        if [ $? -eq 0 ]; then
            echo "  -> 成功"
        else
            echo "  -> [エラー] 生成に失敗しました"
        fi
    fi
done

echo "=================================================="
echo "  すべての処理が完了しました。"
echo "=================================================="