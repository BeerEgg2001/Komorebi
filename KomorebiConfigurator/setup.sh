#!/bin/bash

echo "================================================="
echo " Komorebi Resolver Setup for EDCB (Linux)"
echo "================================================="
echo ""

# パスの入力
read -p "1. EDCBの HttpPublic フォルダの絶対パスを入力してください: " HTTP_PUBLIC_DIR
if [ ! -d "$HTTP_PUBLIC_DIR" ]; then
    echo "エラー: 指定された HttpPublic フォルダが見つかりません。"
    exit 1
fi

read -p "2. 録画フォルダの実体パスを入力してください: " REC_DIR
if [ ! -d "$REC_DIR" ]; then
    echo "エラー: 指定された録画フォルダが見つかりません。"
    exit 1
fi

read -p "3. 公開用エイリアス名を入力してください [デフォルト: rec]: " ALIAS
ALIAS=${ALIAS:-rec}

echo ""
echo "以下の設定でセットアップを行います:"
echo " - HttpPublic : $HTTP_PUBLIC_DIR"
echo " - 録画フォルダ : $REC_DIR"
echo " - エイリアス   : $ALIAS"
read -p "よろしいですか？ (y/n): " CONFIRM
if [ "$CONFIRM" != "y" ] && [ "$CONFIRM" != "Y" ]; then
    echo "セットアップをキャンセルしました。"
    exit 0
fi

# シンボリックリンクの作成
ln -sfn "$REC_DIR" "$HTTP_PUBLIC_DIR/$ALIAS"
echo "[OK] シンボリックリンクを作成しました: $HTTP_PUBLIC_DIR/$ALIAS -> $REC_DIR"

# Luaスクリプトの生成
# HttpPublic直下ではなく、view.lua等と同じlegacyフォルダに配置します
LUA_DIR="$HTTP_PUBLIC_DIR/legacy"
if [ ! -d "$LUA_DIR" ]; then
    mkdir -p "$LUA_DIR"
fi
LUA_FILE="$LUA_DIR/legacy/komorebi_resolver.lua"

# ヒアドキュメントでLuaスクリプトを生成 ('EOF'でBashの変数展開を無効化)
cat << 'EOF' > "$LUA_FILE"
-- ==========================================
-- Komorebi File Resolver
-- ==========================================
local MAPPING = {
EOF

# マッピング行の追加 (Bash変数を展開するためここは別途echoで追記)
echo "    [\"$REC_DIR\"] = \"$ALIAS\"" >> "$LUA_FILE"

# 残りのLuaロジックを追記
cat << 'EOF' >> "$LUA_FILE"
}

-- HTTPヘッダの出力
mg.write("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n\r\n")

-- URLクエリパラメータからIDを取得
local id = tonumber(mg.get_var(mg.request_info.query_string, "id"))
if not id then
    mg.write('{"error":"Missing or invalid video id"}')
    return
end

-- EDCBから録画情報を取得
local recInfo = edcb.GetRecFileInfo(id)
if not recInfo then
    mg.write('{"error":"RecInfo not found for ID: ' .. tostring(id) .. '"}')
    return
end

local filePath = recInfo.recFilePath
local matchedAlias = nil
local relativePath = nil

-- マッピングの探索
for localPath, alias in pairs(MAPPING) do
    local normalizedFilePath = filePath:gsub("\\", "/")
    local normalizedLocalPath = localPath:gsub("\\", "/")

    -- パスが一致するか前方一致で判定
    if string.sub(normalizedFilePath, 1, string.len(normalizedLocalPath)) == normalizedLocalPath then
        matchedAlias = alias

        -- 文字欠け防止のための安全な切り出し処理
        local remainder = string.sub(normalizedFilePath, string.len(normalizedLocalPath) + 1)
        relativePath = string.gsub(remainder, "^/+", "")
        break
    end
end

-- マッピングに失敗した場合は、設定のヒントとして実際のパスを返す
if not matchedAlias then
    local safePath = filePath:gsub("\\", "\\\\")
    mg.write('{"error":"Path not mapped in komorebi_resolver.lua", "detected_path":"' .. safePath .. '"}')
    return
end

-- URLエンコード処理関数
local function urlencode(str)
    if str then
        str = string.gsub(str, "\n", "\r\n")
        str = string.gsub(str, "([^%w %-%_%.%~])", function(c)
            return string.format("%%%02X", string.byte(c))
        end)
        str = string.gsub(str, " ", "%%20")
    end
    return str
end

-- 相対パスをエンコードしてベースURLを構築
local encodedPath = urlencode(relativePath)
local baseUrl = "/" .. matchedAlias .. "/" .. encodedPath

-- JSONレスポンスの構築
local json = string.format([[
{
    "video_url": "%s",
    "thumbnail_url": "%s.jpg",
    "chapter_url": "%s.chapter.txt",
    "chapter_alt_url": "%s",
    "tile_image_url": "%s.tile.webp",
    "tile_json_url": "%s.tile.json"
}
]], baseUrl, baseUrl, baseUrl, string.gsub(baseUrl, "%.ts$", "") .. ".chapter.txt", baseUrl, baseUrl)

mg.write(json)
EOF

echo "[OK] Luaスクリプトを生成しました: $LUA_FILE"
echo ""
echo "🎉 セットアップが完了しました！"
echo "Komorebiアプリで EDCB への直接アクセスが利用可能になります。"
