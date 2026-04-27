package com.beeregg2001.komorebi.ui.main

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.beeregg2001.komorebi.ui.theme.getTimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun SeasonalDecor(season: String, isDark: Boolean, modifier: Modifier = Modifier) {
    val modifierWithConstraint =
        if (isDark && !season.startsWith("KOMOREBI") && !season.startsWith("KYLE")) modifier.size(
            600.dp
        ) else modifier.fillMaxSize()

    // 内部で時間を監視し、動的に描画を切り替える
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(60000); currentTime = LocalTime.now()
        }
    }

    val timeZone = getTimeZone(currentTime)
    val actualSeason = when (season) {
        "KOMOREBI", "KOMOREBI_DAY", "KOMOREBI_NIGHT" -> "KOMOREBI_${timeZone.name}"
        "KYLE", "KYLE_DAY", "KYLE_NIGHT" -> "KYLE_${timeZone.name}"
        else -> season
    }

    // ★ カイル君の回遊アニメーション (右から左へ、約45秒かけてゆっくり泳ぐ)
    val infiniteTransition = rememberInfiniteTransition(label = "kyle_swim")
    val kyleX by infiniteTransition.animateFloat(
        initialValue = 2400f, // 画面の右外からスタート
        targetValue = -600f,  // 画面の左外へ消える
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 45000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "kyle_x"
    )
    // ★ カイル君の上下の波打ち (約6秒周期でフワフワ上下する)
    val kyleYOffset by infiniteTransition.animateFloat(
        initialValue = -30f,
        targetValue = 30f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "kyle_y"
    )

    Canvas(modifier = modifierWithConstraint) {
        val random = Random(actualSeason.hashCode() + (if (isDark) 1 else 0))

        when (actualSeason) {
            "SPRING" -> {
                val baseColor = if (isDark) Color(0xFFFFC0CB) else Color(0xFFFF82A9)
                for (i in 0 until (if (isDark) 25 else 35)) {
                    val cx =
                        if (isDark) size.width * (0.3f + random.nextFloat() * 0.7f) else size.width * (0.05f + random.nextFloat() * 0.95f)
                    val cy =
                        if (isDark) size.height * (0.3f + random.nextFloat() * 0.7f) else size.height * (0.05f + random.nextFloat() * 0.95f)
                    val r = 8f + random.nextFloat() * 18f
                    val a = random.nextFloat() * 360f
                    val alpha =
                        if (isDark) (0.2f + random.nextFloat() * 0.4f) else (0.4f + random.nextFloat() * 0.4f)
                    drawSakuraPetal(Offset(cx, cy), r, a, baseColor.copy(alpha = alpha))
                }
            }

            "SUMMER" -> { /* ...省略(既存のまま)... */
            }

            "AUTUMN" -> { /* ...省略(既存のまま)... */
            }

            "WINTER" -> { /* ...省略(既存のまま)... */
            }

            // ====== 木漏れ日 ======
            "KOMOREBI_MORNING" -> {
                val beamColor = Color(0xFFFFD599).copy(alpha = 0.08f)
                for (i in 0 until 10) {
                    val startY = size.height * (0.2f + random.nextFloat() * 0.5f)
                    val endY = startY + size.height * 0.3f
                    val strokeW = 40f + random.nextFloat() * 80f
                    drawLine(
                        beamColor,
                        Offset(-100f, startY),
                        Offset(size.width + 100f, endY),
                        strokeWidth = strokeW
                    )
                }
            }

            "KOMOREBI_DAY" -> {
                val beamColor = Color(0xFFFFFBE8).copy(alpha = 0.12f)
                for (i in 0 until 14) {
                    val startX = size.width * (0.3f + random.nextFloat() * 0.7f)
                    val endX = startX - size.width * (0.4f + random.nextFloat() * 0.15f)
                    val strokeW = 20f + random.nextFloat() * 60f
                    drawLine(
                        beamColor,
                        Offset(startX, -100f),
                        Offset(endX, size.height + 100f),
                        strokeWidth = strokeW
                    )
                }
                val particleColor = Color(0xFFFFFBE8)
                for (i in 0 until 45) {
                    drawCircle(
                        particleColor.copy(alpha = 0.3f + random.nextFloat() * 0.5f),
                        radius = 2f + random.nextFloat() * 5f,
                        center = Offset(
                            random.nextFloat() * size.width,
                            random.nextFloat() * size.height
                        )
                    )
                }
            }

            "KOMOREBI_EVENING" -> {
                val beamColor = Color(0xFFFFB280).copy(alpha = 0.1f)
                for (i in 0 until 8) {
                    val startY = size.height * random.nextFloat()
                    val strokeW = 50f + random.nextFloat() * 100f
                    drawLine(
                        beamColor,
                        Offset(-100f, startY),
                        Offset(size.width + 100f, startY + 200f),
                        strokeWidth = strokeW
                    )
                }
                val fireflyColor = Color(0xFFFFAB91).copy(alpha = 0.8f)
                for (i in 0 until 15) {
                    drawCircle(
                        fireflyColor,
                        radius = 2f + random.nextFloat() * 4f,
                        center = Offset(
                            random.nextFloat() * size.width,
                            size.height * (0.5f + random.nextFloat() * 0.5f)
                        )
                    )
                }
            }

            "KOMOREBI_NIGHT" -> {
                val lightColor = Color(0xFFDDE7F5)
                val layers = 4
                for (i in 0 until layers) {
                    val spread = 1f - (i / layers.toFloat())
                    val topHalfWidth = 0.04f + (spread * 0.04f)
                    val bottomHalfWidth = 0.15f + (spread * 0.25f)
                    val path = Path().apply {
                        moveTo(size.width * (0.75f - topHalfWidth), 0f)
                        lineTo(size.width * (0.75f + topHalfWidth), 0f)
                        lineTo(size.width * (0.35f + bottomHalfWidth), size.height)
                        lineTo(size.width * (0.35f - bottomHalfWidth), size.height)
                        close()
                    }
                    val moonBeamBrush = Brush.linearGradient(
                        colors = listOf(
                            lightColor.copy(alpha = 0.06f + (i * 0.03f)),
                            Color.Transparent
                        ),
                        start = Offset(size.width * 0.75f, 0f),
                        end = Offset(size.width * 0.35f, size.height * 0.85f)
                    )
                    drawPath(path, moonBeamBrush)
                }
                val fireflyColor = Color(0xFFEAF8D9)
                for (i in 0 until 35) {
                    drawCircle(
                        fireflyColor.copy(alpha = 0.2f + random.nextFloat() * 0.6f),
                        radius = 1.5f + random.nextFloat() * 3.5f,
                        center = Offset(
                            random.nextFloat() * size.width,
                            size.height * (0.4f + random.nextFloat() * 0.6f)
                        )
                    )
                }
            }

            // ====== カイル (時間連動) ======
            "KYLE_MORNING" -> {
                val bubbleColor = Color.White.copy(alpha = 0.3f)
                for (i in 0 until 30) {
                    drawCircle(
                        bubbleColor,
                        radius = 3f + random.nextFloat() * 15f,
                        center = Offset(
                            random.nextFloat() * size.width,
                            random.nextFloat() * size.height
                        ),
                        style = Stroke(width = 1.5f)
                    )
                }
                // ★ 朝のカイル君 (白く爽やかに光る)
                drawKyleSilhouette(
                    Offset(kyleX, size.height * 0.4f + kyleYOffset),
                    120f,
                    Color.White.copy(alpha = 0.15f)
                )
            }

            "KYLE_DAY" -> {
                val bubbleColor = Color.White.copy(alpha = 0.25f)
                for (i in 0 until 60) {
                    val cx = random.nextFloat() * size.width
                    val cy = random.nextFloat() * size.height
                    val r = 4f + random.nextFloat() * 20f
                    drawCircle(
                        bubbleColor,
                        radius = r,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.5f)
                    )
                    drawCircle(
                        Color.White.copy(alpha = 0.3f),
                        radius = r * 0.25f,
                        center = Offset(cx - r * 0.35f, cy - r * 0.35f)
                    )
                }
                // ★ 昼のカイル君 (海に溶け込む淡いシルエット)
                drawKyleSilhouette(
                    Offset(kyleX, size.height * 0.5f + kyleYOffset),
                    140f,
                    Color.White.copy(alpha = 0.12f)
                )
            }

            "KYLE_EVENING" -> {
                val bubbleColor = Color(0xFFFFE0B2).copy(alpha = 0.3f)
                for (i in 0 until 40) {
                    drawCircle(
                        bubbleColor,
                        radius = 4f + random.nextFloat() * 18f,
                        center = Offset(
                            random.nextFloat() * size.width,
                            random.nextFloat() * size.height
                        ),
                        style = Stroke(width = 1.5f)
                    )
                }
                // ★ 夕方のカイル君 (夕焼けを反射してオレンジに光る)
                drawKyleSilhouette(
                    Offset(kyleX, size.height * 0.45f + kyleYOffset),
                    130f,
                    Color(0xFFFFCC80).copy(alpha = 0.15f)
                )
            }

            "KYLE_NIGHT" -> {
                val snowColor = Color(0xFFDDE8F7).copy(alpha = 0.8f)
                for (i in 0 until 90) {
                    drawCircle(
                        snowColor,
                        radius = 1.5f + random.nextFloat() * 5f,
                        center = Offset(
                            random.nextFloat() * size.width,
                            random.nextFloat() * size.height
                        )
                    )
                }
                // ★ 夜のカイル君 (月光のような青白い光を帯びて深海を泳ぐ)
                drawKyleSilhouette(
                    Offset(kyleX, size.height * 0.6f + kyleYOffset),
                    150f,
                    Color(0xFF8FBEDC).copy(alpha = 0.08f)
                )
            }
        }
    }
}

