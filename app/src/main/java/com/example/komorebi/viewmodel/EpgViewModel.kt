package com.example.komorebi.viewmodel

import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import com.example.komorebi.data.SettingsRepository
import com.example.komorebi.data.model.EpgChannel
import com.example.komorebi.data.model.EpgChannelWrapper
import com.example.komorebi.data.model.EpgProgram
import com.example.komorebi.data.repository.EpgRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Duration
import java.time.OffsetDateTime
import javax.inject.Inject
import com.example.komorebi.viewmodel.Program as PlayerProgram

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class EpgViewModel @Inject constructor(
    private val repository: EpgRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // --- [State] データの状態 ---
    var uiState by mutableStateOf<EpgUiState>(EpgUiState.Loading)
        private set

    private val _isPreloading = MutableStateFlow(true)
    val isPreloading: StateFlow<Boolean> = _isPreloading

    private val broadcastingTypes = listOf("GR", "BS", "CS", "BS4K", "SKY")

    // --- [State] UI表示の状態管理 ---
    private val _baseTime = MutableStateFlow(OffsetDateTime.now().withMinute(0).withSecond(0).withNano(0))
    val baseTime: StateFlow<OffsetDateTime> = _baseTime.asStateFlow()

    // 番組表の描画定数
    val dpPerMinute = 1.3f
    val hourHeightDp = 60 * dpPerMinute

    // 設定情報
    private var mirakurunBaseUrl by mutableStateOf("")

    // プレイヤー連携用
    private val _activeChannelForPlayer = MutableStateFlow<Channel?>(null)
    val activeChannelForPlayer: StateFlow<Channel?> = _activeChannelForPlayer

    private val _selectedBroadcastingType = MutableStateFlow("GR")
    val selectedBroadcastingType = _selectedBroadcastingType.asStateFlow()

    init {
        viewModelScope.launch {
            // 設定値の取得
            combine(
                settingsRepository.mirakurunIp,
                settingsRepository.mirakurunPort
            ) { ip, port ->
                "http://$ip:$port"
            }.collect { url ->
                mirakurunBaseUrl = url
                // URL確定後に初回読み込み
                preloadAllEpgData()
            }
        }
    }

    /**
     * 全放送波のデータを取得・保持する
     */
    fun preloadAllEpgData() {
        viewModelScope.launch {
            _isPreloading.value = true
            uiState = EpgUiState.Loading
            try {
                val deferredList = broadcastingTypes.map { type ->
                    async {
                        repository.fetchEpgData(
                            startTime = _baseTime.value,
                            endTime = _baseTime.value.plusDays(1),
                            channelType = type
                        ).getOrThrow()
                    }
                }
                val allData = deferredList.awaitAll().flatten()
                uiState = EpgUiState.Success(allData)
            } catch (e: Exception) {
                uiState = EpgUiState.Error(e.message ?: "データの取得に失敗しました")
            } finally {
                _isPreloading.value = false
            }
        }
    }

    /**
     * 表示の起点時間を更新し、その時間のデータを再ロードする（日付変更用）
     */
    fun updateBaseTime(newTime: OffsetDateTime) {
        viewModelScope.launch {
            _baseTime.value = newTime
            preloadAllEpgData() // 日付が変わるため再取得
        }
    }

    /**
     * 番組詳細から「視聴」が押された際の処理
     */
    fun onPlayProgram(program: EpgProgram) {
        val currentData = uiState as? EpgUiState.Success ?: return
        val wrapper = currentData.data.find { it.channel.id == program.channel_id }

        wrapper?.let {
            _activeChannelForPlayer.value = convertToPlayerChannel(it)
        }
    }

    /**
     * プレイヤーを閉じる際の処理
     */
    fun closePlayer() {
        _activeChannelForPlayer.value = null
    }

    /**
     * 番組表データ型をプレイヤー用データ型へ変換
     */
    private fun convertToPlayerChannel(wrapper: EpgChannelWrapper): Channel {
        val ec = wrapper.channel
        return Channel(
            id = ec.id,
            displayChannelId = ec.display_channel_id,
            name = ec.name,
            channelNumber = ec.channel_number,
            networkId = ec.network_id.toLong(),
            serviceId = ec.service_id.toLong(),
            type = ec.type,
            isWatchable = ec.is_watchable,
            isDisplay = true,
            programPresent = wrapper.programs.firstOrNull()?.let { prog ->
                val start = OffsetDateTime.parse(prog.start_time)
                val end = OffsetDateTime.parse(prog.end_time)
                val durationInSeconds = Duration.between(start, end).seconds.toInt()

                // ★ ここで EpgGenre -> Genre の変換を行う
                val mappedGenres = prog.genres?.map { epgGenre ->
                    com.example.komorebi.viewmodel.Genre(
                        major = epgGenre.major,
                        middle = epgGenre.middle
                    )
                } ?: emptyList()

                PlayerProgram(
                    id = prog.id,
                    title = prog.title,
                    description = prog.description,
                    detail = prog.detail,
                    startTime = prog.start_time,
                    endTime = prog.end_time,
                    duration = durationInSeconds,
                    genres = mappedGenres, // 変換後のリストを渡す
                    videoResolution = ""
                )
            },
            programFollowing = null,
            remocon_Id = ec.remocon_id
        )
    }

    /**
     * チャンネルロゴのURLを取得
     */
    @OptIn(UnstableApi::class)
    fun getLogoUrl(channel: EpgChannel): String {
        val networkIdPart = when (channel.type) {
            "GR" -> channel.network_id.toString()
            "BS", "CS", "SKY", "BS4K" -> "${channel.network_id}00"
            else -> channel.network_id.toString()
        }

        val streamId = "$networkIdPart${channel.service_id}"
        // BS/CS等の場合は networkId を 10進数5桁等に調整が必要な場合があります（昨日作成したbuildStreamId相当）
        Log.i("logourl","streamId: $streamId")
        return "$mirakurunBaseUrl/api/services/$streamId/logo"
    }

    fun updateBroadcastingType(type: String) {
        _selectedBroadcastingType.value = type
    }
}

sealed class EpgUiState {
    object Loading : EpgUiState()
    data class Success(val data: List<EpgChannelWrapper>) : EpgUiState()
    data class Error(val message: String) : EpgUiState()
}