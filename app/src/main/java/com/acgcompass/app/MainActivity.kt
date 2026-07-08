package com.acgcompass.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.acgcompass.app.navigation.AcgApp
import com.acgcompass.core.designsystem.AcgCompassTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single host Activity for the whole app (RC.03.02).
 *
 * Hosts the Material 3 theme and the Compose navigation graph ([AcgApp]): a 5-tab bottom
 * navigation (首页 / 发现 / 待补池 / 时光机 / 我的) plus nested routes (Detail / Settings /
 * Import / Recommender) and first-launch onboarding gating (Requirements 5.2、5.3 / RC.03.01).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // RC.20.6a：安装系统开屏（androidx core-splashscreen）——必须在 super.onCreate 之前。
        // 开屏动效（八芒星旋转 + 淡入放大）由 Theme.Hoshimi.Splash 的 windowSplashScreenAnimatedIcon
        // （@drawable/avd_splash）在**系统冷启动窗口**内直接播放，不再用 Compose 手写覆盖层——从根上
        // 消除「首帧与 Compose 争抢主线程掉帧」与「方形静态图标闪现」。setContent 直接托管 AcgApp。
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AcgCompassTheme {
                AcgApp()
            }
        }
    }
}
