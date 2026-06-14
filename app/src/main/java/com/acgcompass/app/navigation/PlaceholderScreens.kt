package com.acgcompass.app.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 五栏底栏的占位屏（RC.03.02）。真实功能页面在任务 17–21 中实现，
 * 这里仅渲染居中标题以验证导航骨架可编译、可切换、可高亮。
 *
 * @param title 居中展示的栏目名称。
 * @param subtitle 可选副标题，用于说明该页将在后续任务实现。
 */
@Composable
internal fun PlaceholderScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun TimeMachineScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(title = "时光机", subtitle = "评分回顾与年度报告（任务 20）", modifier = modifier)
}
