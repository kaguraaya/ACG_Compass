# Requirements Document

## Introduction

ACG Compass 是一个原生 Android 应用，定位为多平台 ACG（动画 / 漫画 / 游戏 / Visual Novel）补番补游整理与决策助手。它不提供在线播放、下载或盗版资源聚合，而是在 Bangumi、AniList、MAL/Jikan、VNDB 等数据源之上提供一个个人决策层：批量导入想看清单、聚合多平台评分、导入个人评分与短评、生成无剧透评价摘要、分析个人口味、并推荐“今晚看什么”。

交付目标为可直接安装的 Android APK：无广告、低成本、用户隐私数据本地保存。MVP 版本不需要后端服务器——搜索、详情、待补池、批量导入、评分聚合、时光机快照、设置以及 AI 调用均在用户本机完成，并使用用户自行配置的第三方 API。

本需求文档按 PRD 定义的 RC.xx.yy 编号体系组织，每条需求引用对应的 RC 编号以映射 PRD 验收条件。所有需求均须保留（不存在“可不做”的功能）；允许按优先级（P0/P1/P2）分阶段实现，但数据模型与页面结构必须为后续功能预留位置。

本文档使用中文编写以匹配 PRD 语言；验收条件采用 EARS 格式。

## Glossary

- **System**：ACG Compass 应用本体，泛指在用户设备上运行的软件实体。
- **ACG_Compass**：本产品 Android 应用整体，等同于 System。
- **Navigation_Controller**：负责底部五栏导航、页面切换与返回栈管理的导航组件。
- **Home_Screen**：首页“今日决策中心”。
- **Discover_Screen**：发现页，用于搜索与寻找新作品。
- **Backlog_Screen**：待补池页，用于管理想看 / 想读 / 想玩内容。
- **TimeMachine_Screen**：时光机页，用于回顾个人评分、短评、状态变化与报告。
- **Mine_Screen**：我的页，承载账号状态、统计、口味画像入口、导入导出与设置入口。
- **Settings_Screen**：设置页，从我的页进入，用于管理数据源凭据、AI Provider 与隐私选项。
- **Search_Module**：搜索与多源匹配模块，位于首页和发现页顶部。
- **Import_Module**：批量导入模块（补番收纳箱）。
- **Detail_Screen**：作品详情页。
- **Recommender**：今晚看什么推荐器。
- **Spoiler_Radar**：无剧透评价雷达模块。
- **Taste_Profile**：个人口味画像与匹配度模块。
- **Route_Map**：补番路线图与系列管理模块。
- **Time_Machine**：本地时光机与年度报告模块的数据与逻辑层。
- **AI_Engine**：负责构造提示词、调用用户配置的 AI Provider、解析结构化输出并执行剧透保护的模块。
- **AI_Provider**：用户选择并配置的第三方 AI 服务（OpenAI / Gemini / DeepSeek / OpenRouter / 自定义 OpenAI 兼容）。
- **Local_Fallback**：在未配置 AI key 时使用关键词与标签统计生成结果的本地规则引擎。
- **Data_Source**：外部数据源，包括 Bangumi、AniList、Jikan/MAL 公共、MAL 官方 API、VNDB。
- **Bangumi**：P0 主数据源，提供中文条目、评分、标签、收藏、个人评分/短评、进度、关联作品、角色。
- **AniList**：P1 辅助数据源，提供国际评分、热度、趋势、Reviews、用户列表、罗马音/英文标题、Staff、本季信息。
- **Jikan**：P1/P2 补充数据源，非官方 MAL API，提供 MAL 评分、排名、Reviews、Recommendations。
- **MAL_Official**：P2 用户数据源，MyAnimeList 官方 API，提供 OAuth 用户列表。
- **VNDB**：P2 数据源，提供 Visual Novel 资料；需成人内容分级控制。
- **Credential_Store**：本地凭据存储，仅保存在用户设备上的第三方凭据。
- **Backlog_Item**：待补池中的一条想看/想读/想玩作品记录。
- **Work**：一部作品（动画/漫画/小说/游戏/VN 等）。
- **Import_Batch**：一次批量导入生成的批次记录。
- **Snapshot**：时光机的本地数据快照。
- **Match_Confidence**：多源匹配的置信度。
- **Completion_Cost**：补完成本（按集数、单集时长、卷数、估计游玩时长计算）。
- **Page_State**：页面状态，取值为加载中、空、错误、未授权、限流、无网络、数据部分缺失之一。

## Requirements

### Requirement 1: 全局产品规则、隐私与安全（RC.00）

**User Story:** 作为一名注重隐私的用户，我希望应用永远把数据保存在本机、永不内置任何第三方密钥、并在升级时绝不清除数据，以便我可以放心长期使用。

#### Acceptance Criteria

1. THE System SHALL 将用户的全部业务数据（收藏、待补池、评分、短评、标签、快照、设置）保存在设备本地存储中（RC.00）。
2. THE System SHALL 在代码、资源文件、示例配置、测试文件、README 截图与日志中均不包含任何第三方 key、token、client secret 或 AI API key（RC.00）。
3. WHEN 用户尚未配置 AI key，THE AI_Engine SHALL 将所有 AI 功能保持在“未配置”状态并默认关闭（RC.00 / RC.14.01）。
4. WHEN 用户触发任一需要发送数据的 AI 请求，THE AI_Engine SHALL 在发送前提示用户将把标题、简介、标签、评论摘要或用户选择的数据发送给所选 AI_Provider（RC.00）。
5. WHEN 用户执行数据导出，THE System SHALL 默认不包含任何 key 或 token（RC.00 / RC.16.01）。
6. IF 用户显式选择导出设置信息，THEN THE System SHALL 要求二次确认并默认对凭据字段脱敏（RC.00）。
7. THE System SHALL 在日志、错误报告与文档中仅记录脱敏后的凭据，绝不记录完整 key 或 token（RC.00）。
8. WHEN 应用升级到新版本，THE System SHALL 保留旧版本的数据库、缓存、设置、收藏、评分、短评与快照并执行迁移或兼容策略（RC.00 / RC.16.03）。
9. THE System SHALL 不提供在线播放、漫画源聚合、下载、盗版资源搜索、弹幕抓取或网页抓取功能（RC.00）。

### Requirement 2: 项目文档制度（RC.00 文档制度）

**User Story:** 作为开发者，我希望项目维护三份规范文档并使用统一编号，以便需求、实现与经验可追溯。

#### Acceptance Criteria

1. THE System 项目仓库 SHALL 包含需求文档 REQUIREMENTS.md，记录每个功能的 RC 编号、页面位置、用户目标、优先级、状态与验收方式（RC.00 文档制度）。
2. THE System 项目仓库 SHALL 包含开发文档 DEVELOPMENT.md，记录已核验 API 文档链接、核验日期、实际字段、接口失败处理与数据迁移说明（RC.00 文档制度）。
3. THE System 项目仓库 SHALL 包含经验文档 EXPERIENCE.md，记录问题现象、原因、修复方式、避免策略与相关 RC 编号（RC.00 文档制度）。
4. WHEN 新增功能、修复问题、调整接口或修改页面布局，THE 开发流程 SHALL 同步更新对应文档（RC.00 文档制度）。
5. THE System SHALL 在代码注释、任务拆分与自检记录中使用统一 RC.xx.yy 编号（RC.00 文档制度）。

### Requirement 3: 数据源接入与 API 核验（RC.01）

**User Story:** 作为用户，我希望应用从多个 ACG 数据源获取数据并优雅降级，以便在不同源缺失或失败时仍能得到可信信息且不被伪造内容误导。

#### Acceptance Criteria

1. THE System SHALL 支持从 Bangumi 作为 P0 主源获取条目资料、中文评分、排名、标签、收藏、用户个人评分、用户个人短评、进度、关联作品与角色/人物（RC.01）。
2. THE System SHALL 在向 Bangumi 发起请求时附带符合 Bangumi 要求、且包含应用名称与版本标识的 User-Agent（RC.01）。
3. WHERE 用户已启用 AniList，THE System SHALL 作为 P1 辅助源通过 GraphQL 按需获取国际评分、热度、趋势、Reviews、用户列表、罗马音/英文标题、角色/Staff 与本季信息（RC.01）。
4. WHERE 用户已启用 Jikan，THE System SHALL 作为 P1/P2 补充源获取 MAL 评分、排名、人气、Reviews 与 Recommendations，并将请求速率节流至不超过约 3 req/s 与 60 req/min（以最新官方文档为准）（RC.01）。
5. WHERE 用户已配置 MAL_Official 的 Client ID，THE System SHALL 作为 P2 用户源通过 OAuth 获取官方用户列表、进度与评分（RC.01）。
6. WHERE 用户已配置 VNDB，THE System SHALL 作为 P2 源获取 Visual Novel 资料、评分、标签、角色、Staff、制作社与用户 VN 列表（RC.01）。
7. IF 某一数据源的某项数据缺失，THEN THE System SHALL 在该项位置显示“暂无数据”而非伪造数据，且不影响同一条目其余可用字段的正常展示（RC.01 / 第 11 节降级策略）。
8. IF 优先数据源请求失败，THEN THE System SHALL 按 Bangumi（P0）→ AniList（P1）→ Jikan（P1/P2）→ MAL_Official（P2）→ VNDB（P2）的降级顺序回退到下一个可获取的数据源，并在界面以来源标签标记当前数据来源（RC.01 / 第 11 节）。
9. IF 某数据源的请求在 10 秒内未返回或返回错误（包括 401/403/404/429/500、网络超时、字段缺失或空结果），THEN THE System SHALL 视该源本次不可用、按第 8 条降级顺序回退，并显示包含失败原因与重试按钮的错误提示且不崩溃（RC.01 / 第 11 节）。
10. WHILE 对某数据源的请求速率达到其速率限制的 80% 或以上，THE System SHALL 对该源进行节流以使请求不超过该源的速率限制（RC.01）。
11. WHEN 显示中文条目资料且 Bangumi 不可用，THE System SHALL 使用 AniList/Jikan 英文资料并允许用户手动修正标题（RC.01 / 第 11 节）。

### Requirement 4: 设置页与用户凭据管理（RC.02）

**User Story:** 作为用户，我希望在设置页自行填写、测试、查看与删除各数据源和 AI Provider 的凭据，以便我完全掌控自己的隐私凭据。

#### Acceptance Criteria

