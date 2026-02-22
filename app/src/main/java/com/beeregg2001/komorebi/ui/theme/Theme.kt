package com.beeregg2001.komorebi.ui.theme

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import java.time.LocalTime

// 1. テーマの種類を定義
enum class AppTheme(val label: String) {
    MONOTONE("モノトーン"),
    HIGHTONE("ハイトーン"),
    WINTER_DARK("冬 (聖夜) - Winter Dark"),
    WINTER_LIGHT("冬 (祝祭) - Winter Light"),
    SPRING("春 (夜桜) - Spring Dark"),
    SPRING_LIGHT("春 (昼桜) - Spring Light"),
    SUMMER("夏 (夜海) - Summer Dark"),
    SUMMER_LIGHT("夏 (青空) - Summer Light"),
    AUTUMN("秋 (夜長) - Autumn Dark"),
    AUTUMN_LIGHT("秋 (紅葉) - Autumn Light")
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

// --- 各テーマの配色定義 ---
val MonotonePalette = KomorebiColors(
    background = Color(0xFF121212),
    surface = Color(0xFF1A1A1A),
    accent = Color.White,
    textPrimary = Color.White,
    textSecondary = Color.Gray,
    isDark = true
)
val HightonePalette = KomorebiColors(
    background = Color(0xFFF0F3F5),
    surface = Color(0xFFFFFFFF),
    accent = Color(0xFF00A0E9),
    textPrimary = Color(0xFF222222),
    textSecondary = Color(0xFF666666),
    isDark = false
)

// ★修正: 背景をより深いグリーンに、アクセントを落ち着いた赤系に
val WinterDarkPalette = KomorebiColors(
    background = Color(0xFF0F2016), // 深い森のような緑
    surface = Color(0xFF1A2B20),    // 少し明るい緑
    accent = Color(0xFFCF3C3C),     // 派手すぎないシックな赤
    textPrimary = Color(0xFFF0F4F1),
    textSecondary = Color(0xFF8A9AB0),
    isDark = true
)
val WinterLightPalette = KomorebiColors(
    background = Color(0xFFFAF7F2),
    surface = Color(0xFFFFFFFF),
    accent = Color(0xFFD32F2F),
    textPrimary = Color(0xFF2B1F1F),
    textSecondary = Color(0xFF7A6868),
    isDark = false
)
val SpringDarkPalette = KomorebiColors(
    background = Color(0xFF1F1216),
    surface = Color(0xFF2E1C22),
    accent = Color(0xFFF48FB1),
    textPrimary = Color(0xFFFFF0F5),
    textSecondary = Color(0xFFBCAAA4),
    isDark = true
)
val SpringLightPalette = KomorebiColors(
    background = Color(0xFFFCE4EC),
    surface = Color(0xFFFFFFFF),
    accent = Color(0xFFD81B60),
    textPrimary = Color(0xFF4E342E),
    textSecondary = Color(0xFF8D6E63),
    isDark = false
)
val SummerDarkPalette = KomorebiColors(
    background = Color(0xFF0B132B),
    surface = Color(0xFF1C2541),
    accent = Color(0xFF00E5FF),
    textPrimary = Color(0xFFF0F4FF),
    textSecondary = Color(0xFF8E9EBD),
    isDark = true
)
val SummerLightPalette = KomorebiColors(
    background = Color(0xFFE1F5FE),
    surface = Color(0xFFFFFFFF),
    accent = Color(0xFF0288D1),
    textPrimary = Color(0xFF011A27),
    textSecondary = Color(0xFF546E7A),
    isDark = false
)
val AutumnDarkPalette = KomorebiColors(
    background = Color(0xFF2C1E16),
    surface = Color(0xFF3E2A20),
    accent = Color(0xFFFF7043),
    textPrimary = Color(0xFFFFF3E0),
    textSecondary = Color(0xFFBCAAA4),
    isDark = true
)
val AutumnLightPalette = KomorebiColors(
    background = Color(0xFFEBE0D8),
    surface = Color(0xFFFFFFFF),
    accent = Color(0xFFD84315),
    textPrimary = Color(0xFF3E2723),
    textSecondary = Color(0xFF8D6E63),
    isDark = false
)

// 3. 季節の装飾用ヘルパー
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun getSeasonalBackgroundBrush(theme: AppTheme, currentTime: LocalTime): Brush {
    val alpha = if (theme.name.contains("LIGHT")) 0.10f else 0.15f
    val hour = currentTime.hour

    return when (theme) {
        AppTheme.SPRING, AppTheme.SPRING_LIGHT -> {
            val center = if (hour in 16..18) Offset(1920f, 200f) else Offset(0f, 0f)
            Brush.radialGradient(
                listOf(Color(0xFFFF8A80).copy(alpha = alpha), Color.Transparent),
                center = center,
                radius = 1800f
            )
        }

        AppTheme.SUMMER, AppTheme.SUMMER_LIGHT -> {
            Brush.verticalGradient(
                0.0f to Color(0xFF00E5FF).copy(alpha = alpha),
                0.5f to Color.Transparent
            )
        }

        AppTheme.AUTUMN, AppTheme.AUTUMN_LIGHT -> {
            val center = if (hour in 5..9) Offset(0f, 540f) else Offset(1920f, 540f)
            Brush.radialGradient(
                listOf(Color(0xFFFF7043).copy(alpha = alpha), Color.Transparent),
                center = center,
                radius = 2000f
            )
        }

        AppTheme.WINTER_DARK, AppTheme.WINTER_LIGHT -> {
            // ★修正: 黄色系から、アクセントカラーに合わせた暖色系の赤（あるいは暖炉の火のような色）へ変更
            Brush.verticalGradient(
                0.5f to Color.Transparent,
                1.0f to Color(0xFFCF3C3C).copy(alpha = alpha)
            )
        }

        else -> Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    }
}

fun getSeasonalIcon(theme: AppTheme): String {
    return when (theme) {
        AppTheme.SPRING, AppTheme.SPRING_LIGHT -> "🌸"
        AppTheme.SUMMER, AppTheme.SUMMER_LIGHT -> "🌻"
        AppTheme.AUTUMN, AppTheme.AUTUMN_LIGHT -> "🍁"
        AppTheme.WINTER_DARK, AppTheme.WINTER_LIGHT -> "❄️"
        else -> ""
    }
}

// 4. Compose内参照用
val LocalKomorebiColors = staticCompositionLocalOf { MonotonePalette }
val LocalAppTheme = staticCompositionLocalOf { AppTheme.MONOTONE }

object KomorebiTheme {
    val colors: KomorebiColors @Composable @ReadOnlyComposable get() = LocalKomorebiColors.current
    val theme: AppTheme @Composable @ReadOnlyComposable get() = LocalAppTheme.current
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KomorebiTheme(theme: AppTheme = AppTheme.MONOTONE, content: @Composable () -> Unit) {
    val komorebiColors = when (theme) {
        AppTheme.HIGHTONE -> HightonePalette
        AppTheme.WINTER_DARK -> WinterDarkPalette; AppTheme.WINTER_LIGHT -> WinterLightPalette
        AppTheme.SPRING -> SpringDarkPalette; AppTheme.SPRING_LIGHT -> SpringLightPalette
        AppTheme.SUMMER -> SummerDarkPalette; AppTheme.SUMMER_LIGHT -> SummerLightPalette
        AppTheme.AUTUMN -> AutumnDarkPalette; AppTheme.AUTUMN_LIGHT -> AutumnLightPalette
        else -> MonotonePalette
    }

    val onAccent = if (komorebiColors.isDark) Color.Black else Color.White

    val materialColorScheme = if (komorebiColors.isDark) {
        darkColorScheme(
            primary = komorebiColors.accent,
            onPrimary = onAccent,
            secondary = komorebiColors.accent,
            onSecondary = onAccent,
            tertiary = komorebiColors.accent,
            onTertiary = onAccent,
            background = komorebiColors.background,
            onBackground = komorebiColors.textPrimary,
            surface = komorebiColors.surface,
            onSurface = komorebiColors.textPrimary,
            surfaceVariant = komorebiColors.surface,
            onSurfaceVariant = komorebiColors.textSecondary,
            error = Color(0xFFF44336)
        )
    } else {
        lightColorScheme(
            primary = komorebiColors.accent,
            onPrimary = onAccent,
            secondary = komorebiColors.accent,
            onSecondary = onAccent,
            tertiary = komorebiColors.accent,
            onTertiary = onAccent,
            background = komorebiColors.background,
            onBackground = komorebiColors.textPrimary,
            surface = komorebiColors.surface,
            onSurface = komorebiColors.textPrimary,
            surfaceVariant = komorebiColors.surface,
            onSurfaceVariant = komorebiColors.textSecondary,
            error = Color(0xFFD32F2F)
        )
    }

    CompositionLocalProvider(
        LocalKomorebiColors provides komorebiColors,
        LocalAppTheme provides theme
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = AppTypography,
            content = content
        )
    }
}