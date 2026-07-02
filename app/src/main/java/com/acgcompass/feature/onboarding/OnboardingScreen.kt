package com.acgcompass.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.acgcompass.core.designsystem.AcgCompassTheme

/**
 * 首启引导路由入口（RC.03.01 / Requirements 5.1）。
 *
 * 连接 [OnboardingViewModel]：用户点击「开始使用」时持久化 `onboardingShown = true`
 * 并回调 [onFinished]，由导航层切换到主界面。
 *
 * @param onFinished 引导完成后的回调（导航离开引导页）。
 */
@Composable
fun OnboardingRoute(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    OnboardingScreen(
        state = OnboardingUiState.DEFAULT,
        onConfirm = { consented ->
            viewModel.onOnboardingComplete(consentToProxyToken = consented)
            onFinished()
        },
        modifier = modifier,
    )
}

/**
 * 无状态引导界面（Material 3）。展示合规与隐私说明，底部提供主操作按钮。
 *
 * 设计为无状态以便预览与 UI 测试：所有内容来自 [state]，交互通过 [onConfirm] 上抛。
 */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onConfirm: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var consented by remember { mutableStateOf(false) }
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start,
        ) {
            Spacer(Modifier.height(48.dp))
            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))
            state.highlights.forEachIndexed { index, highlight ->
                HighlightCard(index = index + 1, highlight = highlight)
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { consented = !consented }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(checked = consented, onCheckedChange = { consented = it })
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.consentPrompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onConfirm(consented) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = state.confirmLabel)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HighlightCard(
    index: Int,
    highlight: OnboardingHighlight,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .semantics { contentDescription = "第 $index 条说明" },
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = index.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = highlight.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = highlight.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenPreview() {
    AcgCompassTheme {
        OnboardingScreen(
            state = OnboardingUiState.DEFAULT,
            onConfirm = {},
        )
    }
}
