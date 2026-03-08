package com.beeregg2001.komorebi.data.api.interceptor

import android.os.Build
import androidx.annotation.RequiresApi
import com.beeregg2001.komorebi.data.model.*
import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * 録画番組 大量データ（16,000件超え）のパフォーマンス検証用モックインターセプター
 * 多種多様なシリーズ名を生成し、SQLiteのGROUP BYの限界をテストします。
 */
class MockRecordInterceptor : Interceptor {
    private val gson = Gson()

    // ★ 17,000件生成
    private val totalRecords = 17000
    private val pageSize = 30

    // ★ 大幅増量したシリーズマスターデータ
    private val masterSeriesTitles = listOf(
        // アニメ
        "葬送のフリーレン", "呪術廻戦", "SPY×FAMILY", "鬼滅の刃", "名探偵コナン",
        "ドラえもん", "クレヨンしんちゃん", "機動戦士ガンダム", "ぼっち・ざ・ろっく！",
        "進撃の巨人", "チェンソーマン", "リコリス・リコイル", "僕のヒーローアカデミア",
        "ONE PIECE", "サザエさん", "ちびまる子ちゃん", "忍たま乱太郎", "プリキュア",
        "ソードアート・オンライン", "ウマ娘 プリティダービー", "ラブライブ！",
        // ドラマ
        "相棒", "大河ドラマ", "連続テレビ小説", "科捜研の女", "半沢直樹",
        "逃げるは恥だが役に立つ", "アンナチュラル", "VIVANT", "下町ロケット",
        "ドクターX", "特捜9", "孤独のグルメ", "深夜食堂", "家政夫のミタゾノ",
        // バラエティ
        "水曜日のダウンタウン", "アメトーーク！", "月曜から夜ふかし", "マツコの知らない世界",
        "ブラタモリ", "世界の果てまでイッテQ！", "有吉の壁", "鉄腕DASH",
        "プレバト!!", "しゃべくり007", "ダウンタウンDX", "探偵!ナイトスクープ",
        "オモウマい店", "ゴッドタン", "相席食堂", "ポツンと一軒家",
        // ニュース・情報・ドキュメンタリー
        "ニュースウオッチ9", "報道ステーション", "WBS", "news zero",
        "めざましテレビ", "ZIP!", "News23", "Nスタ", "スーパーJチャンネル",
        "ガイアの夜明け", "カンブリア宮殿", "クローズアップ現代", "情熱大陸",
        // 映画枠
        "金曜ロードショー", "土曜プレミアム", "日曜洋画劇場", "午後のロードショー"
    )

    private val channelMaster = listOf(
        Triple("ch_1", "GR", "NHK総合"),
        Triple("ch_2", "GR", "Eテレ"),
        Triple("ch_3", "GR", "日テレ"),
        Triple("ch_4", "GR", "TBS"),
        Triple("ch_5", "GR", "フジテレビ"),
        Triple("ch_6", "GR", "テレ朝"),
        Triple("ch_7", "GR", "テレ東"),
        Triple("ch_8", "BS", "BSフジ"),
        Triple("ch_9", "BS", "WOWOW")
    )

    @RequiresApi(Build.VERSION_CODES.O)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val path = url.encodedPath

        return when {
            path == "/api/videos" || path == "/api/videos/search" -> {
                // 17000件で1秒待機するとテストに数十分かかるため、10msに短縮
                Thread.sleep(10L)

                val page = url.queryParameter("page")?.toIntOrNull() ?: 1
                val dummyResponse = generateDummyResponse(page)
                val jsonBody = gson.toJson(dummyResponse)

                Response.Builder()
                    .code(200)
                    .message("OK (Mocked Massive Videos)")
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .body(jsonBody.toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            }

            path.contains("/keep-alive") -> {
                Response.Builder()
                    .code(200)
                    .message("OK (Mocked Keep-Alive)")
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .body("".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            }

            path.endsWith(".m3u8") || path.contains("playlist.m3u8") -> {
                val dummyM3u8 = """
                    #EXTM3U
                    #EXT-X-VERSION:3
                    #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720
                    https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8
                """.trimIndent()

                Response.Builder()
                    .code(200)
                    .message("OK (Mocked Playlist)")
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .body(dummyM3u8.toResponseBody("application/vnd.apple.mpegurl".toMediaTypeOrNull()))
                    .build()
            }

            else -> chain.proceed(request)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun generateDummyResponse(page: Int): RecordedApiResponse {
        val programs = mutableListOf<RecordedProgram>()
        val startIndex = (page - 1) * pageSize
        val endIndex = minOf(startIndex + pageSize, totalRecords)

        val now = ZonedDateTime.now()
        val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        for (i in startIndex until endIndex) {
            val startTime = now.minusHours((i * 2).toLong())
            val endTime = startTime.plusHours(1)
            val selectedChannel = channelMaster[i % channelMaster.size]

            val seriesBase = masterSeriesTitles[i % masterSeriesTitles.size]
            val episodeNum = (i / masterSeriesTitles.size) + 1

            // ランダムに「第X話」や「〇〇SP」「[字]」などのノイズを入れる（ソートロジックの検証用）
            val titleSuffix = when {
                Random.nextFloat() > 0.9f -> " 拡大SP"
                Random.nextFloat() > 0.8f -> " [字] 傑作選"
                else -> " 第${episodeNum}話"
            }
            val programTitle = "$seriesBase$titleSuffix"

            val isEpisodicFlag = Random.nextFloat() > 0.5f

            val program = RecordedProgram(
                id = 10000 + i,
                title = programTitle,
                description = "【負荷テスト用データ】 $programTitle の詳細テキストです。大量の文字列が入ることを想定しています。あいうえおかきくけこ。",
                detail = mapOf("番組内容" to "ダミー詳細テキストです。"),
                startTime = startTime.format(formatter),
                endTime = endTime.format(formatter),
                duration = 3600.0,
                isPartiallyRecorded = false,
                isEpisodic = isEpisodicFlag,
                channel = RecordedChannel(
                    id = selectedChannel.first,
                    displayChannelId = "",
                    type = selectedChannel.second,
                    name = selectedChannel.third,
                    channelNumber = ""
                ),
                recordedVideo = RecordedVideo(
                    id = 10000 + i,
                    status = "Recorded",
                    filePath = "/api/streams/video/${10000 + i}/1080p/playlist.m3u8",
                    recordingStartTime = startTime.format(formatter),
                    recordingEndTime = endTime.format(formatter),
                    duration = 3600.0,
                    containerFormat = "mpegts",
                    videoCodec = "mpeg2video",
                    audioCodec = "aac",
                    hasKeyFrames = true,
                    thumbnailInfo = null
                ),
                genres = listOf(EpgGenre("anime", "anime")),
                isRecording = false,
                playbackPosition = 0.0
            )
            programs.add(program)
        }

        return RecordedApiResponse(
            total = totalRecords,
            recordedPrograms = programs
        )
    }
}