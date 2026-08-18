package com.beeregg2001.komorebi.data.model

/** EPGStation のチャンネル情報を表す DTO。 */
data class EsChannelItem(
    val id: Long = 0,
    val serviceId: Long = 0,
    val networkId: Long = 0,
    val name: String = "",
    val remoteControlKeyId: Int? = null,
    val hasLogoData: Boolean = false,
    val channelType: String = "",
    val region: EsNamedItem? = null,
    val affiliation: EsNamedItem? = null
)

/** EPGStation の名称付きマスタ項目を表す DTO。 */
data class EsNamedItem(val id: String = "", val name: String = "", val order: Int = 0)

/** チャンネル単位の番組表を表す DTO。 */
data class EsSchedule(
    val channel: EsChannelItem = EsChannelItem(),
    val programs: List<EsScheduleProgramItem> = emptyList()
)

/** 番組表の番組情報を表す DTO。 */
data class EsScheduleProgramItem(
    val id: Long = 0,
    val channelId: Long = 0,
    val startAt: Long = 0,
    val endAt: Long = 0,
    val isDurationUndefined: Boolean = false,
    val isFree: Boolean = true,
    val name: String = "",
    val description: String? = null,
    val extended: String? = null,
    val rawExtended: Map<String, String>? = null,
    val genre1: Int? = null,
    val subGenre1: Int? = null,
    val genre2: Int? = null,
    val subGenre2: Int? = null,
    val genre3: Int? = null,
    val subGenre3: Int? = null,
    val videoType: String? = null,
    val videoResolution: String? = null,
    val audioSamplingRate: Int? = null
)

/** 録画一覧を表す DTO。 */
data class EsRecords(val records: List<EsRecordedItem> = emptyList(), val total: Int = 0)

/** 録画番組の情報を表す DTO。 */
data class EsRecordedItem(
    val id: Int = 0,
    val ruleId: Int? = null,
    val programId: Long? = null,
    val channelId: Long = 0,
    val channelName: String? = null,
    val tsChannelName: String? = null,
    val startAt: Long = 0,
    val endAt: Long = 0,
    val name: String = "",
    val description: String? = null,
    val extended: String? = null,
    val rawExtended: Map<String, String>? = null,
    val genre1: Int? = null,
    val subGenre1: Int? = null,
    val genre2: Int? = null,
    val subGenre2: Int? = null,
    val genre3: Int? = null,
    val subGenre3: Int? = null,
    val isRecording: Boolean = false,
    val thumbnails: List<Int>? = null,
    val videoFiles: List<EsVideoFile>? = null,
    val isEncoding: Boolean = false,
    val isProtected: Boolean = false,
    val series: EsRecordedSeries? = null
)

/** 録画に紐づくシリーズ情報を表す DTO。 */
data class EsRecordedSeries(
    val seriesId: Int = 0,
    val seriesTitle: String = "",
    val seasonNumber: Int? = null,
    val episodeNumber: Double? = null,
    val episodeLabel: String? = null,
    val episodeTitle: String? = null,
    val episodeComment: String? = null,
    val episodeCommentSource: String? = null,
    val airType: String = ""
)

/** 録画ファイルの情報を表す DTO。 */
data class EsVideoFile(
    val id: Int = 0,
    val name: String = "",
    val filename: String? = null,
    val type: String = "ts",
    val duration: Double? = null,
    val startTime: Double? = null,
    val startAt: Long? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitRate: Int? = null,
    val watchHistory: EsWatchHistory? = null
)

/** 視聴履歴を表す DTO。 */
data class EsWatchHistory(
    val videoFileId: Int = 0,
    val recordedId: Int = 0,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val status: String = "unwatched",
    val updatedAt: Long = 0
)

/** 録画ファイル内のチャプターを表す DTO。 */
data class EsVideoChapter(
    val id: Int = 0,
    val startAt: Double = 0.0,
    val endAt: Double = 0.0,
    val title: String? = null
)

data class EsVideoChapters(val chapters: List<EsVideoChapter> = emptyList())
data class EsAudioTrack(val track: String = "", val name: String = "", val streamIndex: Int = 0)
data class EsAudioTracks(val tracks: List<EsAudioTrack> = emptyList())
data class EsDuration(val duration: Double? = null)
data class EsVideoFileMetadata(
    val videoFileId: Int = 0,
    val duration: Double? = null,
    val startTime: Double? = null,
    val startAt: Long? = null
)
data class EsStartStreamInfo(val streamId: Int = 0)

/** 次に見る候補を表す DTO。 */
data class EsNextUpResult(
    val currentSeriesId: Int? = null,
    val latest: List<EsRecordedItem> = emptyList(),
    val series: List<EsRecordedItem> = emptyList(),
    val hasMoreLatest: Boolean = false,
    val hasMoreSeries: Boolean = false
)

