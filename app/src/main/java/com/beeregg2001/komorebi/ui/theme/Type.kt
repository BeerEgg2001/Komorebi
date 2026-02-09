package com.beeregg2001.komorebi.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.compose.ui.text.font.Font
import com.beeregg2001.komorebi.R // プロジェクトのパッケージ名に合わせて変更

// Set of Material typography styles to start with
@OptIn(ExperimentalTvMaterial3Api::class)
//val Typography = Typography(
//    bodyLarge = TextStyle(
//        fontFamily = FontFamily.Default,
//        fontWeight = FontWeight.Normal,
//        fontSize = 16.sp,
//        lineHeight = 24.sp,
//        letterSpacing = 0.5.sp
//    ),
val NotoSansJP = FontFamily(
    Font(R.font.notosansjp_regular, FontWeight.Normal),
    Font(R.font.notosansjp_medium, FontWeight.Medium),
    Font(R.font.notosansjp_bold, FontWeight.Bold),
    Font(R.font.notosansjp_semibold, FontWeight.SemiBold),
    Font(R.font.notosansjp_thin, FontWeight.Thin),
    Font(R.font.notosansjp_light, FontWeight.Light)
)
    /* Other default text styles to override
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    */
