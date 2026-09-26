package com.beeregg2001.komorebi.data

import java.util.concurrent.ConcurrentHashMap

/**
 * 局ロゴ URL のプロセス全体で共有されるメモリキャッシュ。
 *
 * ■ なぜ必要か
 * 局ロゴの URL 解決 (`LiveProvider.getChannelLogoUrl`) は suspend 関数で、バックエンドごとに
 * 以下のような「見た目より重い」処理を行っている。
 *   - KonomiTV : DataStore の Flow を 3 回 `first()` で読む (backendType / ip / port)
 *   - EDCB     : Dispatchers.IO へ切り替え + キャッシュファイルの存在確認 (初回はダウンロード)
 *   - EPGStation: Dispatchers.IO へ切り替え + ファイル存在確認 + チャンネル一覧の走査
 *
 * これがチャンネル一覧・番組表・ホームのカードなど「1 画面に数十個」並ぶ Composable から
 * 個別に呼ばれていたため、リストのスクロールやタブ移動のたびに大量のコルーチン往復が発生していた。
 * さらに Composable 側は解決完了まで空文字を表示するため、`"" -> URL` の 2 段階再コンポーズが
 * 起こり、ロゴが一瞬消えてから出る「チラつき」の原因にもなっていた。
 *
 * ■ 設計
 * - URL は「バックエンド種別が同じなら channelId から決定的に決まる」ため、
 *   バックエンド種別をシグネチャとして保持し、変わったら全消しする。
 * - Compose から同期的に初期値を取れるよう [peek] を提供する。ヒットすれば
 *   最初のフレームから正しい URL で描画でき、チラつきと再コンポーズが消える。
 * - 実体のキャッシュは Coil 側(メモリ/ディスク)が持つので、ここが持つのは URL 文字列だけ。
 */
object ChannelLogoUrlCache {

    private val cache = ConcurrentHashMap<String, String>()

    /** 現在キャッシュが有効なバックエンド種別。これが変わったらキャッシュ全体を破棄する。 */
    @Volatile
    private var signature: String = ""

    /**
     * Compose から同期的に呼ぶ用。解決済みなら URL、未解決なら null。
     * バックエンド種別を知らなくても呼べるようにしてあるが、[sync] によって
     * 古いバックエンドのエントリはすでに破棄されているため誤った URL は返らない。
     */
    fun peek(channelId: String): String? = cache[channelId]

    fun get(backendType: String, channelId: String): String? {
        sync(backendType)
        return cache[channelId]
    }

    fun put(backendType: String, channelId: String, url: String) {
        if (url.isEmpty()) return
        sync(backendType)
        cache[channelId] = url
    }

    /** 接続先設定が変わったときに呼ぶ。IP / ポート変更で URL が変わるため。 */
    fun clear() {
        cache.clear()
    }

    private fun sync(backendType: String) {
        if (signature == backendType) return
        synchronized(this) {
            if (signature != backendType) {
                cache.clear()
                signature = backendType
            }
        }
    }
}
