# ACG Compass 经验文档（EXPERIENCE.md）

> 本文档记录开发与维护过程中遇到的问题、根因、修复方式与避免策略，并关联到对应的 RC 编号，方便后续复用与避坑。
> 维护规则（RC.00 文档制度）：修复任一问题后必须在此追加一条记录；记录中**绝不**出现任何 key / token / client secret / AI API key，引用凭据时仅按字段名（如 `OpenAI API key`）描述。

## 使用说明

- 每遇到一个值得记录的问题（崩溃、接口变更、数据迁移、限流、AI 输出异常、页面渲染等），新增一行。
- `相关 RC` 列填写对应 RC.xx.yy 编号，便于与 `REQUIREMENTS.md` / `DEVELOPMENT.md` 交叉追溯。
- 严重问题或具有共性的坑，建议在表格下方“详细记录”章节补充背景、复现步骤与结论。

## 经验记录表

| 序号 | 问题现象 | 原因 | 修复方式 | 避免策略 | 相关 RC |
| --- | --- | --- | --- | --- | --- |
| 1 | GraphQL 响应 `{"errors":null}` 被判为请求失败（`isSuccessful=false`） | `root.get("errors")` 返回的是 `JsonNull`（JSON 显式 null），不等于 Kotlin `null`，导致 `errors == null` 判定为 false | `GraphQlClient` 读取 `data`/`errors` 时用 `takeUnless { it is JsonNull }` 把 `JsonNull` 规范化为 Kotlin `null` | 解析第三方 JSON 时，显式 `null` 与缺字段都应归一化处理，断言前先规范化 | RC.01 3.3 |
| 2 | 仓库单元测试报 `Detected use of different schedulers` | 测试用 `TestDispatchers` 内部 `UnconfinedTestDispatcher()` 自建 `TestCoroutineScheduler`，与 `runTest{}` 的调度器不同，`flowOn`+`combine` 收集时冲突 | `TestDispatchers` 改用真实 `Dispatchers.Unconfined`（立即执行、无独立 scheduler） | 测试里若需跨多个测试分发器，应共享同一 `TestCoroutineScheduler`，或对纯内存 Fake 用 `Dispatchers.Unconfined` | RC.08 / RC.03.04 |
| 3 | 待补池 `addAll` 同批次重复作品未计入去重结果（Property 10） | 同一次调用内重复 workId 命中时只自增被安利次数、未加入 `duplicateWorkIds` | 在「已处理」分支补记 `duplicateWorkIds`；按「项」计数（同 workId 可同时出现在 added 与 duplicate） | 去重语义应以「输入项」为单位明确建模，并在文档/属性测试中固定 | RC.06.06/07 |
| 4 | 导入一键加入待补池后被安利次数偏低（Property 11 示例侧） | `addBatchToBacklog` 对匹配作品 `.distinct()` 后才交给 `addAll`，使同一作品多次命中只计 1 次 | 去掉 `.distinct()`，按每条匹配命中传入，被安利计数由 `addAll` 逐次自增；待补池去重仍由 `addAll` 按 workId 负责 | 「被安利次数」语义=命中条数，去重与计数职责要分离 | RC.06.06 |
| 5 | 凭据隔离属性测试 1.A 偶发泄露（脱敏后仍含完整值） | 测试生成器包含任意低熵串与以 `-` 开头的伪 JWT，裸日志行中无字段上下文且 `jwtRegex` 的 `\b` 无法锚定前导 `-`，无法识别为密钥 | 将密钥生成器收敛为贴近真实凭据形态（`sk-`/三段式以字母数字开头/字母数字混合长串），使「凭据绝不泄露」可被有效检验 | 属性测试应对「凭据」量化于真实形态；裸文本无上下文时只能按 shape 识别 | RC.00 1.7 / 19.3 |
| 6 | `DetailViewModel` 注入 `GenerateSpoilerRadarUseCase` 后 Hilt 报 `AiProviderSelector cannot be provided` | `AiProviderSelector` 仅有实现类无 Hilt 绑定；此前无消费者触发校验，接入 ViewModel 后暴露缺失绑定 | 在 `AiModule` 以 `@Provides` 绑定 `DefaultAiProviderSelector` | 新增可注入依赖时，确保其全部传递依赖都有绑定；用 `:app:compileDebugUnitTestKotlin` 触发 Hilt 全图校验 | RC.14.01 |
| 7 | 详情页评分人数错配（真值 2.7 万被显示成极小值/选成同名小说·漫画·二期的数据） | 评分修复只在**榜单**路径加了 `type=2` 过滤；**搜索/匹配**路径 `searchSubjects` 不传 filter，选代表 `representativeOf` 以匹配置信度为首排序键、评分人数仅次级 tiebreak，故同名小说/漫画/二期(343241/377761/550507) 置信度不低于 TV 动画(464376,27330) 时被选为代表 | （2026-06-23 已修复）选代表/反查/搜索排序四处统一改用「综合得分 `representativeScore` = 标题相似度 + 评分人数对数加成(≤0.18)」并对动画候选按 `type=2` 收敛；见 `CrossSourceMerge.representativeScore`，回归测试 `CrossSourceMergeTest` | 「修复」要覆盖**所有**调用路径（搜索+榜单+反查），勿只改一条；文档标「完成」前须按真实抓包数据回归，避免名实不符 | RC.01 3.7 |
| 8 | 榜单滑到约第 20 名即提示「已到底部」，无法加载第 61+ 名 | `canLoadMore = 本页返回数 >= limit`，但 Bangumi 实验性 `search/subjects` 某页可能返回少于 limit 条（即便后面还有），被误判到底 | `searchRankedSubjects` 透出分页 `total`（新增 `BangumiRankedPage`/`RankingPage`）；上层 `offset += 本页返回数`、`canLoadMore = 本页非空 && offset < total` | 分页「是否到底」应以服务端 `total`/游标判定，**绝不**用「本页数 == pageSize」近似；实验性 API 尤其会短返回 | RC.05.04 |
| 9 | 「今晚看什么·想看的标签」恒显示「暂无可用标签」、题材筛选筛不出内容、点推荐显示「暂无内容」 | 领域 `Work.tags` 由 `toWork()` 正确映射，但持久化层丢标签：`persistMatches`/`BangumiSyncManager` 落库只写 `WorkEntity`（不含 tags），`observeWorks` 又以 `toDomain(tags=emptyList())` 读出 → 候选池/facet 作品标签恒空 | 新增共享 `WorkTagWriter` 在两条入库路径写 `tags`+`work_tags`（统一主键 `category:name` 避免唯一索引冲突）；`observeWorks` 经新增 `TagDao.getTagsForWorks` 批量回填标签 | 领域模型「有字段」≠「已持久化」：跨「写实体」「读实体」两端都要核对字段是否真正落库/读出；连接表型字段最易在 `toEntity`/`toDomain` 边界被静默丢弃 | RC.05.06 / RC.07 |
| 10 | 单元测试全部报 `Could not execute test class … ClassNotFoundException`（含最简 `SanityTest`，`clean` 后依旧），但 `compileDebugKotlin` 成功且 `.class` 已生成 | Gradle 测试 worker（fork JVM）运行时 classpath 无法解析已编译测试类；高度疑似工程路径含**空格 + 非 ASCII**（`ACG Compass de分支`）触发 Windows 下 test-worker classpath/pathing-jar 解析失败；叠加 `--tests` 过滤更易复现（Kotest 下尤甚） | 新算法暂以 `compileDebugKotlin` 全量编译 + 手算阈值验证；测试执行需在**无空格/无中文**路径（或 CI）运行，或排查 Gradle test worker classpath 配置 | 验证不能只靠 `--tests <Class>`（Kotest 会 ClassNotFound）；测试目录避免空格与非 ASCII；严格区分「编译 classpath OK」与「测试 fork 运行时 classpath」 | RC.00 / RC.08 |
| 11 | 主源 `:app:compileDebugKotlin` 通过，但 `:app:compileDebugUnitTestKotlin` 报 `FakeBacklogRepository is not abstract and does not implement archiveToDust` | `BacklogRepository` 在「吃灰馆移出还原」一轮新增 `archiveToDust`/`restoreFromDust`，生产实现已更新，但测试里的 `FakeBacklogRepository`（`RouteMapRepositoryImplTest`）未同步实现 → 仅测试编译单元报错 | 给该 fake 补 `archiveToDust`/`restoreFromDust` 无操作实现 | 接口新增方法后必须跑 `:app:compileDebugUnitTestKotlin`（不止主源编译），以触发所有 fake/实现的完整性校验；新增接口方法时全局搜 `: 接口名` 同步所有实现 | RC.08 / RC.00 |
| 12 | 详情页口味匹配度「都挤在 55–65」、明显不合口味的作品也给高分；今晚看什么会推社区低分/低匹配作品 | 旧 `PersonalTasteScorer` 仅做标签重合的线性加权，输出未做分布校准也未拉开差距，区分度低；今晚看什么用其打分且缺质量护栏 | 落地最终版 12 维引擎：正负偏好向量 + 题材组合挖掘 + **温度化 logistic 校准**（`μ=median(z)`、`τ=max(0.18,std(z))`）+ **分数拉开** `50+sign·min(45,1.18·|100p-50|)→[5,95]`；今晚看什么改用引擎精排并加「社区分≥6.0 + 口味下限」双护栏与 14 天重复冷却；引擎与旧打分并存，缺特征/缺画像回退不退化 | 评分类输出要先校准到合理分布再拉开区分度，别用未校准线性分直接展示；改推荐排序口径时同步补「质量下限」护栏，避免为「个性化」牺牲基本质量 | RC.10.03 / RC.11 |
| 13 | 要「像 animeko 那样」一键 Bangumi 登录 + 自动刷新，但本项目无后端 | animeko 登录依赖其**自建后端中转站**（`auth.myani.org` 等）保管 `client_secret` 并处理回调；联网核验确认 Bangumi 令牌端点**强制 `client_secret`、不支持 PKCE**，纯客户端无法做到「零内置也能一键登录」 | **内置共享应用**：开发者注册一次，`client_id/secret` 经 `local.properties`(gitignored)→`BuildConfig`→Hilt `@Named` 注入，所有用户共用、仅需点登录（`requireClientId/Secret` 取「自填 ?: 内置」，缺省回退自填）；回调用 **WebView 拦截自定义 scheme**；续期放**启动期** `BangumiTokenRefresher`、避开 `AuthInterceptor` 依赖环 | OAuth「无缝体验」先核验是否依赖对方后端 + 目标是否支持 PKCE；无后端要一键登录只能内置共享 App（secret 仅放 gitignored 文件、属 native OAuth 已知权衡 RFC 8252），但**用户 token 仍各自获取**、不破坏「零内置用户凭据」 | RC.02 4.6 / RC.00 |
| 14 | 发现页「评分差异」恒空 / 极少 | 该榜需单作品 ≥2 个有效来源评分，但发现页批量为性能只读本地缓存（K9 `aggregateRatingsCached`），公共池作品多半仅主源一条评分 → `scoreSpread` 因有效源 <2 恒返回 `null` | 进入差异页时按需触发**有预算上限**的回填：对「仅 1 有效源、评分人数最多」的前 12 部调用既有 `aggregateRatings`（→`crossValidateRatings` 按标题跨源匹配落第二源），写回后差异榜经 `observeWorks` 自动刷新；先 `first { 非空 }`+超时等公共池就绪再选目标，避免「池未就绪→0 目标→提前 latch」 | 「批量只读缓存」与「单作品联网聚合」是两条数据路径；依赖多源的视图必须显式触发回填，且 best-effort + 预算上限 + 失败吞掉，复用既有交叉验证而非新造 | RC.05.05 / RC.07 |
| 15 | 需求列「A4 AI 画像分析」要用 LLM 校正标签分类，但代码库无任何 AI 调用基础设施 | 12 维「引擎」是纯统计（`domain/taste/*`），项目从未接入 LLM 客户端/密钥/提示词；硬上 AI 子系统风险大且非本批核心 | 交付**可落地且诚实**的版本：画像页「重新分析」按钮 = `recomputeFromLocal()` 重算统计画像 + `TasteEngine.refreshFull()` 联网补全 12 维特征（无需重新同步）；LLM 标签分类校正明确**延后为独立专项** | 文档写「AI」前先核验是否已有 AI 基础设施；无则区分「可立即交付的手动入口」与「需新建子系统的 AI 能力」，不为对齐措辞而伪造未实现的 AI | RC.10 / RC.00 |
| 16 | 「今晚看什么」多选标签时推荐偏题（选催泪推航海王、选校园恋爱日常推灵能百分百） | 旧意图匹配用「命中标签绝对数」线性加权，单个强标签即可拿高分，未要求**同时覆盖**多意图，也无主气质冲突惩罚 | 改 soft-AND 覆盖率：意图覆盖 = 命中意图占所选意图比例（几何覆盖 + minHit），关键意图缺失/主气质冲突扣分、意图不匹配封顶；`intentFit` 权重提到 0.32，理由展示覆盖率 | 多条件检索的相关性应建模为「覆盖率（AND 倾向）」而非「命中计数（OR 倾向）」，并对明显冲突显式惩罚 | RC.11 |

