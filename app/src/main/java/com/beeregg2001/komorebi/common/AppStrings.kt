package com.beeregg2001.komorebi.common

object AppStrings {
    // 初期設定・起動時
    const val SETUP_REQUIRED_TITLE = "初期設定が必要です"
    const val SETUP_REQUIRED_MESSAGE =
        "サーバーへの接続情報が設定されていません。\n設定画面から接続先を入力してください。"
    const val GO_TO_SETTINGS = "設定画面へ移動"
    const val GO_TO_SETTINGS_SHORT = "設定画面へ"
    const val CONNECTION_ERROR_TITLE = "接続エラー"
    const val CONNECTION_ERROR_MESSAGE =
        "サーバーへの接続に失敗しました。\n設定を確認するか、サーバーの状態を確認してください。"
    const val EXIT_APP = "アプリ終了"
    const val EXIT_APP_FULL = "アプリを終了する"

    // OS非対応
    const val INCOMPATIBLE_OS_TITLE = "非対応のOSバージョン"
    const val INCOMPATIBLE_OS_MESSAGE =
        "本アプリの実行には Android 8.0 (API 26) 以上が必要です。\nお使いの端末 (API %d) は現在サポートされていません。"

    // 共通ボタン
    const val BUTTON_CANCEL = "キャンセル"
    const val BUTTON_DELETE = "削除する"
    const val BUTTON_RETRY = "再読み込み"
    const val BUTTON_BACK = "戻る"
    const val BUTTON_OK = "OK"
    const val BUTTON_CONFIRM = "決定"

    // 曜日
    const val DAY_SUN = "日"
    const val DAY_MON = "月"
    const val DAY_TUE = "火"
    const val DAY_WED = "水"
    const val DAY_THU = "木"
    const val DAY_FRI = "金"
    const val DAY_SAT = "土"

    // ライブ視聴
    const val LIVE_PLAYER_ERROR_TITLE = "再生エラー"
    const val LIVE_PLAYER_INIT_ERROR = "プレイヤーの初期化に失敗しました。再試行してください。"

    // 状態監視・SSEイベント関連
    const val SSE_CONNECTING = "チューナーに接続しています..."
    const val SSE_OFFLINE = "放送が終了しました"
    const val STATUS_LOADING = "読み込み中..."
    const val ERR_TUNER_START_FAILED = "チューナーの起動に失敗しました"

    // サブメニュー項目
    const val MENU_AUDIO = "音声切替"
    const val MENU_SOURCE = "映像ソース"
    const val MENU_SUBTITLE = "字幕設定"
    const val MENU_QUALITY = "画質設定"
    const val MENU_COMMENT = "コメント表示"

    // エラー詳細メッセージ
    const val ERR_TUNER_FULL =
        "チューナーに空きがありません (503)\n他の録画や視聴が終了するのを待ってください。"
    const val ERR_CHANNEL_NOT_FOUND =
        "チャンネルが見つかりません (404)\n放送局の都合や番組改編により、現在放送されていない可能性があります。"
    const val ERR_CONNECTION_REFUSED = "接続が拒否されました"
    const val ERR_TIMEOUT = "通信がタイムアウトしました"
    const val ERR_NETWORK = "ネットワークエラーが発生しました"
    const val ERR_UNKNOWN = "不明なエラー"
    const val ERR_SERVER_HTTP = "サーバーエラー (HTTP %d)"
    const val ERR_DATA_READ = "データ読み込みエラー: %s"

    // 予約関連
    const val TOAST_RECORDING_STARTED = "録画を開始しました"
    const val TOAST_RESERVED = "予約しました"
    const val TOAST_RESERVE_UPDATED = "予約設定を更新しました"
    const val DIALOG_DELETE_RESERVE_TITLE = "予約の削除"
    const val DIALOG_DELETE_RESERVE_MESSAGE = "この予約を削除してもよろしいですか？\n%s"
    const val TOAST_RESERVE_DELETED = "予約を削除しました"
    const val TOAST_RECORDING_STOPPED = "録画を停止しました"
    const val TOAST_RECORDING_STARTING = "録画を開始します"

    // EPG予約関連画面
    const val DIALOG_EPG_RESERVE_TITLE = "EPG予約 (キーワード自動予約)"
    const val LABEL_TRACKING_KEYWORD = "追跡キーワード"
    const val LABEL_TRACKING_CRITERIA = "追跡基準 (時間絞り込み)"
    const val FORMAT_EVERY_WEEK = "毎週(%s)"
    const val BUTTON_OPEN_ADVANCED_SETTINGS = "詳細設定を開く"
    const val BUTTON_RESERVE_WITH_CONDITION = "この条件で予約"
    const val DIALOG_SELECT_HOUR_TITLE = "時を選択"
    const val DIALOG_SELECT_MINUTE_TITLE = "分を選択"
    const val DIALOG_SELECT_DAY_TITLE = "曜日を選択"

