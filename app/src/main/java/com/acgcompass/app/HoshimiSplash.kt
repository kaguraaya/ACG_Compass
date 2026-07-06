package com.acgcompass.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Hoshimi 动态开屏（P / 参考 Kotatsu）。
 *
 * 与系统开屏（androidx core-splashscreen）无缝衔接：系统开屏先以品牌底色 + Hoshimi 图标即时覆盖冷启动，
 * Compose 首帧接管后由本组件播放「指南针星形指针旋转归位 + 四芒星点亮发光」，播放一次后整屏淡出直接进主页。
 * 底色跟随系统亮/暗（与启动主题 @color/launch_background 一致），避免衔接闪色。
 */
@Composable
fun HoshimiSplash(onFinished: () -> Unit) {
    val dark = isSystemInDarkTheme()
    // 与 Theme.Hoshimi.Splash 的 windowSplashScreenBackground（@color/launch_background）保持一致，衔接无跳色。
    val bg = if (dark) Color(0xFF12131A) else Color(0xFFFDFBFF)
    val badge = Color(0xFF1F2A44)   // 品牌深蓝徽标底（与 App 图标一致）
    val ring = Color(0xFF7FB3FF)    // 指南针圆环
    val needle = Color(0xFFFFFFFF)  // 四芒星指针
    val star = Color(0xFFFFD479)    // 发光四芒星（金）

    val iconAlpha = remember { Animatable(0f) }
    val iconScale = remember { Animatable(0.72f) }
    val needleAngle = remember { Animatable(-400f) } // 旋转约 1.1 圈后归位
    val starProgress = remember { Animatable(0f) }   // 0→1：缩放 + 透明 + 辉光
    val overlayAlpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        launch { iconAlpha.animateTo(1f, tween(240)) }
        launch { iconScale.animateTo(1f, tween(660, easing = FastOutSlowInEasing)) }
        needleAngle.animateTo(0f, tween(660, easing = FastOutSlowInEasing))
        starProgress.animateTo(1f, tween(320, easing = LinearOutSlowInEasing))
        delay(140)
        overlayAlpha.animateTo(0f, tween(260))
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(overlayAlpha.value)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .size(132.dp)
                .alpha(iconAlpha.value),
        ) {
            val s = size.minDimension
            val c = Offset(size.width / 2f, size.height / 2f)
            val scale = iconScale.value

            // 圆形徽标底（模拟 App 图标在系统开屏被裁成圆形的样子，衔接自然）。
            drawCircle(color = badge, radius = s / 2f * scale, center = c)
            // 指南针圆环。
            drawCircle(
                color = ring,
                radius = s * 0.30f * scale,
                center = c,
                style = Stroke(width = s * 0.035f * scale),
            )
            // 四芒星指针（南北长、东西短），随 needleAngle 旋转归位。
            rotate(degrees = needleAngle.value, pivot = c) {
                drawPath(
                    path = fourPointStar(
                        center = c,
                        longR = s * 0.30f * scale,
                        shortR = s * 0.14f * scale,
                        innerR = s * 0.065f * scale,
                    ),
                    color = needle,
                )
            }
            // 发光四芒星（右上，收尾点亮）。
            val sp = starProgress.value
            if (sp > 0f) {
                val starCenter = Offset(c.x + s * 0.20f, c.y - s * 0.20f)
                val glowRadius = s * 0.22f * sp
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(star.copy(alpha = 0.55f * sp), Color.Transparent),
                        center = starCenter,
                        radius = glowRadius,
                    ),
                    radius = glowRadius,
                    center = starCenter,
                )
                drawPath(
                    path = sparkleStar(starCenter, outerR = s * 0.09f * sp, innerR = s * 0.032f * sp),
                    color = star.copy(alpha = sp),
                )
            }
        }
    }
}

/** 四芒星路径：N/S 用 [longR]，E/W 用 [shortR]，四个对角内凹顶点用 [innerR]（保留指针指向感）。 */
private fun fourPointStar(center: Offset, longR: Float, shortR: Float, innerR: Float): Path {
    val cx = center.x
    val cy = center.y
    val d = innerR / sqrt(2f)
    return Path().apply {
        moveTo(cx, cy - longR)      // N
        lineTo(cx + d, cy - d)      // 内 NE
        lineTo(cx + shortR, cy)     // E
        lineTo(cx + d, cy + d)      // 内 SE
        lineTo(cx, cy + longR)      // S
        lineTo(cx - d, cy + d)      // 内 SW
        lineTo(cx - shortR, cy)     // W
        lineTo(cx - d, cy - d)      // 内 NW
        close()
    }
}

/** 对称四芒星（sparkle）：四角等长 [outerR]，对角内顶点 [innerR]。 */
private fun sparkleStar(center: Offset, outerR: Float, innerR: Float): Path {
    val cx = center.x
    val cy = center.y
    val d = innerR / sqrt(2f)
    return Path().apply {
        moveTo(cx, cy - outerR)
        lineTo(cx + d, cy - d)
        lineTo(cx + outerR, cy)
        lineTo(cx + d, cy + d)
        lineTo(cx, cy + outerR)
        lineTo(cx - d, cy + d)
        lineTo(cx - outerR, cy)
        lineTo(cx - d, cy - d)
        close()
    }
}
