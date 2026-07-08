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
import androidx.compose.material3.LinearProgressIndicator
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
import com.acgcompass.data.taste.TagClassifyProgress
import com.acgcompass.data.taste.TagDimensionSummary
import com.acgcompass.data.taste.TasteRefreshProgress
import com.acgcompass.domain.model.TagBucket
import com.acgcompass.domain.model.TasteProfile
import com.acgcompass.domain.taste.AdvancedTasteProfile
import com.acgcompass.domain.taste.TasteCategory
import com.acgcompass.domain.taste.TopicCombo
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
    val refreshProgress by viewModel.refreshProgress.collectAsStateWithLifecycle()
    val classifying by viewModel.classifying.collectAsStateWithLifecycle()
    val tagClassifyProgress by viewModel.tagClassifyProgress.collectAsStateWithLifecycle()
    val advancedProfile by viewModel.advancedProfile.collectAsStateWithLifecycle()
    val dimensionSummary by viewModel.dimensionSummary.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }
    // B：进入画像页自动联网分析（内部按 6h 节流判断是否需要）。
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.onScreenOpened() }
    TasteProfileScreen(
        state = state,
        onBack = onBack,
        importing = importing,
        refreshProgress = refreshProgress,
        // R47：CTA 已配置 Bangumi 则真正导入并刷新画像；未配置则跳转设置登录。
        onImportData = { viewModel.onImportFromBangumi(onNotConfigured = onOpenSettings) },
        // A4：手动重新分析（重算统计画像 + 联网补全 12 维特征，无需重新同步）。
        onRefreshAnalysis = { viewModel.onRefreshAnalysis() },
        classifying = classifying,
        tagClassifyProgress = tagClassifyProgress,
        // A5①：12 维引擎画像，供画像页展示更丰富的维度 / 组合 / 概况。
        advancedProfile = advancedProfile,
        // RC.20.2e：AI 升维效果摘要（把多少兜底为「题材」的标签细化到了哪些精确维度）。
        dimensionSummary = dimensionSummary,
        // N3：手动 AI 标签分维分类（细化本地兜底为题材的未知标签）。
        onClassifyTags = { viewModel.onClassifyTags() },
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
    refreshProgress: TasteRefreshProgress? = null,
    classifying: Boolean = false,
    tagClassifyProgress: TagClassifyProgress? = null,
    advancedProfile: AdvancedTasteProfile? = null,
    dimensionSummary: TagDimensionSummary? = null,
    onImportData: () -> Unit = {},
    onRefreshAnalysis: () -> Unit = {},
    onClassifyTags: () -> Unit = {},
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
                // A2/A4：把「重新分析」「AI 分维分类」两个操作理清进同一张「分析与优化」卡片；按钮只保留短标签、
                // 长说明下移为小字（不再把括号说明夹进按钮里），进度条紧跟对应操作下方（A3）。
                SectionCard("分析与优化") {
                    Text(
                        "上次分析：${formatLastUpdated(profile.generatedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.material3.OutlinedButton(
                        onClick = onRefreshAnalysis,
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (importing) "正在重新分析…" else "重新分析画像")
                    }
                    Text(
                        "重算口味画像并联网补全 12 维特征（评分 / 标签变动后用）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // A3：联网分析进度（自动 / 手动进行中显示，紧跟按钮下方）。
                    refreshProgress?.let { RefreshProgressContent(it) }
                    androidx.compose.material3.OutlinedButton(
                        onClick = onClassifyTags,
                        enabled = !classifying && !importing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (classifying) "正在分维分类…" else "AI 标签分维分类")
                    }
                    Text(
                        "把兜底为题材的未知社区标签交 AI 归入更精确维度并缓存，提升画像 / 评分",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // A3：分维分类进度（紧跟按钮下方）。
                    tagClassifyProgress?.let { TagClassifyProgressContent(it) }
                }
                // RC.20.2e：AI 升维效果——展示分维分类把多少笼统「题材」标签细化到了哪些精确维度。
                dimensionSummary?.takeIf { !it.isEmpty }?.let { TagDimensionUpgradeSection(it) }
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

                // A5①：12 维引擎画像——按维度拆解偏好 + 偏爱题材组合（仅在画像可用时展示）。
                advancedProfile?.takeIf { it.isUsable }?.let { adv ->
                    TasteDimensionsSection(adv)
                    TasteCombosSection(adv.combos)
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

                // A5①：画像概况——真实样本量 + 各维度数据覆盖率（透明度 / 可信度）。
                advancedProfile?.takeIf { it.isUsable }?.let { adv ->
                    ProfileOverviewSection(adv)
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

/** A5①：口味维度——把偏好按 12 维（题材 / 角色类型 / 声优 / 来源…）拆解，每维展示代表标签。 */
@Composable
private fun TasteDimensionsSection(profile: AdvancedTasteProfile) {
    val dims = DIMENSION_DISPLAY_ORDER.mapNotNull { cat ->
        val pref = profile.categories[cat] ?: return@mapNotNull null
        val likes = pref.positive.entries
            .sortedByDescending { it.value }
            .take(6)
            .map { it.key }
        if (likes.isEmpty()) null else cat to likes
    }
    if (dims.isEmpty()) return
    SectionCard("口味维度") {
        Text(
            "按不同维度拆解你的偏好：标签来自你高分作品的社区标签，越靠前越偏爱。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        dims.forEach { (cat, likes) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(cat.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FlowRowChips(likes)
            }
        }
    }
}

/** A5①：偏爱的题材组合——从高分样本挖掘的题材 / 情节组合，按强度取前若干。 */
@Composable
private fun TasteCombosSection(combos: List<TopicCombo>) {
    val top = combos.sortedByDescending { it.strength }.take(8)
    if (top.isEmpty()) return
    SectionCard("偏爱的题材组合") {
        Text(
            "从你的高分作品里挖掘出的题材 / 情节组合，越靠前越常见。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRowChips(top.map { it.tags.joinToString(" + ") })
    }
}

/**
 * RC.20.2e：AI「升维」效果——本地规则把大量未知社区标签统统兜底成笼统「题材」；这里展示 AI 分维分类
 * 已把多少这类标签细化到了哪些**更精确**的维度（情节 / 角色类型 / 来源 / 年代 / 社区梗…），让用户看到
 * 「AI 标签分维分类」按钮的实际收益，而非只有一次性 Toast。噪声维度另行说明（已从画像主分剔除）。
 */
@Composable
private fun TagDimensionUpgradeSection(summary: TagDimensionSummary) {
    val refined = summary.buckets.filter { it.category != TasteCategory.NOISE }
    val noiseCount = summary.buckets.firstOrNull { it.category == TasteCategory.NOISE }?.count ?: 0
    val refinedTotal = refined.sumOf { it.count }
    SectionCard("AI 升维效果") {
        Text(
            "原本被笼统归为「题材」的社区标签，经 AI 分维分类已细化到更精确的维度，" +
                "让画像与评分更懂你的偏好。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (refinedTotal > 0) {
            Text(
                "已升维 $refinedTotal 个标签",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRowChips(refined.map { "${it.category.label} × ${it.count}" })
        }
        if (noiseCount > 0) {
            Text(
                "另有 $noiseCount 个被识别为噪声（如泄漏标题 / 联想梗），已从画像主分中剔除。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A5①：画像概况——真实样本量 + 用户均分 + 各维度数据覆盖率（透明度 / 可信度）。 */
@Composable
private fun ProfileOverviewSection(profile: AdvancedTasteProfile) {
    SectionCard("画像概况") {
        StatLine("参与画像的评分样本", "${profile.sampleCount} 部")
        if (profile.userAvgRating > 0.0) {
            StatLine("你的平均分", "${oneDecimal(profile.userAvgRating.toFloat())} 分")
        }
        Text(
            "各维度数据覆盖（越高说明该维度线索越充分，匹配越可信）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatLine("题材 / 标签", coveragePercent(profile.coverage.tag))
        StatLine("制作阵容", coveragePercent(profile.coverage.staff))
        StatLine("评价语义", coveragePercent(profile.coverage.comment))
        StatLine("年代", coveragePercent(profile.coverage.time))
    }
}

/** 覆盖率 [0,1] → 百分比文案；≤0 显示「暂无」。 */
private fun coveragePercent(v: Double): String =
    if (v <= 0.0) "暂无" else "${(v * 100).roundToInt()}%"

/** A5①：口味维度展示顺序（跳过 COMBO 单列 / COMMUNITY 技术先验 / NOISE 噪声）。 */
private val DIMENSION_DISPLAY_ORDER: List<TasteCategory> = listOf(
    TasteCategory.TOPIC,
    TasteCategory.DEVICE,
    TasteCategory.XP,
    TasteCategory.CHARACTER,
    TasteCategory.STAFF,
    TasteCategory.CV,
    TasteCategory.SOURCE,
    TasteCategory.TIME,
    TasteCategory.MEME,
    TasteCategory.COMMENT,
)

/** B/A3：联网分析进度内容（内联于「分析与优化」卡片）——补齐特征阶段确定进度条 + “x/N”；构建阶段走不确定条。 */
@Composable
private fun RefreshProgressContent(progress: TasteRefreshProgress) {
    val label = when (progress.phase) {
        TasteRefreshProgress.Phase.FETCHING_RATED -> "正在补齐已评分作品特征"
        TasteRefreshProgress.Phase.FETCHING_POOL -> "正在补齐候选池特征（提升未评分作品打分准确度）"
        TasteRefreshProgress.Phase.BUILDING -> "正在构建口味画像"
    }
    val suffix = if (progress.isDeterminate) "（${progress.current}/${progress.total}）" else ""
    Text("$label$suffix", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    if (progress.isDeterminate) {
        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/** N3/A3：AI 标签分维分类进度内容（内联于「分析与优化」卡片）——确定进度条 + “done/total”。 */
@Composable
private fun TagClassifyProgressContent(progress: TagClassifyProgress) {
    val total = progress.total.coerceAtLeast(1)
    Text(
        "正在用 AI 分维分类标签（${progress.done}/${progress.total}）",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
    )
    LinearProgressIndicator(
        progress = { progress.done.toFloat() / total },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** B：将画像生成时间（epoch 毫秒）格式化为本地时间字符串；≤0 视为尚未分析。 */
private fun formatLastUpdated(millis: Long): String {
    if (millis <= 0L) return "尚未分析"
    val zoned = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
    return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(zoned)
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