## 详细记录

> 当某条问题需要更长的背景说明时，在此处按 `### [序号] 标题` 展开记录复现步骤、分析过程与最终结论。

<!-- 模板：
### [1] 简要标题
- 问题现象：
- 原因：
- 修复方式：
- 避免策略：
- 相关 RC：RC.xx.yy
- 复现步骤 / 备注：
-->

### [7] Bangumi 评分人数错配：评分修复未覆盖搜索/匹配路径（2026-06-22 复核确诊）
- 问题现象：详情页某 TV 动画评分人数远小于真实值，或评分整体取了同名小说/漫画/二期的数据（真值如 2.7 万被显示为极小值）。
- 抓包确诊（代理 `bgmapi.anibt.net` 数据可信，拔作岛 9582 已验证正确）：同一作品名下 Bangumi 有多条不同 type 条目——
  - `464376` type=2(动画) platform=TV total=**27330** ← 真正的 TV 动画（应选）
  - `343241` type=1(小说) total=2353
  - `377761` type=1(漫画 @comic) total=206
  - `550507` type=2(动画) total=11 ← 第二季（评分人数极少）
- 原因：评分/人数修复只落到**榜单**路径（`BangumiRemoteDataSource.searchRankedSubjects` 用 `filter.type=[ANIME]`）。**搜索/匹配**路径未做 type 过滤：`searchSubjects` 默认 `filter=null`，`WorkRepositoryImpl.search` 调用 `bangumi.searchSubjects(keyword)` 不传 filter；选代表 `CrossSourceMerge.representativeOf` 以 `matchConfidence` 为首排序键，`popularity`(=`rating.total` 评分人数) 仅作同置信度的次级 tiebreak。当小说/漫画/二期标题置信度 ≥ TV 动画时，人数加权敌不过置信度，代表落到小条目 → 评分人数错配。
- 修复方向（待修，用户要求先记录暂不动）：**搜索匹配「动画」作品时，对 Bangumi 候选先按 `type=2`(动画) 过滤排除小说/漫画，再按评分人数加权**（比单纯人数 tiebreak 更稳）。须同时覆盖 `searchSubjects`、`backfillBangumi` 反查等所有取 Bangumi 候选的路径。注意：全局搜索本身多源多媒介（含小说/漫画/游戏），过滤应「当目标被判定为动画时对 Bangumi 候选按 type=2 收敛」，而非全局只搜动画。
- 避免策略：评分类「修复」必须覆盖**全部调用路径**并按真实抓包数据回归；文档标「已完成」前先验证，避免名实不符（本条即文档曾误标 P0-1 完成的教训）。
- 相关 RC：RC.01 3.7（不伪造）。

