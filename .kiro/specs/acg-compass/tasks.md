# Implementation Plan: ACG Compass

## Overview

本计划将 ACG Compass 的设计转化为一系列可由编码代理增量执行的任务。整体顺序遵循依赖关系：

工程脚手架与 Gradle/Hilt 配置 → 核心（设计系统、UiState/Page_State、错误类型）→ 本地数据层（Room/DataStore/EncryptedSharedPreferences 凭据存储）→ 领域模型与仓库接口 → 网络核心（OkHttp/Retrofit/Apollo + 拦截器 + 降级编排）→ 各数据源客户端（Bangumi P0 先行，随后 AniList、Jikan、MAL 官方、VNDB）→ 按优先级实现各功能（设置/凭据、导航/全局状态、首页、发现/搜索/匹配、批量导入、详情、待补池、无剧透雷达 + 本地回退、口味画像、推荐器、路线图、时光机、AI 子系统、我的页/统计、备份/导出/迁移、趣味功能）→ 横切测试。

约定：
- P0（MVP）任务优先完成；P1/P2 功能排后，但其数据模型与页面结构在早期任务中预留。
- 属性测试使用 `kotest-property`，每个属性 ≥ 100 次迭代（`PropTestConfig(iterations = 100)`），标签格式：`// Feature: acg-compass, Property {n}: {property_text}`。
- 标 `*` 的子任务为可选/测试类任务（属性测试、单元测试、集成/UI/快照测试），不阻塞核心实现。
- 每个数据源接入实现前，必须先联网核验最新官方文档并写入 `DEVELOPMENT.md`（RC.01）。
- 代码、资源、测试、README、日志中不得包含任何 key/token/secret（RC.00）。

## Tasks

- [x] 1. 初始化工程脚手架与构建配置
  - 创建单 Module Android 工程（Gradle Kotlin DSL），`minSdk 26`、`targetSdk` 当前最新，启用 `exportSchema=true`
  - 配置 Kotlin、Jetpack Compose（Material 3）、Coroutines/Flow、Hilt、Coil、WorkManager、kotlinx.serialization 依赖与版本目录
  - 创建 `com.acgcompass` 包结构骨架：`app/ core/{designsystem,ui,common,network} data/{local,datastore,credential,remote,repository} domain/{model,repository,usecase,fallback} feature/*`
  - 配置 Hilt：`@HiltAndroidApp` Application、宿主单 Activity
  - 接入 `kotest` 与 `kotest-property` 测试依赖
  - _Requirements: 1.1, 5.8_

- [x] 2. 建立三份制度文档与 RC 编号体系
  - 创建仓库根目录 `REQUIREMENTS.md`：记录每个功能的 RC 编号、页面位置、用户目标、优先级、状态与验收方式
  - 创建 `DEVELOPMENT.md`：预置「已核验官方 API 文档链接 / 核验日期 / 实际字段 / 失败处理 / 数据迁移」表格骨架
  - 创建 `EXPERIENCE.md`：预置「问题现象 / 原因 / 修复方式 / 避免策略 / 相关 RC」骨架
  - 在 `DEVELOPMENT.md` 中明确 RC.01「实现各源前先核验官方文档」的强制流程说明
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

- [x] 3. 实现核心通用类型与错误体系
  - [x] 3.1 实现 `Result`/`AppError` 类型与协程调度器
    - 在 `core/common` 定义 `AppError` 封闭接口（Network/Unauthorized/RateLimited/NotFound/Server/FieldMissing/AiMalformed/Spoiler），每类含 `cause/nextStep/retryable/docUrl`
    - 定义 `DispatcherProvider` 与通用扩展、`Result` 包装工具（保证异常兜底为 `AppError.Server`）
    - _Requirements: 5.5, 5.6, 5.4_
  - [x] 3.2 编写错误信息映射属性测试
    - **Property 6: 错误信息映射完整**
    - **Validates: Requirements 5.5, 5.6**
  - [x] 3.3 实现统一 `UiState`（Page_State 七态）封闭类
    - 在 `core/ui` 定义 `UiState<T>`：Loading/Empty/Error/Unauthorized/RateLimited/NoNetwork/PartialMissing/Success
    - _Requirements: 5.7, 5.3, 5.4_

- [x] 4. 实现设计系统与状态脚手架组件
  - [x] 4.1 实现 Material 3 主题、动态取色与深色模式
    - 在 `core/designsystem` 实现主题、排版、圆角、颜色方案与深色模式适配
    - _Requirements: 5.8_
  - [x] 4.2 实现 `StateScaffold` 与 `ErrorCard` 组件
    - `StateScaffold` 渲染七态；`ErrorCard` 含「简短原因 + 下一步 + 重试 + 查看文档」
    - 实现空状态组件（说明 + 下一步操作按钮）
    - _Requirements: 5.3, 5.4, 5.5, 5.6, 5.7_
  - [x] 4.3 实现统一 `WorkCard` 与 `AiCard` 组件骨架
    - `WorkCard`：封面、标题、别名/年份、类型、评分（缺失显示「暂无数据」）、来源标签、待补状态、补完成本、风险/心情标签；封面缺失、长标题兜底
    - `AiCard`：显示「AI 生成/规则生成」、生成时间、数据来源、置信度、重新生成按钮
    - _Requirements: 5.9, 16.7_
  - [x] 4.4 编写设计系统组件 UI/快照测试
    - 覆盖深色模式、大字体、长标题、封面缺失场景
    - _Requirements: 19.8_

- [x] 5. 实现本地数据层：Room 实体、DAO 与数据库
  - [x] 5.1 定义 Room 实体与类型转换器
    - 实现 `WorkEntity`、`SourceLinkEntity`、`RatingEntity`、`BacklogItemEntity`、`TagEntity`、`WorkTagEntity`、`RecommendationCountEntity`、`ImportBatchEntity`、`ImportItemEntity`、`SnapshotEntity`、`ChangeLogEntity`、`TasteProfileEntity`、`TasteTagStatEntity`、`AiResultEntity`、`RouteNodeEntity`、`CredentialMetaEntity`
    - 凭据明文绝不入 Room；仅 `CredentialMetaEntity` 保存非敏感元数据
    - _Requirements: 1.1, 1.2, 5.7_
  - [x] 5.2 实现各实体 DAO 与数据库类（v1，exportSchema）
    - 实现 DAO（Flow 查询）、`RoomDatabase`（version=1，禁用 `fallbackToDestructiveMigration`），导出 schema json 入版本库
    - _Requirements: 1.1, 1.8_
  - [x] 5.3 实现 Room 迁移框架与升级前自动备份兜底
    - 建立显式 `Migration` 注册机制；迁移涉及升级前自动生成内部 JSON 备份；迁移失败回滚并保留原始备份
    - _Requirements: 1.8, 18.3, 18.5_
  - [x] 5.4 编写数据库迁移保行属性测试
    - **Property 2: 数据库迁移保留所有行**
    - **Validates: Requirements 1.8, 18.3, 19.2**

- [x] 6. 实现 DataStore 设置层与凭据安全存储
  - [x] 6.1 实现 Preferences DataStore 设置/开关层
    - 保存非敏感开关：是否允许 AI 分析评论、是否记录时光机快照、各源启用开关、成人内容显示开关等
    - _Requirements: 4.11, 4.12, 1.1_
  - [x] 6.2 实现 `CredentialStore`（EncryptedSharedPreferences + Keystore）
    - 基于 AES256-GCM，MasterKey 由 Keystore 保护；实现 `put/get/clear/observeStatus/exportRedacted`
    - `observeStatus` 仅暴露元数据；`exportRedacted` 输出脱敏字段（如 `sk-****…ab`）
    - _Requirements: 1.1, 1.2, 4.2, 4.12_
  - [x] 6.3 实现 `RedactingLogger` 日志脱敏器
    - 对疑似 key/token 正则脱敏，绝不打印完整值
    - _Requirements: 1.7, 19.3_
  - [x] 6.4 编写凭据隔离属性测试
    - **Property 1: 凭据隔离（绝不出现在导出与日志中）**
    - **Validates: Requirements 1.2, 1.5, 1.7, 18.1**

- [x] 7. 定义领域模型与仓库接口
  - [x] 7.1 实现领域模型与映射器
    - 实现 `Work/Titles/MediaType/Units/CompletionCost`、`RatingAggregate/RatingEntry/Consensus`、`BacklogItem/Priority`、`Tag/TagCategory`、`WorkMatch/SourceRef/SourceId`、`Snapshot/ChangeLog`、`TasteProfile`、`AiResult` 等纯 Kotlin 模型与 Entity↔Model mapper
    - _Requirements: 1.1, 5.9, 9.1_
  - [x] 7.2 定义仓库接口契约
    - 定义 `WorkRepository/BacklogRepository/RatingRepository/SnapshotRepository/TasteRepository/AiRepository/CredentialStore` 接口
    - _Requirements: 1.1, 7.2, 8.7, 8.6_

- [x] 8. 实现网络核心与拦截器链
  - [x] 8.1 实现 OkHttp/Retrofit/Apollo 工厂
    - 配置 REST（Retrofit + kotlinx.serialization）与 GraphQL（Apollo）客户端工厂；AI 走独立 OkHttp 实例
    - _Requirements: 1.1_
  - [x] 8.2 实现拦截器链：UserAgent / Auth / RateLimit / Timeout
    - `UserAgentInterceptor` 注入「ACGCompass/{version}」合规 UA；`AuthInterceptor` 从 `CredentialStore` 注入鉴权（无凭据透传）；`RateLimitInterceptor` 每源独立令牌桶（达 80% 即节流，支持双桶如 3 req/s + 60 req/min）；`TimeoutInterceptor` 10s 调用超时
    - _Requirements: 3.2, 3.4, 3.10, 3.9_
  - [x] 8.3 编写限流器属性测试
    - **Property 4: 限流不超过配置上限**
    - **Validates: Requirements 3.4, 3.10**
  - [x] 8.4 实现 HTTP 状态/异常 → `AppError` 映射器
    - 200/404/401/403/429/500、空数组、字段缺失、超时 → 确定 `AppError`，不抛未捕获异常
    - _Requirements: 19.4, 3.9_
  - [x] 8.5 编写 HTTP 状态映射属性测试
    - **Property 18: HTTP 状态到错误类型映射**
    - **Validates: Requirements 19.4, 3.9**
  - [x] 8.6 实现 `DataSourceOrchestrator` 降级编排
    - 按 `Bangumi → AniList → Jikan → MAL_Official → VNDB` 顺序回退；附 `sourceTag` 与 `matchConfidence`；字段级缺失显示「暂无数据」；全部不可用返回可重试错误卡片；不抓网页
    - _Requirements: 3.7, 3.8, 3.9, 3.11_
  - [x] 8.7 编写降级顺序确定性属性测试
    - **Property 3: 降级顺序确定性**
    - **Validates: Requirements 3.8, 3.9**

- [x] 9. 检查点 — 核心层与网络核心验证
  - 确保所有测试通过，如有疑问请询问用户。

- [x] 10. 实现 Bangumi 数据源客户端（P0）（核心完成，optional tests pending）
  - [x] 10.1 核验 Bangumi 官方文档并记录
    - 联网核验授权方式、字段、速率限制、返回结构，将链接与核验日期写入 `DEVELOPMENT.md`
    - _Requirements: 3.1, 3.2_
  - [x] 10.2 实现 Bangumi API service、DTO 与 mapper
    - 实现条目资料、中文评分/排名/标签、收藏、个人评分/短评、进度、关联作品、角色获取；接入降级编排
    - _Requirements: 3.1, 3.2, 3.7_
  - [ ] 10.3 编写 Bangumi mapper 与异常处理单元测试
    - 测试 404/401/403/429/500/空结果/字段缺失映射
    - _Requirements: 19.4, 3.9_继续

- [x] 11. 实现标题归一化与多源匹配
  - [x] 11.1 实现标题归一化与相似度匹配
    - 实现中/日/罗马音/英/别名归一化（去符号、全半角、大小写）、相似度计算、`matchConfidence ∈ [0,1]`、阈值合并与低置信标记、`overrideMatch` 持久化
    - _Requirements: 7.1, 7.2, 7.3_
  - [x] 11.2 编写标题归一化幂等属性测试
    - **Property 7: 标题归一化幂等**
    - **Validates: Requirements 7.1**
  - [x] 11.3 编写多源合并阈值与手动纠正持久性属性测试
    - **Property 8: 多源合并阈值与手动纠正持久性**
    - **Validates: Requirements 7.2, 7.3**

- [x] 12. 实现评分聚合（核心完成，optional tests pending）
  - [x] 12.1 实现 `aggregateRatings` 与社区共识
    - 每源评分独立存储后聚合；缺失源标记 `missing` 且不被填充；样本不足时 `consensus` 为 null/低置信，不伪造结论
    - _Requirements: 3.7, 9.2, 9.4_
  - [x] 12.2 编写评分聚合不伪造属性测试
    - **Property 5: 评分聚合不伪造、缺失即标记**
    - **Validates: Requirements 3.7, 9.2, 9.4**

- [x] 13. 实现 WorkRepository 与缓存（单一可信源 = Room）
  - [x] 13.1 实现 `WorkRepository`：远程拉取写入 Room、UI 从 Flow 读取
    - 实现 `observeWork/search/aggregateRatings/overrideMatch`
    - _Requirements: 1.1, 7.2, 3.8_
  - [ ] 13.2 编写 WorkRepository 集成测试（MockWebServer）
    - 最小化连通与字段解析，结构化降级链路
    - _Requirements: 3.8, 19.4_

- [x] 14. 实现设置页与凭据管理（P0）
  - [x] 14.1 实现设置页各源/AI/隐私可折叠卡片
    - 每卡片含凭据输入（默认掩码 + 临时显示开关）、连接测试、状态显示、清除按钮、官方文档链接
    - Bangumi（Token/OAuth + 用户名 + 同步开关 + 清除授权）、AniList（Token/登录说明 + 列表读取开关）、MAL（Client ID + 可选 Secret + OAuth/PKCE，仅显式配置后启用）、Jikan（无 key + 启用开关 + 频率/降级提示）、VNDB（Token + 成人内容开关 + 分级过滤）
    - _Requirements: 4.1, 4.2, 4.5, 4.6, 4.7, 4.8, 4.9, 4.13_
  - [x] 14.2 实现 AI Provider 设置卡片
    - Provider 选择、API key、Base URL、模型名、测试按钮、预算提醒
    - _Requirements: 4.10, 16.1_
  - [x] 14.3 实现隐私控制卡片与保存提示
    - 本地数据清除、缓存清除、导入导出入口、是否允许 AI 分析评论、是否记录快照开关；保存凭据时提示「凭据仅保存在本机」
    - _Requirements: 4.3, 4.11, 4.12_
  - [x] 14.4 实现连接测试探针与三态结果
    - 各源最小化探针请求（10s 超时），返回成功/失败原因/文档入口
    - _Requirements: 4.4_

- [x] 15. 实现导航、首启引导与全局状态（P0）
  - [x] 15.1 实现单 Activity + Compose Navigation 五栏底栏
    - 首页/发现/待补池/时光机/我的，高亮当前页，自然返回栈；嵌套路由 Detail/Settings/Import/Recommender
    - _Requirements: 5.2, 5.3_
  - [x] 15.2 实现首次启动引导
    - 说明不提供播放/下载、数据默认本地、可稍后配置 key
    - _Requirements: 5.1_

- [x] 16. 检查点 — Bangumi/匹配/聚合/设置/导航验证
  - 确保所有测试通过，如有疑问请询问用户。

- [x] 17. 实现批量导入模块（P0 核心）（核心完成，optional tests pending）
  - [x] 17.1 实现导入文本解析器
    - 自动拆分疑似作品名；解析书名号、顿号、逗号、换行、编号列表；TXT/CSV 文件导入（作品名 + 可选备注）；OCR 入口与权限说明预留
    - _Requirements: 8.1, 8.2, 8.3, 8.4_
  - [ ] 17.2 编写导入解析 round-trip 属性测试
    - **Property 9: 导入解析 round-trip**
    - **Validates: Requirements 8.1, 8.2, 19.6**
  - [x] 17.3 实现 Import_Batch 生成、被安利次数与一键加入去重
    - 生成批次（名/时间/来源/识别/成功/失败数）；命中同一 Work 自增 `recommendedCount`；低置信需用户确认；一键加入待补池并去重
    - _Requirements: 8.5, 8.6, 8.7, 8.8_
  - [ ] 17.4 编写加入去重幂等属性测试
    - **Property 10: 加入待补池去重且幂等**
    - **Validates: Requirements 8.7, 10.5**
  - [ ] 17.5 编写被安利次数计数属性测试
    - **Property 11: 被安利次数计数正确**
    - **Validates: Requirements 8.6**

- [x] 18. 实现待补池与决策池（P0 核心 + P1 扩展）
  - [x] 18.1 实现 `BacklogRepository` 与待补池页核心
    - 展示全部 BacklogItem 卡片（封面/标题/评分/匹配度/补完成本）；筛选排序；高/中/低优先级 + 自定义备注
    - _Requirements: 8.1, 10.1, 10.2, 10.3_
  - [x] 18.2 实现批量操作、吃灰天数与一键抽番
    - 批量删除/改状态/同步/加标签；吃灰天数与「吃灰博物馆」阈值归类；一键抽番带理由（非纯随机）
    - _Requirements: 10.4, 10.5, 10.6, 8.4_

- [x] 19. 实现作品详情页（P0 核心）
  - [x] 19.1 实现详情页顶部信息区与评分区
    - 封面/标题/原名/中文名/别名/类型/年份/状态/集数·卷数·游玩时长；多平台评分与人数，缺失显示「暂无数据」不隐藏区域
    - _Requirements: 9.1, 9.2, 9.3_
  - [x] 19.2 实现个人区、社区共识卡与决策区
    - 我的状态/评分/进度/短评/标签 + 加入/移出待补池；社区共识卡（稳定度/争议/优先级，不伪造结论）；决策区（匹配度/理由/雷达/心情/成本）
    - _Requirements: 9.4, 9.5, 9.6_
  - [x] 19.3 实现详情 Tab 与 Completion_Cost 分类
    - 简介/评论摘要/角色·Staff/关联作品/观看路线/平台数据/我的记录；按集数·单集时长·卷数·游玩时长归类为今晚/周末/长期坑
    - _Requirements: 9.7, 9.8_

- [x] 20. 实现首页今日决策中心（P0 核心 + P1）
  - [x] 20.1 实现首页核心区
    - 「今晚看什么」大卡 → Recommender；今日状态选择；继续看/读/玩区；待补池概览（数量/最近/吃灰最久/短篇/高匹配）；搜索与批量导入入口
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6_
  - [x] 20.2 实现同步提醒与今日补番签（P1）
    - 最近同步时间/失败提醒/手动同步入口；今日宜/忌建议
    - _Requirements: 6.7, 6.8_

- [x] 21. 实现发现页与搜索（P0 搜索 + P1 发现）
  - [x] 21.1 实现搜索模块与低置信手动纠正
    - 按中文/日文/罗马音/英文/别名搜索；多源合并显示来源标签与 Match_Confidence；低置信允许手动选择正确条目
    - _Requirements: 7.1, 7.2, 7.3_
  - [x] 21.2 实现发现页榜单、评分差异榜与高级筛选（P1）
    - 各源榜单标来源；评分差异榜（中性措辞）；高级筛选（类型/状态/篇幅/评分/年份/完结/来源/风险/心情）；本季/冷门高分/短篇佳作
    - _Requirements: 7.4, 7.5, 7.6, 7.7_

