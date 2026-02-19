package com.beeregg2001.komorebi.data.mapper

import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.model.ReserveItem

/**
 * 予約データと他モデル間の変換を行うマッパー
 */
object ReserveMapper {
    /**
     * ReserveItem を EpgProgram に変換します。
     * 番組詳細画面 (ProgramDetailScreen) で表示するために使用します。
     */
    fun toEpgProgram(item: ReserveItem): EpgProgram {
        return EpgProgram(
            id = item.program.id,
            channel_id = item.channel.id,
            // ★修正: Long 型から Int 型へ明示的に変換してビルドエラーを解消
            network_id = item.channel.network_Id.toInt(),
            service_id = item.channel.service_Id.toInt(),
            event_id = 0, // 予約情報には含まれない場合があるため0
            title = item.program.title,
            description = item.program.description ?: "",
            start_time = item.program.startTime,
            end_time = item.program.endTime,
            duration = item.program.duration,
            detail = item.program.detail ?: emptyMap(),
            genres = null, // 必要であれば ReserveGenre -> EpgGenre の変換を追加

            // 追加されたパラメータの設定
            is_free = item.program.isFree,
            video_type = item.program.videoType,
            audio_type = item.program.audioType,
            audio_sampling_rate = item.program.audioSamplingRate
        )
    }
}