data class EsSeriesListResult(
    val items: List<EsSeriesListItem> = emptyList(),
    val total: Int = 0
)

data class EsSeriesListItem(
    val id: Int = 0,
    val title: String = "",
    val normalizedTitle: String = "",
    val titleKana: String? = null,
    val mediaType: String = "",
    val preferredChannelId: Long? = null,
    val updatedAt: Long = 0,
    val seasonYear: Int? = null,
    val seasonName: String? = null,
    val seasonSource: String? = null,
    val titleSource: String? = null,
    val recordedCount: Int = 0,
    val totalFileSize: Long = 0,
    val firstAiredAt: Long? = null,
    val lastAiredAt: Long? = null,
    val unwatchedCount: Int = 0,
    val totalEpisodes: Int? = null,
    val missingEpisodeCount: Int = 0,
    val duplicateEpisodeCount: Int = 0,
    val isOnAir: Boolean = false,
    val hasImage: Boolean = false,
    val imageSource: String? = null,
    val imageCopyright: String? = null,
    val origin: String = "local"
)

data class EsSeriesDetail(
    val id: Int = 0,
    val title: String = "",
    val normalizedTitle: String = "",
    val titleKana: String? = null,
    val mediaType: String = "",
    val preferredChannelId: Long? = null,
    val updatedAt: Long = 0,
    val seasonYear: Int? = null,
    val seasonName: String? = null,
    val seasonSource: String? = null,
    val titleSource: String? = null,
    val recordedCount: Int = 0,
    val totalFileSize: Long = 0,
    val firstAiredAt: Long? = null,
    val lastAiredAt: Long? = null,
    val unwatchedCount: Int = 0,
    val totalEpisodes: Int? = null,
    val missingEpisodeCount: Int = 0,
    val duplicateEpisodeCount: Int = 0,
    val isOnAir: Boolean = false,
    val hasImage: Boolean = false,
    val imageSource: String? = null,
    val imageCopyright: String? = null,
    val origin: String = "local",
    val recorded: List<EsSeriesRecordedRow> = emptyList()
)

data class EsSeriesRecordedRow(
    val recordedId: Int = 0,
    val channelId: Long = 0,
    val channelName: String? = null,
    val recordedTitle: String = "",
    val startAt: Long = 0,
    val endAt: Long = 0,
    val episodeId: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Double? = null,
    val episodeLabel: String? = null,
    val episodeTitle: String? = null,
    val episodeComment: String? = null,
    val episodeCommentSource: String? = null,
    val airType: String = "",
    val confidence: Double = 0.0
)
data class EsSeriesMappingValue(
    val recordedId: Int = 0,
    val recordedTitle: String = "",
    val seriesId: Int = 0,
    val seriesTitle: String = "",
    val episodeId: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Double? = null,
    val episodeLabel: String? = null,
    val episodeTitle: String? = null,
    val episodeComment: String? = null,
    val episodeCommentSource: String? = null,
    val airType: String = "",
    val matchMethod: String = "",
    val confidence: Double = 0.0,
    val manualLock: Boolean = false
)

data class EsReserves(val reserves: List<EsReserveItem> = emptyList(), val total: Int = 0)
data class EsReserveItem(
    val id: Int = 0,
    val ruleId: Int? = null,
    val isSkip: Boolean = false,
    val isConflict: Boolean = false,
    val isOverlap: Boolean = false,
    val allowEndLack: Boolean = true,
    val programId: Long? = null,
    val channelId: Long = 0,
    val startAt: Long = 0,
    val endAt: Long = 0,
    val name: String = "",
    val description: String? = null,
    val genre1: Int? = null,
    val subGenre1: Int? = null
)

