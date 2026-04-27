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
import com.beeregg2001.komorebi.common.AppStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.LocalTime

// ★ 時間帯の定義
enum class TimeZone { MORNING, DAY, EVENING, NIGHT }

@RequiresApi(Build.VERSION_CODES.O)
fun getTimeZone(time: LocalTime): TimeZone {
    val hour = time.hour
    return when {
        hour in 5..8 -> TimeZone.MORNING  // 5時〜8時: 朝
        hour in 9..15 -> TimeZone.DAY     // 9時〜15時: 昼
        hour in 16..18 -> TimeZone.EVENING // 16時〜18時: 夕方
        else -> TimeZone.NIGHT            // 19時〜4時: 夜
    }
}

@RequiresApi(Build.VERSION_CODES.O)
fun getDynamicThemeLabel(theme: AppTheme, time: LocalTime = LocalTime.now()): String {
    val timeZone = getTimeZone(time)
    return when (theme) {
        AppTheme.KOMOREBI -> when (timeZone) {
            TimeZone.MORNING -> AppStrings.THEME_NAME_MORNING_KOMOREBI
            TimeZone.DAY -> AppStrings.THEME_NAME_DAY_KOMOREBI
            TimeZone.EVENING -> AppStrings.THEME_NAME_EVENING_KOMOREBI
            TimeZone.NIGHT -> AppStrings.THEME_NAME_NIGHT_KOMOREBI
        }
        AppTheme.KYLE -> when (timeZone) {
            TimeZone.MORNING -> AppStrings.THEME_NAME_MORNING_KYLE
            TimeZone.DAY -> AppStrings.THEME_NAME_DAY_KYLE
            TimeZone.EVENING -> AppStrings.THEME_NAME_EVENING_KYLE
            TimeZone.NIGHT -> AppStrings.THEME_NAME_NIGHT_KYLE
        }
        else -> theme.label
    }
}

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
    AUTUMN_LIGHT("秋 (紅葉) - Autumn Light"),

    // ★ 統合された時間連動テーマ
    KOMOREBI("時間連動 (木漏れ日)"),
    KYLE("時間連動 (海辺のカイル)"),

    // (互換性維持用)
    KOMOREBI_DAY("昼 (木漏れ日)"), KOMOREBI_NIGHT("夜 (月光)"),
    KYLE_DAY("昼 (海辺のカイル)"), KYLE_NIGHT("夜 (深海のカイル)")
}

data class KomorebiColors(
    val background: Color, val surface: Color, val accent: Color,
    val textPrimary: Color, val textSecondary: Color, val isDark: Boolean = true
)