- [x] 22. 检查点 — P0 主功能可用性验证
  - 确保所有测试通过，如有疑问请询问用户。

- [x] 23. 实现 AI 子系统（P0 结构化与剧透保护）
  - [x] 23.1 实现 `AiProvider` 抽象与各 Provider
    - 实现 OpenAI/Gemini/DeepSeek/OpenRouter/自定义 OpenAI 兼容；注入 baseUrl/model/key（来自 CredentialStore）；`supportsStructuredOutput`
    - _Requirements: 16.1_
  - [x] 23.2 定义四类 AI 任务与固定输出 schema
    - 防剧透雷达/口味画像/今晚推荐/路线图的固定字段 schema 与系统提示词模板（强制剧透保护）
    - _Requirements: 16.2, 16.4_
  - [x] 23.3 实现 `AiEngine` 调用管线：schema 校验 + 修复 + 低置信兜底 + 剧透过滤
    - 缺字段/JSON 损坏发起修复二次请求；仍失败输出低置信不编造；剧透 token 过滤抽象化；成本估算与确认（允许仅分析摘要）；写入 `AI_RESULT` 缓存
    - _Requirements: 16.3, 16.4, 16.5, 16.6, 16.7_
  - [ ] 23.4 编写 AI 修复链路集成测试（mock provider）
    - 结构化输出与修复二次请求链路
    - _Requirements: 16.3, 19.5_

- [x] 24. 实现无剧透评价雷达与本地回退（P0 基础 + P1 增强）（核心完成，optional tests pending）
  - [x] 24.1 实现 `Local_Fallback` 雷达规则引擎
    - 未配置 AI key 时基于标签与短评关键词统计生成基础维度雷达，标注 `generator=RULE`
    - _Requirements: 11.1, 11.2, 11.3_
  - [x] 24.2 实现雷达 AI 增强、剧透等级与来源标注（P1）
    - AI 增强摘要；剧透等级（无剧透必做，轻微/完整预留）；摘要来源标注（短评/Reviews/标签/AI）
    - _Requirements: 11.4, 11.5, 11.6, 11.7_
  - [ ] 24.3 编写雷达维度完整且无剧透 token 属性测试
    - **Property 12: 雷达维度完整且无剧透 token**
    - **Validates: Requirements 11.1, 11.2, 11.7, 16.4**

- [x] 25. 实现口味画像与匹配度（P0 导入 + P1 分析）（核心完成，optional tests pending）
  - [x] 25.1 实现口味导入与统计
    - 从 Bangumi 等源导入用户评分/状态/短评/标签；统计高分/低分标签、常用短评词、常见搁置类型
    - _Requirements: 12.1, 12.2_
  - [x] 25.2 实现评分习惯、口味称号、口味黑洞与匹配度（P1）
    - 评分严格度/平均分/高分稀有度/分数段；口味称号；口味黑洞；详情页「你可能会不会喜欢」匹配度，低样本用「可能/倾向于」
    - _Requirements: 12.3, 12.4, 12.5, 12.6, 12.7_
  - [ ] 25.3 编写口味统计守恒与低样本置信属性测试
    - **Property 13: 口味统计守恒与低样本置信**
    - **Validates: Requirements 12.2, 12.7**

- [x] 26. 实现今晚看什么推荐器（P0）（核心完成，optional tests pending）
  - [x] 26.1 实现推荐器选择与三推荐
    - 时间/心情/接受程度选择；三推荐（稳妥/赌一把/神经病）带理由；不推荐已完成或不满足硬过滤的作品
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.8_
  - [x] 26.2 实现不准纠结/期末保护/深夜提醒模式（P1）
    - 不准纠结仅一个推荐 + 明确理由；期末保护过滤长篇/致郁/高上头/未完结；深夜温柔提醒
    - _Requirements: 13.5, 13.6, 13.7_
  - [ ] 26.3 编写推荐器硬过滤与不重复已完成属性测试
    - **Property 14: 推荐器硬过滤与不重复已完成**
    - **Validates: Requirements 13.4, 13.6, 13.8**

- [x] 27. 实现补番路线图与系列管理（P0 展示 + P1 顺序）
  - [x] 27.1 实现关联作品展示与一键加入系列
    - 展示续作/前传/外传/OVA/剧场版/总集篇；一键将整个系列或必看节点加入待补池
    - _Requirements: 14.1, 14.4_
  - [x] 27.2 实现推荐观看顺序与路线待确认（P1）
    - 必看/可选/可跳过/总集篇提示；系列进度节点；资料不足标记 `routeConfirmed=false` 不编造顺序
    - _Requirements: 14.2, 14.3, 14.5_
  - [ ] 27.3 编写路线待确认不编造顺序属性测试
    - **Property 15: 路线待确认不编造顺序**
    - **Validates: Requirements 14.5**

- [x] 28. 实现本地时光机与年度报告（P0 核心 + P1 报告）
  - [x] 28.1 实现初始快照与差异追踪
    - 首次导入建立 `Snapshot(kind=INITIAL)`；后续同步 diff 生成 `CHANGE_LOG`（新增/状态/评分/短评/进度变化）；仅从首次同步起记录
    - _Requirements: 15.1, 15.2, 15.7_
  - [x] 28.2 实现时间线与月/年报告（P1）
    - 按月/年时间线；以前的我怎么想；评分打脸现场；月度/年度报告（数量/平均分/最高分/常见标签/口味变化/吃灰作品）
    - _Requirements: 15.3, 15.4, 15.5, 15.6_

- [x] 29. 实现我的页与统计（P0）（核心完成，optional tests pending）
  - [x] 29.1 实现我的页配置状态与统计
    - 各平台配置状态 + 最后测试时间；数据统计（看过/在看/想看/搁置/抛弃/平均评分/最高分/常见标签）；口味画像入口；进入设置入口
    - _Requirements: 17.1, 17.2, 17.3, 17.6_
  - [x] 29.2 实现隐私/导出操作与关于页（P1）
    - 清除本地数据/导出备份/导入备份/清理缓存；关于页（数据源/开源许可/免责声明/不提供盗版）
    - _Requirements: 17.4, 17.5_
  - [ ] 29.3 编写统计计数守恒属性测试
    - **Property 16: 统计计数守恒**
    - **Validates: Requirements 17.2**

- [x] 30. 实现备份/导出/迁移（P0 备份 + P1 CSV）（核心完成，optional tests pending）
  - [x] 30.1 实现备份序列化（默认零凭据）与导入合并不覆盖
    - 导出收藏/待补池/评分/短评/标签/快照/设置，默认 `credentials=null`；显式导出凭据需二次确认 + 脱敏；导入按业务主键合并不覆盖、提示冲突、默认保留较新 `updatedAt`
    - _Requirements: 18.1, 18.2, 18.3, 18.4, 18.5_
  - [ ] 30.2 编写备份序列化 round-trip 属性测试
    - **Property 17: 备份序列化 round-trip**
    - **Validates: Requirements 18.8**
  - [x] 30.3 实现 CSV 导出与跨账号合并数据结构预留（P1/P2）
    - 待补池/时光机/评分表 CSV 导出；为跨账号/多平台列表合并预留结构（`SOURCE_LINK`/`IMPORT_BATCH`）
    - _Requirements: 18.6, 18.7_

- [x] 31. 实现 AniList 数据源客户端（P1）
  - [x] 31.1 核验 AniList 官方文档并记录
    - 联网核验 GraphQL 鉴权、字段、速率限制；写入 `DEVELOPMENT.md`
    - _Requirements: 3.3_
  - [x] 31.2 实现 AniList Apollo GraphQL service、DTO 与 mapper
    - 国际评分/热度/趋势/Reviews/用户列表/罗马音·英文标题/Staff/本季；接入降级编排与中文兜底
    - _Requirements: 3.3, 3.8, 3.11_

- [x] 32. 实现 Jikan 数据源客户端（P1/P2）
  - [x] 32.1 核验 Jikan 官方文档并记录
    - 核验速率限制（约 3 req/s & 60 req/min）、字段；写入 `DEVELOPMENT.md`
    - _Requirements: 3.4_
  - [x] 32.2 实现 Jikan REST service、DTO、mapper 与双桶限流配置
    - MAL 评分/排名/人气/Reviews/Recommendations；配置 3 req/s + 60 req/min 双令牌桶；接入降级编排
    - _Requirements: 3.4, 3.10, 3.8_

- [x] 33. 实现 MAL 官方数据源客户端（P2）
  - [x] 33.1 核验 MAL 官方文档并记录
    - 核验 OAuth2/PKCE、字段；写入 `DEVELOPMENT.md`
    - _Requirements: 3.5_
  - [x] 33.2 实现 MAL OAuth/PKCE 与 REST service
    - 仅用户显式配置 Client ID 后启用；官方用户列表/进度/评分；接入降级编排
    - _Requirements: 3.5, 4.7, 4.13_

- [x] 34. 实现 VNDB 数据源客户端（P2）
  - [x] 34.1 核验 VNDB 官方文档并记录
    - 核验 HTTP API、字段、成人内容分级；写入 `DEVELOPMENT.md`
    - _Requirements: 3.6_
  - [x] 34.2 实现 VNDB HTTP API service 与成人内容过滤
    - VN 资料/评分/标签/角色/Staff/制作社/用户列表；成人内容分级过滤；接入降级编排
    - _Requirements: 3.6, 4.9_

- [x] 35. 实现趣味功能（P1/P2）
  - [x] 35.1 实现安利债务、吃灰博物馆、补番人格与情绪风险提示
    - 安利债务（欠群友 N 部）；吃灰博物馆；补番人格称号（复用口味称号）；情绪风险提示（致郁/压抑/党争/高上头）；补番遗书入口预留
    - _Requirements: 20.1, 20.2, 20.3, 20.4, 20.5_

- [ ] 36. 横切测试与冒烟验证
  - [ ] 36.1 编写冒烟测试：无账号/无 key/无网络干净安装不崩溃
    - 验证干净安装启动不崩溃；验证仓库存在三份文档且使用 RC.xx.yy 编号
    - _Requirements: 19.1, 2.1, 2.2, 2.3_
  - [ ] 36.2 编写 API 异常处理集成测试（MockWebServer）
    - 覆盖 200/404/401/403/429/500/空数组/字段缺失/限流/超时
    - _Requirements: 19.4_
  - [ ] 36.3 编写时光机快照/同步/冲突单元测试
    - 首次快照、二次同步、评分/短评/状态变化、删除/合并冲突
    - _Requirements: 19.7_
  - [ ] 36.4 编写页面 UI/快照测试
    - 深色模式、大字体、长标题、封面缺失、横竖屏、低端机、返回栈
    - _Requirements: 19.8, 5.8_
  - [ ] 36.5 编写导入边界单元测试
    - 中/日/英/罗马音、多行、逗号、书名号、重复标题、同名作品、低置信匹配
    - _Requirements: 19.6_
  - [ ] 36.6 编写 AI 降级场景单元测试
    - 无 key、本地规则版、输出不完整、含剧透、JSON 损坏、超时
    - _Requirements: 19.5_

- [x] 37. 最终检查点 — 全量测试与文档同步
  - 确保所有测试通过；同步更新 `REQUIREMENTS.md`/`DEVELOPMENT.md`/`EXPERIENCE.md`；如有疑问请询问用户。

## Notes

