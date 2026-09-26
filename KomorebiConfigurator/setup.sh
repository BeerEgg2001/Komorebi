#!/bin/bash

echo "================================================="
echo " Komorebi Resolver Setup for EDCB (Linux)"
echo "================================================="
echo ""

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
echo " - HttpPublic フォルダ : $HTTP_PUBLIC_DIR"
echo " - 録画フォルダ        : $REC_DIR"
echo " - エイリアス          : $ALIAS"
read -p "よろしいですか？ (y/n): " CONFIRM
if [ "$CONFIRM" != "y" ] && [ "$CONFIRM" != "Y" ]; then
    echo "セットアップをキャンセルしました。"
    exit 0
fi

# ★ 修正: 以下、各ステップの成否を明示的にチェックする。
# 以前は失敗しても後続の処理をそのまま続行し、最後に無条件で
# 「セットアップが完了しました」と表示してしまっていた
# (パーミッション不足等で mkdir/ln が失敗しても気付けなかった)。

VIDEO_DIR="$HTTP_PUBLIC_DIR/video"
if [ ! -d "$VIDEO_DIR" ]; then
    mkdir -p "$VIDEO_DIR" || {
        echo "エラー: $VIDEO_DIR の作成に失敗しました。書き込み権限を確認してください。"
        exit 1
    }
fi

ln -sfn "$REC_DIR" "$VIDEO_DIR/$ALIAS" || {
    echo "エラー: シンボリックリンクの作成に失敗しました: $VIDEO_DIR/$ALIAS -> $REC_DIR"
    exit 1
}
echo "[OK] シンボリックリンクを作成しました: $VIDEO_DIR/$ALIAS -> $REC_DIR"

LUA_DIR="$HTTP_PUBLIC_DIR/komorebi"
if [ ! -d "$LUA_DIR" ]; then
    mkdir -p "$LUA_DIR" || {
        echo "エラー: $LUA_DIR の作成に失敗しました。書き込み権限を確認してください。"
        exit 1
    }
fi
LUA_FILE="$LUA_DIR/resolver.lua"

cat << 'EOF' > "$LUA_FILE"
-- ==========================================
-- Komorebi File Resolver (Cross-Platform)
-- ==========================================
WIN32 = not package.config:find('^/')
DIR_SEPS = WIN32 and '\\/' or '/'
DIR_SEP = WIN32 and '\\' or '/'

-- EMWUIの標準util.luaを読み込む (GetVarIntやCsrfTokenなどのため)
local utilPath = mg.document_root:gsub('['..DIR_SEPS..']*$', DIR_SEP) .. 'api' .. DIR_SEP .. 'util.lua'
if not package.loaded["api.util"] then
    pcall(dofile, utilPath)
end

-- ====================================================
-- 安全なファイル検索関数 (util.luaへの依存を断ち切る)
-- ====================================================
local function SafeFindFile(path)
    if not edcb.FindFile then return nil end
    local ff = edcb.FindFile(path, 1)
    return ff and ff[1]
end

-- ====================================================
-- 簡易JSONエンコーダ (Lua環境にResponseJsonがない場合へのフェールセーフ)
-- ====================================================
local function EncodeJson(val)
    local t = type(val)
    if t == "string" then
        local escaped = val:gsub("\\", "\\\\"):gsub('"', '\\"'):gsub("\n", "\\n"):gsub("\r", "\\r"):gsub("\t", "\\t")
        return '"' .. escaped .. '"'
    elseif t == "number" or t == "boolean" then
        return tostring(val)
    elseif t == "table" then
        local isArray = true
        local maxKey = 0
        local count = 0
        for k, v in pairs(val) do
            if type(k) ~= "number" or k <= 0 or math.floor(k) ~= k then
                isArray = false
                break
            end
            if k > maxKey then maxKey = k end
            count = count + 1
        end
        if isArray and count == maxKey then
            if count == 0 then return "[]" end
            local parts = {}
            for i = 1, maxKey do table.insert(parts, EncodeJson(val[i])) end
            return "[" .. table.concat(parts, ", ") .. "]"
        else
            local parts = {}
            for k, v in pairs(val) do
                local keyStr = type(k) == "string" and k or tostring(k)
                table.insert(parts, '"' .. keyStr .. '": ' .. EncodeJson(v))
            end
            return "{" .. table.concat(parts, ", ") .. "}"
        end
    elseif val == nil then
        return "null"
    else
        return '""'
    end
