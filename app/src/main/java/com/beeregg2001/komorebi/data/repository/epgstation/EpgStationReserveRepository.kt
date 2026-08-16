package com.beeregg2001.komorebi.data.repository.epgstation

import com.beeregg2001.komorebi.data.api.EpgStationApi
import com.beeregg2001.komorebi.data.api.edcb.EdcbConstants
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.ReserveProvider
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** EPGStation の録画予約と自動予約ルールを提供するリポジトリ。 */
@Singleton
class EpgStationReserveRepository @Inject constructor(
    private val api: EpgStationApi,
    private val channelCache: EpgStationChannelCache
) : ReserveProvider {
    /** EPGStation のチャンネルを共通の予約チャンネルへ変換する。 */
    private fun mapChannel(channel: EsChannelItem?, allChannels: List<EsChannelItem>): ReserveChannel {
        return ReserveChannel(
            id = channel?.let { EpgStationDataMapper.buildChannelId(it.id) } ?: "",
            network_Id = channel?.networkId ?: 0,
            service_Id = channel?.serviceId ?: 0,
            channelNumber = channel?.let { EpgStationDataMapper.channelNumber(it, allChannels) } ?: "",
            displayChannelId = channel?.let { EpgStationDataMapper.buildChannelId(it.id) },
            type = channel?.channelType ?: "",
            name = channel?.name ?: "不明なチャンネル"
        )
    }

    /** 予約一覧をチャンネル情報と結合して取得する。 */
    override suspend fun getReserves(): Result<List<ReserveItem>> {
        return try {
            val channels = channelCache.getChannels()
            val channelMap = channels.associateBy { it.id }
            val now = System.currentTimeMillis()
            Result.success(api.getReserves().reserves.map { reserve ->
                ReserveItem(
                    id = reserve.id,
                    channel = mapChannel(channelMap[reserve.channelId], channels),
                    program = ReserveProgramDetail(
                        id = reserve.programId?.toString() ?: "",
                        title = reserve.name.ifBlank { "番組情報なし" },
                        description = reserve.description,
                        startTime = EpgStationDataMapper.toIso8601(reserve.startAt),
                        endTime = EpgStationDataMapper.toIso8601(reserve.endAt),
                        duration = ((reserve.endAt - reserve.startAt) / 1000).toInt(),
                        genres = EpgStationDataMapper.mapGenres(
                            reserve.genre1,
                            reserve.subGenre1
                        ).map { genre ->
                            ReserveGenre(major = genre.major, middle = genre.middle)
                        }
                    ),
                    isRecordingInProgress = now in reserve.startAt..reserve.endAt,
                    recordingAvailability = when {
                        reserve.isConflict -> "Conflict"
                        reserve.isSkip -> "Skipped"
                        reserve.isOverlap -> "Overlapped"
                        else -> "Pending"
                    },
                    recordSettings = ReserveRecordSettings(isEnabled = !reserve.isSkip)
                )
            })
        } catch (e: Exception) {
            Result.failure(Exception("予約一覧の取得に失敗しました。\n[詳細]: ${e.message}", e))
        }
    }

    /** 番組 ID を指定して手動予約を追加する。 */
    override suspend fun addReserve(request: ReserveRequest): Result<Unit> {
        val programId = request.programId.toLongOrNull()
            ?: return Result.failure(Exception("EPGStationでは この形式の番組IDから予約できません。"))
        return runCatching {
            api.addReserve(EsManualReserveOption(programId = programId))
            Unit
        }
    }

    /** EPGStation が扱える末尾切れ設定だけを更新する。 */
    override suspend fun updateReserve(reservationId: Int, request: ReserveRequest): Result<Unit> {
        return runCatching {
            api.updateReserve(reservationId, EsEditManualReserveOption())
            Unit
        }
    }

    /** 予約を削除する。 */
    override suspend fun deleteReservation(reservationId: Int): Result<Unit> {
        return runCatching {
            api.deleteReserve(reservationId)
            Unit
        }
    }

    /** EPGStation のルールを共通の予約条件へ変換する。 */
    private suspend fun mapCondition(rule: EsRule, channels: List<EsChannelItem>): ReservationCondition {
        val search = rule.searchOption
        return ReservationCondition(
            id = rule.id,
            reservationCount = rule.reservesCnt ?: 0,
            programSearchCondition = ProgramSearchCondition(
                isEnabled = search.name == true || search.description == true,
                keyword = search.keyword ?: "",
                excludeKeyword = search.ignoreKeyword ?: "",
                isTitleOnly = search.name == true && search.description != true,
                isCaseSensitive = search.keyCS ?: false,
                isRegexSearchEnabled = search.keyRegExp ?: false,
                serviceRanges = search.channelIds?.mapNotNull { id ->
                    channels.firstOrNull { it.id == id }?.let { channel ->
                        ProgramSearchConditionService(
                            networkId = channel.networkId.toInt(),
                            transportStreamId = 0,
                            serviceId = channel.serviceId.toInt()
                        )
                    }
                },
                genreRanges = search.genres?.mapNotNull { genre ->
                    EdcbConstants.CONTENT_TYPE[genre.genre]?.let { contentType ->
                        Genre(
                            major = contentType.first,
                            middle = contentType.second[genre.subGenre] ?: "未定義"
                        )
                    }
                },
                durationRangeMin = search.durationMin,
                durationRangeMax = search.durationMax,
                broadcastType = if (search.isFree == true) "FreeOnly" else "All",
                duplicateTitleCheckScope = if (rule.reserveOption.avoidDuplicate) {
                    "AllChannels"
                } else {
                    "None"
                },
                duplicateTitleCheckPeriodDays = rule.reserveOption.periodToAvoidDuplicate ?: 6
            ),
            recordSettings = RecordSettings(isEnabled = rule.reserveOption.enable)
        )
    }

    /** 自動予約条件の一覧を取得する。 */
    override suspend fun getReservationConditions(): Result<List<ReservationCondition>> {
        return runCatching {
            val channels = channelCache.getChannels()
            api.getRules().rules.map { mapCondition(it, channels) }
        }
    }

    /** 共通の検索条件を EPGStation のルール検索条件へ変換する。 */
    private fun mapRuleBody(
        search: ProgramSearchCondition,
        settings: RecordSettings
    ): EsAddRuleOption {
        return EsAddRuleOption(
            searchOption = EsRuleSearchOption(
                keyword = search.keyword,
                ignoreKeyword = search.excludeKeyword,
                keyCS = search.isCaseSensitive,
                keyRegExp = search.isRegexSearchEnabled,
                name = !search.isTitleOnly,
                description = !search.isTitleOnly,
                isFree = search.broadcastType == "FreeOnly",
                durationMin = search.durationRangeMin,
                durationMax = search.durationRangeMax
            ),
            reserveOption = EsRuleReserveOption(
                enable = settings.isEnabled,
                avoidDuplicate = search.duplicateTitleCheckScope != "None",
                periodToAvoidDuplicate = search.duplicateTitleCheckPeriodDays
            )
        )
    }

    /** 自動予約条件を追加する。 */
    override suspend fun addReservationCondition(request: ReservationConditionAddRequest): Result<Unit> {
        return runCatching {
            api.addRule(mapRuleBody(request.programSearchCondition, request.recordSettings))
            Unit
        }
    }

    /** 自動予約条件を更新し、更新後の条件を再取得する。 */
    override suspend fun updateReservationCondition(
        conditionId: Int,
        request: ReservationConditionUpdateRequest
    ): Result<ReservationCondition> {
        return runCatching {
            api.updateRule(
                conditionId,
                mapRuleBody(request.programSearchCondition, request.recordSettings)
            )
            val channels = channelCache.getChannels()
            mapCondition(api.getRule(conditionId), channels)
        }
    }

    /** 自動予約条件を削除する。 */
    override suspend fun deleteReservationCondition(conditionId: Int): Result<Unit> {
        return runCatching {
            api.deleteRule(conditionId)
            Unit
        }
    }
}
