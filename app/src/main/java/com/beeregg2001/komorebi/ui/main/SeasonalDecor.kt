package com.beeregg2001.komorebi.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun SeasonalDecor(season: String, isDark: Boolean, modifier: Modifier = Modifier) {
    val modifierWithConstraint = if (isDark) modifier.size(600.dp) else modifier.fillMaxSize()

    Canvas(modifier = modifierWithConstraint) {
        val random = Random(season.hashCode() + (if (isDark) 1 else 0))

        when (season) {
            "SPRING" -> {
                val baseColor = if (isDark) Color(0xFFFFC0CB) else Color(0xFFFF82A9)
                val count = if (isDark) 25 else 35
                for (i in 0 until count) {
                    val cx = if (isDark) size.width * (0.3f + random.nextFloat() * 0.7f) else size.width * (0.05f + random.nextFloat() * 0.95f)
                    val cy = if (isDark) size.height * (0.3f + random.nextFloat() * 0.7f) else size.height * (0.05f + random.nextFloat() * 0.95f)
                    val r = 8f + random.nextFloat() * 18f
                    val a = random.nextFloat() * 360f
                    val alpha = if (isDark) (0.2f + random.nextFloat() * 0.4f) else (0.4f + random.nextFloat() * 0.4f)
                    drawSakuraPetal(Offset(cx, cy), r, a, baseColor.copy(alpha = alpha))
                }
            }
            "SUMMER" -> {
                if (isDark) {
                    val starColor = Color.White
                    for (i in 0 until 40) {
                        val cx = size.width * (0.2f + random.nextFloat() * 0.8f)
                        val cy = size.height * (0.2f + random.nextFloat() * 0.8f)
                        val r = 2f + random.nextFloat() * 5f
                        val alpha = 0.2f + random.nextFloat() * 0.8f
                        if (i % 3 == 0) {
                            drawTwinkleStar(Offset(cx, cy), r * 1.5f, starColor.copy(alpha = alpha))
                        } else {
                            drawCircle(starColor.copy(alpha = alpha), radius = r, center = Offset(cx, cy))
                        }
                    }
                }

                val cloudColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.6f)
                val cloudCount = if (isDark) 4 else 7
                for (i in 0 until cloudCount) {
                    val cx = if (isDark) size.width * (0.4f + random.nextFloat() * 0.6f) else size.width * (0.1f + random.nextFloat() * 0.8f)
                    val cy = if (isDark) size.height * (0.5f + random.nextFloat() * 0.4f) else size.height * (0.4f + random.nextFloat() * 0.5f)
                    val w = 50f + random.nextFloat() * 90f
                    drawCloud(Offset(cx, cy), w, cloudColor)
                }
            }
            "AUTUMN" -> {
                val leafColors = listOf(Color(0xFFD2691E), Color(0xFFFF4500), Color(0xFFB22222), Color(0xFFDAA520))
                val count = if (isDark) 18 else 25
                for (i in 0 until count) {
                    val cx = if (isDark) size.width * (0.3f + random.nextFloat() * 0.7f) else size.width * (0.05f + random.nextFloat() * 0.95f)
                    val cy = if (isDark) size.height * (0.3f + random.nextFloat() * 0.7f) else size.height * (0.05f + random.nextFloat() * 0.95f)
                    val r = 12f + random.nextFloat() * 22f
                    val a = random.nextFloat() * 360f
                    val c = leafColors[random.nextInt(leafColors.size)]
                    val alpha = if (isDark) (0.2f + random.nextFloat() * 0.4f) else (0.5f + random.nextFloat() * 0.4f)
                    drawMapleLeaf(Offset(cx, cy), r, a, c.copy(alpha = alpha))
                }
            }
            "WINTER" -> {
                val baseColor = if (isDark) Color.White else Color(0xFF87CEFA)
                val count = if (isDark) 25 else 35
                for (i in 0 until count) {
                    val cx = if (isDark) size.width * (0.3f + random.nextFloat() * 0.7f) else size.width * (0.05f + random.nextFloat() * 0.95f)
                    val cy = if (isDark) size.height * (0.3f + random.nextFloat() * 0.7f) else size.height * (0.05f + random.nextFloat() * 0.95f)
                    val r = 6f + random.nextFloat() * 14f
                    val alpha = if (isDark) (0.15f + random.nextFloat() * 0.4f) else (0.4f + random.nextFloat() * 0.5f)
                    val angleOffset = random.nextFloat() * 60f
                    drawSnowflake(Offset(cx, cy), r, angleOffset, baseColor.copy(alpha = alpha))
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSakuraPetal(center: Offset, size: Float, angle: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y + size)
        cubicTo(center.x + size, center.y + size * 0.2f, center.x + size * 0.8f, center.y - size, center.x + size * 0.15f, center.y - size * 0.7f)
        lineTo(center.x, center.y - size * 0.4f)
        lineTo(center.x - size * 0.15f, center.y - size * 0.7f)
        cubicTo(center.x - size * 0.8f, center.y - size, center.x - size, center.y + size * 0.2f, center.x, center.y + size)
        close()
    }
    withTransform({ rotate(angle, center) }) {
        drawPath(path, color)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCloud(center: Offset, width: Float, color: Color) {
    val path = Path().apply {
        addOval(Rect(center.x - width * 0.4f, center.y - width * 0.15f, center.x, center.y + width * 0.2f))
        addOval(Rect(center.x - width * 0.15f, center.y - width * 0.35f, center.x + width * 0.25f, center.y + width * 0.15f))
        addOval(Rect(center.x + width * 0.05f, center.y - width * 0.2f, center.x + width * 0.4f, center.y + width * 0.15f))
        addRoundRect(RoundRect(center.x - width * 0.3f, center.y - width * 0.05f, center.x + width * 0.3f, center.y + width * 0.2f, CornerRadius(width * 0.1f)))
    }
    drawPath(path, color)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTwinkleStar(center: Offset, size: Float, color: Color) {
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMapleLeaf(center: Offset, size: Float, angle: Float, color: Color) {
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
        drawLine(Color.White.copy(alpha = 0.3f), Offset(center.x, center.y + size * 0.3f), Offset(center.x, center.y - size * 0.8f), strokeWidth = size * 0.08f)
        drawLine(Color.White.copy(alpha = 0.3f), Offset(center.x, center.y + size * 0.2f), Offset(center.x + size * 0.7f, center.y - size * 0.1f), strokeWidth = size * 0.06f)
        drawLine(Color.White.copy(alpha = 0.3f), Offset(center.x, center.y + size * 0.2f), Offset(center.x - size * 0.7f, center.y - size * 0.1f), strokeWidth = size * 0.06f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSnowflake(center: Offset, radius: Float, angleOffset: Float, color: Color) {
    val stroke = radius * 0.15f
    for (i in 0 until 6) {
        val angle = angleOffset * (PI.toFloat() / 180f) + i * 60f * (PI.toFloat() / 180f)
        val end = Offset(center.x + radius * cos(angle), center.y + radius * sin(angle))
        drawLine(color, center, end, strokeWidth = stroke)

        val branchDist = radius * 0.5f
        val branchRadius = radius * 0.35f
        val branchCenter = Offset(center.x + branchDist * cos(angle), center.y + branchDist * sin(angle))
        val branchAngle1 = angle + 45f * (PI.toFloat() / 180f)
        val branchAngle2 = angle - 45f * (PI.toFloat() / 180f)

        drawLine(color, branchCenter, Offset(branchCenter.x + branchRadius * cos(branchAngle1), branchCenter.y + branchRadius * sin(branchAngle1)), strokeWidth = stroke)
        drawLine(color, branchCenter, Offset(branchCenter.x + branchRadius * cos(branchAngle2), branchCenter.y + branchRadius * sin(branchAngle2)), strokeWidth = stroke)
    }
}