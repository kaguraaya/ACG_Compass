package com.acgcompass.feature.recommender

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.core.designsystem.WorkCard
import com.acgcompass.core.designsystem.WorkCardUiModel
import com.acgcompass.core.ui.StateScaffold
import com.acgcompass.core.ui.UiState

/**
 * 推荐器路由入口（RC.11 / Requirements 13.1–13.4, 13.8）。连接 [RecommenderViewModel] 并把
 * 状态与回调下发给无状态的 [RecommenderScreen]。
 *
 * @param onOpenWork 点击某推荐卡片时打开作品详情（导航由上层注入）。
 */
@Composable
fun RecommenderRoute(
    onOpenWork: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecommenderViewModel = hiltViewModel(),
) {
    val input by viewModel.input.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()

    RecommenderScreen(
        input = input,
        result = result,
        onSelectTime = viewModel::onSelectTime,
        onToggleMood = viewModel::onToggleMood,
        onToggleAcceptance = viewModel::onToggleAcceptance,
        onToggleIndecision = viewModel::onToggleIndecisionMode,
        onToggleFinalsProtection = viewModel::onToggleFinalsProtection,
        onToggleLateNight = viewModel::onToggleLateNight,
        onSelectCandidatePool = viewModel::onSelectCandidatePool,
        onSubmit = viewModel::onSubmit,
        onOpenWork = onOpenWork,
        modifier = modifier,
    )
}

/**
 * 无状态推荐器界面（Material 3）。上半部为三组选择（时间 / 心情 / 接受程度）与提交按钮；
 * 下半部为三推荐（稳妥 / 赌一把 / 神经病）结果区，由 [StateScaffold] 统一渲染七态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommenderScreen(
    input: RecommenderInput,
    result: UiState<List<RecommendationUiModel>>,
    onSelectTime: (TimeBudget) -> Unit,
    onToggleMood: (MoodOption) -> Unit,
    onToggleAcceptance: (AcceptanceOption) -> Unit,
    onToggleIndecision: () -> Unit,
    onToggleFinalsProtection: () -> Unit,
    onToggleLateNight: () -> Unit,
    onSelectCandidatePool: (CandidatePool) -> Unit = {},
    onSubmit: () -> Unit,
    onOpenWork: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("今晚看什么") },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                // F1：标题栏下方额外 8dp 呼吸（共约 24dp），底部额外留白避免末项贴边。
                .padding(top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // I9：候选池选择——待补池 / 全部作品。
            Text("候选池", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CandidatePool.entries.forEach { pool ->
                    androidx.compose.material3.FilterChip(
                        selected = input.candidatePool == pool,
                        onClick = { onSelectCandidatePool(pool) },
                        label = { Text(pool.label()) },
                    )
                }
            }

            InputSection(
                input = input,
                onSelectTime = onSelectTime,
                onToggleMood = onToggleMood,
                onToggleAcceptance = onToggleAcceptance,
            )

            Button(
                onClick = onSubmit,
                enabled = input.canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (input.canSubmit) "生成今晚推荐" else "先选择可用时间")
            }

            // 模式开关（RC.11.05/06/07）：不准纠结 / 期末周保护 / 深夜提醒。
            ModeSwitches(
                input = input,
                onToggleIndecision = onToggleIndecision,
                onToggleFinalsProtection = onToggleFinalsProtection,
                onToggleLateNight = onToggleLateNight,
            )

            // 深夜提醒（RC.11.07）：温柔提醒，不强行劝睡。
            if (input.lateNightMode) {
                Text(
                    text = "夜深了，看一集就好，注意休息～",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            ResultSection(
                result = result,
                onSubmit = onSubmit,
                onOpenWork = onOpenWork,
            )
        }
    }
}

/** 模式开关区（RC.11.05/06/07）：不准纠结 / 期末周保护 / 深夜提醒。 */
@Composable
private fun ModeSwitches(
    input: RecommenderInput,
    onToggleIndecision: () -> Unit,
    onToggleFinalsProtection: () -> Unit,
    onToggleLateNight: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("特别模式", style = MaterialTheme.typography.titleMedium)
        ModeSwitchRow(
            label = "不准纠结（只给一个推荐）",
            checked = input.indecisionMode,
            onToggle = onToggleIndecision,
        )
        ModeSwitchRow(
            label = "期末周保护（过滤长篇 / 致郁 / 高上头 / 未完结）",
            checked = input.finalsProtectionMode,
            onToggle = onToggleFinalsProtection,
        )
        ModeSwitchRow(
            label = "深夜提醒（温柔提醒早点休息）",
            checked = input.lateNightMode,
            onToggle = onToggleLateNight,
        )
    }
}

