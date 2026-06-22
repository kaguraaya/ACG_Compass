package com.acgcompass.domain.usecase

/**
 * 标签分类法（口味算法共用，纯Kotlin、无依赖）。
 *
 * **多维度模型（B-3）**：把每个社区标签显式归入一个语义**维度**（[TagDimension]），权重由维度决定：
 * - **元数据 META**：年份/季度（2024、2024年10月、2024春）、媒介格式（TV/OVA/剧场版/泡面番）、
 *   改编来源（漫画改/原创）、放送状态（连载中/完结）、关联（续作/总集篇/番外）、地区（日本/国产/欧美）等。
 *   每部作品都带、无区分度，计入口味会让「人人都有的标签」霸榜并污染高/低分倾向（最弱权重，且在画像构建时被 [isMeta] 过滤）。
 * - **厂商 STUDIO**：制作公司（京都动画/动画工房 等）。用户**可能**有偏好，故不剔除，仅适度弱化。
 * - **受众 DEMOGRAPHIC**：广义向别（少年向/少女向/青年向/女性向 等）——较弱的口味判别信号，介于题材与厂商之间。
 * - **题材/内容 CONTENT**：其余（类型/情绪/设定/元素）视为题材标签，保持全权重。
 *
 * 池完整性（B-3）：[NOISE_TAGS]/[STUDIO_TAGS]/[DEMOGRAPHIC_TAGS] 与日期正则尽量覆盖常见变体，减少错分。
 *
 * 用途（统一口径，避免两处分类规则漂移）：
 * - `BangumiSyncManager` 构建口味画像时用 [isMeta] 过滤掉元数据标签；
 * - `PersonalTasteScorer` 个性化评分时用 [weightFactor]（= [dimensionOf] 的维度因子）对各维度降权。
 *
 * 规则源自此前 `BangumiSyncManager.isNoiseTag`（P0-2），抽出复用并扩展为多维度（B-3）。
 */
object TasteTagTaxonomy {

    /** 题材标签权重因子（全权重）。 */
    const val CONTENT_FACTOR: Float = 1f

    /** 受众/向别标签权重因子（B-3 多维度）：广义受众是较弱的口味判别信号，低于具体题材。 */
    const val DEMOGRAPHIC_FACTOR: Float = 0.7f

    /** 厂商标签权重因子（弱化但保留：用户可能有制作公司偏好）。 */
    const val STUDIO_FACTOR: Float = 0.5f

    /** 元数据标签权重因子（强弱化：年份/季度/格式/来源/地区，几乎无区分度）。 */
    const val META_FACTOR: Float = 0.2f

    /**
     * 标签语义维度（B-3 多维度）：每个标签归入唯一维度，权重由维度的 [factor] 决定。
     * 该枚举是「多维度」的显式建模，便于后续按维度细化权重或做维度多样性分析。
     */
    enum class TagDimension(val factor: Float) {
        META(META_FACTOR),
        STUDIO(STUDIO_FACTOR),
        DEMOGRAPHIC(DEMOGRAPHIC_FACTOR),
        CONTENT(CONTENT_FACTOR),
    }

    /**
     * 判定标签所属维度（B-3）：优先级 元数据 > 厂商 > 受众 > 题材（兽底）。
     * 注：百合/BL/后宫/乙女 等归为题材（强信号），不计入受众维度。
     */
    fun dimensionOf(tag: String): TagDimension = when {
        isMeta(tag) -> TagDimension.META
        isStudio(tag) -> TagDimension.STUDIO
        isDemographic(tag) -> TagDimension.DEMOGRAPHIC
        else -> TagDimension.CONTENT
    }

    /**
     * 是否为「非内容」元数据噪声标签（年份/季度/媒介格式/改编来源/地区）。
     * 厂商标签**不**计入此判定（厂商按 [weightFactor] 弱化而非剔除）。
     */
    fun isMeta(tag: String): Boolean {
        val t = tag.trim()
        if (t.isEmpty()) return true
        if (NOISE_DATE_REGEX.matches(t)) return true
        return t.lowercase() in NOISE_TAGS
    }

    /** 是否为厂商/制作公司标签（小写精确匹配已知集合，低误判）。 */
    fun isStudio(tag: String): Boolean = tag.trim().lowercase() in STUDIO_TAGS