end

-- ====================================================
-- 共通の安全なJSONレスポンス関数
-- ====================================================
local function SafeResponseJson(data)
    local ok, result = pcall(function()
        local jsonStr = EncodeJson(data)
        mg.write("HTTP/1.1 200 OK\r\n")
        mg.write("Content-Type: application/json; charset=utf-8\r\n")
        mg.write("Access-Control-Allow-Origin: *\r\n\r\n")
        mg.write(jsonStr)
    end)
    if not ok then
        mg.write("HTTP/1.1 500 Internal Server Error\r\nContent-Type: application/json; charset=utf-8\r\n\r\n")
        local safeErr = tostring(result):gsub("\\", "\\\\"):gsub('"', '\\"'):gsub("\n", "\\n"):gsub("\r", "")
        mg.write('{"error":"Lua JSON Encoding Error", "detail":"' .. safeErr .. '"}')
    end
end

local MAPPING = {
EOF

# ★ Bash変数を展開してマッピング行を動的に追加
echo "    [\"$REC_DIR\"] = \"$ALIAS\"" >> "$LUA_FILE"

cat << 'EOF' >> "$LUA_FILE"
}

-- GetVarIntの代わりにmg.get_varを使用して互換性を確保
local id_str = mg.get_var(mg.request_info.query_string, 'id')
local id = id_str and tonumber(id_str) or nil

-- ====================================================
-- 【分岐1】idの指定がない場合 (共通設定・ctok・画質の取得)
-- ====================================================
if not id then
    local ok, result = pcall(function()
        local optionList = {}
        if XCODE_OPTIONS then
            for i, v in ipairs(XCODE_OPTIONS) do
                if v.xcoder and v.xcoder ~= '' then
                    table.insert(optionList, { id = tostring(i), name = v.name or "" })
                end
            end
        end

        local ctok_x = ""
        local ctok_v = ""
        if CsrfToken then
            ctok_x = CsrfToken('view') or ""
            ctok_v = CsrfToken('tvcast') or ""
        end

        SafeResponseJson({
            ctok = { xcode = ctok_x, view = ctok_v },
            option = optionList,
            recFolder = EdcbRecFolderPathList and EdcbRecFolderPathList() or {}
        })
    end)
    if not ok then
        mg.write("HTTP/1.1 500 Internal Server Error\r\nContent-Type: application/json; charset=utf-8\r\n\r\n")
        local safeErr = tostring(result):gsub("\\", "\\\\"):gsub('"', '\\"'):gsub("\n", "\\n"):gsub("\r", "")
        mg.write('{"error":"Failed to initialize settings", "detail":"' .. safeErr .. '"}')
    end
    return
end

