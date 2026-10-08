package com.beeregg2001.komorebi.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

/**
 * AIコンシェルジュ用の音声録音エンジン。
 *
 * 録音した PCM を WAV 化して返し、呼び出し側がその WAV をそのまま Gemini へ投げる。
 *
 * ■ [stopRecording] が suspend である理由
 * WAV の書き出しは録音ループと同じバックグラウンドコルーチン内で、ループを抜けた後に行われる。
 * 以前の実装は [stopRecording] が書き出しの完了を待たずに File を返していたため、
 * 呼び出し側が「まだ今回の音声が書かれていないファイル」を読み込んでいた。
 * 出力先が固定パスだったことも重なり、結果として **前回の録音データがそのまま Gemini に送られ**、
 * 「別のことを話したのに前回の内容に応答する」という誤動作になっていた。
 *
 * そのため [stopRecording] は書き出しコルーチンの join を待つ suspend 関数にしている。
 * 加えて、万一書き出しに失敗したときに古い音声を拾わないよう、
 * 出力ファイル名は録音セッションごとにユニークにしている。
 */
class AudioRecorderHelper(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var outputFile: File? = null

    /**
     * 録音ループの継続フラグ。
     * メインスレッドから書き、IOスレッドの録音ループから読むため @Volatile が必須。
     */
    @Volatile
    private var isRecording = false

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    /**
     * read() 1回あたりの読み出しバッファ長。
     * getMinBufferSize() は端末によって ERROR(-1) / ERROR_BAD_VALUE(-2) を返すことがあるため、
     * その場合は 200ms 相当 (16000 サンプル/秒 × 2バイト × 0.2秒 = 6400バイト) にフォールバックする。
     */
    private val readBufferSize: Int =
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            .takeIf { it > 0 } ?: (sampleRate * 2 / 5)

    /**
     * AudioRecord 内部のリングバッファ長。
     * 最小サイズのまま使うと、GC やファイル書き込みの待ちでバッファが溢れてサンプルを取りこぼし、
     * 音声が途切れて認識精度が落ちる。余裕を持たせるため最小サイズの4倍を確保する。
     */
    private val recordBufferSize: Int = readBufferSize * 4

    fun startRecording() {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "マイク権限がありません！")
            return
        }

        // 前のセッションが残っていれば破棄する。この音声はもう使わないので join は不要。
        abortRecording()
        // 前回のWAV/PCMを消しておく(ディスクを無駄に食わないようにするため)
        deleteCachedAudioFiles()

        val file = File(context.cacheDir, "$FILE_PREFIX${System.currentTimeMillis()}.wav")
        outputFile = file

        try {
            // Fire OS などのTV端末で拾いやすい「VOICE_RECOGNITION」を使う
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                recordBufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecordの初期化に失敗しました")
                record.release()
                return
            }

            audioRecord = record
            record.startRecording()
            isRecording = true
            Log.i(TAG, "🎤 録音を開始しました")

            // フィールドではなくローカルの record を渡す。
            // 停止処理で audioRecord が null になっても、ループ側は自分のインスタンスを見続けられる。
            recordingJob = coroutineScope.launch {
                writeAudioDataToFile(record, file)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "セキュリティ例外", e)
            abortRecording()
        } catch (e: Exception) {
            Log.e(TAG, "録音の開始に失敗しました", e)
            abortRecording()
        }
    }

    /**
     * 録音を停止し、WAV ファイルを返す。音声が取得できなかった場合は null。
     *
     * WAV の書き出し完了を待ってから返すため suspend になっている。
     * この join を省くと、呼び出し側が書き込み前のファイルを読んでしまう。
     */
    suspend fun stopRecording(): File? {
        if (!isRecording) return null
        isRecording = false

        // 1. 先に capture を止めて、ブロック中の read() を解放する
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "録音の停止時にエラー", e)
        }

        // 2. WAVの書き出し完了を待つ(ここを待たないと前回の録音を送ってしまう)
        recordingJob?.join()
        recordingJob = null

        // 3. 書き出しループが AudioRecord を参照しなくなった後で初めて release する。
        //    以前は join より前に release していたため、ループ側が解放済みインスタンスを
        //    read() して IllegalStateException を投げ、WAVが生成されないことがあった。
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecordの解放時にエラー", e)
        }
        audioRecord = null

        Log.i(TAG, "⏹️ 録音を終了しました")

        // ヘッダのみ(44バイト)はデータ0件なので、録音できなかった扱いにする
        return outputFile?.takeIf { it.exists() && it.length() > WAV_HEADER_SIZE }
    }

    /**
     * 画面破棄時に呼ぶ。録音中であればマイクを解放し、内部スコープも畳む。
     * これを呼ばないと、録音中に画面を離れた場合にマイクが掴まれたままになる。
     */
    fun release() {
        abortRecording()
        deleteCachedAudioFiles()
        coroutineScope.cancel()
    }

    /** 出力を破棄して録音を打ち切る。WAVの完成を待たない停止処理。 */
    private fun abortRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "録音の中断時にエラー", e)
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecordの解放時にエラー", e)
        }
        audioRecord = null
        outputFile = null
    }

    private fun writeAudioDataToFile(record: AudioRecord, wavFile: File) {
        val rawFile = File(context.cacheDir, wavFile.nameWithoutExtension + ".pcm")
        val buffer = ByteArray(readBufferSize)
        var totalBytesRead = 0L

        try {
            FileOutputStream(rawFile).use { os ->
                while (isRecording) {
                    val read = record.read(buffer, 0, buffer.size)
                    when {
                        read > 0 -> {
                            os.write(buffer, 0, read)
                            totalBytesRead += read
                        }
                        // stop()後やエラー時は負値が返る。これ以上読めないのでループを抜ける
                        read < 0 -> {
                            Log.w(TAG, "AudioRecord.read がエラーを返しました: $read")
                            return@use
                        }
                    }
                }
            }

            Log.i(TAG, "🎤 読み取った生の音声データ量: $totalBytesRead bytes")

            if (totalBytesRead > 0) {
                copyRawToWav(rawFile, wavFile)
            } else {
                Log.w(
                    TAG,
                    "⚠️ マイクから音声データが全く取得できませんでした。リモコンのマイクがオフになっている可能性があります。"
                )
            }
        } catch (e: Exception) {
            // IOException だけでなく IllegalStateException なども捕まえる。
            // ここで例外が抜けると WAV が生成されないまま stopRecording() が返ってしまう。
            Log.e(TAG, "音声データの書き込みエラー", e)
        } finally {
            rawFile.delete()
        }
    }

    private fun copyRawToWav(rawFile: File, wavFile: File) {
        // InputStream.read() は1回で配列を埋めきる保証がなく、
        // 埋まらなかった残りが無音として送られてしまうため readBytes() を使う。
        val rawData = rawFile.readBytes()

        wavFile.outputStream().use { os ->
            writeWavHeader(os, rawData.size.toLong(), sampleRate.toLong(), 1, 16)
            os.write(rawData)
        }
    }

    /** cacheDir に残っている過去の録音ファイル(WAV / PCM)をまとめて削除する。 */
    private fun deleteCachedAudioFiles() {
        try {
            context.cacheDir
                .listFiles { file -> file.name.startsWith(FILE_PREFIX) }
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "録音キャッシュの削除に失敗しました", e)
        }
    }

    private fun writeWavHeader(
        out: FileOutputStream,
        audioDataLength: Long,
        sampleRate: Long,
        channels: Int,
        bitsPerSample: Int
    ) {
        val totalDataLen = audioDataLength + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] =
            'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte(); header[5] =
            (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte(); header[7] =
            (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] =
            'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] =
            't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0; header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte(); header[25] =
            (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte(); header[27] =
            (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte(); header[31] =
            (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] =
            't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (audioDataLength and 0xff).toByte(); header[41] =
            (audioDataLength shr 8 and 0xff).toByte()
        header[42] = (audioDataLength shr 16 and 0xff).toByte(); header[43] =
            (audioDataLength shr 24 and 0xff).toByte()
        out.write(header, 0, 44)
    }

    private companion object {
        const val TAG = "AudioRecorderHelper"

        /** 録音キャッシュのファイル名プレフィクス。セッションごとに時刻を付けてユニーク化する。 */
        const val FILE_PREFIX = "ai_concierge_voice_"

        /** WAVヘッダのバイト数。これ以下のサイズは音声データ0件を意味する。 */
        const val WAV_HEADER_SIZE = 44
    }
}