    /** 是否为广义受众/向别标签（B-3：少年向/少女向/青年向/女性向 等，小写精确匹配）。 */
    fun isDemographic(tag: String): Boolean = tag.trim().lowercase() in DEMOGRAPHIC_TAGS

    /**
     * 标签在个性化评分中的权重因子（= 其所属 [TagDimension] 的因子，B-3 多维度）：
     * 元数据 [META_FACTOR] < 厂商 [STUDIO_FACTOR] < 受众 [DEMOGRAPHIC_FACTOR] < 题材 [CONTENT_FACTOR]。
     * 据此对「年份/厂商等维度弱化、题材标签加成」（用户诉求）。
     */
    fun weightFactor(tag: String): Float = dimensionOf(tag).factor

    /**
     * 年份/季度型噪声标签正则（池完整性 B-3，尽量覆盖常见变体）：
     * 2024、2024年、2024年10月、2024-10、10月、2024春/2024年春季、2024Q1、10月新番 等。
     */
    private val NOISE_DATE_REGEX = Regex(
        "^(\\d{4}|\\d{4}年|\\d{4}年\\d{1,2}月|\\d{4}[-./]\\d{1,2}|\\d{1,2}月|" +
            "\\d{4}年?[春夏秋冬]季?|\\d{1,2}月新番|\\d{4}[Qq][1-4])$",
    )

    /**
     * 媒介格式/改编来源/放送状态/关联/地区等非内容噪声标签（小写比较；池完整性 B-3 已扩充）。
     */
    private val NOISE_TAGS: Set<String> = setOf(
        // 媒介格式
        "tv", "ova", "oad", "ona", "web", "sp", "pv", "cm", "mv", "op", "ed",
        "剧场版", "剧场", "电影", "短片", "特别篇", "动画", "日本动画", "tv动画", "动画化",
        "网络动画", "泡面番", "广播剧", "drama cd", "动画电影", "剧场版动画", "电视动画",
        "原创动画", "ova动画", "tv版", "特别企划",
        // 改编来源
        "漫画改", "轻小说改", "小说改", "游戏改", "gal改", "galgame改", "eroge改", "原创", "漫改",
        "改编", "18禁游戏改", "手游改", "同名漫画改", "同名小说改", "同名游戏改",
        // 放送状态 / 关联
        "完结", "已完结", "连载", "连载中", "未完结", "放送中",
        "续作", "续篇", "前作", "新作", "系列作", "系列作品", "总集篇", "番外", "番外篇",
        "前篇", "后篇", "上篇", "下篇", "完结篇", "重制", "重置", "复刻", "重启", "衍生",
        // 地区
        "日本", "国产", "中国", "美国", "韩国", "欧美", "欧洲", "台湾", "香港", "日本动画",
    )

    /**
     * 已知厂商/制作公司标签（小写精确匹配；池完整性 B-3 已扩充常见厂牌与别名）。仅作弱化，不求全；
     * 未收录的厂商会被当作题材（全权重），影响有限。
     */
    private val STUDIO_TAGS: Set<String> = setOf(
        "京都动画", "京阿尼", "京产", "动画工房", "doga kobo", "ufotable", "飞碟社",
        "mappa", "a-1 pictures", "a-1", "a1", "骨头社", "bones", "trigger", "扳机社", "扳机",
        "p.a.works", "pa社", "wit studio", "wit", "j.c.staff", "jc", "shaft", "马虎社",
        "david production", "云雀", "lerche", "feel", "silver link", "白狐", "white fox",
        "studio deen", "deen", "sunrise", "日升", "madhouse", "疯房子", "production i.g", "ig",
        "cloverworks", "东映动画", "东映", "toei", "龙之子", "tatsunoko", "gainax", "khara",
        "science saru", "汤浅", "olm", "studio pierrot", "pierrot", "小丑社", "gonzo",
        "8bit", "八位社", "passione", "project no.9", "satelight", "diomedea", "studio bind", "bind",
    )

    /**
     * 广义受众/向别标签（B-3 多维度，小写精确匹配）。注：百合/BL/后宫/乙女 等是题材（强信号），
     * 不列入此；仅「×向」类广义受众标签计入。
     */
    private val DEMOGRAPHIC_TAGS: Set<String> = setOf(
        "子供向", "少年向", "少女向", "青年向", "女性向", "男性向", "全年龄", "成人向",
        "亲子向", "幼儿向", "女性向け", "男性向け",
    )
}