data class EsManualReserveOption(
    val programId: Long? = null,
    val allowEndLack: Boolean = true
)
data class EsEditManualReserveOption(val allowEndLack: Boolean = true)
data class EsAddRuleOption(
    val isTimeSpecification: Boolean = false,
    val searchOption: EsRuleSearchOption = EsRuleSearchOption(),
    val reserveOption: EsRuleReserveOption = EsRuleReserveOption()
)
data class EsRuleSearchOption(
    val keyword: String? = null,
    val ignoreKeyword: String? = null,
    val keyCS: Boolean? = null,
    val keyRegExp: Boolean? = null,
    val name: Boolean? = null,
    val description: Boolean? = null,
    val extended: Boolean? = null,
    val ignoreKeyCS: Boolean? = null,
    val ignoreKeyRegExp: Boolean? = null,
    val ignoreName: Boolean? = null,
    val ignoreDescription: Boolean? = null,
    val ignoreExtended: Boolean? = null,
    val GR: Boolean? = null,
    val BS: Boolean? = null,
    val CS: Boolean? = null,
    val SKY: Boolean? = null,
    val NW1: Boolean? = null,
    val NW2: Boolean? = null,
    val NW3: Boolean? = null,
    val NW4: Boolean? = null,
    val NW5: Boolean? = null,
    val NW6: Boolean? = null,
    val NW7: Boolean? = null,
    val NW8: Boolean? = null,
    val NW9: Boolean? = null,
    val NW10: Boolean? = null,
    val NW11: Boolean? = null,
    val NW12: Boolean? = null,
    val NW13: Boolean? = null,
    val NW14: Boolean? = null,
    val NW15: Boolean? = null,
    val NW16: Boolean? = null,
    val NW17: Boolean? = null,
    val NW18: Boolean? = null,
    val NW19: Boolean? = null,
    val NW20: Boolean? = null,
    val NW21: Boolean? = null,
    val NW22: Boolean? = null,
    val NW23: Boolean? = null,
    val NW24: Boolean? = null,
    val NW25: Boolean? = null,
    val NW26: Boolean? = null,
    val NW27: Boolean? = null,
    val NW28: Boolean? = null,
    val NW29: Boolean? = null,
    val NW30: Boolean? = null,
    val NW31: Boolean? = null,
    val NW32: Boolean? = null,
    val NW33: Boolean? = null,
    val NW34: Boolean? = null,
    val NW35: Boolean? = null,
    val NW36: Boolean? = null,
    val NW37: Boolean? = null,
    val NW38: Boolean? = null,
    val NW39: Boolean? = null,
    val NW40: Boolean? = null,
    val BS4K: Boolean? = null,
    val CS4K: Boolean? = null,
    val channelIds: List<Long>? = null,
    val genres: List<EsRuleGenre>? = null,
    val times: List<EsRuleTime>? = null,
    val isFree: Boolean? = null,
    val durationMin: Int? = null,
    val durationMax: Int? = null
)
data class EsRuleGenre(val genre: Int = 0, val subGenre: Int? = null)
data class EsRuleTime(
    val start: Int? = null,
    val range: Int? = null,
    val week: Int = 0
)
data class EsRuleReserveOption(
    val enable: Boolean = true,
    val allowEndLack: Boolean = true,
    val avoidDuplicate: Boolean = false,
    val periodToAvoidDuplicate: Int? = null
)
data class EsRule(
    val id: Int = 0,
    val reservesCnt: Int? = null,
    val isTimeSpecification: Boolean = false,
    val searchOption: EsRuleSearchOption = EsRuleSearchOption(),
    val reserveOption: EsRuleReserveOption = EsRuleReserveOption()
)
data class EsRules(val rules: List<EsRule> = emptyList(), val total: Int = 0)

data class EsConfig(val streamConfig: EsStreamConfig? = null)
data class EsStreamConfig(
    val live: EsLiveStreamConfig? = null,
    val recorded: EsRecordedStreamConfig? = null
)
data class EsLiveStreamConfig(
    val m2ts: List<EsM2tsStreamParam>? = null,
    val m2tsll: List<String>? = null,
    val webm: List<String>? = null,
    val mp4: List<String>? = null,
    val hls: List<String>? = null
)
data class EsRecordedStreamConfig(
    val ts: EsFormatConfig? = null,
    val encoded: EsFormatConfig? = null
)
data class EsLiveFormatConfig(
    val m2ts: List<EsM2tsStreamParam>? = null,
    val m2tsll: List<String>? = null,
    val webm: List<String>? = null,
    val mp4: List<String>? = null,
    val hls: List<String>? = null
)
data class EsM2tsStreamParam(val name: String = "", val isUnconverted: Boolean = false)
data class EsFormatConfig(
    val mp4: List<String>? = null,
    val hls: List<String>? = null,
    val webm: List<String>? = null
)
data class EsPlaybackPosition(val position: Double = 0.0, val duration: Double = 0.0)


/** EPGStation の作品辞書検索結果を表す DTO。 */
data class EsDictionaryWorkSearchResult(
    val total: Int = 0,
    val items: List<EsDictionaryWork> = emptyList()
)

/** 作品辞書の検索結果 1 件を表す DTO。 */
data class EsDictionaryWork(
    val title: String = "",
    val titleKana: String? = null,
    val syobocalTid: Int? = null,
    val annictId: Int? = null,
    val wikidataQid: String? = null,
    val tmdbId: Int? = null,
    val seasonYear: Int? = null,
    val seasonName: String? = null,
    val totalEpisodes: Int? = null,
    val source: String = "",
    val matchType: String = "",
    val seriesId: Int? = null
)