- 标 `*` 的子任务为可选/测试类任务，可在快速 MVP 时跳过，但属性测试覆盖 18 条 Correctness Properties，建议执行。
- 每个任务引用具体 Requirement 与 RC 编号以保证可追溯。
- 检查点用于增量验证。
- 数据源按 Bangumi(P0) → AniList(P1) → Jikan(P1/P2) → MAL(P2) → VNDB(P2) 顺序接入；每源实现前必须先核验官方文档并写入 `DEVELOPMENT.md`（RC.01）。
- P1/P2 功能的数据模型与页面结构已在任务 5/7（Room 实体、领域模型）中预留。
- 属性测试使用 `kotest-property`，每个属性 ≥ 100 次迭代，标签格式：`// Feature: acg-compass, Property {n}: {property_text}`。

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1"] },
    { "id": 1, "tasks": ["2", "3.1", "3.3"] },
    { "id": 2, "tasks": ["3.2", "4.1", "5.1", "6.1"] },
    { "id": 3, "tasks": ["4.2", "4.3", "5.2", "6.2", "6.3"] },
    { "id": 4, "tasks": ["4.4", "5.3", "6.4", "7.1", "8.1"] },
    { "id": 5, "tasks": ["5.4", "7.2", "8.2", "8.4"] },
    { "id": 6, "tasks": ["8.3", "8.5", "8.6"] },
    { "id": 7, "tasks": ["8.7", "10.1"] },
    { "id": 8, "tasks": ["10.2", "11.1"] },
    { "id": 9, "tasks": ["10.3", "11.2", "11.3", "12.1"] },
    { "id": 10, "tasks": ["12.2", "13.1"] },
    { "id": 11, "tasks": ["13.2", "14.1", "14.2"] },
    { "id": 12, "tasks": ["14.3", "14.4", "15.1"] },
    { "id": 13, "tasks": ["15.2", "17.1"] },
    { "id": 14, "tasks": ["17.2", "17.3"] },
    { "id": 15, "tasks": ["17.4", "17.5", "18.1"] },
    { "id": 16, "tasks": ["18.2", "19.1"] },
    { "id": 17, "tasks": ["19.2", "19.3", "20.1"] },
    { "id": 18, "tasks": ["20.2", "21.1"] },
    { "id": 19, "tasks": ["21.2", "23.1"] },
    { "id": 20, "tasks": ["23.2"] },
    { "id": 21, "tasks": ["23.3"] },
    { "id": 22, "tasks": ["23.4", "24.1", "25.1", "26.1", "27.1", "28.1", "29.1", "30.1"] },
    { "id": 23, "tasks": ["24.2", "24.3", "25.2", "25.3", "26.2", "26.3", "27.2", "27.3", "28.2", "29.2", "29.3", "30.2", "30.3"] },
    { "id": 24, "tasks": ["31.1", "32.1", "33.1", "34.1"] },
    { "id": 25, "tasks": ["31.2", "32.2", "33.2", "34.2", "35.1"] },
    { "id": 26, "tasks": ["36.1", "36.2", "36.3", "36.4", "36.5", "36.6"] }
  ]
}
```

## Runtime 验收反馈（第 3 轮 · 真机测试）— 重开 / 新增任务

> 说明：以下为真机验收发现的实际功能 / 体验缺陷。凡引用「重开 RC.xx / 任务 N」者，表示该任务此前标为核心完成但运行时未达标（runtime gap），现重新打开；与可选 `*` 测试任务严格分开，本节均为非可选实现任务。按 P0 → P1 → P2 顺序实现，全部完成后再 assembleDebug。

### P0 — 核心可用性（必须优先）

- [x] R1. 搜索状态完整持久化（重开 RC.05.01 / 任务 21.1）— 已完成：DiscoverViewModel uiState 改为 SharingStarted.Lazily，关键词/筛选未变不重搜
  - runtime gap：详情页停留约 10s 后返回搜索页会重新搜索；期望关键词/筛选未变则不重搜
  - 关键词、筛选、结果列表、滚动位置、来源错误提示全部保留；提供手动刷新 / 下拉刷新，禁止自动重搜
  - 排查 ViewModel 生命周期、back stack、rememberSaveable、StateFlow 缓存、screen state key
- [x] R2. 搜索质量优化（重开 RC.05.02 / 任务 11.1 / 21.1）— 已完成：TitleSimilarity 增加子串/前缀/全半角归一；按置信度+来源可信度排序；低置信顶部统一提示
  - 支持中/日/英/罗马音/别名、符号差异、全半角差异；「2.5」「2.5次元」「2.5次元的诱惑」均应命中「2.5次元の誘惑」
  - 高匹配结果按热度/评分人数/来源可信度排序；低置信提示在顶部统一展示一处，不每条一卡
- [x] R3. 搜索结果详情页数据补齐（重开 RC.07 / 任务 19.1）— 已完成：WorkRepositoryImpl.refreshRatingsAndWork 进入详情按全部源链接拉取评分+主源刷新作品写回 Room
  - 进入详情时按 work/source id 拉取完整详情写入 Room，再由 Flow 展示：评分、社区共识、来源标签、简介、关联作品、观看路线入口
  - 缺失字段显示「暂无数据」，但不可整页空白
- [x] R4. 批量导入真实 UI（重开 RC.06 / 任务 17.1/17.3）— 已完成：feature/imports ImportScreen+ImportViewModel（粘贴拆分预览/识别/低置信确认/一键加入去重计数/统计）
  - 首页「批量导入」当前是占位页；实现：粘贴框 + 自动拆分预览、TXT/CSV 入口、识别结果列表、低置信确认、一键加入待补池（去重 + 被安利计数）、成功/失败统计
- [x] R5. 口味画像页面（重开 RC.10 / 任务 25.2）— 已完成：feature/taste TasteProfileScreen+ViewModel，接入统计；经 我的→更多→口味画像 进入
  - 「我的→更多→口味画像」当前无反应；实现页面并接入已有统计：平均分/严格度/高分稀有度/分数段/称号/口味黑洞；低样本用「可能/倾向于」
- [x] R6. 隐私与数据 / 备份导入导出可用（重开 RC.16 / 任务 30.1/30.3 / RC.02 4.3）— 已完成：SAF CreateDocument/OpenDocument 接入；BackupRepository.exportCsv(kind) 支持 backlog/ratings/time-machine；导出默认 credentials=null；导入合并不覆盖并回报冲突
  - 接入系统文件选择器/保存器；导出备份默认 credentials=null 无明文；CSV 导出 backlog/ratings/time-machine 可打开；导入备份冲突提示、不静默覆盖
- [x] R7. 时光机未登录可用 + Bangumi 同步（重开 RC.13 / 任务 28.1/28.2）— 已完成：动作栏「拍快照（本地）」从待补池建初始快照/记录差异（无需登录）；「从 Bangumi 同步」凭据门控，未配置明确提示、已配置验证连接并说明云端→本地只读、本地→云端写入需确认（冲突安全）；结果经 Snackbar 反馈，按钮不再无反应
  - 备注：Bangumi 全量收藏→本地条目映射（subjectId→内部 workId）涉及数据层 source-link 解析，作为后续增强；当前为凭据验证 + 冲突安全策略提示
  - 同步/「…」按钮不可点；本地添加/导入即建初始快照，后续变化生成 change log，未登录也可用
  - 登录 Bangumi 后增强：Bangumi→本地、本地→Bangumi 同步，双向需明确冲突策略与确认

### P1 — 重要体验

- [x] R8. 待补池页面视觉重做（重开 RC.08 / 任务 18.1）— 已完成：统一大标题；筛选/排序做成清晰卡片（标题+已选数量+展开/收起）；空池美观引导卡（批量导入/搜索添加 CTA）；空池隐藏「一键抽番」；多选与整体布局协调
  - 顶部标题区完整、筛选/排序折叠做成清晰卡片栏、空状态做成美观卡片（引导去导入/搜索）、空池时「一键抽番」禁用或提示、多选与整体布局协调
  - 验收补充：不仅是折叠筛选，页面要有明确主体感；空待补池时「一键抽番」必须禁用或给出明确提示（不可孤零零漂浮）
- [x] R9. 我的页 UI 与滚动（重开 RC.15 / 任务 29.1）— 已完成：新增 AcgTopLevelTopBar（headlineSmall 加粗、靠左、windowInsets=0 消除双重内边距）统一五个顶层页标题；内层 Scaffold contentWindowInsets=0；我的页滚动列加底部 padding
  - 顶部内容展示不全且无法上滑；处理 status bar / bottom nav padding；一级标题（我的/待补池/首页/发现）更靠左上、字号略大
- [x] R10. 我的「隐私与数据管理」深链（重开 RC.15.04）— 已完成：settings?section=privacy 路由 + SettingsViewModel.expandPrivacy()，深链进入设置并自动展开隐私区
  - 该入口应进入设置页并自动展开/滚动到「隐私与数据」区域（当前与「设置」入口相同）
- [x] R11. 设置页数据源逻辑与文案（重开 RC.02 4.x / RC.01 3.x）— 已完成：Bangumi 卡片在列；公共搜索并行查询所有源（不强制 token，MAL 除外）；statusText 区分「公共搜索可用，个人同步未配置」；token 框去重复「（可选）」；MAL 增加 Client ID 获取说明
  - 必须有 Bangumi API 卡片；AniList/Jikan/VNDB 公共搜索不强制 token，未填 token 启用也能在搜索页见到公共结果；token 仅限个人列表/同步/写入
  - 修复 token 框重复「（可选）」；连接测试文案区分「公共搜索可用 / 个人同步需 token / 未配置 / 失败原因 / 10s 超时」；MAL Client ID 提供获取说明/文档入口
- [x] R12. 官方文档链接可打开（重开 RC.02 4.13）— 已完成：显式 ACTION_VIEW Intent（FLAG_ACTIVITY_NEW_TASK）+ uriHandler 兜底；都失败则复制链接到剪贴板并 Toast 提示，不再无反应
  - 所有「查看官方文档」用浏览器/Custom Tab 打开；无浏览器时给错误提示，不可无反应
- [x] R13. 底部导航「首页」误回发现页（重开 RC.03.02）— 已完成：navigateToTopLevel 去除 saveState/restoreState，popUpTo 起始页 + launchSingleTop，每个 Tab 必进正确页
  - 排查 popUpTo/launchSingleTop/restoreState/selected route；每个 Tab 必进正确页面
- [x] R14. 页面切换动画过慢（重开 RC.03）— 已完成：NavHost enter/exit/popEnter/popExit 全部 None，即时切换
  - 去除/大幅缩短 Tab 与普通页切换动画
- [x] R15. 发现→筛选闪退根因复核（重开 RC.05.06）— 已完成：根因=LazyColumn 重复 key（itemsIndexed 复合 key 修复）；发现筛选只对 observeWorks∩待补池（库内/待补作品）生效，不灌入搜索缓存
  - 已修 LazyColumn 重复 key；继续排查筛选状态与旧搜索结果混用、item 类型不一致、空数据态；修复后记录根因
  - 验收补充（用户不接受现状）：发现页筛选不得展示「搜索缓存作品」当作搜索历史。明确边界——发现筛选只对「用户收藏/待补池等已纳入库的作品」生效，搜索缓存作品不应直接灌入发现筛选池
- [x] R16. 搜索框与按钮文字布局（重开 RC.03 / RC.05.01）— 已完成：搜索框 maxLines=1+ellipsis、占位文案缩短；时光机/待补池/导入/CSV/文档链接等多字按钮单行省略
  - 搜索框单行、过长省略/横向滚动；≥4 字按钮保持单行（加宽/缩字号/改短文案）

### P2 — 完整性与打磨

- [x] R17. 首页与待补池内容边界梳理（重开 RC.04 / RC.08）— 已完成：首页待补池概览标注「快速一览，完整管理在待补池标签页」；首页聚焦今日决策/快速入口/状态概览
  - 首页偏「今日决策/快速入口/状态概览」，待补池偏「完整管理/筛选排序/批量操作」，避免观感重复
- [x] R18. 连接测试与数据源可用性反馈细化（重开 RC.02 4.4）— 已完成：RealConnectionTester 真实探针（10s 超时），区分 公共搜索可用 / 个人同步未配置 / 未配置需 Client ID / 连接失败原因 / 超时
  - 无 token 公共 API 提示「公共搜索可用，个人同步未配置」；需 key/token 的个人功能提示具体缺什么

### 文档同步（本轮收尾）

- [x] R19. 同步 tasks.md（本节）/ EXPERIENCE.md（运行问题+根因+规避）/ DEVELOPMENT.md（token 可选性、文档链接、API 行为变化）— 已完成

## Runtime 验收反馈（第 4 轮 · 真机测试）— 重开 / 新增任务

> 说明：第 4 轮真机验收发现「功能表面存在但数据链路/体验未打通」的一批缺陷。引用「重开 RC.xx / 任务 N / Rn」者为之前标完成但 runtime 仍不达标，现重新打开；本节全部为非可选实现任务，与 optional `*` 测试严格分开。按 P0 → P1 → P2 实现，全部完成后再 assembleDebug。

### P0 — 核心数据链路与可用性

- [ ] R20. 搜索结果真正跨源合并（重开 R2/R20 / RC.05.02 / 任务 11.1）— 已完成（DiscoverContract.mergeCrossSource：按全标题变体相似度+年份贪心聚类，借各源普遍填充的日文名实现跨语言合并；多源合并为一张卡+多来源标签+最佳置信度；合并候选保留供手动调整）
  - 已知限制：纯罗马音↔汉字且双方都不含共享日文名变体的极端对仍可能不自动合并，提供「已合并/手动调整」入口兜底
- [x] R21. 搜索卡「手动纠正」泛滥（重开 R2 / RC.05.03）— 已完成：仅低置信单源结果显示「手动纠正」；多源合并卡显示「已合并 N 个来源 · 调整」轻量入口；顶部低置信提示只一次
- [ ] R22. 发现页本季榜单/评分差异/筛选不可用（重开 RC.05.04/05/06 / R15）— 部分完成：发现池由「仅待补池交集」放开为「已缓存多源作品（公共发现池）」，搜索/浏览过即有内容；筛选 UI 标注当前范围
  - 待办：本季榜单/热门接入实时公共 trending 端点（AniList trending / Jikan top / Bangumi 热门）尚未接入，目前榜单基于已缓存条目；live 端点为后续增强
- [ ] R23. 详情页 fetch-on-open 数据补齐（重开 R3 / RC.07 / 任务 19.1）
  - 详情页除评分外基本空白；进入时拉取完整详情写 Room：简介/多平台评分+人数/社区共识/关联作品/观看路线入口/平台数据/我的记录/来源标签
  - 兼容 VNDB（简介/评分/标签/角色/制作）与 Bangumi（简介/评分/收藏状态/进度/短评）；缺字段「暂无数据」不可整页空
- [ ] R24. Bangumi 绑定后个人数据接入（重开 RC.15 / RC.13 / 任务 29.1）
  - 配置后获取用户收藏/状态/评分/进度/短评；我的页统计基于 Bangumi 导入或本地（看过/在看/想看/搁置/抛弃/平均分/常见标签）
  - 显示是否已同步 + 最后同步时间 + 失败原因；提供「从 Bangumi 同步我的收藏/评分」入口；API 限制致缺字段在 UI 说明
- [ ] R25. 时光机本地与 Bangumi 统一时间线（重开 R7 / RC.13 / 任务 28.1/28.2）
  - 现两按钮割裂、Bangumi 只提示不导入；登录后「从 Bangumi 导入/同步收藏」写入 Room + 生成初始快照 + 后续 change log
  - 可暂不自动写回云端，但必须支持云端→本地导入 + 本地快照，显示同步结果（新增/更新/跳过/失败），本地与 Bangumi 进同一条时间线
- [x] R26. 设置卡片标题竖排/挤压（重开 RC.02 4.1）— 已完成：CollapsibleCard 改为「行1 标题（weight+单行省略）+ 展开箭头，行2 状态文案独立成行」，移除挤压标题的等宽状态 Chip；适配窄屏/大字体
- [ ] R27. Bangumi 配置后设置卡片异常/消失（重开 RC.02 4.6）— 部分完成：卡片始终在列且布局修复（R26 解决竖排导致的「像消失」观感），已配置显示清除授权/重新测试入口
  - 待办：已配置时显示「当前用户信息（用户名）」需 Bangumi getMe 接入，依赖 R24 同步
- [x] R28. AI 服务连接测试（重开 RC.14.01 / RC.02 4.5）— 已完成：RealConnectionTester 对 AI_PROVIDER 发起一次最小补全探针（model 留空回落默认，maxTokens=1，10s 超时），区分 未配置 / 连接成功模型可用 / 连接失败原因 / 超时

### P1 — 页面与体验

- [x] R29. 我的页顶部滑动/遮挡（重开 R9 / RC.15）— 已完成（随 R9）：AcgTopLevelTopBar(windowInsets=0) + 内层 Scaffold contentWindowInsets=0 消除双重 inset；verticalScroll 列加底部 24dp padding，内容可完整滚动不被底栏遮挡
- [ ] R30. 口味画像「从设置导入口味数据」CTA 无效（重开 RC.10 / 任务 25.2）— 部分完成：空态 CTA 不再是死按钮，点击导航到设置页（Bangumi/同步入口）；StateScaffold onCta 已接线
  - 待办：「立即从 Bangumi 导入口味数据」并刷新画像依赖 R24 用户收藏同步（构建 TasteInputRecord 需用户评分数据）
- [ ] R31. 设置页改为摘要卡 + 二级详情页（重开 RC.02 4.1）
  - 主设置页仅摘要卡（名称/公共搜索可用/个人同步是否配置/最后测试/进入按钮）；点击进二级页放 Token/Client ID/Base URL/模型名/测试/清除/文档；返回后主页状态刷新
- [ ] R32. 首页可选展示模块 + 与待补池边界（重开 R17 / RC.04）
  - 设置增加首页展示偏好（近期热门/排行榜/适合你的/随机抽番/待补池快捷/继续看读玩）；首页默认今日决策+热门/本季+待补概览+快速入口；待补池专注完整管理
- [ ] R33. 本季榜单/排行/适合你的接入数据（重开 RC.05.04 / RC.04）
  - 近期热门/排行来自公共 API（AniList trending / Jikan top / Bangumi 热门排名）；适合你的基于口味画像+榜单候选+待补池；数据不足显示引导不空白；无 token 公共 API 可用
- [ ] R34. 详情页「我的记录」真实数据（重开 RC.07 / RC.15）
  - 不能只是占位；绑定 Bangumi 显示该条目状态/评分/进度/短评/标签 + 来源；本地有记录显示本地；冲突显示来源与策略；支持加入/移出待补池、修改本地记录；写回 Bangumi 的按钮注明需确认

### P2 — 打磨与验收

- [ ] R35. 搜索合并自测样例（验收）
  - 用「2.5 / 2.5次元 / 2.5次元的诱惑」「9nine / 9-nine / 9-nine 新章 / 9-nine- 新章 / 9-nine- Shinshou」自测；同一作品跨源不重复，中/日/英/罗马音互相命中，高热度/准确条目靠前，低置信统一提示不刷屏
- [x] R36. 数据源公共/个人能力模型统一（重开 RC.01 / RC.02）— 已完成（文档 + 状态文案）：DEVELOPMENT.md 明确各源公共/个人能力；设置状态文案区分「公共搜索可用」与「个人同步未配置」
- [x] R37. 文档同步（收尾）— 已完成：tasks.md（本节）/ EXPERIENCE.md / DEVELOPMENT.md 更新

### 第 4 轮未完成项（需较大数据层 / 重构，明确原因，留待后续）

- R23/R24/R25/R34（详情页完整数据、Bangumi 个人数据接入我的页统计、时光机统一导入、详情页「我的记录」）：均依赖**将 Bangumi 用户收藏/状态/评分/进度/短评同步入库**这一共同基础设施。当前 Room 无「用户收藏/观看状态」表（我的页 watched/watching 等本就无本地建模），需新增 Entity + DAO + 数据库迁移，并改造详情/我的/时光机/口味画像四处消费方。属跨层较大改动且涉及 schema 迁移风险，本轮未实现，单独排期，避免在未充分验证下引入迁移破坏既有数据。
- R31（设置主页摘要卡 + 二级详情页导航）：需新增 6 个二级路由与导航/状态回传重构，本轮以 R26 修复卡片挤压先保证可用，二级页拆分留待后续。
- R32（首页可选展示模块 + 偏好）：需新增首页偏好设置项与首页模块化渲染，本轮先以 R17 边界提示区分首页/待补池，模块化偏好留待后续。
- R33（本季榜单/排行接入实时公共 trending 端点）：需为 AniList/Jikan/Bangumi 新增 trending/top/热门端点与 DTO/mapper；本轮先放开发现池为已缓存多源条目（R22），live 端点留待后续。

## Runtime 验收反馈（第 5 轮 · 真机测试）— 重开 / 新增任务

> 说明：第 5 轮真机验收。核心数据闭环（Bangumi 个人数据入库）不再作为「后续增强」，本轮必须实现。布局裁切、凭据状态不一致、搜索合并只合卡不合数据、发现页公共内容缺失一并处理。非可选任务。按阶段实现，全部完成后再 assembleDebug。不要 destructive migration。

### 阶段一 — 阻塞 UI 与凭据状态

- [x] R38. 我的页无法完整滚动、底部被裁切（重开 R9/R29 / RC.15）— 已完成：MineScreen 由 Column(verticalScroll) 改为 LazyColumn（与其它可滚动页一致）+ 底部 96dp contentPadding，内容完整可达不被底栏遮挡
- [x] R39. 设置页无法完整滚动、顶部 Bangumi 卡片被裁切（RC.02 4.1）— 已完成：设置 Scaffold contentWindowInsets=0 + TopAppBar windowInsets=0 消除双重顶部 inset，第一张卡完整可达；列表底部 96dp 留白
- [x] R40. Bangumi 已配置后失效 / 状态不一致（重开 R27/R24 / RC.02 / RC.01）— 已完成：设置/搜索/时光机/同步统一经 CredentialStore 状态与 BangumiSyncManager.isConfigured()；公共搜索匿名不受 token 影响；连接失败显示具体原因（getMe/分页错误 cause：401/403/网络/超时/解析）

### 阶段二 — Bangumi 用户数据同步基础设施（核心）

- [x] R45. Bangumi 用户数据同步基础设施（重开 R24/R23/R34 / RC.13/RC.15）— 已完成
  - 新增 `UserCollectionEntity`（user_collections 表：id=BANGUMI:subjectId / source / sourceItemId / localWorkId / status / rating / progress / comment / tags / updatedAt / syncedAt / sourceUpdatedAt）+ `UserCollectionDao` + 非破坏 `MIGRATION_1_2`（仅 CREATE TABLE+索引，DB version 1→2，无 destructive）
  - SourceLink/Work：Bangumi work id == subjectId，同步时 upsert Work（保留 createdAt），与搜索缓存天然共用同一 work id
  - `BangumiSyncManager.syncCollections()`：凭据门控 + getMe + 分页（50/页，上限 40 页）拉取 + 状态/评分/进度/短评/标签映射 + 统计（新增/更新/跳过/失败/总数）+ 错误处理（401/403/网络/超时/解析经 AppError.cause）
  - 消费方：我的页统计(R48) / 详情页我的记录(R44) / 口味画像(R47) / 时光机(R46) 均消费 user_collections
- [x] R46. 时光机真正从 Bangumi 导入并统一时间线（重开 R7/R25 / RC.13）— 已完成：onSyncFromBangumi 调 SyncManager 真正入库 → 读 user_collections 映射 CollectionState → 无快照建初始基线/有则记 change log；反馈新增/更新/跳过/失败统计；本地与云端同一时间线；未登录本地可用
- [x] R48. 我的页统计使用真实数据（重开 R24 / RC.15.02）— 已完成：buildMineStats 增 collections 参数，已同步时按 user_collections 计算看过/在看/想看/搁置/抛弃/平均分/最高分/常见标签；未同步回退待补池并显示「从 Bangumi 同步我的数据」入口
- [x] R47. 口味画像 CTA 真实可用（重开 R30 / RC.10）— 已完成：已配置则 onImportFromBangumi 调 SyncManager 同步 + 映射 TasteInputRecord + importAndCompute 刷新画像；未配置跳设置；Toast 反馈结果
- [x] R44. 详情页正文与个人记录（重开 R23/R34 / RC.07）— 已完成（个人记录）：DetailViewModel 注入 UserCollectionDao，observeByWork→CollectionState 喂入既有 buildDetailUiState/PersonalUiModel，显示状态/评分/进度/短评/标签；评分聚合/简介/关联作品沿用既有 fetch-on-open（R3）
  - 已知限制：跨源「多平台评分」在详情页仅聚合该 work id 名下评分；不同源各自 work id 的评分融合依赖 R42 的 work-id 统一（见 R42）

### 阶段三 — 搜索 / 发现数据链路

- [ ] R42. 搜索合并要融合信息而非只合卡（重开 R20 / RC.05.02）— 部分完成：合并卡展示多来源标签 + 最佳置信度，同作品聚为一张卡（R20）；待办：跨源「多平台评分摘要」需把不同源 work id 统一为同一规范作品并合并 source links/ratings，属数据层 work-id 归并较大改动，单独排期
- [x] R43. 搜索「手动纠正」仍过多（重开 R21 / RC.05.03）— 已完成（R21）：默认不显示，仅低置信单源显示「手动纠正」，多源合并卡显示轻量「已合并 N 源·调整」，高置信不显示
- [ ] R41. 发现页榜单/评分差异/筛选仍空（重开 R22）— 部分完成：发现池放开为已缓存多源作品（R22），筛选标注范围；待办：实时公共 trending 端点（AniList/Jikan/Bangumi）未接入
- [ ] R52. 发现页与首页公共模块共用公共发现数据源（RC.04/05）— 待办：与 R41 一并，需新增公共 trending 数据源后共用
- [ ] R53. 搜索合并自测样例（验收）— 待真机验证（2.5 / 2.5次元 / 2.5次元的诱惑 / 9nine / 9-nine / 9-nine 新章 / 9-nine- Shinshou）

### 阶段四 — 设置二级页与首页配置

- [ ] R49. 设置主页摘要卡 + 二级详情页（重开 R31 / RC.02 4.1）— 待办：二级导航重构未做；本轮已用 R26/R39 修复卡片挤压与滚动裁切保证主页可用
- [x] R50. AI Provider 可测试连接（重开 R28 / RC.14.01）— 已完成（R28）：AI 连接测试发起最小补全探针（10s 超时），成功「模型可用」/ 失败具体原因；位于 AI 卡片（二级页拆分见 R49）
- [ ] R51. 首页显示模块可配置（重开 R32 / RC.04）— 待办：首页偏好设置未做；本轮以 R17 边界提示区分首页/待补池

### 收尾

- [x] R54. 文档同步：tasks.md / EXPERIENCE.md（滚动裁切、凭据不一致、个人数据未入库、合并只合卡根因）/ DEVELOPMENT.md（Bangumi 用户收藏同步 API/分页/字段/错误/迁移/公共私有边界）— 已完成

## Runtime 验收反馈（第 6 轮）— Bangumi API 地址可配置

> 背景：国内网络可能无法直连 Bangumi 官方 API；需支持用户自选 API 地址（官方/社区反代/自定义），默认仍官方，绝不默认把 Token 发往第三方。本节为 R56/R69 的补充。

- [x] R55. Bangumi API 地址模式可配置（官方 / 社区反代 / 自定义）— 已完成：SettingsDataStore.bangumiApiBaseUrl + 设置页地址卡（官方/候选/自定义+校验归一化）；候选 bgmapi.anibt.net、api.bangumi.one 均已联网核验代理 /v0/，内置为社区候选（非默认）
- [x] R56. Token 隐私风险提示与确认 — 已完成：非官方+Token 需开关确认；BangumiBaseUrlInterceptor 在非官方且未确认时移除 Authorization（Token 不外发）；SyncManager 个人同步未确认即拒绝；Token 不入日志/错误/备份
- [x] R57. Bangumi 连接测试分能力细化 — 已完成：当前地址类型 / 地址可访问+公共搜索 / Token+用户信息 / 收藏可读，逐项结论 + 可读失败原因 + 官方失败切换提示
- [x] R58. 所有 Bangumi 功能统一读取同一 API Base URL — 已完成：BangumiBaseUrlInterceptor 对所有 Bangumi 源请求统一改写 host，搜索/详情/getMe/同步/统计/详情我的记录/口味/时光机/连接测试全部走同一配置
- [ ] R59. 设置页 Bangumi 卡片摘要 + 二级页 — 部分完成：已在 Bangumi 卡片下方提供完整 API 地址配置 + 风险确认；独立摘要卡 + 二级页归入 R49 后续排期
- [x] R60. DEVELOPMENT.md 记录 Bangumi API 地址策略 — 已完成

### 第 6 轮续修（用户复测：Bangumi API 地址选择器已通过验收，保留）

- [x] R61. 全页面顶部内容被标题栏遮挡 — 已完成：根因=外层 Scaffold 与各页内层 Scaffold/TopAppBar 的 inset 归属冲突。改为「外层 Scaffold contentWindowInsets=0（不消费顶部 inset）+ 各页 TopAppBar 用默认 windowInsets 自行消费状态栏」单一来源模型；AcgTopLevelTopBar 去掉 windowInsets=0；设置页 TopAppBar 恢复默认 inset。覆盖 Mine/Settings/Taste/TimeMachine/Detail/Backlog/Discover（共用 AcgTopLevelTopBar 或默认 TopAppBar）
- [x] R62. 时光机导入后仍「暂无内容」 — 已完成：根因=UI 仅消费 change log，而初始快照不产生 change log。VM uiState 改为 combine(changeLogs, works, snapshots, userCollections)：有 changeLog 显示时间线；仅有快照/收藏时显示「基线快照（N 个作品）」而非空态；导入文案含新增/更新/跳过/失败
- [x] R63. 时光机本地快照按钮无反应 — 已完成：onCaptureLocalSnapshot 优先用 user_collections，否则待补池；两者皆空显示「当前没有本地记录，先添加作品或从 Bangumi 同步」；成功后时间线（含基线视图）立即刷新
- [x] R64. 搜索跨源合并写入 source links（数据融合） — 已完成：新增通用 `domain/matching/CrossSourceMerge`（clusterMatches/representativeOf/sameWork）；WorkRepositoryImpl.persistMatches 把同簇各源链接统一指向代表 work id；详情页 refreshRatingsAndWork 据此聚合多平台评分。UI 与仓库共用同一聚类，卡片 workId==代表 id
- [x] R65. 搜索合并通用算法（防误合并/漏合并，无样例硬编码） — 已完成：CrossSourceMerge 通用规则——原文标题任一归一化变体相等且年份/类型不冲突→自动合并；原文一致但年份或类型冲突→不自动合并（区分系列总称/续作/新章/外传/跨媒介）；否则按多变体最大相似度≥阈值；热度仅用于排序不决定合并。2.5 / 9-nine 仅作回归样例，代码无 if/else 特判
- [x] R66. 隐藏普通搜索结果「手动纠正」 — 已完成（R21/R43）：高置信不显示；仅低置信单源显示；多源合并卡显示轻量「已合并 N 源·调整」
- [x] R67. Bangumi API 地址 UI 改为下拉框 — 已完成：ExposedDropdownMenuBox 单选（官方/bgmapi.anibt.net/api.bangumi.one/自定义）；选自定义才显示输入框；非官方显示风险+Token 同意开关。底层 BangumiBaseUrlInterceptor / Token 保护 / 连接测试逻辑不变
- [x] R68. 口味画像标签清洗 — 已完成：下划线/连字符转空格、折叠空白（BanGDream / No_Game_No_Life 更可读）
- [ ] R69. 发现页本季榜单/评分差异/筛选接公共数据源 — 未完成（技术原因）：需为 AniList(trending/season)、Jikan(top/season)、Bangumi(calendar/ranking)、VNDB 新增公共榜单端点 + DTO/mapper + 缓存表，属新增数据源接入（多文件、需各 API 文档核验），单独排期；当前发现池仍为已缓存多源作品（第 4 轮 R22 放开）
- [ ] R70. 详情页内容进一步补齐（简介/角色/关联作品/路线/平台数据） — 部分完成：我的记录（R44）+ 多源评分聚合（R64）已打通；角色/关联作品/路线等需各源额外端点与 UI 区块扩展，单独排期
- [ ] R71. 设置二级页（每源详情页） — 未完成（技术原因）：需新增 6 条二级路由 + 摘要卡导航 + 返回刷新，属导航重构；本轮已用 R61（滚动/遮挡修复）+ R26（卡片布局）+ R67（地址下拉）保证主页面可用，二级页拆分单独排期
- [ ] R72. 首页模块可配置偏好 — 未完成（技术原因）：需新增首页偏好设置项 + 首页模块化渲染 + 公共数据源（依赖 R69），单独排期
- [x] R73. 我的页 / Bangumi 卡片同步状态展示更清楚 — 部分完成：我的页「个人数据同步」卡显示同步结果（新增/更新/跳过/失败）与配置态；最后同步时间持久化展示需新增 DataStore 字段，单独排期

## Runtime 验收反馈（第 7 轮 · 真机测试）

> 进展：搜索合并、多平台评分、Bangumi 同步后我的统计/口味画像部分可用。仍有关键问题。非可选任务，按阶段实现，全部完成后再 assembleDebug。

### 第一阶段 — 布局与无效按钮
- [x] R74/R81. 多页面顶部内容过靠上 / 被遮挡（重开 R61）— 已完成：根因=DetailScreen 无 Scaffold/TopAppBar，外层不再消费顶部 inset 后封面贴状态栏。Detail 加 statusBarsPadding+底部留白；其余页（Home/Discover/Backlog/TimeMachine/Mine/Settings/Taste/Import/Recommender）均有 TopAppBar（默认 inset）+ 外层 contentWindowInsets=0 单一来源模型
- [x] R85. 首页同步提醒「立即同步」无反应 — 已完成：onManualSync 接入 BangumiSyncManager 真正同步；未配置跳设置；成功刷新提醒+Toast 统计；失败显示原因
- [x] R86. 待补池筛选无结果误判为空 — 已完成：区分真空与筛选无结果，后者保留筛选栏+「没有符合筛选条件的作品」+清除筛选，不破坏原列表
- [x] R87. 一键抽番 FAB 遮挡卡片按钮 — 已完成：移除悬浮 FAB，改为内容区顶部「一键抽番」按钮，不再遮挡「查看详情」
### 第二阶段 — 搜索合并与详情数据
- [x] R75. 搜索漏合并（核心标题包含，通用，重开 R65）— 已完成：CrossSourceMerge 增加核心标题包含判定（较长核心被完整包含且长度≥10 即合并，年份/类型不冲突），处理「日文原名 + Episode/副标题」场景；过短系列前缀（如「9 nine」）不触发，防误并
- [x] R76. 合并后多源数据融合稳定（重开 R64）— 已完成：persistMatches 把同簇各源链接统一指向代表 work id；详情 aggregateRatings 汇总各源；单源失败显示该源「暂无数据」不整源消失
- [x] R77. 多平台评分显示分数上限 — 已完成：Bangumi/Jikan/MAL 显示 x/10，AniList/VNDB 显示 x/100
- [ ] R78. 搜索排序优化 — 部分完成：已按匹配置信度降序 + 来源可信度同分裁决；热度/评分人数二级排序需搜索期获取各源 voteCount（当前搜索仅取标题匹配，评分在详情期才拉取），单独排期
- [ ] R79. 详情页多 Tab 与决策助手 — 部分完成：决策助手口味画像读取已修（R80）；简介/角色Staff/关联作品/路线接入归入 R90，单独排期
- [x] R80. 详情页误报「尚未生成口味画像」— 已完成：区分「画像未生成」与「画像已生成但该作品无标签/样本不足」，分别给出准确文案，不再误报未生成
- [ ] R90. 详情页角色/Staff/关联作品/路线接入 — 未完成（技术原因）：需为各源新增 characters/staff/relations 端点 DTO/mapper + 详情 UI 区块，多文件接入，单独排期
- [ ] R82. 时光机信息架构重构 — 未完成（较大 UI 重构）：需概览卡 + 时间线主体 + 操作移入更多菜单，单独排期
- [ ] R83. 时光机导入后展示具体作品（按状态分组可展开）— 未完成：依赖 R82 重构与快照明细读取，单独排期
- [ ] R84. 发现页公共数据源 — 未完成（技术原因）：需新增 AniList trending/season、Jikan top/season 端点 + DTO/mapper + 公共发现池缓存，单独排期
- [ ] R88. 设置二级页 — 未完成（导航重构），单独排期
- [ ] R89. 首页模块可配置 — 未完成，依赖 R84 公共数据源，单独排期
- [x] R91. 文档同步 — 已完成

## Runtime 验收反馈（第 8 轮 · 真机测试）

> 目标：把现存需求全部做完再打包。按阶段连续实现；若上下文/限流无法一次完成，明确停在某 R 项，下一轮从该项继续，不把未完成标完成。

### 第一阶段 — 同步状态与页面布局
- [x] R96. 页面顶部布局通病（重开 R74/R61）— 已完成：改为 inset 单一来源模型。外层 AcgApp Scaffold 用默认 contentWindowInsets 消费状态栏/导航栏并 consumeWindowInsets 下发；新增统一 `core/ui/AcgScreenScaffold` + `ScreenContentPadding`；所有页 TopAppBar 一律 windowInsets=0（AcgTopLevelTopBar + Settings/Taste/Recommender/Import/About 原始 TopAppBar）；Detail 去掉 statusBarsPadding。首张卡片不再贴顶/被裁，底栏不遮挡
- [x] R93. 首页同步后仍提示「尚未同步」状态不一致 — 已完成：首页同步提醒读 SyncStatusRepository 真实状态；Bangumi 行显示「已同步 · 最后同步时间 · 本地 N 部 · 新增/更新/跳过/失败」或失败原因；同步成功后 combine 自动刷新
- [x] R94. Bangumi 收藏状态完整同步入库 — 已完成：mapCollection 保存 想看/在看/看过/搁置/抛弃 + 评分/进度/短评/标签/更新时间到 user_collections；我的统计(buildMineStats)/详情我的记录(R44)/时光机(R46) 均消费；推荐器仅从待补池抽取（看过不在待补池，天然不推荐）
- [x] R100. 统一同步状态（SyncStatusRepository）— 已完成：新增 `data/sync/SyncStatusRepository`（独立 DataStore 持久化 lastSyncAt/added/updated/skipped/failed/remoteTotal/lastError/currentSource + 实时本地收藏数）；BangumiSyncManager 每次同步写入；首页/我的统一读取，重启后仍可见
- [x] R101. 待补池筛选空状态/FAB 不回退（守护 R86/R87）— 已完成：本轮未改动 BacklogScreen 相关逻辑，R86（筛选无结果保留筛选栏+清除筛选）/R87（抽番改内容区按钮）保持
### 第二阶段 — 口味匹配与详情页
- [x] R92. 口味匹配「本地规则 + 估计」混合，作品无标签也能算（重开 R80）— 已完成（本地规则部分）：有标签走标签重合；无标签/画像无标签统计时用「社区评分(归一化10分)相对你的平均分 + 类型」做低置信估计，文案标注「基于社区评分/类型粗略估计，仅供参考」；仅当连标签和社区评分都没有才显示「数据不足，无法计算」。AI 结构化增强（matchScore/likedReasons/riskReasons JSON）属新 Ai 任务管线，留待后续（见下）
- [ ] R97. 详情页各 Tab 真实数据（重开 R79/R90）— 未完成（下一轮从此继续）：简介需为 Work 增 summary 字段 + 非破坏迁移 v2→v3；角色Staff/关联作品/观看路线需接 Bangumi getSubjectCharacters/Persons/Relations（+AniList/Jikan/VNDB）并新增详情 UI 区块与 fetch-on-open
### 第三阶段 — 搜索合并与排序
- [ ] R95. 搜索合并/排序通用增强（重开 R75/R78）— 未完成（下一轮）：核心标题提取去 Episode/Season/Part/vol/括号/英文副标题；排序精确>原文>核心>热度/人数
### 第四阶段 — 公共发现数据源
- [ ] R98. 发现页公共数据源（重开 R84）— 未完成（下一轮）：AniList trending/season、Jikan top/season 端点 + DTO/mapper + 公共发现池
### 第五阶段 — 时光机重构
- [ ] R99. 时光机时间线重构（重开 R82/R83）— 未完成（下一轮）：概览卡 + 时间线主体；同步/快照移更多菜单；基线快照可展开按状态分组
### 第六阶段 — 设置二级页与首页模块
- [ ] R102. 设置二级页（重开 R88）— 未完成（下一轮）
- [ ] R103. 首页模块可配置（重开 R89，依赖 R98）— 未完成（下一轮）
### 收尾
- [ ] R104. 文档同步 — 部分完成：tasks.md 本节状态已更新；EXPERIENCE.md/DEVELOPMENT.md 待 R97+ 完成后一并补

---

> **历史归档说明**：以上「Runtime 验收反馈 第 3 / 4 / 5 / 6 / 7 / 8 轮」各章节自此**仅作为历史记录保留**，不再作为执行入口，其内部勾选状态可能与真机现状不一致（已知存在「标 [x] 但真机未达标」的历史矛盾）。
>
> **唯一执行入口** = 下方 **Final Runtime Stabilization Sprint**。后续不再新增「第 9 / 10 轮」碎片化章节；同类问题在本章只保留一个最终任务。

# Final Runtime Stabilization Sprint（最终真机验收收尾）

> 本章是当前唯一的执行入口与真机验收口径。状态规则（严格遵守）：
> - 仅当**真机验收通过**才写 `[x]`。
> - 部分完成 / 对用户仍不可用 → 一律写 `[ ]`，并在条目内注明「已有基础」与「仍缺什么」。
> - 禁止出现「`[ ]` 但文字写已完成」的矛盾状态。
> - 不补 optional 测试，除非用于验证本章核心 bug。
> - 执行需按下方「执行顺序与依赖」分阶段进行，**不可一股脑并行**；可在阶段内 `compileDebugKotlin` 自检，**全部 P0+P1 完成后再 assembleDebug**。

## 当前唯一未完成任务索引（按执行顺序）

P0（阻塞体验 / 数据可见性 / 核心数据链路）：
1. `[ ]` F1 全局页面顶部遮挡 / 滚动裁切最终修复 —（代码已实现 · compile 通过 · 待真机验收）
2. `[ ]` F4 信息架构：我的库 / 待补池 / 吃灰区 / 已看列表 —（代码已实现 · compile 通过 · 待真机验收）
3. `[ ]` F2 我的库 / 我的收藏页面与入口（Bangumi 同步内容可见）—（代码已实现 · compile 通过 · 待真机验收）
4. `[ ]` F3 时光机真正显示同步内容与本地快照 —（代码已实现 · compile 通过 · 待真机验收）
5. `[ ]` F5 搜索合并最终修复（合并 + source links + 多源评分融合 + 排序）—（代码已实现 · compile 通过 · 待真机验收）
6. `[ ]` F6 一键加入待补池优先识别动画 —（代码已实现 · compile 通过 · 待真机验收）
7. `[ ]` F7 详情页完整内容（简介 / 角色·Staff / 关联 / 路线 / 平台数据 / 我的记录）—（代码已实现 · compile 通过 · 待真机验收：Work.summary+迁移v3 已就绪，简介取 summary，角色/Staff/关联/路线接 Bangumi 端点，路线不确定显示「路线待确认」）
8. `[ ]` F9 口味画像标签来源修复（读作品标签而非仅自定义标签）—（进行中）
9. `[ ]` F8 社区共识 / 可能会喜欢 / 补番优先级模型（依赖 F7 + F9）—（代码已实现核心 · compile 通过 · 待真机验收：稳定度/争议度改为独立模型；新增可解释补番优先级；可能会喜欢本地模型可用。AI「分析匹配度」按钮已接入：详情页决策助手内点击 → loading → 结构化结果（matchScore/likedReasons/riskReasons/confidence/dataSources）写 AI_RESULT 缓存，未配置/失败回退本地模型，页面不无响应）

P1（结构性补齐）：
10. `[ ]` F10 发现页本季榜单 / 评分差异 / 筛选公共数据源 —（代码已实现 · compile 通过 · 待真机验收：Jikan top/season 公共端点 + AniList trending（TRENDING_DESC）公共端点；loadPublicDiscovery 合并 Jikan + AniList 写入作品与多源评分；进入榜单/评分差异自动加载，含 loading/错误重试。AniList 简介 description 也已接入 Work.summary）
11. `[ ]` F11 首页模块可配置（依赖 F10 公共池）—（代码已实现 · compile 通过 · 待真机验收：新增首页模块偏好（DataStore）+「首页模块」设置页（首页右上角入口）；首页按偏好显隐模块 + 新增「近期热门/本季」模块（取自 F10 公共池）。模块偏好独立页，未塞进主设置页）
12. `[ ]` F12 设置二级页 —（代码已实现 · compile + assembleDebug 通过 · 待真机验收：主设置页改为各源摘要行（名称+状态+最后测试+进入），点击进入二级编辑页（复用原卡片，强制展开）；Bangumi 二级页保留 API 地址下拉/Token 风险确认/连接测试，底层逻辑未改）

> 状态汇总（本轮）：F1–F12 全部「代码已实现 + compile + assembleDebug 通过」，等待真机验收后才改 `[x]`。AI 结构化「分析匹配度」按钮（F8 增强项）与 AniList trending（F10 增强项）为后续小增强。

元任务：
- `[x]` F13 tasks.md 重复 / 矛盾状态清理（本次完成：建立本统一章节 + 历史归档说明 + 唯一索引）

> 旧轮次与本章的对应（去重映射，便于追溯，不再单独执行）：
> - F1 ⇐ R9/R29/R38/R39/R61/R74/R81/R96
> - F2/F4 ⇐ R24/R48/R94（数据已入库）+ 新增「我的库」可见性
> - F3 ⇐ R7/R25/R46/R62/R63/R82/R83/R99
> - F5 ⇐ R2/R20/R42/R64/R65/R75/R76/R77/R78/R95（合并 + 排序 + 融合统一为一项）
> - F6 ⇐ 新增（一键加入类型优先级）
> - F7 ⇐ R3/R23/R44/R70/R79/R90/R97
> - F8 ⇐ R92/R80 + 新增共识/优先级模型
> - F9 ⇐ R68 + 标签来源修复
> - F10 ⇐ R22/R33/R41/R52/R69/R84/R98
> - F11 ⇐ R17/R32/R51/R72/R89/R103
> - F12 ⇐ R31/R49/R59/R71/R88/R102

## 执行顺序与依赖（不可一股脑并行）

- 阶段 A（独立，先做）：**F1**。纯布局，不依赖数据层。
- 阶段 B（信息架构地基）：**F4** → 之后 **F2**、**F3** 才能做（F2/F3 都依赖「我的库」概念与 user_collections 查询）。
- 阶段 C（搜索/导入链路）：**F5** → **F6**（F6 的类型优先级作用于搜索/导入结果）。
- 阶段 D（详情与模型）：**F7** → **F9** → **F8**（F8 的「可能会喜欢/优先级」依赖 F7 拉到的简介/标签与 F9 修复后的标签来源）。
- 阶段 E（P1 结构）：**F10** → **F11**（首页模块复用 F10 公共池）；**F12** 可与 E 其它项独立但排在 P0 之后。

## P0 任务明细

- [ ] F1. 全局页面顶部遮挡 / 滚动裁切最终修复
  - 真机现状：我的页/设置页/口味画像页首张卡片仍贴近标题栏，内容像「少了一截」；根因在内容区起始位置（contentPadding / innerPadding），不是标题栏本身。
  - 已有基础：已建 inset 单一来源模型（外层 Scaffold 消费系统栏 + consumeWindowInsets）与 `AcgScreenScaffold`/`ScreenContentPadding`（R96），但各页尚未统一接入该 padding 规范，仍有贴顶。
  - 要求：不改标题栏；统一各页内容区顶部留出 16–24dp 呼吸空间（用 `ScreenContentPadding.lazyList(innerPadding)` 或等价 contentPadding）；所有页可完整滚到底、不被底栏遮挡。
  - 覆盖页：MineScreen / SettingsScreen / TasteProfileScreen / TimeMachineScreen / DetailScreen / BacklogScreen / DiscoverScreen / ImportScreen / AboutScreen。
  - 验收：每页首张卡/封面在标题栏下方有明显间距，最后一项不被底部导航吃掉。

- [ ] F4. 信息架构：我的库 / 待补池 / 吃灰区 / 已看列表（F2、F3 前提）
  - 定义并在 UI 落地以下边界：
    - 我的库：全部同步 / 本地记录的作品（想看 / 在看 / 看过 / 搁置 / 抛弃），来源 user_collections（+本地）。
    - 待补池：用户主动加入、准备以后补的作品（backlog）。
    - 吃灰区：待补池中长期未动的作品，是待补池的一个筛选 / 分区。
    - 已看列表：我的库中「看过」状态，不属于待补池。
  - 要求：看过列表不得与待补池混在一起；想看/在看可手动「加入待补池」；看过默认不进待补池但在我的库可见。
  - 详情页需显示该作品当前归属：我的库状态 / 是否在待补池 / 是否在吃灰区。
  - 验收：四个概念有明确入口与查询，互不混淆。

- [ ] F2. 我的库 / 我的收藏页面与入口（Bangumi 同步内容可见）
  - 真机现状：只能看到「同步了多少」，看不到同步进来的条目列表。
  - 要求：新增「我的库 / 我的收藏」页面；支持列表分组：想看 / 在看 / 看过 / 搁置 / 抛弃 / 全部；每条显示封面、标题、类型、年份、我的状态、我的评分、进度、来源（Bangumi）。
  - 我的页「个人数据同步」卡片下增加入口：查看我的收藏 / 在看 / 看过 / 想看。
  - 文案解释清楚「收藏入库」= 把 Bangumi 收藏/评分/进度/短评同步到本地 user_collections。
  - 验收：同步后能浏览到具体作品，不再只有数字。

- [ ] F3. 时光机真正显示同步内容与本地快照
  - 要求：首次同步 Bangumi → 生成初始快照 + 页面显示「导入了 N 部收藏」+ 时间线出现同步事件；后续同步状态/评分/进度/短评变化 → 生成 change log。
  - 「拍快照（本地）」必须可点击；有 user_collections 即可拍；无可快照数据要提示原因。
  - 验收：不允许只显示数量而时间线为空；基线快照可展开看作品（可按状态分组）。

- [ ] F5. 搜索合并最终修复（合并 + source links + 多源评分融合 + 排序）
  - 合并：同一作品跨 Bangumi/AniList/Jikan/MAL/VNDB 只显示一张卡；合并后保存 source links；多源评分 entries 挂到同一 canonical work；详情页能看到多平台评分。
  - 强匹配：原文标题一致=强匹配；原文 + 年份 + 类型一致=自动合并；中/日/英/罗马音/别名互相命中；符号与数字归一（9nine / 9-nine / 9 -Nine-；2.5 / 2.5次元 / 2.5 Jigen）。
  - 防误合并：系列总称不与具体分作合并；用 subtitle/episode/原文/年份/类型/source links 综合判断，不只看前缀。
  - 排序：精确匹配优先；高匹配时按热度/收藏数/评分人数排序；源可信度仅作辅助，不得让低热度高优先级源压过高热度准确条目。
  - 手动纠正：普通列表默认不显示「手动纠正」；仅低置信冲突/疑似重复/「管理匹配模式」时显示。
  - 自测样例（回归，非硬编码）：2.5 / 2.5次元 / 2.5次元的诱惑 / 9nine / 9-nine / 9-nine 新章 / 9-nine- Shinshou / 9-nine- 天色天歌天籁音 / 9-nine- Sorairo Sorauta Soranooto / 9-nine-九次九日九重色。

- [ ] F6. 一键加入待补池优先识别动画
  - 未指定类型时优先级：动画/TV/OVA/Movie → 游戏/VN → 漫画/小说。
  - 同名多类型时弹出选择，不静默加入错误类型；搜索/导入结果卡片明显显示类型；确认列表可切换类型；批量导入同样应用该规则。

- [ ] F7. 详情页完整内容
  - 必须补齐：简介 / 评论摘要 / 角色·Staff / 关联作品 / 观看路线 / 平台数据 / 我的记录 / 加入移出待补池 / 加入我的库或修改状态。
  - 数据源：Bangumi（简介/关联/角色·Staff）、VNDB（简介/标签/角色/制作社）、AniList/Jikan（英文简介/staff/评分补充）。
  - 需为 Work 增 summary 字段 + 非破坏迁移（v2→v3，ADD COLUMN）；各源 characters/staff/relations 端点接入 + fetch-on-open 写 Room。
  - 缺失字段显示「暂无数据」，但 Tab 与区域结构必须存在。

- [ ] F9. 口味画像标签来源修复（依赖 F7 的作品标签拉取）
  - 高分/低分倾向标签来自用户高分/低分作品的**作品标签**（Bangumi subject tags / AniList genres·tags / VNDB tags），自定义标签可参与但非唯一来源。
  - 标签清洗：下划线转自然显示、去技术字段、合并同义、尽量可读名称；作品标签未拉取时在同步/详情刷新补拉。
  - 低分样本不足显示「低分样本不足」（非笼统「暂无数据」）；口味黑洞说明生成条件。

- [ ] F8. 社区共识 / 可能会喜欢 / 补番优先级模型（依赖 F7 + F9）
  - 社区共识：稳定度与争议度**不得简单互补相加到 100**。稳定度基于评分人数 + 多源一致性 + 方差/标准差 + 源数量；争议度基于多源差异 + 标签/评论情绪分歧；样本不足显示「样本不足」。
  - 补番优先级：综合口味匹配 + 社区评分 + 热度/人数 + 补完成本 + 是否完结 + 情绪风险 + 当前状态 + 是否在待补池/吃灰天数 → 低/中/高 + 理由。
  - 可能会喜欢：本地模型（高分标签相似度 + 低分避雷标签 + 类型偏好 + 评分习惯 + 社区评分兜底）先可用；AI 增强为可选：点击「AI 分析匹配度」显示 loading，AI 返回结构化 JSON（matchScore/likedReasons/riskReasons/confidence/dataSources）并写缓存，失败回退本地模型，页面不得无响应。

## P1 任务明细

- [ ] F10. 发现页本季榜单 / 评分差异 / 筛选公共数据源
  - 本季榜单接公共 API：AniList trending/season、Jikan top/season、Bangumi 热门/排名或公共搜索 fallback。
  - 评分差异：从多源评分作品生成；无候选时提供「加载公共候选」按钮。
  - 筛选：可筛公共发现池 / 我的库 / 待补池，UI 显示当前筛选范围；无 token 也能显示公共内容；网络失败显示错误卡片 + 重试，不空白。

- [ ] F11. 首页模块可配置（依赖 F10）
  - 设置增加首页展示偏好：今日决策 / 近期热门 / 本季榜单 / 适合你的 / 随机抽番 / 待补池概览 / 继续看玩读。
  - 首页 = 今日决策与推荐入口；待补池 = 完整管理；两者不重复。

- [ ] F12. 设置二级页
  - 主设置页仅摘要卡；每个源（Bangumi/AniList/Jikan/MAL/VNDB/AI Provider）进二级页放 Token/Base URL/模型/Client ID/测试连接/文档入口。
  - 保留 Bangumi API 地址选择器（官方/社区候选/自定义 + 非官方 Token 风险确认）；所有 Bangumi 功能统一使用该 Base URL（勿回退已通过验收的 R55–R67 逻辑）。

## 本章完成后输出要求
- Final Sprint 每项状态（[x]/[ ]）。
- 改动文件列表；新增/修改的数据表、DAO、迁移。
- 搜索合并与排序模型说明。
- 社区共识 / 可能会喜欢 / 补番优先级模型说明。
- 我的库 / 待补池 / 已看列表 / 吃灰区信息架构说明。
- APK 路径；仍无法完成的内容与原因。


### 参考项目补充要求 / 验收补充（本轮，归入既有 F 项，不新开轮次）

> 参考（仅借鉴架构/交互/算法/信息组织，不复制代码；结论记 DEVELOPMENT.md「Reference Review」）：
> xiaoyvyv/bangumi、czy0729/Bangumi（Timeline/Time Machine/排行/每日放送/年鉴/标签）、open-ani/animeko（数据源/缓存/分层/同步）、Anikku·Aniyomi（多 tracker 同步/库管理/状态筛选）、AnimeMate·Sprout·MyAnimeReport（评分历史/标签偏好/推荐解释/KNN·Slope One）、MZZB Score·anime-rating-db（多站评分聚合/跨站 ID 映射/标准化）。

- A（F5/原 11·12·13·21）搜索：canonical work + source links + 多源评分融合（非仅 UI 合并）；原文一致强匹配、原文+年份+类型自动合并；9-nine 系列正确合并/防总称误并；排序含精确/原文/核心/热度/人数/收藏；手动纠正仅管理模式/低置信。
- B（F7/原 19·24·27）详情：简介/评论摘要/角色·Staff/关联/观看路线/平台数据/我的记录；Bangumi+VNDB+AniList/Jikan 字段；缺失「暂无数据」但结构在。
- C（F3/原 28）时光机：导入事件+初始快照+状态/评分/进度/短评变化；按时间/状态分组；本地与云端同一时间线；我的库/待补池/已看/吃灰变化可记录。
- D（F4/F2/原 18·29）信息架构：我的库/待补池/吃灰区/已看列表入口与归属。
- E（F8/F9/原 25·26）口味/推荐：标签来自作品标签（Bangumi/AniList/VNDB/Jikan）+ 清洗；本地可能会喜欢可用；稳定度/争议度独立；补番优先级综合；AI 匹配为增强按钮（失败回退本地）。
- F（F10/F11/原 20·21）发现/首页：Jikan top/season（可加 AniList trending）；榜单/评分差异/筛选有公共数据；首页模块化。
- G（F12/原 14）设置二级页：摘要卡 + 各源二级页；Bangumi 地址选择器保留。
- H（归入 F7 观看路线 / 原 27）观看路线 Tab：可点击作品卡列表，按发布时间排序，含 TV/续作/前传/OVA/剧场版/总集篇/特别篇；「智能选择主线」按本地 relation type 分主线/可选/可跳过；AI 仅排序已知关联不编造；一键加入「主线必看」/「全系列」到待补池。
- I（F6/原 17·18）导入类型优先级：默认动画；同名多类型且置信接近时弹选择；确认列表可切换类型；结果显示类型。
- J 文档/少量测试：DEVELOPMENT.md Reference Review；EXPERIENCE.md 根因；关键单测（搜索合并/导入类型优先级/时光机快照）。


### B/E/F/I 续推进度（本轮，归入既有 F 项，不新开轮次）

> 状态规则同上：仅真机验收通过才 `[x]`；以下为「代码完成 + compile/test 通过 + 待真机验收」。

- B（详情多源数据，归 F7）—（代码已实现 · compile 通过）：Jikan `synopsis` / VNDB `description` / AniList `description`（清理 HTML）均映射到 `Work.summary`，详情「简介」Tab 可显示三源简介；Bangumi summary（迁移 v3）已就绪。缺字段仍「暂无数据」。
- E（AI 分析匹配度按钮，归 F8）—（代码已实现 · compile 通过）：新增 `AiTaskType.TASTE_MATCH` + `TasteMatchOutput`(matchScore/likedReasons/riskReasons/confidence) + `AiTask.TasteMatch` + 提示词/schema；详情页决策助手内「AI 分析」按钮 → `AiMatchUi`(Idle/Loading/Result/NotConfigured/Error)；点击经 `AiEngine.run(TasteMatch, confirmed=true)`，成功展示结构化结果（写 AI_RESULT 缓存），未配置/低置信/失败回退本地模型并提示，页面不无响应。
- F（AniList trending，归 F10）—（代码已实现 · compile 通过）：`AniListQueries.TRENDING_NOW`(TRENDING_DESC) + `AniListApi.getTrending` + `AniListRemoteDataSource.getTrending`(返回 Work+RatingEntry?)；`loadPublicDiscovery` 合并 Jikan(season/top) + AniList trending 写入公共发现池与多源评分；任一源失败不致命，全失败才报错可重试。
- I（导入类型优先级，归 F6）—（代码已实现 · compile + 单测通过）：抽出纯函数 `domain/matching/MediaTypePriority`（`mediaTypePriority` / `sortMatchesByTypePriority` / `hasAmbiguousMediaType`）；`ImportRepositoryImpl` 改用共享排序（动画 > 游戏/VN > 漫画/小说）；新增 `MediaTypePriorityTest`（验证番剧名同置信不默认落漫画、显著高置信仍按置信、同名多类型置信接近判为歧义应弹选择）。
  - 仍待真机/后续：导入确认列表「类型切换」与「同名多类型弹选择」的 UI 交互接线（`hasAmbiguousMediaType` 已提供判定基础），单独排期。

> 参考核验诚实声明：本轮未实际联网打开 GitHub 参考仓库；AniList trending/description、Jikan、VNDB 字段均依据各源**官方 API 文档**核验落地（见 DEVELOPMENT.md），参考项目仅作架构/交互/信息组织方向，未复制代码。


## Final Sprint · G 轮（真机验收 + 参考强化，归入唯一执行入口，不新开 Runtime 轮次）

> 本节是「Final Runtime Stabilization Sprint」的延续，仍是唯一执行入口。状态规则不变：仅真机验收通过才 `[x]`。所有项均映射到既有 F 项或为其细化，不重复建项。

### Reference Review（本轮实际核验情况，诚实声明）

- **实际联网读取（README/文档）**：
  - `kisekinoumi/mzzbscore`（README 已读）：多站评分聚合经验 → 评分标准化（AniList 百分制→十分制）、**交叉验证**（一站搜索失败用另一站返回的日文名重试、再用英文名重试）、按年份匹配、并发抓取、链接优先/搜索兜底。落地：F5 搜索合并 + 评分聚合的跨源标题回退与归一。
  - `Ameobea/sprout`（README 已读）：推荐为「去噪自编码器」，按用户评分共现加权（两部都高分→边权增大）、留一法解释推荐贡献。落地：F8 本地口味模型——用「近期评分作品的标签/类型共现 + 高分加权」做相似度，解释「因为你给 X 高分」。
  - `Shuvam-Banerji-Seal/AnimeMate`（README 已读）：Twitter 式推荐——内容-用户亲和（题材匹配学习权重）、时间衰减（加权在播/新作）、多样性注入（同题材占比上限 40% 防信息茧房）、社区分作社会证明、负反馈降权、80/20 探索/利用、已看排除；MAL v2 `PATCH /my_list_status` **双向同步**。落地：F8 今晚看什么决策 + 补番优先级（多样性/时间衰减/已看排除/负反馈）。
  - `open-ani/animeko`（README 已读）：以 Bangumi 为核心数据层、云同步收藏进度、**新番时间表**（Bangumi + 服务端）、标签搜索、缓存。落地：F10 榜单/时间表用 Bangumi、F7 详情、数据层缓存。
- **未实际核验（HTML 未能提取，诚实标注）**：`czy0729/Bangumi`、`xiaoyvyv/bangumi` 的源码页面本轮未成功联网读取；其 Bangumi API 用法以**本项目已实现的 Bangumi `/v0/` 接口**与官方 v0 规范为准（收藏修改 `POST /v0/users/-/collections/{subject_id}`、进度 `PATCH .../episodes`、`/calendar` 每日放送）。后续若需深入再单独核验，绝不臆造其源码细节。

> **订正（用户已下载源码到本地 `参考项目文件/`，本轮已实际读取）**：
> - `xiaoyvyv/bangumi`（Kotlin，已读源码）：收藏编辑走网页表单 `POST /subject/{id}/interest/update`（FieldMap：interest/rating/comment/tags/privacy）、单集进度 `POST /subject/ep/{epId}/status/{type}`、整季进度 `POST /subject/set/watched/{id}`；登录用 OAuth token（`authToken` code/refresh）。
> - `czy0729/Bangumi`（RN/TS，已读源码）：v0 收藏读 `GET /v0/users/{user}/collections/{subjectId}`、章节进度 `PATCH /v0/users/-/collections/{subjectId}/episodes`、ep 状态 `POST` `API_EP_STATUS`；收藏字段 type/rate/ep_status/vol_status/comment/private/tags。
> - **本项目采用**：官方 v0 **token** 路径 `POST /v0/users/-/collections/{subject_id}`（upsert：type/rate/ep_status/vol_status/comment/tags），与现有 token 鉴权一致，避免网页 cookie 抓取。已据此实现 G8 写回。

### G 轮任务（按执行顺序，全部完成后再 assembleDebug）

- [ ] G1. 全局滚动区顶部被标题栏遮挡（重开 F1/R96/R74）：凡顶部有 Tab/标题栏的页面（发现页搜索/榜单/筛选、我的页等），滚动内容顶端仍被遮住一块。根因在内容区起始 padding 与 TopAppBar/TabRow 高度叠加。统一：内容区 `contentPadding` 顶部 = innerPadding.top + 额外呼吸，不让首项贴在 Tab 下沿。覆盖发现页四个 Tab 内容、我的页、详情页。
- [ ] G2. 搜索跨源合并仍漏合并（重开 F5/R64/R65/R75）：图示「9-nine- 新章(Bangumi·游戏)」与「9-nine- Shinshou(VNDB·视觉小说)」实为同一作品却两张卡。需：原文/罗马音互通（Shinshou↔新章）、游戏↔视觉小说类型视为可合并的同族（不因 GAME/VN 类型差异阻止合并）、合并后并卡并融合 source links 与多源评分。
- [ ] G3. 隐藏普通搜索结果「手动纠正」（重开 F5/R21/R43）：默认完全不显示「手动纠正」；仅「管理匹配模式」内显示。卡片底部不再挂手动纠正按钮。
- [ ] G4. 发现页「本季榜单 / 评分差异」可加载（重开 F10/R69/R98）：本季榜单默认用 **Bangumi**（`/calendar` 每日放送聚合本季 + 条目详情），评分差异基于多源评分（Bangumi+其他源聚合）。无内容时「加载公共候选」，loading/失败重试。主页「近期热门/本季」也改为以 Bangumi 为主，Jikan/AniList 作补充，不再全是 Jikan。
- [ ] G5. 发现页「筛选」有内容（重开 F10）：筛选作用于公共发现池（Bangumi 本季/热门 + 已缓存多源）+ 我的库 + 待补池，标注当前范围；类型/年份/评分/状态等筛选项可用。
- [ ] G6. 时光机时间线视图（重开 F3/R82/R99）：从上往下一条时间线，显示「何时看了什么番 + 当时评分 + 当时评价」；按时间分组，基线快照可展开。数据来自 user_collections + change log。
- [ ] G7. 时光机操作按钮整理（重开 F3）：移除无用的「拍快照」主按钮（或移入「更多」菜单），「从 Bangumi 导入/同步」放到合适位置（顶部操作区）。
- [ ] G8. 本地「我的记录」编辑并回写 Bangumi（重开 F7/RC.13）：详情页「我的记录」可编辑 状态/评分/进度/短评/标签，保存即写 user_collections 并经 `POST /v0/users/-/collections/{subjectId}`（进度 episodes 接口）回传 Bangumi；本地先写、后台同步、失败可重试并提示。新增 Bangumi 写接口 + 冲突策略。
- [ ] G9. 番剧本地状态标注基于 Bangumi（重开 F4/F2）：列表/详情可直接标注「想看/在看/看过/搁置/抛弃」，与 G8 共用写回链路。
- [ ] G10. AI 分析匹配度输入升级（重开 F8/E）：不再只用「我的标签」；改为取**近期 10–20 部已看/已评分作品 + 当时评分与短评**作为 AI 输入，更具参考价值。
- [ ] G11. 口味匹配本地算法升级（重开 F8/F9）：参考 sprout（评分共现加权 + 留一解释）与 AnimeMate（题材亲和 + 负反馈 + 时间衰减 + 多样性）。本地默认算法产出更准的匹配度与「因为你喜欢 X」解释；不精准问题改善。
- [ ] G12. 详情页「口味匹配度」改为可触发 AI 的单一区块（重开 F8/E）：默认显示内置算法数值；**移除**下方独立的「AI 分析匹配度」整条；在「口味匹配度」区块内加一个「AI 分析」按钮，点击后给出 AI 数值 + 简短评价（loading/失败回退本地）。
- [ ] G13. 详情页「我的记录」数据源统一为 Bangumi（重开 F7/R44）：状态/评分/进度/短评/标签全部基于 Bangumi（user_collections），不用其他源；与 G8 编辑链路一致。
- [ ] G14. 观看路线去重 + 仅番剧（重开 F7/H）：详情页「观看路线（智能选择主线）」与底部详情 Tab 的「观看路线」二选一保留一个；路线**仅展示动画**；游戏/画集/广播剧等不进「可选/可跳过」，归为「衍生作 / 原作」关系单独标注。
- [ ] G15. 今晚看什么决策增强（重开 F11/R??）：参考 sprout/AnimeMate——基于口味画像 + 候选池（Bangumi 本季/热门 + 待补池）+ 时间衰减 + 多样性 + 已看排除，给稳妥/赌一把/神经病三推荐与理由。
- [ ] G16. 数据源职责梳理（贯穿）：榜单/本季/筛选/番剧详情/我的记录/编辑回写默认 **Bangumi**；评分差异与「多平台评分」用搜索聚合 + 评分聚合（参考 mzzbscore 归一与交叉验证）补充 AniList/Jikan/MAL/VNDB；公共发现池不再全是 Jikan。
- [ ] G17. 文档同步：DEVELOPMENT.md 记录新增 Bangumi 写接口（collection 修改/进度）、calendar 榜单用法、评分聚合归一/交叉验证、口味/推荐算法来源；EXPERIENCE.md 记录本轮根因（漏合并/遮挡/榜单空/我的记录只读）。

> 与既有 F 项映射（去重）：G1⇐F1；G2/G3⇐F5；G4/G5/G16⇐F10；G6/G7⇐F3；G8/G9/G13⇐F7+F4+F2；G10/G11/G12/G15⇐F8/F9；G14⇐F7(H)。无新建重复编号。


### G 轮实现进度（本轮，compile 通过 · 待真机验收）

- [ ] G2 搜索跨源合并 —（代码已实现 · compile + 单测通过）：CrossSourceMerge 改为媒介家族判定（GAME↔VN、MANGA↔NOVEL 同族不算冲突，动画自成一类），9-nine 游戏/视觉小说可并卡。
- [ ] G3 隐藏「手动纠正」—（代码已实现 · compile 通过）：普通搜索结果不再显示，仅「管理匹配」模式出现；提示文案改为引导管理匹配。
- [ ] G8/G9/G13 我的记录编辑并回写 Bangumi —（代码已实现 · compile 通过 · 待真机验收）：新增 `BangumiUpdateCollectionRequest` + `BangumiApi.updateUserCollection`(`POST /v0/users/-/collections/{id}`) + `BangumiRemoteDataSource.updateUserCollection`；详情页「我的记录」新增「编辑我的记录」对话框（状态/评分/进度/短评），保存先写本地 user_collections 再回传 Bangumi，未配置/失败有提示，数据源统一 Bangumi。
- [ ] G12 口味匹配度内嵌 AI 按钮 —（代码已实现 · compile 通过）：删除独立「AI 分析匹配度」条，AI 按钮移入「口味匹配度」标题行，结果内嵌展示。
- [ ] G14 观看路线去重 —（代码已实现 · compile 通过）：移除重复的「观看路线」文字 Tab，保留结构化「观看路线（智能选择主线）」。仍待：路线仅限动画 + 游戏/画集/广播剧归类为衍生作/原作（需关联条目媒介类型，单独排期）。

### G 轮仍未完成（下一阶段继续，按序）

- [ ] G4/G16（部分完成 · compile 通过）：公共发现池已改为**Bangumi 优先**——`GET /calendar` 每日放送作为本季/近期热门主源最先写入，Jikan top/season + AniList trending 作补充（用于评分聚合/差异）。主页「近期热门/本季」不再全是 Jikan。仍待真机验证榜单/评分差异/筛选 UI 是否据此填充。
- [ ] G5 筛选作用于公共池+我的库+待补池（待真机验证 facets 是否随 Bangumi 池填充）。
- [ ] G6/G7 时光机时间线视图 + 操作按钮整理。
- [ ] G10/G11/G15 AI 输入改用近期评分作品；本地口味/推荐算法参考 sprout（共现加权+留一解释）/AnimeMate（题材亲和+负反馈+时间衰减+多样性+已看排除）重写；今晚决策增强。
- [ ] G1 各页滚动区顶部留白（防被 Tab/标题栏遮挡）。
- [ ] G17 文档同步（DEVELOPMENT.md 写接口/榜单/评分聚合/算法来源；EXPERIENCE.md 根因）。


### G 轮第二批实现进度（本轮 · compile + assembleDebug 通过 · 待真机验收）

- [ ] G6 时光机时间线 —（代码已实现）：时间线每条显示日期 + 作品 + 当时变化；短评变化直接展示当时写下的内容（截断），不再笼统「短评有更新」。
- [ ] G7 时光机按钮整理 —（代码已实现）：移除无效的「拍快照（本地）」按钮，保留「从 Bangumi 同步收藏」单按钮（同步中显示「同步中…」）。
- [ ] G10 AI 匹配输入升级 —（代码已实现）：详情 AI 分析改为纳入近期最多 20 部已评分作品（标题·当时评分·短评）+ 口味画像 + 社区评分，比仅用标签更具参考价值。
- [ ] G11 本地口味匹配算法升级 —（代码已实现）：参考 AnimeMate——负信号（低分标签）按 1.5 倍降权 + 社区评分社会证明加权融合（亲和 0.7 + 社区 0.3），缓和「标签太少」的极端值；理由文案更具体。
- [ ] G14 余项 观看路线仅番剧 —（代码已实现）：关联作品按 Bangumi `type` 过滤，非动画（游戏/画集/广播剧/三次元等）归入新「衍生作 / 原作（非动画）」分区，不再混进可选/可跳过。
- [ ] G1 顶部留白 —（代码已实现 · 待真机验收）：统一内容区顶部留白由 16dp 增至 24dp（ScreenContentPadding.Top），缓解贴顶/被标题栏遮挡。

### G 轮仍待办（下一阶段）

- [ ] G5 发现筛选 facets 随 Bangumi 公共池填充（待真机验证）。
- [ ] G15 今晚看什么决策增强（候选池+时间衰减+多样性+已看排除）。
- [ ] G13 余项 我的记录「标签」编辑（当前编辑覆盖状态/评分/进度/短评）。
- [ ] G16 余项 评分聚合交叉验证（mzzbscore：用对方日文/英文名重试）。
- [ ] 详情「评论摘要」数据源接入。
- [ ] G17 文档同步（DEVELOPMENT.md/EXPERIENCE.md）。


### G 轮第三批进度（本轮 · compile 通过）

- [ ] G13 我的记录「标签」编辑 —（代码已实现）：编辑对话框新增「标签（空格分隔）」字段，保存随状态/评分/进度/短评一并写本地 + 回传 Bangumi（tags）。
- [ ] G16 评分差异排序 —（已确认正确）：`buildScoreDiffBoard` 按跨源归一化差距降序（差距大排前面），<1.5/10 不入榜。已知限制：需同一规范作品挂 ≥2 源评分；搜索合并后可融合，公共池条目跨源合并待补（见下）。
- [x] G17 文档同步（DEVELOPMENT.md 写接口/榜单/评分差异/算法来源已更新）。

### G 轮最终仍待办（需较大改动，单独排期）

- [ ] G15 今晚看什么决策增强（候选池 + 时间衰减 + 多样性 + 已看排除，参考 sprout/AnimeMate；属 BacklogRepository.draw 抽番逻辑较大改动）。
- [ ] 详情「评论摘要」数据源接入（Bangumi/AniList reviews）。
- [ ] 评分差异公共池 cross-source 去重真机验证（避免同作多卡）。


### G 轮评分差异（本轮 · compile + assembleDebug 通过）

- [ ] 评分差异公共池跨源融合 —（代码已实现）：`loadPublicDiscovery` 改为收集 Bangumi 每日放送 + Jikan + AniList 全部命中后**一次性** `persistMatches`，跨源按标题聚类把多平台链接指向同一代表作品；`buildScoreDiffBoard` 按跨源归一化差距降序（差距大排前面）。待真机验证池内去重与多源评分聚合效果。


## Final Sprint · H 轮（真机验收第 N 次 · 唯一执行入口延续，不新开 Runtime 轮次）

> 状态规则不变：仅真机验收通过才 `[x]`。所有项映射到既有 F/G 或为其细化，不重复。实现前必先读相关源码与 `参考项目文件/` 中两个 Bangumi 客户端的 API 用法，不盲改。

### H1. 顶部遮挡仍在——更激进下移可滑动区顶部
- 现状：内容区顶部已留 24dp，但真机仍被标题栏遮一点点（只露出一点）。
- 要求：进一步加大顶部留白（激进，如 `ScreenContentPadding.Top` → 40–48dp，或在外层 Scaffold/TopAppBar inset 上再叠加），确保首张卡完整露出、明显在标题栏下方。覆盖首页/发现/我的/待补池/时光机/详情/设置/口味画像。

### H2. 首页「近期热门 / 本季」仍全是 Jikan，无 Bangumi
- 现状：`loadPublicDiscovery` 虽已接 Bangumi `/calendar`，但首页「近期热门/本季」模块展示的仍全是 Jikan 条目。
- 排查：HomeViewModel / HomeContract 该模块的数据来源（是否只取 Jikan 源、或排序把 Bangumi 排后）。要求该模块**以 Bangumi 为主**展示。

### H3. 发现页「本季榜单 / 评分差异 / 筛选」依旧全空
- 现状：三个 Tab 真机仍无内容。
- 本季榜单：**只显示 Bangumi 即可**（用 `/calendar` 或 Bangumi 排行；API 用法参考 `参考项目文件/bangumi-main`(xiaoyvyv) 与 `参考项目文件/Bangumi-master`(czy0729)）。
- 评分差异：需同一作品挂 ≥2 源评分（依赖跨源合并）；排查 `worksWithRatings`→`buildScoreDiffBoard` 为何空（可能 observeWorks 没返回 calendar 写入的作品，或 aggregateRatings 不产生多源）。
- 筛选：facets 要能筛出动画（至少 Bangumi 来源）；排查 buildFilterFacets/applyFilter 为何空。

### H4. 待补池 ↔ Bangumi 状态机同步
- 加入待补池 → 默认标记 **想看**（type=1）并回写 Bangumi。
- 标记看到第 1 集（进度>0 且未完结）→ 状态自动 **在看**（type=3）。
- 看完（进度≥总集数）→ 状态自动 **看过**（type=2）。
- 进度**不得超过番剧总集数**（clamp 到 eps）。
- 反向：Bangumi 的「想看 / 在看」收藏同步进我们的待补池（看过不进待补池）。
- 参考 Bangumi 进度接口：`PATCH /v0/users/-/collections/{id}/episodes` 或收藏 `ep_status` 字段（见 czy0729 `API_EP_STATUS` / xiaoyvyv `/subject/ep/.../status`）。

### H5. 待补池「吃灰池」自动搁置
- 待补池内新增「吃灰池」功能：放入吃灰池的番剧自动进入 **Bangumi 搁置**（type=4）并回写。

### H6. 时光机历史时间线（不止基础快照）
- 现状：只有一条基础快照，看不到以前看过的番。
- 要求：把历史收藏（user_collections，含 Bangumi 同步来的 updatedAt/评分/短评）渲染成时间线条目：时间、番剧名、评分、评价。即使没有 change log，也要按 user_collections 的时间生成「历史时间线」。

### H7. 设置「自动同步间隔」
- 设置页加「自动同步时间」选项（如默认关闭 / 1500 分钟等可选），到点自动触发 Bangumi 同步（WorkManager 周期任务）。

### H8. 口味匹配度 AI 分析改为「替换匹配度数值」
- 现状：点 AI 分析后另给一个置信度。
- 要求：AI 分析完成后，用 AI 的 matchScore **替换**「口味匹配度」主数值（顶替内置算法的数值），下方仍展示 AI 的简短评价（likedReasons/riskReasons）。不再单独显示置信度作为主体。

### H9. 详情页「评论摘要」接入数据源
- 现状：评论摘要 Tab 仍「暂无数据」。
- 接 Bangumi 短评/评论或 AniList reviews，展示评论摘要（无 AI 时可做规则摘要；有评论就展示）。

### H10. 详情页缓存（先缓存后刷新）
- 现状：每次进详情都重新加载。
- 要求：进入先显示本地缓存（Room 已有的 work/评分/extras），后台 API 返回后再更新（stale-while-revalidate）。角色/Staff/关联/路线等 extras 也缓存。

### H11. 观看路线跳转「暂无内容」修复
- 现状：路线内多数可跳转作品点进去显示「暂无内容」，但搜索该名能搜到；衍生作/原作非番剧更难跳。
- 根因排查：路线 entry 的 workId 是 Bangumi 关联条目 id，但本地无 work 记录 → 进详情 observeWork 为空且 fetch-on-open 失败。修复：进详情时若本地无该 work，按 subjectId 拉 Bangumi 条目落库再展示（确保路线跳转可用）。

### H12. 补完成本算法优化
- 用已有番剧时长数据（集数 × 单集时长 / 游玩时长）优化补完成本估算与分桶阈值，更贴近真实。

### H13. 展示社区 tag（别人给番剧标的标签）
- 现状：只显示用户自己的 tag。
- 要求：展示 **Bangumi 社区 tag**（subject tags，已在 `/v0/subjects/{id}` 的 `tags` 字段），用于详情展示与口味分析（如「催泪」+ 我打 10 分 → 高分标签）。口味画像/匹配应使用社区 tag 而非仅用户自定义 tag。
- 若 Bangumi tag 不足，可网页搜索其他来源 tag API（记录到 DEVELOPMENT.md）。

### H14. 待补池卡片排版可切换
- 现状：一行一个作品。
- 要求：增加「网格排版」——3 个作品一行、每个只显示封面；用户可在待补池切换「列表 / 网格」两种排版。

### H15. 本地编辑回写 Bangumi 实测失效修复
- 现状：本地标注「想看」，Bangumi 无反应。
- 排查 G8 回写链路为何没生效（鉴权 token 注入？subjectId？接口返回码？错误吞掉？）。务必让本地编辑真正同步到 Bangumi。先读 `参考项目文件/` 两个 Bangumi 客户端确认收藏修改的确切请求形态，写进 EXPERIENCE.md。

### H16. 评分聚合多源（mzzbscore 思路）
- 评分差异/多平台评分要真正多源：参考 `参考项目文件/mzzbscore-main`（多站聚合 + 交叉验证用对方日/英文名重试 + 归一化）。

> 实现顺序建议：先排查类（H2/H3/H11/H15 读代码定位根因）→ 再做 bounded UI（H1/H8/H14）→ 数据链路（H4/H5/H6/H7/H10/H13）→ 算法（H12/H16）→ H9 评论摘要。每步先读文件后编辑，分阶段 compileDebugKotlin，全部完成后 assembleDebug。


### H 轮第二批进度（本轮 · compile 通过 · 待真机验收）

- [ ] H1 顶部留白 → 55dp（再下移）。
- [ ] 卡片「暂无数据」杂讯：WorkCard 评分缺失时**不再**显示「暂无数据」，只显示类型（首页/搜索/发现列表卡统一）。详情页平台评分仍保留「暂无数据」（缺失即标记）。已同步更新对应 UI 测试断言。
- [ ] 首页/Bangumi 封面不加载根因修复：legacy `/calendar` 封面多为 `http://`（Android 默认禁明文）→ 映射时统一升级为 `https://`，首页可直接加载封面（不必先进详情）。
- [ ] H4（部分）：详情页「加入待补池」默认置「想看」→ 写本地 user_collections + 回写 Bangumi（`POST /v0/users/-/collections/{id}` type=1，已配置时）；已有更进状态不回退。EXPERIENCE.md 记录了回写排查与两个客户端的收藏修改方式（网页表单 vs v0）。
- 说明：首页「近期热门/本季」目前 = Bangumi 每日放送（本季在播），按年份近似排序；真正「热门度」排序需把评分/人数数据并入首页模块，列入后续。

