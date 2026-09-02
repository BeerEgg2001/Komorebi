package com.beeregg2001.komorebi.util

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.beeregg2001.komorebi.NativeLib
import java.io.BufferedInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// ★ CServiceFilterの学習済み状態(PID構成/PTS-PCR差分)と、それを取得した時点でのファイル内バイト位置を
// セットで保持する。position はトラック切替時の「隠れリシーク」(=ほぼ同じ位置での再オープン)か、
// 本物のシーク(=離れた位置への再オープン)かを判定するために使う(TsReadExDataSource.open()参照)。
data class TsFilterStateSnapshot(val position: Long, val state: LongArray)

@UnstableApi
class TsReadExDataSource(
    private val nativeLib: NativeLib,
    var tsArgs: Array<String>,
    private val fileSizeBytesRef: AtomicLong? = null, // ★ ファイルサイズ格納用
    private val requestHeaders: Map<String, String> = emptyMap(), // ★ Cloudflare Access 等のリクエストヘッダー
    // ★ 追加: 録画TS直接再生でシークのたびにフィルタが作り直される際、直前のインスタンスが
    // 学習した音声PTS-PCR差分等を引き継ぐための退避先。呼び出し元がopen()をまたいで
    // 同一の参照を使い回すことで、CServiceFilter再生成のたびに音ズレが再発する問題を防ぐ
    // (詳細は servicefilter.hpp の CServiceFilter::State のコメント参照)。
    // ただし本物のシーク(離れた位置への再オープン)では学習済みのPTS-PCR差分が別区間のものになり
    // 逆に破綻の原因になるため、open()側でほぼ同じ位置への再オープンかどうかを判定してから使う。
    private val filterStateRef: AtomicReference<TsFilterStateSnapshot?>? = null,
    // ★ 追加: 直接TS再生のシーク機構。ExoPlayerのSeekMapには頼らず、シーク要求のたびに
    // 呼び出し元(VideoPlayerScreen.kt)がこの値をセットしてから MediaItem を作り直すことで、
    // dataSpec.position=0(=Media3から見て「新規再生」)であっても、ここに予約された
    // バイト位置から読み始められるようにする。-1は「予約なし」を意味し、消費後は-1に戻す。
    private val pendingSeekByteRef: AtomicLong? = null
) : BaseDataSource(true) {

    private var handle: Long = 0
    private var connection: HttpURLConnection? = null
    private var edcbSocket: Socket? = null

    private var inputStream: InputStream? = null
    private var uri: Uri? = null
    private var opened = false

    // ★ 診断用: open()時のバイト位置と、そこからの生バイト読み込み量(=現在のファイル内位置の推定)
    private var openPosition: Long = 0
    private var rawBytesRead: Long = 0

    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(188 * 20000)
    private val tempArray = ByteArray(188 * 20000)
    private val outputBuffer: ByteBuffer = ByteBuffer.allocateDirect(188 * 30000)

    private val nwtvId: Int
    private var lastCloseRequestTime = 0L

    companion object {
        private const val CMD_EPG_SRV_RELAY_VIEW_STREAM = 301
        private const val CMD_EPG_SRV_NWTV_ID_SET_CH = 1073
        private const val CMD_EPG_SRV_NWTV_ID_CLOSE = 1074
        private const val CMD_SUCCESS = 1
        private const val TAG = "TsReadExDataSource"

        private val edcbTunerLock = ReentrantLock()
        private var nwtvIdCounter = 500

        // ★ 音声トラック切替に伴う「隠れリシーク」(=ほぼ同じ位置での再オープン)と、
        // 本物のシーク(=離れた位置への再オープン)を区別するための許容誤差。
        // チャプタースキップ等の実シークは通常これより桁違いに大きく移動するため、
        // 数MB程度の余裕を見ておけば読み込みバッファのずれを吸収しつつ誤判定は避けられる。
        private const val HIDDEN_RESEEK_TOLERANCE_BYTES = 4L * 1024 * 1024
    }

    init {
        edcbTunerLock.withLock {
            nwtvId = nwtvIdCounter++
            if (nwtvIdCounter > 10000) nwtvIdCounter = 500
        }
    }

    override fun getUri(): Uri? = uri

    override fun open(dataSpec: DataSpec): Long {
        this.uri = dataSpec.uri
        transferInitializing(dataSpec)

        // ★ 追加: シーク機構の中核。ExoPlayerのSeekMapには頼らず、呼び出し元が事前に
        // pendingSeekByteRef へ「本当に読み始めたいバイト位置」をセットしておく方式にした。
        // dataSpec.position は(新規MediaItemとして開くため)常に0だが、予約値があれば
        // それを実際の開始位置として使う。
        val pendingSeekByte = pendingSeekByteRef?.getAndSet(-1L) ?: -1L
        val effectivePosition = if (dataSpec.position == 0L && pendingSeekByte >= 0L) {
            pendingSeekByte
        } else {
            dataSpec.position
        }
        openPosition = effectivePosition
        rawBytesRead = 0

        try {
            handle = nativeLib.openFilter(tsArgs)
        } catch (e: Exception) {
            throw IOException("Failed to open native filter", e)
        }
        // ★ 追加: 直前のフィルタが学習済みのPID構成/PTS-PCR差分があれば新しいフィルタへ引き継ぐ。
        // ただし、これはトラック切替時の「ほぼ同じ位置での再オープン」を想定した引き継ぎなので、
        // 離れた位置への本物のシークでは適用しない(別区間の学習値を誤って使うと、そこだけ
        // 音ズレが悪化したりデコードエラーの原因になり得るため)。
        val pending = filterStateRef?.get()
        val isNearSamePosition = pending != null &&
            kotlin.math.abs(pending.position - effectivePosition) <= HIDDEN_RESEEK_TOLERANCE_BYTES
        if (pending != null && isNearSamePosition) {
            nativeLib.importFilterState(handle, pending.state)
        }

        if (dataSpec.uri.scheme == "edcb") {
            edcbTunerLock.withLock { openEdcbStream(dataSpec.uri) }
        } else {
            openHttpStream(dataSpec, effectivePosition)
        }

        transferStarted(dataSpec)
        opened = true

        // ★ 核心: ExoPlayer の暴走する末尾シークを完全に封殺するため、常に LENGTH_UNSET を返す
        return C.LENGTH_UNSET.toLong()
    }

    private fun openHttpStream(dataSpec: DataSpec, effectivePosition: Long) {
        val url = java.net.URL(dataSpec.uri.toString())
        connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            doInput = true

            if (effectivePosition > 0) {
                setRequestProperty("Range", "bytes=$effectivePosition-")
            }
            // ★ 追加: Cloudflare Access ヘッダーを付与
            requestHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        val responseCode = connection?.responseCode ?: -1
        if (responseCode !in 200..299) {
            // ★ 404はファイル消失(録画削除など)として区別し、呼び出し側でリトライ対象から除外できるようにする
            if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                throw FileNotFoundException("Recording file not found (HTTP 404): ${dataSpec.uri}")
            }
            throw IOException("Server returned code $responseCode")
        }

        val contentLengthStr = connection?.getHeaderField("Content-Length")
        val contentLength = contentLengthStr?.toLongOrNull() ?: 0L

        // ★ 初回接続時（真のposition = 0、シーク予約なし）にファイル全体サイズを取得し、
        // シーク時のバイト位置計算用に保存
        if (contentLength > 0L && effectivePosition == 0L) {
            fileSizeBytesRef?.set(contentLength)
        }

        inputStream = BufferedInputStream(connection!!.inputStream, 188 * 50000)
    }

    private fun openEdcbStream(uri: Uri) {
        val ip = uri.host ?: throw IOException("Host not found")
        val port = if (uri.port != -1) uri.port else 4510
        val onid = uri.getQueryParameter("onid")?.toIntOrNull() ?: 0
        val tsid = uri.getQueryParameter("tsid")?.toIntOrNull() ?: 0
        val sid = uri.getQueryParameter("sid")?.toIntOrNull() ?: 0

        var targetProcessId = 0
        val startTime = System.currentTimeMillis()

        cleanupEdcbSessionSynchronous(ip, port)

        while (System.currentTimeMillis() - startTime < 10000) {
            try {
                Socket().use { socket ->
                    socket.soTimeout = 4000
                    socket.connect(InetSocketAddress(ip, port), 2000)

                    val body = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
                    body.putInt(26); body.putInt(1); body.putShort(onid.toShort()); body.putShort(tsid.toShort()); body.putShort(sid.toShort()); body.putInt(1); body.putInt(nwtvId); body.putInt(2)

                    sendEdcbCommand(socket.getOutputStream(), CMD_EPG_SRV_NWTV_ID_SET_CH, body.array())

                    val (ret, size) = readEdcbResponseHeader(socket.getInputStream())
                    if (ret == CMD_SUCCESS && size >= 4) {
                        val resData = readExactBytes(socket.getInputStream(), size)
                        if (resData != null) {
                            targetProcessId = ByteBuffer.wrap(resData).order(ByteOrder.LITTLE_ENDIAN).getInt()
                            if (targetProcessId != 0) break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SetCh fail: ${e.message}")
            }
            Thread.sleep(1000)
        }

        if (targetProcessId == 0) throw IOException("EDCB SetCh failed (Tuner could not start)")

        val relayStartTime = System.currentTimeMillis()
        var relaySocket: Socket? = null
        var relayConnected = false

        while (System.currentTimeMillis() - relayStartTime < 10000) {
            try {
                val s = Socket()
                s.soTimeout = 15000
                s.connect(InetSocketAddress(ip, port), 3000)

                val relayReq = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(targetProcessId).array()
                sendEdcbCommand(s.getOutputStream(), CMD_EPG_SRV_RELAY_VIEW_STREAM, relayReq)

                val (retRelay, _) = readEdcbResponseHeader(s.getInputStream())
                if (retRelay == CMD_SUCCESS) {
                    relaySocket = s; relayConnected = true; break
                } else {
                    s.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Relay attempt fail: ${e.message}")
            }
            Thread.sleep(1000)
        }

        if (!relayConnected || relaySocket == null) {
            cleanupEdcbSessionSynchronous(ip, port)
            throw IOException("EDCB Relay failed.")
        }

        this.edcbSocket = relaySocket
        this.inputStream = BufferedInputStream(edcbSocket!!.getInputStream(), 188 * 30000)
    }

    private fun cleanupEdcbSessionSynchronous(ip: String, port: Int) {
        val now = System.currentTimeMillis()
        if (now - lastCloseRequestTime < 1000) return

        try {
            Socket().use { s ->
                s.soTimeout = 2000
                s.connect(InetSocketAddress(ip, port), 1500)
                val closeReq = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(nwtvId).array()
                sendEdcbCommand(s.getOutputStream(), CMD_EPG_SRV_NWTV_ID_CLOSE, closeReq)
                readEdcbResponseHeader(s.getInputStream())
                lastCloseRequestTime = System.currentTimeMillis()
            }
        } catch (e: Exception) { }
    }

    private fun cleanupEdcbSessionAsynchronous(ip: String, port: Int) {
        Thread { edcbTunerLock.withLock { cleanupEdcbSessionSynchronous(ip, port) } }.start()
    }

    private fun sendEdcbCommand(outStream: OutputStream, cmd: Int, data: ByteArray) {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(cmd); header.putInt(data.size)
        outStream.write(header.array())
        if (data.isNotEmpty()) outStream.write(data)
        outStream.flush()
    }

    private fun readEdcbResponseHeader(ins: InputStream): Pair<Int, Int> {
        val header = readExactBytes(ins, 8) ?: throw IOException("EDCB Header missing")
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        return Pair(buf.getInt(), buf.getInt())
    }

    private fun readExactBytes(ins: InputStream, length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val read = ins.read(buffer, totalRead, length - totalRead)
            if (read == -1) return null
            totalRead += read
        }
        return buffer
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val input = inputStream ?: return C.RESULT_END_OF_INPUT

        var total = 0
        while (total < length) {
            val processed = nativeLib.popDataBuffer(handle, outputBuffer, length - total)
            if (processed > 0) {
                outputBuffer.position(0)
                outputBuffer.get(buffer, offset + total, processed)
                total += processed
            } else {
                val readCount = input.read(tempArray)
                if (readCount == -1) {
                    return if (total > 0) total else C.RESULT_END_OF_INPUT
                }
                if (readCount > 0) {
                    rawBytesRead += readCount
                    inputBuffer.clear()
                    inputBuffer.put(tempArray, 0, readCount)
                    nativeLib.pushDataBuffer(handle, inputBuffer, readCount)
                }
            }
        }
        if (total > 0) bytesTransferred(total)
        return total
    }

    override fun close() {
        if (opened) {
            transferEnded(); opened = false
        }
        try {
            uri?.let {
                if (it.scheme == "edcb") {
                    val ip = it.host
                    val port = if (it.port != -1) it.port else 4510
                    if (ip != null) cleanupEdcbSessionAsynchronous(ip, port)
                }
            }
            inputStream?.close()
            connection?.disconnect()
            edcbSocket?.close()
        } finally {
            inputStream = null; connection = null; edcbSocket = null
            if (handle != 0L) {
                // ★ 追加: 破棄する前に学習済み状態と、その時点でのファイル内バイト位置を退避し、
                // 次にこのDataSourceがほぼ同じ位置で開かれた際(隠れリシーク)に引き継げるようにする
                val exported = nativeLib.exportFilterState(handle)
                val finalPosition = openPosition + rawBytesRead
                filterStateRef?.set(TsFilterStateSnapshot(finalPosition, exported))
                nativeLib.closeFilter(handle); handle = 0L
            }
        }
    }
}