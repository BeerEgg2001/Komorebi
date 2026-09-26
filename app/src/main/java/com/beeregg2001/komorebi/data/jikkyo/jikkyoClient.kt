package com.beeregg2001.komorebi.data.jikkyo

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * NX-Jikkyo / ニコニコ実況 とのWebSocket通信を管理するクライアントクラス。
 * ViewModelから渡された watchSessionUrl に接続し、コメントセッションへの接続とPing/Pongを一元管理します。
 */
class JikkyoClient(
    private val watchSessionUrl: String
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket用にタイムアウト無効化
        .build()

    private var watchSocket: WebSocket? = null
    private var commentSocket: WebSocket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // コメント受信時のコールバック
    private var onCommentReceived: ((String) -> Unit)? = null

    fun start(onComment: (String) -> Unit) {
        this.onCommentReceived = onComment
        connect()
    }

    fun stop() {
        job?.cancel()
        scope.cancel()

        // ゾンビメッセージがUIに送られるのを防ぐため即座にnull化
        onCommentReceived = null

        // close (1000) ではなく cancel() で即座に強制切断（終了時のモタつき防止）
        // ※これによって内部的に SocketException が発生しますが、onFailure で握りつぶします
        watchSocket?.cancel()
        commentSocket?.cancel()
        watchSocket = null
        commentSocket = null

        // OkHttpのバックグラウンドスレッドを確実にシャットダウンしてメモリリークを防ぐ
        client.dispatcher.executorService.shutdown()
    }

    private fun connect() {
        job = scope.launch {
            try {
                Log.i("JikkyoClient", "Start Watch Session: $watchSessionUrl")
                val request = Request.Builder().url(watchSessionUrl).build()
                watchSocket = client.newWebSocket(request, createWatchListener())
            } catch (e: Exception) {
                Log.e("JikkyoClient", "Connection failed", e)
            }
        }
    }

    private fun createWatchListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i("JikkyoClient", "Watch Socket Opened")
            // 接続直後に startWatching を送信
            val startJson = """{"type":"startWatching","data":{"reconnect":false}}"""
            webSocket.send(startJson)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                val type = json.optString("type")

                // Ping/Pong (接続維持)
                if (type == "ping") {
                    webSocket.send("""{"type":"pong"}""")
                    webSocket.send("""{"type":"keepSeat"}""")
                    return
                }

                // "room" イベントから threadId と yourPostKey を取得
                if (type == "room") {
                    val data = json.getJSONObject("data")
                    val threadId = data.getString("threadId")
                    val yourPostKey = data.getString("yourPostKey")
                    val messageServer = data.getJSONObject("messageServer")
                    val commentUri = messageServer.getString("uri")

                    Log.i(
                        "JikkyoClient",
                        "Room Info: thread=$threadId, key=$yourPostKey, uri=$commentUri"
                    )

                    // コメントサーバーへ接続開始
                    connectToCommentServer(commentUri, threadId, yourPostKey)
                }
            } catch (e: Exception) {
                Log.e("JikkyoClient", "Watch Message Parse Error", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // ★ 修正: cancel() による意図的な強制切断（SocketException等）はエラーとして出力しない
            if (t is java.net.SocketException || t is java.io.EOFException || t.message == "Canceled") {
                Log.i("JikkyoClient", "Watch Socket closed safely.")
                return
            }
            Log.e("JikkyoClient", "Watch Socket Error", t)
        }
    }

    private fun connectToCommentServer(uri: String, threadId: String, yourPostKey: String) {
        val initArray = JSONArray()
        initArray.put(JSONObject().put("ping", JSONObject().put("content", "rs:0")))
        initArray.put(JSONObject().put("ping", JSONObject().put("content", "ps:0")))

        val threadObj = JSONObject()
        threadObj.put("version", "20061206")
        threadObj.put("thread", threadId)
        threadObj.put("threadkey", yourPostKey)
        threadObj.put("user_id", "")
        threadObj.put("res_from", -20) // 過去ログ取得件数
        initArray.put(JSONObject().put("thread", threadObj))

        initArray.put(JSONObject().put("ping", JSONObject().put("content", "pf:0")))
        initArray.put(JSONObject().put("ping", JSONObject().put("content", "rf:0")))

        val initMessage = initArray.toString()
        Log.i("JikkyoClient", "Connecting to Comment Server...")

        val request = Request.Builder()
            .url(uri)
            .addHeader("Sec-WebSocket-Protocol", "msg.nicovideo.jp#json")
            .build()

        commentSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, resp: Response) {
                Log.i("JikkyoClient", "Comment Socket Connected! Sending init...")
                ws.send(initMessage)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                // 受信した生のJSONデータをViewModelへ流す
                onCommentReceived?.invoke(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) {
                // ★ 修正: cancel() による意図的な強制切断（SocketException等）はエラーとして出力しない
                if (t is java.net.SocketException || t is java.io.EOFException || t.message == "Canceled") {
                    Log.i("JikkyoClient", "Comment Socket closed safely.")
                    return
                }
                Log.e("JikkyoClient", "Comment Socket Error", t)
            }
        })
    }
}