package com.acgcompass.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Hoshimi 动态开屏（P）。承接「方形静态图标 → 圆形动态图标 → 进入主页」的流程：
 *
 * 系统开屏（androidx core-splashscreen）以品牌底色 + 方形静态图标即时覆盖冷启动；Compose 首帧接管后，
 * 本组件在圆形图标底上播放八芒星动效——大四芒（主体）顺时针 90°、小四芒（点缀）逆时针 90°，同时淡入放大，
 * 播放一次后整屏淡出直接进主页（不再重复展示静态图标）。
 * 颜色随系统亮/暗反色：亮→白底黑星、暗→黑底白星；底色与 @color/launch_background 一致，衔接无跳色。
 */
@Composable
fun HoshimiSplash(onFinished: () -> Unit) {
    val dark = isSystemInDarkTheme()
    // 与 Theme.Hoshimi.Splash 的 windowSplashScreenBackground（@color/launch_background）一致，衔接无跳色。
    val bg = if (dark) Color(0xFF12131A) else Color(0xFFFDFBFF)
    // 圆形动态图标底（较屏幕底略深/浅一档，显出「圆形图标」轮廓，简约不抢眼）。
    val disc = if (dark) Color(0xFF181A24) else Color(0xFFEFF3FB)
    // 反色主体：亮→近黑、暗→白；点缀小四芒：亮→浅蓝、暗→深蓝（与 App 图标一致）。
    val bigStar = if (dark) Color(0xFFFFFFFF) else Color(0xFF141821)
    val smallStar = if (dark) Color(0xFF2B3F70) else Color(0xFF7FB3FF)

    val iconAlpha = remember { Animatable(0f) }
    val iconScale = remember { Animatable(0.82f) }
    val bigAngle = remember { Animatable(0f) }      // 大四芒：顺时针 0 → +90
    val smallAngle = remember { Animatable(45f) }   // 小四芒：斜向 45，逆时针 45 → -45（转 90°）
    val overlayAlpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        launch { iconAlpha.animateTo(1f, tween(240)) }
        launch { iconScale.animateTo(1f, tween(640, easing = FastOutSlowInEasing)) }
        launch { smallAngle.animateTo(-45f, tween(640, easing = FastOutSlowInEasing)) }
        bigAngle.animateTo(90f, tween(640, easing = FastOutSlowInEasing))
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

            // 圆形动态图标底。
            drawCircle(color = disc, radius = s / 2f * scale, center = c)
            // 小四芒（点缀，逆时针）——垫在大四芒下。
            rotate(degrees = smallAngle.value, pivot = c) {
                drawPath(
                    path = fourPointStar(c, outerR = s * 0.20f * scale, innerR = s * 0.085f * scale),
                    color = smallStar,
                )
            }
            // 大四芒（主体，顺时针）。
            rotate(degrees = bigAngle.value, pivot = c) {
                drawPath(
                    path = fourPointStar(c, outerR = s * 0.42f * scale, innerR = s * 0.12f * scale),
                    color = bigStar,
                )
            }
        }
    }
}

/** 对称四芒星：四臂等长 [outerR]，对角内顶点距圆心 [innerR]（点朝 N/E/S/W）。 */
private fun fourPointStar(center: Offset, outerR: Float, innerR: Float): Path {
    val cx = center.x
    val cy = center.y
    val d = innerR / sqrt(2f)
    return Path().apply {
        moveTo(cx, cy - outerR)     // N
        lineTo(cx + d, cy - d)      // 内 NE
        lineTo(cx + outerR, cy)     // E
        lineTo(cx + d, cy + d)      // 内 SE
        lineTo(cx, cy + outerR)     // S
        lineTo(cx - d, cy + d)      // 内 SW
        lineTo(cx - outerR, cy)     // W
        lineTo(cx - d, cy - d)      // 内 NW
        close()
    }
}