### [9] 候选池/题材筛选/今晚看什么标签恒空：领域有 tags 但未落库（2026-06-23 确诊并修复）
- 问题现象：①推荐器「想看的标签」永远「暂无可用标签」；②发现页题材筛选选了也筛不出任何作品；③「今晚看什么」点心情进推荐后显示「暂无内容」。三处实为同一根因。
- 根因：`BangumiSubjectDto.toWork()` 本就把社区标签映射进 `Work.tags`（前 15 个 `CONTENT`），但**持久化两端都丢了它**——
  - 写：`WorkRepositoryImpl.persistMatches`（发现/搜索）与 `BangumiSyncManager`（个人收藏同步）落库都只 `workDao.upsert(work.toEntity(...))`，而 `Work.toEntity()` 不含 tags（标签存于 `tags` + `work_tags` 连接表），此前仅「备份恢复」路径会写连接表。
  - 读：`observeWorks()` 用 `it.toDomain()`，其 `tags` 形参默认 `emptyList()`，未从连接表回填。
  - 结果：候选池（`RecommenderViewModel.loadCandidates`）与题材 facet（`DiscoverBoards.buildFilterFacets`）拿到的作品 `tags` 恒空 → `computeAvailableTags` 空、`applyFilter` 题材永不命中、推荐 tag 硬筛 `matched.isEmpty()` 淘汰全部 → 空态。