1. THE Settings_Screen SHALL 为每个 Data_Source 与 AI_Provider 提供一个可折叠卡片，包含凭据输入、连接测试、状态显示、清除按钮与官方文档链接（RC.02 / 第 13 节设置卡片）。
2. THE Settings_Screen SHALL 默认隐藏凭据字段，并提供临时显示凭据的开关（RC.02）。
3. WHEN 用户保存任一凭据，THE System SHALL 告知用户“凭据仅保存在本机，用于直接向第三方服务请求数据”（RC.02）。
4. WHEN 用户点击某数据源的连接测试，THE System SHALL 显示测试结果为成功、失败原因或文档入口之一（RC.02 / 第 12 节配置流程）。
5. THE Settings_Screen SHALL 为 Bangumi 提供 Token/OAuth 输入区、连接测试、当前用户名、同步开关与清除授权按钮（RC.02）。
6. THE Settings_Screen SHALL 为 AniList 提供 Token 输入区或登录说明、连接测试与用户列表读取开关（RC.02）。
7. THE Settings_Screen SHALL 为 MAL_Official 提供 Client ID 输入、可选 Client Secret 输入与 OAuth/PKCE 状态，并仅在用户明确配置后启用（RC.02）。
8. THE Settings_Screen SHALL 为 Jikan 提供启用开关、请求频率提示与失败降级提示，且不要求 key（RC.02）。
9. THE Settings_Screen SHALL 为 VNDB 提供 Token 输入区、权限说明、成人内容显示开关与分级过滤（RC.02）。
10. THE Settings_Screen SHALL 为 AI_Provider 提供 Provider 选择（OpenAI / Gemini / DeepSeek / OpenRouter / 自定义 OpenAI 兼容）、API key 输入、Base URL 输入、模型名输入、测试按钮与预算提醒（RC.02 / RC.14.01）。
11. THE Settings_Screen SHALL 提供隐私控制项：本地数据清除、缓存清除、导入导出、是否允许 AI 分析评论、是否记录本地时光机快照（RC.02 / RC.15.04）。
12. WHEN 用户点击清除某凭据，THE System SHALL 从 Credential_Store 删除该凭据并更新其状态显示（RC.02）。
13. IF 某 API 需要 OAuth 或 App Client 信息，THEN THE System SHALL 支持用户手动填写 token/client 信息并提示用户如何自行创建，且不写入任何开发者私有信息（RC.02 / RC.00）。

### Requirement 5: 导航、启动与全局状态（RC.03）

**User Story:** 作为用户，我希望应用有清晰的导航、首次启动引导以及所有页面统一的状态处理，以便在任何情况下都能理解发生了什么并知道下一步。

#### Acceptance Criteria

1. WHEN 用户首次启动应用，THE System SHALL 展示引导，说明应用不提供播放/下载、数据默认本地保存、可稍后配置 API key（RC.03.01）。
2. THE Navigation_Controller SHALL 提供固定的五栏底部导航：首页、发现、待补池、时光机、我的，并高亮当前页（RC.03.02）。
3. WHEN 用户在五栏之间切换或返回，THE Navigation_Controller SHALL 维持自然的返回栈逻辑（RC.03.02）。
4. WHEN 任一页面处于无数据、无网络、未配置 key、无搜索结果或无 AI key 状态，THE System SHALL 显示友好的空状态说明与下一步操作按钮（RC.03.03）。
5. IF 接口失败、限流、授权失效、字段缺失或 AI 输出异常，THEN THE System SHALL 显示可理解的错误提示并保持应用不崩溃（RC.03.04）。
6. THE 错误卡片 SHALL 包含简短原因、用户下一步、重试按钮与查看文档按钮（RC.03.04 / 第 13 节错误卡片）。
7. THE System SHALL 为每个页面定义并支持以下 Page_State：加载中、空、错误、未授权、限流、无网络、数据部分缺失（RC.03.03 / RC.03.04 / 第 13 节）。
8. THE System SHALL 呈现现代 Android 原生视觉：清晰卡片、圆角、深色模式适配、顺滑过渡与可读排版（RC.03.05）。
9. THE 作品卡片 SHALL 统一展示封面、标题、别名/年份、类型、评分、来源、待补状态、补完成本与风险/心情标签（RC.03.05 / 第 13 节作品卡片）。

### Requirement 6: 首页 — 今日决策中心（RC.04）

**User Story:** 作为用户，我希望首页是一个决策中心，集中展示今晚看什么、当前状态、进行中作品与待补概览，以便快速决定现在该做什么。

#### Acceptance Criteria

1. THE Home_Screen SHALL 在首屏展示“今晚看什么”大卡片，WHEN 用户点击该卡片，THE System SHALL 进入 Recommender（RC.04.01）。
2. THE Home_Screen SHALL 提供今日状态选择，包含轻松、胃疼、热血、恋爱、悬疑、神作补课、短篇、想被震撼、电波等状态（RC.04.02）。
3. THE Home_Screen SHALL 展示“继续看/读/玩”区，显示正在进行作品、进度与下次应继续的位置（RC.04.03）。
4. THE Home_Screen SHALL 展示待补池概览，包含待补数量、最近加入、吃灰最久、短篇可补与高匹配作品（RC.04.04）。
5. THE Home_Screen SHALL 在顶部提供搜索入口，进入 Search_Module（RC.04 / 第 7 节）。
6. THE Home_Screen SHALL 提供批量导入入口，进入 Import_Module（RC.04 / 第 7 节）。
7. WHERE 用户已配置至少一个可同步数据源，THE Home_Screen SHALL 展示同步提醒，包含最近 Bangumi/AniList/VNDB 同步时间、失败提醒与手动同步入口（RC.04.05）。
8. THE Home_Screen SHALL 展示今日补番签，输出今日宜/忌建议（RC.04.06）。

### Requirement 7: 发现、搜索与多源匹配（RC.05）

**User Story:** 作为用户，我希望通过多种名称搜索并跨源合并结果，以便准确找到作品并在匹配不准时手动纠正。

#### Acceptance Criteria

1. THE Search_Module SHALL 支持按中文名、日文名、罗马音、英文名与别名搜索（RC.05.01）。
2. WHEN 同一作品来自多个数据源，THE Search_Module SHALL 合并展示该作品并显示来源标签与 Match_Confidence（RC.05.02）。
3. IF 某条匹配为低置信，THEN THE Search_Module SHALL 允许用户手动选择正确条目（RC.05.03）。
4. THE Discover_Screen SHALL 分别展示 Bangumi/AniList/Jikan/MAL 榜单并标记数据来源（RC.05.04）。
5. THE Discover_Screen SHALL 提供评分差异榜，突出不同社区评分差距大的作品，并用中性文字说明“可能存在圈层口味差异”（RC.05.05）。
6. THE Discover_Screen SHALL 提供高级筛选，可按类型、状态、篇幅、评分、年份、完结状态、来源平台、风险标签与心情标签筛选（RC.05.06 / 第 9 节）。
7. THE Discover_Screen SHALL 在顶部提供搜索入口、并展示本季、榜单、冷门高分与短篇佳作（RC.05 / 第 7 节）。

### Requirement 8: 批量导入 — 补番收纳箱（RC.06）

**User Story:** 作为用户，我希望粘贴或导入一段推荐清单并自动拆分匹配作品名，以便快速把分散的安利整理进待补池。

#### Acceptance Criteria

1. WHEN 用户粘贴一段推荐清单文本，THE Import_Module SHALL 自动拆分出疑似作品名（RC.06.01）。
2. WHEN 用户从剪贴板导入，THE Import_Module SHALL 解析书名号、顿号、逗号、换行与编号列表（RC.06.02）。
3. WHERE 用户选择本地 TXT/CSV 文件，THE Import_Module SHALL 导入作品名与可选备注（RC.06.03）。
4. WHERE 截图 OCR 导入功能尚未实现，THE Import_Module SHALL 预留入口与权限说明（RC.06.04）。
5. WHEN 一次导入完成，THE Import_Module SHALL 生成 Import_Batch，记录批次名、时间、来源、识别数量、成功数量与失败数量（RC.06.05）。
6. WHEN 同一作品被多次导入，THE Import_Module SHALL 记录该作品的“被安利次数”（RC.06.06）。
7. WHEN 用户确认匹配后执行一键加入，THE Import_Module SHALL 批量加入待补池并自动去重（RC.06.07）。
8. IF 某导入条目为低置信匹配，THEN THE Import_Module SHALL 要求用户确认后再加入待补池（RC.06 / RC.05.03 / 第 12 节）。

### Requirement 9: 作品详情页（RC.07）

**User Story:** 作为用户，我希望在作品详情页看到聚合的多平台评分、个人记录与决策信息，以便在不被剧透的情况下决定是否观看。

#### Acceptance Criteria

1. THE Detail_Screen SHALL 展示顶部信息区：封面、标题、原名、中文名、别名、类型、年份、状态、集数/卷数/游玩时长（RC.07.01）。
2. THE Detail_Screen SHALL 展示评分区，分别显示 Bangumi、AniList、MAL/Jikan、VNDB 的评分与人数（RC.07.02）。
3. IF 某平台评分缺失，THEN THE Detail_Screen SHALL 在该平台位置显示“暂无数据”而不隐藏整个评分区域（RC.07.02 / 第 13 节评分卡片）。
4. THE Detail_Screen SHALL 展示社区共识卡，综合呈现评分稳定度、争议程度与补番优先级，且不伪造客观结论（RC.07.03）。
5. THE Detail_Screen SHALL 展示个人区：我的状态、我的评分、我的进度、我的短评、我的标签与加入/移出待补池操作（RC.07.04）。
6. THE Detail_Screen SHALL 展示决策区：口味匹配度、推荐/不推荐理由、无剧透评价雷达、适合心情与补完成本（RC.07.05）。
7. THE Detail_Screen SHALL 提供详情 Tab：简介、评论摘要、角色/Staff、关联作品、观看路线、平台数据与我的记录（RC.07.06）。
8. THE Detail_Screen SHALL 按集数、单集时长、卷数与估计游玩时长计算并展示 Completion_Cost 为“今晚/周末/长期坑”分类（RC.07.07）。

### Requirement 10: 待补池与决策池（RC.08）

**User Story:** 作为用户，我希望管理所有想看/想读/想玩的作品，并能筛选、排序、设优先级与一键抽番，以便降低选择成本。

#### Acceptance Criteria

1. THE Backlog_Screen SHALL 展示所有 Backlog_Item，每张卡片包含封面、标题、评分、匹配度与补完成本（RC.08.01）。
2. THE Backlog_Screen SHALL 支持按类型、评分、匹配度、加入时间、补完成本、完结状态与心情标签筛选排序（RC.08.02 / 第 9 节）。
3. THE Backlog_Screen SHALL 允许用户为每个 Backlog_Item 设置高/中/低优先级与自定义备注（RC.08.03）。
4. THE Backlog_Screen SHALL 显示作品在待补池停留的吃灰天数，WHEN 停留时间超过阈值，THE System SHALL 将该作品归入“吃灰博物馆”（RC.08.04 / RC.18.02）。
5. THE Backlog_Screen SHALL 支持批量操作：批量删除、批量改状态、批量同步与批量加标签（RC.08.05）。
6. WHEN 用户触发一键抽番，THE Backlog_Screen SHALL 抽出一个作品并显示抽中理由，而非纯随机（RC.08.06）。
7. THE Backlog_Screen SHALL 提供批量导入入口，进入 Import_Module（RC.08 / 第 7 节）。

### Requirement 11: 无剧透评价雷达（RC.09）