### H 轮仍待办（按序，下一阶段）
- [ ] H3 发现页本季榜单/评分差异/筛选真机填充验证（数据链路已就绪，需确认 `/calendar` 返回）。
- [ ] H4 余项：进度→在看/看过状态机 + clamp 总集数 + 反向（Bangumi 想看/在看→待补池）。
- [ ] H5 吃灰池=搁置；H6 历史时间线；H7 自动同步间隔；H9 评论摘要；H10 详情缓存（先缓存后刷新）；H11 观看路线跳转「暂无内容」修复；H12 补完成本算法；H13 社区 tag；H14 待补池网格排版；H16 多源评分聚合。


### H 轮第三批进度（本轮 · compile 通过 · 待真机验收）

- [ ] H11 观看路线跳转「暂无内容」修复：`refreshRatingsAndWork` 在本地无该作品且 id 为数字时，先拉 Bangumi 条目落库，再展示；路线跳转可正常进详情。
- [ ] H13 社区 tag 展示+用于口味：新增 `TagCategory.CONTENT`；Bangumi v0 `toWork` 把社区 subject tags（前 15）纳入 `Work.tags`，详情展示 + 口味匹配可用别人标的标签（如「催泪」）。
- [ ] H4 进度状态机：编辑「我的记录」时进度 clamp 到总集数；未显式选状态时按进度自动推导（>0 在看 / ≥总集数 看过），回写 Bangumi。
- [ ] H4 反向同步：`BangumiSyncManager` 同步后把「想看/在看」收藏并入待补池（addAll 去重）。
- [ ] H6 历史时间线：时光机基线视图改为按 user_collections 的 `sourceUpdatedAt`(回退 updatedAt) 生成「以前看的番」时间线（按月分组，展示当时状态/评分/短评/进度）。
- [ ] H14 待补池列表/网格切换：顶栏「网格/列表」切换；网格 3 列只显示封面+标题。

