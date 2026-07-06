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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
        onConfirm = { setup ->
            viewModel.onOnboardingComplete(setup)
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
    onConfirm: (OnboardingSetup) -> Unit,
    modifier: Modifier = Modifier,
) {
    var consented by remember { mutableStateOf(false) }
    // H：快速配置（可选）草稿——仅内存，随「开始使用」一次性上抛加密保存（空值不写）。
    var bangumiToken by remember { mutableStateOf("") }
    var aiApiKey by remember { mutableStateOf("") }
    var aiBaseUrl by remember { mutableStateOf(OnboardingDefaults.AI_BASE_URL) }
    var aiModel by remember { mutableStateOf(OnboardingDefaults.AI_MODEL) }
    var revealSecrets by remember { mutableStateOf(false) }
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

            // H：快速配置（可选）——直接在引导页填写主要设置项（Bangumi 个人 Token + AI 增强）。
            QuickSetupSection(
                bangumiToken = bangumiToken,
                onBangumiTokenChange = { bangumiToken = it },
                aiApiKey = aiApiKey,
                onAiApiKeyChange = { aiApiKey = it },
                aiBaseUrl = aiBaseUrl,
                onAiBaseUrlChange = { aiBaseUrl = it },
                aiModel = aiModel,
                onAiModelChange = { aiModel = it },
                revealSecrets = revealSecrets,
                onToggleReveal = { revealSecrets = !revealSecrets },
            )

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
                onClick = {
                    onConfirm(
                        OnboardingSetup(
                            consentToProxyToken = consented,
                            bangumiToken = bangumiToken,
                            aiApiKey = aiApiKey,
                            aiBaseUrl = aiBaseUrl,
                            aiModel = aiModel,
                        ),
                    )
                },
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

/**
 * H：首启引导「快速配置（可选）」——把设置主要项（Bangumi 个人 Token + AI 增强）搬进引导页直接填写。
 * 均可留空跳过；敏感值经宆口 [OnboardingSetup] 上抛，由 ViewModel 加密保存到 `CredentialStore`（RC.00 1.2）。
 */
@Composable
private fun QuickSetupSection(
    bangumiToken: String,
    onBangumiTokenChange: (String) -> Unit,
    aiApiKey: String,
    onAiApiKeyChange: (String) -> Unit,
    aiBaseUrl: String,
    onAiBaseUrlChange: (String) -> Unit,
    aiModel: String,
    onAiModelChange: (String) -> Unit,
    revealSecrets: Boolean,
    onToggleReveal: () -> Unit,
) {
    val mask = if (revealSecrets) VisualTransformation.None else PasswordVisualTransformation()
    Text(
        text = "快速配置（可选，可跳过）",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "以下均可留空，稍后在「我的 → 设置」补填。密钥仅加密保存在本机，不会上传。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Bangumi 个人同步", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "粘贴 Bangumi Access Token 即可同步你的收藏 / 评分（在 ${OnboardingDefaults.BANGUMI_TOKEN_HELP_URL} 生成；" +
                    "也可稍后在设置里用 Bangumi 登录）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = bangumiToken,
                onValueChange = onBangumiTokenChange,
                label = { Text("Access Token（可选）") },
                singleLine = true,
                visualTransformation = mask,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("AI 增强", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "填入 OpenAI 兼容服务的 API Key 可启用口味匹配 / 无剧透雷达（推荐 DeepSeek）；" +
                    "未填则自动回退本地规则。AI 调用会消耗你所配置服务的额度。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = aiApiKey,
                onValueChange = onAiApiKeyChange,
                label = { Text("API Key（可选）") },
                singleLine = true,
                visualTransformation = mask,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = aiBaseUrl,
                onValueChange = onAiBaseUrlChange,
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = aiModel,
                onValueChange = onAiModelChange,
                label = { Text("模型名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Spacer(Modifier.height(8.dp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleReveal() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = revealSecrets, onCheckedChange = { onToggleReveal() })
        Spacer(Modifier.width(8.dp))
        Text(
            text = "显示我输入的 Token / API Key",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(4.dp))
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