**User Story:** 作为用户，我希望看到只总结风格、节奏、争议与雷点而不泄露关键剧情的评价摘要，以便在不被剧透的情况下判断作品是否适合我。

#### Acceptance Criteria

1. THE Spoiler_Radar SHALL 仅总结风格、节奏、争议与雷点，且不泄露关键剧情、角色结局、反转与真相（RC.09.01）。
2. THE Spoiler_Radar SHALL 输出完整维度：总体印象、优点、争议、雷点、适合人群、不适合人群、观看时机与数据置信度（RC.09.02）。
3. WHEN 用户未配置 AI key，THE Spoiler_Radar SHALL 使用 Local_Fallback 通过关键词与标签统计生成基础雷达（RC.09.03）。
4. WHERE 用户已配置 AI key，THE Spoiler_Radar SHALL 提供对评论/标签/简介更自然的 AI 增强摘要（RC.09.04）。
5. THE Spoiler_Radar SHALL 支持剧透等级（无剧透、轻微剧透、完整分析），并至少完成无剧透等级（RC.09.05）。
6. THE Spoiler_Radar SHALL 显示摘要来源：用户短评、公共 Reviews、标签统计或 AI 总结（RC.09.06）。
7. IF 评论中出现剧透，THEN THE Spoiler_Radar SHALL 仅将其抽象成非具体表述（如“中后期有重要转折”“结尾争议较大”）且不复述原文剧透句（RC.09.01 / RC.14.04）。

### Requirement 12: 个人口味画像与匹配度（RC.10）

**User Story:** 作为用户，我希望应用从我的历史评分中分析出我的口味偏好与匹配度，以便了解自己并预测是否会喜欢某部作品。

#### Acceptance Criteria

1. WHERE 用户已配置数据源，THE Taste_Profile SHALL 从 Bangumi 等源导入用户自己的评分、状态、短评与标签（RC.10.01）。
2. THE Taste_Profile SHALL 统计高分作品标签、低分作品标签、常用短评词与常见搁置类型（RC.10.02）。
3. THE Taste_Profile SHALL 在详情页显示“你可能会不会喜欢”的口味匹配度并给出理由（RC.10.03）。
4. THE Taste_Profile SHALL 生成评分习惯：评分严格度、平均分、高分稀有度与常见分数段（RC.10.04）。
5. THE Taste_Profile SHALL 生成口味称号（如世界观考据型观众、胃疼收藏家、规则系剧情捕手、短篇完结党）（RC.10.05 / RC.18.03）。
6. THE Taste_Profile SHALL 找出用户经常收藏但总是搁置/抛弃的类型作为“口味黑洞”（RC.10.06）。
7. IF 样本数量不足，THEN THE Taste_Profile SHALL 降低置信度并使用“可能”“倾向于”等措辞而非绝对判断（RC.10 / 第 10.2 节）。

### Requirement 13: 今晚看什么推荐器（RC.11）

**User Story:** 作为用户，我希望根据可用时间、心情与接受程度从待补池得到带理由的推荐，以便不再纠结今晚看什么。

#### Acceptance Criteria

1. THE Recommender SHALL 提供时间选择：20 分钟、1 小时、2-3 小时、周末通宵（RC.11.01）。
2. THE Recommender SHALL 提供心情选择：轻松、胃疼、热血、恋爱、悬疑、神作补课、短篇、电波、想被震撼（RC.11.02）。
3. THE Recommender SHALL 提供接受程度选择：可慢热、可致郁、可党争、可长篇、不要剧透、不要太累、不要未完结（RC.11.03）。
4. WHEN 用户提交时间、心情与接受程度，THE Recommender SHALL 给出三个推荐：稳妥选择、赌一把选择、神经病选择，且每个都附理由（RC.11.04）。
5. WHERE 用户开启“不准纠结模式”，THE Recommender SHALL 只给一个推荐并提供“就看这个”的明确理由（RC.11.05）。
6. WHERE 用户开启“期末周保护模式”，THE Recommender SHALL 过滤长篇、致郁、高上头风险与未完结作品（RC.11.06）。
7. WHILE 当前为深夜时段，THE Recommender SHALL 对胃疼/压抑/长篇作品给出温柔提醒（RC.11.07）。
8. THE Recommender SHALL 不推荐用户已完成的作品，且不推荐不符合硬性过滤条件的作品（RC.11 / 第 10.3 节）。

### Requirement 14: 补番路线图与系列管理（RC.12）

**User Story:** 作为用户，我希望看到一部作品的系列关联与推荐观看顺序，以便正确安排补番路线而不被错误顺序误导。

#### Acceptance Criteria

1. THE Route_Map SHALL 在详情页展示续作、前传、外传、OVA、剧场版、总集篇等关联作品（RC.12.01）。
2. THE Route_Map SHALL 生成推荐观看顺序，标记必看、可选、可跳过与总集篇提示（RC.12.02）。
3. THE Route_Map SHALL 显示用户在该系列中已看/未看/搁置的节点（RC.12.03）。
4. WHEN 用户选择一键加入系列，THE Route_Map SHALL 将整个系列或必看节点加入待补池（RC.12.04）。
5. IF 关联资料不足，THEN THE Route_Map SHALL 标记“路线待确认”且不编造观看顺序（RC.12.05 / 第 10.4 节）。

### Requirement 15: 本地时光机与年度报告（RC.13）

**User Story:** 作为用户，我希望应用记录我的评分、短评与状态变化并生成报告，以便回顾自己的 ACG 历程与口味变化。

#### Acceptance Criteria

1. WHEN 用户首次导入收藏，THE Time_Machine SHALL 导入当前收藏、评分、短评与进度并形成本地初始 Snapshot（RC.13.01）。
2. WHEN 用户后续同步，THE Time_Machine SHALL 记录新增收藏、状态变化、评分变化、短评变化与进度变化（RC.13.02）。
3. THE TimeMachine_Screen SHALL 按月/年展示用户自己的 ACG 活动时间线（RC.13.03）。
4. THE TimeMachine_Screen SHALL 提供“以前的我怎么想”，随机展示过去短评与当时评分（RC.13.04）。
5. THE TimeMachine_Screen SHALL 提供“评分打脸现场”，发现以前低分后来高分或反向变化（RC.13.05）。
6. THE TimeMachine_Screen SHALL 生成月度/年度报告，包含看了多少、平均评分、最高分、常见标签、口味变化与吃灰作品（RC.13.06）。
7. THE Time_Machine SHALL 仅从首次同步开始记录变化，且不承诺完整复刻 Bangumi 历史（RC.13 / 第 11 节）。

### Requirement 16: AI 提示词与结构化输出（RC.14）

**User Story:** 作为用户，我希望 AI 功能即使使用低质量模型也能输出固定结构、禁止剧透并标注置信度，以便 AI 结果可信且可直接展示。

#### Acceptance Criteria

1. THE AI_Engine SHALL 由用户选择并配置 AI_Provider，且不内置任何 key（RC.14.01）。
2. THE AI_Engine SHALL 为防剧透雷达、口味画像、今晚推荐与路线图使用固定的结构化输出格式（RC.14.02）。
3. WHEN AI 输出缺少必需字段，THE AI_Engine SHALL 发起“修复成指定格式”的二次请求（RC.14.03）。
4. IF AI 无法判断，THEN THE AI_Engine SHALL 输出低置信结果而非编造内容（RC.14.03）。
5. THE AI_Engine SHALL 在系统提示词中强制禁止泄露关键剧情、死亡、身份、结局、反转与 CP 结果等剧透（RC.14.04）。
6. WHEN 用户触发 AI 分析，THE AI_Engine SHALL 显示估计成本范围并请求确认，并允许只分析摘要而非完整评论（RC.14.05）。
7. THE AI 卡片 SHALL 显示“AI 生成/规则生成”、生成时间、数据来源、置信度与重新生成按钮（RC.14 / 第 13 节 AI 卡片）。

### Requirement 17: 我的页、统计与账号状态（RC.15）

**User Story:** 作为用户，我希望在我的页查看账号配置状态、个人统计与口味画像入口，以便集中管理我的账号与数据。

#### Acceptance Criteria

1. THE Mine_Screen SHALL 显示 Bangumi/AniList/MAL/VNDB/AI 的配置状态与最后测试时间（RC.15.01）。
2. THE Mine_Screen SHALL 展示数据统计：看过、在看、想看、搁置、抛弃、平均评分、最高分与常见标签（RC.15.02）。
3. THE Mine_Screen SHALL 提供进入完整口味画像的入口（RC.15.03）。
4. THE Mine_Screen SHALL 提供隐私与导出操作：清除本地数据、导出备份、导入备份与清理缓存（RC.15.04）。
5. THE Mine_Screen SHALL 提供关于页，说明数据源、开源许可、免责声明与不提供盗版资源（RC.15.05）。
6. THE Mine_Screen SHALL 提供进入 Settings_Screen 的入口（RC.15 / 第 7 节）。

### Requirement 18: 数据导入导出、备份与迁移（RC.16）

**User Story:** 作为用户，我希望备份与恢复我的数据，并在应用升级时保留旧数据，以便我的个人 ACG 档案长期安全不丢失。

#### Acceptance Criteria

1. WHEN 用户执行本地备份，THE System SHALL 导出收藏、待补池、评分、短评、标签、快照与设置，且默认不导出 key（RC.16.01 / RC.00）。
2. IF 用户显式选择导出设置中的凭据，THEN THE System SHALL 二次确认并默认对凭据脱敏（RC.16.01 / RC.00）。
3. WHEN 用户导入旧备份，THE System SHALL 合并而不覆盖新数据，并提示冲突（RC.16.02）。
4. WHEN 应用升级，THE System SHALL 保留旧数据（RC.16.03）。
5. IF 数据迁移失败，THEN THE System SHALL 保留原始备份并提示用户恢复（RC.16.03）。
6. WHERE 用户选择 CSV 导出，THE System SHALL 允许导出待补池/时光机/评分表到 CSV（RC.16.04）。
7. WHERE 跨账号合并功能尚未实现，THE System SHALL 为 Bangumi 旧账号/新账号或多平台列表合并预留数据结构（RC.16.05）。
8. WHEN 备份序列化后再次导入，THE System SHALL 还原出与原始数据等价的业务数据（RC.16 round-trip / 第 14 节导入测试）。

### Requirement 19: 测试、自检与异常处理（RC.17）

**User Story:** 作为开发者与评审者，我希望应用在各种安装、升级、凭据、API、AI、导入、时光机与页面场景下都被验证，以便确保稳定性、隐私与数据安全。

#### Acceptance Criteria