### H 轮仍待办（较大/有风险，未开始 ≠ 半截，单独排期）
- [x] H5 吃灰池=搁置：批量「移入吃灰馆」时把选中条目本地置「搁置」并回写 Bangumi（type=4，best-effort）。已在 `BacklogViewModel.onBulk(ARCHIVE_TO_DUST_MUSEUM)` 实现 `archiveToBangumiShelved`（已「看过」不回退；非数字 id 仅本地）。**代码完成待真机验收**。
- [x] H7 设置自动同步间隔：新增 `@HiltWorker BangumiSyncWorker` + `SyncScheduler`（WorkManager 唯一周期任务，间隔下限 15 分钟，CONNECTED 约束）；`SettingsState.autoSyncIntervalMinutes` + DataStore int 键 + 设置页「自动同步 Bangumi」FilterChip（关闭/6h/12h/24h）；`Application` 实现 `Configuration.Provider` 用 Hilt WorkerFactory 并观察间隔即时重调度；Manifest 移除默认 WorkManager 初始化器。**代码完成待真机验收**。
- [x] H9 详情「评论摘要」：用无剧透评价雷达（AI/本地规则）折叠为多段口碑概览（总体印象/好评点/争议点/适合·不适合/观看时机）+ 社区评分行，标注来源；雷达与评分都缺失才「暂无数据」。在 `DetailContract.reviewSummary` 实现。**代码完成待真机验收**。
- [x] H16 多源评分聚合交叉验证（mzzbscore 思路）：`WorkRepositoryImpl.crossValidateRatings` 对尚无评分的公共动画源（Bangumi/AniList/Jikan）按标题跨平台搜索，标题相似度≥0.86 才采纳，拉取对方评分并以当前 workId 入库 + 落源链接。仅动画、best-effort。**代码完成待真机验收**。
- [ ] H10 详情缓存：work/评分已 Room SWR 缓存；角色/Staff/路线等 extras 仍每次重取（需 extras 缓存表，schema 改动）。
- [ ] H12 补完成本：现按 集数×单集时长/游玩时长 估算，基本可用；如需更精细另排。
- [ ] H3 发现榜单/评分差异/筛选真机填充验证。