// ★ カイル君（イルカ）のシルエットを描画するヘルパー関数
// ★ [最終調整版] 下腹部の引き締めと、尾ヒレのV字切れ込みを再現
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawKyleSilhouette(
    center: Offset,
    size: Float,
    color: Color
) {
    val w = size
    val h = size * 0.45f

    val path = Path().apply {
        // --- イルカの本体（右向きベース） ---
        // おでこからスタート
        moveTo(center.x - w * 0.6f, center.y - h * 0.8f)

        // おでこからクチバシへのカーブ
        quadraticBezierTo(
            center.x - w * 0.75f, center.y - h * 0.8f,
            center.x - w * 0.9f, center.y - h * 0.1f
        )

        // クチバシ
        cubicTo(
            center.x - w * 1.05f, center.y,
            center.x - w * 1.0f, center.y + h * 0.2f,
            center.x - w * 0.8f, center.y + h * 0.3f
        )

        // ★ 修正1: 下腹部を「上向きの流線型」に引き締め
        cubicTo(
            center.x - w * 0.4f, center.y + h * 0.9f, // お腹をスッキリ上に
            center.x + w * 0.2f, center.y + h * 0.7f, // くびれも上に
            center.x + w * 0.6f, center.y + h * 0.3f  // 尾柄（付け根）
        )

        // ★ 修正2: 尾ヒレの下側への広がり
        quadraticBezierTo(
            center.x + w * 0.8f,
            center.y + h * 0.2f,
            center.x + w * 1.1f,
            center.y + h * 0.8f
        )

        // ★ 修正3: 尾ヒレの「真ん中で分かれている（V字の切れ込み）」形状
        quadraticBezierTo(
            center.x + w * 1.05f,
            center.y + h * 0.3f,
            center.x + w * 0.95f,
            center.y
        ) // 下から中心へ
        quadraticBezierTo(
            center.x + w * 1.05f,
            center.y - h * 0.3f,
            center.x + w * 1.1f,
            center.y - h * 0.8f
        ) // 中心から上へ

        // ★ 修正4: 尾ヒレ上側からスマートな背中へのライン
        quadraticBezierTo(
            center.x + w * 0.8f,
            center.y - h * 0.2f,
            center.x + w * 0.6f,
            center.y - h * 0.4f
        )

        // 背中からおでこへ戻るカーブ
        cubicTo(
            center.x + w * 0.3f, center.y - h * 1.0f,
            center.x - w * 0.4f, center.y - h * 0.9f,
            center.x - w * 0.6f, center.y - h * 0.8f
        )
        close()

        // --- 背びれ (そのまま) ---
        moveTo(center.x, center.y - h * 0.8f)
        quadraticBezierTo(
            center.x + w * 0.05f, center.y - h * 1.9f,
            center.x + w * 0.3f, center.y - h * 1.6f
        )
        quadraticBezierTo(
            center.x + w * 0.15f, center.y - h * 1.2f,
            center.x + w * 0.2f, center.y - h * 0.65f
        )
        close()

        // --- 胸びれ (お腹の引き締めに合わせて少し上に配置) ---
        moveTo(center.x - w * 0.4f, center.y + h * 0.4f)
        quadraticBezierTo(
            center.x - w * 0.3f,
            center.y + h * 1.2f,
            center.x - w * 0.1f,
            center.y + h * 1.4f
        )
        quadraticBezierTo(
            center.x - w * 0.2f,
            center.y + h * 0.9f,
            center.x - w * 0.2f,
            center.y + h * 0.5f
        )
        close()
    }

    // シルエットを描画 (左向き)
    withTransform({
        scale(1f, 1f, center)
    }) {
        drawPath(path, color)
    }

    // おともの小魚
    val fishColor = color.copy(alpha = color.alpha * 0.8f)
    drawCompanionFish(Offset(center.x - w * 0.9f, center.y - h * 2.0f), size * 0.15f, fishColor)
    drawCompanionFish(Offset(center.x - w * 1.3f, center.y + h * 1.6f), size * 0.12f, fishColor)
}

