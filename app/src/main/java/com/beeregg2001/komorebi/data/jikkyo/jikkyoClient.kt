package com.beeregg2001.komorebi.data.jikkyo

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * KonomiTV / NX-Jikkyo とのWebSocket通信を管理するクライアントクラス。
 * 視聴セッション(Watch)の確立、認証キーの取得、コメントセッション(Comment)への接続、
 * およびPing/Pongによる接続維持を一元管理します。
 */
class JikkyoClient(
    private val konomiIp: String,
    private val konomiPort: String,
    private val displayChannelId: String
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
        watchSocket?.close(1000, "Client stopped")
        commentSocket?.close(1000, "Client stopped")
        watchSocket = null
        commentSocket = null
        scope.cancel()
    }

    private fun connect() {
        job = scope.launch {
            try {
                // 1. APIから接続情報を取得
                val apiUrl = "$konomiIp:$konomiPort/api/channels/$displayChannelId/jikkyo"
                val requestApi = Request.Builder().url(apiUrl).build()

                val response = client.newCall(requestApi).execute()
                if (!response.isSuccessful) {
                    Log.e("JikkyoClient", "API Request failed: ${response.code}")
                    return@launch
                }

                val jsonStr = response.body?.string() ?: "{}"
                val jikkyoInfo = JSONObject(jsonStr)
                val watchUrl = jikkyoInfo.optString("watch_session_url")

                if (watchUrl.isNotEmpty() && watchUrl.startsWith("ws")) {
                    Log.d("JikkyoClient", "Start Watch Session: $watchUrl")
                    val request = Request.Builder().url(watchUrl).build()
                    watchSocket = client.newWebSocket(request, createWatchListener())
                } else {
                    Log.e("JikkyoClient", "Invalid watch URL: $watchUrl")
                }

            } catch (e: Exception) {
                Log.e("JikkyoClient", "Connection failed", e)
            }
        }
    }

    private fun createWatchListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("JikkyoClient", "Watch Socket Opened")
            // 2. 接続直後に startWatching を送信
            val startJson = """{"type":"startWatching","data":{"reconnect":false}}"""
            webSocket.send(startJson)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Log.d("JikkyoClient", "Watch Msg: $text")
            try {
                val json = JSONObject(text)
                val type = json.optString("type")

                // Ping/Pong (接続維持)
                if (type == "ping") {
                    webSocket.send("""{"type":"pong"}""")
                    webSocket.send("""{"type":"keepSeat"}""")
                    return
                }

                // 3. "room" イベントから threadId と yourPostKey を取得
                if (type == "room") {
                    val data = json.getJSONObject("data")
                    val threadId = data.getString("threadId")
                    val yourPostKey = data.getString("yourPostKey")
                    val messageServer = data.getJSONObject("messageServer")
                    val commentUri = messageServer.getString("uri")

                    Log.d("JikkyoClient", "Room Info: thread=$threadId, key=$yourPostKey, uri=$commentUri")

                    // 4. コメントサーバーへ接続開始
                    connectToCommentServer(commentUri, threadId, yourPostKey)
                }
            } catch (e: Exception) {
                Log.e("JikkyoClient", "Watch Message Parse Error", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e("JikkyoClient", "Watch Socket Error", t)
        }
    }

    private fun connectToCommentServer(uri: String, threadId: String, yourPostKey: String) {
        // 初期化コマンド配列の作成
        val initArray = JSONArray()
        initArray.put(JSONObject().put("ping", JSONObject().put("content", "rs:0")))
        initArray.put(JSONObject().put("ping", JSONObject().put("content", "ps:0")))

        // thread情報
        val threadObj = JSONObject()
        threadObj.put("version", "20061206")
        threadObj.put("thread", threadId)
        threadObj.put("threadkey", yourPostKey)
        threadObj.put("user_id", "")
        threadObj.put("res_from", -20) // 過去ログ100件取得
        initArray.put(JSONObject().put("thread", threadObj))

        initArray.put(JSONObject().put("ping", JSONObject().put("content", "pf:0")))
        initArray.put(JSONObject().put("ping", JSONObject().put("content", "rf:0")))

        val initMessage = initArray.toString()
        Log.d("JikkyoClient", "Connecting to Comment Server...")

        val request = Request.Builder()
            .url(uri)
            .addHeader("Sec-WebSocket-Protocol", "msg.nicovideo.jp#json")
            .build()

        commentSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, resp: Response) {
                Log.d("JikkyoClient", "Comment Socket Connected! Sending init...")
                ws.send(initMessage)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d("JikkyoClient", "Received: $text")
                // コメントデータを受信したらコールバックへ通知
                onCommentReceived?.invoke(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) {
                Log.e("JikkyoClient", "Comment Socket Error", t)
            }
        })
    }
}