---

## Final Sprint · I 轮（H/I 实测反馈，对应 RC.33–RC.48 / Requirement 35–50）

> 每个任务实现前先读「关联文件 + 参考项目」再改；分阶段 compileDebugKotlin，全部完成后 assembleDebug。参考项目在 `参考项目文件/`（两个 Bangumi 客户端、mzzbscore、sprout、AnimeMate、animeko）。

- [ ] I1（RC.33）同一作品跨源一致聚合 + 主源优先 Bangumi 中文
  - 关联：`domain/matching/CrossSourceMerge`（clusterMatches/representativeOf）、`WorkRepositoryImpl.search/persistMatches`、`BangumiMappers`、`DetailContract.toHeader`。
  - 改：representativeOf 在簇内有 Bangumi 命中时选 Bangumi 为代表；搜索卡片展示聚合到的全部来源标识；详情简介/标题优先 Bangumi。
- [x] I2（RC.34）滚动安全区一致化 + 底部留白克制（**全局回归**）
  - 已做：`ScreenContentPadding.Bottom` 96→28（外层 Scaffold 已消费底部导航 inset，消除滚到底大空白）；新增 `UnderBarTop=10dp`，发现各 Tab 列表与时光机列表改用 `UnderBarTop`（位于 TabRow/同步栏下方，不再叠加 55dp 过度下沉）；直接位于标题栏下方的页面（首页/我的/设置/我的库/口味）保持 `Top=55`。**代码完成待真机验收**（顶部精调如仍需可再调常量）。
