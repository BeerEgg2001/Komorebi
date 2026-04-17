using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Platform.Storage;
using KomorebiConfigurator.ViewModels;
using System;

namespace KomorebiConfigurator.Views;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
    }

    // 「参照...」ボタンが押された時の処理
    private async void BrowseHttpPublic_Click(object sender, RoutedEventArgs e)
    {
        // 現在のウィンドウから StorageProvider (ダイアログ機能) を取得
        var topLevel = TopLevel.GetTopLevel(this);
        if (topLevel == null) return;

        // フォルダ選択ダイアログを開く
        var result = await topLevel.StorageProvider.OpenFolderPickerAsync(new FolderPickerOpenOptions
        {
            Title = "EDCBの HttpPublic フォルダを選択してください",
            AllowMultiple = false
        });

        // フォルダが選択された場合
        if (result.Count >= 1)
        {
            // 選択されたパスを取得 (Mac/Windows両対応のローカルパス)
            var selectedPath = result[0].Path.LocalPath;

            // ViewModel (データ側) にパスを渡す
            if (DataContext is MainWindowViewModel vm)
            {
                vm.HttpPublicPath = selectedPath;
                
                // パスが変更されたら自動的に設定を保存する
                vm.SaveConfigCommand.Execute(null);
            }
        }
    }

    // 「録画フォルダ実体」の参照...ボタンが押された時の処理
    private async void BrowsePhysicalPath_Click(object sender, RoutedEventArgs e)
    {
        var topLevel = TopLevel.GetTopLevel(this);
        if (topLevel == null) return;

        var result = await topLevel.StorageProvider.OpenFolderPickerAsync(new FolderPickerOpenOptions
        {
            Title = "録画ファイルの実体があるフォルダを選択してください",
            AllowMultiple = false
        });

        if (result.Count >= 1)
        {
            var selectedPath = result[0].Path.LocalPath;
            if (DataContext is MainWindowViewModel vm)
            {
                vm.NewPhysicalPath = selectedPath;
            }
        }
    }
}