- 修复：抽出共享 `WorkTagWriter.persist(works)`，在 `persistMatches` 与 `BangumiSyncManager` 两条入库路径写 `tags`+`work_tags`；标签主键统一为 `"${category.name}:${name}"`（与 `tags` 表 `(category,name)` 唯一索引一致，两路径生成同 id 才不会在 upsert 时违反唯一索引而崩溃）。读侧 `observeWorks` 新增 `TagDao.getTagsForWorks(workIds)`（分批 ≤900 避开 SQLite 变量上限）批量回填 `toDomain(tags=...)`。
- 注意（数据迁移）：存量作品的 `work_tags` 仍为空，需**重新同步一次**或**重进发现页触发公共池加载**后才补齐标签；新装/新数据无此问题。
- 避免策略：领域模型「有字段」不代表「已持久化」。凡是经连接表存储的字段（tags、aliases→已在 WorkEntity 内联则另说），务必在 `toEntity`（写）与 `toDomain`（读）两端都显式核对；同一种数据存在多条入库路径时，抽公共写入器统一主键规则，避免「一条路径写得进、另一条 id 不一致写崩」。
- 相关 RC：RC.05.06（高级筛选）/ RC.07（口味与标签）。

## 关联文档

- 需求与可追溯性：见 [`REQUIREMENTS.md`](./REQUIREMENTS.md)。
- 开发与 API 核验：见 [`DEVELOPMENT.md`](./DEVELOPMENT.md)。
- EARS 验收条件：见 `.kiro/specs/acg-compass/requirements.md`。

## Runtime 验收第 3 轮（R1–R19）运行问题、根因与规避

- 搜索每次返回页面就重搜（R1）：根因 = uiState 用 `WhileSubscribed`，返回时订阅重启触发上游再执行。规避 = 对“纯展示型”搜索结果流用 `SharingStarted.Lazily`，让结果在 ViewModel 存活期内只算一次；状态保留交给 ViewModel/缓存而非导航 saveState。
- 底部「首页」误回发现页（R13）：根因 = 顶层切换用了 `saveState/restoreState`，恢复了错误目的地状态。规避 = 顶层 Tab 切换 `popUpTo(startDestination)` + `launchSingleTop`，不用 save/restore；页内状态由各自 ViewModel 负责。
- 发现→筛选闪退（R15）：根因 = LazyColumn 列表项 key 重复（同一标题/无 id）。规避 = 用 `itemsIndexed` + 复合 key（index+title）；并明确“发现筛选只作用于库内/待补作品”，不混入搜索缓存。
- 我的页顶部内容被截断、无法上滑（R9）：根因 = 外层 Scaffold（含底栏）已消费系统栏 inset 并以 padding 传入 NavHost，内层各页 Scaffold/TopAppBar 又重复消费一次，产生“双重内边距”。规避 = 顶层页统一用 `AcgTopLevelTopBar`（`windowInsets=0`）+ 内层 Scaffold `contentWindowInsets=0`；滚动列另加底部 padding。
- 时光机未登录不可用、同步按钮无反应（R7）：根因 = 时光机只读渲染，无任何动作入口；同步强绑定 Bangumi。规避 = 本地快照（从待补池建初始基线/记差异）无需登录即可用；Bangumi 同步做成凭据门控的增强动作，未配置时明确提示而非静默失败，结果用 Snackbar 反馈。
- 官方文档点不开且无反馈（R12）：根因 = 仅用 `uriHandler.openUri` 且 `runCatching` 吞掉异常。规避 = 先用显式 `ACTION_VIEW` Intent（`FLAG_ACTIVITY_NEW_TASK`），失败再回退 uriHandler，仍失败则复制链接到剪贴板并 Toast。
- 公共可搜源（AniList/VNDB 等）未填 token 看不到结果（R11/R18）：搜索层本就并行查询所有源（MAL 除外，需 Client ID），“看不到”多为该源运行时请求失败被静默吞掉。规避 = 提供真实连接测试探针（10s 超时）暴露每源“公共搜索可用/失败原因/超时”，便于定位；状态文案区分“公共搜索可用，个人同步未配置”。
- 按钮文字换行 / 搜索框换行（R16）：规避 = 长文案按钮统一 `maxLines=1 + TextOverflow.Ellipsis`，搜索框 `singleLine`、占位文案缩短。

## Runtime 验收第 4 轮（R20–R37）根因与规避

- 搜索同一作品被拆成多张卡（R20）：根因 = 搜索只是把各源结果并排展示，从未跨源聚类。规避 = 在 UI 契约层加纯函数 `mergeCrossSource`，按「全标题变体（含各源普遍填充的日文名）相似度 + 年份不冲突」贪心聚类为一张卡。关键洞察：Bangumi/AniList/VNDB/Jikan/MAL 的 mapper 大多会填 `titles.ja`（日文原名），使跨语言（中文 canonical ↔ 罗马音）也能借共享日文名命中。极端纯罗马音↔汉字且无共享日文名的对仍需「手动调整」兜底。
- 「手动纠正」泛滥（R21）：根因 = 大量结果置信度 < 0.85 都被判低置信。规避 = 合并后多源簇视为高置信不显示纠正；仅低置信单源显示「手动纠正」，合并卡显示轻量「已合并 N 源·调整」。
- 设置卡标题竖排（R26）：根因 = 标题与「状态 AssistChip」同处一行且 chip 文案在 R11 后变长（如「公共搜索可用，个人同步未配置」），把 `weight(1f)` 的标题挤到极窄而逐字换行。规避 = 状态文案移到标题下独立一行，标题 `maxLines=1+ellipsis`，绝不与等宽 chip 抢宽度。教训：往状态 chip 塞长文案会破坏同行标题布局，长状态应独立成行。
- 发现页榜单/筛选无内容（R22）：根因 = 第 3 轮为避免「搜索历史污染」把发现池限制为「待补池交集」，导致新用户/未加待补时永远空。规避 = 放开为「已缓存多源作品（公共发现池）」，并在筛选 UI 标注范围。教训：边界收紧要避免把功能收成不可用；用「公共发现池 vs 个人库」分层而非一刀切。
- AI 只显示「已配置」不能测（R28）：规避 = 复用 AiProviderRegistry/Selector，对选定 provider 发一次最小补全（model 留空回落默认、maxTokens=1、10s 超时），区分成功/失败原因/超时。
- 口味画像 CTA 死按钮（R30）：根因 = StateScaffold 的 onCta 未接线。规避 = 接线导航到设置（Bangumi/同步入口）。完整「一键导入并刷新画像」依赖用户收藏同步（见下）。
- 未完成项的共同根因：Bangumi**用户个人数据**（收藏/状态/评分/进度/短评）从未落地本地存储，Room 也没有「观看状态」表。我的页统计、详情页「我的记录」、口味画像导入、时光机导入都依赖这套同步基础设施。规避策略 = 后续单独排期：新增用户收藏 Entity+DAO+迁移 → 一个 BangumiSyncManager 分页导入 → 同时喂给四个消费方；迁移需充分测试，不与本轮 UI 修复混做以免破坏既有数据。

