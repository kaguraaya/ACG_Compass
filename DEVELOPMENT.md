# ACG Compass 开发文档（DEVELOPMENT.md）

> 本文档记录每个数据源与 AI Provider 的**已核验官方 API 文档链接 / 核验日期 / 实际字段 / 失败处理 / 数据迁移**。
> 维护规则（RC.00 文档制度 / RC.01）：调整接口、字段或授权方式时必须同步更新本表。
> 本文件**绝不**记录任何 key / token / client secret / AI API key；如需示例一律使用脱敏占位符（如 `sk-****…ab`）。

## RC.01 强制流程：实现各源前先核验官方文档

> 这是 RC.01 的强制开发流程。任何数据源客户端（Bangumi / AniList / Jikan / MAL 官方 / VNDB）以及任何对外接口的实现，**必须在写代码之前**完成以下步骤，否则不得进入实现阶段。

1. **联网核验最新官方文档**：在开始实现任一 `Data_Source` 接入前，先联网打开该源的最新官方文档，核验以下四项：
   - 授权方式（无需鉴权 / Token / OAuth / OAuth+PKCE / Client ID 等）；
   - 实际字段（请求参数、返回结构、字段名与类型、可空性）；
   - 速率限制（如 Jikan 约 3 req/s 与 60 req/min，以最新文档为准）；
   - 返回结构与错误码（200 / 401 / 403 / 404 / 429 / 500、空数组、字段缺失）。
2. **记录核验结果**：将“已核验官方文档链接 + 核验日期 + 实际字段 + 失败处理 + 数据迁移”写入本文件对应表格。`核验日期` 在实际联网核验后填写，未核验前保持 `待核验`。
3. **以官方文档为准**：当接口路径、字段或授权方式与设计文档不一致时，以最新官方文档为准；更新前先核对当前代码的实际请求，再决定迁移策略。
4. **遵守速率限制**：按官方文档配置令牌桶/节流，在接近限制（约 80%）时主动节流。
5. **无稳定官方接口时降级**：若某能力（如 Bangumi 公共吐槽箱、完整历史时光机）无稳定官方接口，则降级处理，**不得凭空假设字段或抓取网页**。
6. **凭据安全**：核验与实现过程中产生的任何 key/token 只保存在用户本机的 `CredentialStore`，绝不写入代码、资源、测试、文档或日志（RC.00）。

> 核验完成后，请在对应数据源表格的 `核验日期` 列填入实际日期（格式 `YYYY-MM-DD`），并补全 `实际字段` 与 `失败处理` 列。

## 数据源核验表

> `核验日期` 在实际联网核验官方文档后填写；当前统一标记为 `待核验`。

### Bangumi（P0 主数据源）

| 项目 | 内容 |
| --- | --- |
| 已核验官方 API 文档链接 | API 文档站：<https://bangumi.github.io/api/>；OpenAPI 源（`api.bgm.tv` 公开 API，`openapi/v0.yaml`）：<https://github.com/bangumi/server>（raw: `https://raw.githubusercontent.com/bangumi/server/master/openapi/v0.yaml`）；文档仓库：<https://github.com/bangumi/api>；授权说明：<https://github.com/bangumi/api/blob/master/docs-raw/How-to-Auth.md>；User-Agent 要求：<https://github.com/bangumi/api/blob/master/docs-raw/user%20agent.md> |
| 核验日期 | 2026-06-06 |
| 授权方式 | **OAuth 2.0（authorization code grant）**。授权用 Base URL 与 API 不同：① 引导用户 `GET https://bgm.tv/oauth/authorize`（参数 `client_id`、`response_type=code`、可选 `redirect_uri`/`state`，`code` 有效期 60s）；② `POST https://bgm.tv/oauth/access_token`（`grant_type=authorization_code` + `client_id` + `client_secret` + `code` + `redirect_uri`）换取 Access Token，返回 `access_token`/`expires_in`(默认 604800s≈7天)/`token_type=Bearer`/`refresh_token`/`user_id`；③ 刷新用 `grant_type=refresh_token`；④ 调用 API 时在请求头携带 `Authorization: Bearer <access_token>`。**新版 `/v0/` API 不再允许用 query string 传递 token**。多数 `/v0/` 读取接口为 `OptionalHTTPBearer`（匿名可读公开数据，带 token 可读私有/更全数据）；`/v0/me`、写接口等为 `HTTPBearer`（必须授权）。本项目仅做读取，token 由用户在本机 `CredentialStore` 配置（RC.00，本文件不记录任何 token）。 |
| 实际字段 | API Base：`https://api.bgm.tv`。已核验关键接口（均含 `400` 校验错误 / `404` 未找到响应）：<br>• `GET /v0/subjects/{subject_id}` → `Subject`（缓存 300s）：已确认含 `id`、`name`、`name_cn`、`summary`、`images`、`eps`、`infobox`、评分相关（见下）等；条目评分以 `rating` 对象表示（`score` 评分、`total`/`count` 人数、`rank` 排名），公共标签为 `tags`（`name`/`count`），收藏数为 `collection` —— `rating`/`rating_count`/`rank` 亦作为搜索过滤维度被官方确认。<br>• `POST /v0/search/subjects`（**实验性 API，schema 可能变动**）：请求体 `{ keyword(必填), sort: match|heat|rank|score, filter: { type[], meta_tags[], tag[], air_date[], rating[], rating_count[], rank[], nsfw } }`，`limit`/`offset` 为 query，返回 `Paged_Subject`。<br>• `GET /v0/subjects/{subject_id}/persons` → `RelatedPerson[]`（人物）。<br>• `GET /v0/subjects/{subject_id}/characters` → `RelatedCharacter[]`（角色）。<br>• `GET /v0/subjects/{subject_id}/subjects` → `SubjectRelation[]`（关联作品，字段 `id`/`type`/`name`/`name_cn`/`images`/`relation`）。<br>• `GET /v0/subjects/{subject_id}/image?type={small\|grid\|large\|medium\|common}`（302 跳转图片，无图回默认图）。<br>• 用户收藏（个人评分/短评/进度/状态）：`GET /v0/users/{username}/collections`（query `subject_type`、`type`=收藏类型、`limit`/`offset`）→ `Paged_UserCollection`；`GET /v0/users/{username}/collections/{subject_id}` → `UserSubjectCollection`；当前用户用 `GET /v0/me`（`HTTPBearer`，返回 `username`/`nickname`/`id` 等）。<br>• 角色/人物含 `name`、`name_cn`/`subject_name_cn`、`images`、`relation`/`staff` 等字段。<br>说明：`getSubjectById` 响应体 `Subject` 在本次核验时其独立 schema 组件文件未能直接抓取（仅通过搜索过滤维度与关联接口间接确认核心字段）；`volumes`、`total_episodes`、`date`、`platform`、`collection` 细分计数等**完整字段清单在实现 DTO 前需复核** `openapi/v0.yaml` 的 `Subject` 定义。 |
| 失败处理 | 经现有 `AppError` 映射（`core/common/AppError.kt`）：网络超时/无网络（10s 内无返回）→ `AppError.Network`；`401`/`403`（授权失效）→ `AppError.Unauthorized`；`404` → `AppError.NotFound`；`429` → `AppError.RateLimited`；`5xx` 及未捕获异常 → `AppError.Server`；字段缺失/空结果 → `AppError.FieldMissing`（UI 显示「暂无数据」，不隐藏整块区域）。按降级链 Bangumi(P0)→ AniList(P1)→ Jikan → MAL_Official → VNDB 回退，并在 UI 标记当前数据来源；Bangumi 不可用时用 AniList/Jikan 英文资料兜底并允许用户手动修正标题（需求 3：AC7/AC8/AC9/AC11）。 |
| 速率限制 | 官方 OpenAPI（`v0.yaml`）与文档**未公开明确的数值速率限制**；强约束为 **User-Agent 要求**（见下）与部分接口缓存（如 `getSubjectById` 缓存 300s）。实现按通用礼貌节流并在接近上限时退避；**确切阈值需复核**最新官方说明，不臆造数值。 |
| User-Agent | 官方明确要求：非浏览器调用者必须设置**包含开发者个人 ID + 应用名称**的 User-Agent；需分发的应用（如 Android 客户端）须附**版本号**；开源项目应附**项目主页**。各类请求库的默认 UA 可能被禁用，且**禁止**使用 `Bangumi/1.0`、`database` 等模糊 UA。官方示例：`czy0729/Bangumi/6.4.0 (Android) (http://github.com/czy0729/Bangumi)`。本项目应配置形如 `<owner>/ACG-Compass/<versionName> (Android) (<repo-url>)` 的 UA（对应需求 3 AC2 / RC.01；具体值在 `core/network` UA 拦截器实现）。 |
| 数据迁移 | 本次为接口契约核验，无 Room schema 变更。后续若以本地缓存持久化 Bangumi 条目/收藏，需在「数据迁移说明（汇总）」登记版本与迁移策略；因 `/v0/search/subjects` 为实验性 API、`Subject` 完整字段待复核，DTO 应对未知/缺失字段保持向后兼容（可空 + 忽略未知键），避免 schema 变动导致解析失败。 |
| OAuth 登录实现（0.13.0） | 已落地**应用内 WebView 授权码登录 + 启动期自动续期 + 内置共享应用**（纯客户端、无后端中转）：`BangumiOAuthApi`（`POST bgm.tv/oauth/access_token`，form-urlencoded）/ `BangumiOAuthClient`（拼授权 URL + 换取/刷新；生效凭据 = 用户自填 ?: 内置默认）/ `BangumiOAuthScreen`（WebView 拦截回调 `acgcompass://oauth/bangumi/callback` 取 `code` 并校验 `state`）/ `BangumiTokenRefresher`（启动时剩余寿命<2天则用 `refresh_token` 续期）。**内置应用凭据**经 `local.properties`（gitignored）→ `BuildConfig.BANGUMI_CLIENT_ID/SECRET` → `BangumiModule` `@Named` 注入，所有最终用户共用、仅需点「登录」、无需注册；留空则回退用户自填。`access_token`/`refresh_token`/过期时刻存入加密 `CredentialStore`（`SecretBundle.refreshToken`/`tokenExpiresAt`，非 Room、非默认备份）。详见技术决策 TD-13.1。 |

### AniList（P1 辅助数据源）

