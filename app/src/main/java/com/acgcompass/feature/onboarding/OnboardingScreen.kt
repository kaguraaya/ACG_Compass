package com.acgcompass.feature.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * 无状态引导界面（Material 3）——多步向导（D）。
 *
 * 将原单页长表单拆为 4 步，降低首启认知负担：
 * 1. 介绍：合规 / 隐私 / 多源等要点（[OnboardingUiState.highlights]）。
 * 2. 代理 API 同意：社区反代说明 + 个人 Token 中转同意（可不勾选，仅公共搜索经反代）。
 * 3. Bangumi 个人同步（可选）：粘贴 Access Token。
 * 4. AI 增强（可选）：OpenAI 兼容 API Key / Base URL / 模型名。
 *
 * 顶部进度点指示当前步；底部「上一步 / 下一步」，末步为主操作（[OnboardingUiState.confirmLabel]）；
 * 后两步为可选项，顶部「跳过」直接以当前草稿完成。草稿仅在内存，完成时一次性上抛加密保存（空值不写）。
 * 设计为无状态以便预览与 UI 测试：内容来自 [state]，完成通过 [onConfirm] 上抛 [OnboardingSetup]。
 */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onConfirm: (OnboardingSetup) -> Unit,
    modifier: Modifier = Modifier,
) {
    // H：快速配置（可选）草稿——仅内存，随「开始使用」一次性上抛加密保存（空值不写）。
    var consented by remember { mutableStateOf(false) }
    var bangumiToken by remember { mutableStateOf("") }
    var aiApiKey by remember { mutableStateOf("") }
    var aiBaseUrl by remember { mutableStateOf(OnboardingDefaults.AI_BASE_URL) }
    var aiModel by remember { mutableStateOf(OnboardingDefaults.AI_MODEL) }
    var revealSecrets by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf(0) }

    fun complete() = onConfirm(
        OnboardingSetup(
            consentToProxyToken = consented,
            bangumiToken = bangumiToken,
            aiApiKey = aiApiKey,
            aiBaseUrl = aiBaseUrl,
            aiModel = aiModel,
        ),
    )

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Spacer(Modifier.height(16.dp))
            // 顶部：进度点 +（可选步）跳过。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepDots(
                    current = step,
                    total = ONBOARDING_PAGE_COUNT,
                    modifier = Modifier.weight(1f),
                )
                if (step >= FIRST_OPTIONAL_PAGE) {
                    TextButton(onClick = { complete() }) { Text("跳过") }
                }
            }

            Crossfade(
                targetState = step,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    when (page) {
                        0 -> IntroPage(state)
                        1 -> ConsentPage(
                            consentPrompt = state.consentPrompt,
                            consented = consented,
                            onConsentedChange = { consented = it },
                        )
                        2 -> BangumiPage(
                            bangumiToken = bangumiToken,
                            onBangumiTokenChange = { bangumiToken = it },
                            revealSecrets = revealSecrets,
                            onToggleReveal = { revealSecrets = !revealSecrets },
                        )
                        else -> AiPage(
                            aiApiKey = aiApiKey,
                            onAiApiKeyChange = { aiApiKey = it },
                            aiBaseUrl = aiBaseUrl,
                            onAiBaseUrlChange = { aiBaseUrl = it },
                            aiModel = aiModel,
                            onAiModelChange = { aiModel = it },
                            revealSecrets = revealSecrets,
                            onToggleReveal = { revealSecrets = !revealSecrets },
                        )
                    }
                }
            }

            // 底部导航：上一步 | 下一步 / 开始使用。
            val isLast = step == ONBOARDING_PAGE_COUNT - 1
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    OutlinedButton(
                        onClick = { if (step > 0) step-- },
                        modifier = Modifier.weight(1f),
                    ) { Text("上一步") }
                }
                Button(
                    onClick = { if (isLast) complete() else step++ },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isLast) state.confirmLabel else "下一步")
                }
            }
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

/** 顶部步骤进度指示：当前步用主色加长条，其余为浅色圆点。 */
@Composable
private fun StepDots(
    current: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.semantics { contentDescription = "第 ${current + 1} / $total 步" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { index ->
            val active = index == current
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(if (active) 24.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
            )
        }
    }
}

/** 第 1 步：介绍——欢迎标题 + 合规 / 隐私 / 多源等要点卡片。 */
@Composable
private fun IntroPage(state: OnboardingUiState) {
    Spacer(Modifier.height(16.dp))
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
    Spacer(Modifier.height(24.dp))
    state.highlights.forEachIndexed { index, highlight ->
        HighlightCard(index = index + 1, highlight = highlight)
        Spacer(Modifier.height(12.dp))
    }
    Spacer(Modifier.height(8.dp))
}

/** 第 2 步：代理 API 同意——社区反代说明 + 个人 Token 中转同意（可不勾选）。 */
@Composable
private fun ConsentPage(
    consentPrompt: String,
    consented: Boolean,
    onConsentedChange: (Boolean) -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = "数据源与网络",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "默认使用社区反代地址访问 Bangumi——官方地址在部分网络需特殊环境，反代通常可直连。" +
            "可随时在「设置 → 数据源」切回官方或更换地址。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onConsentedChange(!consented) }
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(checked = consented, onCheckedChange = onConsentedChange)
            Spacer(Modifier.width(8.dp))
            Text(
                text = consentPrompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = "提示：不勾选也可正常使用——仅公共搜索经反代，不会发送你的个人 Token。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 第 3 步（可选）：Bangumi 个人同步——粘贴 Access Token。 */
@Composable
private fun BangumiPage(
    bangumiToken: String,
    onBangumiTokenChange: (String) -> Unit,
    revealSecrets: Boolean,
    onToggleReveal: () -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = "Bangumi 个人同步（可选）",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "粘贴 Bangumi Access Token 即可同步你的收藏 / 评分（在 ${OnboardingDefaults.BANGUMI_TOKEN_HELP_URL} 生成；" +
            "也可稍后在设置里用 Bangumi 登录）。可留空跳过。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = bangumiToken,
        onValueChange = onBangumiTokenChange,
        label = { Text("Access Token（可选）") },
        singleLine = true,
        visualTransformation = if (revealSecrets) VisualTransformation.None else PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    RevealSecretsToggle(revealSecrets = revealSecrets, onToggleReveal = onToggleReveal)
    Spacer(Modifier.height(8.dp))
    Text(
        text = "密钥仅加密保存在本机，不会上传。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 第 4 步（可选）：AI 增强——OpenAI 兼容 API Key / Base URL / 模型名。 */
@Composable
private fun AiPage(
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
    Spacer(Modifier.height(16.dp))
    Text(
        text = "AI 增强（可选）",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "填入 OpenAI 兼容服务的 API Key 可启用口味匹配 / 无剧透雷达（推荐 DeepSeek）；" +
            "未填则自动回退本地规则。AI 调用会消耗你所配置服务的额度。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
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
    RevealSecretsToggle(revealSecrets = revealSecrets, onToggleReveal = onToggleReveal)
}

/** 显示 / 隐藏已输入的 Token / API Key（Bangumi / AI 两步共用）。 */
@Composable
private fun RevealSecretsToggle(
    revealSecrets: Boolean,
    onToggleReveal: () -> Unit,
) {
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
}

/** 引导向导总步数：介绍 / 同意 / Bangumi / AI。 */
private const val ONBOARDING_PAGE_COUNT = 4

/** 第 3 步（索引 2）起为可选配置项，顶部提供「跳过」直接完成。 */
private const val FIRST_OPTIONAL_PAGE = 2

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