## Runtime 验收第 5 轮（R38–R54）根因与规避

- 我的页滚不到底（R38）：根因 = MineScreen 用 `Column(verticalScroll)`，而能正常滚动的页（首页/待补池/发现/时光机）都用 `LazyColumn`；该页在某些设备/内容高度下滚动范围异常。规避 = 统一改用 `LazyColumn` + 底部 96dp contentPadding。教训：可滚动长页一律用 LazyColumn，并给足底部留白以清开底栏。
- 设置页顶部卡被裁切（R39）：根因 = 设置是嵌套路由，外层 NavHost 已被外层 Scaffold 的系统栏 inset padding；设置内层 Scaffold 的 TopAppBar 又默认消费状态栏 inset → 双重顶部 inset 压缩首卡可视。规避 = 内层 Scaffold `contentWindowInsets=0` + TopAppBar `windowInsets=0`，顶部 inset 只由外层处理。
- Bangumi「已配置但全连不上」（R40）：根因 = 公共搜索（匿名）与个人接口（需 token，如 getMe）混为一谈；失败只报「连接失败」。规避 = 统一经 CredentialStore 状态 + BangumiSyncManager.isConfigured()；失败一律带 `AppError.cause`（401/403/网络/超时/解析）；公共搜索不受 token 影响。
- 个人数据未入库（R45，本轮核心）：根因 = 之前 Bangumi 个人收藏从未落地 Room，我的页/详情/口味/时光机都没有可消费的数据源。规避 = 新增 `user_collections` 表（Entity+DAO+非破坏 MIGRATION_1_2，DB v1→2）+ `BangumiSyncManager` 分页同步入库；四个消费方统一读这张表。要点：Bangumi work id == subjectId，使同步与搜索缓存共用 work id，避免重复作品；迁移只 CREATE TABLE 不动旧表，绝不 destructive。
- 搜索合并「只合卡不合数据」（R42）：根因 = 各源各自生成独立 work id，合并只在展示层聚类成一张卡，底层仍是多个 work。要真正融合多平台评分需把多源 work 归并为同一规范作品并合并 source links/ratings（数据层 work-id 统一），属较大改动，单独排期。
- 时光机「只提示不导入」（R46）：根因 = 旧实现只 getMe + 计数并 Snackbar 提示。规避 = 改为真正 syncCollections 入库 → 读库映射 CollectionState → 建初始快照/记 change log，本地与云端同一时间线。

## Runtime 验收第 6 轮续修（R61–R73）根因与规避

- 全页面顶部被标题栏遮挡（R61）：根因 = inset 归属不一致——外层 Scaffold（默认消费系统栏）与各页内层 Scaffold/TopAppBar（之前被设为 windowInsets=0）叠加，导致「双重或缺失」顶部 inset。规避 = 单一来源模型：外层 Scaffold `contentWindowInsets=0` 不碰顶部，**每个页面的 TopAppBar 用默认 windowInsets 自行消费状态栏**。教训：双 Scaffold 嵌套时，状态栏 inset 必须由唯一一层负责，不要两层都设或两层都清零。
- 时光机导入入库但 UI 不显示（R62）：根因 = 时光机 UI 只消费 ChangeLog，而首次导入只产生「初始快照」无 change log。规避 = uiState 同时观察 snapshots/user_collections，仅有快照时显示「基线快照（N 个作品）」而非空态。教训：初始基线无 diff，UI 不能只依赖 diff 流判空。
- 搜索合并只合卡不合数据（R64）：根因 = 合并只在 UI 层聚类成一张卡，底层各源仍是各自 work id，详情只聚合代表 work 的链接。规避 = 把通用聚类下沉到 `domain/matching/CrossSourceMerge`，仓库 persistMatches 把同簇各源 source link 统一指向「代表 work id」；详情 refreshRatingsAndWork 据此抓取并聚合多平台评分。UI 与仓库共用同一聚类与代表选择，保证卡片 workId == 详情聚合 id。
- 合并误合并/漏合并（R65）：根因 = 仅靠前缀/相似度易把「系列总称 vs 具体分作 / 续作 / 新章」混合。规避 = 通用多字段规则：原文标题归一化变体相等 + 年份/类型不冲突才自动合并；冲突则不合并（保留独立/人工确认）。跨语言匹配依赖各源普遍填充的日文原名变体。**严禁对样例（9-nine/2.5）写 if/else**，只用通用规则 + 样例回归。
- Bangumi 官方 API 国内不可达（第 6 轮已修）：可配置 Base URL + 拦截器统一改写 host；UI 从并列按钮改为下拉框（R67）以省空间，底层拦截器与 Token 保护不变。
- 口味标签原始格式（R68）：下划线/连字符转空格清洗后展示。

## Runtime 验收第 7 轮（R74–R91）根因与规避

