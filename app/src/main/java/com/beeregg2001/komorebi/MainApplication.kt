package com.beeregg2001.komorebi

import android.app.Application
// import android.content.res.Configuration <- これを削除しました
import android.os.SystemClock
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.beeregg2001.komorebi.data.api.interceptor.CloudflareAccessInterceptor
import com.beeregg2001.komorebi.data.worker.RecordSyncWorker
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

// ★ 追加(診断用): コールドブート直後の緩慢さの原因を実測するため、プロセス開始からの
// 経過時間を主要な通過点でログ出力する。原因特定後は削除する想定の一時的な計測コード。
// `adb logcat -s ColdStartDiag:I` で経過時間(ms)を直接確認できる。
object ColdStartDiag {
    private const val TAG = "ColdStartDiag"
    private val processStartElapsedMs = SystemClock.elapsedRealtime()

    fun mark(label: String) {
        val elapsed = SystemClock.elapsedRealtime() - processStartElapsedMs
        Log.i(TAG, "T+${elapsed}ms: $label")
    }
}

@HiltAndroidApp
class MainApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var cloudflareAccessInterceptor: CloudflareAccessInterceptor

    // ★ 追加: Coil (AsyncImage) の画像取得にも Cloudflare Access ヘッダーを付与
    // ★ 最適化: Android TV はメモリ・CPU ともに非力なため、Coil のデフォルト任せをやめて
    //           メモリ／ディスクキャッシュを明示設定する。
    //           - 局ロゴやサムネイルは「小さくて再利用頻度が極端に高い」画像なので、
    //             メモリキャッシュを大きめ(25%)に固定する。Coil のデフォルトは
    //             isLowRamDevice 判定で 15% まで落ちることがあり、TV 端末では
    //             ロゴが即座に押し出されて毎回デコードし直す原因になっていた。
    //           - respectCacheHeaders(false): KonomiTV / EPGStation / Mirakurun の
    //             ロゴエンドポイントは no-cache 系ヘッダーを返すことがあり、
    //             そのままだとディスクキャッシュがあっても毎回ネットワークへ出てしまう。
    //             局ロゴは頻繁に変わらないためヘッダーを無視してキャッシュを優先する。
    //           - crossfade(false): TV でのフェード合成は描画コストが高いため全体で無効化。
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    .addInterceptor(cloudflareAccessInterceptor)
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .respectCacheHeaders(false)
            .crossfade(false)
            .build()
    }

    // ★ 最新の WorkManager に合わせてプロパティとしてオーバーライドします
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        ColdStartDiag.mark("MainApplication.onCreate")

        // バックグラウンド同期スケジュールを登録する。
        //
        // WorkManager.getInstance() は初回呼び出し時に WorkManager 本体
        // (Room DB の構成・ForceStopRunnable の起動など) を初期化するため、
        // Application.onCreate から同期的に呼ぶと最初のフレームが描画されるまでの
        // メインスレッドをそのぶん占有してしまう。
        // 定期同期は 15 分間隔であり起動直後に即時実行する必要が一切ないので、
        // 専用スレッドへ逃がして起動のクリティカルパスから外す。
        Thread({ RecordSyncWorker.schedule(this) }, "komorebi-worker-schedule").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }
}