package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.util.EpgUtils
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecordGenrePane(
    genres: List<String>,
    selectedGenre: String?,
    onGenreSelect: (String) -> Unit,
    onClosePane: () -> Unit, // ★追加: 左キーで戻るためのコールバック
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors

    Surface(
        modifier = modifier.fillMaxHeight(),
        colors = SurfaceDefaults.colors(containerColor = colors.surface.copy(alpha = 0.95f)),
        shape = RoundedCornerShape(16.dp),
        border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f)))
    ) {
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)) {
            Text(
                text = "ジャンル選択",
                style = MaterialTheme.typography.titleSmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    GenreItem(
                        label = "全てのジャンル",
                        isSelected = selectedGenre.isNullOrEmpty(),
                        genreColor = colors.textSecondary,
                        onClick = { onGenreSelect("") },
                        onBack = onClosePane // ★追加
                    )
                }

                itemsIndexed(genres) { _, genre ->
                    GenreItem(
                        label = genre,
                        isSelected = selectedGenre == genre,
                        genreColor = EpgUtils.getGenreColor(genre),
                        onClick = { onGenreSelect(genre) },
                        onBack = onClosePane // ★追加
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GenreItem(
    label: String,
    isSelected: Boolean,
    genreColor: Color,
    onClick: () -> Unit,
    onBack: () -> Unit // ★追加
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }

    val baseBorder = if (isSelected) {
        Border(BorderStroke(1.5.dp, genreColor))
    } else {
        Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f)))
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { event ->
                // ★左キーが押されたらパネルを閉じる（親に通知）
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                    onBack()
                    true
                } else false
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) genreColor.copy(alpha = 0.15f) else Color.Transparent,
            focusedContainerColor = colors.textPrimary,
            contentColor = if (isSelected) colors.accent else colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            border = baseBorder,
            focusedBorder = Border(BorderStroke(2.dp, colors.accent))
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier
                .size(12.dp)
                .background(genreColor, RoundedCornerShape(2.dp)))
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                fontSize = 15.sp,
                maxLines = 1,
                modifier = Modifier.then(if (isFocused) Modifier.basicMarquee() else Modifier)
            )
        }
    }
}