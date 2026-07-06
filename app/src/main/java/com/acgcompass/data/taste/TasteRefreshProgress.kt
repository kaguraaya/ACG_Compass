package com.acgcompass.data.taste

/**
 * 口味画像「联网分析」进度（B：自动 / 手动后台分析 + 进度条）。
 *
 * 由 [TasteEngine.refreshFull] 在联网补齐 work_features 各阶段更新，供画像页展示确定进度条。
 * `null`（见 [TasteEngine.observeRefreshProgress]）表示当前无分析进行中。
 *
 * - [Phase.FETCHING_RATED]：补齐「已评分作品」特征（画像样本）。
 * - [Phase.FETCHING_POOL]：补齐「未评分候选池」特征（G Step2 冷启动校准池）。
 * - [Phase.BUILDING]：本地构建 12 维画像（无网络，通常极快，进度不确定）。
 */
data class TasteRefreshProgress(
    val phase: Phase,
    val current: Int = 0,
    val total: Int = 0,
) {
    enum class Phase { FETCHING_RATED, FETCHING_POOL, BUILDING }

    /** 已完成占比 [0,1]；[total]==0（构建阶段）时为 0，配合 [isDeterminate] 走不确定进度条。 */
    val fraction: Float get() = if (total > 0) (current.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

    /** 是否有明确总量（可显示确定进度条）；构建阶段无总量走不确定条。 */
    val isDeterminate: Boolean get() = total > 0
}