// --- 既存パレット ---
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
val WinterDarkPalette = KomorebiColors(
    background = Color(0xFF0F2016),
    surface = Color(0xFF1A2B20),
    accent = Color(0xFFCF3C3C),
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

// --- ★ 昼夜のパレット (Komorebi / Kyle) ---
val KomorebiDayPalette = KomorebiColors(
    background = Color(0xFF8C8675),
    surface = Color(0xFF9C9584),
    accent = Color(0xFFF9F1CC),
    textPrimary = Color(0xFF1C1A17),
    textSecondary = Color(0xFF4A463D),
    isDark = false
)
val KomorebiNightPalette = KomorebiColors(
    background = Color(0xFF0B141F),
    surface = Color(0xFF132030),
    accent = Color(0xFFDDE7F5),
    textPrimary = Color(0xFFEAF1FB),
    textSecondary = Color(0xFF8A9AB0),
    isDark = true
)
val KyleDayPalette = KomorebiColors(
    background = Color(0xFF5D9B91),
    surface = Color(0xFF6CAFA5),
    accent = Color(0xFFFFE066),
    textPrimary = Color(0xFF051F1C),
    textSecondary = Color(0xFF204742),
    isDark = false
)
val KyleNightPalette = KomorebiColors(
    background = Color(0xFF05171F),
    surface = Color(0xFF0A2433),
    accent = Color(0xFF4DB6AC),
    textPrimary = Color(0xFFDDF0F7),
    textSecondary = Color(0xFF6B8AAB),
    isDark = true
)

// --- ★ [NEW] 朝夕のパレット (エモーショナルな中間色) ---
val KomorebiMorningPalette = KomorebiColors(
    background = Color(0xFF4A4D45),
    surface = Color(0xFF5C5F57),
    accent = Color(0xFFFFE0B2), // 柔らかな朝日
    textPrimary = Color(0xFFFFFBE8),
    textSecondary = Color(0xFFBDB59F),
    isDark = true
)
val KomorebiEveningPalette = KomorebiColors(
    background = Color(0xFF594F53),
    surface = Color(0xFF6E6166),
    accent = Color(0xFFFFB280), // 夕暮れのオレンジ
    textPrimary = Color(0xFFFFF0E6),
    textSecondary = Color(0xFFC4B6C2),
    isDark = true
)
val KyleMorningPalette = KomorebiColors(
    background = Color(0xFF38666B),
    surface = Color(0xFF457A80),
    accent = Color(0xFFAEE8F2), // 爽やかな朝の海
    textPrimary = Color(0xFFEAF9FA),
    textSecondary = Color(0xFF8BBFCA),
    isDark = true
)
val KyleEveningPalette = KomorebiColors(
    background = Color(0xFF5E4356),
    surface = Color(0xFF6D4E64),
    accent = Color(0xFFFFAB66), // 夕焼けの海
    textPrimary = Color(0xFFFFF3E0),
    textSecondary = Color(0xFFC4A7BC),
    isDark = true
)

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun getSeasonalBackgroundBrush(theme: AppTheme, currentTime: LocalTime): Brush {
    val alpha = if (theme.name.contains("LIGHT") || !KomorebiTheme.colors.isDark) 0.12f else 0.18f
    val timeZone = getTimeZone(currentTime)

    return when (theme) {
        AppTheme.SPRING, AppTheme.SPRING_LIGHT -> Brush.radialGradient(
            listOf(
                Color(0xFFFF8A80).copy(
                    alpha = alpha
                ), Color.Transparent
            ), center = Offset(0f, 0f), radius = 1800f
        )

        AppTheme.SUMMER, AppTheme.SUMMER_LIGHT -> Brush.verticalGradient(
            0.0f to Color(0xFF00E5FF).copy(
                alpha = alpha
            ), 0.5f to Color.Transparent
        )

        AppTheme.AUTUMN, AppTheme.AUTUMN_LIGHT -> Brush.radialGradient(
            listOf(
                Color(0xFFFF7043).copy(
                    alpha = alpha
                ), Color.Transparent
            ), center = Offset(1920f, 540f), radius = 2000f
        )

        AppTheme.WINTER_DARK, AppTheme.WINTER_LIGHT -> Brush.verticalGradient(
            0.5f to Color.Transparent,
            1.0f to Color(0xFFCF3C3C).copy(alpha = alpha)
        )

        AppTheme.KOMOREBI, AppTheme.KOMOREBI_DAY, AppTheme.KOMOREBI_NIGHT -> {
            when (timeZone) {
                TimeZone.MORNING -> Brush.verticalGradient(
                    listOf(
                        Color(0xFFFFD599).copy(alpha = alpha * 1.5f),
                        Color(0xFF2B2822).copy(alpha = 0.5f)
                    ), startY = 0f, endY = 1080f
                )

                TimeZone.DAY -> Brush.linearGradient(
                    listOf(
                        Color(0xFF3E5C38).copy(alpha = 0.55f),
                        Color(0xFFFFFBE8).copy(alpha = 0.35f),
                        Color(0xFF2B2822).copy(alpha = 0.7f)
                    ), start = Offset(400f, 0f), end = Offset(1920f, 1080f)
                )

                TimeZone.EVENING -> Brush.linearGradient(
                    listOf(
                        Color(0xFFFFB280).copy(alpha = alpha * 1.2f),
                        Color(0xFF2C2433).copy(alpha = 0.8f)
                    ), start = Offset(0f, 0f), end = Offset(1920f, 1080f)
                )

                TimeZone.NIGHT -> Brush.verticalGradient(
                    listOf(
                        Color(0xFF0F1A2A),
                        Color(0xFF060A08)
                    ), startY = 0f, endY = 1080f
                )
            }
        }

        AppTheme.KYLE, AppTheme.KYLE_DAY, AppTheme.KYLE_NIGHT -> {
            when (timeZone) {
                TimeZone.MORNING -> Brush.verticalGradient(
                    listOf(
                        Color(0xFFAEE8F2).copy(alpha = alpha * 1.2f),
                        Color(0xFF23444A).copy(alpha = 0.6f)
                    ), startY = 0f, endY = 1080f
                )

                TimeZone.DAY -> Brush.verticalGradient(
                    listOf(
                        Color(0xFF0277BD).copy(alpha = 0.55f),
                        Color(0xFF26A69A).copy(alpha = 0.25f),
                        Color(0xFF0F362F).copy(alpha = 0.65f)
                    ), startY = 0f, endY = 1080f
                )

                TimeZone.EVENING -> Brush.verticalGradient(
                    listOf(
                        Color(0xFFFFAB66).copy(alpha = alpha * 1.2f),
                        Color(0xFF38233B).copy(alpha = 0.8f)
                    ), startY = 0f, endY = 1080f
                )

                TimeZone.NIGHT -> Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Color(0xFF05171F).copy(alpha = 0.8f)
                    ), startY = 0f, endY = 1080f
                )
            }
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
        AppTheme.KOMOREBI, AppTheme.KOMOREBI_DAY, AppTheme.KOMOREBI_NIGHT -> "🍃"
        AppTheme.KYLE, AppTheme.KYLE_DAY, AppTheme.KYLE_NIGHT -> "🐬"
        else -> ""
    }
}

