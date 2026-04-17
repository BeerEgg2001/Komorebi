@echo off
setlocal enabledelayedexpansion

echo ==================================================
echo   Komorebi サムネイル一括生成バッチ
echo ==================================================

rem --- 設定 ---
rem 対象とするディレクトリ
set TARGET_DIR=OWN_DIRECTORY
rem 実行ファイルの場所（このバッチと同じフォルダ）
set THUMBNAILER=%~dp0KomorebiThumbnailer.exe
set FFMPEG_DIR=%~dp0

echo 探索ディレクトリ: %TARGET_DIR%
echo 実行ファイル: %THUMBNAILER%
echo FFmpegディレクトリ: %FFMPEG_DIR%
echo.

rem --- 処理開始 ---
rem 指定フォルダ 以下の .ts, .m2ts, .mp4 ファイルを再帰的に検索
for /r "%TARGET_DIR%" %%i in (*.ts *.m2ts *.mp4) do (
    
    rem すでにタイル画像が存在するかチェック（二重処理を防止）
    if exist "%%i.tile.webp" (
        echo [スキップ] すでに存在します: %%~nxi
    ) else (
        echo [処理中] %%~nxi
        "%THUMBNAILER%" "%%i" --ffmpeg-dir "%FFMPEG_DIR%"
        if !errorlevel! neq 0 (
            echo [エラー] %%~nxi の生成に失敗しました。
        )
    )
)

echo.
echo ==================================================
echo   すべての処理が完了しました。
echo ==================================================
pause