| 项目 | 内容 |
| --- | --- |
| 已核验官方 API 文档链接 | 文档站：<https://docs.anilist.co/>；GraphQL 入门：<https://docs.anilist.co/guide/graphql/>；Media 查询示例：<https://docs.anilist.co/guide/graphql/queries/media>；鉴权：<https://docs.anilist.co/guide/auth/> 与认证请求 <https://docs.anilist.co/guide/auth/authenticated-requests>；速率限制：<https://docs.anilist.co/guide/rate-limiting>；GraphQL 错误：<https://docs.anilist.co/guide/graphql/errors>；文档源仓库：<https://github.com/AniList/ApiV2-GraphQL-Docs>。GraphQL endpoint：`https://graphql.anilist.co`（POST，body `{query, variables, operationName}`）。 |
| 核验日期 | 2026-06-08 |
| 授权方式 | **公共查询免鉴权**（官方明确：仅读取公开数据无需任何 token —— 见 auth 指南）。仅在需要读取私有数据或执行 mutation 时才需 OAuth2 access token，以 `Authorization: Bearer <token>` 头携带（token 为 JWT）。本项目仅做公开读取：默认匿名调用；若用户在本机 `CredentialStore` 配置了可选 AniList token，则由 `AuthInterceptor` + `SourceAuths.anilist`（`TokenHeaderAuth(ANILIST, Bearer)`）注入；无 token 时透传匿名查询。**本文件不记录任何 token**（RC.00）。 |
| 实际字段 | 单一 endpoint `POST https://graphql.anilist.co`。已核验 `Media` 查询（`Media(id: Int)` 按 ID、`Page { media(search: String!, type: MediaType, sort: [MediaSort]) }` 按标题搜索；搜索推荐 `sort: SEARCH_MATCH`）。本项目实际使用的 `Media` 字段：`id`(Int)、`type`(MediaType=ANIME/MANGA)、`format`(MediaFormat：TV/MOVIE/OVA/ONA/SPECIAL/MUSIC/MANGA/NOVEL/ONE_SHOT…)、`status`(MediaStatus：FINISHED/RELEASING/NOT_YET_RELEASED/CANCELLED/HIATUS)、`title{ romaji english native }`、`averageScore`(Int，**0–100 加权均分**)、`meanScore`(Int)、`popularity`(Int，列表用户数)、`favourites`(Int)、`trending`(Int)、`genres`([String])、`season`(MediaSeason：WINTER/SPRING/SUMMER/FALL)、`seasonYear`(Int)、`episodes`/`duration`/`chapters`/`volumes`(Int)、`startDate{ year month day }`(FuzzyDate)、`coverImage{ extraLarge large medium color }`、`rankings{ rank type(RATED/POPULAR) allTime context }`、`stats{ scoreDistribution{ score amount } }`（评分人数=各档 `amount` 之和，作为 `RatingEntry.voteCount` 的真实样本数；Staff/Reviews 为可选连接字段，按需再扩展）。映射：`averageScore`→`RatingEntry.score`（保留 100 分制，聚合时由 `AggregateRatingsUseCase.normalizeToTen` 归一）；`title.english ?: romaji ?: native`→`Work.titles.canonical`（中文兜底语义 RC.01 3.11：Bangumi 不可用时用 AniList 英文标题）；缺失字段一律 `null`/空集合，不伪造（RC.01 3.7）。 |
| 失败处理 | 经 `HttpErrorMapper` / `AppError`：HTTP 非 2xx 按状态码映射（401/403→`Unauthorized`、404（按 `type` 过滤却请求了另一类型的 ID，或 ID 不存在）→`NotFound`、429→`RateLimited`、5xx→`Server`）；GraphQL `200 + errors[]`（如 `{"status":429,"message":"Too Many Requests."}`）按 error 内 `status` 映射，无 status 时兜底 `Server`；`data.Media` 为 `null` / `Page.media` 为空数组 / 关键字段缺失→`FieldMissing`（UI「暂无数据」）；超时 / 无网络→`Network`。降级链 Bangumi(P0)→**AniList(P1)**→Jikan→MAL_Official→VNDB；AniList 作为 Bangumi 之后的 P1 源接入 `DataSourceOrchestrator`，Bangumi 不可用时用 AniList 英文资料兜底（需求 3 AC8/AC11）。 |
| 速率限制 | 官方文档：正常 **90 请求/分钟**；当前 API 处于降级状态、**临时限制为 30 请求/分钟**（以官方文档为准，可能恢复）。响应头返回 `X-RateLimit-Limit` / `X-RateLimit-Remaining`；超限返回 `429` + `Retry-After`(秒) + `X-RateLimit-Reset`(Unix 时间戳)。另有 burst limiter 防短时间高频。本项目按官方阈值配置令牌桶并在接近上限时退避（`RateLimitInterceptor`），不臆造数值。 |
| 数据迁移 | 本次为接口契约核验 + 客户端接入，无 Room schema 变更。DTO 对未知/缺失字段保持向后兼容（`NetworkJson`：`ignoreUnknownKeys` + `coerceInputValues`，全字段可空/默认值），AniList schema 演进不致解析崩溃。GraphQL 仍沿用 `TD-8.1` 的薄 OkHttp POST 调用器（`GraphQlClient`），未引入 Apollo codegen。 |

### Jikan（P1/P2 补充数据源，非官方 MAL API）

| 项目 | 内容 |
| --- | --- |
| 已核验官方 API 文档链接 | REST v4 文档站：<https://docs.api.jikan.moe/>；官网：<https://jikan.moe/>；REST 源码仓库：<https://github.com/jikan-me/jikan-rest>。API Base URL：`https://api.jikan.moe/v4/`。 |
| 核验日期 | 2026-06-07 |
| 授权方式 | **无需 key / 无鉴权**（Jikan 是抓取 MyAnimeList 的非官方只读 API，公开匿名访问）。本项目以 `SourceAuths.jikan`（`NoAuth`）恒透传，不注入任何凭据头。注意：Jikan 自身可能因上游 MyAnimeList.net 而被二次限流。 |
| 实际字段 | 已联网核验（`GET /v4/anime/1` 等真实响应）。<br>• `GET /v4/anime?q={keyword}&limit={n}&page={p}&sfw`（搜索）→ `{ pagination: { last_visible_page, has_next_page, current_page, items: { count, total, per_page } }, data: Anime[] }`。<br>• `GET /v4/anime/{id}` → `{ data: Anime }`。<br>• `GET /v4/anime/{id}/reviews?page={p}` → `{ pagination: { last_visible_page, has_next_page }, data: Review[] }`：`Review` 含 `mal_id`/`url`/`type`/`date`/`review`/`score`/`tags[]`/`is_spoiler`/`is_preliminary`/`episodes_watched`/`reactions{ overall,nice,love_it,funny,confusing,informative,well_written,creative }`/`user{ username,url,images }`。<br>• `GET /v4/anime/{id}/recommendations` → `{ data: Recommendation[] }`：`Recommendation` 含 `entry{ mal_id,url,title,images }`、`url`、`votes`。<br>**Anime 关键字段**（评分相关，对应 MAL）：`mal_id`、`url`、`images{ jpg{ image_url,small_image_url,large_image_url }, webp{...} }`、`title`/`title_english`/`title_japanese`/`title_synonyms[]`/`titles[]{ type,title }`、`type`（TV/Movie/OVA/…）、`source`、`episodes`、`status`、`airing`、`aired{ from,to,prop,string }`、`duration`、`rating`(分级)、**`score`**(0–10，缺失为 0)、**`scored_by`**(评分人数)、**`rank`**(排名)、**`popularity`**(人气排名)、`members`、`favorites`、`synopsis`、`background`、`season`、`year`、`genres[]`/`themes[]`/`demographics[]`/`studios[]`/`producers[]`/`licensors[]`(均 `{ mal_id,type,name,url }`)。<br>**JSON 约定（官方）**：标量缺失 → `null`；数组 / 对象缺失 → 空；**`score` 等评分缺失 → `0`**（故 mapper 在 `score<=0` 或 `scored_by<=0` 时按「暂无数据」处理，绝不以 0 分伪造评分）。 |
| 速率限制 | 官方文档明确：**每秒 3 次（3 req/s）**且**每分钟 60 次（60 req/min）**，每日不限。本项目用 `core/network/RateLimiter` 配置**双令牌桶/滑动窗口**（`perSecond(3)` + `perMinute(60)`），并在接近上限 80% 时主动错峰（RC.01 3.10）。 |
| 失败处理 | 经 `HttpErrorMapper` 映射：超时/无网络 → `AppError.Network`；`404` → `AppError.NotFound`；`429`（Jikan 或上游 MAL 限流）→ `AppError.RateLimited`；`5xx`/未捕获异常 → `AppError.Server`；空 `data`/字段缺失 → `AppError.FieldMissing`（UI 显示「暂无数据」）。降级链中 Jikan 位于 `Bangumi→AniList→Jikan→MAL→VNDB`，失败则回退至下一源。 |
| 数据迁移 | 本次为接口契约核验，无 Room schema 变更。DTO 对未知/缺失字段保持向后兼容（全部字段可空 + 默认值，配合 `NetworkJson` 的 `ignoreUnknownKeys`/`coerceInputValues`），避免上游 MAL 抓取结构变动导致解析失败。 |

### MAL 官方 API（MAL_Official，P2 用户数据源）