    // 詳細設定画面
    const val DIALOG_ADVANCED_SETTINGS_TITLE = "EPG予約 詳細設定"
    const val LABEL_EXCLUDE_KEYWORD = "除外キーワード"
    const val LABEL_TITLE_ONLY_SEARCH = "番組名のみを検索対象にする"
    const val LABEL_TARGET_BROADCAST = "検索対象の放送波"
    const val LABEL_FUZZY_SEARCH = "あいまい検索を有効にする"
    const val LABEL_DUPLICATE_AVOIDANCE = "重複予約の回避"
    const val LABEL_RECORD_PRIORITY = "録画優先度 (1:最低 〜 5:最高)"
    const val LABEL_EVENT_RELAY = "イベントリレー追従"
    const val LABEL_EXACT_RECORD = "ぴったり録画"
    const val BROADCAST_ALL = "全て"
    const val BROADCAST_GR = "地デジ"
    const val BROADCAST_BS = "ＢＳ"
    const val BROADCAST_CS = "ＣＳ"
    const val DUPLICATE_NONE = "しない"
    const val DUPLICATE_SAME_CHANNEL = "同じチャンネルのみ"
    const val DUPLICATE_ALL_CHANNELS = "全てのチャンネル"
    const val VALUE_YES = "する"
    const val VALUE_NO = "しない"
    const val VALUE_NONE = "なし"
    const val BUTTON_APPLY_AND_BACK = "適用して戻る"

    // 条件編集ダイアログ
    const val DIALOG_CONDITION_EDIT_TITLE = "自動予約条件の編集・削除"
    const val LABEL_ENABLE_AUTO_RESERVE = "自動予約を有効にする"
    const val BUTTON_DELETE_CONDITION = "この条件を削除"
    const val BUTTON_SAVE_CHANGES = "変更を保存"
    const val LABEL_MATCHING_RESERVES = "条件に一致する予約一覧"
    const val DIALOG_DELETE_CONFIRM_TITLE = "削除の確認"
    const val DIALOG_DELETE_CONDITION_MSG = "この自動予約条件を削除しますか？"
    const val DIALOG_DELETE_CONDITION_DESC =
        "この自動予約条件を削除しますか？\n(関連する予約もオプションで一緒に削除できます)"
    const val CHECKBOX_DELETE_RELATED_RESERVES = "関連する予約（%d件）もすべて削除する"

    // 予約リスト画面
    const val TITLE_RESERVE_LIST = "録画予約"
    const val TAB_SINGLE_RESERVE = "単発予約"
    const val TAB_AUTO_RESERVE = "自動予約 (EPG)"
    const val MSG_NO_RESERVES = "予約されている番組はありません。"
    const val MSG_NO_CONDITIONS = "自動予約の条件が登録されていません。"

    // 履歴関連
    const val TOAST_CHANNEL_HISTORY_DELETED = "チャンネル履歴を削除しました"
    const val TOAST_WATCH_HISTORY_DELETED = "視聴履歴を削除しました"

    // ライブ視聴トースト関連
    const val TOAST_DUAL_SCREEN_SWAPPED = "左右の画面を入れ替えました"
    const val TOAST_AUDIO_CHANGED = "音声: %s"
    const val TOAST_SOURCE_SWITCHED = "ソース切替"
    const val TOAST_SUBTITLE_CHANGED = "字幕: %s"
    const val TOAST_COMMENT_CHANGED = "実況: %s"
    const val TOAST_QUALITY_CHANGED = "画質: %s"

    // 各種状態・ラベル
    const val STATE_SHOW = "表示"
    const val STATE_HIDE = "非表示"
    const val AUDIO_MAIN = "主音声"
    const val AUDIO_SUB = "副音声"
    const val CHANNEL_TYPE_GR = "地デジ"
    const val PROGRAM_INFO_NONE = "番組情報なし"
    const val STATUS_RECORDING = "録画中"

    // 二画面・モック関連
    const val DUAL_MOCK_LEFT = "左画面\n(720p上限 モック)"
    const val DUAL_MOCK_RIGHT_SELECTING = "チャンネル選択中...\n(720p上限 モック)"
    const val DUAL_MOCK_RIGHT_SELECTED = "右画面\n(720p上限 モック)"
    const val DUAL_MOCK_RIGHT_UNSELECTED = "右画面\n（未選択）\n(720p上限 モック)"
    const val DUAL_RIGHT_SELECTING = "チャンネル選択中..."
    const val DUAL_RIGHT_UNSELECTED = "（未選択）"

