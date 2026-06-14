package com.acgcompass.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AcgCompassTheme {
                AcgApp()
            }
        }
    }
}