1. WHEN 在无账号、无 key、无网络条件下干净安装 APK，THE System SHALL 启动且不崩溃（RC.17 / 第 14 节安装测试）。
2. WHEN 从旧版本升级，THE System SHALL 保留待补池、评分、短评、快照与设置数据（RC.17 / 第 14 节升级测试）。
3. WHEN key 被填错、过期、删除、导出、记录到日志或出现在截图场景，THE System SHALL 不泄露完整 key（RC.17 / 第 14 节凭据测试 / RC.00）。
4. WHEN API 返回成功、404、401、403、429、500、字段缺失、空数组、限流或网络超时，THE System SHALL 给出对应的可恢复处理且不崩溃（RC.17 / 第 14 节 API 测试 / RC.03.04）。
5. WHEN AI 处于无 key、本地规则版、模型输出不完整、输出包含剧透、JSON/结构损坏或超时场景，THE System SHALL 进行降级或修复处理（RC.17 / 第 14 节 AI 测试 / RC.14）。
6. WHEN 导入中文/日文/英文/罗马音、多行、逗号、书名号、重复标题、同名作品或低置信匹配文本，THE Import_Module SHALL 正确解析并标记需确认项（RC.17 / 第 14 节导入测试）。
7. WHEN 进行首次快照、二次同步、评分变化、短评变化、状态变化或删除/合并冲突，THE Time_Machine SHALL 正确记录变化并处理冲突（RC.17 / 第 14 节时光机测试）。
8. WHEN 在深色模式、大字体、长标题、封面缺失、横竖屏、低端机或返回栈场景下使用，THE System SHALL 正确渲染页面（RC.17 / 第 14 节页面测试）。

### Requirement 20: 趣味功能（RC.18）

**User Story:** 作为用户，我希望应用提供有趣的衍生功能，以便整理与回顾过程更有乐趣。

#### Acceptance Criteria

1. THE System SHALL 显示安利债务，例如“你欠群友 N 部番没看”（RC.18.01）。
2. THE System SHALL 提供吃灰博物馆，展示待补池里躺太久的作品（RC.18.02 / RC.08.04）。
3. THE System SHALL 根据口味生成补番人格称号（RC.18.03 / RC.10.05）。
4. WHERE 补番遗书模式功能尚未实现，THE System SHALL 预留生成一句中二补番宣言的入口（RC.18.04）。
5. THE System SHALL 提供情绪风险提示，标记致郁、压抑、党争、高上头等风险（RC.18.05 / 第 9 节风险标签）。

## Requirement Priority Summary

下列优先级用于分阶段实现；P1/P2 功能的数据模型与页面结构必须在 MVP 阶段预留（RC.00 / 第 17 节）。

- **P0（MVP 必做）**：RC.00 全局规则与文档制度、RC.01 数据源核验、RC.02 设置与凭据、RC.03 导航与状态、RC.04.01–04.04 首页核心、RC.05.01–05.03 搜索与匹配、RC.06.01/02/05/07 批量导入核心、RC.07 详情页核心、RC.08.01–08.03 待补池核心、RC.09.01–09.03 无剧透雷达基础、RC.10.01/02 口味导入、RC.11.01–11.04 推荐器、RC.12.01 关联展示、RC.13.01–13.03 时光机核心、RC.14.01–14.04 AI 结构化与剧透保护、RC.15.01–15.04 我的页核心、RC.16.01–16.03 备份与迁移。
- **P1（次优先）**：RC.04.05/06、RC.05.04–05.06、RC.06.03/06、RC.07.03/07、RC.08.04–08.06、RC.09.04–09.06、RC.10.03–10.06、RC.11.05–11.07、RC.12.02–12.05、RC.13.04–13.06、RC.14.05、RC.15.05、RC.16.04、RC.18.01–18.03/18.05。
- **P2（预留与扩展）**：RC.06.04 截图 OCR、RC.16.05 跨账号合并、RC.18.04 补番遗书模式、MAL_Official OAuth、VNDB 成人内容扩展。

### Requirement 3: 数据源与 API 文档核验（RC.01）

**User Story:** 作为开发者，我希望在编码前核验各数据源的最新官方文档并记录核验结果，以便接口稳定且在变更时可快速定位。

#### Acceptance Criteria

1. WHEN 开始实现任一 Data_Source 的接入，THE 开发流程 SHALL 先联网核验该源的最新官方文档（授权方式、字段、速率限制、返回结构）并记录核验日期（RC.01）。
2. THE System SHALL 在向 Bangumi 请求时附带符合 Bangumi 要求的合理 User-Agent（RC.01）。
3. WHEN 任一接口路径、字段或授权方式与本文档不一致，THE 开发流程 SHALL 以最新官方文档为准，并在更新前先核对当前代码实际请求（RC.01 / RC.15 文档变更规则）。
4. THE System SHALL 遵守各源的速率限制（例如 Jikan 公共约 3 req/s、60 req/min，以最新文档为准），并在接近限制时进行节流（RC.01）。
5. IF 某接口（如 Bangumi 公共吐槽箱、完整历史时光机）无稳定官方接口，THEN THE System SHALL 降级处理且不得凭空假设或抓取网页（RC.01 / RC.11 降级策略）。
6. THE 开发流程 SHALL 将已核验的官方文档链接与核验日期写入 DEVELOPMENT.md（RC.01）。

### Requirement 4: 设置页与用户凭据管理（RC.02）

**User Story:** 作为用户，我希望在设置页自行填写、测试、查看与删除各数据源和 AI Provider 的凭据，并确信它们只保存在本机，以便安全地连接服务。

#### Acceptance Criteria

1. THE Settings_Screen SHALL 为 Bangumi、AniList、MAL_Official、Jikan、VNDB、AI_Provider 与隐私分别提供可折叠的设置卡片（RC.02）。
2. THE Settings_Screen SHALL 支持用户自行填写、测试连接、查看状态与删除凭据（RC.02）。
3. THE Settings_Screen SHALL 默认隐藏凭据字段并提供临时显示开关（RC.02）。
4. WHEN 用户保存凭据，THE Credential_Store SHALL 仅保存在本机并向用户明确告知“凭据仅保存在本机，用于直接向第三方服务请求数据”（RC.02 / RC.00）。
5. WHEN 用户点击测试连接，THE System SHALL 显示成功、失败原因或对应官方文档入口（RC.02）。
6. THE Bangumi 卡片 SHALL 提供 Token/OAuth 输入区、连接测试、当前用户名、同步开关与清除授权按钮（RC.02）。
7. THE AniList 卡片 SHALL 提供 Token 输入区或登录说明、连接测试与用户列表读取开关（RC.02）。
8. THE MAL_Official 卡片 SHALL 提供 Client ID、可选 Client Secret 与 OAuth/PKCE 状态，并仅在用户明确配置后启用（RC.02）。
9. THE Jikan 卡片 SHALL 无需 key，但提供启用开关、请求频率提示与失败降级提示（RC.02）。
10. THE VNDB 卡片 SHALL 提供 Token 输入区、权限说明、成人内容显示开关与分级过滤（RC.02）。
11. THE AI_Provider 卡片 SHALL 提供 Provider 选择（OpenAI / Gemini / DeepSeek / OpenRouter / 自定义 OpenAI 兼容）、API key、Base URL、模型名、测试按钮与预算提醒（RC.02 / RC.14.01）。
12. THE 隐私 卡片 SHALL 提供本地数据清除、缓存清除、导入导出、是否允许 AI 分析评论、是否记录本地时光机快照等开关（RC.02 / RC.15.04）。
13. IF 某 API 需要 OAuth 或 App Client 信息，THEN THE System SHALL 不写死开发者私有信息，并优先支持用户手动填写 token/client 信息或清晰提示自行创建的方式（RC.02 / RC.00）。

### Requirement 5: 导航、启动与全局状态（RC.03）

**User Story:** 作为用户，我希望应用拥有清晰的五栏导航、首启引导，以及在任何异常情形下都友好且不崩溃的页面状态，以便顺畅使用。

#### Acceptance Criteria

1. WHEN 用户首次启动应用，THE System SHALL 展示引导，说明应用不提供播放/下载、数据默认本地保存、可稍后配置 API key（RC.03.01）。
2. THE Navigation_Controller SHALL 提供固定的五栏底部导航：首页、发现、待补池、时光机、我的，当前页高亮且返回逻辑自然（RC.03.02）。
3. WHEN 出现无数据、无网络、未配置 key、无搜索结果或无 AI key 的情形，THE System SHALL 展示友好的空状态说明与下一步操作按钮（RC.03.03）。
4. WHEN 接口失败、限流、授权失效、字段缺失或 AI 输出异常，THE System SHALL 展示可理解的错误提示且不崩溃（RC.03.04）。
5. THE 错误状态 SHALL 包含简短原因、用户下一步、重试按钮与查看文档按钮（RC.03.04 / 页面展示细节）。
6. THE System SHALL 呈现现代 Android 原生视觉：清晰卡片、圆角、深色模式适配、顺滑过渡与可读排版（RC.03.05）。
7. THE 每个页面 SHALL 预留加载中、空、错误、未授权、限流、无网络、数据部分缺失的 Page_State 展示（RC.03 / 页面展示细节）。

### Requirement 6: 首页——今日决策中心（RC.04）

**User Story:** 作为用户，我希望首页能直接帮我决定现在看什么、显示当前进度与待补概览，以便降低选择成本。

#### Acceptance Criteria

1. THE Home_Screen SHALL 在首屏展示“今晚看什么”主卡片，点击进入 Recommender（RC.04.01）。
2. THE Home_Screen SHALL 提供今日状态选择（轻松、胃疼、热血、恋爱、悬疑、神作补课、短篇、想被震撼、电波等）（RC.04.02）。
3. THE Home_Screen SHALL 展示“继续看/读/玩”区，显示正在进行作品、进度与下次应继续的位置（RC.04.03）。
4. THE Home_Screen SHALL 展示待补池概览：待补数量、最近加入、吃灰最久、短篇可补与高匹配作品（RC.04.04）。
5. THE Home_Screen SHALL 展示同步提醒：最近 Bangumi/AniList/VNDB 同步时间、失败提醒与手动同步入口（RC.04.05）。
6. THE Home_Screen SHALL 展示“今日补番签”，输出今日宜/忌建议（RC.04.06）。
7. THE Home_Screen SHALL 提供进入批量导入的入口（RC.04 / RC.06）。

### Requirement 7: 发现、搜索与多源匹配（RC.05）

**User Story:** 作为用户，我希望跨多平台搜索并合并同一作品的多源结果，并能手动纠正低置信匹配，以便准确找到想看的作品。

#### Acceptance Criteria

1. THE Search_Module SHALL 支持按中文名、日文名、罗马音、英文名与别名搜索（RC.05.01）。
2. WHEN 同一作品来自多个 Data_Source，THE Search_Module SHALL 合并展示并显示来源标签与 Match_Confidence（RC.05.02）。
3. WHEN 匹配置信度较低，THE Search_Module SHALL 允许用户手动选择正确条目（RC.05.03）。
4. THE Discover_Screen SHALL 分别展示 Bangumi/AniList/Jikan/MAL 榜单并标记数据来源（RC.05.04）。
5. THE Discover_Screen SHALL 提供评分差异榜，突出社区评分差距大的作品并以中性文字解释可能的圈层口味差异（RC.05.05）。
6. THE Discover_Screen SHALL 提供高级筛选：类型、状态、篇幅、评分、年份、完结状态、来源平台、风险标签、心情标签（RC.05.06）。

