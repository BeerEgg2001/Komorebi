-- ==========================================
-- Komorebi File Resolver
-- ==========================================
local MAPPING = {
    -- EDCBが認識しているローカルパスと、公開エイリアスの紐付け
    -- ※最後の \ は入れないでください。 \ は \\ と2つ重ねてエスケープします。
    ["D:\\"] = "rec"
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
        -- ★修正: 一致した部分以降を切り出し、先頭の余分なスラッシュをすべて削除する（文字欠け防止）
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