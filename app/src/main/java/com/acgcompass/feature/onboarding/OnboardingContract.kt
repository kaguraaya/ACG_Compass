package com.acgcompass.feature.onboarding

import androidx.annotation.VisibleForTesting

/**
 * 首次启动引导（Onboarding）的 UI 契约（RC.03.01 / Requirements 5.1）。
 *
 * 引导只承载**纯静态、可安全展示**的说明文案——绝不涉及任何凭据 / key（RC.00 1.2）。
 * 三条核心说明：
 * 1. 应用不提供在线播放 / 下载 / 盗版资源（合规边界）。
 * 2. 数据默认本地保存（隐私优先，RC.00 1.1）。
 * 3. 可稍后在「我的 → 设置」按需配置各数据源 API key（RC.02）。
 */

/**
 * 单条引导说明项。
 *
 * @property title 说明标题（用户可读）。
 * @property description 说明正文。
 */
data class OnboardingHighlight(
    val title: String,
    val description: String,
)

/**
 * 引导页静态 UI 状态：标题、副标题、说明项列表与主操作按钮文案。
 *
 * 内容固定且无网络 / 凭据依赖，因此以常量形式提供，无需 [androidx.lifecycle.ViewModel] 计算。
 */
data class OnboardingUiState(
    val title: String,
    val subtitle: String,
    val highlights: List<OnboardingHighlight>,
    val confirmLabel: String,
) {
    companion object {
        /** 默认引导文案（RC.03.01）。 */
        val DEFAULT: OnboardingUiState = OnboardingUiState(
            title = "欢迎使用 ACG Compass",
            subtitle = "你的补番补游决策助手 · 本地优先 · 隐私安全",
            highlights = listOf(
                OnboardingHighlight(
                    title = "不提供播放与下载",
                    description = "本应用仅做信息聚合与决策辅助，不提供在线播放、下载或任何盗版资源。",
                ),
                OnboardingHighlight(
                    title = "数据默认本地保存",
                    description = "你的收藏、评分与画像默认仅保存在本机，不上传云端。",
                ),
                OnboardingHighlight(
                    title = "可稍后配置 API Key",
                    description = "各数据源（Bangumi / AniList / MAL 等）的 API Key 可稍后在「我的 → 设置」按需配置，凭据仅加密保存在本机。",
                ),
            ),
            confirmLabel = "开始使用",
        )
    }
}

/**
 * 根据「引导是否已展示」的持久化标志，判断是否需要展示首启引导（RC.03.01）。
 *
 * 纯函数，便于单元测试：仅当 [onboardingShown] 为 `false`（即从未展示过）时返回 `true`。
 */
@VisibleForTesting
internal fun shouldShowOnboarding(onboardingShown: Boolean): Boolean = !onboardingShown