- 详情页封面贴状态栏 / 多页面顶部过靠上（R74/R81）：根因 = 第 6 轮把外层 Scaffold 顶部 inset 清零后，依赖各页 TopAppBar 自己消费状态栏；但 DetailScreen 根本没有 Scaffold/TopAppBar（纯 Column），无人消费状态栏 → 内容贴顶。规避 = 无标题栏的页（Detail）显式 `statusBarsPadding()`；有 TopAppBar 的页用默认 windowInsets。教训：清零外层 inset 后，必须确认每个页面都有「某层」消费状态栏，否则纯内容页会贴顶。
- 详情页误报「尚未生成口味画像」（R80）：根因 = `toTasteMatch` 在「作品无标签」时也返回 Unavailable，且文案写死「尚未生成」。规避 = Unavailable 携带 reason，区分「画像未生成」「画像已生成但作品无标签」「样本不足」。
- 首页「立即同步」无反应（R85）：根因 = onManualSync 是只自增触发器的桩。规避 = 接入 BangumiSyncManager 真同步，未配置跳设置。
- 待补池筛选无结果回到「空池」（R86）：根因 = 把 Success(emptyList) 一律当空池。规避 = 结合 hasActiveFilter 区分真空与筛选无结果。
- 一键抽番 FAB 遮挡卡片按钮（R87）：规避 = 去掉悬浮 FAB，改为内容区按钮。
- 搜索漏合并（R75）：根因 = 仅靠相似度阈值，「核心日文名 + Episode/副标题」对的相似度略低于阈值。规避 = 增加「较长核心被完整包含」判定（长度≥10 防过短前缀误并），仍为通用规则、无样例硬编码。
- 时光机概念偏差（R82/R83，未完成）：当前为两个并排功能按钮 + 基线摘要，缺少时间线主体与作品明细；需信息架构重构，已记录排期。


## Final Sprint 续修（参考项目补充轮）根因与规避

| 现象 | 根因 | 修复 | 规避 |
|---|---|---|---|
| 9nine / 9-nine 跨源漏合并 | 归一化把连字符转空格，`9nine` 与 `9 nine` 仅差空格不相等 | 新增紧凑（去空格）精确匹配 `normalizeCompact`，长度≥4 才用 | 标题比较同时保留「带分隔」与「紧凑」两种归一形 |
| 稳定度/争议度看起来相加=100 | 旧实现 stability=1-controversy | 改为独立模型：稳定度用人数/来源数/方差，争议度用极差/标准差 | 多维度指标各自建模，不用互补式占位 |
| 口味标签参考价值低 | 仅统计用户自定义标签（用户很少打标签） | 同步时合并 Bangumi subject 社区标签到收藏记录并清洗 | 口味画像标签来源以「作品标签」为主、自定义为辅 |
| 详情页像评分页 | 简介/角色/关联/路线 Tab 未接数据 | Work.summary（迁移 v3）+ 接 Bangumi characters/persons/relations + 结构化观看路线 | 详情区块结构先固定，缺字段填「暂无数据」 |
| 发现页本季/榜单空 | 发现池仅本地已缓存作品 | 接 Jikan top/season 公共端点写入作品+评分 | 公共数据源无需 token，进入分区按需加载 + 错误重试 |
| 观看路线只是文字 | route 仅渲染字符串 | 结构化 RouteEntry（主线/可选/可跳过）+ 可点击 + 一键加入待补池 | 关联关系本地分类，AI 仅排序不编造 |


## 口味个性化增强轮（B 阶段，2026-06）根因与规避

| 现象 | 根因 | 修复 | 规避 |
|---|---|---|---|
| 想给口味标签加「时间权重」又怕破坏计数守恒属性测试 | 直接对计数乘权重会让展示计数变非整数、且可能 > 桶样本数 | 计数仍取**原始出现次数**（整数），仅**排序**改用近因加权得分（半衰期 540 天 + 地板 0.3） | 把「排序信号」与「展示量」分离；守恒属性只约束展示量，加权只影响顺序；无时间戳退化为未加权（向后兼容） |
| 榜单缓存想入 Room 但顾虑迁移风险 | 原实现用 DataStore 存换行拼接的 id blob 以规避迁移 | 新增 `ranking_cache` 表 + `MIGRATION_4_5`，DAO 整范围事务覆盖写 | 迁移 DDL 逐字对齐 Room 导出的 `schemas/N.json` 的 `createSql`；复合主键写 `PRIMARY KEY(a, b)`；构建后核对 5.json |
| Room `@Transaction` 写在 interface 默认方法上是否真正织入事务存疑 | 项目无先例，Kotlin interface default + suspend 的事务织入不确定 | DAO 改用 `abstract class` + 具体 `open @Transaction` 方法（Room 文档规范写法） | 需要多语句事务的 DAO 一律用 abstract class，不用 interface 默认方法 |
| 单元测试在本机必然失败（ClassNotFoundException） | 工程路径含空格+中文且 `android.overridePathCheck=true`，分叉的 JUnit/Kotest test runner classpath 解析失败 | 以 `compileDebugKotlin`/`compileDebugUnitTestKotlin` 编译为验证门；迁移测试参数化覆盖 `AcgMigrations.ALL`（androidTest，真机/模拟器跑） | 该环境验证以编译为准，最终交付用 `assembleDebug` 出 APK |


## H 轮：Bangumi 本地编辑回写排查（H15/H4）

- 现象：本地标注「想看」后 Bangumi 无反应。
- 根因分析（已读 `参考项目文件/` 两个客户端源码）：
  - `czy0729/Bangumi`：收藏修改用**网页表单** `POST /subject/{id}/interest/update`（带 formhash），章节进度 `POST API_EP_STATUS` / `API_SUBJECT_UPDATE_WATCHED(watched_eps)`；v0 仅用于**读取**收藏。
  - `xiaoyvyv/bangumi`：同样走网页表单 `/subject/{id}/interest/update`（FieldMap interest/rating/comment/tags/privacy）+ `/subject/ep/{epId}/status/{type}`。
  - 即两个客户端都**不用** v0 token POST 改收藏（多为历史原因/兼容匿名 cookie 登录）。本项目用 token 鉴权，采用官方 v0 `POST /v0/users/-/collections/{subject_id}`（现代文档支持，需写权限 token）。