### Requirement 8: 批量导入——补番收纳箱（RC.06）

**User Story:** 作为用户，我希望粘贴群友的安利清单后应用能自动拆分标题、多源匹配并一键加入待补池，以便快速整理分散的安利。

#### Acceptance Criteria

1. WHEN 用户粘贴一段推荐清单文本，THE Import_Module SHALL 自动拆分疑似作品名（RC.06.01）。
2. WHEN 用户从剪贴板导入，THE Import_Module SHALL 解析书名号、顿号、逗号、换行与编号列表（RC.06.02）。
3. THE Import_Module SHALL 支持导入本地 TXT/CSV 文件中的作品名与可选备注（RC.06.03）。
4. THE Import_Module SHALL 为截图 OCR 导入预留页面入口与权限说明（后续功能）（RC.06.04）。
5. WHEN 一次导入完成，THE Import_Module SHALL 生成包含批次名、时间、来源、识别数量、成功数量与失败数量的 Import_Batch（RC.06.05）。
6. WHEN 同一作品被多次导入，THE Import_Module SHALL 记录“被安利次数”（RC.06.06）。
7. WHEN 用户确认匹配，THE Import_Module SHALL 批量加入待补池并自动去重（RC.06.07）。

### Requirement 9: 作品详情页（RC.07）

**User Story:** 作为用户，我希望在详情页看到聚合的多平台评分、我的个人记录与决策辅助信息，以便决定是否补这部作品。

#### Acceptance Criteria

1. THE Detail_Screen SHALL 展示顶部信息区：封面、标题、原名、中文名、别名、类型、年份、状态、集数/卷数/游玩时长（RC.07.01）。
2. THE Detail_Screen SHALL 展示评分区：Bangumi、AniList、MAL/Jikan、VNDB 的评分与人数；缺失时显示“暂无数据”且不隐藏整个区域（RC.07.02 / 页面展示细节）。
3. THE Detail_Screen SHALL 展示社区共识卡：评分稳定度、争议程度、补番优先级，且不伪造客观结论（RC.07.03）。
4. THE Detail_Screen SHALL 展示个人区：我的状态、评分、进度、短评、标签与加入/移出待补池操作（RC.07.04）。
5. THE Detail_Screen SHALL 展示决策区：口味匹配度、推荐/不推荐理由、无剧透评价雷达、适合心情与补完成本（RC.07.05）。
6. THE Detail_Screen SHALL 提供详情 Tab：简介、评论摘要、角色/Staff、关联作品、观看路线、平台数据、我的记录（RC.07.06）。
7. THE Detail_Screen SHALL 按集数、单集时长、卷数与估计游玩时长计算 Completion_Cost 并归类为今晚/周末/长期坑（RC.07.07）。

### Requirement 10: 待补池与决策池（RC.08）

**User Story:** 作为用户，我希望集中管理所有想看内容，能筛选排序、设置优先级、批量操作并随机抽番，以便高效消化待补清单。

#### Acceptance Criteria

1. THE Backlog_Screen SHALL 展示所有想看/想读/想玩作品，卡片包含封面、标题、评分、匹配度与补完成本（RC.08.01）。
2. THE Backlog_Screen SHALL 支持按类型、评分、匹配度、加入时间、补完成本、完结状态与心情标签筛选排序（RC.08.02）。
3. THE Backlog_Screen SHALL 支持用户设置高/中/低优先级与自定义备注（RC.08.03）。
4. THE Backlog_Screen SHALL 显示作品的吃灰天数，并在超过阈值时进入“吃灰博物馆”（RC.08.04 / RC.18.02）。
5. THE Backlog_Screen SHALL 支持批量删除、批量改状态、批量同步与批量加标签（RC.08.05）。
6. WHEN 用户使用一键抽番，THE Backlog_Screen SHALL 给出抽中理由而非纯随机（RC.08.06）。

### Requirement 11: 无剧透评价雷达（RC.09）

**User Story:** 作为用户，我希望获得严格不剧透的多维度作品评价摘要，以便判断是否值得看而不被剧透。

#### Acceptance Criteria

1. THE Spoiler_Radar SHALL 仅总结风格、节奏、争议与雷点，绝不泄露关键剧情、角色结局、反转与真相（RC.09.01 / RC.14.04）。
2. THE Spoiler_Radar SHALL 输出完整维度：总体印象、优点、争议、雷点、适合人群、不适合人群、观看时机与数据置信度（RC.09.02）。
3. WHEN 用户未配置 AI key，THE Local_Fallback SHALL 使用关键词与标签统计生成基础雷达（RC.09.03）。
4. WHEN 用户已配置 AI key，THE AI_Engine SHALL 对评论/标签/简介生成更自然的无剧透摘要（RC.09.04）。
5. THE Spoiler_Radar SHALL 至少支持“无剧透”等级，并为“轻微剧透”“完整分析”预留剧透等级选项（RC.09.05）。
6. THE Spoiler_Radar SHALL 显示摘要来源：用户短评、公共 Reviews、标签统计或 AI 总结（RC.09.06）。

### Requirement 12: 个人口味画像与匹配度（RC.10）

**User Story:** 作为用户，我希望应用根据我的历史评分分析口味、生成称号并在详情页给出匹配度，以便发现更可能喜欢的作品。

#### Acceptance Criteria

1. THE Taste_Profile SHALL 支持从 Bangumi 等源导入用户自己的评分、状态、短评与标签（RC.10.01）。
2. THE Taste_Profile SHALL 统计高分作品标签、低分作品标签、常用短评词与常见搁置类型（RC.10.02）。
3. THE Detail_Screen SHALL 展示“你可能会不会喜欢”的口味匹配度并给出理由，且使用“可能/倾向于”而非绝对措辞（RC.10.03 / RC.10.2 Prompt 目标）。
4. THE Taste_Profile SHALL 生成评分习惯：评分严格度、平均分、高分稀有度与常见分数段（RC.10.04）。
5. THE Taste_Profile SHALL 生成口味称号（如世界观考据型观众、胃疼收藏家、规则系剧情捕手、短篇完结党）（RC.10.05 / RC.18.03）。
6. THE Taste_Profile SHALL 找出用户经常收藏但总搁置/抛弃的“口味黑洞”类型（RC.10.06）。
7. WHEN 样本数量较少，THE Taste_Profile SHALL 降低置信度提示（RC.10 / RC.10.2 Prompt 目标）。

### Requirement 13: 今晚看什么推荐器（RC.11）

**User Story:** 作为用户，我希望根据可用时间、心情与接受程度获得有理由的推荐，并有不准纠结、期末保护、深夜提醒等模式，以便立即决定看什么。

#### Acceptance Criteria

1. THE Recommender SHALL 提供时间选择：20 分钟、1 小时、2-3 小时、周末通宵（RC.11.01）。
2. THE Recommender SHALL 提供心情选择：轻松、胃疼、热血、恋爱、悬疑、神作补课、短篇、电波、想被震撼（RC.11.02）。
3. THE Recommender SHALL 提供接受程度选择：可慢热、可致郁、可党争、可长篇、不要剧透、不要太累、不要未完结（RC.11.03）。
4. THE Recommender SHALL 给出三个带理由的推荐：稳妥选择、赌一把选择、神经病选择（RC.11.04 / RC.10.3 Prompt 目标）。
5. WHEN 用户开启“不准纠结模式”，THE Recommender SHALL 只给一个推荐并提供“就看这个”的明确理由（RC.11.05）。
6. WHEN 用户开启“期末周保护模式”，THE Recommender SHALL 过滤长篇、致郁、高上头风险与未完结作品（RC.11.06）。
7. WHEN 当前为深夜时段且候选含胃疼/压抑/长篇作品，THE Recommender SHALL 给出温柔的深夜危险提示（RC.11.07）。
8. THE Recommender SHALL 不推荐用户已完成作品，且不推荐不符合硬性过滤条件的作品（RC.11 / RC.10.3 Prompt 目标）。

### Requirement 14: 补番路线图与系列管理（RC.12）

**User Story:** 作为用户，我希望查看作品的关联条目与推荐观看顺序，并能将整个系列加入待补池，以便理清观看路线。

#### Acceptance Criteria

1. THE Route_Map SHALL 在详情页展示续作、前传、外传、OVA、剧场版、总集篇等关联作品（RC.12.01）。
2. THE Route_Map SHALL 生成推荐观看顺序：必看、可选、可跳过与总集篇提示（RC.12.02）。
3. THE Route_Map SHALL 显示系列进度：用户已看/未看/搁置的系列节点（RC.12.03）。
4. THE Route_Map SHALL 支持将整个系列或必看节点一键加入待补池（RC.12.04）。
5. IF 资料不足以确定观看顺序，THEN THE Route_Map SHALL 标记“路线待确认”而非编造顺序（RC.12.05 / RC.10.4 Prompt 目标）。

### Requirement 15: 本地时光机与年度报告（RC.13）

**User Story:** 作为用户，我希望应用从首次同步起记录我的评分、短评与状态变化并生成报告，以便长期回顾自己的 ACG 历程。

#### Acceptance Criteria

1. WHEN 用户首次导入收藏，THE Time_Machine SHALL 导入当前收藏、评分、短评与进度形成本地初始 Snapshot（RC.13.01）。
2. WHEN 后续同步发生，THE Time_Machine SHALL 记录新增收藏、状态变化、评分变化、短评变化与进度变化（RC.13.02）。
3. THE TimeMachine_Screen SHALL 按月/年展示用户自己的 ACG 活动时间线（RC.13.03）。
4. THE TimeMachine_Screen SHALL 随机展示过去的短评与当时评分（“以前的我怎么想”）（RC.13.04）。
5. THE TimeMachine_Screen SHALL 呈现“评分打脸现场”：发现以前低分后来高分或反向变化（RC.13.05）。
6. THE TimeMachine_Screen SHALL 生成月度/年度报告：看了多少、平均评分、最高分、常见标签、口味变化与吃灰作品（RC.13.06）。
7. THE Time_Machine SHALL 不承诺完整复刻 Bangumi 历史，仅从首次同步开始记录变化（RC.13 / RC.11 降级策略）。

### Requirement 16: AI 提示词与结构化输出（RC.14）

**User Story:** 作为用户，我希望 AI 功能使用固定结构输出、强制剧透保护、且在低质量模型下仍可用，以便获得可靠且安全的分析结果。

#### Acceptance Criteria

1. THE AI_Engine SHALL 由用户选择并配置 AI_Provider，且不内置任何 key（RC.14.01 / RC.00）。
2. THE AI_Engine SHALL 对防剧透雷达、口味画像、今晚推荐与路线图使用固定的结构化输出格式（RC.14.02）。
3. WHEN AI 输出缺少字段，THE AI_Engine SHALL 发起“修复成指定格式”的二次请求；WHEN 无法判断，THE AI_Engine SHALL 输出低置信度而非编造（RC.14.03）。
4. THE AI_Engine 的系统提示词 SHALL 强制禁止泄露关键剧情、死亡、身份、结局、反转、CP 结果等剧透（RC.14.04）。
5. WHEN 用户发起 AI 分析，THE AI_Engine SHALL 显示估计成本范围/确认，并允许只分析摘要而非完整评论（RC.14.05）。
6. THE AI 卡片 SHALL 显示“AI 生成/规则生成”、生成时间、数据来源、置信度与重新生成按钮（RC.14 / 页面展示细节）。

