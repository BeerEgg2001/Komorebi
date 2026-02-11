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

    // ライブ視聴
    const val LIVE_PLAYER_ERROR_TITLE = "再生エラー"
    const val BUTTON_RETRY = "再読み込み"
    const val BUTTON_BACK = "戻る"

    // 状態監視・SSEイベント関連 (★今回追加)
    const val SSE_CONNECTING = "チューナーに接続しています..."
    const val SSE_OFFLINE = "放送が終了しました"

    // サブメニュー項目 (★今回追加)
    const val MENU_AUDIO = "音声切替"
    const val MENU_SOURCE = "映像ソース"
    const val MENU_SUBTITLE = "字幕設定"
    const val MENU_QUALITY = "画質設定"

    // エラー詳細メッセージ
    const val ERR_TUNER_FULL = "チューナーに空きがありません (503)\n他の録画や視聴が終了するのを待ってください。"
    const val ERR_CHANNEL_NOT_FOUND = "チャンネルが見つかりません (404)\n放送局が休止中か、設定が誤っている可能性があります。"
    const val ERR_CONNECTION_REFUSED = "サーバーに接続できません\nIPアドレスやポート番号を確認してください。"
    const val ERR_TIMEOUT = "接続がタイムアウトしました\nネットワーク環境を確認してください。"
    const val ERR_NETWORK = "ネットワークエラーが発生しました"
    const val ERR_UNKNOWN = "不明なエラーが発生しました"

    // 設定画面など
    const val SETTINGS_TITLE = "設定"
    const val SETTINGS_HOME_BACK = "ホームに戻る"
    const val SETTINGS_CONNECT = "接続設定"
    const val SETTINGS_DISPLAY = "表示設定"
    const val SETTINGS_INFO = "アプリ情報"
}