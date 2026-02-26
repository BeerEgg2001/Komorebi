package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.* // ★追加
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecordNavigationPane(
    selectedCategory: RecordCategory,
    onCategorySelect: (RecordCategory) -> Unit,
    isOverlay: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    val backgroundColor = colors.surface.copy(alpha = 0.95f)
    val borderColor = colors.textPrimary.copy(alpha = 0.1f)

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth(),
        colors = SurfaceDefaults.colors(
            containerColor = backgroundColor,
            contentColor = colors.textPrimary
        ),
        shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
        border = Border(BorderStroke(1.dp, borderColor))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start
        ) {
            RecordCategory.values().forEach { category ->
                NavigationItem(
                    category = category,
                    isSelected = selectedCategory == category,
                    isOverlay = isOverlay, // ★追加
                    onClick = { onCategorySelect(category) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NavigationItem(
    category: RecordCategory,
    isSelected: Boolean,
    isOverlay: Boolean, // ★追加
    onClick: () -> Unit
) {
    val colors = KomorebiTheme.colors

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 12.dp)
            // ★追加: 右キーで決定して閉じる挙動
            .onKeyEvent { event ->
                if (isOverlay && event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                    onClick() // 選択を確定させる
                    true
                } else {
                    false
                }
            },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) colors.accent.copy(alpha = 0.2f) else Color.Transparent,
            focusedContainerColor = colors.textPrimary,
            contentColor = if (isSelected) colors.accent else colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = category.icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = category.label,
                fontSize = 15.sp,
                maxLines = 1,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}