### Requirement 17: 我的页、统计与账号状态（RC.15）

**User Story:** 作为用户，我希望在我的页查看各平台配置状态、个人统计、口味画像入口与隐私/导出选项，以便集中管理账号与数据。

#### Acceptance Criteria

1. THE Mine_Screen SHALL 显示 Bangumi/AniList/MAL/VNDB/AI 的配置状态与最后测试时间（RC.15.01）。
2. THE Mine_Screen SHALL 展示数据统计：看过、在看、想看、搁置、抛弃、平均评分、最高分与常见标签（RC.15.02）。
3. THE Mine_Screen SHALL 提供进入完整口味画像的入口（RC.15.03）。
4. THE Mine_Screen SHALL 提供清除本地数据、导出备份、导入备份与清理缓存（RC.15.04）。
5. THE Mine_Screen SHALL 提供关于页，说明数据源、开源许可、免责声明与不提供盗版资源（RC.15.05）。

### Requirement 18: 数据导入导出、备份与迁移（RC.16）

**User Story:** 作为用户，我希望能本地备份与恢复数据、在升级时不丢数据、并导出 CSV，以便长期安全地保存我的 ACG 档案。

#### Acceptance Criteria

1. WHEN 用户导出备份，THE System SHALL 导出收藏、待补池、评分、短评、标签、快照与设置，且默认不导出 key（RC.16.01）。
2. WHEN 用户导入旧备份，THE System SHALL 合并而非覆盖新数据，并提示冲突（RC.16.02）。
3. WHEN 应用升级，THE System SHALL 保留旧数据；IF 迁移失败，THEN THE System SHALL 保留原始备份并提示恢复（RC.16.03 / RC.00）。
4. THE System SHALL 允许将待补池/时光机/评分表导出为 CSV（RC.16.04）。
5. THE System SHALL 为 Bangumi 旧/新账号或多平台列表的跨账号合并预留功能（RC.16.05）。

### Requirement 19: 测试与自检（RC.17）

**User Story:** 作为开发者，我希望每个模块完成后都按测试矩阵自检，以便保证安装、升级、凭据安全、接口异常与页面状态的健壮性。

#### Acceptance Criteria

1. WHEN 在无账号、无 key、无网络的干净环境安装 APK，THE System SHALL 不崩溃（RC.17 安装测试）。
2. WHEN 从旧版本升级，THE System SHALL 保留待补池、评分、短评、快照与设置不丢失（RC.17 升级测试 / RC.16.03）。
3. WHEN key 填错、过期、删除、导出、记录日志或截图，THE System SHALL 不泄露完整 key（RC.17 凭据测试 / RC.00）。
4. THE System SHALL 处理 API 的成功、404、401、403、429、500、字段缺失、空数组、限流与网络超时场景（RC.17 API 测试）。
5. THE System SHALL 处理 AI 的无 key、本地规则版、输出不完整、输出含剧透、JSON/结构损坏与超时场景（RC.17 AI 测试）。
6. THE System SHALL 处理导入的中文/日文/英文/罗马音、多行、逗号、书名号、重复标题、同名作品与低置信匹配场景（RC.17 导入测试）。
7. THE System SHALL 处理时光机的首次快照、二次同步、评分/短评/状态变化与删除/合并冲突场景（RC.17 时光机测试）。
8. THE System SHALL 在深色模式、大字体、长标题、封面缺失、横竖屏、低端机与返回栈场景下正确展示（RC.17 页面测试 / RC.03.05）。

### Requirement 20: 趣味功能（RC.18）

**User Story:** 作为用户，我希望应用带有安利债务、吃灰博物馆、补番人格等趣味元素，以便整理过程更有乐趣并获得情绪风险提示。

#### Acceptance Criteria

1. THE System SHALL 显示“安利债务”：你欠群友 N 部番没看（RC.18.01）。
2. THE System SHALL 展示“吃灰博物馆”：待补池里躺太久的作品（RC.18.02 / RC.08.04）。
3. THE System SHALL 根据口味生成“补番人格”称号（RC.18.03 / RC.10.05）。
4. THE System SHALL 提供“补番遗书模式”，生成一句中二补番宣言（RC.18.04）。
5. THE System SHALL 对致郁、压抑、党争、高上头等风险给出情绪风险提示（RC.18.05 / RC.11.07）。

---

## QA 修订需求（基于实测反馈，RC.19–RC.32）

> 以下需求来自实现基本完成后的实测反馈（缺陷与行为变更）。它们对已有功能区（导航、设置/凭据、数据源、搜索/匹配、待补池、时光机、我的页、批量导入、UI/UX、稳定性）进行修正与细化，沿用 RC.xx.yy 编号体系，并在每条验收条件中回链其细化的原始 RC 编号，以保持可追溯。原有 Requirement 1–20 保持不变。

### Glossary 补充

- **Taste_Profile_Screen**：完整口味画像页，从 Mine_Screen 的「更多 → 口味画像」进入。
- **Privacy_Data_Section**：设置页中的「隐私与数据管理」区，承载导出备份、导入备份、清除本地数据与清理缓存操作。

### Requirement 21: 导航栈正确性与深链接（RC.19，细化 RC.03.02 / RC.15）

**User Story:** 作为用户，我希望底部导航与「更多」入口总是把我带到正确的页面，以便在任何返回栈状态下都能可靠地回到目标页面。

#### Acceptance Criteria

1. WHEN 用户点击底部导航的「首页」按钮，THE Navigation_Controller SHALL 始终返回 Home_Screen 根目的地，而不展示 Discover_Screen 或任何先前选中的子页面（RC.19 / 细化 RC.03.02）。
2. WHEN 用户点击任一底部导航按钮，THE Navigation_Controller SHALL 恢复到该标签的根目的地并保持各标签返回栈相互独立（RC.19 / 细化 RC.03.02）。
3. WHEN 用户在 Mine_Screen 的「更多」中点击「设置」，THE Navigation_Controller SHALL 打开 Settings_Screen（RC.19 / 细化 RC.15.06）。
4. WHEN 用户在 Mine_Screen 的「更多」中点击「隐私与数据管理」，THE Navigation_Controller SHALL 深链接进入 Settings_Screen 并自动展开且滚动至 Privacy_Data_Section（RC.19 / 细化 RC.15.04）。
5. WHEN 用户在 Mine_Screen 的「更多」中点击「口味画像」，THE Navigation_Controller SHALL 打开 Taste_Profile_Screen（RC.19 / 细化 RC.15.03）。

### Requirement 22: 设置页配置补全、文档链接与字段标签（RC.20，细化 RC.02）

**User Story:** 作为用户，我希望设置页提供完整的 Bangumi 配置、可用的官方文档链接与准确的字段标签，以便正确配置各数据源。

#### Acceptance Criteria

1. THE Settings_Screen SHALL 提供 Bangumi API 配置卡片，包含 Token/OAuth 输入、当前用户名显示、同步开关、清除授权按钮、连接测试、状态显示与官方文档链接（RC.20 / 细化 RC.02.05 / RC.02.06）。
2. WHEN 用户点击任一数据源或 AI_Provider 设置卡片中的「查看官方文档」链接，THE Settings_Screen SHALL 打开该源对应的官方文档 URL（RC.20 / 细化 RC.02.01 / RC.02.04）。
3. THE Settings_Screen 的凭据输入字段 SHALL 对可选凭据仅显示一次「(可选)」标识，且不在同一字段重复显示该标识（RC.20 / 细化 RC.02.02）。

### Requirement 23: 免凭据数据源参与搜索与连接测试（RC.21，细化 RC.01 / RC.02）

**User Story:** 作为用户，我希望无需凭据即可使用的数据源在启用后也能参与搜索，以便在未配置凭据时仍能获得更完整的结果。

#### Acceptance Criteria

1. WHERE 某 Data_Source 无需凭据即可发起公共查询（包括 AniList 公共查询与无 key 的 Jikan），WHILE 该源的启用开关为开，THE System SHALL 使该源参与搜索并贡献结果（RC.21 / 细化 RC.01.03 / RC.01.04）。
2. WHERE VNDB 的 Token 为可选且未配置，WHILE VNDB 的启用开关为开，THE System SHALL 使 VNDB 参与搜索并贡献其公共可得结果（RC.21 / 细化 RC.01.06）。
3. WHEN 用户对一个免凭据或凭据可选的数据源点击连接测试，THE Settings_Screen SHALL 显示反映「无需凭据即可使用」的测试内容与结果，而非提示缺少凭据（RC.21 / 细化 RC.02.04）。

### Requirement 24: 搜索结果与详情数据完整性（RC.22，细化 RC.01.07 / RC.05.02 / RC.07）

**User Story:** 作为用户，我希望搜索结果卡片展示更完整的字段、详情页聚合全部可得数据，以便在结果与详情中获得足够的决策信息。

#### Acceptance Criteria

1. WHEN 搜索结果卡片展示某条作品，THE Search_Module SHALL 展示该源已提供的可用字段（包含评分、来源标签与 Match_Confidence），并对缺失字段显示「暂无数据」（RC.22 / 细化 RC.01.07 / RC.05.02）。
2. WHEN 来自 Jikan 的结果包含评分、排名、人气或社区共识字段，THE Search_Module SHALL 展示这些字段，而非仅展示标题、类型、年份与状态（RC.22 / 细化 RC.01.04）。
3. WHEN 用户从搜索结果进入 Detail_Screen，THE Detail_Screen SHALL 聚合并展示各源的完整可得数据（评分、社区共识、标签、关联与详情字段）（RC.22 / 细化 RC.07.02 / RC.07.03）。

### Requirement 25: 隐私与数据管理操作生效（RC.23，细化 RC.15.04 / RC.16）

**User Story:** 作为用户，我希望设置页的导出、导入、清除数据与清理缓存按钮真实生效，以便切实管理我的本地数据。

#### Acceptance Criteria

1. WHEN 用户在 Privacy_Data_Section 点击「导出备份」，THE System SHALL 生成并导出备份文件，且默认不包含任何凭据（RC.23 / 细化 RC.15.04 / RC.16.01）。
2. WHEN 用户点击「导入备份」，THE System SHALL 读取所选备份文件并按业务主键合并而非覆盖（RC.23 / 细化 RC.16.02）。
3. WHEN 用户点击「清除本地数据」，THE System SHALL 在二次确认后删除本地业务数据（RC.23 / 细化 RC.15.04）。
4. WHEN 用户点击「清理缓存」，THE System SHALL 清除缓存数据并保留用户业务数据（RC.23 / 细化 RC.15.04）。

### Requirement 26: 搜索相关性、中文匹配与低置信分组（RC.24，细化 RC.05.01 / RC.05.02 / RC.05.03）

