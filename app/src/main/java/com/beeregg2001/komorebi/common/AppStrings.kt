package com.beeregg2001.komorebi.common

object AppStrings {
    // 初期設定・起動時
    const val SETUP_REQUIRED_TITLE = "初期設定が必要です"
    const val SETUP_REQUIRED_MESSAGE = "サーバーへの接続情報が設定されていません。\n設定画面から接続先を入力してください。"
    const val GO_TO_SETTINGS = "設定画面へ移動"
    const val GO_TO_SETTINGS_SHORT = "設定画面へ"
    const val CONNECTION_ERROR_TITLE = "接続エラー"
    const val CONNECTION_ERROR_MESSAGE = "サーバーへの接続に失敗しました。\n設定を確認するか、サーバーの状態を確認してください。"
    const val EXIT_APP = "アプリ終了"
    const val EXIT_APP_FULL = "アプリを終了する"

    // OS非対応
    const val INCOMPATIBLE_OS_TITLE = "非対応のOSバージョン"
    const val INCOMPATIBLE_OS_MESSAGE = "本アプリの実行には Android 8.0 (API 26) 以上が必要です。\nお使いの端末 (API %d) は現在サポートされていません。"

    // 共通ボタン
    const val BUTTON_CANCEL = "キャンセル"
    const val BUTTON_DELETE = "削除する"
    const val BUTTON_RETRY = "再読み込み"
    const val BUTTON_BACK = "戻る"

    // ライブ視聴
    const val LIVE_PLAYER_ERROR_TITLE = "再生エラー"

    // 状態監視・SSEイベント関連
    const val SSE_CONNECTING = "チューナーに接続しています..."
    const val SSE_OFFLINE = "放送が終了しました"

    // サブメニュー項目
    const val MENU_AUDIO = "音声切替"
    const val MENU_SOURCE = "映像ソース"
    const val MENU_SUBTITLE = "字幕設定"
    const val MENU_QUALITY = "画質設定"
    const val MENU_COMMENT = "コメント表示"

    // エラー詳細メッセージ
    const val ERR_TUNER_FULL = "チューナーに空きがありません (503)\n他の録画や視聴が終了するのを待ってください。"
    const val ERR_CHANNEL_NOT_FOUND = "チャンネルが見つかりません (404)\n放送局の都合や番組改編により、現在放送されていない可能性があります。"
    const val ERR_CONNECTION_REFUSED = "接続が拒否されました"
    const val ERR_TIMEOUT = "通信がタイムアウトしました"
    const val ERR_NETWORK = "ネットワークエラーが発生しました"
    const val ERR_UNKNOWN = "不明なエラー"

    // 予約関連 (★今回追加)
    const val TOAST_RECORDING_STARTED = "録画を開始しました"
    const val TOAST_RESERVED = "予約しました"
    const val TOAST_RESERVE_UPDATED = "予約設定を更新しました"
    const val DIALOG_DELETE_RESERVE_TITLE = "予約の削除"
    const val DIALOG_DELETE_RESERVE_MESSAGE = "この予約を削除してもよろしいですか？\n%s"
    const val TOAST_RESERVE_DELETED = "予約を削除しました"

    // 履歴関連 (★今回追加)
    const val TOAST_CHANNEL_HISTORY_DELETED = "チャンネル履歴を削除しました"
    const val TOAST_WATCH_HISTORY_DELETED = "視聴履歴を削除しました"
}