    // オーバーレイ・信号情報
    const val OVERLAY_SIGNAL_INFO = "信号情報"
    const val OVERLAY_VIDEO_RES = "映像解像度"
    const val OVERLAY_VIDEO_CODEC = "映像コーデック"
    const val OVERLAY_VIDEO_BITRATE = "映像ビットレート"
    const val OVERLAY_SYNC_FREQ = "垂直同期周波数"
    const val OVERLAY_DROP_FRAME = "ドロップフレーム"
    const val OVERLAY_AUDIO_CODEC = "音声コーデック"
    const val OVERLAY_AUDIO_CH = "音声チャンネル"
    const val OVERLAY_AUDIO_SAMPLE = "サンプリング周波数"
    const val OVERLAY_BUFFER = "バッファ残量"

    // ダイアログ関連
    const val DIALOG_MIRAKURUN_WARNING_TITLE = "ソース切り替えの確認"
    const val DIALOG_MIRAKURUN_WARNING_MSG =
        "メイン画面をMirakurunソースで再生しているときはKonomiTVソースに切り替えます。\nよろしいですか？"

    // --- 設定画面 ---
    const val SETTINGS_TITLE = "設定"
    const val SETTINGS_BACK_TO_HOME = "ホームに戻る"

    // カテゴリ名
    const val SETTINGS_CATEGORY_GENERAL = "基本設定"
    const val SETTINGS_CATEGORY_CONNECTION = "接続設定"
    const val SETTINGS_CATEGORY_PLAYBACK = "再生設定"
    const val SETTINGS_CATEGORY_HOME = "ホーム設定"
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
    const val SETTINGS_SECTION_STREAM_PRIORITY = "優先配信設定"
    const val SETTINGS_ITEM_ADDRESS = "アドレス"
    const val SETTINGS_ITEM_PORT = "ポート番号"
    const val SETTINGS_ITEM_PREFERRED_SOURCE = "優先するソース"
    const val SETTINGS_VALUE_SOURCE_KONOMITV = "KonomiTV"
    const val SETTINGS_VALUE_SOURCE_MIRAKURUN = "Mirakurun"
    const val SETTINGS_VALUE_SOURCE_KONOMITV_FIXED = "KonomiTV (固定)"
    const val SETTINGS_VALUE_SOURCE_MIRAKURUN_PREFERRED = "Mirakurun を優先"
    const val SETTINGS_VALUE_SOURCE_KONOMITV_PREFERRED = "KonomiTV を優先"
    const val SETTINGS_VALUE_UNSET = "未設定"
    const val SETTINGS_INPUT_KONOMITV_ADDRESS = "KonomiTV アドレス"
    const val SETTINGS_INPUT_KONOMITV_PORT = "KonomiTV ポート番号"
    const val SETTINGS_INPUT_MIRAKURUN_ADDRESS = "Mirakurun IPアドレス"
    const val SETTINGS_INPUT_MIRAKURUN_PORT = "Mirakurun ポート番号"

    // 再生設定
    const val SETTINGS_SECTION_QUALITY = "画質設定"
    const val SETTINGS_ITEM_LIVE_QUALITY = "ライブ視聴画質"
    const val SETTINGS_ITEM_VIDEO_QUALITY = "録画視聴画質"
    const val SETTINGS_SECTION_SUBTITLE_AUDIO = "字幕・音声設定"
    const val SETTINGS_ITEM_LIVE_SUBTITLE_DEFAULT = "ライブ視聴 デフォルト字幕"
    const val SETTINGS_ITEM_VIDEO_SUBTITLE_DEFAULT = "録画視聴 デフォルト字幕"
    const val SETTINGS_VALUE_SHOW = "表示"
    const val SETTINGS_VALUE_HIDE = "非表示"
    const val SETTINGS_SECTION_COMMENT_LAYER = "実況表示レイヤー"
    const val SETTINGS_ITEM_SUBTITLE_COMMENT_LAYER = "字幕とコメントの重なり"
    const val SETTINGS_VALUE_LAYER_COMMENT_TOP = "コメントを上に表示"
    const val SETTINGS_VALUE_LAYER_SUBTITLE_TOP = "字幕を上に表示"
    const val DIALOG_LAYER_ORDER_TITLE = "表示優先度"
    const val DIALOG_LAYER_COMMENT_TOP = "実況コメントを上に表示"
    const val DIALOG_LAYER_SUBTITLE_TOP = "字幕を上に表示"
    const val DIALOG_QUALITY_TITLE = "視聴画質"