-- ====================================================
-- 【分岐2】idが指定された場合 (録画ファイルのパス解決)
-- ====================================================
local ok, processErr = pcall(function()
    local recInfo = edcb.GetRecFileInfo(id)
    if not recInfo then
        SafeResponseJson({error = 'RecInfo not found for ID: ' .. tostring(id)})
        return
    end

    local filePath = recInfo.recFilePath
    if not filePath or filePath == "" then
        SafeResponseJson({error = 'RecFilePath is empty for ID: ' .. tostring(id)})
        return
    end

    filePath = string.gsub(filePath, "[%z\1-\8\11\12\14-\31]", "")
    local normalizedFilePath = filePath:gsub("\\", "/")
    local matchedAlias = nil
    local relativePath = nil
    local cleanRemainder = nil

    for localPath, alias in pairs(MAPPING) do
        local normalizedLocalPath = localPath:gsub("\\", "/")
        if string.sub(normalizedFilePath, 1, string.len(normalizedLocalPath)) == normalizedLocalPath then
            matchedAlias = alias
            local remainder = string.sub(normalizedFilePath, string.len(normalizedLocalPath) + 1)
            cleanRemainder = string.gsub(remainder, "^/+", "")
            relativePath = "video/" .. alias .. "/" .. cleanRemainder
            break
        end
    end

    if not matchedAlias then
        local safePath = filePath:gsub("\\", "\\\\"):gsub('"', '\\"')
        SafeResponseJson({error = 'Path not mapped', detected_path = safePath})
        return
    end

    local encodedPath = mg.url_encode(cleanRemainder):gsub('%%2[fF]', '/')
    local baseUrl = "/video/" .. matchedAlias .. "/" .. encodedPath

    local thumbnailUrl = ""
    local ff = SafeFindFile(filePath .. ".jpg")
    if not ff and not WIN32 then
        ff = SafeFindFile(filePath .. ".JPG")
    end

    if ff then
        thumbnailUrl = baseUrl .. ".jpg"
    else
        local thumbHash = mg.md5(string.lower(filePath))
        thumbnailUrl = "/video/thumbs/" .. thumbHash .. ".jpg"
    end

    local fullPath = ""
    if PathAppend then
        fullPath = PathAppend(mg.document_root, relativePath)
    else
        fullPath = mg.document_root:gsub('['..DIR_SEPS..']*$', '') .. DIR_SEP .. relativePath:gsub('^['..DIR_SEPS..']*', '')
    end

    local chapterUrl = nil
    for i, ext in ipairs({'.chapter', '.chapters.txt', '.chapter.txt'}) do
        for j, dir in ipairs({'%1chapters', ''}) do
            local fpath = fullPath:gsub('(['..DIR_SEPS..'])([^'..DIR_SEPS..']*)$', dir..'%1%2'):gsub('%.[0-9A-Za-z]+$', '') .. ext
            if SafeFindFile(fpath) then
                local docPath = NativeToDocumentPath and NativeToDocumentPath(fpath) or fpath:sub(mg.document_root:len() + 1)
                chapterUrl = '/' .. mg.url_encode(docPath):gsub('%%2[fF]', '/')
                break
            end
        end
        if chapterUrl then break end
    end

    SafeResponseJson({
        video_url = baseUrl,
        thumbnail_url = thumbnailUrl,
        chapter_url = baseUrl .. ".chapter.txt",
        chapter_alt_url = chapterUrl or "",
        tile_image_url = baseUrl .. ".tile.webp",
        tile_json_url = baseUrl .. ".tile.json"
    })
end)

-- スクリプト内でクラッシュした場合のフェールセーフ
if not ok then
    mg.write("HTTP/1.1 500 Internal Server Error\r\nContent-Type: application/json; charset=utf-8\r\n\r\n")
    local safeErrMsg = tostring(processErr):gsub("\\", "\\\\"):gsub('"', '\\"'):gsub('\n', ' '):gsub('\r', '')
    mg.write('{"error":"Fatal Lua Error", "detail":"' .. safeErrMsg .. '"}')
end
EOF

# ★ 修正: ヒアドキュメントの書き込み自体が失敗する(ディスク容量不足等)ケースに備え、
# 生成後のファイルが実際に存在し、空でないことを確認してから完了とみなす
if [ ! -s "$LUA_FILE" ]; then
    echo "エラー: $LUA_FILE の生成に失敗しました(ファイルが空、または作成されませんでした)。"
    exit 1
fi

echo "[OK] Luaスクリプトを生成しました: $LUA_FILE"
echo ""
echo "🎉 セットアップが完了しました！"
echo "Komorebiアプリで EDCB への直接アクセスが利用可能になります。"
echo ""
echo "※ 録画フォルダが複数ある場合は、このスクリプトは1つのフォルダにしか対応していません。"
echo "  $LUA_FILE 内の MAPPING テーブルに、他のフォルダの行を手動で追加してください。"
echo "  例: [\"/path/to/another/rec\"] = \"rec2\""