| 项目 | 内容 |
| --- | --- |
| 已核验官方 API 文档链接 | API v2 参考（beta）：<https://myanimelist.net/apiconfig/references/api/v2>；OAuth2 + PKCE 授权：<https://myanimelist.net/apiconfig/references/authorization>；客户端注册：<https://myanimelist.net/apiconfig>。API Base URL：`https://api.myanimelist.net/v2/`；授权 Base：`https://myanimelist.net/v1/oauth2/`。 |
| 核验日期 | 2026-06-07 |
| 授权方式 | **OAuth 2.0 Authorization Code Grant + PKCE**（官方明确，唯一支持的 grant）。流程：① 生成 `code_verifier`（43–128 字符）与 `code_challenge`——**官方目前仅支持 `plain` 方法**（`code_challenge == code_verifier`，`code_challenge_method=plain`，缺省即 plain）；② 引导用户 `GET https://myanimelist.net/v1/oauth2/authorize?response_type=code&client_id=<id>&state=<state>&redirect_uri=<uri>&code_challenge=<challenge>&code_challenge_method=plain`；③ 回调 `redirect_uri?code=<code>&state=<state>`，客户端**必须校验 state**；④ `POST https://myanimelist.net/v1/oauth2/token`（`application/x-www-form-urlencoded`）换 token：`grant_type=authorization_code` + `client_id` + `code` + `redirect_uri` + `code_verifier`（Scheme 2 另加 `client_secret`；Scheme 1 用 HTTP Basic 把 client_id:client_secret 放 `Authorization: Basic`）。返回 `{ token_type:"Bearer", expires_in(秒，Access Token 寿命 1 小时), access_token, refresh_token(寿命 1 个月) }`。刷新：`grant_type=refresh_token` + `refresh_token`（同样需客户端认证）。调用 API 带 `Authorization: Bearer <access_token>`。**无需用户登录的公开读取**可改用 `X-MAL-CLIENT-ID: <client_id>` 头（`client_auth` 方案）。本项目仅在用户于本机 `CredentialStore` 显式配置 Client ID（可选 Client Secret）后启用（RC.02 4.8）；本文件不记录任何 client id/secret/token（RC.00）。 |
| 实际字段 | API Base：`https://api.myanimelist.net/v2/`，列表统一 `{ data:[...], paging:{ previous?, next? } }`，日期为 ISO 8601（`date-time` / `date` 可为 `2017`/`2017-10`/`2017-10-23`）。**字段按需用 `fields` 参数请求**（默认不返回全部字段）。本项目核验/使用：<br>• `GET /v2/anime/{anime_id}?fields=id,title,main_picture,mean,rank,popularity,num_episodes,status,start_season,media_type`（亦支持 `alternative_titles`、`num_scoring_users`、`num_list_users`、`start_date`/`end_date`、`synopsis`、`genres`、`source`、`average_episode_duration`、`rating`、`nsfw`、`my_list_status`、`statistics` 等）。关键映射字段：`id`(int)、`title`(string)、`main_picture{ medium, large }`、`mean`(number，0–10 加权均分，**缺失时整字段不返回**)、`rank`(int)、`popularity`(int)、`num_episodes`(int)、`status`(`finished_airing`/`currently_airing`/`not_yet_aired`)、`media_type`(`tv`/`movie`/`ova`/`ona`/`special`/`music`/`unknown`)、`start_season{ year, season(winter/spring/summer/fall) }`。<br>• `GET /v2/anime?q=<kw>&limit=<=100&offset=&fields=`（搜索；`limit` 默认 100 上限 100）。<br>• `GET /v2/users/@me/animelist?fields=list_status&status=&sort=&limit=<=1000&offset=`（官方用户列表；列表项 `{ node:{ id,title,main_picture }, list_status:{ status,score,num_episodes_watched,is_rewatching,updated_at } }`，需 `write:users` 作用域的用户 token）。<br>• `GET /v2/users/@me?fields=anime_statistics`（当前用户信息，仅 `@me`）。<br>JSON 约定：**缺失字段一律不出现在响应中**（非 `null`），故 DTO 全字段可空 + `ignoreUnknownKeys`；`mean` 缺失即「暂无评分」，mapper 不以 0 伪造（Property 5）。 |
| 失败处理 | 经 `HttpErrorMapper` / `AppError`：`400`（参数错误）→ 视为请求缺陷按 `Server`/`FieldMissing` 兜底；`401`（`invalid_token`，Access Token 过期/无效，响应头 `WWW-Authenticate: Bearer error="invalid_token"`）→ `AppError.Unauthorized`（触发 refresh-token 重取，仍失败则提示重新授权）；`403`（DoS detected 等）→ `Unauthorized`/`Server`；`404`→`NotFound`；`429`→`RateLimited`；`5xx`/未捕获→`Server`；超时/无网络→`Network`；`mean`/`data` 缺失或空列表→`FieldMissing`（UI「暂无数据」）。**仅在用户显式配置 Client ID 后启用**（未配置时数据源直接以「未启用」短路，不发请求，RC.02 4.8）。降级链中 MAL_Official 位于 `Bangumi→AniList→Jikan→MAL_Official→VNDB` 的 P2 段，失败回退下一源（RC.01 3.8）。 |
| 速率限制 | 官方 API v2 文档**未公开明确的数值速率限制**；`403 Forbidden` 文档标注为「DoS detected etc.」，即异常高频会被判定为 DoS 而封禁。本项目按通用礼貌节流并在接近上限时退避（`RateLimitInterceptor`），不臆造具体阈值；确切限制以官方最新说明为准。 |
| 数据迁移 | 本次为接口契约核验 + OAuth/PKCE 脚手架 + REST 客户端接入，无 Room schema 变更。DTO 对未知/缺失字段保持向后兼容（全字段可空/默认值 + `NetworkJson` 的 `ignoreUnknownKeys`/`coerceInputValues`），适配 beta 版（版本前缀 `0.`）可能的字段增补；MAL 标注向后兼容变更包含「新增字段/端点/可选参数」，DTO 不因此解析失败。 |

### VNDB（P2 数据源）

| 项目 | 内容 |
| --- | --- |
| 已核验官方 API 文档链接 | API v2「Kana」文档站（HTTPS API）：<https://api.vndb.org/kana>；沙盒环境：<https://beta.vndb.org/api/kana>；schema 自省：<https://api.vndb.org/kana/schema>；数据授权：<https://vndb.org/d17#4>。API endpoint：`https://api.vndb.org/kana`（本项目 Base URL 取 `https://api.vndb.org/kana/`）。 |
| 核验日期 | 2026-06-07 |
| 授权方式 | **多数端点免鉴权**（查询类 `POST /vn` 等公开可读）。可选 **Token 鉴权**：用户在「My Profile → Applications」标签（`https://vndb.org/u/tokens`）生成 token（形如 `xxxx-xxxxx-…`，破折号可选），调用时以 **HTTP 头 `Authorization: Token <token>`** 携带；token 无效返回 `401`，`GET /authinfo` 可校验。仅列表管理（`POST /ulist` 写操作等）需 `listread`/`listwrite` 权限。本项目仅做公开 VN 查询：默认匿名；若用户在本机 `CredentialStore` 配置了可选 VNDB token，则由 `AuthInterceptor` + `SourceAuths.vndb`（`TokenHeaderAuth(VNDB, scheme="Token")`）注入，无 token 时透传匿名查询。**本文件不记录任何 token**（RC.00）。 |
| 实际字段 | 单一端点 `POST {base}/vn`（`Content-Type: application/json`）。**请求体**（成员均可选，默认见文档）：`filters`（三元谓词数组，如 `["id","=","v17"]`、`["search","=","关键字"]`，可用 `and`/`or` 组合）、`fields`（逗号分隔字符串，支持 `image.url` 点号与 `image{url,sexual}` 花括号嵌套；顶层 `id` 恒返回）、`sort`（`id`/`title`/`released`/`rating`/`votecount`/`searchrank`）、`reverse`、`results`（每页 ≤100）、`page`（从 1 起）、`count`。**响应体**：`{ results: [...], more: Bool, count?: Int }`。本项目请求的 **VN 字段**：`id`（vndbid，形如 `"v17"`）、`title`（主标题，通常罗马音）、`alttitle`（可空，原文标题）、`titles[]{lang,title,latin,official,main}`、`aliases[]`、`olang`、`released`（`"YYYY-MM-DD"`/`"YYYY-MM"`/`"YYYY"`/`"TBA"`，可空）、`length`（1–5 粗略时长，可空）、`length_minutes`（用户实测均值分钟，可空）、`description`（可空，含格式码）、`image{url,sexual,violence,votecount,dims,thumbnail}`（`image` 可空；`image.sexual` 为 **0–2 的成人内容评分均值**，0 安全/1 暗示/2 露骨）、**`rating`**（**贝叶斯均分，整数 10–100，无人投票为 `null`**，缓存）、**`votecount`**（投票数，整数，缓存）、`average`（原始均分 10–100，可空）。映射：`rating`→`RatingEntry.score`（**保留 10–100 源标度**，聚合时由 `AggregateRatingsUseCase.normalizeToTen` 归一），`votecount`→`RatingEntry.voteCount`；`title`/`alttitle`/`aliases`→`Work.titles`；`released` 解析年份→`Work.year`；`length_minutes`→`Work.units.estPlayMinutes`；`mediaType=VN`。`rating==null` 或 `votecount<=0` 视为「暂无数据」，**不以 0 伪造评分**（RC.07 9.2 / Property 5）。 |
| 失败处理 | 经 `HttpErrorMapper`/`AppError`：超时/无网络 → `AppError.Network`；`400`（请求体/查询无效）→ `AppError.Server`（按非 2xx 通用映射）；`401`（token 无效）→ `AppError.Unauthorized`；`404`（路径/方法错误）→ `AppError.NotFound`；`429`（限流）→ `AppError.RateLimited`；`500`/`502`（服务端错误/宕机）→ `AppError.Server`；`results` 为空 / `rating` 缺失 → `AppError.FieldMissing`（UI「暂无数据」）。**成人内容分级过滤（RC.02 4.9/4.10）**：依据 `image.sexual`，当 `sexual >= 1.0` 视为成人内容；除非 `SettingsDataStore.showAdultContent` 为 `true`，否则从结果中过滤/隐藏。降级链 `Bangumi→AniList→Jikan→MAL→VNDB`，VNDB 为末位 P2 源。 |
| 速率限制 | 官方明确：**5 分钟内最多 200 次请求**，且**每分钟最多 1 秒执行时长**；单次请求超过 **3 秒**将被中断；超限返回 `429`。本项目按通用礼貌节流（`RateLimiter`，接近上限主动错峰，RC.01 3.10），并控制 `fields`/`results` 规模以规避「Too much data selected」与执行时长上限；确切阈值以最新文档为准，不臆造。 |
| 数据迁移 | 本次为接口契约核验 + 客户端接入，无 Room schema 变更。DTO 对未知/缺失字段保持向后兼容（`NetworkJson`：`ignoreUnknownKeys` + `coerceInputValues`，全字段可空/默认值），VNDB schema 演进（见官方 Change Log）不致解析崩溃。 |

## AI Provider 核验表

> AI Provider 的 key 由用户在本机配置，**不内置任何 key**（RC.14.01 / RC.00）。下表仅记录接口契约核验，不记录任何凭据。

### OpenAI

| 项目 | 内容 |
| --- | --- |
| 已核验官方 API 文档链接 | 待核验 |
| 核验日期 | 待核验 |
| 授权方式 | 待核验（用户自填 API key + Base URL + 模型名） |
| 实际字段 | 待核验（chat/completions 请求与结构化输出字段） |
| 失败处理 | 待核验（缺字段→修复二次请求；JSON 损坏/超时→低置信兜底；不编造） |
| 数据迁移 | 待核验 |

### Gemini

| 项目 | 内容 |
| --- | --- |
| 已核验官方 API 文档链接 | 待核验 |
| 核验日期 | 待核验 |
| 授权方式 | 待核验（用户自填 API key + Base URL + 模型名） |
| 实际字段 | 待核验 |
| 失败处理 | 待核验（同上修复/兜底链路） |
| 数据迁移 | 待核验 |

### DeepSeek

| 项目 | 内容 |
| --- | --- |
| 已核验官方 API 文档链接 | 待核验 |
| 核验日期 | 待核验 |
| 授权方式 | 待核验（用户自填 API key + Base URL + 模型名） |
| 实际字段 | 待核验 |
| 失败处理 | 待核验（同上修复/兜底链路） |
| 数据迁移 | 待核验 |

### OpenRouter

| 项目 | 内容 |
| --- | --- |
| 已核验官方 API 文档链接 | 待核验 |
| 核验日期 | 待核验 |
| 授权方式 | 待核验（用户自填 API key + Base URL + 模型名） |
| 实际字段 | 待核验 |
| 失败处理 | 待核验（同上修复/兜底链路） |
| 数据迁移 | 待核验 |

### 自定义 OpenAI 兼容

| 项目 | 内容 |
| --- | --- |
| 已核验官方 API 文档链接 | 待核验（由用户提供） |
| 核验日期 | 待核验 |
| 授权方式 | 待核验（用户自填 API key + Base URL + 模型名） |
| 实际字段 | 待核验（OpenAI 兼容 schema） |
| 失败处理 | 待核验（同上修复/兜底链路） |
| 数据迁移 | 待核验 |

## 数据迁移说明（汇总）

> 记录 Room schema 版本、迁移策略与升级兼容（RC.00 / RC.16.03）。

