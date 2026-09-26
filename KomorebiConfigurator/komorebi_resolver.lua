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
-- ★追加: 安全なファイル検索関数 (util.luaへの依存を断ち切る)
-- ====================================================
local function SafeFindFile(path)
    if not edcb.FindFile then return nil end
    local ff = edcb.FindFile(path, 1)
    return ff and ff[1]
end

-- ====================================================
-- 簡易JSONエンコーダ (Lua環境非依存)
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

-- ★ ここはユーザー固有のマッピング設定（維持）
local MAPPING = {
    ["D:\\recorded"] = "rec"
}

-- 互換性確保: GetVarIntが存在しない場合のフォールバック
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

    -- 不正な制御文字を除去
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
            -- 先頭のスラッシュを取り除く
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

    -- エンコード後、スラッシュ (%2F または %2f) を元の / に戻す
    local encodedPath = mg.url_encode(cleanRemainder):gsub('%%2[fF]', '/')
    local baseUrl = "/video/" .. matchedAlias .. "/" .. encodedPath

    local thumbnailUrl = ""
    -- ★修正: EdcbFindFilePlain の代わりに SafeFindFile を使用
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

    -- 互換性確保: PathAppendがない場合のフォールバック
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
            -- ★修正: EdcbFindFilePlain の代わりに SafeFindFile を使用
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

if not ok then
    mg.write("HTTP/1.1 500 Internal Server Error\r\nContent-Type: application/json; charset=utf-8\r\n\r\n")
    local safeErrMsg = tostring(processErr):gsub("\\", "\\\\"):gsub('"', '\\"'):gsub('\n', ' '):gsub('\r', '')
    mg.write('{"error":"Fatal Lua Error", "detail":"' .. safeErrMsg .. '"}')
end
