package com.acgcompass.feature.taste

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.core.ui.AcgScreenScaffold
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.core.ui.StateScaffold
import com.acgcompass.core.ui.UiState
import com.acgcompass.domain.model.TagBucket
import com.acgcompass.domain.model.TasteProfile
import kotlin.math.roundToInt

/** 口味画像路由入口（RC.10）。 */
@Composable
fun TasteProfileRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    viewModel: TasteProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }
    TasteProfileScreen(
        state = state,
        onBack = onBack,
        importing = importing,
        // R47：CTA 已配置 Bangumi 则真正导入并刷新画像；未配置则跳转设置登录。
        onImportData = { viewModel.onImportFromBangumi(onNotConfigured = onOpenSettings) },
        modifier = modifier,
    )
}

/** 无状态口味画像界面：评分习惯 / 称号 / 高低分标签 / 口味黑洞；低样本用保守措辞（RC.10.07）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasteProfileScreen(
    state: UiState<TasteProfile>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    importing: Boolean = false,
    onImportData: () -> Unit = {},
) {
    AcgScreenScaffold(
        title = "口味画像",
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
    ) { innerPadding ->
        StateScaffold(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
            // R30：空态 CTA 不再是死按钮——前往设置（Bangumi/同步）导入口味数据。
            onCta = { onImportData() },
        ) { profile ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // F1 inset 模型：统一 AcgScreenScaffold + ScreenContentPadding，避免首个卡片被标题栏遮挡。
                    .padding(
                        start = ScreenContentPadding.Horizontal,
                        end = ScreenContentPadding.Horizontal,
                        top = ScreenContentPadding.Top,
                        bottom = ScreenContentPadding.Bottom,
                    ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val lowSample = profile.confidence < 0.3f
                if (lowSample) {
                    Text(
                        "样本较少，以下为初步倾向，仅供参考（可能 / 倾向于）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (profile.titles.isNotEmpty()) {
                    SectionCard("口味称号") {
                        FlowRowChips(profile.titles)
                    }
                }

                SectionCard("评分习惯") {
                    val hedge = if (lowSample) "可能" else ""
                    StatLine("平均分", if (profile.avgScore > 0f) "$hedge ${oneDecimal(profile.avgScore)} 分".trim() else "暂无数据")
                    StatLine("评分严格度", percentText(profile.strictness, lowSample))
                    StatLine("高分稀有度", percentText(profile.highScoreRarity, lowSample))
                    StatLine("常见分段", profile.commonScoreBand ?: "暂无数据")
                }

                val highTags = profile.tagStats.filter { it.bucket == TagBucket.HIGH_SCORE }.map { it.tagName }
                val lowTags = profile.tagStats.filter { it.bucket == TagBucket.LOW_SCORE }.map { it.tagName }
                SectionCard("高分倾向标签") {
                    if (highTags.isEmpty()) Text("暂无数据") else FlowRowChips(highTags)
                }
                SectionCard("低分倾向标签") {
                    if (lowTags.isEmpty()) {
                        Text("低分样本不足（评分较低的作品太少，暂无法统计避雷标签）")
                    } else {
                        FlowRowChips(lowTags)
                    }
                }
                SectionCard("口味黑洞（常被搁置 / 抛弃）") {
                    if (profile.blackHole.isEmpty()) Text("暂无数据") else FlowRowChips(profile.blackHole)
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Text(
        "$label：$value",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(items: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { AssistChip(onClick = {}, label = { Text(cleanTagLabel(it)) }) }
    }
}

/** 标签清洗（R6 续修）：下划线 / 连字符转空格、折叠多余空白，使 BanGDream / No_Game_No_Life 等更可读。 */
private fun cleanTagLabel(raw: String): String =
    raw.replace('_', ' ').replace('-', ' ').trim().replace(Regex("\\s+"), " ")

private fun oneDecimal(v: Float): String = ((v * 10).roundToInt() / 10.0).toString()

private fun percentText(v: Float, lowSample: Boolean): String {
    if (v <= 0f) return "暂无数据"
    val pct = (v * 100).roundToInt()
    val prefix = if (lowSample) "倾向于 " else ""
    return "$prefix$pct%"
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun TasteProfileScreenPreview() {
    AcgCompassTheme {
        TasteProfileScreen(
            state = UiState.Success(
                TasteProfile(
                    id = "t1",
                    strictness = 0.4f,
                    avgScore = 7.6f,
                    highScoreRarity = 0.7f,
                    commonScoreBand = "7-8 分",
                    titles = listOf("严格评分官", "「治愈」头号粉丝"),
                    confidence = 0.6f,
                    generatedAt = 0L,
                    blackHole = listOf("长篇", "致郁"),
                ),
            ),
            onBack = {},
        )
    }
}
