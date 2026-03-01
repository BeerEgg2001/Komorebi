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

/**
 * 録画番組1,000件表示のパフォーマンス検証用モックインターセプター
 * KonomiTVの仕様に合わせ、1ページあたり必ず30件を返す
 */
class MockRecordInterceptor : Interceptor {
    private val gson = Gson()
    private val totalRecords = 5000 // ★ ここを 3000 などに増やしても面白いです
    private val pageSize = 30

    @RequiresApi(Build.VERSION_CODES.O)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val path = url.encodedPath

        return when {
            path == "/api/videos" || path == "/api/videos/search" -> {
                // ========================================================
                // ★追加: ネットワークの通信遅延をシミュレート (1ページにつき1秒待機)
                // ========================================================
                Thread.sleep(1000L)

                val page = url.queryParameter("page")?.toIntOrNull() ?: 1
                val dummyResponse = generateDummyResponse(page)
                val jsonBody = gson.toJson(dummyResponse)

                Response.Builder()
                    .code(200)
                    .message("OK (Mocked Videos)")
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

        val channelMaster = listOf(
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

        for (i in startIndex until endIndex) {
            val startTime = now.minusHours((i * 2).toLong())
            val endTime = startTime.plusHours(1)
            val selectedChannel = channelMaster.random()

            val program = RecordedProgram(
                id = 10000 + i,
                title = "【ダミーデータ検証用】番組タイトル_第${i}回",
                description = "これはパフォーマンス検証用のダミーテキストです。",
                detail = mapOf("番組内容" to "ダミー詳細テキストです。"),
                startTime = startTime.format(formatter),
                endTime = endTime.format(formatter),
                duration = 3600.0,
                isPartiallyRecorded = false,
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