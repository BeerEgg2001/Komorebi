package com.beeregg2001.komorebi.data.repository.epgstation

import com.beeregg2001.komorebi.data.api.EpgStationApi
import com.beeregg2001.komorebi.data.api.edcb.EdcbConstants
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.ReserveProvider
import kotlinx.coroutines.flow.first
import retrofit2.Response
import kotlin.math.ceil
import javax.inject.Inject
import javax.inject.Singleton

/** EPGStation の録画予約と自動予約ルールを提供するリポジトリ。 */
@Singleton
class EpgStationReserveRepository @Inject constructor(
    private val api: EpgStationApi,
    private val channelCache: EpgStationChannelCache
) : ReserveProvider {
    /** EPGStation のチャンネルを共通の予約チャンネルへ変換する。 */
    private fun mapChannel(
        channel: EsChannelItem?,
        index: EpgStationDataMapper.ChannelIndex
    ): ReserveChannel {
        return ReserveChannel(
            id = channel?.let { EpgStationDataMapper.buildChannelId(it.id) } ?: "",
            network_Id = channel?.networkId ?: 0,
            service_Id = channel?.serviceId ?: 0,
            channelNumber = channel?.let { index.numberOf(it) } ?: "",
            displayChannelId = channel?.let { EpgStationDataMapper.buildChannelId(it.id) },
            type = channel?.let { EpgStationDataMapper.normalizeChannelType(it.channelType) } ?: "",
            name = channel?.name ?: "不明なチャンネル"
        )
    }

    /** 予約一覧をチャンネル情報と結合して取得する。 */
    override suspend fun getReserves(): Result<List<ReserveItem>> {
        return try {
            val index = channelCache.getChannelIndex()
            val ruleKeywords = runCatching { api.getRules().rules }
                .getOrDefault(emptyList())
                .associate { it.id to it.searchOption.keyword.orEmpty() }
            val now = System.currentTimeMillis()
            Result.success(api.getReserves().reserves.map { reserve ->
                ReserveItem(
                    id = reserve.id,
                    channel = mapChannel(index.byId[reserve.channelId], index),
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
                    comment = reserve.ruleId?.let { ruleId ->
                        ruleKeywords[ruleId]?.let { "EPG自動予約($it)" }
                    }.orEmpty(),
                    recordSettings = ReserveRecordSettings(
                        isEnabled = !reserve.isSkip,
                        allowEndLack = reserve.allowEndLack
                    )
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
            api.addReserve(
                EsManualReserveOption(
                    programId = programId,
                    allowEndLack = request.recordSettings.allowEndLack
                )
            )
                .requireSuccessful("予約の追加")
            Unit
        }
    }

    /** EPGStation が扱える末尾切れ設定だけを更新する。 */
    override suspend fun updateReserve(reservationId: Int, request: ReserveRequest): Result<Unit> {
        return runCatching {
            api.updateReserve(
                reservationId,
                EsEditManualReserveOption(allowEndLack = request.recordSettings.allowEndLack)
            )
                .requireSuccessful("予約の更新")
            Unit
        }
    }

    /** 予約を削除する。 */
    override suspend fun deleteReservation(reservationId: Int): Result<Unit> {
        return runCatching {
            api.deleteReserve(reservationId).requireSuccessful("予約の削除")
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
                isEnabled = rule.reserveOption.enable,
                keyword = search.keyword ?: "",
                excludeKeyword = search.ignoreKeyword ?: "",
                isTitleOnly = search.name == true && search.description != true,
                isCaseSensitive = search.keyCS ?: false,
                isRegexSearchEnabled = search.keyRegExp ?: false,
                serviceRanges = selectedRuleChannels(search, channels).map { channel ->
                        ProgramSearchConditionService(
                            networkId = channel.networkId.toInt(),
                            transportStreamId = 0,
                            serviceId = channel.serviceId.toInt()
                        )
                }.takeIf { it.isNotEmpty() },
                genreRanges = search.genres?.mapNotNull { genre ->
                    EdcbConstants.CONTENT_TYPE[genre.genre]?.let { contentType ->
                        Genre(
                            major = contentType.first,
                            middle = contentType.second[genre.subGenre] ?: "未定義"
                        )
                    }
                },
                dateRanges = search.times.orEmpty().flatMap(::mapRuleTime),
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
    private suspend fun mapRuleBody(
        search: ProgramSearchCondition,
        settings: RecordSettings
    ): EsAddRuleOption {
        if (search.broadcastType == "PaidOnly") {
            throw IllegalArgumentException("EPGStationの自動予約APIは有料番組のみの指定に対応していません。")
        }
        val channels = channelCache.getChannels()
        val serviceRanges = search.serviceRanges.orEmpty()
        val selectedChannels = serviceRanges.mapNotNull { service ->
            channels.firstOrNull {
                it.networkId.toInt() == service.networkId && it.serviceId.toInt() == service.serviceId
            }?.id
        }
        if (serviceRanges.any { it.networkId != 0 || it.serviceId != 0 } &&
            selectedChannels.isEmpty()
        ) {
            throw IllegalArgumentException("指定されたチャンネルをEPGStation上で特定できません。")
        }
        val channelIds = selectedChannels.ifEmpty { channels.map { it.id } }
        val hasKeyword = search.keyword.isNotBlank()
        val hasIgnoreKeyword = search.excludeKeyword.isNotBlank()
        return EsAddRuleOption(
            searchOption = EsRuleSearchOption(
                keyword = search.keyword.takeIf { it.isNotBlank() },
                ignoreKeyword = search.excludeKeyword.takeIf { it.isNotBlank() },
                keyCS = search.isCaseSensitive.takeIf { hasKeyword },
                keyRegExp = search.isRegexSearchEnabled.takeIf { hasKeyword },
                name = hasKeyword,
                description = (hasKeyword && !search.isTitleOnly),
                extended = (hasKeyword && !search.isTitleOnly),
                ignoreKeyCS = search.isCaseSensitive.takeIf { hasIgnoreKeyword },
                ignoreKeyRegExp = search.isRegexSearchEnabled.takeIf { hasIgnoreKeyword },
                ignoreName = hasIgnoreKeyword,
                ignoreDescription = hasIgnoreKeyword,
                ignoreExtended = hasIgnoreKeyword,
                channelIds = channelIds,
                genres = mapGenres(search.genreRanges),
                times = mapDateRanges(search.dateRanges),
                isFree = true.takeIf { search.broadcastType == "FreeOnly" },
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
                .requireSuccessful("自動予約条件の追加")
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
            ).requireSuccessful("自動予約条件の更新")
            val channels = channelCache.getChannels()
            mapCondition(api.getRule(conditionId), channels)
        }
    }

    /** 自動予約条件を削除する。 */
    override suspend fun deleteReservationCondition(conditionId: Int): Result<Unit> {
        return runCatching {
            api.deleteRule(conditionId).requireSuccessful("自動予約条件の削除")
            Unit
        }
    }

    /** 共通ジャンル名を EPGStation の ARIB ジャンル番号へ戻す。 */
    private fun mapGenres(genres: List<Genre>?): List<EsRuleGenre>? {
        return genres.orEmpty().mapNotNull { genre ->
            val major = EdcbConstants.CONTENT_TYPE.entries
                .firstOrNull { it.value.first == genre.major } ?: return@mapNotNull null
            val middle = major.value.second.entries
                .firstOrNull { it.value == genre.middle }?.key
            EsRuleGenre(genre = major.key, subGenre = middle)
        }.takeIf { it.isNotEmpty() }
    }

    /** channelIds または放送波フラグから、既存ルールの対象チャンネルを復元する。 */
    private fun selectedRuleChannels(
        search: EsRuleSearchOption,
        channels: List<EsChannelItem>
    ): List<EsChannelItem> {
        search.channelIds?.let { ids -> return channels.filter { it.id in ids } }
        val types = buildSet {
            if (search.GR == true) add("GR")
            if (search.BS == true) add("BS")
            if (search.CS == true) add("CS")
            if (search.SKY == true) add("SKY")
            if (search.BS4K == true) add("BS4K")
            if (search.CS4K == true) add("CS4K")
            listOf(
                search.NW1, search.NW2, search.NW3, search.NW4, search.NW5,
                search.NW6, search.NW7, search.NW8, search.NW9, search.NW10,
                search.NW11, search.NW12, search.NW13, search.NW14, search.NW15,
                search.NW16, search.NW17, search.NW18, search.NW19, search.NW20,
                search.NW21, search.NW22, search.NW23, search.NW24, search.NW25,
                search.NW26, search.NW27, search.NW28, search.NW29, search.NW30,
                search.NW31, search.NW32, search.NW33, search.NW34, search.NW35,
                search.NW36, search.NW37, search.NW38, search.NW39, search.NW40
            ).forEachIndexed { index, enabled -> if (enabled == true) add("NW${index + 1}") }
        }
        return channels.filter { it.channelType in types }
    }

    /** 曜日・時刻範囲をEPGStationの時間検索へ変換する。分指定は包含する時間帯へ広げる。 */
    private fun mapDateRanges(ranges: List<ProgramSearchConditionDate>?): List<EsRuleTime>? {
        val converted = ranges.orEmpty().map { range ->
            val startMinutes = range.startHour * 60 + range.startMinute
            val dayOffset = (range.endDayOfWeek - range.startDayOfWeek + 7) % 7
            var endMinutes = dayOffset * 24 * 60 + range.endHour * 60 + range.endMinute
            if (endMinutes <= startMinutes) endMinutes += 24 * 60
            val coveredHours = ceil((endMinutes - range.startHour * 60) / 60.0).toInt()
            if (coveredHours >= 24) {
                EsRuleTime(week = 1 shl range.startDayOfWeek)
            } else {
                EsRuleTime(
                    start = range.startHour,
                    range = coveredHours.coerceIn(1, 23),
                    week = 1 shl range.startDayOfWeek
                )
            }
        }
        return converted.groupBy { it.start to it.range }.map { (time, values) ->
            EsRuleTime(start = time.first, range = time.second, week = values.fold(0) { acc, it -> acc or it.week })
        }.takeIf { it.isNotEmpty() }
    }

    /** EPGStationの時間検索を編集画面用の曜日別範囲へ戻す。 */
    private fun mapRuleTime(time: EsRuleTime): List<ProgramSearchConditionDate> {
        return (0..6).mapNotNull { day ->
            if (time.week and (1 shl day) == 0) return@mapNotNull null
            val start = time.start ?: 0
            val range = time.range ?: 24
            val end = start + range
            ProgramSearchConditionDate(
                startDayOfWeek = day,
                startHour = start,
                startMinute = 0,
                endDayOfWeek = (day + end / 24) % 7,
                endHour = end % 24,
                endMinute = 0
            )
        }
    }

    /** RetrofitのResponseは4xx/5xxでも例外にならないため、明示的に失敗へ変換する。 */
    private fun Response<*>.requireSuccessful(operation: String) {
        if (isSuccessful) return
        val detail = errorBody()?.string()?.take(300)?.takeIf { it.isNotBlank() }
        throw Exception("$operation に失敗しました (HTTP ${code()})${detail?.let { ": $it" }.orEmpty()}")
    }
}
