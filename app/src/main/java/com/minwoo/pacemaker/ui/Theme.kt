package com.minwoo.pacemaker.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object P {
    val Bg = Color(0xFF0B0E13)
    val Surface = Color(0xFF151A21)
    val Surface2 = Color(0xFF1E252E)
    val Line = Color(0xFF2A323D)
    val Text = Color(0xFFF3F5F7)
    val Muted = Color(0xFF8C96A3)
    val Accent = Color(0xFFFF6A3D)      // 주황
    val AccentSoft = Color(0x33FF6A3D)
    val Good = Color(0xFF3DDC97)        // 목표 범위 안
    val Fast = Color(0xFF4FC3F7)        // 목표보다 빠름
    val Slow = Color(0xFFFF5C6C)        // 목표보다 느림
    val Ready = Color(0xFF3D7BFF)

    /** 목표 대비 속도 비율로 색 결정 */
    fun speedColor(kmh: Double, target: Double, tol: Double = 0.03): Color {
        if (target <= 0 || kmh <= 0) return Muted
        val r = (kmh - target) / target
        return when {
            r > tol -> Fast
            r < -tol -> Slow
            else -> Good
        }
    }

    fun diffColor(diffS: Double): Color = when {
        diffS > 5 -> Slow
        diffS < -5 -> Fast
        else -> Good
    }
}

private val Num = FontFamily.SansSerif

private val typography = Typography(
    displayLarge = TextStyle(fontFamily = Num, fontWeight = FontWeight.Black, fontSize = 84.sp, letterSpacing = (-3).sp),
    displayMedium = TextStyle(fontFamily = Num, fontWeight = FontWeight.Black, fontSize = 52.sp, letterSpacing = (-2).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.6.sp),
)

@Composable
fun PacemakerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = P.Accent,
            onPrimary = Color.Black,
            background = P.Bg,
            onBackground = P.Text,
            surface = P.Surface,
            onSurface = P.Text,
            surfaceVariant = P.Surface2,
            onSurfaceVariant = P.Muted,
            outline = P.Line,
        ),
        typography = typography,
        content = content,
    )
}