- 真正的「没反应」直接原因：**加入待补池路径从未调用任何写接口**（只写本地 backlog）。已修复：详情页「加入待补池」默认置 Bangumi「想看」(type=1) 并回写 + 本地 user_collections 同步。编辑「我的记录」对话框路径本就调用 updateUserCollection。
- 风险提示：若用户用非官方 Bangumi 反代地址且未确认 Token 风险，`BangumiBaseUrlInterceptor` 会移除 Authorization → 写接口 401，故写操作需在官方地址或已确认风险的代理下进行。


## H 轮收尾：WorkManager 周期同步 + 评分交叉验证 + 评论摘要 + 吃灰=搁置

### H7 WorkManager + Hilt 接线（关键，易踩坑）
- 依赖：`androidx.work:work-runtime-ktx`(2.9.1) + `androidx.hilt:hilt-work`(1.2.0) + `ksp(androidx.hilt:hilt-compiler)`。三者缺一不可（hilt-compiler 用 ksp 不是 kapt）。
- Worker：`@HiltWorker class X @AssistedInject constructor(@Assisted ctx, @Assisted params, 其它依赖)`，继承 `CoroutineWorker`。其它依赖（如 `BangumiSyncManager`）由 Hilt 注入。
- Application 必须 `implements Configuration.Provider`，提供 `workManagerConfiguration = Configuration.Builder().setWorkerFactory(hiltWorkerFactory).build()`，并 `@Inject lateinit var workerFactory: HiltWorkerFactory`。
- **Manifest 必须移除默认初始化器**，否则自带 Configuration 不生效并告警：
  ```xml
  <provider android:name="androidx.startup.InitializationProvider"
      android:authorities="${applicationId}.androidx-startup" tools:node="merge">
      <meta-data android:name="androidx.work.WorkManagerInitializer"
          android:value="androidx.startup" tools:node="remove" />
  </provider>
  ```
  （根 `<manifest>` 加 `xmlns:tools`）。
- 周期任务下限 15 分钟（小于会被钳制）；`enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, req)` 间隔变更即时生效；间隔=0 用 `cancelUniqueWork(name)` 关闭。
- 调度入口放 `Application.onCreate`，用 app 级 CoroutineScope 观察 `SettingsDataStore.autoSyncIntervalMinutes.distinctUntilChanged()` 并 `SyncScheduler.apply(minutes)`，做到「设置一改就重排」。

### H16 评分多源交叉验证（mzzbscore 思路落地）
- `aggregateRatings(workId)` 读取的是 `ratings WHERE workId=:workId`，所以跨源评分要以**当前 workId** 写入 rating 行（sourceId 区分），而非各源自己的 id。
- `crossValidateRatings`：对当前作品**尚无评分行**的公共动画源（Bangumi/AniList/Jikan）用标题跨平台搜索，按候选各别名 vs 本作规范名取最大 `matchConfidence`，**≥0.86** 才采纳（高阈值宁缺毋滥防误配），拉取对方评分写入并落一条源链接（下次直接命中免再搜）。仅 `MediaType.ANIME`、全程 best-effort。

### H9 评论摘要（不伪造）
- Bangumi v0 无干净的「条目评论」端点；不爬网。改用既有「无剧透评价雷达」（AI 或本地规则 `LocalFallbackRadar`）的结构化输出折叠为多段口碑概览 + 社区评分行，并标注来源（AI / 本地规则）。雷达与评分都缺失才「暂无数据」。

### H5 吃灰池=搁置
- `BacklogViewModel.onBulk(ARCHIVE_TO_DUST_MUSEUM)` 在仓库归档后，对每个 id 调 `archiveToBangumiShelved`：本地 user_collections 置「搁置」+ 已配置时回写 Bangumi `updateUserCollection(type=4)`。已「看过」不回退；非数字 id（非 Bangumi 来源）仅本地标记；失败吞掉。仓库 `BacklogRepositoryImpl` 构造保持不变（其单测直接 new，不引入难以伪造的 BangumiRemoteDataSource 依赖），写回逻辑放 VM 与 `DetailViewModel` 既有模式一致。


## I 轮（实测反馈，对应 RC.33–48）

### ⚠️ 严重教训：禁止用 PowerShell 读写含非 ASCII 的源文件
- 用 `Get-Content -Raw | ... | Set-Content` 批量替换 `DiscoverScreen.kt` 时，Windows PowerShell 5.1 默认以 ANSI 读取 UTF-8 文件，导致全部中文被替换为 U+FFFD（读时即丢失，不可逆），89 处损坏、编译失败。
- 恢复方式：代码结构未损（仅字符串/注释里的中文丢失），逐行重建中文 → 写入一个 UTF-8 数据文件（`行号<TAB>正确内容`）+ 一个**纯 ASCII** 的 PS 脚本（`[System.IO.File]::ReadAllLines/WriteAllLines` 显式 UTF8 no-BOM）按行号回填。62 行修复后 0 个 U+FFFD，编译通过。
- **铁律**：源文件编辑一律用 `fs_write` / `str_replace`（保证 UTF-8）。PowerShell 仅用于运行命令，绝不用于改含中文/日文的文件。

### I15/I16 Bangumi 发行状态 + 开播日期
- v0 subject 无干净的「完结」布尔。`deriveBangumiStatus(date, episodes)` 保守启发式：日期解析失败→UNKNOWN；晚于今天→未发布；单集/剧场版→已完结；多集按 `开播+(集数-1)*7天+14天缓冲` 估完结日判定连载中/已完结。
- 开播日期到天：新增 `Work.airDate` + `WorkEntity.airDate` + Room **v3→v4** 迁移（`ALTER TABLE works ADD COLUMN airDate TEXT`），DB version 3→4，schema 4.json 已生成。`CURRENT_DB_VERSION` 历史上未随 v2/v3 维护（仍为 1），本轮保持不动以免触动已失败的 DatabasePreMigrationBackupTest。

