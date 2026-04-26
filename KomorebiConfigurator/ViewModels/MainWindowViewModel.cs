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
        var mappings = string.Join(",\n", Mappings.Select(m =>
        {
            // Windowsのパス区切り \ を Lua の文字列リテラルとして安全にするために \\ に置換
            string escapedPath = m.PhysicalPath.Replace("\\", "\\\\");
            return $"    [\"{escapedPath}\"] = \"{m.Alias}\"";
        }));

        var luaTemplate = @"-- ==========================================
-- Komorebi File Resolver (Cross-Platform)
-- ==========================================
local scriptDir = mg.script_name:gsub('[^\\/]*$', '')
dofile(scriptDir .. '../legacy/util.lua')

local MAPPING = {
" + mappings + @"
}

mg.write(""HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n\r\n"")

local id = tonumber(mg.get_var(mg.request_info.query_string, ""id""))
if not id then
    mg.write('{""error"":""Missing or invalid video id""}')
    return
end

local recInfo = edcb.GetRecFileInfo(id)
if not recInfo then
    mg.write('{""error"":""RecInfo not found for ID: ' .. tostring(id) .. '""}')
    return
end

local filePath = recInfo.recFilePath
local normalizedFilePath = filePath:gsub(""\\"", ""/"")
local matchedAlias = nil
local relativePath = nil

for localPath, alias in pairs(MAPPING) do
    local normalizedLocalPath = localPath:gsub(""\\"", ""/"")
    if string.sub(normalizedFilePath, 1, string.len(normalizedLocalPath)) == normalizedLocalPath then
        matchedAlias = alias
        local remainder = string.sub(normalizedFilePath, string.len(normalizedLocalPath) + 1)
        relativePath = string.gsub(remainder, ""^/+"", """")
        break
    end
end

if not matchedAlias then
    local safePath = filePath:gsub(""\\"", ""\\\\"")
    mg.write(string.format('{""error"":""Path not mapped"", ""detected_path"":""%s""}', safePath))
    return
end

local function urlencode(str)
    if str then
        str = string.gsub(str, ""([^%w %-%_%.%~])"", function(c)
            return string.format(""%%%02X"", string.byte(c))
        end)
        str = string.gsub(str, "" "", ""%%20"")
    end
    return str
end

local encodedPath = urlencode(relativePath)
local baseUrl = ""/"" .. matchedAlias .. ""/"" .. encodedPath

local thumbnailUrl = """"
local ff = EdcbFindFilePlain(filePath .. "".jpg"")
if not ff and not WIN32 then
    ff = EdcbFindFilePlain(filePath .. "".JPG"")
end

if ff then
    thumbnailUrl = baseUrl .. "".jpg""
else
    local thumbHash = mg.md5(string.lower(filePath))
    thumbnailUrl = ""/video/thumbs/"" .. thumbHash .. "".jpg""
end

local json = string.format([[
{
    ""video_url"": ""%s"",
    ""thumbnail_url"": ""%s"",
    ""chapter_url"": ""%s.chapter.txt"",
    ""chapter_alt_url"": ""%s"",
    ""tile_image_url"": ""%s.tile.webp"",
    ""tile_json_url"": ""%s.tile.json""
}
]], baseUrl, thumbnailUrl, baseUrl, string.gsub(baseUrl, ""%.ts$"", """") .. "".chapter.txt"", baseUrl, baseUrl)

mg.write(json)
";
        return luaTemplate;
    }
}