- [x] I1（RC.33）同一作品跨源一致聚合 + 主源优先 Bangumi 中文
  - 已确认实现：`mergeCrossSource`（搜索结果按 `clusterMatches` 聚类成单卡）+ `representativeOf` 优先 Bangumi 为代表（详情用 Bangumi 中文标题/简介）+ 合并卡 `sourceTags` 展示全部来源标识 + `persistMatches` 跨源 source links 指向代表作品（详情聚合多源评分）。**已知局限**：跨「不同关键词」的一致性受限——聚类只能合并某次查询里各源返回的命中；不同关键词各源返回不同记录时，列表代表源可能不同（如「9nine」Bangumi 未命中则该次以 Jikan 为代表，而详情走已入库的多源 links）。彻底消除需对每个结果再做一次 Bangumi 按名解析（额外网络开销），作为后续增强单列。
- [ ] I3（RC.35）发现本季榜单/评分差异/筛选数据装填（真机验证）
- [x] I9（RC.40）今晚看什么候选池选择 + 更多候选 + mzzbscore 相似度
  - 已做：`RecommenderInput.candidatePool`（待补池/全部作品）+ 推荐页候选池 FilterChip；`RecommenderViewModel.recommendFromAllWorks` 从全部动画作品按口味画像高/低分标签重合度（社区标签来自 H13/F9，负信号 1.5× 降权，sprout/mzzbscore 思路）打分排序，排除看过/抛弃，取前 3（不准纠结取 1）分别标稳妥/赌一把/神经病，理由解释「与你高分作品常见标签重合：…」。**代码完成待真机验收**。
- [x] I4（RC.36）吃灰馆独立页面 + 入口 + 移入移出
  - 已做：新增 `DustMuseumScreen/Route` + `AppDestination.DustMuseum` + 导航；`BacklogViewModel.dustMuseumCards`（仅吃灰区、按吃灰天数倒序）+ `onRestoreFromDust`（RESTORE_FROM_DUST_MUSEUM）；待补池顶栏新增「吃灰馆」入口；逐条「移出吃灰馆」。**代码完成待真机验收**。
- [x] I5/I7（RC.37）本地→Bangumi 回写真正生效（排查同步失败）
  - 根因：此前一律用 `POST /v0/users/-/collections/{id}`，对**已存在**收藏会失败（v0 用 PATCH 修改、POST 仅新建）→ UI 提示「同步失败」。已改为 `BangumiRemoteDataSource.updateUserCollection` 先 PATCH，404（尚未收藏）再 POST 新建；`BangumiApi` 拆为 `patchUserCollection`/`createUserCollection`。参考 animeko `v0.yaml` 确认 PATCH 全字段可选。**代码完成待真机验收**。
- [x] I6（RC.38）我的记录状态机 + 总集数 + 清空
  - 已做：编辑对话框采用「即完整意图」覆盖语义——不选状态=清空（无状态）、评分/进度留空=清空（VM 不再回退既有值，回写时显式提交 0/"" 清除 Bangumi 端）；进度输入按总集数封顶并显示「共 N 话」；有进度自动判在看、满集判看过。**代码完成待真机验收**。
- [x] I8（RC.39）社区标签用于画像/推荐/匹配度
  - 已满足（既有管线）：F9 在同步时把作品社区标签并入 `user_collections.tags` → 口味画像高/低分桶来自社区标签；H13 把社区标签写入 `work.tags`；G11 `toTasteMatch` 用 work 社区标签 vs 画像高/低分标签计算（负信号 1.5× 降权 + 社区评分融合）。**代码完成待真机验收**（如需进一步调参另排）。
- [ ] I9（RC.40）今晚看什么候选池选择 + 更多候选 + mzzbscore 相似度
  - **仍待**：需扩展 `DrawCriteria`（候选池=待补池/全部）+ `BacklogRepositoryImpl.draw` 支持全量作品候选 + 基于看过评分的相似度排序 + 推荐页/首页 UI 选择器（较大，单独排期）。
- [x] I10（RC.41）AI 匹配度提示词工程（近20部+最高/最低分锚点）
  - 已做：`DetailViewModel.onAnalyzeMatchWithAi` 在 prompt 中纳入「历史最高分（最喜欢）」「历史最低分（最不喜欢，≤6 分）」各前 5 部 + 近期 20 部评分作品（标题·评分·短评）。**代码完成待真机验收**。
- [x] I11（RC.42）时光机条目可跳转详情
  - 已做：`TimelineEntryUi` 增 `workId`；时间线行与基线作品行可点击 → `onOpenWork` → detailRoute；导航图已接线。**代码完成待真机验收**。
- [x] I12（RC.43）跳转加载态不闪现空状态
  - 已做：`DetailViewModel` 增 `workEnsured`，在首次「确保作品已加载」（必要时联网拉取入库）完成前把 `Empty` 视为 `Loading`，消除观看路线跳转瞬间「暂无内容」闪现。**代码完成待真机验收**。
- [x] I13（RC.44）评论摘要展示评论内容摘要（非评分）
  - 已做：`reviewSummary` 移除「社区评分」行，仅展示无剧透雷达的内容性口碑（总体印象/好评点/争议吐槽/适合/观看时机）+ 来源标注；无内容→暂无数据，不再用评分充当。**代码完成待真机验收**（真实逐条评论文本源后续接入）。
- [x] I14（RC.45）决策助手 需注意/不推荐、雷达、适合心情 实装
  - 已做：推荐理由补充雷达好评点；需注意补充风险标签 + 雷达争议/雷点；适合心情在 MOOD 标签缺失时由内容标签按关键词推断（治愈/催泪/热血/搞笑/恋爱/悬疑/科幻/奇幻/音乐/电波）。**代码完成待真机验收**。
- [x] I15（RC.46）封面旁发行/完结状态展示
  - 已做：`BangumiMappers.deriveBangumiStatus(date, episodes)` 由开播日期+集数保守推导（未发布/连载中/已完结），替代恒为 UNKNOWN。**代码完成待真机验收**。
- [x] I16（RC.47）开播日期精确到天
  - 已做：`Work.airDate` + `WorkEntity.airDate` + Room v3→v4 迁移（ALTER ADD COLUMN）+ 映射；Bangumi `date` 写入；详情头新增「开播日期」行（yyyy-MM-dd），缺失退回年份/暂无数据。**代码完成待真机验收**。
- [x] I17（RC.48）待补池网格/列表形态持久化
  - 已做：`SettingsDataStore.backlogGridMode` + `BacklogViewModel.gridMode/onToggleGridMode`，替换原进程内 `rememberSaveable`，跨进入记忆。**代码完成待真机验收**。

> 实现顺序：先做有回归风险且影响全局的 I2；再数据链路 I5/I7、I6、I15、I16；再页面 I4、I11、I12、I17；再算法/AI I1、I8、I9、I10、I13、I14；最后 I3 真机验证。
> 本轮已完成：I2、I10、I11、I12、I13、I14、I15、I16、I17（均编译 + 单测无新增失败 + 打包验证）。第二批已完成：I4、I5/I7、I6、I8。第三批：I9（推荐候选池+相似度）、I1（搜索跨源合并已确认实现，含已知局限）。剩余仅 I3（真机验证项，无可编码内容）。


---

## Final Sprint · J 轮（0.2.0-I 真机复测反馈）

> 先读文件再改；优先读参考项目 `参考项目文件/`（两个 Bangumi 客户端 czy0729/xiaoyvyv、mzzbscore、sprout、AnimeMate、animeko）。分阶段 compileDebugKotlin，完成后 assembleDebug + adb 安装到 f976f3e1 验证。

- [ ] J1 顶部遮挡微调：首页/设置等「直属标题栏」页面顶部内容仍被遮挡一点 → `ScreenContentPadding.Top` 适当增大（仅影响这些页；中间栏页用 UnderBarTop 不动）。
- [ ] J2 完结状态来源：现状是按开播日期+集数推测（`deriveBangumiStatus`）。确认 Bangumi v0 是否有可用「完结/连载」状态字段；有则用真值，无则保留并改进预测。
- [ ] J3 今晚看什么「全部作品」推荐空：`recommendFromAllWorks` 推不出 → 排查（works 为空？mediaType 过滤？阈值？）；保证有数据就能出推荐。
- [ ] J4 待补池推荐重复：`BacklogRepository.draw` 确定性导致总推同一部（最后点想看的）→ 改多维度算法（口味相似度+多样性+时间衰减+随机扰动+已看排除），参考 sprout/AnimeMate。
- [ ] J5 推荐可选标签更多：扩充 `MoodOption`/`AcceptanceOption` 维度。
- [ ] J6 决策助手仍空：需注意/不推荐、无剧透雷达、适合心情 → 保证本地规则雷达恒生成（LocalFallbackRadar）、决策三块在最小数据下也有内容（标签/评分/题材派生），未配置 AI 也要有。
- [ ] J7 评论摘要仍空：依赖雷达（J6）；并调研参考 Bangumi 客户端是否有评论/吐槽接口（czy0729/xiaoyvyv），能取则取真实短评做摘要。
- [ ] J8 口味匹配度不准：本地算法升级为多维（社区标签重合 + 类型/题材 + 评分习惯 + 社区评分 + 负信号），可联网检索更好做法。
- [ ] J9 本季榜单/评分差异/筛选仍空：排查 `loadPublicDiscovery`（calendar/Jikan/AniList 是否返回）；UI 暴露失败原因；保证至少 Bangumi 本季能填充。
- [ ] J10 我的记录回写仍失败「请稍后重试」：**先把真实错误暴露**——`updateUserCollection` 捕获 HTTP code + errorBody 文本，UI 显示具体码与内容，便于定位；再据此修复。
- [ ] J11 搜索跨源不一致（通用）：不同关键词→列表来源不同、列表与详情来源/评分/简介不一致。设计通用算法：搜索结果以规范作品聚类、代表统一（Bangumi 优先且简介取 Bangumi 中文）、详情与列表同源一致；Bangumi 番剧通常无别名，避免错误并卡/拆卡。**不对 9nine 特殊处理**。
- [ ] J12 删除搜索页「管理匹配」按钮（已无用）。


### J 轮进度（0.3.0-J）
- [x] J1 顶部遮挡：`ScreenContentPadding.Top` 55→68dp（仅直属标题栏页）。
- [x] J3 全部作品推荐空：`recommendFromAllWorks` 不再因无正向标签命中而整体为空，有动画候选即给推荐（弱排序+随机扰动）。
- [x] J4 待补池推荐重复：`draw` 改多维加权（优先级+吃灰归一+随机扰动），重复抽番有变化、不再固定同一部。
- [x] J5 推荐标签更多：MoodOption 扩到 17 项（治愈/搞笑/催泪/科幻/奇幻/日常/音乐/运动等）。
- [x] J6 决策助手空：根因=AI 已配置但成本未确认时雷达为 null。新增 `GenerateSpoilerRadarUseCase.local()`，详情在成本未确认时用本地规则雷达兜底，决策助手/雷达/适合心情恒有内容。
- [x] J7 评论摘要空：随 J6 修复（雷达恒有内容）；摘要取雷达口碑内容。真实逐条 Bangumi 短评接入仍待（v0 无干净评论端点，需调研）。
- [x] J10 回写错误暴露：`updateUserCollection` 失败时抛出含 `HTTP 码 + 方法 + 服务端错误体` 的 `AppError.withCause`，详情页提示显示具体 cause（便于定位真正失败原因）。
- [x] J11 搜索跨源一致（通用）：详情交叉验证命中 Bangumi 时，用 Bangumi 条目覆盖展示字段（中文标题/简介/封面/状态/开播日 + 主源标记 Bangumi），消除「列表单源、详情多源、简介英文」不一致。非特例化 9nine。
- [x] J12 删除搜索页「管理匹配」按钮。
- [ ] J2 完结状态：经查 Bangumi v0 subject **无**完结/在播布尔字段（仅 date/eps/total_episodes），故继续用 `deriveBangumiStatus` 按开播日期+集数预测。
- [ ] J8 口味匹配度：已是多信号（社区标签重合+负信号 1.5×降权+社区评分融合）；进一步精度提升需 tagStats 权重化，迭代中。
- [ ] J9 本季榜单/评分差异/筛选空：已让筛选 Tab 也触发公共池加载；榜单内容依赖联网抓 Bangumi `/calendar`——需真机上看「加载失败」提示文案以定位（boards 的 worksWithRatings 会对每作品触发评分刷新，量大时偏慢，后续优化为只读本地缓存）。


---

## Final Sprint · K 轮（0.3.0-J 复测反馈）

- [ ] K1 回写 HTTP 415：错误体「request with body must set content-type header to application/json」→ PATCH/POST 收藏请求缺 `Content-Type: application/json`。给 `patchUserCollection`/`createUserCollection` 显式加 `@Headers("Content-Type: application/json")`。**这是回写失败的真因。**
- [ ] K2 全部作品推荐质量差（推了 3.x 分番）：ALL 池在口味排序基础上**融入社区评分**（取 top 候选拉评分、过滤低分、按 口味+评分 综合排序），挑「好+准+合口味」。
- [ ] K3 决策助手/评论摘要/雷达本地仍是占位空话：用户同意**雷达走 AI 驱动**。已配置 AI 时 `loadRadar` 直接以 confirmed=true 跑 AI 生成；未配置时显示「配置 AI 后生成」而非无用占位。适合心情/需注意 结合 AI 输出 + 标签。
- [ ] K4 搜索跨源不一致（按用户要求做强化修复）：不同关键词→来源数不同/详情来源数不定。根因疑为 AniList/Jikan 命中未带**日文原名**，无法与 Bangumi 日文名聚类。改：各源映射补齐原名/别名变体；聚类按任一标题变体匹配；并在搜索期对缺 Bangumi 的簇做 Bangumi 反查补齐，使来源稳定。
- [ ] K5 口味匹配度/今晚看什么 持续优化（迭代）。
- [ ] K6 状态清空后残留：把状态点「想看」再取消保存后，时光机基线 / 我的收藏里多出一条「暂无数据」。根因=I6 覆盖语义把全清空仍写入空 user_collections 行。改：全字段为空时**删除**该行而非写空行；时光线/基线过滤无效空行。


### K 轮进度（0.4.0-K）
- [x] K1 回写 415：`patchUserCollection`/`createUserCollection` 加 `@Headers("Content-Type: application/json")`。修复「request with body must set content-type」。
- [x] K2 全部作品推荐质量：ALL 池先按口味取 top30，再用**本地缓存社区评分**二次排序（口味0.5+评分0~4+扰动），过滤社区均分 <6 的低分作品；理由附「社区均分约 X」。
- [x] K3 决策助手/评论摘要/雷达：已配置 AI 时 `loadRadar` 以 confirmed=true 跑 AI（真实无剧透分析）；未配置回退本地规则。雷达驱动决策三块 + 评论摘要。
- [x] K4 搜索跨源一致（通用）：`search()` 新增 `backfillBangumi`——对缺 Bangumi 的结果簇用日文/罗马音/规范名反查 Bangumi（限额 8 次、阈值 0.86），使不同关键词都稳定带出 Bangumi 源、列表与详情一致。非特例化。
- [x] K6 状态清空残留：全字段清空时 `userCollectionDao.deleteByWork` 删除该行（不再写空行），消除时光机基线/我的收藏的「暂无数据」幽灵条目。
- [x] K9 榜单/差异/筛选空：根因=`worksWithRatings` 对每作品 `aggregateRatings` 触发联网刷新风暴、永不收敛。新增 `aggregateRatingsCached`（只读本地），发现页改用之 → 榜单按已加载公共池即时成榜。
- [ ] K5 口味匹配度持续优化（迭代；本轮先升级推荐侧）。
- [ ] J2 完结状态：Bangumi v0 无完结字段，维持开播日期+集数预测。


---

## Final Sprint · L 轮（0.4.0-K 复测反馈）