| 版本 | 日期 | 变更说明 | 迁移策略 | 失败回滚 |
| --- | --- | --- | --- | --- |
| v1 | 待填写 | 初始 schema（exportSchema=true） | 禁用 fallbackToDestructiveMigration；升级前自动 JSON 备份 | 迁移失败回滚并保留原始备份 |
| v2 | 待填写 | 新增 `user_collections` 表（R45，用户个人收藏：状态/评分/进度/短评/标签） | `MIGRATION_1_2`：仅 `CREATE TABLE` + 两索引，不触碰既有表 | 同上（非破坏，旧表数据不动） |
| v3 | 待填写 | `works` 新增可空 `summary` 列（F7，作品简介） | `MIGRATION_2_3`：仅 `ALTER TABLE ADD COLUMN`（TEXT，可空） | 同上 |
| v4 | 待填写 | `works` 新增可空 `airDate` 列（I16，开播/发行日期） | `MIGRATION_3_4`：仅 `ALTER TABLE ADD COLUMN`（TEXT，可空） | 同上 |
| v5 | 2026-06 | 新增 `ranking_cache` 表（B-4，榜单结果本地缓存：范围键+排名次序+作品 id+缓存时间），由 DataStore Preferences 迁移而来 | `MIGRATION_4_5`：仅 `CREATE TABLE`（复合主键 `scopeKey,position`），DDL 与 Room 期望 schema 一致 | 同上（非破坏，仅派生缓存） |
| v6 | 2026-06 | `backlog_items` 新增可空 `prevStatus` 列（吃灰馆移出/还原保留原状态） | `MIGRATION_5_6`：仅 `ALTER TABLE ADD COLUMN`（TEXT，可空） | 同上 |
| v7 | 2026-06 | 新增 `work_features`（12 维特征缓存：社区标签计数 + staff/角色/CV + 社区分/票数 + 集数/时长/平台）与 `recommendation_exposure`（推荐曝光记录，支撑重复推荐冷却）两表（最终版算法） | `MIGRATION_6_7`：仅 `CREATE TABLE`，DDL 与 Entity 期望 schema 一致 | 同上（非破坏，纯新增缓存/记录表） |
| v8 | 2026-06 | `works` 新增可空 `titleCn` 列（中文标题，Bangumi `name_cn`，D2 卡片/榜单/列表中文优先展示） | `MIGRATION_7_8`：仅 `ALTER TABLE ADD COLUMN`（TEXT，可空） | 同上 |

## 技术决策记录

> 记录影响实现方式的关键技术取舍，便于后续可追溯与平滑替换。

### TD-13.1：Bangumi OAuth —「内置共享应用 + WebView 拦截回调 + 启动期续期」，不照搬 animeko 后端中转

- **背景（0.13.0 / RC.02 4.6）**：用户要求「像 animeko 那样一键 Bangumi 登录 + token 自动刷新」。核验 animeko 架构确认其登录依赖**自建后端**（`auth.myani.org` 等）作 OAuth 中转、由服务端保管 `client_secret` 并处理回调；ACG Compass 纯本地无后端，**无法照搬**。
- **已联网核验（`bangumi/api` How-to-Auth，2026-06）**：Bangumi 令牌端点 `POST bgm.tv/oauth/access_token`（换取 / `grant_type=refresh_token` 刷新）**强制 `client_secret`（必填）**、**不支持 PKCE**（无 `code_verifier`/`code_challenge`）。故「用户只需登录、无需注册」在无后端时只能靠**内置一个共享应用**。
- **决策**：① **内置共享应用凭据**——开发者在 `bgm.tv/dev/app` 注册**一次**，把 `client_id`/`client_secret` 放 `local.properties`（gitignored）→ `BuildConfig` → Hilt `@Named` 注入 `BangumiOAuthClient`；所有最终用户共用、仅需点「登录」。`requireClientId/Secret` 取「用户自填 ?: 内置默认」，故高级用户仍可自托管覆盖、缺省构建可零内置回退自填。② 回调用**应用内 WebView 拦截自定义 scheme**（`acgcompass://oauth/bangumi/callback`）而非系统 deep-link——自包含、无需 Manifest 注册。③ 自动续期放**启动期**（`BangumiTokenRefresher`，剩余<2天则刷新）而非 OkHttp `Authenticator`/`AuthInterceptor`——避免「base `OkHttpClient` → `AuthInterceptor` → `BangumiOAuthClient` → 同一 `Retrofit`」的 Hilt 依赖环；令牌端点复用共享 `Retrofit.Builder`、不打 Bangumi `@Tag`，故不被鉴权/反代改写拦截器处理（始终走官方 `bgm.tv`）。
- **RC.00 立场（扩展说明）**：本项目「零内置密钥」原指**绝不内置用户的/付费的凭据**（AI key、用户 token）。OAuth **应用自身**的 `client_secret` 标识「ACG Compass 这个应用」而非任何用户，RFC 6749 §2.1 / RFC 8252 亦明确 native client 无法真正保密 secret；折中为：内置值只放 gitignored 的 `local.properties`（不进源码、不进 git），APK 内存在但属 native OAuth 的已知权衡。**用户 token 仍各自登录获得、存各自本机加密存储**——此条不破坏。
- **平滑替换**：若日后自建中转服务端，可在 `BangumiOAuthClient` 增「服务端换取」实现并保留现有客户端直连作为降级。

### TD-8.1：AniList GraphQL 暂用薄 OkHttp POST 调用器，不应用 Apollo codegen 插件

- **背景（task 8.1 / RC.01）**：core/network 需要提供 AniList（`https://graphql.anilist.co`）的 GraphQL 客户端。
- **决策**：当前以一个基于共享 `OkHttpClient` + kotlinx.serialization 的轻量 `GraphQlClient`（POST `{query, variables, operationName}`）作为 AniList 客户端，**不应用** Apollo 的 Gradle codegen 插件。
- **理由**：
  1. Apollo codegen 插件较重，且需要预先核验并落地 AniList 的 schema 与 operations（RC.01 文档前置流程），属于 task 31.x 范畴；在此之前引入会拖慢构建并产生无法核验的假设代码。
  2. 薄调用器复用基础 `OkHttpClient`，task 8.2 的拦截器链（UA / Auth / RateLimit / Timeout）可自动作用于 GraphQL 调用，无需为 Apollo 单独适配。
  3. 保持「不臆造结构」（RC.01 3.5）：薄封装仅传输 `query/variables` 并原样返回 `data/errors`，由上层在核验后解析。
- **现状**：`apollo-runtime` 运行时与 `apollo` 插件别名已登记进 `gradle/libs.versions.toml`，但插件**未** apply、运行时**未**加入 `app/build.gradle.kts` 依赖。
- **后续（task 31.2）**：核验 AniList 官方文档与 schema 后，可平滑切换到 Apollo（应用插件 + 加入 runtime 依赖），并以相同的 DI 入口替换 `GraphQlClient` 的 provider，调用方无感。
- **相关文件**：`core/network/graphql/GraphQlClient.kt`、`core/network/NetworkConstants.kt`、`core/network/di/NetworkModule.kt`。

## 关联文档

- 需求与可追溯性：见 [`REQUIREMENTS.md`](./REQUIREMENTS.md)。
- 经验记录：见 [`EXPERIENCE.md`](./EXPERIENCE.md)。
- EARS 验收条件：见 `.kiro/specs/acg-compass/requirements.md`。

## 最终检查点状态（任务 37）

- 构建：`:app:compileDebugKotlin` 通过；Hilt 全图校验（`:app:compileDebugUnitTestKotlin` / `hiltJavaCompileDebug`）通过。
- 测试：`:app:testDebugUnitTest` 全绿（318 个单元 / 属性测试，0 失败）。属性测试覆盖凭据隔离、降级顺序、限流、标题归一化、评分聚合、导入解析、备份 round-trip、口味统计守恒等核心不变式（各 ≥ 100 次迭代）。
- 本次修复（详见 EXPERIENCE.md 1–6）：GraphQL `JsonNull` 归一化；测试调度器共享；待补池 `addAll` 去重项按项计数；导入被安利计数不去重；凭据隔离属性测试生成器收敛为真实凭据形态；`AiProviderSelector` 补 Hilt 绑定。
- 待办（环境依赖）：安装 Android SDK 后执行 `:app:assembleDebug` 产出可安装 APK，并运行 `:app:connectedDebugAndroidTest` 完成 UI/instrumentation 验证。

## 数据源 token 可选性、连接测试与文档链接（Runtime 第 3 轮校正）

### Token 可选性 / 公共搜索
- 公共搜索（无需任何凭据）：Bangumi、AniList、Jikan、VNDB。`WorkRepositoryImpl.search` 并行查询所有源并合并，单源失败不影响其它源。
- 需要凭据才能参与搜索：MyAnimeList（官方）——必须先保存 Client ID（`MalRemoteDataSource.isEnabled()` 门控），否则跳过、不发请求。
- Token 仅用于“个人数据”能力：读取个人列表 / 收藏同步 / 写入。未填 token 不应阻断公共搜索。
- 设置页状态文案：未配置但可公共搜索的源显示「公共搜索可用，个人同步未配置」；Jikan 显示「公共搜索可用，无需 API Key」。

### 连接测试（RealConnectionTester，feature/settings/SettingsModule.kt）
- 对每个源发起一次最小化公共搜索探针，`withTimeoutOrNull(10_000)`。
- 结果文案：成功→「公共搜索可用」(+个人同步是否就绪)；失败→「连接失败：<原因>」；超时→「连接超时（超过 10 秒）」；MAL 未配置→「需先填写并保存 Client ID」。
- 不伪造成功（RC.00）：探针真实命中网络。

### 官方文档链接打开策略（SettingsScreen.effectiveOpenDoc）
- 先 `Intent.ACTION_VIEW` + `FLAG_ACTIVITY_NEW_TASK`；失败回退 `LocalUriHandler.openUri`；再失败复制链接到剪贴板并 Toast 提示。
- 文档链接为公开 URL，绝不携带任何 key/token。
- MAL Client ID 获取说明：登录 MyAnimeList → 账号设置 → API → Create ID，复制 Client ID（设置页 MAL 卡片 infoNotes + 查看官方文档）。

### 时光机本地快照 / Bangumi 同步
- 本地快照无需登录：从待补池映射为 `CollectionState`，首次 `captureInitialSnapshot` 建基线（不产生变更），其后 `recordSyncDiff` 生成 change log。
- Bangumi 同步为凭据门控增强：当前实现验证连接（getMe + getUserCollections 计数）并提示冲突安全策略（云端→本地只读合并；本地→云端写入需显式确认以免覆盖）。全量收藏 subjectId→内部 workId 映射为后续增强项。

## 跨源搜索合并、发现池模型与 AI 测试（Runtime 第 4 轮）

### 跨源搜索合并（R20）
- 入口：`feature/discover/DiscoverContract.kt` 的 `mergeCrossSource(matches): List<DiscoverResultItem>`，在 `DiscoverViewModel.searchToState` 中替代逐条 `toResultItem`。
- 聚类：按置信度降序贪心，`sameWork(a,b)` = 同 id，或「任一标题变体相似度 ≥ MERGE_THRESHOLD(0.82) 且年份不冲突」。`maxTitleSimilarity` 比较两作品的 `canonical/ja/romaji/en/aliases` 笛卡尔积，借各源普遍填充的日文名实现跨语言匹配。
- 展示：代表项优先取含中文名的 Bangumi 成员；卡片 `sourceTags` = 各来源标签 + 最佳置信度；多源合并视为高置信。`mergeCandidates` 保留各源单项供「手动调整/纠正」，纠正经 `overrideMatch` 持久化。

### 发现池模型（R22，对第 3 轮 R15 的修正）
- 发现页榜单/评分差异/筛选的数据源由「待补池交集」放开为 `WorkRepository.observeWorks()`（已缓存多源作品 = 公共发现池）。搜索/浏览命中即写入 Room，故有内容。
- 个人筛选范围在筛选 UI 文案标注。实时公共 trending 端点（AniList trending / Jikan top / Bangumi 热门）尚未接入，为后续增强。

