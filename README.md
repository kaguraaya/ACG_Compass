# ACG Compass

多源 ACG（动画 / 游戏 / 轻小说）推荐与「口味匹配」Android 应用。聚合 Bangumi、AniList、Jikan(MAL)、VNDB 等数据源，跨源匹配同一作品，并基于用户长期口味提供个性化推荐与无剧透 AI 评价。

> A multi-source anime/visual-novel discovery app for Android. It aggregates Bangumi, AniList, Jikan (MyAnimeList) and VNDB, performs cross-source matching, and produces personalized, taste-aware recommendations with spoiler-free AI analysis.

## 功能特性

- **多源聚合与跨源匹配**：同一作品在 Bangumi / AniList / MAL / VNDB 的条目自动聚类，按可信度与评分人数选代表条目。
- **口味画像**：从用户评价过的作品（使用作品自身 tag）构建长期正/负向偏好。
- **口味匹配度**：详情页给出「这部作品和我的口味有多契合」，可解释、低数据量低置信。
- **今晚看什么**：按当前心情 / 场景标签硬筛候选池，再结合长期口味排序。
- **无剧透 AI 评价雷达 / 评论摘要**：聚合社区评论，输出亮点 / 雷点 / 制作 / 节奏（无剧透）。
- **榜单与发现**：公共榜单、评分差异、题材筛选。
- **收藏与进度同步**：与 Bangumi 收藏 / 观看状态双向同步。

## 技术栈

- Kotlin · Jetpack Compose · Material 3
- Coroutines / Flow
- Hilt（依赖注入）
- Room（本地持久化）· DataStore
- Retrofit / OkHttp · kotlinx.serialization
- EncryptedSharedPreferences（凭据本地加密）

## 构建

```bash
./gradlew assembleDebug
```

- 产物：`app/build/outputs/apk/debug/app-debug.apk`
- 若工程路径包含非 ASCII 字符（如中文目录），需在 `gradle.properties` 保留：`android.overridePathCheck=true`

## AI 配置

应用内「设置」可配置 OpenAI 兼容 / Gemini / DeepSeek / OpenRouter 等服务商。**API Key 仅保存在设备本地加密存储中，不会上传或写入仓库。**

## 隐私

- 不收集用户数据；所有凭据与同步 Token 仅存于本地。
- 仓库已通过 `.gitignore` 排除 `*.har`、`local.properties`、keystore 等敏感文件。

## 许可证

待补充（建议 MIT 或 Apache-2.0）。
