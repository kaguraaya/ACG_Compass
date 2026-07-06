package com.acgcompass.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
        // P：安装系统开屏（core-splashscreen）——必须在 super.onCreate 之前，让冷启动即时套用
        // Theme.Hoshimi.Splash 的 DayNight 底色（承接 O），随后由 Compose HoshimiSplash 接管动效。
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AcgCompassTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AcgApp()
                    // P：动态开屏动效覆盖层。rememberSaveable 保证仅冷启动播放一次；
                    // 动画播完淡出后移除，直接露出主页（配置变更 / 重组不重播）。
                    var showSplash by rememberSaveable { mutableStateOf(true) }
                    if (showSplash) {
                        HoshimiSplash(onFinished = { showSplash = false })
                    }
                }
            }
        }
    }
}