val LocalKomorebiColors = staticCompositionLocalOf { MonotonePalette }
val LocalAppTheme = staticCompositionLocalOf { AppTheme.MONOTONE }

object KomorebiTheme {
    val colors: KomorebiColors @Composable @ReadOnlyComposable get() = LocalKomorebiColors.current
    val theme: AppTheme @Composable @ReadOnlyComposable get() = LocalAppTheme.current
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KomorebiTheme(theme: AppTheme = AppTheme.MONOTONE, content: @Composable () -> Unit) {
    // ★ 1分ごとに現在時刻を更新して時間連動を実現
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(60000); currentTime = LocalTime.now()
        }
    }
    val timeZone = getTimeZone(currentTime)

    val komorebiColors = when (theme) {
        AppTheme.HIGHTONE -> HightonePalette
        AppTheme.WINTER_DARK -> WinterDarkPalette; AppTheme.WINTER_LIGHT -> WinterLightPalette
        AppTheme.SPRING -> SpringDarkPalette; AppTheme.SPRING_LIGHT -> SpringLightPalette
        AppTheme.SUMMER -> SummerDarkPalette; AppTheme.SUMMER_LIGHT -> SummerLightPalette
        AppTheme.AUTUMN -> AutumnDarkPalette; AppTheme.AUTUMN_LIGHT -> AutumnLightPalette
        // ★ 時間帯によるパレットの出し分け
        AppTheme.KOMOREBI, AppTheme.KOMOREBI_DAY, AppTheme.KOMOREBI_NIGHT -> {
            when (timeZone) {
                TimeZone.MORNING -> KomorebiMorningPalette
                TimeZone.DAY -> KomorebiDayPalette
                TimeZone.EVENING -> KomorebiEveningPalette
                TimeZone.NIGHT -> KomorebiNightPalette
            }
        }

        AppTheme.KYLE, AppTheme.KYLE_DAY, AppTheme.KYLE_NIGHT -> {
            when (timeZone) {
                TimeZone.MORNING -> KyleMorningPalette
                TimeZone.DAY -> KyleDayPalette
                TimeZone.EVENING -> KyleEveningPalette
                TimeZone.NIGHT -> KyleNightPalette
            }
        }

        else -> MonotonePalette
    }

    val onAccent = when (theme) {
        AppTheme.KOMOREBI, AppTheme.KOMOREBI_DAY, AppTheme.KYLE, AppTheme.KYLE_DAY -> {
            if (timeZone == TimeZone.DAY) komorebiColors.textPrimary else Color.Black
        }

        else -> if (komorebiColors.isDark) Color.Black else Color.White
    }

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