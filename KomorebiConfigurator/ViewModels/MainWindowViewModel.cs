using System;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using KomorebiConfigurator.Models;

namespace KomorebiConfigurator.ViewModels;

public partial class MainWindowViewModel : ViewModelBase
{
    private readonly AppConfig _config;

    // --- 画面とバインドするプロパティ (ObservableProperty属性で自動生成) ---

    [ObservableProperty]
    private string _httpPublicPath = "";

    [ObservableProperty]
    private string _newPhysicalPath = "";

    [ObservableProperty]
    private string _newAlias = "rec";

    [ObservableProperty]
    private string _statusMessage = "準備完了";

    [ObservableProperty]
    private string _addUpdateButtonText = "リストに追加";

    // 選択中のマッピング
    [ObservableProperty]
    private PathMapping? _selectedMapping;

    // CommunityToolkit.Mvvm の魔法：SelectedMapping が変わった時に自動で呼ばれるメソッド
    partial void OnSelectedMappingChanged(PathMapping? value)
    {
        if (value != null)
        {
            // 選択されたら内容をコピーし、ボタンを「更新」にする
            NewPhysicalPath = value.PhysicalPath;
            NewAlias = value.Alias;
            AddUpdateButtonText = "リストを更新";
        }
        else
        {
            // 選択が解除されたら空にして、ボタンを「追加」に戻す
            NewPhysicalPath = "";
            NewAlias = "rec";
            AddUpdateButtonText = "リストに追加";
        }
    }

    // リストに表示するマッピングデータ
    public ObservableCollection<PathMapping> Mappings => _config.Mappings;

    // --- コンストラクタ ---
    public MainWindowViewModel()
    {
        // 起動時に設定を読み込む
        _config = AppConfig.Load();
        HttpPublicPath = _config.HttpPublicPath;
    }

    // --- ボタンが押された時の処理 (RelayCommand属性で自動生成) ---

    // 設定を保存する（パス入力時などに呼ばれる）
    [RelayCommand]
    private void SaveConfig()
    {
        _config.HttpPublicPath = HttpPublicPath;
        _config.Save();
        StatusMessage = "設定を保存しました。";
    }

    // 追加 / 更新処理
    [RelayCommand]
    private void AddOrUpdateMapping()
    {
        if (string.IsNullOrWhiteSpace(NewPhysicalPath) || string.IsNullOrWhiteSpace(NewAlias))
        {
            StatusMessage = "エラー: 物理パスとエイリアス名を入力してください。";
            return;
        }

        if (SelectedMapping != null)
        {
            // ==========================================
            // 【更新モード】（リスト項目が選択されている時）
            // ==========================================
            
            // 変更後のエイリアス名が、"他の項目"で既に使われていないか重複チェック
            if (Mappings.Any(m => m != SelectedMapping && m.Alias == NewAlias))
            {
                StatusMessage = $"エラー: エイリアス '{NewAlias}' は他で既に使用されています。";
                return;
            }

            // 選択されている項目のデータを上書き
            SelectedMapping.PhysicalPath = NewPhysicalPath;
            SelectedMapping.Alias = NewAlias;
            StatusMessage = $"マッピング '{NewAlias}' を更新しました。";
        }
        else
        {
            // ==========================================
            // 【追加モード】（リスト項目が選択されていない時）
            // ==========================================
            
            // 新規のエイリアス名が既に存在しないか重複チェック
            if (Mappings.Any(m => m.Alias == NewAlias))
            {
                StatusMessage = $"エラー: エイリアス '{NewAlias}' は既に存在します。";
                return;
            }

            // 新しい項目を追加
            Mappings.Add(new PathMapping { PhysicalPath = NewPhysicalPath, Alias = NewAlias });
            StatusMessage = "マッピングを追加しました。";
        }

        // リストの表示を強制的に更新（Avaloniaの仕様対策）
        // ※クリアした瞬間に SelectedMapping が自動的に null になり、入力欄もリセットされます
        var temp = Mappings.ToList();
        Mappings.Clear();
        foreach (var item in temp) Mappings.Add(item);

        // 念のため明示的に null にして追加モードに戻す
        SelectedMapping = null; 
        SaveConfig();
    }

