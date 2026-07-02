package com.acgcompass.domain.taste

/**
 * 作品结构化特征仓库（最终版算法的共享底座，RC.10/RC.11）。
 *
 * 口味画像构建与今晚推荐**共用**同一份 [WorkFeature] 缓存（避免「修了榜单没修搜索」式半路径不一致）。
 * 实现以 Room `work_features` 为单一可信源；缓存缺失 / 过期时 best-effort 联网（Bangumi 条目 + 关联人物
 * + 关联角色）补齐并落库，失败则返回已有缓存或 `null`（绝不伪造，RC.01 3.7 / RC.17.4）。
 */
interface WorkFeatureRepository {

    /**
     * 取单部作品特征：优先本地缓存；[allowNetwork] 且缓存缺失 / 过期时联网补齐并落库。
     * 数字 id（Bangumi subjectId）才能联网；非数字 id 仅查缓存。无任何数据返回 `null`。
     */
    suspend fun getFeature(subjectId: String, allowNetwork: Boolean = true): WorkFeature?

    /**
     * 批量取特征：先一次性读缓存命中；对未命中者在 [networkBudget] 限额内 best-effort 联网补齐。
     * 返回 subjectId → [WorkFeature]（仅含成功获取者）。
     */
    suspend fun getFeatures(subjectIds: List<String>, networkBudget: Int = 0): Map<String, WorkFeature>

    /** 仅读本地缓存，绝不联网（批量发现页 / 离线场景用）。 */
    suspend fun getCached(subjectId: String): WorkFeature?

    /** 主动缓存某条目特征（同步链路调用，best-effort）。返回是否成功落库。 */
    suspend fun refresh(subjectId: String): Boolean
}