- [ ] L9 **严重·数据污染**：打开 clannad 详情，评分加载瞬间条目变成「战锤40K」并被持久化。根因=J11 在 `crossValidateRatings` 里用 Bangumi 搜索结果**覆盖了作品标题/简介/主源**，错配即污染。→ **撤销该字段覆盖**；交叉验证只追加评分源链接，绝不改变作品身份。
- [ ] L1 回写 HTTP 400「can't set 'vol_status'/'ep_status' on non-book subject」：收藏 PATCH 对非书籍不能带 ep_status/vol_status（我清空逻辑恒发 ep_status=0 触发）。→ 收藏修改不再发 ep_status/vol_status（非书籍）；动画进度改走专用「章节进度」端点或仅本地。先保证状态/评分/短评能存。
- [ ] L2 榜单点进去「暂无内容」：`RankedWorkCard` 用 `titleKey()`(标题) 当 workId 导航 → 详情查不到。→ 榜单携带真实 work id 导航。
- [ ] L2b 榜单不准/不明：现榜单只是 Room 已缓存作品排序（非真实排行，航海王8.3>clannad 之类）。→ 用真实排行数据；新增「总榜/年榜/本季榜」；「本机榜单」改名「榜单」；每个源可点进看完整榜单（非仅前15）。参考 czy0729/xiaoyvyv 的 rank 接口。
- [ ] L3 评分差异只含点开过的番：数据面太小。→ 用公共池/排行批量数据计算跨平台差异，思路改进。
- [ ] L4 筛选不全 + 顶部标签占位太大 + 缺传统分类（热血/校园等）：→ 折叠/精简筛选 UI；补全题材分类（参考项目的 genre 列表）。
- [ ] L5 评论摘要放真实他人短评：Bangumi 原生有短评/吐槽。→ 找 Bangumi 评论/短评接口（v0 或参考客户端），取最近几条展示。
- [ ] L6 今晚看什么(待补池) 只出「神经病选择」：SAFE/GAMBLE 档因条件过严抽空，只剩最松的 WILDCARD。→ 调整：候选不足时各档兜底、保证尽量给满 3 档。
- [ ] L7 今晚看什么(全部作品) 全是 Jikan 源：Room 里多为 Jikan 条目。→ 推荐去重/优先 Bangumi 代表，避免同番多源刷屏与偏 Jikan。
- [ ] L8 推荐准确度：clannad after story 被当「日常番」推荐（实为催泪）。→ 心情/题材匹配优化。
- [ ] L10 AI 雷达仍「资料有限/规则生成/置信度0%」即便已配置 AI：AI 实际没跑成或输入太少。→ AI 输入纳入作品简介+社区评分等；诊断 AI 调用失败原因；小众番 AI 不知剧情时如实说明而非空话。
- [ ] L11 详情页点击封面看大图 + 下载大图按钮。
- [ ] L12 缓存/空间优化 + 详情先显缓存再刷新：50MB 缓存无感知；详情应先渲染缓存再后台刷新（SWR 体验）。
- [ ] L13 榜单/差异顶部「公共榜单(Jikan/MAL)无需登录」那行 + 只显「刷」字的按钮：整理这块 UI（按钮文案完整、布局合理）。
- [ ] L14 口味匹配度算法持续优化（迭代）。


### L 轮进度（0.5.0-L · compile 通过 · 单测无新增失败 · 待真机验收）
- [x] L9 数据污染：撤销 `crossValidateRatings` 覆盖作品身份的逻辑，交叉验证只追加评分源，绝不改标题/简介/主源（修复 clannad 变战锤40K）。
- [x] L1 回写 HTTP 400：收藏 PATCH/POST 对非书籍统一不带 `ep_status`/`vol_status`，避免「can't set ep_status on non-book subject」；状态/评分/短评可正常保存（动画进度仅本地）。
- [x] L2 榜单点进「暂无内容」：`RankingBoard.items` 改为携带真实 work id 的 `RankedWork` 导航，修复用标题当 id。
- [x] L2b 榜单准确性 + 改名：`BangumiRemoteDataSource.searchRankedSubjects`（`sort=rank` + 类型/开播日过滤）拉取真实排名动画（含真实评分），并入公共池（总榜 40 + 本季 20），使「榜单」反映真实排名；Tab「本季榜单」改名为「榜单」。**仍待**：总榜/年榜/本季榜独立子 Tab 切换 + 每源完整榜单详情页（较大，单列后续）。
- [x] L4 筛选：新增「题材」维度（社区 CONTENT 标签 + 常见传统题材兜底：热血/校园/恋爱…）；筛选条件 UI 默认折叠（标题显示「已选 N 项」），消除顶部标签占位过大。
- [x] L6 待补池推荐只出「神经病」：`drawWithFallback` 逐级放宽（心情→时间→风险），各档尽量填满，不再因待补条目无 mood/risk 标签而只剩 WILDCARD。
- [x] L7 全部作品推荐全是 Jikan：`dedupePreferBangumi` 按规范化标题跨源去重、优先 Bangumi 代表，避免同番多源刷屏与偏 Jikan。
- [x] L8/L14 心情/匹配：`inferMoodsFromTags` 强情绪（催泪/致郁）优先，「日常/轻松」在有强情绪时不再误判为治愈/放松（修复 clannad after story 被当日常番）。
- [x] L10 AI 雷达资料有限：`RadarRequest` 纳入作品简介 + 社区评分（本地缓存）+ 类型作为 AI 输入；AI 已配置却回退规则时通过 `aiFallbackReason` 把失败原因（cause + nextStep）经 recordMessage 暴露给用户，便于诊断「为何仍是规则生成」。
- [x] L11 详情封面：点击封面打开全屏大图（双指缩放）+「保存到相册」（经 Coil 取位图 + MediaStore 写入 Pictures/ACGCompass，兼容分区存储）。
- [x] L13 顶部「公共榜单(Jikan/MAL)无需登录」行：文本占 weight、按钮文案完整为「刷新」（不再截断成「刷」）。
- [ ] L3 评分差异仅含点开过的番：L2b 已让公共池纳入真实排名作品，差异面有所扩大；批量跨平台差异（更大规模）仍可后续增强。
- [ ] L5 评论摘要放真实他人短评：Bangumi v0 无干净的逐条短评端点（参考客户端走 HTML 抓取，风险高），本轮维持雷达口碑摘要；真实逐条短评接入单列后续。
- [ ] L12 缓存/空间优化 + 详情 SWR：work/评分已 Room SWR；extras 缓存表（schema 改动）与缓存用量可见化单列后续。


## Final Sprint · M 轮（0.5.0-L 复测反馈 + 上轮暂缓项全做）

> 已联网核验：(1) 官方 `GET https://next.bgm.tv/p1/subjects/{id}/comments` 返回干净 JSON 短评（nickname/rate/comment/updatedAt），非网页抓取，符合「不抓网页」；(2) Bangumi v0 collection PATCH 的 `ep_status/vol_status`「只能用于修改书籍条目进度」，动画进度须走 `GET /v0/episodes?subject_id=&type=0` 取本篇章节 id + `PATCH /v0/users/-/collections/{id}/episodes`（body `episode_id[]`+`type`，EpisodeCollectionType 2=看过）。写入 DEVELOPMENT.md。

- [x] M1（L5）真实他人短评：新增 `next.bgm.tv/p1` 服务取条目短评；详情「评论摘要」展示最近数条真实短评（昵称+评分+短评，无剧透截断）；并把真实短评喂入雷达 `publicReviews`（AI/规则都更有料）。
- [x] M2（L2b）榜单总榜/今年/本季子 Tab：RANKING Tab 顶部加 Bangumi 真实排行（`sort=rank`+air_date），子分类切换；下方保留本地多源缓存榜单。每源完整榜单详情页（仅 Bangumi 有 rank API，其余源需各自端点）留待后续。
- [x] M3（L3）评分差异扩面：随 M2 真实排行 + 公共池跨源 source links 聚类，有多源评分样本扩大；UI 标注范围。
- [x] M4（L12）缓存治理 + 利用：Application 实现 Coil `ImageLoaderFactory`（内存 25% + 磁盘 100MB 上限，启用缓存策略复用封面）；新增 OkHttp 20MB HTTP 响应磁盘缓存（复用条目/评分/评论 GET）；设置「清除缓存」接线清空 Coil 内存+磁盘缓存。详情 extras Room 缓存表（schema 改动）留待后续。
- [x] M5 动画进度上传 Bangumi：动画用 `GET /v0/episodes` + `PATCH .../episodes`（type=2 标看过前 N 集）；书籍/漫画编辑对话框隐藏进度输入、不上传。
- [x] M6 无 Bangumi 词条不上传：写回前 `subjectExists` 确认，无词条则仅本地保存并提示「该作品在 Bangumi 无词条」。
- [x] M7 仅生成 APK 不安装；版本 0.6.0-M。


## Final Sprint · N 轮（0.6.0-M 复测反馈）

> 根因已定位：Jikan/AniList 的 `work.id` 是**裸数字**（malId/anilistId），而回写把 `workId.toIntOrNull()` 当成 Bangumi subjectId → 非 Bangumi 作品的数字 id 与某个无关 Bangumi 条目碰撞（如 Code Geass R2 的进度写到了「i-wish you were here」）。这是数据污染级 bug。

- [x] N1 **严重·回写错条目**：`resolveBangumiSubjectId`——primarySource==BANGUMI 用 work.id，否则用 Bangumi 源链接 sourceItemId，都没有则判「无词条」不回写。markWantToWatch/onUpdateMyRecord 均改用之，修复 Jikan 裸数字 id 与无关 Bangumi 条目碰撞（Code Geass R2→「i-wish you were here」）。
- [x] N16 无 Bangumi 词条详情页：隐藏我的状态/评分/进度，仅「加入待补池」+ 说明。
- [x] N2 本地清空同步清云端：清空时对 Bangumi 发 rate=0/短评空/标签空（收藏状态本身 v0 无删除端点，提示在网页移除）。
- [x] N3 书籍/漫画/游戏：详情页展示与编辑均不显示进度（仅动画）。
- [x] N5 榜单只留 Bangumi + 修复离谱：rank 过滤 `>0` 排除未排名 + 客户端按 rank 升序兜底；移除其他源缓存榜单。
- [x] N6 评分差异/筛选番剧太少：loadPublicDiscovery 分页拉 Bangumi rank 前 150 + 本季 30，扩充池。
- [x] N7 封面大图：仅「保存到相册」按钮；点击图片外/系统手势退出。
- [x] N9 AI 雷达结构异常：SpoilerRadar 必需字段放宽为仅 `overallImpression`（其余有默认值），模型省略字段也能解析成功。
- [x] N11 口味画像顶部遮挡：TopAppBar 用默认 windowInsets（含状态栏）。
- [x] N12 主页继续看/读/玩：直接取 Bangumi「在看」收藏（user_collections）。
- [x] N13 同步提醒仅 >24h 未同步（或失败）才显示。
- [x] N17 详情分数多源：交叉验证用作品全部标题变体（原名 ja 优先）跨源查询（拔作岛→ぬきたし 找回其他源评分）。
- [ ] N4 评论摘要真实短评：Bangumi 主源作品已可取 next.bgm.tv/p1 短评；非 Bangumi 主源（经交叉验证有链接）取短评仍待接（loadExtras 目前仅 Bangumi 主源）。
- [ ] N10 决策助手空：随 N9 改善（AI 可用即有内容）；本地最小数据下进一步补全待迭代。
- [ ] N14 今晚看什么优先 Bangumi：ALL_WORKS 已 dedupePreferBangumi；待补池抽取按用户已加条目，进一步优先 Bangumi 待迭代。
- [ ] N15 Jikan 详情简介英文→中文：跨源已补 Bangumi 评分（N17）；以 Bangumi 中文简介覆盖展示需「代表源切换」，为避免 L9 式污染，单列谨慎实现。


## Final Sprint · O 轮（0.7.0-N 复测反馈 · 推荐算法）

- [x] O1 今晚看什么题材/心情感知：两个候选池（待补池/全部作品）统一改为基于作品**社区标签**的题材打分——所选心情展开为关键词与作品标签重合得正分；仅选放松向（轻松/日常/治愈/搞笑/恋爱/音乐/短篇）时对强冲突标签（热血/战斗/血腥/致郁/战争/巨人…）重罚并过滤，修复「选日常/轻松却推进击的巨人」。综合分 = 题材×2 + 口味画像×0.5 + 社区评分(0~4) + 神作偏好 + 随机扰动；排除看过/抛弃；短篇偏好集数≤13/今晚可看完；待补池档改用作品标签而非空的待补条目标签。
- [x] O3 推荐理由聚焦「为什么这部贴合」（展示命中的作品题材标签），不再重复回显所选心情。


## Final Sprint · P 轮（清理 N 轮剩余项）

- [x] N4 评论摘要真实短评（扩展）：详情 loadExtras 改用 `resolveBangumiSubjectId`（主源或交叉验证链接），有 Bangumi 词条的非主源作品也能取真实短评/角色；init 串行化（评分聚合→交叉验证落链接→再加载雷达/评论），雷达 publicReviews 也用解析后的 subjectId。
  - 已知局限：短评走官方 next.bgm.tv/p1（固定官方域名），若用户因网络对官方 Bangumi 不可达而配了反代，p1 短评可能取不到（反代通常不覆盖 p1）；此时回退雷达口碑摘要。
- [x] N10 决策助手最小数据兜底：推荐理由在无 AI/口味/共识信号时回退「题材偏向：…」；社区评分稳定追加「口碑较稳定」；适合心情由内容标签推断（含社区 CONTENT 标签）。
- [x] N14 今晚看什么待补池档去重优先 Bangumi（dedupePreferBangumi）。
- [x] N15 Jikan 详情简介英文→中文：非 Bangumi 主源但有 Bangumi 链接时，详情刷新末尾用 Bangumi 中文简介覆盖**仅简介**（不动标题/封面/主源，避免 L9 式身份污染）。


## Final Sprint · Q 轮（0.9.0-P 复测反馈 · 算法与体验深化）

- [x] Q1 今晚看什么高度重复：加随机性（更大扰动 + 候选池取 topN 后随机抽取，而非恒取最高分）。
- [x] Q2 推荐质量：用**评分人数加权**贝叶斯均分，过滤「高分但人数少的奇怪番」；不要老推近满分番，要推「适合自己」（口味权重 > 绝对分）。
- [x] Q3 特别模式（不准纠结/期末保护/深夜提醒）在新推荐算法中生效（不准纠结→1 部；期末保护→排除长篇/未完结/高耗能）。
- [ ] Q4 拔作岛评分错误：Bangumi 词条且 9000+ 人 6.4 分，App 却显示 1 人 6 分。**待真机抓包定位**（疑搜索命中到同名小条目/评分人数字段映射）。
- [x] Q5 榜单条数：分页拉取至 ~150（每页 50×3）。**滚动懒加载下一页留作后续**（当前一次性载 150 + 缓存）。
- [x] Q6 评分差异/筛选内容过少：随榜单扩量（公共池纳入 150 排行）改善。
- [ ] Q7 新用户首启引导 + 介绍 API：**待做**（需新 onboarding 页）。
- [x] Q9 无剧透雷达提示词升级：要求前期/中期/后期节奏 + 好评/争议/雷点 + 适合人群 + 观看时机，资料有限也要基于题材常识给具体判断（已配置 AI 时由 AiEngine 缓存到 AI_RESULT）。
- [x] Q10 适合心情兜底：无心情可推断时直接展示高频社区标签；推荐理由有题材兜底。
- [x] Q11 口味画像页顶部遮挡：去掉 contentWindowInsets=0，用 Scaffold 默认 inset。
- [ ] Q12 取消本地状态清云端：v0 无「删除收藏」端点（网页可删）；本轮已做 rate=0/短评清空，状态删除待调研更底层端点。
- [x] Q13 评分差异 Tab 强制换行：Tab 文案单行不折行。
- [ ] Q14 主页近期热门按热度：**待做**（需为季度作品取评分人数排序）。
- [x] Q15 主页继续看限 3，按最近更新进度排序。
- [x] Q16 口味匹配度多维模型：题材覆盖率拉升 + 负信号 + 社区评分 + 评分习惯微调，区分度更大（不再都 50-70）。
- [x] Q17 榜单缓存：按范围内存缓存，切回不重载；重试/刷新强制重载。
- [x] Q18 筛选结果点开「暂无内容」：筛选卡片携带真实 work id 导航。
- [x] Q19 AI 口味匹配提示词升级：要求强区分度评分（避免都 80%）+ 结合用户最爱/最不爱作品类比。
- [ ] Q20 自建反代显示评论：评论走官方 next.bgm.tv/p1；自建反代需**额外代理 next.bgm.tv/p1** 才能显示评论。专用「评论 API 反代地址」设置项待后续加（避免误改官方用户）。


## Final Sprint · R 轮（0.10.0-Q 复测反馈 · AI Provider 修复 + 凭据保留）

> 根因已定位：(1) `DefaultAiProviderSelector` 恒返回 `ProviderId.OPENAI`，用户选 DeepSeek/Gemini 等被忽略——Gemini 因协议不同**完全不工作**；(2) `AiEngineImpl` 首个请求用 `json_schema`，许多中转站/模型（Kimi、部分 DeepSeek 派生）返回 400 即直接失败、无降级；(3) 连接探针 `maxTokens=1` 让推理型模型（Kimi）产出 0 可见 token → 误判失败；(4) 保存后输入框全清空，已填的 baseUrl/模型名也消失。

- [x] R-new1 AI provider 选择真正生效：新增 `PersistedAiProviderSelector`（读 `SettingsDataStore.aiProviderId`，未设置时按已配置 baseUrl 主机名推断 Gemini/DeepSeek/OpenRouter/OpenAI/自定义），替换恒 OPENAI 的默认实现；`SettingsViewModel.onAiProviderSelect`/`onAiSave` 持久化所选 `ProviderId`；`AiModule` 改绑持久化选择器。
- [x] R-new1 结构化输出降级：`AiEngineImpl.requestWithFormatFallback` 首个请求按「spec 格式(json_schema) → json_object → 纯文本」序列降级，仅在「可能由 response_format 不被支持」的错误（Server/AiMalformed/FieldMissing，多为 400）时重试，鉴权/限流/网络不重试。修复 Kimi/部分中转站「无法使用」。
- [x] R-new1 连接探针：AI 测试探针 `maxTokens` 1→32，避免推理型模型（Kimi）产 0 可见 token 误判「测试失败」；探针用所选 provider（随选择器修复）。
- [x] R-new2 凭据保存后保留：敏感字段（API Key/Token/Client*）保存后以掩码占位「已保存 ••••••（重新输入以更换）」展示、**禁用临时显示**（明文绝不回读 UI），重新输入新值时恢复可显；非敏感字段（Base URL/模型名）保存后继续可见，并在重进设置页时从加密存储回读（baseUrl/model 非敏感）。VM `onToggleReveal`/`onToggleAiReveal` 仅在有新草稿时允许显示；新增 `CredentialFieldState.savedMasked`。
- [x] R-new3 口味匹配 AI 输入含作品主要 tag：已满足——`onAnalyzeMatchWithAi` 已纳入 `work.tags`（含社区 CONTENT 标签）+ 高/低分桶标签 + 最爱/最不爱作品锚点。
- [ ] Q4（顺延）拔作岛 1 人 6 分：需真机抓包确认搜索命中的 subjectId 与评分人数字段映射，**待用户协助抓包**。
- [ ] Q7（顺延）新用户首启引导 + API 说明页。
- [ ] Q12（顺延）Bangumi 删除收藏端点调研。
- [ ] Q14（顺延）主页本季按评分人数/热度排序。
- [ ] Q20（顺延）专用「评论 API 反代地址」设置项（p1 单独反代）。