**User Story:** 作为用户，我希望搜索能匹配中文名、按相关性与热度排序，并把大量低置信结果折叠，以便更快找到正确作品。

#### Acceptance Criteria

1. THE Search_Module SHALL 支持按中文名匹配作品，包含简体写法与日文汉字写法（如「2.5次元的诱惑」与「2.5次元の誘惑」）（RC.24 / 细化 RC.05.01）。
2. WHEN 用户提交查询，THE Search_Module SHALL 按相关性对结果排序，使标题更贴近查询的作品排在更前（RC.24 / 细化 RC.05.02）。
3. WHEN 多个结果与查询同等精确匹配，THE Search_Module SHALL 按人气/热度对这些结果降序排序（RC.24 / 细化 RC.05.02）。
4. WHEN 搜索结果包含多条低置信匹配，THE Search_Module SHALL 将这些低置信匹配合并为靠近结果顶部的单一条目，而非为每条低置信匹配渲染独立完整卡片（RC.24 / 细化 RC.05.03）。
5. WHEN 用户展开低置信匹配合并条目，THE Search_Module SHALL 展示其包含的各候选项以供手动选择正确条目（RC.24 / 细化 RC.05.03）。

### Requirement 27: 搜索结果与历史持久化（RC.25，细化 RC.05）

**User Story:** 作为用户，我希望在查询不变时保留已有搜索结果，以便从详情页返回时无需重新搜索而等待。

#### Acceptance Criteria

1. WHILE 查询内容未发生变化，THE Search_Module SHALL 保留上一次的搜索结果与搜索历史（RC.25 / 细化 RC.05）。
2. WHEN 用户从 Detail_Screen 返回搜索界面且查询内容未变，THE Search_Module SHALL 展示已保留的结果而不重新发起搜索（RC.25 / 细化 RC.05）。
3. WHEN 用户修改查询内容，THE Search_Module SHALL 发起新的搜索（RC.25 / 细化 RC.05）。

### Requirement 28: 待补池筛选面板可折叠（RC.26，细化 RC.08.02）

**User Story:** 作为用户，我希望待补池的筛选面板可折叠，以便为作品列表留出更多空间。

#### Acceptance Criteria

1. THE Backlog_Screen SHALL 将筛选面板呈现为可折叠组件，并在默认折叠状态下占用最小空间（RC.26 / 细化 RC.08.02）。
2. WHEN 用户切换筛选面板的折叠状态，THE Backlog_Screen SHALL 相应展开或收起筛选面板并为作品列表释放空间（RC.26 / 细化 RC.08.02）。

### Requirement 29: 时光机离线可用与双向同步（RC.27，细化 RC.13.02）

**User Story:** 作为用户，我希望时光机在未登录 Bangumi 时也能离线使用，并在登录后与 Bangumi 双向同步，以便随时回顾且保持数据一致。

#### Acceptance Criteria

1. WHILE 用户未登录 Bangumi 或处于离线状态，THE Time_Machine SHALL 允许用户使用本地时光机功能，包含查看时间线、快照与报告（RC.27 / 细化 RC.13）。
2. WHEN 用户登录 Bangumi 后触发同步，THE Time_Machine SHALL 将本地时光机条目上传至 Bangumi（RC.27 / 细化 RC.13.02）。
3. WHEN 用户登录 Bangumi 后触发同步，THE Time_Machine SHALL 将 Bangumi 条目下载至本地（RC.27 / 细化 RC.13.02）。
4. WHEN 用户点击 TimeMachine_Screen 的「同步后…」按钮，THE TimeMachine_Screen SHALL 响应点击并执行其对应的同步动作（RC.27 / 细化 RC.13.02）。

### Requirement 30: 我的页滚动与标题排版（RC.28，细化 RC.15 / RC.03.05）

**User Story:** 作为用户，我希望我的页完整可滚动且标题排版清晰，以便看到全部内容并获得良好的视觉层级。

#### Acceptance Criteria

1. THE Mine_Screen SHALL 使其全部内容可垂直滚动，使包含顶部在内的内容均可被滚动到并完整显示（RC.28 / 细化 RC.15）。
2. THE System SHALL 将各屏幕标题（如「我的」）定位在更靠近左上方的位置（RC.28 / 细化 RC.03.05）。
3. THE System SHALL 以相对更大的字号渲染各屏幕标题以强化视觉层级（RC.28 / 细化 RC.03.05）。

### Requirement 31: 批量导入流程可用（RC.29，细化 RC.06）

**User Story:** 作为用户，我希望首页的补番收纳箱提供真正可用的导入流程，以便把安利清单导入待补池。

#### Acceptance Criteria

1. WHEN 用户在 Home_Screen 打开补番收纳箱（批量导入），THE Import_Module SHALL 展示可用的导入流程，而非仅展示空的收纳箱（RC.29 / 细化 RC.06）。
2. WHEN 用户提交导入文本或文件，THE Import_Module SHALL 解析并匹配作品名，并将用户确认后的条目导入待补池（RC.29 / 细化 RC.06.01 / RC.06.07）。

### Requirement 32: 过渡动画、按钮与搜索框布局（RC.30，细化 RC.03.05 / RC.05）

**User Story:** 作为用户，我希望页面切换快速、按钮文字不换行、搜索框保持单行，以便获得流畅一致的界面体验。

#### Acceptance Criteria

1. WHEN 用户在页面之间导航，THE System SHALL 使用快速过渡或无过渡动画，避免缓慢的淡入淡出（RC.30 / 细化 RC.03.05）。
2. THE System SHALL 将按钮文字渲染为单行，且不因文字达到 4 个或以上字符而换行，必要时使用省略号或自适应尺寸（RC.30 / 细化 RC.03.05）。
3. THE Discover_Screen 的搜索框 SHALL 将其内容保持在单行并正确适配尺寸，不换行至第二行（RC.30 / 细化 RC.05）。

### Requirement 33: 筛选与空状态稳定性（RC.31，细化 RC.03.04 / RC.05 / RC.17.4）

**User Story:** 作为用户，我希望打开发现页筛选时应用稳定不崩溃，以便安全地筛选与浏览结果。

#### Acceptance Criteria

1. WHEN 用户打开 Discover_Screen 的筛选界面，THE System SHALL 正确渲染筛选界面且不崩溃（RC.31 / 细化 RC.03.04 / RC.17.4）。
2. THE Discover_Screen SHALL 不将上一次搜索的残留结果带入筛选界面（RC.31 / 细化 RC.05）。
3. WHILE 筛选结果为空或处于残留/无效状态，THE System SHALL 显示空状态且不崩溃（RC.31 / 细化 RC.03.03 / RC.03.04）。

### Requirement 34: 首页与待补池内容边界（RC.32，细化 RC.04.04 / RC.08.01）

**User Story:** 作为用户，我希望首页与待补池各司其职，以便不在两个页面看到重复的完整待补列表。

#### Acceptance Criteria

1. THE Home_Screen SHALL 仅以决策导向的概览子集呈现待补内容（如今晚推荐、概览统计与精选条目）（RC.32 / 细化 RC.04.04）。
2. THE Backlog_Screen SHALL 呈现完整、可管理、可筛选排序的待补列表（RC.32 / 细化 RC.08.01）。
3. THE System SHALL 将完整待补列表的浏览与管理限定在 Backlog_Screen，使 Home_Screen 与 Backlog_Screen 的待补内容形态互不重复（RC.32 / 细化 RC.04.04 / RC.08.01）。

## 可追溯说明（任务阶段处理）

- 上述 RC.19–RC.32 的实现与修复 SHALL 按 RC.00 文档制度同步更新 REQUIREMENTS.md、DEVELOPMENT.md 与 EXPERIENCE.md（细化 Requirement 2 / RC.00 文档制度）。
- tasks.md 中尚未完成的可选/测试类任务（标 `*` 的子任务）与第 36 节横切测试与冒烟验证，仍由 Requirement 19（RC.17）与第 36 节任务管辖，将在任务阶段补全；本需求文档不重复定义其验收条件。


---

## QA 修订需求（H/I 轮实测反馈，RC.33–RC.48）

> 以下需求来自 H/I 轮真机实测反馈，沿用 RC.xx 编号体系并回链所细化的原始 RC/Requirement。**仅新增，不重复**已有 Requirement 1–34；与既有条目重叠处通过「细化」标注其上位编号。原有需求保持不变。

### Requirement 35: 同一作品的跨源一致聚合与主源优先（RC.33，细化 RC.05.02 / RC.22 / RC.07）

**User Story:** 作为用户，我希望同一部作品在搜索结果与详情中表现一致、由统一主源呈现核心信息，以便不会看到同一部番在不同查询下来源、原名、简介、评分各不相同的混乱结果。

#### Acceptance Criteria

1. WHEN 用户以不同关键词（中文译名 / 英文名 / 罗马音）搜索同一部作品，THE Search_Module SHALL 将各源命中聚类为同一作品条目，使其代表来源与展示标题保持稳定一致（RC.33 / 细化 RC.05.02 / RC.22.03）。
2. WHERE 某作品存在 Bangumi 来源命中，THE System SHALL 以 Bangumi 作为该作品的代表主源，并在详情页优先展示 Bangumi 的中文标题与中文简介（RC.33 / 细化 RC.07.02）。
3. WHEN 搜索结果列表展示某作品卡片，THE Search_Module SHALL 展示该作品已聚合到的全部来源标识，而非仅展示单一命中源（RC.33 / 细化 RC.01.07）。
4. THE System SHALL 不因列表卡片仅显示单一源而在详情页出现「列表无评分但详情多源有评分」的不一致；详情页与卡片 SHALL 基于同一聚类作品读取数据（RC.33 / 细化 RC.07.03）。

### Requirement 36: 滚动安全区一致化与底部留白克制（RC.34，细化 RC.03.05 / RC.28）

**User Story:** 作为用户，我希望各页面顶部既不被状态栏/标题栏遮挡、也不过度下沉，且底部不留过大空白，以便所有页面排版统一美观。

#### Acceptance Criteria

1. THE System SHALL 通过统一的安全区（system bar inset）规则计算各可滚动页面的顶部内边距，使内容不被顶部栏遮挡（RC.34 / 细化 RC.03.05）。
2. THE System SHALL 不对本就无顶部遮挡的页面（如时光机、发现搜索结果列表）施加额外的固定顶部下移量，使其顶部与遮挡页面观感一致而非明显下沉（RC.34 / 细化 RC.03.05）。
3. THE Home_Screen SHALL 使其顶部内容完整可见、不被顶部栏遮挡（RC.34 / 细化 RC.04）。
4. THE System SHALL 限制各可滚动页面底部留白为合理的内容内边距，避免滚动到底部时出现过大空白（RC.34 / 细化 RC.03.05）。

### Requirement 37: 发现页本季榜单 / 评分差异 / 筛选数据装填（RC.35，细化 RC.32 / RC.05）

**User Story:** 作为用户，我希望发现页的本季榜单、评分差异、筛选都能加载出真实内容，以便浏览与发现作品。