    // 選択されたマッピングの削除
    [RelayCommand]
    private void RemoveMapping()
    {
        if (SelectedMapping != null)
        {
            Mappings.Remove(SelectedMapping);
            SaveConfig();
            StatusMessage = "マッピングを削除しました。";
        }
    }

    // ★ メインイベント：セットアップ実行
    [RelayCommand]
    private void ExecuteSetup()
    {
        if (string.IsNullOrWhiteSpace(HttpPublicPath) || !Directory.Exists(HttpPublicPath))
        {
            StatusMessage = "エラー: 正しい HttpPublic フォルダのパスを指定してください。";
            return;
        }

        if (Mappings.Count == 0)
        {
            StatusMessage = "エラー: 最低1つのマッピングを追加してください。";
            return;
        }

        try
        {
            // 1. Luaスクリプトの生成と配置
            GenerateLuaScript();

            // 2. シンボリックリンクの作成 (Windowsのみ)
            if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
            {
                CreateSymbolicLinksOnWindows();
                StatusMessage = "セットアップが完了しました！（Lua生成 ＆ リンク作成）";
            }
            else
            {
                StatusMessage = "Mac環境のためリンク作成はスキップしました。（Lua生成のみ完了）";
            }
        }
        catch (Exception ex)
        {
            StatusMessage = $"エラーが発生しました: {ex.Message}";
        }
    }

    // --- 内部ロジック（修正版） ---

    private void GenerateLuaScript()
    {
        // legacy ではなく komorebi フォルダに配置
        string komorebiDir = Path.Combine(HttpPublicPath, "komorebi");
        if (!Directory.Exists(komorebiDir))
        {
            Directory.CreateDirectory(komorebiDir);
        }

        // ファイル名もスッキリと resolver.lua に
        string luaPath = Path.Combine(komorebiDir, "resolver.lua");
        StringBuilder sb = new StringBuilder();

        sb.AppendLine("-- ==========================================");
        sb.AppendLine("-- Komorebi File Resolver (Auto Generated)");
        sb.AppendLine("-- ==========================================");
        
        // MAPPINGテーブルのみ文字列として組み立て
        sb.AppendLine("local MAPPING = {");
        foreach (var map in Mappings)
        {
            string safePhysicalPath = map.PhysicalPath.Replace("\\", "\\\\");
            sb.AppendLine($"    [\"{safePhysicalPath}\"] = \"{map.Alias}\",");
        }
        sb.AppendLine("}");
        
        sb.Append(GetLuaLogicPart());

        File.WriteAllText(luaPath, sb.ToString(), new UTF8Encoding(false));
    }

    private void CreateSymbolicLinksOnWindows()
    {
        // リンクを配置する video フォルダを特定（なければ作成）
        string videoDir = Path.Combine(HttpPublicPath, "video");
        if (!Directory.Exists(videoDir))
        {
            Directory.CreateDirectory(videoDir);
        }

        foreach (var map in Mappings)
        {
            // HttpPublic 直下ではなく video フォルダの中に作成
            string linkPath = Path.Combine(videoDir, map.Alias);
            string targetPath = map.PhysicalPath;

            if (Directory.Exists(linkPath) || File.Exists(linkPath))
            {
                continue;
            }

            string cmd = $"/c mklink /D \"{linkPath}\" \"{targetPath}\"";

            ProcessStartInfo psi = new ProcessStartInfo
            {
                FileName = "cmd.exe",
                Arguments = cmd,
                UseShellExecute = true,
                WindowStyle = ProcessWindowStyle.Hidden
            };

            Process.Start(psi)?.WaitForExit();
        }
    }

private string GetLuaLogicPart()
    {
        // ユーザーが設定した MAPPING を動的に生成
        var mappings = string.Join(",\n", Mappings.Select(m =>
        {
            // Windowsのパス区切り \ を Lua の文字列リテラルとして安全にするために \\ に置換
            string escapedPath = m.PhysicalPath.Replace("\\", "\\\\");
            return $"    [\"{escapedPath}\"] = \"{m.Alias}\"";
        }));

        // C# 11の生文字列リテラルを使用して、安全な Lua テンプレートを返す
        var luaTemplate = $$"""
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
{{mappings}}
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
""";
        return luaTemplate;
    }
}