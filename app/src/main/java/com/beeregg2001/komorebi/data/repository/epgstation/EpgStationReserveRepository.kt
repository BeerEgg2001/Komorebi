package com.beeregg2001.komorebi.data.repository.epgstation

import android.util.Log
import com.beeregg2001.komorebi.data.api.EpgStationApi
import com.beeregg2001.komorebi.data.api.edcb.EdcbConstants
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.ReserveProvider
import kotlinx.coroutines.CancellationException
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
    private companion object {
        private const val TAG = "EpgStationReserveRepo"
    }

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

    /**
     * EPGStation が扱える末尾切れ設定だけを更新する。
     *
     * PUT /api/reserves/{id} は saveOption/encodeOption を省略するとサーバー側で null に
     * 上書きする(stuayu/EPGStation ReservationManageModel.edit())。末尾切れだけを送ると
     * EPGStation Web UI側で設定した保存先・エンコード設定が消えるため、更新前に既存の予約を
     * 読み直し、Komorebiが編集しない項目をそのまま送り返す(read-modify-write)。
     */
    override suspend fun updateReserve(reservationId: Int, request: ReserveRequest): Result<Unit> {
        return runCatching {
            val base = api.getReserve(reservationId)
            api.updateReserve(
                reservationId,
                EsEditManualReserveOption(
                    allowEndLack = request.recordSettings.allowEndLack,
                    tags = base.tags,
                    saveOption = base.toSaveOption(),
                    encodeOption = base.toEncodeOption()?.let { keepValidEncodeModes(it) }
                )
            )
                .requireSuccessful("予約の更新")
            Unit
        }
    }

    /** 予約の保存先設定を編集用オプションへ戻す。1項目も無ければ省略する。 */
    private fun EsReserveItem.toSaveOption(): EsRuleSaveOption? {
        if (parentDirectoryName == null && directory == null && recordedFormat == null) return null
        return EsRuleSaveOption(
            parentDirectoryName = parentDirectoryName,
            directory = directory,
            recordedFormat = recordedFormat
        )
    }

    /** 予約のエンコード設定を編集用オプションへ戻す。エンコード指定が無ければ省略する。 */
    private fun EsReserveItem.toEncodeOption(): EsRuleEncodeOption? {
        if (encodeMode1 == null && encodeMode2 == null && encodeMode3 == null) return null
        // modeの無い枠にディレクトリだけ残っているとサーバーの検証(checkEncodeOption)で弾かれるため、
        // modeが無い枠は保存先も送らない。
        return EsRuleEncodeOption(
            mode1 = encodeMode1,
            encodeParentDirectoryName1 = encodeParentDirectoryName1.takeIf { encodeMode1 != null },
            directory1 = encodeDirectory1.takeIf { encodeMode1 != null },
            mode2 = encodeMode2,
            encodeParentDirectoryName2 = encodeParentDirectoryName2.takeIf { encodeMode2 != null },
            directory2 = encodeDirectory2.takeIf { encodeMode2 != null },
            mode3 = encodeMode3,
            encodeParentDirectoryName3 = encodeParentDirectoryName3.takeIf { encodeMode3 != null },
            directory3 = encodeDirectory3.takeIf { encodeMode3 != null },
            isDeleteOriginalAfterEncode = isDeleteOriginalAfterEncode
        )
    }

    /**
     * サーバーに現存しないエンコードモードの枠を取り除く。
     * サーバーは送られたmodeが config.yml の encode に無いと予約更新自体を拒否する
     * (stuayu/EPGStation ReserveOptionChecker.checkEncodeOption())。予約後にエンコード設定を
     * 削除・改名していると、既存値を送り返すだけで末尾切れの変更すらできなくなるため、
     * 実行できないmodeの枠だけを外して送る(サーバー側でも実行できない設定なので実害は無い)。
     * 設定の取得に失敗した場合は判断できないため、そのまま送ってサーバーの検証に委ねる。
     */
    private suspend fun keepValidEncodeModes(option: EsRuleEncodeOption): EsRuleEncodeOption? {
        val modes = try {
            api.getConfig().encode
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch encode modes. Sending reserve encode option as is.", e)
            null
        } ?: return option
        fun String?.isValid() = this != null && this in modes
        val filtered = option.copy(
            mode1 = option.mode1.takeIf { it.isValid() },
            encodeParentDirectoryName1 = option.encodeParentDirectoryName1.takeIf { option.mode1.isValid() },
            directory1 = option.directory1.takeIf { option.mode1.isValid() },
            mode2 = option.mode2.takeIf { it.isValid() },
            encodeParentDirectoryName2 = option.encodeParentDirectoryName2.takeIf { option.mode2.isValid() },
            directory2 = option.directory2.takeIf { option.mode2.isValid() },
            mode3 = option.mode3.takeIf { it.isValid() },
            encodeParentDirectoryName3 = option.encodeParentDirectoryName3.takeIf { option.mode3.isValid() },
            directory3 = option.directory3.takeIf { option.mode3.isValid() }
        )
        if (filtered != option) {
            Log.w(TAG, "Dropped encode modes that no longer exist on the server: $option -> $filtered")
        }
        return filtered.takeIf { it.mode1 != null || it.mode2 != null || it.mode3 != null }
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

    /**
     * 共通の検索条件を EPGStation のルール検索条件へ変換する。
     *
     * [base] を渡した場合(更新時)、Komorebi側でモデル化・編集していないフィールド
     * (saveOption/encodeOption/tags/allowEndLack/isTimeSpecification)を既存ルールから
     * 引き継ぐ。PUT /api/rules/{id} は全置換のため、これらを渡さないとサーバー側で
     * null/falseにリセットされてしまう(stuayu/EPGStation RuleDB.convertRuleToDBRule()で
     * 確認済み)。新規追加時(base=null)は従来通りの既定値になる。
     */
    private suspend fun mapRuleBody(
        search: ProgramSearchCondition,
        base: EsRule? = null
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
        }.distinct()
        if (serviceRanges.any { it.networkId != 0 || it.serviceId != 0 } &&
            selectedChannels.isEmpty()
        ) {
            throw IllegalArgumentException("指定されたチャンネルをEPGStation上で特定できません。")
        }
        val channelIds = selectedChannels.ifEmpty { channels.map { it.id } }
        val hasKeyword = search.keyword.isNotBlank()
        val hasIgnoreKeyword = search.excludeKeyword.isNotBlank()
        val searchOption = EsRuleSearchOption(
            // 時刻指定ルールはサーバー側で keyword(番組名として使う)が必須のため、
            // 編集画面で空にされた場合は既存ルールの値を引き継ぐ(ReserveOptionChecker.checkSearchOption())。
            keyword = search.keyword.takeIf { it.isNotBlank() }
                ?: base?.searchOption?.keyword?.takeIf { base.isTimeSpecification },
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
            // ★ 修正: EsRuleSearchOption.timesの単位は通常ルール(時単位: start 0〜23時・
            // range 1〜23時間)と時刻指定予約(秒単位)で異なる(stuayu/EPGStation
            // api.d.tsのSearchTime)。mapDateRanges()は常に時単位で組み立てるため、
            // 時刻指定ルール(base.isTimeSpecification==true)をKomorebiから編集すると、
            // フラグだけ引き継がれ値は秒扱いのまま送られ、意図しないごく短時間の
            // ルールに化けていた(エラーにならず静かに壊れる)。Komorebi側には
            // 時刻指定予約を編集するUIが無いため、時刻指定ルールの場合はtimesを
            // 一切再構築せず既存ルールの値をそのまま引き継ぐ。
            times = if (base?.isTimeSpecification == true) {
                base.searchOption.times
            } else {
                mapDateRanges(search.dateRanges)
            },
            isFree = true.takeIf { search.broadcastType == "FreeOnly" },
            durationMin = search.durationRangeMin,
            durationMax = search.durationRangeMax,
            // ★ 追加: 検索対象期間はKomorebiでは編集しないため、既存ルールの値を引き継ぐ。
            searchPeriods = base?.searchOption?.searchPeriods
        )
        val avoidDuplicate = search.duplicateTitleCheckScope != "None"
        return EsAddRuleOption(
            isTimeSpecification = base?.isTimeSpecification ?: false,
            searchOption = if (base != null && keepsBroadcastFlags(base.searchOption, serviceRanges, channels)) {
                // ★ 追加: 放送波フラグ(GR/BS/NW*等)で対象を指定しているルールを、チャンネル選択を
                // 変えずに更新する場合は、channelIdsへ展開せずフラグのまま送り返す。展開すると
                // その時点の局一覧で固定され、後から追加された局が対象から外れてしまうため。
                base.searchOption.copy(
                    keyword = searchOption.keyword,
                    ignoreKeyword = searchOption.ignoreKeyword,
                    keyCS = searchOption.keyCS,
                    keyRegExp = searchOption.keyRegExp,
                    name = searchOption.name,
                    description = searchOption.description,
                    extended = searchOption.extended,
                    ignoreKeyCS = searchOption.ignoreKeyCS,
                    ignoreKeyRegExp = searchOption.ignoreKeyRegExp,
                    ignoreName = searchOption.ignoreName,
                    ignoreDescription = searchOption.ignoreDescription,
                    ignoreExtended = searchOption.ignoreExtended,
                    channelIds = null,
                    genres = searchOption.genres,
                    times = searchOption.times,
                    isFree = searchOption.isFree,
                    durationMin = searchOption.durationMin,
                    durationMax = searchOption.durationMax,
                    searchPeriods = searchOption.searchPeriods
                )
            } else {
                searchOption
            },
            reserveOption = EsRuleReserveOption(
                // ★ 修正: 編集画面の「この条件を有効にする」は programSearchCondition.isEnabled を
                // 書き換える(ReserveViewModel)。以前は recordSettings.isEnabled(編集前の値のまま)を
                // 送っていたため、Komorebiから条件の有効/無効を切り替えても反映されなかった。
                enable = search.isEnabled,
                allowEndLack = base?.reserveOption?.allowEndLack ?: true,
                avoidDuplicate = avoidDuplicate,
                // ★ 修正: サーバーは avoidDuplicate=false なのに periodToAvoidDuplicate が
                // 指定されているとルールを不正とみなし 500 を返す(stuayu/EPGStation
                // ReserveOptionChecker.checkReserveOption())。以前は常に期間を送っていたため、
                // 重複回避を使わないルールはKomorebiから追加・更新できなかった。
                periodToAvoidDuplicate = search.duplicateTitleCheckPeriodDays.takeIf { avoidDuplicate },
                tags = base?.reserveOption?.tags
            ),
            saveOption = base?.saveOption,
            encodeOption = base?.encodeOption
        )
    }

    /**
     * 既存ルールが放送波フラグで対象局を指定しており、今回の更新で対象局が変わっていないか。
     * 編集画面の選択局は mapCondition() がフラグから復元したものなので、
     * (networkId, serviceId) の集合が一致すれば利用者は局の選択を変えていないとみなす。
     * フラグに該当する局が現在の局一覧に1局も無い場合も、編集画面は「局指定なし」で開くため
     * 選択が空のままなら変更なしとみなしてフラグを保持する(全局指定へ化けさせない)。
     */
    private fun keepsBroadcastFlags(
        base: EsRuleSearchOption,
        serviceRanges: List<ProgramSearchConditionService>,
        channels: List<EsChannelItem>
    ): Boolean {
        if (base.channelIds != null || !base.hasAnyBroadcastFlag()) return false
        val baseServices = selectedRuleChannels(base, channels)
            .map { it.networkId.toInt() to it.serviceId.toInt() }
            .toSet()
        return serviceRanges.map { it.networkId to it.serviceId }.toSet() == baseServices
    }

    /** GR/BS/CS/SKY/BS4K/CS4K/NW1〜NW40 のいずれかが有効か。 */
    private fun EsRuleSearchOption.hasAnyBroadcastFlag(): Boolean = listOf(
        GR, BS, CS, SKY, BS4K, CS4K,
        NW1, NW2, NW3, NW4, NW5, NW6, NW7, NW8, NW9, NW10,
        NW11, NW12, NW13, NW14, NW15, NW16, NW17, NW18, NW19, NW20,
        NW21, NW22, NW23, NW24, NW25, NW26, NW27, NW28, NW29, NW30,
        NW31, NW32, NW33, NW34, NW35, NW36, NW37, NW38, NW39, NW40
    ).any { it == true }

    /** 自動予約条件を追加する。 */
    override suspend fun addReservationCondition(request: ReservationConditionAddRequest): Result<Unit> {
        return runCatching {
            api.addRule(mapRuleBody(request.programSearchCondition))
                .requireSuccessful("自動予約条件の追加")
            Unit
        }
    }

    /**
     * 自動予約条件を更新し、更新後の条件を再取得する。
     *
     * ★ 修正: PUT /api/rules/{id} は全置換のため、以前は既存ルールを読まずに
     * Komorebiが編集する項目だけでリクエストボディを組み立てていた。結果、
     * EPGStation Web UI側で設定した保存先・エンコード設定・自動タグ・時刻指定予約の
     * フラグ等が、Komorebi側で1文字編集して保存するだけで消えていた。
     * 更新前に既存ルールを取得し、mapRuleBody()へ渡してモデル化していないフィールドを
     * 保持したまま再送する(read-modify-write)。
     */
    override suspend fun updateReservationCondition(
        conditionId: Int,
        request: ReservationConditionUpdateRequest
    ): Result<ReservationCondition> {
        return runCatching {
            val base = api.getRule(conditionId)
            api.updateRule(
                conditionId,
                mapRuleBody(request.programSearchCondition, base)
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
        if (code() == 401) throw Exception(EpgStationDataMapper.AUTH_REQUIRED_MESSAGE.format(operation))
        val detail = errorBody()?.string()?.take(300)?.takeIf { it.isNotBlank() }
        throw Exception("$operation に失敗しました (HTTP ${code()})${detail?.let { ": $it" }.orEmpty()}")
    }
}