### AI 连接测试（R28）
- `RealConnectionTester` 注入 `AiProviderRegistry` + `AiProviderSelector`；AI_PROVIDER 测试发起一次最小 `complete(AiRequest(model="", maxTokens=1))`，`withTimeoutOrNull(10s)`。
- 结果：未配置 / 连接成功模型可用 / 连接失败（携带 AppError.cause）/ 超时。

### 数据源公共 vs 个人能力（R36）
- Bangumi：公共搜索可用（匿名）；个人同步（收藏/评分/进度/短评、写入）需 Access Token/OAuth。
- AniList：公共搜索可用；个人列表需 Token。
- Jikan：公共可用、无 key；无官方个人写入。
- MAL 官方：搜索/个人能力取决于 Client ID + OAuth（设置页含 Client ID 获取说明）。
- VNDB：公共搜索可用、无需 token；个人列表需 token（设置页注明 token 可选）。
- AI 服务：需 API Key + Base URL + 模型名，且可连接测试。
- 设置 UI 区分「公共搜索未配置」与「个人同步未配置」，不混为一谈。

### 已知未落地（需后续排期）
- Bangumi 用户个人数据同步入库：Room 暂无「用户收藏/观看状态」表；我的页 watched/watching、详情页「我的记录」、口味画像导入、时光机云端导入均依赖此基础设施（新增 Entity+DAO+迁移 + BangumiSyncManager 分页导入）。属带 schema 迁移的较大改动，单独排期。
- 设置二级详情页（R31）、首页可选模块偏好（R32）、实时 trending 端点（R33）为后续增强。

## Bangumi 用户收藏同步与本地存储（Runtime 第 5 轮，R45）

### Room 数据层
- 新表 `user_collections`（`data/local/entity/UserCollectionEntity.kt`）：
  - `id`(PK, `BANGUMI:<subjectId>`) / `source` / `sourceItemId` / `localWorkId` / `status` / `rating`(Int?) / `progress`(Int?) / `comment` / `tags`(List<String>) / `updatedAt` / `syncedAt` / `sourceUpdatedAt`。
  - 索引：`index_user_collections_localWorkId`、`index_user_collections_source`。
- DAO：`data/local/dao/UserCollectionDao.kt`（observeAll/getAll/observeByWork/getByWork/count/upsert/upsertAll/clearBySource）。
- 数据库：`AcgCompassDatabase` version 1→2，新增 entity + `userCollectionDao()`；`DatabaseModule` 提供 DAO。
- 迁移：`AcgMigrations.MIGRATION_1_2` 仅 `CREATE TABLE IF NOT EXISTS` + 两个索引，DDL 与 Room 期望 schema 完全一致；**非破坏**，不 `fallbackToDestructiveMigration`，旧表/数据不动。

### BangumiSyncManager（`data/sync/BangumiSyncManager.kt`）
- `isConfigured()`：CredentialStore 中 Bangumi 是否已配置。
- `syncCollections(): AppResult<SyncReport>`：
  1. 未配置 → Failure(Unauthorized)。
  2. `getMe()` 取 username（失败原样返回 AppError.cause）。
  3. 分页 `getUserCollections(username, limit=50, offset)`，上限 40 页；首页失败→整体失败，后续页失败→计 failed 并停止。
  4. 每条：`subject.toWork()` upsert Work（保留 createdAt）；映射 `UserCollectionEntity`（type→状态：1想看/2看过/3在看/4搁置/5抛弃；rate 1–10；ep_status；comment；tags；updated_at）。
  5. 批量 upsert；返回 `SyncReport(added/updated/skipped/failed/total)`。
- Bangumi work id == subjectId（见 BangumiMappers），故同步与搜索缓存共用同一 work id，避免重复作品。

### 消费方
- 我的页统计（R48）：`MineViewModel` combine `userCollectionDao.observeAll()`；`buildMineStats(..., collections)` 已同步时按收藏算看过/在看/想看/搁置/抛弃 + 平均分/最高分 + 常见标签；未同步回退待补池。入口「从 Bangumi 同步我的数据」。
- 详情页我的记录（R44）：`DetailViewModel` observeByWork→CollectionState，喂入既有 `buildDetailUiState(collectionState=...)`/`PersonalUiModel`。
- 口味画像（R47）：`TasteProfileViewModel.onImportFromBangumi` → syncCollections → 映射 `TasteInputRecord(rating/tags/reviewText/status)` → `TasteProfileRepository.importAndCompute` 刷新画像。
- 时光机（R46）：`TimeMachineViewModel.onSyncFromBangumi` → syncCollections → 读库映射 CollectionState → captureInitialSnapshot/recordSyncDiff。

### 公共 vs 个人能力边界（重申）
- 公共搜索（匿名可用）与个人同步（需 token）分离；token 失效不影响公共搜索。
- 待办（后续排期）：多源 work-id 归并以融合多平台评分（R42）；实时公共 trending 端点（R41/R52）；设置二级页（R49）；首页模块偏好（R51）。

## Bangumi API 地址可配置（Runtime 第 6 轮，R55–R60）

### 策略
- **官方 API 仍是默认**：`https://api.bgm.tv/`。
- 国内网络环境可能无法直连官方 API（连接测试会明确提示检查网络/代理或切换地址）。
- App 支持用户自定义 API Base URL（自建反代 / Cloudflare Worker / nginx）。
- 社区反代仅作为用户**主动选择**项，不默认启用、不保证稳定性。

### 候选反代核验结论（2026-06-08 联网核验）
- `bgmapi.anibt.net`：✅ 已核验。`GET https://bgmapi.anibt.net/v0/subjects/1` 返回完整 Bangumi 格式 JSON（图片改写为 `bgmimg.anibt.net`）。**已内置为候选**。
- `api.bangumi.one`：✅ 已核验。`GET https://api.bangumi.one/v0/subjects/1` 返回完整 Bangumi 格式 JSON（图片改写为 `lain.bangumi.one`）。**已内置为候选**。
- 二者均仅作为「社区反代」用户主动选择项，带风险提示；默认不启用。
- 图片反代（bgmimg.anibt.net / lain.bangumi.one）当前不单独做图片 Base URL，仅记录；反代返回的图片 URL 已是其自身域名。

### 实现
- 配置存储：`SettingsDataStore.bangumiApiBaseUrl`（默认官方，归一化补结尾斜杠）+ `bangumiNonOfficialTokenConsent`（非官方+Token 风险确认）。`SettingsState.isBangumiOfficialApi` 判定。
- 统一改写：`BangumiBaseUrlInterceptor`（core/network）对**标注为 Bangumi 源**的请求按配置改写 scheme/host/port，排在 `AuthInterceptor` 之后。因此搜索/详情/getMe/收藏同步/我的页统计/详情我的记录/口味画像/时光机/连接测试**全部统一**走同一配置地址（R58），无任何功能写死官方地址。
- **Token 隐私（R56）**：拦截器在「非官方地址且未确认风险」时移除 `Authorization` 头——个人 Token 绝不在未同意时发往第三方；公共搜索仍可匿名工作。`BangumiSyncManager` 个人同步在「非官方+未确认」时直接拒绝并提示去设置确认。Token 不写日志/错误/备份（沿用 RC.00）。
- 连接测试（R57）：Bangumi 测试分能力——当前地址类型 / 地址可访问+公共搜索 / Token 有效+用户信息 / 收藏可读；失败带可读原因（网络/401/403/超时/解析）；官方失败提示切换自定义或检查代理。
- UI（R59）：设置页 Bangumi 卡片下方「Bangumi API 地址」卡：官方按钮 + 社区反代候选按钮 + 自定义地址输入/保存 + 非官方时风险文案与 Token 同意开关。（完整摘要卡+独立二级页归入 R49，后续排期）

### 建议
- 优先使用官方 API + 自己的代理 / 自建反代；公益反代不在 App 内强推，仅作可选项与文档说明。

## 搜索跨源合并规则与 source link 融合（Runtime 第 6 轮续修，R64/R65）

- 通用算法位于 `domain/matching/CrossSourceMerge.kt`（纯函数，无样例硬编码）：
  - `Work.allTitleVariants()`：规范名 + 日文 + 罗马音 + 英文 + 别名。
  - `sameWork(a,b)`：同 id→是；任一归一化标题变体相等且年份/类型不冲突→是；原文相等但年份或类型冲突→否；否则多变体最大相似度（`similarity`，已含 NFKC 全半角/大小写/去标点/子串包含）≥ `MERGE_THRESHOLD(0.82)`→是。
  - `clusterMatches` 贪心聚类；`representativeOf` 选代表（优先 Bangumi 中文名，否则最高置信度）。
  - 热度/评分人数只用于排序（见 WorkRepositoryImpl 搜索排序），不参与是否合并。
- 持久化融合：`WorkRepositoryImpl.persistMatches` 对每个簇，把所有成员的 source link（sourceId, sourceItemId=各源 work id）统一 upsert 到「代表 work id」；`refreshRatingsAndWork(代表 id)` 遍历这些链接抓取各源评分写入 ratings 表 → `aggregateRatings` 在详情页汇总多平台评分。UI（DiscoverContract.mergeCrossSource）复用同一 `clusterMatches/representativeOf`，卡片 workId 与持久化代表 id 一致。
- 避免误合并：年份/类型冲突作为硬约束；系列总称与具体分作/续作/新章因 subtitle 不同→标题变体不相等且相似度受限→不自动合并。

## 页面 inset / 滚动（R61）
- 单一来源：外层 `AcgApp` Scaffold `contentWindowInsets=WindowInsets(0)`；各页 TopAppBar 用默认 windowInsets 消费状态栏；列表用足够底部 contentPadding 清开底栏。避免双 Scaffold 双重/缺失 inset。

## 时光机基线视图（R62）
- `TimeMachineViewModel` uiState = combine(changeLogs, works, snapshots, userCollections)；首次导入仅有初始快照（无 change log）时显示「基线快照（N 个作品）」，不再误判空态。

## Bangumi API 地址 UI（R67）
- 设置页用 `ExposedDropdownMenuBox` 单选（官方/bgmapi.anibt.net/api.bangumi.one/自定义）；选「自定义」才显示输入框。底层 `BangumiBaseUrlInterceptor`（统一改写 host）与 Token 隐私保护（非官方未同意时移除 Authorization）逻辑不变。

## 未完成（需后续，技术原因）
- 发现页公共榜单/季度/trending（R69）、详情页角色/关联作品/路线区块（R70）、设置二级页（R71）、首页模块偏好（R72）：均需新增公共数据源端点/DTO/mapper 或导航重构，单独排期。

## 第 7 轮：搜索合并通用规则、评分量表、页面 inset、详情数据源覆盖

### 搜索合并通用规则（R75，domain/matching/CrossSourceMerge）
- 信号：归一化标题变体（含日文原名）相等 / 较长核心被完整包含（≥10 归一化字符）/ 多变体最大相似度 ≥0.82。
- 硬约束：年份冲突或类型冲突 → 不自动合并（区分系列总称 / 续作 / 新章 / 外传 / 不同媒介）。
- 过短前缀（如「9 nine」<10）不触发包含合并，防止系列总称误并进具体分作。
- 热度 / 评分人数仅用于排序，不参与合并。无样例硬编码；9-nine / 2.5 仅作回归。

