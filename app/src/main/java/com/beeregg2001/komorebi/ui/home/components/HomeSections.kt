@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.getSeasonalIcon

@Composable
fun SectionHeader(title: String, icon: ImageVector, modifier: Modifier = Modifier) {
    val colors = KomorebiTheme.colors
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(18.dp), tint = colors.textPrimary.copy(0.6f))
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary
        )
    }
}

@Composable
fun NavigationLinkButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = KomorebiTheme.colors
    Box(modifier = Modifier.padding(start = 48.dp, top = 12.dp)) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.colors(
                containerColor = colors.textPrimary.copy(0.05f),
                contentColor = colors.textPrimary,
                focusedContainerColor = colors.textPrimary,
                focusedContentColor = if (colors.isDark) Color.Black else Color.White
            ),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            modifier = Modifier.height(40.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(text = label, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
fun LastWatchedSection(
    channels: List<Channel>,
    groupedChannels: Map<String, List<Channel>>,
    konomiIp: String, konomiPort: String,
    mirakurunIp: String, mirakurunPort: String,
    modifier: Modifier = Modifier,
    onChannelClick: (Channel) -> Unit,
    onUpdateHeroInfo: (HomeHeroInfo) -> Unit
) {
    val isKonomiTvMode =
        mirakurunIp.isEmpty() || mirakurunIp == "localhost" || mirakurunIp == "127.0.0.1"

    Column(modifier = Modifier.animateContentSize()) {
        SectionHeader(
            "前回視聴したチャンネル",
            Icons.Default.History,
            Modifier.padding(horizontal = 48.dp)
        )
        TvLazyRow(
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(channels, key = { "ch_${it.id}" }) { channel ->
                val logoUrl = if (isKonomiTvMode) UrlBuilder.getKonomiTvLogoUrl(
                    konomiIp,
                    konomiPort,
                    channel.displayChannelId
                )
                else UrlBuilder.getMirakurunLogoUrl(
                    mirakurunIp,
                    mirakurunPort,
                    channel.networkId,
                    channel.serviceId
                )
                val liveChannel = remember(groupedChannels, channel.id) {
                    groupedChannels.values.flatten().find { it.id == channel.id }
                }

                LastWatchedChannelCard(
                    channel = channel,
                    liveChannel = liveChannel,
                    logoUrl = logoUrl,
                    onClick = { onChannelClick(channel) },
                    onFocus = {
                        onUpdateHeroInfo(
                            HomeHeroInfo(
                                title = liveChannel?.programPresent?.title ?: channel.name,
                                subtitle = channel.name,
                                description = liveChannel?.programPresent?.description
                                    ?: "前回視聴していたチャンネルです。",
                                imageUrl = logoUrl,
                                isThumbnail = false,
                                tag = "前回視聴"
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
fun HotChannelSection(
    hotChannels: List<UiChannelState>,
    konomiIp: String, konomiPort: String,
    modifier: Modifier = Modifier,
    onChannelClick: (Channel) -> Unit,
    onUpdateHeroInfo: (HomeHeroInfo) -> Unit
) {
    Column(modifier = Modifier.animateContentSize()) {
        SectionHeader(
            "今、盛り上がっているチャンネル",
            Icons.Default.TrendingUp,
            Modifier.padding(horizontal = 48.dp)
        )
        TvLazyRow(
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(hotChannels, key = { "hot_${it.channel.id}" }) { uiState ->
                val logoUrl = UrlBuilder.getKonomiTvLogoUrl(
                    konomiIp,
                    konomiPort,
                    uiState.channel.displayChannelId
                )
                HotChannelCard(
                    uiState = uiState,
                    logoUrl = logoUrl,
                    onClick = { onChannelClick(uiState.channel) },
                    onFocus = {
                        onUpdateHeroInfo(
                            HomeHeroInfo(
                                title = uiState.programTitle,
                                subtitle = uiState.name,
                                description = uiState.channel.programPresent?.description ?: "",
                                imageUrl = logoUrl,
                                isThumbnail = false,
                                tag = "盛り上がり"
                            )
                        )
                    }
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun GenrePickupSection(
    genrePickup: List<Pair<EpgProgram, String>>,
    pickupGenreName: String,
    pickupTimeSlot: String,
    konomiIp: String, konomiPort: String,
    modifier: Modifier = Modifier,
    onProgramClick: (EpgProgram) -> Unit,
    onNavigateToTab: (Int) -> Unit,
    onUpdateHeroInfo: (HomeHeroInfo) -> Unit
) {
    Column(modifier = Modifier.animateContentSize()) {
        val timePrefix = when (pickupTimeSlot) {
            "朝" -> "今朝の"; "昼" -> "今日の"; else -> "今夜の"
        }
        SectionHeader(
            "${timePrefix}${pickupGenreName}ピックアップ ${getSeasonalIcon(KomorebiTheme.theme)}",
            Icons.Default.Star,
            Modifier.padding(horizontal = 48.dp)
        )
        TvLazyRow(
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(genrePickup, key = { "pick_${it.first.id}" }) { (program, channelName) ->
                GenrePickupCard(
                    program = program,
                    channelName = channelName,
                    timeSlot = pickupTimeSlot,
                    onClick = { onProgramClick(program) },
                    onFocus = { startFormat ->
                        onUpdateHeroInfo(
                            HomeHeroInfo(
                                title = program.title,
                                subtitle = "$startFormat - $channelName",
                                description = program.description,
                                imageUrl = UrlBuilder.getKonomiTvLogoUrl(
                                    konomiIp,
                                    konomiPort,
                                    program.channel_id
                                ),
                                isThumbnail = false,
                                tag = "ピックアップ"
                            )
                        )
                    }
                )
            }
        }
        NavigationLinkButton(
            "番組表を開く",
            Icons.Default.CalendarToday,
            onClick = { onNavigateToTab(3) })
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun WatchHistorySection(
    watchHistory: List<KonomiHistoryProgram>,
    konomiIp: String, konomiPort: String,
    modifier: Modifier = Modifier,
    onHistoryClick: (KonomiHistoryProgram) -> Unit,
    onUpdateHeroInfo: (HomeHeroInfo) -> Unit
) {
    Column(modifier = Modifier.animateContentSize()) {
        SectionHeader(
            "録画の視聴履歴",
            Icons.Default.PlayCircle,
            Modifier.padding(start = 48.dp, bottom = 12.dp)
        )
        TvLazyRow(
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(watchHistory, key = { "hist_${it.program.id}" }) { history ->
                WatchHistoryCard(
                    history = history,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    onClick = { onHistoryClick(history) },
                    onFocus = { progressVal, thumbnailUrl ->
                        onUpdateHeroInfo(
                            HomeHeroInfo(
                                title = history.program.title,
                                subtitle = "視聴履歴から再開",
                                description = history.program.description,
                                imageUrl = thumbnailUrl,
                                isThumbnail = true,
                                tag = "視聴履歴",
                                progress = progressVal
                            )
                        )
                    }
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun UpcomingReserveSection(
    upcomingReserves: List<ReserveItem>,
    konomiIp: String, konomiPort: String,
    modifier: Modifier = Modifier,
    onReserveClick: (ReserveItem) -> Unit,
    onNavigateToTab: (Int) -> Unit,
    onUpdateHeroInfo: (HomeHeroInfo) -> Unit
) {
    Column(modifier = Modifier.animateContentSize()) {
        SectionHeader(
            "これからの録画予約",
            Icons.Default.RadioButtonChecked,
            Modifier.padding(horizontal = 48.dp)
        )
        TvLazyRow(
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(upcomingReserves, key = { "res_${it.id}" }) { reserve ->
                UpcomingReserveCard(
                    reserve = reserve,
                    onClick = { onReserveClick(reserve) },
                    onFocus = { startFormat ->
                        onUpdateHeroInfo(
                            HomeHeroInfo(
                                title = reserve.program.title,
                                subtitle = "$startFormat - ${reserve.channel.name}",
                                description = reserve.program.description ?: "",
                                imageUrl = UrlBuilder.getKonomiTvLogoUrl(
                                    konomiIp,
                                    konomiPort,
                                    reserve.channel.displayChannelId ?: ""
                                ),
                                isThumbnail = false,
                                tag = "録画予約"
                            )
                        )
                    }
                )
            }
        }
        NavigationLinkButton(
            "録画予約リストを表示",
            Icons.Default.List,
            onClick = { onNavigateToTab(4) })
    }
}