### I2 inset 模型
- 单一来源：外层 `AcgApp` Scaffold 默认 contentWindowInsets 消费状态栏 + 底部导航，`.padding(innerPadding).consumeWindowInsets()` 下发；各页 Scaffold + TopAppBar 用零 inset。
- 因此各页列表 **不应**再为底部导航预留 96dp（会双算→滚到底大空白）→ `Bottom` 收敛到 28dp。
- 位于 TabRow/同步栏下方的列表（发现各 Tab、时光机）再叠加 55dp 顶部会过度下沉 → 新增 `UnderBarTop=10dp`。直接在标题栏下方的页面（首页/我的/设置/我的库/口味）保持 55dp。

### 既有失败单测（与本轮无关，未触碰其文件）
- `AggregateRatingsUseCaseTest`（评分稳定度 0.795 vs 期望 >0.9）、`DatabasePreMigrationBackupTest`（备份计数）——两轮前即失败，本轮 328 测试仍恰好 2 失败、无新增。


## I 轮第二批（I4/I5/I7/I6/I8）

### I5/I7 本地→Bangumi 回写「同步失败」根因（重要）
- 真因：Bangumi v0 收藏写接口 **POST 仅用于新建**，修改**已存在**收藏必须用 **PATCH**。此前一律 POST，对已收藏条目（用户大多数场景）失败 → UI 恒提示「同步失败：请稍后再试」。
- 修复：`BangumiRemoteDataSource.updateUserCollection` 改为先 `PATCH /v0/users/-/collections/{id}`，返回 404（尚未收藏）再 `POST` 新建。`BangumiApi` 拆 `patchUserCollection`(@PATCH) / `createUserCollection`(@POST)。
- 佐证：animeko `datasource/bangumi/v0.yaml` 的 `patchUserCollection`「PATCH 方法的所有请求体字段均可选」；两个 Bangumi 客户端避开 v0 写接口走网页表单，侧面印证 v0 POST/PATCH 语义需区分。
- 排查路径（已确认非以下原因）：UA 拦截器注入合规 `ACGCompass/{ver}`；`TokenHeaderAuth(BANGUMI, Bearer)` 注入 Authorization；`NetworkJson explicitNulls=false` 已省略 null 字段（非 400 主因）；RateLimitInterceptor 仅对 Jikan 且为阻塞式（不对 Bangumi 合成 429）。

### I6 我的记录清空语义
- 编辑对话框=完整意图覆盖：VM `onUpdateMyRecord` 不再 `?: existing`，未选状态/留空即清空；回写 Bangumi 时显式 `rate=0` / `ep_status=0` / `comment=""` 清除；有进度自动在看、满总集自动看过；进度按总集封顶。

### I8 社区标签
- 既有管线已满足：F9 同步合并作品社区标签进 `user_collections.tags`（口味画像高/低分桶来源）；H13 写入 `work.tags`；G11 匹配用社区标签 + 负信号 1.5× 降权 + 社区评分融合。

### I4 吃灰馆
- 独立页 `DustMuseumScreen` 复用 `BacklogViewModel.dustMuseumCards`（filter inDustMuseum=true、按吃灰天数倒序）；逐条「移出吃灰馆」= bulk(RESTORE_FROM_DUST_MUSEUM)；入口在待补池顶栏。


## 0.15.0 上手实测续修（N1–N12）根因与规避

| 现象 | 根因 | 修复 | 规避 / 教训 |
|---|---|---|---|
| 评分差异榜同一部番出现两条（中文名 + 罗马音名），都显示同一 Jikan 分 | 两条未合并的同番行（不同源 / 不同语言标题）都进榜，且 D9 交叉验证给两者挂了同一 Jikan 分 | `buildScoreDiffBoard` 前置 `dedupeSameWork`：按标题变体（canonical/cn/ja/romaji/en/aliases 归一化，长度≥4）交集**聚类去重**，每簇留 Bangumi/中文代表 | 展示层去重用「标题变体交集」跨语言聚类；不臆造合并分值以免误并邻季导致分数错配 |
| 口味匹配分普遍偏低（连已评高分作品也低） | 已评分作品未把「个人评分」作为口味锚点；部分作品无特征向量 | 接线个人评分锚定 + 对全部已评分作品补齐特征 | 已评分作品应以真实评分为强信号，不能只靠标签重算 |
| 今晚看什么进入即卡 / 首屏联网 | 热路径逐作品联网补特征 | 热路径只读本地缓存 `budget=0`，画像后台重建 | 首屏路径严禁同步联网；联网补齐放后台 |
| 口味下限设「关闭」仍过滤掉低匹配作品 | 「关闭」(0) 被强制回落 0.45，形同虚设 | `tasteMatchThreshold=0` 时**不**施加下限；候选不足逐级放宽；标签口径与画像统一 | 「关闭」必须真关闭（WYSIWYG）；默认给 0.4 质量档而非硬编码兜底 |
| 首启需特殊网络才能用 Bangumi | 默认官方 `api.bgm.tv` 部分网络不可达 | 首启引导默认设社区反代 `bgmapi.anibt.net` + 说明 + 「Token 经反代」同意勾选（仅首启写入） | 反代默认只改公共访问；个人 Token 经非官方需显式同意，且不影响老用户 |
| 主线跳转的关联条目详情只有 Bangumi 评分 | 关联条目仅 Bangumi 源；`crossValidateRatings` 按**绝对相似度阈值**逐源匹配，续作 / 跨语言标题易漏配 | 新增 `enrichViaCrossSourceSearch`：复用搜索页「多源搜索 + 季度感知聚类（`sameWork`/`clusterMatches`）」把同簇其他源评分挂到本 workId | 单源作品补齐应复用「聚类」而非绝对阈值逐源匹配；聚类季度感知，避免把邻季评分挂错 |
| 文档误记「无 AI 基础设施」 | 0.14.0 收尾把 N3 延后写成「无 AI 基础设施」 | 更正：项目实有完整 AI 管线（AiEngine/AiTask 五类/OpenAiCompatibleProvider…）；N3 仅为待接入新任务类型 | 延后原因要写准确，避免误导后续排期 |