    // 音声出力設定
    const val SETTINGS_SECTION_AUDIO_OUTPUT = "音声出力"
    const val SETTINGS_ITEM_AUDIO_OUTPUT_MODE = "音声出力モード"
    const val SETTINGS_VALUE_AUDIO_DOWNMIX = "ダウンミックス"
    const val SETTINGS_VALUE_AUDIO_PASSTHROUGH = "パススルー"
    const val DIALOG_AUDIO_OUTPUT_TITLE = "音声出力モードを選択"
    const val SETTINGS_VALUE_AUDIO_DOWNMIX_DESC = "ダウンミックス (2ch固定・互換性優先)"
    const val SETTINGS_VALUE_AUDIO_PASSTHROUGH_DESC = "パススルー (多チャンネル維持)"

    // 表示設定
    const val SETTINGS_SECTION_UI_CUSTOM = "インターフェース設定"
    const val SETTINGS_ITEM_BASE_THEME = "基本テーマ"
    const val SETTINGS_VALUE_THEME_DARK = "ダークモード"
    const val SETTINGS_VALUE_THEME_LIGHT = "ライトモード"
    const val SETTINGS_SECTION_THEME = "テーマ設定"
    const val SETTINGS_ITEM_THEME_COLOR = "テーマカラー・季節"
    const val SETTINGS_ITEM_APP_THEME = "アプリのテーマ"
    const val SETTINGS_VALUE_SEASON_SPRING = "春"
    const val SETTINGS_VALUE_SEASON_SUMMER = "夏"
    const val SETTINGS_VALUE_SEASON_AUTUMN = "秋"
    const val SETTINGS_VALUE_SEASON_WINTER = "冬"
    const val SETTINGS_VALUE_SEASON_DEFAULT = "デフォルト"

    // ★追加: 起動時のチャンネル設定
    const val SETTINGS_ITEM_STARTUP_TAB = "起動時のデフォルトタブ"
    const val SETTINGS_ITEM_STARTUP_CHANNEL = "起動時に再生するチャンネル"
    const val SETTINGS_VALUE_STARTUP_OFF = "設定しない (タブ設定を優先)"
    const val SETTINGS_VALUE_STARTUP_LAST = "前回最後に視聴したチャンネル"
    const val DIALOG_STARTUP_CHANNEL_TITLE = "起動時のチャンネルを選択"

    const val SETTINGS_VALUE_TAB_HOME = "ホーム"
    const val SETTINGS_VALUE_TAB_LIVE = "ライブ"
    const val SETTINGS_VALUE_TAB_VIDEO = "ビデオ"
    const val SETTINGS_VALUE_TAB_EPG = "番組表"
    const val SETTINGS_VALUE_TAB_RESERVE = "録画予約"

    const val SETTINGS_SECTION_HOME_PICKUP = "ホーム画面ピックアップ設定"
    const val SETTINGS_ITEM_PICKUP_GENRE = "対象ジャンル"
    const val DIALOG_PICKUP_GENRE_TITLE = "ジャンルを選択"
    const val SETTINGS_GENRE_ANIME = "アニメ"
    const val SETTINGS_GENRE_MOVIE = "映画"
    const val SETTINGS_GENRE_DRAMA = "ドラマ"
    const val SETTINGS_GENRE_SPORTS = "スポーツ"
    const val SETTINGS_GENRE_MUSIC = "音楽"
    const val SETTINGS_GENRE_VARIETY = "バラエティ"
    const val SETTINGS_GENRE_DOCUMENTARY = "ドキュメンタリー"

    const val SETTINGS_ITEM_PICKUP_TIME = "対象時間帯"
    const val DIALOG_PICKUP_TIME_TITLE = "時間帯を選択"
    const val SETTINGS_TIME_AUTO = "自動"
    const val SETTINGS_TIME_MORNING = "朝"
    const val SETTINGS_TIME_NOON = "昼"
    const val SETTINGS_TIME_NIGHT = "夜"

    const val SETTINGS_ITEM_EXCLUDE_PAID = "有料放送を除外する"
    const val SETTINGS_VALUE_EXCLUDE_ON = "ON"
    const val SETTINGS_VALUE_EXCLUDE_OFF = "OFF"

    // 録画リスト設定
    const val SETTINGS_SECTION_RECORD_LIST = "録画リスト"
    const val SETTINGS_ITEM_DEFAULT_RECORD_VIEW = "録画一覧の初期表示形式"
    const val SETTINGS_VALUE_VIEW_LIST = "リスト形式"
    const val SETTINGS_VALUE_VIEW_GRID = "グリッド形式"

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
    const val SETTINGS_INPUT_POST_COMMAND = "録画後コマンド"

    // アプリ情報
    const val SETTINGS_ITEM_OSS_LICENSES = "オープンソースライセンス"
}