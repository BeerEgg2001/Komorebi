package com.beeregg2001.komorebi.data

/**
 * KonomiTVサーバーが Original画質 (生MPEG-2直接再生・ライブのみ) に対応しているかどうかの
 * プロセス全体で共有される判定結果キャッシュ。
 *
 * ■ なぜ必要か
 * KonomiTVの Original画質(ライブ)対応は master ブランチでのみ追加された機能で、
 * 正式リリース版 (例: v0.14.1) ではサーバーが
 * `422 Unprocessable Entity ("Specified quality was not found")` で拒否する。
 * しかもサーバーの VERSION 定数は次バージョンのリリース時にしか上がらないため、
 * master と直近の正式リリースが同じバージョン文字列を名乗る期間があり
 * (2026-09時点でどちらも "0.14.1")、バージョン比較では対応可否を判別できない。
 *
 * そのため「実際に再生を試みて 422 で拒否されたら以後隠す」という実行時検出方式を取る。
 * 一度拒否を確認したら、接続先設定を変えるまでは画質選択肢から Original を除外し、
 * 無駄な再試行と分かりにくいエラー表示を防ぐ。
 *
 * (録画のOriginal画質は `/api/videos/{id}/download` という汎用ダウンロードAPIを流用しており
 * バージョンや対応状況に左右されないため、このゲートの対象はライブ視聴のみ)
 */
object KonomiOriginalQualityGate {

    @Volatile
    private var unsupported = false

    fun isUnsupported(): Boolean = unsupported

    /** ライブのOriginal画質再生でサーバーに拒否された(422)ときに呼ぶ。 */
    fun markUnsupported() {
        unsupported = true
    }

    /** 接続先設定が変わったときに呼ぶ。サーバーが変われば対応状況も変わりうるため。 */
    fun reset() {
        unsupported = false
    }
}
