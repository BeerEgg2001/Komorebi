using System;
using System.Collections.ObjectModel;
using System.IO;
using Newtonsoft.Json;

namespace KomorebiConfigurator.Models;

// 1つのマッピング（実体パスとエイリアス）を表すクラス
public class PathMapping
{
    public string PhysicalPath { get; set; } = "";
    public string Alias { get; set; } = "rec";
}

// アプリ全体の設定を管理し、ファイルへの保存・読み込みを行うクラス
public class AppConfig
{
    // HttpPublicフォルダのパス
    public string HttpPublicPath { get; set; } = "";
    
    // マッピングのリスト（画面のリストと連動しやすいObservableCollectionを使用）
    public ObservableCollection<PathMapping> Mappings { get; set; } = new();

    // 設定ファイルの保存先（ユーザーのAppData/Local/KomorebiConfigurator などを想定）
    [JsonIgnore] // このプロパティ自体はJSONに保存しない
    private static string ConfigFilePath => 
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), 
            "KomorebiConfigurator", "settings.json");

    // 設定をファイルに保存するメソッド
    public void Save()
    {
        try
        {
            var directory = Path.GetDirectoryName(ConfigFilePath);
            if (!Directory.Exists(directory) && directory != null)
            {
                Directory.CreateDirectory(directory);
            }

            var json = JsonConvert.SerializeObject(this, Formatting.Indented);
            File.WriteAllText(ConfigFilePath, json);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"設定の保存に失敗しました: {ex.Message}");
        }
    }

    // 設定をファイルから読み込むメソッド（ファイルがなければ初期状態を返す）
    public static AppConfig Load()
    {
        try
        {
            if (File.Exists(ConfigFilePath))
            {
                var json = File.ReadAllText(ConfigFilePath);
                var config = JsonConvert.DeserializeObject<AppConfig>(json);
                if (config != null) return config;
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"設定の読み込みに失敗しました: {ex.Message}");
        }

        // 失敗した時や初回起動時は空の設定を返す
        return new AppConfig();
    }
}