### 多平台评分量表（R77）
- Bangumi/Jikan/MAL：x/10；AniList/VNDB：x/100。详情页原始评分带分母展示，不混淆量表。内部归一化（normalizeScore）仅用于综合/排序。

### 页面 inset（R74）
- 外层 Scaffold contentWindowInsets=0；有 TopAppBar 的页由其默认 windowInsets 消费状态栏；无标题栏页（DetailScreen）用 statusBarsPadding()。

### 详情页数据源覆盖现状
- 已接入：多平台评分 + 评分人数（aggregateRatings，跨源 source links 融合）、我的记录（Bangumi 同步收藏）、口味匹配度（口味画像存在时）。
- 待接入（R90，需各源额外端点）：简介（部分源 getSubject 已带 summary，可进一步用于详情简介区）、角色/Staff（Bangumi getSubjectCharacters/Persons、AniList characters/staff、Jikan characters/staff、VNDB）、关联作品（Bangumi getSubjectRelations、AniList relations）、观看路线（由关联作品派生）。这些端点部分已存在于 RemoteDataSource（如 BangumiRemoteDataSource.getSubjectCharacters/Persons/Relations）但尚未接入详情 UI，单独排期。

### 公共发现数据源（R84，未接入）
- 计划：AniList trending/season（GraphQL）、Jikan top/season（REST）；写入公共发现池供发现页榜单/评分差异/筛选与首页热门共用。无 token 可用。单独排期。


## Reference Review（开源项目借鉴结论 — 仅借鉴架构/交互/算法/信息组织，不复制代码）