#### Acceptance Criteria

1. THE Discover_Screen 的本季榜单 SHALL 默认以 Bangumi 数据装填并展示作品（RC.35 / 细化 RC.32）。
2. THE Discover_Screen 的评分差异榜 SHALL 展示跨平台评分差异较大的作品，并按差异从大到小排序（RC.35 / 细化 RC.22）。
3. THE Discover_Screen 的筛选 SHALL 能基于已装填的作品数据筛选出动画结果，且至少对一个数据源可用（RC.35 / 细化 RC.05）。

### Requirement 38: 吃灰馆独立页面与移入移出（RC.36，细化 RC.08.04 / RC.18.02）

**User Story:** 作为用户，我希望吃灰馆像待补池一样有独立页面与入口，并能把作品在待补池与吃灰馆之间移入移出，以便单独管理长期搁置的作品。

#### Acceptance Criteria

1. THE System SHALL 提供吃灰馆（Dust_Museum）独立页面，展示已移入吃灰馆的作品列表（RC.36 / 细化 RC.18.02）。
2. THE System SHALL 提供进入吃灰馆页面的明确入口（RC.36 / 细化 RC.08.04）。
3. WHEN 用户在待补池将作品移入吃灰馆，THE System SHALL 使该作品出现在吃灰馆页面（RC.36 / 细化 RC.08.05）。
4. WHEN 用户在吃灰馆页面将作品移出，THE System SHALL 使该作品回到待补池非吃灰区（RC.36 / 细化 RC.08.05）。

### Requirement 39: 本地编辑回写 Bangumi 真正生效（RC.37，细化 RC.13.02 / Requirement 9）

**User Story:** 作为用户，我希望在本地修改的收藏状态/评分/进度/短评能真正同步回 Bangumi，以便本地与云端一致。

#### Acceptance Criteria

1. WHEN 用户加入待补池且本地置「想看」，WHILE 已配置 Bangumi 凭据，THE System SHALL 调用 Bangumi 收藏修改接口将状态回写为「想看」并在成功后标记已同步（RC.37 / 细化 RC.13.02）。
2. WHEN 用户在「我的记录」保存状态/评分/进度/短评，WHILE 已配置 Bangumi 凭据，THE System SHALL 将改动回写 Bangumi 并在成功后提示已同步、失败时给出可读原因（RC.37 / 细化 RC.13.02）。
3. THE System SHALL 使用正确的 Bangumi 收藏修改请求形态（鉴权头、subject_id、请求体字段、可接受的成功返回码 202/204）以避免被错误判定为同步失败（RC.37 / 细化 RC.01.02）。

### Requirement 40: 我的记录状态机与字段清空（RC.38，细化 Requirement 9 / RC.07.04）

**User Story:** 作为用户，我希望我的记录能按进度自动推导状态、显示总集数上限，并能清空状态/评分/进度，以便准确维护观看记录。

#### Acceptance Criteria

1. WHEN 用户保存我的记录且未选择任何状态，THE System SHALL 将状态置为「无状态」（清空）而非保留旧状态（RC.38 / 细化 RC.07.04）。
2. WHEN 用户进度从 0 变为大于 0 且未显式选状态，THE System SHALL 将状态自动推导为「在看」（RC.38 / 细化 RC.07.04）。
3. WHEN 用户进度达到总集数且未显式选状态，THE System SHALL 将状态自动推导为「看过」（RC.38 / 细化 RC.07.04）。
4. THE Detail_Screen SHALL 在进度编辑处展示作品总集数，并禁止进度超过总集数上限（RC.38 / 细化 RC.07.04）。
5. THE System SHALL 允许将评分与进度清空为「无」，并将清空结果回写 Bangumi（RC.38 / 细化 RC.07.04 / RC.13.02）。

### Requirement 41: 社区标签用于画像、推荐与匹配度（RC.39，细化 RC.10.02 / RC.10.06）

**User Story:** 作为用户，我希望应用利用作品的社区标签（他人标注、非我标注）来提升口味画像、推荐与匹配度的准确度，以便在我很少打标签时仍获得精准分析。

#### Acceptance Criteria

1. THE Taste_Profile SHALL 在统计高/低分倾向标签时纳入作品的社区标签（他人标注），而非仅用户自定义标签（RC.39 / 细化 RC.10.02）。
2. THE Recommender SHALL 使用作品社区标签参与候选相似度计算（RC.39 / 细化 RC.11）。
3. THE Detail_Screen 的本地口味匹配度 SHALL 使用作品社区标签与用户口味画像计算，以提升匹配准确度（RC.39 / 细化 RC.10.03）。

### Requirement 42: 今晚看什么候选池选择与相似度计算（RC.40，细化 RC.11 / RC.04）

**User Story:** 作为用户，我希望「今晚看什么」能选择候选范围（待补池或全部作品）并基于我看过/评分过的作品计算最契合的推荐，以便获得更多更准的推荐。

#### Acceptance Criteria

1. THE Home_Screen 的「今晚看什么」SHALL 允许用户选择候选池：仅待补池作品，或全部已知作品（RC.40 / 细化 RC.04.04）。
2. THE Recommender SHALL 基于用户看过且评分过的作品（标签/类型共现 + 高分加权）计算候选与用户口味的契合度并据此排序（RC.40 / 细化 RC.11.04）。
3. THE Recommender SHALL 在候选充足时提供多于既有数量的可选推荐项（RC.40 / 细化 RC.11）。
4. THE Recommender SHALL 排除用户已完成作品并附可解释理由（RC.40 / 细化 RC.11.08）。

### Requirement 43: AI 匹配度提示词工程（RC.41，细化 RC.10.03 / RC.14.02）

**User Story:** 作为用户，我希望 AI 匹配度分析提交足够有代表性的口味证据，以便分析结果更准确。

#### Acceptance Criteria

1. WHEN 用户发起 AI 匹配度分析，THE AI_Engine SHALL 在输入中纳入用户最近约 20 部已评分作品（标题 + 评分 + 短评）（RC.41 / 细化 RC.10.03）。
2. THE AI_Engine SHALL 在输入中纳入用户历史最高评分作品（可多部）与历史最低评分作品（可多部）作为口味锚点（RC.41 / 细化 RC.10.03）。
3. THE AI_Engine SHALL 使用结构化提示词组织上述证据，并要求模型据此输出匹配度与简短理由（RC.41 / 细化 RC.14.02）。

### Requirement 44: 时光机条目可跳转详情（RC.42，细化 RC.13.03）

**User Story:** 作为用户，我希望时光机时间线里的作品能直接点击跳转到详情页，以便回顾时快速查看作品。

#### Acceptance Criteria

1. WHEN 用户点击 TimeMachine_Screen 时间线中的某作品条目，THE System SHALL 跳转到该作品的 Detail_Screen（RC.42 / 细化 RC.13.03）。

### Requirement 45: 跳转加载态不闪现空状态（RC.43，细化 RC.03.07 / RC.07）

**User Story:** 作为用户，我希望从观看路线等入口跳转到另一作品详情时，加载过程显示加载态而非短暂闪现「暂无内容」，以便观感流畅。

#### Acceptance Criteria

1. WHILE 目标作品详情正在加载，THE Detail_Screen SHALL 显示加载态而非空状态/「暂无内容」（RC.43 / 细化 RC.03.07）。
2. THE Detail_Screen SHALL 仅在确认无数据后才展示空状态/「暂无数据」，不在加载未完成时展示（RC.43 / 细化 RC.03.04 / RC.17.4）。

### Requirement 46: 评论摘要展示评论内容摘要（RC.44，细化 RC.09.06）

**User Story:** 作为用户，我希望「评论摘要」展示对大众评论/吐槽的内容摘要，而不是评分数字，以便快速了解口碑与槽点。

#### Acceptance Criteria

1. THE Detail_Screen 的评论摘要 SHALL 展示对作品评论/吐槽的内容性摘要（观点、口碑、槽点），而非以评分数值充当评论摘要（RC.44 / 细化 RC.09.06）。
2. WHERE 可获取大众评论文本（如 Bangumi 短评 / AniList reviews），THE System SHALL 基于评论文本生成摘要（规则或 AI）（RC.44 / 细化 RC.09.04 / RC.09.06）。
3. IF 无任何评论文本来源可用，THEN THE Detail_Screen SHALL 显示「暂无数据」而非以评分替代（RC.44 / 细化 RC.09.03）。

### Requirement 47: 决策助手各区块实装（RC.45，细化 RC.09 / RC.11）

**User Story:** 作为用户，我希望详情页决策助手的「需注意/不推荐」「无剧透评价雷达」「适合心情」都有内容，以便辅助决策。

#### Acceptance Criteria

1. THE Detail_Screen 决策助手的「需注意/不推荐理由」SHALL 基于作品标签/评分/争议信号以算法生成内容，且在确无信号时才显示「暂无数据」（RC.45 / 细化 RC.11.07）。
2. THE Detail_Screen 决策助手的「无剧透评价雷达」SHALL 以本地规则（未配置 AI 时）或 AI（已配置时）生成并展示（RC.45 / 细化 RC.09.03 / RC.09.04）。
3. THE Detail_Screen 决策助手的「适合心情」SHALL 基于作品的心情/题材标签生成展示（RC.45 / 细化 RC.11.02）。

### Requirement 48: 封面旁发行/完结状态展示（RC.46，细化 RC.07.01）

**User Story:** 作为用户，我希望详情页封面旁展示作品的发行/完结状态，以便一眼看出是否完结。

#### Acceptance Criteria

1. THE Detail_Screen SHALL 在封面旁展示作品的发行状态（连载/放送中、已完结、未发布等）（RC.46 / 细化 RC.07.01）。
2. IF 主源未提供发行状态，THEN THE System SHALL 由已聚合的其他源补齐发行状态后再展示，仍无则显示「暂无数据」（RC.46 / 细化 RC.07.03）。

### Requirement 49: 开播日期精确到天（RC.47，细化 RC.07.01）

**User Story:** 作为用户，我希望详情页除年份外还展示精确到天的开播日期，以便了解准确播出时间。

#### Acceptance Criteria

1. WHERE 数据源提供作品开播日期，THE Detail_Screen SHALL 展示精确到天（YYYY-MM-DD）的开播日期（RC.47 / 细化 RC.07.01）。
2. IF 仅有年份而无完整日期，THEN THE Detail_Screen SHALL 退回展示年份（RC.47 / 细化 RC.07.01）。

### Requirement 50: 待补池展示形态持久化记忆（RC.48，细化 RC.08 / RC.03.05）

**User Story:** 作为用户，我希望待补池的网格/列表展示形态被记住，以便下次进入时保持上次的选择。

#### Acceptance Criteria

1. WHEN 用户切换待补池的网格/列表形态，THE System SHALL 持久化该偏好（RC.48 / 细化 RC.08.01）。
2. WHEN 用户再次进入待补池，THE System SHALL 应用上次持久化的展示形态（RC.48 / 细化 RC.08.01）。
