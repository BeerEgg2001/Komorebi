package com.beeregg2001.komorebi.data

/**
 * アプリ内で使用する Gemini モデル名の一元管理。
 *
 * ■ なぜ必要か
 * 以前はモデル名が AiConciergeViewModel と SettingsViewModel に別々に直書きされており、
 * 実際に次の2つの問題が起きていた。
 *
 *  - ブランチごとに本命モデルが分岐した。1.1.1 (develop) は `gemini-3.5-flash-lite`、
 *    1.2.0-beta は `gemini-3.6-flash` と別のモデルを指しており、マージ時に競合する状態だった。
 *  - AIコンシェルジュのモデルだけが更新され、APIキーの疎通確認は `gemini-3-flash-preview` の
 *    まま取り残されていた。preview 版は正式版より早く廃止されるため、
 *    「コンシェルジュは動くのにAPIキー検証だけ失敗する」という分かりにくい状態になりえた。
 *
 * Google は Gemini モデルを比較的短い周期で廃止するため、モデル名は「いつか必ず死ぬ定数」として
 * 扱う必要がある。ここに集約しておけば、廃止のたびに修正するのはこのファイルだけで済む。
 *
 * ■ 候補の選定条件
 * [CONCIERGE] は「先頭から順に試すフォールバック候補」であり、先頭が本命。
 * 候補に入れられるのは次の2つを満たすモデルのみ。
 *
 *  1. 音声入力 (audio/wav の inlineData) を受け付けること。
 *     コンシェルジュは端末の音声認識を使わず録音データを直接 Gemini へ投げる方式のため、
 *     音声入力に対応しないモデルを候補に入れると音声経路だけが壊れる。
 *  2. preview 版・非推奨 (deprecated) 版でないこと。
 *     特に `gemini-2.5-flash` 系は非推奨化済みで停止予告が出ているため候補から外している。
 */
object GeminiModels {

    /**
     * AIコンシェルジュ (対話・音声入力) 用のモデル候補。
     *
     * 本命の `gemini-3.5-flash-lite` は低コスト・高スループット志向の軽量モデル。
     * フォールバック先2つも含め、いずれも Text/Image/Video/Audio/PDF 入力に対応した stable 版。
     * `gemini-3.5-flash` は本命と同世代の上位モデルで、指示追従の精度は上がるがコストも上がるため、
     * あくまで本命が廃止されたときの受け皿として2番目に置いている。
     */
    val CONCIERGE: List<String> = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.5-flash",
        "gemini-3.1-flash-lite"
    )

    /**
     * APIキーの疎通確認 (countTokens) に使うモデル。
     *
     * 検証に使うモデルが実際の利用時と違うと、
     * 「キーは有効と表示されるのに機能が動かない」およびその逆が起きるため、
     * 必ずコンシェルジュの本命モデルと同じものを使う。
     */
    val VALIDATION: String
        get() = CONCIERGE.first()

    /**
     * 指定したモデルが「サーバー側に存在しない (廃止済み・未提供)」ことを示す例外かどうかを判定する。
     *
     * 旧 generativeai SDK (0.7.0) は 404 を専用の例外型にマッピングせず、
     * gRPC のエラーメッセージをそのまま載せた ServerException として投げてくる
     * (APIController.validateResponse を参照)。そのメッセージは
     *   "models/xxx is not found for API version v1beta, or is not supported for generateContent. ..."
     * という形式なので、メッセージ内容で判別するほかない。
     *
     * ここで true を返したときだけ次の候補へフォールバックする。
     * 認証エラーやレート制限 (InvalidAPIKeyException / QuotaExceededException) は
     * モデルを変えても解決しないどころか、全候補へ無駄なリクエストを投げて
     * レート制限をさらに悪化させるため、呼び出し側で明示的に除外している。
     */
    fun isModelUnavailable(e: Throwable): Boolean {
        val message = (e.message ?: "") + " " + (e.cause?.message ?: "")
        return message.contains("is not found for API version", ignoreCase = true) ||
                message.contains("is not supported for", ignoreCase = true) ||
                message.contains("NOT_FOUND", ignoreCase = true)
    }
}
