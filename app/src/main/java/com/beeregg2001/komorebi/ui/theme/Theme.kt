package com.beeregg2001.komorebi.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

// 1. テーマの種類を定義（冬のライトモードを追加し、名前を統一）
enum class AppTheme(val label: String) {
    MONOTONE("モノトーン"),
    SPRING("春 (夜桜) - Spring Dark"),
    SPRING_LIGHT("春 (昼桜) - Spring Light"),
    SUMMER("夏 (夜海) - Summer Dark"),
    SUMMER_LIGHT("夏 (青空) - Summer Light"),
    AUTUMN("秋 (夜長) - Autumn Dark"),
    AUTUMN_LIGHT("秋 (紅葉) - Autumn Light"),

    WINTER_DARK("冬 (夜空) - Winter Dark"),      // ★変更
    WINTER_LIGHT("冬 (雪景色) - Winter Light"),  // ★追加
}

// 2. Komorebi独自のカラーパレット構造
data class KomorebiColors(
    val background: Color,
    val surface: Color,
    val accent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val isDark: Boolean = true
)

// --- 各テーマの具体的な配色定義 ---

// モノトーン
val MonotonePalette = KomorebiColors(
    background = Color(0xFF121212),
    surface = Color(0xFF1A1A1A),
    accent = Color.White,
    textPrimary = Color.White,
    textSecondary = Color.Gray,
    isDark = true
)

// 冬（ダーク/夜空）
val WinterDarkPalette = KomorebiColors(
    background = Color(0xFF0A0F1E),
    surface = Color(0xFF161C2C),
    accent = Color(0xFFE0F7FA),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF8A9AB0),
    isDark = true
)

// ★新規追加: 冬（ライト/雪景色）
val WinterLightPalette = KomorebiColors(
    background = Color(0xFFF0F4F8),  // 雪のような青みがかった白
    surface = Color(0xFFFFFFFF),     // 純白
    accent = Color(0xFF5C6BC0),      // クールなブルー
    textPrimary = Color(0xFF263238), // 深いブルーグレー
    textSecondary = Color(0xFF78909C), // クールグレー
    isDark = false
)

// 春（ダーク/夜桜）
val SpringDarkPalette = KomorebiColors(
    background = Color(0xFF1F1216),
    surface = Color(0xFF2E1C22),
    accent = Color(0xFFF48FB1),
    textPrimary = Color(0xFFFFF0F5),
    textSecondary = Color(0xFFBCAAA4),
    isDark = true
)

// 春（ライト/昼桜）
val SpringLightPalette = KomorebiColors(
    background = Color(0xFFFCE4EC),
    surface = Color(0xFFFFFFFF),
    accent = Color(0xFFD81B60),
    textPrimary = Color(0xFF4E342E),
    textSecondary = Color(0xFF8D6E63),
    isDark = false
)

// 夏（ダーク/夜海）
val SummerDarkPalette = KomorebiColors(
    background = Color(0xFF0B132B),
    surface = Color(0xFF1C2541),
    accent = Color(0xFF00E5FF),
    textPrimary = Color(0xFFF0F4FF),
    textSecondary = Color(0xFF8E9EBD),
    isDark = true
)

// 夏（ライト/青空）
val SummerLightPalette = KomorebiColors(
    background = Color(0xFFE1F5FE),
    surface = Color(0xFFFFFFFF),
    accent = Color(0xFF0288D1),
    textPrimary = Color(0xFF011A27),
    textSecondary = Color(0xFF546E7A),
    isDark = false
)

// 秋（ダーク/夜長）
val AutumnDarkPalette = KomorebiColors(
    background = Color(0xFF2C1E16),
    surface = Color(0xFF3E2A20),
    accent = Color(0xFFFF7043),
    textPrimary = Color(0xFFFFF3E0),
    textSecondary = Color(0xFFBCAAA4),
    isDark = true
)

// 秋（ライト/紅葉）
val AutumnLightPalette = KomorebiColors(
    background = Color(0xFFFDF8F5),
    surface = Color(0xFFFFFFFF),
    accent = Color(0xFFD84315),
    textPrimary = Color(0xFF3E2723),
    textSecondary = Color(0xFF8D6E63),
    isDark = false
)


// 3. Compose内で色を参照するための仕組み
val LocalKomorebiColors = staticCompositionLocalOf { MonotonePalette }

object KomorebiTheme {
    val colors: KomorebiColors
        @Composable
        @ReadOnlyComposable
        get() = LocalKomorebiColors.current
}

// 4. テーマコンポーネント
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KomorebiTheme(
    theme: AppTheme = AppTheme.MONOTONE,
    content: @Composable () -> Unit,
) {
    // 選択されたテーマに応じたパレットを選択
    val komorebiColors = when (theme) {
        AppTheme.WINTER_DARK -> WinterDarkPalette
        AppTheme.WINTER_LIGHT -> WinterLightPalette // ★追加
        AppTheme.SPRING -> SpringDarkPalette
        AppTheme.SPRING_LIGHT -> SpringLightPalette
        AppTheme.SUMMER -> SummerDarkPalette
        AppTheme.SUMMER_LIGHT -> SummerLightPalette
        AppTheme.AUTUMN -> AutumnDarkPalette
        AppTheme.AUTUMN_LIGHT -> AutumnLightPalette
        else -> MonotonePalette
    }

    // Material 3 の標準ColorSchemeも連動させる
    val materialColorScheme = if (komorebiColors.isDark) {
        darkColorScheme(
            primary = komorebiColors.accent,
            background = komorebiColors.background,
            surface = komorebiColors.surface,
            onBackground = komorebiColors.textPrimary,
            onSurface = komorebiColors.textPrimary
        )
    } else {
        lightColorScheme(
            primary = komorebiColors.accent,
            background = komorebiColors.background,
            surface = komorebiColors.surface,
            onBackground = komorebiColors.textPrimary,
            onSurface = komorebiColors.textPrimary
        )
    }

    CompositionLocalProvider(LocalKomorebiColors provides komorebiColors) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = AppTypography,
            content = content
        )
    }
}