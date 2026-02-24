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

    // 予約関連
    const val TOAST_RECORDING_STARTED = "録画を開始しました"
    const val TOAST_RESERVED = "予約しました"
    const val TOAST_RESERVE_UPDATED = "予約設定を更新しました"
    const val DIALOG_DELETE_RESERVE_TITLE = "予約の削除"
    const val DIALOG_DELETE_RESERVE_MESSAGE = "この予約を削除してもよろしいですか？\n%s"
    const val TOAST_RESERVE_DELETED = "予約を削除しました"

    // 履歴関連
    const val TOAST_CHANNEL_HISTORY_DELETED = "チャンネル履歴を削除しました"
    const val TOAST_WATCH_HISTORY_DELETED = "視聴履歴を削除しました"

    // --- 設定画面 ---
    const val SETTINGS_TITLE = "設定"
    const val SETTINGS_BACK_TO_HOME = "ホームに戻る"

    // カテゴリ名
    const val SETTINGS_CATEGORY_GENERAL = "基本設定"
    const val SETTINGS_CATEGORY_CONNECTION = "接続設定"
    const val SETTINGS_CATEGORY_PLAYBACK = "再生設定"
    const val SETTINGS_CATEGORY_DISPLAY = "表示設定"
    const val SETTINGS_CATEGORY_COMMENT = "コメント設定"
    const val SETTINGS_CATEGORY_LAB = "アドオン・ラボ"
    const val SETTINGS_CATEGORY_APP_INFO = "アプリ情報"

    // 基本設定
    const val SETTINGS_SECTION_DATA_MANAGEMENT = "データ管理"
    const val SETTINGS_ITEM_CLEAR_CHANNEL_HISTORY = "前回視聴したチャンネル履歴を削除"
    const val SETTINGS_ITEM_CLEAR_WATCH_HISTORY = "録画の視聴履歴を削除"
    const val SETTINGS_VALUE_DELETE = "削除"
    const val DIALOG_CLEAR_HISTORY_TITLE = "履歴の削除"
    const val DIALOG_CLEAR_CHANNEL_HISTORY_MSG = "前回視聴したチャンネルの履歴を削除しますか？"
    const val DIALOG_CLEAR_WATCH_HISTORY_MSG = "録画の視聴履歴を削除しますか？"

    // 接続設定
    const val SETTINGS_SECTION_KONOMITV = "KonomiTV"
    const val SETTINGS_SECTION_MIRAKURUN = "Mirakurun (オプション)"
    const val SETTINGS_SECTION_STREAM_SOURCE = "配信ソース設定"
    const val SETTINGS_ITEM_ADDRESS = "アドレス"
    const val SETTINGS_ITEM_PORT = "ポート番号"
    const val SETTINGS_ITEM_PREFERRED_SOURCE = "優先するソース"
    const val SETTINGS_VALUE_SOURCE_KONOMITV = "KonomiTV"
    const val SETTINGS_VALUE_SOURCE_MIRAKURUN = "Mirakurun"
    const val SETTINGS_VALUE_SOURCE_KONOMITV_FIXED = "KonomiTV (固定)"
    const val SETTINGS_VALUE_SOURCE_MIRAKURUN_PREFERRED = "Mirakurun を優先"
    const val SETTINGS_VALUE_SOURCE_KONOMITV_PREFERRED = "KonomiTV を優先"
    const val SETTINGS_INPUT_KONOMITV_ADDRESS = "KonomiTV アドレス"
    const val SETTINGS_INPUT_KONOMITV_PORT = "KonomiTV ポート番号"
    const val SETTINGS_INPUT_MIRAKURUN_ADDRESS = "Mirakurun IPアドレス"
    const val SETTINGS_INPUT_MIRAKURUN_PORT = "Mirakurun ポート番号"

    // 再生設定
    const val SETTINGS_SECTION_QUALITY = "画質設定"
    const val SETTINGS_ITEM_LIVE_QUALITY = "ライブ視聴画質"
    const val SETTINGS_ITEM_VIDEO_QUALITY = "録画視聴画質"
    const val SETTINGS_SECTION_SUBTITLE = "字幕設定"
    const val SETTINGS_ITEM_LIVE_SUBTITLE = "ライブ視聴 デフォルト表示"
    const val SETTINGS_ITEM_VIDEO_SUBTITLE = "録画視聴 デフォルト表示"
    const val SETTINGS_VALUE_SHOW = "表示"
    const val SETTINGS_VALUE_HIDE = "非表示"
    const val SETTINGS_SECTION_LAYER = "表示レイヤー設定"
    const val SETTINGS_ITEM_LAYER_ORDER = "字幕とコメントの重なり"
    const val SETTINGS_VALUE_LAYER_COMMENT_TOP = "コメントを上に表示"
    const val SETTINGS_VALUE_LAYER_SUBTITLE_TOP = "字幕を上に表示"
    const val DIALOG_LAYER_ORDER_TITLE = "表示優先度"
    const val DIALOG_LAYER_COMMENT_TOP = "実況コメントを上に表示"
    const val DIALOG_LAYER_SUBTITLE_TOP = "字幕を上に表示"
    const val DIALOG_QUALITY_TITLE = "視聴画質"

    // ★追加: 音声出力設定関連
    const val SETTINGS_SECTION_AUDIO_OUTPUT = "音声出力"
    const val SETTINGS_ITEM_AUDIO_OUTPUT_MODE = "音声出力モード"
    const val SETTINGS_VALUE_AUDIO_DOWNMIX_REC = "ダウンミックス (推奨)"
    const val SETTINGS_VALUE_AUDIO_PASSTHROUGH = "パススルー"
    const val DIALOG_AUDIO_OUTPUT_TITLE = "音声出力モード"
    const val SETTINGS_VALUE_AUDIO_DOWNMIX_DESC = "ダウンミックス (2ch固定)"
    const val SETTINGS_VALUE_AUDIO_PASSTHROUGH_DESC = "パススルー (5.1ch維持)"

    // 表示設定
    const val SETTINGS_SECTION_GENERAL = "全般"
    const val SETTINGS_ITEM_BASE_THEME = "基本テーマ"
    const val SETTINGS_VALUE_THEME_DARK = "ダークモード"
    const val SETTINGS_VALUE_THEME_LIGHT = "ライトモード"
    const val SETTINGS_ITEM_THEME_COLOR = "テーマカラー・季節"
    const val SETTINGS_VALUE_SEASON_SPRING = "春"
    const val SETTINGS_VALUE_SEASON_SUMMER = "夏"
    const val SETTINGS_VALUE_SEASON_AUTUMN = "秋"
    const val SETTINGS_VALUE_SEASON_WINTER = "冬"
    const val SETTINGS_VALUE_SEASON_DEFAULT = "デフォルト"
    const val SETTINGS_ITEM_STARTUP_TAB = "起動時のデフォルトタブ"
    const val SETTINGS_VALUE_TAB_HOME = "ホーム"
    const val SETTINGS_VALUE_TAB_LIVE = "ライブ"
    const val SETTINGS_VALUE_TAB_VIDEO = "ビデオ"
    const val SETTINGS_VALUE_TAB_EPG = "番組表"
    const val SETTINGS_VALUE_TAB_RESERVE = "録画予約"

    const val SETTINGS_SECTION_HOME_PICKUP = "ホーム画面設定 (おすすめピックアップ)"
    const val SETTINGS_ITEM_PICKUP_GENRE = "ピックアップジャンル"
    const val DIALOG_PICKUP_GENRE_TITLE = "ジャンルを選択"
    const val SETTINGS_GENRE_ANIME = "アニメ"
    const val SETTINGS_GENRE_MOVIE = "映画"
    const val SETTINGS_GENRE_DRAMA = "ドラマ"
    const val SETTINGS_GENRE_SPORTS = "スポーツ"
    const val SETTINGS_GENRE_MUSIC = "音楽"
    const val SETTINGS_GENRE_VARIETY = "バラエティ"
    const val SETTINGS_GENRE_DOCUMENTARY = "ドキュメンタリー"

    const val SETTINGS_ITEM_PICKUP_TIME = "ピックアップ時間帯"
    const val DIALOG_PICKUP_TIME_TITLE = "時間帯を選択"
    const val SETTINGS_TIME_AUTO = "自動"
    const val SETTINGS_TIME_MORNING = "朝"
    const val SETTINGS_TIME_NOON = "昼"
    const val SETTINGS_TIME_NIGHT = "夜"

    const val SETTINGS_ITEM_EXCLUDE_PAID = "ピックアップから有料放送を除外する"
    const val SETTINGS_VALUE_EXCLUDE_ON = "ON (除外する)"
    const val SETTINGS_VALUE_EXCLUDE_OFF = "OFF (除外しない)"

    // コメント設定
    const val SETTINGS_SECTION_COMMENT_DISPLAY = "実況表示"
    const val SETTINGS_ITEM_DEFAULT_DISPLAY = "デフォルト表示"
    const val SETTINGS_ITEM_COMMENT_SPEED = "コメントの速さ"
    const val SETTINGS_ITEM_COMMENT_SIZE = "サイズ倍率"
    const val SETTINGS_ITEM_COMMENT_OPACITY = "不透明度"
    const val SETTINGS_ITEM_COMMENT_MAX_LINES = "最大同時表示行数"
    const val SETTINGS_INPUT_COMMENT_SPEED = "実況コメントの速さ"
    const val SETTINGS_INPUT_COMMENT_SIZE = "実況フォントサイズ倍率"
    const val SETTINGS_INPUT_COMMENT_OPACITY = "実況コメント不透明度"
    const val SETTINGS_INPUT_COMMENT_MAX_LINES = "実況最大同時表示行数"

    // アドオン・ラボ
    const val SETTINGS_SECTION_EXTERNAL_INTEGRATION = "外部サービス連携 (実験的)"
    const val SETTINGS_ITEM_ANNICT = "Annict と同期する"
    const val SETTINGS_ITEM_SHOBOCAL = "しょぼいカレンダー連携"
    const val SETTINGS_VALUE_ENABLE = "有効"
    const val SETTINGS_VALUE_DISABLE = "無効"
    const val SETTINGS_SECTION_RECORD_DETAIL = "録画詳細設定"
    const val SETTINGS_ITEM_POST_COMMAND = "デフォルト録画後実行コマンド"
    const val SETTINGS_VALUE_UNSET = "未設定"
    const val SETTINGS_INPUT_POST_COMMAND = "録画後コマンド"

    // アプリ情報
    const val SETTINGS_ITEM_OSS_LICENSES = "オープンソースライセンス"
}