// ★ カイル君のおともの小魚 (これも少しスマートに)
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCompanionFish(
    center: Offset,
    size: Float,
    color: Color
) {
    val path = Path().apply {
        moveTo(center.x - size, center.y)
        quadraticBezierTo(center.x, center.y - size * 0.8f, center.x + size, center.y - size * 0.4f)
        lineTo(center.x + size * 1.3f, center.y - size) // 尾びれ
        lineTo(center.x + size * 1.1f, center.y)       // 切り込み
        lineTo(center.x + size * 1.3f, center.y + size) // 尾びれ
        lineTo(center.x + size, center.y + size * 0.4f)
        quadraticBezierTo(center.x, center.y + size * 0.8f, center.x - size, center.y)
        close()
    }
    // 小魚は右向きに
    withTransform({
        scale(-1f, 1f, center)
    }) {
        drawPath(path, color)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSakuraPetal(
    center: Offset,
    size: Float,
    angle: Float,
    color: Color
) {
    val path = Path().apply {
        moveTo(center.x, center.y + size)
        cubicTo(
            center.x + size,
            center.y + size * 0.2f,
            center.x + size * 0.8f,
            center.y - size,
            center.x + size * 0.15f,
            center.y - size * 0.7f
        )
        lineTo(center.x, center.y - size * 0.4f)
        lineTo(center.x - size * 0.15f, center.y - size * 0.7f)
        cubicTo(
            center.x - size * 0.8f,
            center.y - size,
            center.x - size,
            center.y + size * 0.2f,
            center.x,
            center.y + size
        )
        close()
    }
    withTransform({ rotate(angle, center) }) { drawPath(path, color) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCloud(
    center: Offset,
    width: Float,
    color: Color
) {
    val path = Path().apply {
        addOval(
            Rect(
                center.x - width * 0.4f,
                center.y - width * 0.15f,
                center.x,
                center.y + width * 0.2f
            )
        )
        addOval(
            Rect(
                center.x - width * 0.15f,
                center.y - width * 0.35f,
                center.x + width * 0.25f,
                center.y + width * 0.15f
            )
        )
        addOval(
            Rect(
                center.x + width * 0.05f,
                center.y - width * 0.2f,
                center.x + width * 0.4f,
                center.y + width * 0.15f
            )
        )
        addRoundRect(
            RoundRect(
                center.x - width * 0.3f,
                center.y - width * 0.05f,
                center.x + width * 0.3f,
                center.y + width * 0.2f,
                CornerRadius(width * 0.1f)
            )
        )
    }
    drawPath(path, color)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTwinkleStar(
    center: Offset,
    size: Float,
    color: Color
) {
    val path = Path().apply {
        moveTo(center.x, center.y - size)
        quadraticBezierTo(center.x, center.y, center.x + size, center.y)
        quadraticBezierTo(center.x, center.y, center.x, center.y + size)
        quadraticBezierTo(center.x, center.y, center.x - size, center.y)
        quadraticBezierTo(center.x, center.y, center.x, center.y - size)
        close()
    }
    drawPath(path, color)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMapleLeaf(
    center: Offset,
    size: Float,
    angle: Float,
    color: Color
) {
    val path = Path().apply {
        moveTo(center.x, center.y + size)
        lineTo(center.x, center.y + size * 0.3f)
        lineTo(center.x + size * 0.7f, center.y + size * 0.4f)
        lineTo(center.x + size * 0.4f, center.y + size * 0.1f)
        lineTo(center.x + size * 0.9f, center.y - size * 0.2f)
        lineTo(center.x + size * 0.4f, center.y - size * 0.2f)
        lineTo(center.x, center.y - size)
        lineTo(center.x - size * 0.4f, center.y - size * 0.2f)
        lineTo(center.x - size * 0.9f, center.y - size * 0.2f)
        lineTo(center.x - size * 0.4f, center.y + size * 0.1f)
        lineTo(center.x - size * 0.7f, center.y + size * 0.4f)
        lineTo(center.x, center.y + size * 0.3f)
        close()
    }
    withTransform({ rotate(angle, center) }) {
        drawPath(path, color)
        drawLine(
            Color.White.copy(alpha = 0.3f),
            Offset(center.x, center.y + size * 0.3f),
            Offset(center.x, center.y - size * 0.8f),
            strokeWidth = size * 0.08f
        )
        drawLine(
            Color.White.copy(alpha = 0.3f),
            Offset(center.x, center.y + size * 0.2f),
            Offset(center.x + size * 0.7f, center.y - size * 0.1f),
            strokeWidth = size * 0.06f
        )
        drawLine(
            Color.White.copy(alpha = 0.3f),
            Offset(center.x, center.y + size * 0.2f),
            Offset(center.x - size * 0.7f, center.y - size * 0.1f),
            strokeWidth = size * 0.06f
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSnowflake(
    center: Offset,
    radius: Float,
    angleOffset: Float,
    color: Color
) {
    val stroke = radius * 0.15f
    for (i in 0 until 6) {
        val angle = angleOffset * (PI.toFloat() / 180f) + i * 60f * (PI.toFloat() / 180f)
        val end = Offset(center.x + radius * cos(angle), center.y + radius * sin(angle))
        drawLine(color, center, end, strokeWidth = stroke)

        val branchDist = radius * 0.5f
        val branchRadius = radius * 0.35f
        val branchCenter =
            Offset(center.x + branchDist * cos(angle), center.y + branchDist * sin(angle))
        val branchAngle1 = angle + 45f * (PI.toFloat() / 180f)
        val branchAngle2 = angle - 45f * (PI.toFloat() / 180f)

        drawLine(
            color,
            branchCenter,
            Offset(
                branchCenter.x + branchRadius * cos(branchAngle1),
                branchCenter.y + branchRadius * sin(branchAngle1)
            ),
            strokeWidth = stroke
        )
        drawLine(
            color,
            branchCenter,
            Offset(
                branchCenter.x + branchRadius * cos(branchAngle2),
                branchCenter.y + branchRadius * sin(branchAngle2)
            ),
            strokeWidth = stroke
        )
    }
}