/** 单行模式开关：标签 + Switch。 */
@Composable
private fun ModeSwitchRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

/** 三组选择区（RC.11.01/02/03）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InputSection(
    input: RecommenderInput,
    onSelectTime: (TimeBudget) -> Unit,
    onToggleMood: (MoodOption) -> Unit,
    onToggleAcceptance: (AcceptanceOption) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 时间（单选，RC.11.01）。
        Text("我有多少时间", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeBudget.entries.forEach { option ->
                FilterChip(
                    selected = input.time == option,
                    onClick = { onSelectTime(option) },
                    label = { Text(option.label()) },
                )
            }
        }

        // 心情（多选，RC.11.02）。
        Text("现在的心情", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MoodOption.entries.forEach { option ->
                FilterChip(
                    selected = option in input.moods,
                    onClick = { onToggleMood(option) },
                    label = { Text(option.label()) },
                )
            }
        }

        // 接受程度（多选，RC.11.03）。
        Text("接受程度", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AcceptanceOption.entries.forEach { option ->
                FilterChip(
                    selected = option in input.acceptances,
                    onClick = { onToggleAcceptance(option) },
                    label = { Text(option.label()) },
                )
            }
        }
    }
}

/** 三推荐结果区（RC.11.04）：由 [StateScaffold] 统一渲染加载 / 空 / 错误 / 成功。 */
@Composable
private fun ResultSection(
    result: UiState<List<RecommendationUiModel>>,
    onSubmit: () -> Unit,
    onOpenWork: (String) -> Unit,
) {
    StateScaffold(
        state = result,
        modifier = Modifier.fillMaxWidth(),
        onRetry = onSubmit,
        // 空态 CTA（选择条件 / 调整条件）仅作引导，点击重新提交一次。
        onCta = { onSubmit() },
    ) { recommendations ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            recommendations.forEach { rec ->
                RecommendationCard(rec = rec, onOpenWork = onOpenWork)
            }
        }
    }
}

/** 单条推荐：种类标题 + 作品卡片 + 理由（RC.11.04）。 */
@Composable
private fun RecommendationCard(
    rec: RecommendationUiModel,
    onOpenWork: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = rec.kind.label(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            WorkCard(
                model = rec.card,
                onClick = { onOpenWork(rec.workId) },
            )
            Text(
                text = rec.reason,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// region Preview

@Preview(showBackground = true)
@Composable
private fun RecommenderScreenInitialPreview() {
    AcgCompassTheme {
        RecommenderScreen(
            input = RecommenderInput(time = TimeBudget.TWO_THREE_HOURS, moods = setOf(MoodOption.RELAXED)),
            result = UiState.Empty(com.acgcompass.core.ui.Cta("选择条件后生成推荐", "submit")),
            onSelectTime = {},
            onToggleMood = {},
            onToggleAcceptance = {},
            onToggleIndecision = {},
            onToggleFinalsProtection = {},
            onToggleLateNight = {},
            onSubmit = {},
            onOpenWork = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecommenderScreenResultPreview() {
    val sample = listOf(
        RecommendationUiModel(
            kind = RecommendationKind.SAFE,
            workId = "w1",
            card = WorkCardUiModel(
                coverUrl = null,
                title = "孤独摇滚！",
                subtitle = "ぼっち・ざ・ろっく！ · 2022",
                type = "动画",
                ratingText = null,
                sourceTags = listOf("Bangumi"),
                backlogStatus = "吃灰 12 天",
                completionCost = "今晚可看完",
                moodRiskTags = listOf("轻松", "治愈"),
            ),
            reason = "严格贴合你的选择，几乎不会踩雷。在 5 个满足条件的待补作品中为你抽中（高优先级，已等待 12 天）。",
        ),
    )
    AcgCompassTheme {
        RecommenderScreen(
            input = RecommenderInput(time = TimeBudget.TWO_THREE_HOURS),
            result = UiState.Success(sample),
            onSelectTime = {},
            onToggleMood = {},
            onToggleAcceptance = {},
            onToggleIndecision = {},
            onToggleFinalsProtection = {},
            onToggleLateNight = {},
            onSubmit = {},
            onOpenWork = {},
        )
    }
}

// endregion