> 核验日期：2026-06-09。以下为对参考项目的「结论性借鉴」，落地到本项目的现有分层（domain/matching、data/repository、feature/*）。未复制任何源代码。

| 参考项目 | 借鉴点 | 本项目落地 |
|---|---|---|
| xiaoyvyv/bangumi（原生客户端） | 条目详情信息分区、收藏/进度/用户记录展示、搜索结果呈现 | 详情页固定分区（简介/角色·Staff/关联/观看路线/平台数据/我的记录），缺失「暂无数据」但结构在（F7） |
| czy0729/Bangumi | Timeline / Time Machine 信息组织、排行、每日放送、标签 | 时光机概览卡 + 基线快照按状态分组 + 变化时间线（F3）；标签来源用作品社区标签（F9） |
| open-ani/animeko | 数据源/缓存/Repository·UseCase·UI 分层、同步 | 维持单一可信源=Room、Repository 持久化后 UI 从 Flow 读取；同步状态集中（SyncStatusRepository，R100） |
| Anikku / Aniyomi | 多 tracker 同步、库管理、状态筛选、推荐入口 | 我的库按状态分组 + 待补池/吃灰区/已看分离（F2/F4） |
| AnimeMate / Sprout / MyAnimeReport | 用户状态、评分历史、标签偏好、推荐解释、Backlog、KNN/Slope One | 本地「可能会喜欢」用标签相似度 + 高/低分标签加权 + 社区评分兜底；补番优先级可解释加权（F8） |
| MZZB Score / anime-rating-db | 多站评分聚合、跨站 ID 映射、评分人数/热度标准化 | 跨源 canonical work + source links；评分按量纲归一到 10 分制；共识用人数/来源数/方差独立建模（F5/F8） |

### 落地算法摘要
- 搜索合并（F5）：标题 NFKC+小写+去标点归一 + 紧凑去空格精确匹配（修 9nine/9-nine 漏合并）+ 核心标题包含 + 相似度阈值；原文一致且年份/类型不冲突自动合并；系列总称（仅前缀相同、核心不同）不并；同簇 source links 指向代表 work，评分挂同一 canonical（详情多平台评分）。
- 搜索排序（F5）：精确匹配 > 合并来源数 > 匹配置信度 > 来源可信度。
- 社区共识（F8）：稳定度 = 一致性(1-σ/3)·0.4 + 评分人数(对数)·0.35 + 来源数·0.25；争议度 = 极差/5·0.6 + σ/3·0.4（两者独立，不互补到 1）；样本<2 不下结论。
- 补番优先级（F8）：口味匹配·0.4 + 社区优先级·0.4 + 补完成本·0.2，情绪风险减 0.1 → 低/中/高 + 理由。
- 观看路线（H）：按 Bangumi relation 文本本地分 主线（前传/续集/正篇）/可选（番外/剧场版/OVA）/可跳过（总集篇/PV/角色歌）；仅对已知关联作品排序，不编造；支持一键加入主线/全系列到待补池。
- 公共发现池（F10）：Jikan `top/anime` + `seasons/now`（无需 token）；写入作品 + JIKAN 评分供榜单/评分差异/筛选；失败显示错误+重试。AniList trending 为后续增强（需 Apollo 代码生成）。


## Reference Review — 证据与核验声明（订正，2026-06-09）

> **诚实声明**：上一节《Reference Review》的结论**不是**基于实际打开/读取这些 GitHub 仓库得出的，而是**基于用户提供的参考项目列表 + 本项目现有需求做的设计方向归纳**。本工具链本轮**未实际联网克隆/浏览**下列仓库源码，因此不应被当作「已核验」证据。以下证据表如实标注每个项目的核验状态；凡未实际访问的，一律标「未实际核验」。

| 项目 | 是否实际联网读取 | 仓库 URL（参考方向，未必已访问） | 阅读的关键文件/目录 | 借鉴点（设计方向） | 本项目落地位置 | 不采用/风险说明 |
|---|---|---|---|---|---|---|
| xiaoyvyv/bangumi | 否（未实际核验） | github.com/xiaoyvyv/bangumi | 无（未访问） | 条目详情信息分区、用户记录展示 | `feature/detail/*`（F7/H） | 未读源码，仅方向参考；许可证未核验，未复制任何代码 |
| czy0729/Bangumi | 否（未实际核验） | github.com/czy0729/Bangumi | 无（未访问） | Timeline/Time Machine 信息组织、排行、标签 | `feature/timemachine/*`（F3）、`feature/discover/*` | 同上；该项目为 RN，技术栈不同，仅借鉴信息架构 |
| open-ani/animeko | 否（未实际核验） | github.com/open-ani/animeko | 无（未访问） | 数据源/缓存/分层、同步 | `data/repository/*`、`data/sync/*` | 同上；仅借鉴分层思路 |
| Anikku / Aniyomi | 否（未实际核验） | github.com/aniyomiorg/aniyomi | 无（未访问） | 库管理、状态筛选、tracker 同步 | `feature/library/*`、`feature/backlog/*`（F2/F4） | 同上；GPL 系许可证，绝不复制代码，仅参考交互组织 |
| AnimeMate / Sprout / MyAnimeReport | 否（未实际核验） | （用户提供，URL 未确认） | 无（未访问） | 评分历史、标签偏好、推荐解释、KNN/Slope One | `feature/taste/*`、`feature/detail` 推荐区（F8/F9） | 仅作算法方向；本项目用自研规则模型，未引入其代码/模型 |
| MZZB Score / anime-rating-db | 否（未实际核验） | （用户提供，URL 未确认） | 无（未访问） | 多站评分聚合、跨站 ID 映射、标准化 | `domain/usecase/AggregateRatingsUseCase`、`domain/matching/*`（F5/F8） | 仅作方向；评分归一与共识为自研 |

> 结论：前一节《Reference Review》请视为**设计方向归纳**而非已核验证据。若后续需要真正的源码级借鉴核验，应在受控环境实际打开上述仓库、记录提交哈希与具体文件路径后再更新本表。本项目所有实现均为原创，未复制任何上述项目的源代码。


## G 轮新增（Bangumi 写回 / 榜单 / 算法，2026-06）

### Bangumi 用户收藏写接口（编辑「我的记录」回传）
- 端点（I5/I7 修正）：**修改已存在收藏用 `PATCH /v0/users/-/collections/{subject_id}`（全字段可选，返回 204）；新建收藏用 `POST`（须带 `type`，返回 202）**。需 Bearer Token。body 字段：`type`(1想看/2看过/3在看/4搁置/5抛弃)、`rate`(0–10，0=清除)、`ep_status`、`vol_status`、`comment`(""=清除)、`tags`、`private`。
- 实现：`BangumiUpdateCollectionRequest` + `BangumiApi.patchUserCollection`/`createUserCollection` + `BangumiRemoteDataSource.updateUserCollection`（先 PATCH，404 再 POST 新建）。
- **历史 bug（I5/I7）**：此前一律 POST，对已收藏条目失败 → UI 一直提示「同步失败：请稍后再试」。改为 PATCH-first/POST-fallback 后修复。参考 animeko `datasource/bangumi/v0.yaml`：`patchUserCollection`「PATCH 方法的所有请求体字段均可选」。
- 策略：本地先写 `user_collections`（即时反映 UI），再回传 Bangumi；未配置 Token / 非 Bangumi 条目仅本地保存并提示；失败不回滚本地，提示同步失败原因。
- 参考核验（已读本地源码）：xiaoyvyv/bangumi 走网页表单 `/subject/{id}/interest/update`（FieldMap interest/rating/comment/tags/privacy）与 `/subject/ep/{epId}/status/{type}`；czy0729/Bangumi 走 v0 `PATCH .../episodes` + 收藏字段 type/rate/ep_status/comment/tags。本项目采用官方 v0 token 路径（与现有鉴权一致，不抓 cookie）。

### Bangumi 榜单 / 本季（公共发现池主源）
- 端点：`GET /calendar`（legacy 每日放送，免鉴权）。返回 7 个工作日分组，每组 `items[]` 为 legacy 条目（id/name/name_cn/images{large,common,...}/rating{score,total,rank}）。
- 实现：`BangumiCalendarDayDto`/`BangumiLegacySubjectDto` + `BangumiRemoteDataSource.getCalendar` + 映射 `toWork/toWorkMatch/toRatingEntry`。
- `WorkRepositoryImpl.loadPublicDiscovery` 改为 **Bangumi 优先**最先写入，Jikan top/season + AniList trending 作补充（用于多源评分聚合与评分差异）。

### 评分聚合 / 差异（参考 mzzbscore）
- 评分差异榜 `buildScoreDiffBoard`：按跨源归一化评分差距（spread）**降序**排列，差距大的排前面；差距 < 阈值(1.5/10) 不入榜；措辞中性。
- 已知限制：差异需同一规范作品上挂有 ≥2 源评分。搜索结果经跨源合并（CrossSourceMerge）后可融合；公共发现池条目（各源独立 work id）尚未自动按标题跨源合并，故池内差异榜可能偏空——后续接入「池内跨源合并 + 交叉验证（用对方日文/英文名重试）」补全。

### 口味匹配 / 个性化推荐（共享 PersonalTasteScorer，2026-06 重构）
- **共享打分引擎**：详情页「口味匹配度」与「今晚看什么」推荐统一走 `domain/usecase/PersonalTasteScorer`（纯函数），口径一致、便于单测（`PersonalTasteScorerTest`）。
- **加权标签重合（主导）**：作品标签 ∩ 口味画像「高/低分标签」，权重 = `ln(1+标注次数) × 题材/元数据因子`；命中高分累正权、低分累负权，软饱和 `posSum/(posSum+1.5)` 聚合 → 命中越多/越强分越高（总体吻合度，组合优于单标签）。
- **多维度标签分类（B-3）**：`domain/usecase/TasteTagTaxonomy` 把标签显式归入 `TagDimension`——题材 CONTENT(1.0) > 受众 DEMOGRAPHIC(0.7，少年向/少女向等「×向」) > 厂商 STUDIO(0.5) > 元数据 META(0.2，年份/季度/媒介格式/改编来源/放送状态/关联/地区)；`weightFactor` 取所属维度因子。池完整性已扩充噪声/厂商/受众词表与日期正则（季番/Q 季度/新番等变体）；`BangumiSyncManager` 构建画像时复用 `isMeta` 过滤（单一规则源，避免两处漂移）。
- **社区评分再弱化**：仅作轻量先验（详情页综合分权重 0.15→**0.08**，换算为「相对你均分」的习惯调整）；推荐排序用 `TasteScore.personal`（**完全不含**社区分），社区仅作 `mean10 ≥ 下限` 的质量护栏 + 轻量 nudge。
- **未选标签也个性化**：推荐器移除「必须选标签」前提——无所选标签时按个人口味分排序（而非只推社区高分热门）；选了标签仍硬筛（必命中≥1）。保留随机扰动与 top 段抽样的多样性。
- **低置信收缩 / 不伪造**：画像 `confidence < 0.3` 时综合分向中性 0.5 收缩并在理由标注；无画像→「未生成画像」、作品无标签且无社区分→「数据不足」，绝不编造。
- **无标签退化**：作品无可匹配标签但有社区分时，退化为「社区分相对你均分」的低置信估计（`Basis.COMMUNITY_FALLBACK`）。
- AI 匹配输入：纳入近期最多 20 部已评分作品（标题·当时评分·短评）+ 口味画像 + 社区评分；失败回退本地模型。AI 提示词已强化「个人口味优先、弱化社区分、近期评分权重更高」（A-4）。
- **时间建权（B-2）**：`TasteStatsCalculator` 以「最近一条记录」为基准对高/低分标签与常用短评词做近因加权（半衰期约 540 天 + 权重地板 0.3）——近期评分的标签排序更靠前；展示计数仍取**原始出现次数**（守恒不破，Property 13）；无 `updatedAt` 时退化为未加权（向后兼容）。
- **画像自动更新（B-1）**：详情页保存/清空评分或短评后触发 `TasteProfileRepository.recomputeFromLocal()`，从本地 `user_collections` 重算并覆盖式落库——无需重新导入即反映最新口味。
- **榜单缓存入 Room（B-4）**：发现页榜单顺序缓存由 DataStore Preferences 迁移到 Room `ranking_cache` 表（`RankingCacheDao`，复合主键 `scopeKey+position`，整范围事务覆盖写，带 `cachedAt`），冷启动秒开，与「单一可信源 = Room」对齐。


## M 轮 · 新核验 API（2026-06-11）

### Bangumi 条目短评（next.bgm.tv/p1）
- 端点：`GET https://next.bgm.tv/p1/subjects/{subjectId}/comments?limit=&offset=`
- 鉴权：公共数据，匿名可读（无需 Token）。
- 返回：`{ data: [ { user: { nickname }, rate, comment, updatedAt } ] }`（JSON，非网页抓取，符合 RC.01 3.11「不抓网页」）。
- 用途：详情「评论摘要」展示真实他人短评；并作为无剧透雷达 `publicReviews` 输入。
- 失败处理：非 2xx / 空 → 返回空列表（best-effort，不伪造，不崩溃）。
- 实现：`BangumiNextApi` + `BangumiModule.provideBangumiNextApi`（base `https://next.bgm.tv/p1/`，固定官方，不随反代设置改写）。

### Bangumi 动画章节进度上传（v0）
- 关键约束：collection PATCH（`/v0/users/-/collections/{id}`）的 `ep_status`/`vol_status`「只能用于修改书籍条目进度」，动画发送会 400「can't set ep_status on non-book subject」。
- 正确方式（动画）：
  1. `GET /v0/episodes?subject_id={id}&type=0` 取本篇章节（按 `ep`/`sort` 升序）。
  2. `PATCH /v0/users/-/collections/{id}/episodes`，body `{ episode_id: [前 N 个本篇章节 id], type: 2 }`（EpisodeCollectionType：1=想看/2=看过/3=抛弃），服务端会重算条目完成度。
- 书籍/漫画：本地不显示/不上传进度（编辑对话框对非动画隐藏进度输入）。
- 无词条作品：写回前 `getSubject` 确认存在，否则仅本地保存并提示「该作品在 Bangumi 无词条」。
- 实现：`BangumiApi.getEpisodes/patchEpisodeCollection` + `BangumiRemoteDataSource.markEpisodesWatched/subjectExists`。

## V2 任务清单核对进度（2026-06-21）

> 对照 `.kiro/specs/acg-compass/tasks-v2.md`（2026-06 重整清单）逐项回归代码核对的真实状态。
> 说明：tasks-v2.md 各任务仍标 ⬜ 为「重整初始态」，已过时；**以本表为准**。
> **2026-06-22 复核**：经真机验证 + 代码复核，P0-1（评分人数）、P2-2（榜单分页）、P2-5（推荐器标签）、P2-6（题材筛选）**名实不符**，已从「已完成」移入下方「⚠️ 待重修」区——其中 P0-1 已确诊到代码级。
> **2026-06-23 修复**：P0-1 已修复并上移「已完成」表：选代表/选条目的比较器统一为 `representativeScore`（相似度 + 评分人数对数加成，上限 0.18），覆盖 `representativeOf`/`backfillBangumi`/`crossValidateRatings`（含跨标题变体）/`search` 排序四处；同时修复上轮遗留的 `confidenceBucket` 编译错误，`compileDebugKotlin` 通过，并补回归测试。
> **2026-06-23 修复**：P2-2 榜单分页已修复并上移「已完成」表：根因为 `canLoadMore = 返回数 >= limit`，而 Bangumi 实验性搜索某页可能返回少于 limit 条→误判到底。改为由 `searchRankedSubjects` 透出分页 `total`，上层以 `offset + 已取数 < total` 判定，offset 按实际返回数推进（不跳号）并以「本页非空」防空页死循环。
> **2026-06-23 修复**：P2-5（推荐器/今晚看什么标签）、P2-6（题材筛选）同根因已修复并上移「已完成」表：`BangumiSubjectDto.toWork()` 本就映射社区标签，但 `persistMatches`/`BangumiSyncManager` 落库只写 `WorkEntity`（不含 tags），`observeWorks` 又以 `toDomain(tags=emptyList())` 读出 → 候选池/题材 facet 作品标签恒空。新增共享 `WorkTagWriter` 在两条入库路径写 `tags`+`work_tags`（统一主键规则避免唯一索引冲突），`observeWorks` 经新增 `TagDao.getTagsForWorks` 批量回填标签。**注意**：存量数据需重新同步/重进发现页一次以补写 `work_tags`。

### 已完成（代码核对通过，编译 + 打包均通过）

| 任务 | 说明 | 关键实现 |
| --- | --- | --- |
| P0-1 评分人数错配 | 选 Bangumi 动画条目的比较/排序统一为「综合得分 = 相似度 + 评分人数对数加成（上限 0.18）」：仅动画(type=2 过滤)候选中，正片在相似度仅略低于续作/小条目时凭人数胜出；加成有上限故高热低相似不误胜。覆盖 `representativeOf`/`backfillBangumi`/`crossValidateRatings`(含跨变体)/`search` 排序四处 | `CrossSourceMerge.representativeScore/representativeOf`、`WorkRepositoryImpl`（标注 P0-1）；回归测试 `CrossSourceMergeTest` |
| P0-2 口味画像 | 用作品 Bangumi 社区标签构建正/负向偏好 + 评分习惯 | `TasteProfile`、`TasteStatsCalculator`、`ScoringHabitCalculator` |
| P0-3 口味匹配度 | 详情页可解释匹配度；无画像/标签时低置信不伪造 | `DetailContract.toTasteMatch`、`TasteMatchUiModel.Unavailable` |
| P0-4 今晚看什么 | tag 硬筛 + 冲突排除 + 口味排序 + 高分段轻度打散 + 放宽提示 | `RecommenderViewModel.recommendFromWorks` |
| P0-5 AI 置信不足仍展示 | AI 失败/未配置回退本地规则雷达，始终可见 | `GenerateSpoilerRadarUseCase`、`LocalFallbackRadar` |
| P1-1 详情 Bangumi 优先 | 非 Bangumi 主源有 bgm 链接时用中文标题/简介覆盖外语 | `WorkRepositoryImpl`（标注 N15/P1-1） |
| P1-3 口味画像自动更新 | 收藏/状态变更后防抖自动重算画像 | `TasteProfileAutoUpdater` |
| P1-4 待补↔吃灰一致性 | 互斥归属移动语义 + 多选移动即时刷新 | `BacklogRepositoryImpl`（inDustMuseum）、`BulkOp.ARCHIVE/RESTORE_FROM_DUST_MUSEUM` |
| P2-1 封面缩放 + 拖动 | 双指缩放（1~5x）+ 放大后拖动平移，缩回 1 复位 | `CoverViewer`（detectTransformGestures + offset） |
| P2-2 榜单触底分页 | 由 Bangumi 分页 `total` 判定是否还有下一页（不再用「返回数 >= limit」）；offset 按实际返回数推进避免短返回跳号，本页空则停止防死循环 | `BangumiRankedPage`/`RankingPage`（含 total）、`searchRankedSubjects`/`loadBangumiRankingPage`、`DiscoverViewModel.onLoadMoreRanking` |
| P2-4 详情页原生 tag | 展示真实 Bangumi 社区标签，取代「适合心情」间接推断 | `DetailScreen.TagsSection`、`DetailViewModel` 拉 bgm tags |
| P2-5 今晚看什么·标签 | 候选池真实社区标签作为「想看的标签」筛选项 + 推荐硬筛；根因（作品标签未入库）已修复 | `WorkTagWriter`、`observeWorks` 回填、`RecommenderViewModel.computeAvailableTags/recommendFromWorks` |
| P2-6 题材筛选 | 题材 facet = 真实社区 CONTENT 标签 ∪ 常见题材兜底，可按题材过滤；根因同 P2-5 已修复 | `WorkTagWriter`、`observeWorks` 回填、`DiscoverBoards.buildFilterFacets/applyFilter` |
| P2-7 评分差异 | 跨源归一化差距榜（差距降序、阈值过滤），中性措辞 | `DiscoverBoards.buildScoreDiffBoard` |
| P3-1 雷达提示词 | 雷达 = 亮点（pros）/雷点（pitfalls）/节奏（overallImpression 前中后期）/制作 | `AiTaskSpecs.spoilerRadarPrompt` |
| P1-2 取消「想看」同步 | 移出待补池=取消想看：本地删「想看」记录 + Bangumi 力所能及清理（评分/短评/标签）；**v0 无删除收藏端点**，明确提示去网页移除（不抓网页 RC.01 3.11）；在看/看过则仅移出不取消收藏 | `DetailViewModel.removeWantToWatch` |
| P2-3 榜单持久化缓存 | 冷启动秒开：DataStore 存每范围有序作品 id，进入先用缓存重建卡片再联网刷新（作品/评分仍以 Room 为单一可信源） | `SettingsDataStore.get/saveRankingOrder`、`WorkRepository.getCachedRanking/saveRankingCache` |
| P2-8 今日状态入口 | 重定义为「今晚看什么」快捷入口：点心情带预填社区标签跳推荐器并预选筛选；移除无意义的旧选中态 | `HomeMood.presetTags`、`recommenderRoute(presetTags)`、`RecommenderViewModel` 预填 |

### ⚠️ 标记完成但核对/实测不达标（待重修，2026-06-22 复核）

> 本区原列 P0-1/P2-2/P2-5/P2-6 四项「名实不符」。经 2026-06-23 逐项确诊根因并修复，**四项均已上移「已完成」表**，本区暂无遗留项。后续若再发现名实不符项，仍在此登记。

### 需运行验证（功能已实现，建议真机回归）

| 任务 | 验证方式 |
| --- | --- |
| P1-2 | 详情页对「想看」作品点「移出待补池」→ 提示「已取消本地想看；Bangumi 不支持 API 删除收藏…」；本地「想看」记录消失；若为在看/看过则仅移出待补池并保留记录 |
| P2-3 | 榜单加载后杀进程冷启动，再进榜单瞬间显示上次内容（随后后台联网刷新） |
| P2-8 | 首页「今晚想看哪种？」点某心情 → 跳「今晚看什么」且对应标签已预选高亮 |

### 工程化与验证

- **E-2 构建环境**：✅ 非 ASCII 路径 `android.overridePathCheck=true`，`:app:assembleDebug` 通过。
- **APK 产物**：`app/build/outputs/apk/debug/app-debug.apk`（含本轮 P1-2/P2-2/P2-3/P2-8 及此前 P2-1/P2-4/P2-5）。
- **V2 清单状态**：P0-1～P3-1 全部已实现并编译通过；剩余仅「真机功能回归」与「纯英文路径下跑单测」两项环境/验证事项。
- **单元测试环境问题**：当前项目路径含中文「分支」，Windows + Kotest（JUnit Platform）类加载报 `ClassNotFoundException`（连无关的 `SanityTest` 亦失败），属环境问题（非代码缺陷）；`--tests` 过滤与 `--no-configuration-cache` 均无效。建议将仓库移至**纯英文路径**后执行 `:app:testDebugUnitTest` 验证（CI Linux 纯英文路径不受影响）。

## 口味算法（最终版 12 维引擎）架构与数据迁移（2026-06-23）

> 目标（用户诉求）：① 详情页口味匹配度别再「都挤在 55–65」、低口味作品别给高分；② 今晚看什么别再推社区低分 / 低匹配作品；③ 评分后画像自动更新可感知。本轮按 `算法文档/` 落地「画像层 + 候选层 + 校准层」，与旧 `PersonalTasteScorer` **并存**（引擎不可用时回退旧逻辑，绝不退化）。

### 新增持久化（Room v6 → v7 迁移）

- **`work_features`**：作品特征缓存（`subjectId` 主键、社区标签计数 JSON、`bangumiScore`/`bangumiVotes`、staff/角色/CV 结构化、`titleVariants`、`fetchedAt`）。画像构建与候选打分共用，避免重复联网。
- **`recommendation_exposure`**：今晚看什么曝光记录（`exposedAt`/`clickedAt`/`dismissedAt`），支撑**重复推荐冷却**（默认 14 天）与后续点击统计。
- **迁移**：`MIGRATION_6_7` 仅 `CREATE TABLE IF NOT EXISTS`（纯新增表，存量数据零风险）；`AppDatabase.version=7`，已在 `DatabaseModule` 注册。`work_features` 为缓存，存量用户首次「导入口味数据」时联网回填。

### 分层与关键类

- **特征层** `WorkFeatureRepository`（`data/taste/WorkFeatureRepositoryImpl`）：按 `subjectId` 取/补 `work_features`，`getFeatures(ids, networkBudget)` 限额联网（画像构建 60、今晚精排 24）。
- **分类层** `TagClassifier` + `TasteCategory`（12 类：题材/装置/XP/Staff/角色/CV/工作室/系列/情绪/节奏/质量/短评）：把社区标签 + 结构化信号归类，题材标签计数做对数压缩 `q=ln(1+count)/ln(1+maxCount)`。
- **画像层** `BuildTasteProfileUseCase` → `AdvancedTasteProfile`：样本选择（≤50，强制纳入极端分/有短评）、时间×极端×短评加权、逐标签正负向量、题材+装置 2/3 元**组合挖掘**、对训练样本求 `z` 分布得温度化 logistic 的 `μ=median(z)`、`τ=max(0.18,std(z))`。
- **候选/校准层** `ComputeTasteMatchUseCase`（`TasteRawScorer` 求十二维融合 `z` → 已评分同作偏置 `0.12·pref` → `p=σ((z-μ)/τ)` → `50+sign·min(45,1.18·|100p-50|)` 拉开到 `[5,95]`）。**分数拉开**正是治「挤在 55–65」的关键。
- **编排层** `TasteEngine`（`@Singleton`）：粘合「`user_collections` × `work_features`」与上述纯用例，内存 `StateFlow` 缓存画像。`rebuildFromCache()`（不联网，评分后即时）、`refreshFull()`（导入时联网补齐）、`score()`/`scoreBatch()`（详情/今晚）。

### 接线点

- **详情页**：`DetailViewModel.advancedTasteMatch` 随作品/我的评分/画像变化用 `TasteEngine.score()` 重算，经 `buildDetailUiState(advancedMatch=…)` → `TasteMatchResult.toTasteMatchUi()` 渲染（分段定性 + 命中大类理由 + 低置信/已评分说明）；引擎不可用回退旧 `toTasteMatch`。
- **今晚看什么**：`RecommenderViewModel` 召回后对候选 `scoreBatch(networkBudget=24)` 精排（引擎匹配度为主导分量），叠加**社区分下限 6.0 + 口味下限**双护栏与 **14 天重复冷却**，并 `recordExposure` 记录曝光。
- **画像自动更新**：评分保存后 `refreshTasteProfileFromLocal()` 同时重建旧画像与 `TasteEngine.rebuildFromCache()`；详情页保存提示在确有评分时追加「口味画像已自动更新」。
- **导入触发**：`TasteProfileViewModel.onImportFromBangumi` 成功后 `TasteEngine.refreshFull()` 联网补齐 `work_features` 并建高级画像。

### 验证

- `:app:compileDebugKotlin` 与 `:app:compileDebugUnitTestKotlin` 均通过（exit 0）。
- 锚点回归 `domain/taste/TasteEngineAnchorTest`（kotest）：偏爱「奇幻+异世界+智斗」、厌恶「恋爱+日常」的合成用户，断言强命中候选显著高分、反口味低分且**拉开差距**、组合优于单题材、已评分偏置、分数恒在 `[5,95]`、空画像中性兜底。**因路径含中文无法本机运行（见经验#10），仅作可编译规范，请在纯英文路径/CI 运行。**
- 修复了与本轮无关但阻塞测试编译的 `RouteMapRepositoryImplTest.FakeBacklogRepository`（缺 `archiveToDust`/`restoreFromDust` 实现，接口在「吃灰馆还原」一轮新增后未同步该 fake）。

## 0.15.0 体验修复与详情跨源（N1–N12）

### 评分差异榜同番去重（N1，`feature/discover/DiscoverBoards.kt`）
- `buildScoreDiffBoard` 前置 `dedupeSameWork`：按标题变体（canonical/cn/ja/romaji/en/aliases → `normalizeCompact`，长度≥4 去噪）交集做并查集聚类；每簇取来源优先级最高（Bangumi>AniList>MAL>Jikan>VNDB）且评分源更全者为代表。**只去重不合并分值**（避免误并邻季导致分数错配）。

### 首启默认 + 新手引导（N6，`feature/onboarding/*`、`data/datastore/SettingsState`）
- 默认值：`DEFAULT_JIKAN_ENABLED=true`、`DEFAULT_VNDB_ENABLED=true`（成人内容默认关）、`DEFAULT_TASTE_MATCH_THRESHOLD=0.4`；新增 `COMMUNITY_PROXY_BANGUMI_API_BASE_URL="https://bgmapi.anibt.net/"`（官方常量保留用于 isOfficial 判定与「切回官方」）。
- 引导：`OnboardingUiState` 增 `consentPrompt`，highlights 增「反代默认 / 多源开箱 / AI 可选（DeepSeek 等）」；`OnboardingScreen` 加同意勾选并经 `onConfirm(consented)` 上抛；`OnboardingViewModel.onOnboardingComplete(consentToProxyToken)` 在**首启**写入反代地址 + `bangumiNonOfficialTokenConsent`。仅首启写入，不影响老用户既有官方配置。

### 口味下限「关闭」真关闭（N4/N5，`RecommenderViewModel`）
- 热路径 `scoreBatch(networkBudget=0)` 只读缓存、后台重建画像不阻塞首屏。`tasteFloor = threshold`（不再 `coerceAtLeast(0.45)`）；`passesTaste = tasteFloor<=0 || match>=tasteFloor`；候选不足时逐级放宽（社区分下限→口味下限→冷却）再兜底，绝不空手。

### 详情页关联条目跨源评分补齐（N12，`data/repository/WorkRepositoryImpl`）
- 现象：`buildRouteEntries` 用 `rel.id`（Bangumi subjectId）作 workId，跳转打开的关联条目仅 Bangumi 源 → 详情只显示 Bangumi 评分。
- 原 `crossValidateRatings` 逐源按绝对相似度阈值（`CROSS_MATCH_THRESHOLD=0.86`）匹配，续作（第N期）/ 跨语言标题常低于阈值漏配。
- 新增 `enrichViaCrossSourceSearch(workId)`：仅动画、已≥2 源即跳过；用作品标题变体多源并行搜索（AniList/Jikan/MAL），以本作为锚点参与 `clusterMatches`（`sameWork` 季度感知、跨变体相似、核心包含），把**同簇**其他源的评分与 source link 挂到本 workId。在 `refreshRatingsAndWork` 中**先于** `crossValidateRatings` 调用（聚类更稳，交叉验证兜底剩余源）。

### AI 标签分维分类（N3，可行性，未落地）
- 项目已具备完整 AI 管线（`AiEngine`/`AiEngineImpl`、`AiTask` 五类、`OpenAiCompatibleProvider`、`AiTaskSpecs` prompt+schema、`SpoilerGuard`、`CredentialStore`；response_format json_schema→json_object→text 降级兼容 DeepSeek）。
- 设计（下一步）：新增 `AiTask.TagClassify`（输出 `[{tag,category}]` 数组 + confidence）或独立用例；`TagClassifier` 增进程级 `@Volatile` 覆盖表（优先于规则）；`TasteEngine` 在画像重建后于后台批量分类未知标签并持久化（DataStore）；未配置 AI 回退规则分类器。

### 验证（0.15.0）
- `:app:compileDebugKotlin` 与 `:app:assembleDebug` 均 **BUILD SUCCESSFUL**（本机路径含空格 + 中文，assembleDebug 可出 APK；单元测试仍受路径限制，见经验 #10）。版本 `0.14.0`→`0.15.0`（`versionCode 14→15`）。
