<div align="center">

<img src="docs/icon.svg" alt="Hoshimi app icon" width="120" height="120">

# Hoshimi · 多源 ACG 口味罗盘

**跨源聚合 · 口味匹配 · 个性化推荐 · 无剧透 AI 评价**

[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)](#environment)
[![Language](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)](#tech-stack)
[![UI](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](#tech-stack)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

</div>

**Hoshimi**（星見）是一款多源 ACG（动画 / 游戏 / 视觉小说）发现与「口味匹配」Android 应用。聚合 Bangumi、AniList、Jikan(MyAnimeList)、VNDB 等数据源，跨源匹配同一作品，并基于用户长期口味提供个性化推荐与无剧透 AI 评价。

> **English** — Hoshimi is a multi-source anime / visual-novel discovery app for Android. It aggregates Bangumi, AniList, Jikan (MyAnimeList) and VNDB, performs cross-source matching, and produces personalized, taste-aware recommendations with spoiler-free AI analysis. All credentials stay encrypted on-device; nothing is uploaded to any first-party server.

---

## 目录

- [功能特性](#功能特性)
- [快速开始（用户）](#快速开始用户)
- [运行环境](#environment)
- [从源码构建（开发者）](#从源码构建开发者)
- [配置 AI 与数据源](#配置-ai-与数据源)
- [项目架构](#项目架构)
- [技术栈](#tech-stack)
- [隐私与安全](#隐私与安全)
- [参与贡献](#参与贡献)
- [致谢](#致谢)
- [许可证](#许可证)

---

## 功能特性

- **多源聚合与跨源匹配** — 同一作品在 Bangumi / AniList / MAL / VNDB 的条目自动聚类；按媒介类型（动画 / 漫画 / 游戏）、可信度与评分人数选取代表条目，避免「同名小说 / 视觉小说」抢占动画评分。
- **口味画像** — 从你评价过的作品构建长期正 / 负向偏好，**仅采用作品自身的 Bangumi 社区标签**并过滤年份 / 媒介格式等噪声标签。
- **口味匹配度** — 详情页给出「这部作品与我的口味有多契合」，以长期口味为主、平台评分为辅，可解释，数据不足时显式标注低置信。
- **今晚看什么** — 按当前心情 / 接受程度进行**硬性标签筛选**（排除冲突题材），再结合长期口味与社区口碑排序；无合适候选时引导放宽条件。
- **探索队列** — 卡片式逐部探索「还没接触」的新作品：单击卡片翻面看简介，左滑略过 / 右滑加入待补池，冷却机制避免重复推荐。
- **无剧透 AI 评价雷达 / 评论摘要** — 聚合社区评论，输出亮点 / 雷点 / 制作 / 节奏，全程剧透过滤。
- **榜单与发现** — 公共榜单、跨平台评分差异、题材筛选。
- **收藏与进度同步** — 与 Bangumi 收藏 / 观看状态双向同步。

## 快速开始（用户）

1. 在 [Releases](../../releases) 下载最新 `app-release.apk`（或参考下文自行构建 Debug 包）。
2. 在设备上允许「安装未知来源应用」，安装 APK。
3. 首次启动可直接浏览公共榜单；如需个性化功能：
   - **口味画像 / 收藏同步**：在「设置 → Bangumi」登录并同步收藏。
   - **AI 评价 / 口味分析**：在「设置 → AI」填入你自己的 API Key（见 [配置](#配置-ai-与数据源)）。

> 说明：本项目为个人 / 学习用途，所有第三方数据归各平台所有，请遵守对应平台的使用条款。

## <a id="environment"></a>运行环境

| 项目 | 要求 |
| --- | --- |
| 最低系统 | Android 8.0（API 26） |
| 目标系统 | Android 14（API 34） |
| 架构 | 通用（纯 Kotlin / Compose，无 NDK） |

## 从源码构建（开发者）

**前置要求**：JDK 17、Android SDK（compileSdk 34）。无需单独安装 Gradle，使用仓库内置 Wrapper。

```bash
# 克隆
git clone https://github.com/kaguraaya/ACG_Compass.git
cd ACG_Compass

# 构建 Debug APK
./gradlew assembleDebug          # Windows: .\gradlew.bat assembleDebug

# 仅编译校验（更快）
./gradlew :app:compileDebugKotlin

# 运行单元测试
./gradlew testDebugUnitTest
```

- 产物：`app/build/outputs/apk/debug/app-debug.apk`
- 若工程路径包含非 ASCII 字符（如中文目录），`gradle.properties` 中需保留：`android.overridePathCheck=true`

## 配置 AI 与数据源

应用内「设置」页支持配置：

- **AI 服务商**：OpenAI 兼容 / Gemini / DeepSeek / OpenRouter 等。填写 `baseUrl` + `API Key` + 模型名即可；支持推理模型（Kimi、DeepSeek-R 等）。
- **Bangumi**：用于登录、收藏同步、口味画像。

> **安全提示**：所有 API Key 与 Token 仅通过 `EncryptedSharedPreferences` 保存在**设备本地加密存储**中，绝不上传、绝不写入仓库。请勿在 issue / PR 中粘贴你的密钥。

## 项目架构

分层（Clean Architecture 思路）+ 按功能划分模块：

```
app/src/main/java/com/acgcompass/
├── core/         # 通用基础：AppResult/AppError、设计系统(Compose 主题/组件)、UiState
├── domain/       # 领域层（纯 Kotlin）：model、repository 接口、usecase、ai 抽象、matching 跨源匹配
├── data/         # 数据层：Room(local)、Retrofit(remote: bangumi/anilist/jikan/mal/vndb/ai)、
│                 #         repository 实现、sync 同步、mapper 映射
└── feature/      # 表现层：按功能分包（detail / recommender / taste / settings / ranking ...），
                  #         每个功能含 Compose Screen + ViewModel + Contract（MVVM + StateFlow）
```

- **数据流**：`Remote/Local → Repository → UseCase → ViewModel(StateFlow) → Compose Screen`
- **跨源匹配**：`domain/matching` 按规范化标题聚类，按媒介类型 / 可信度 / 评分人数选代表条目。
- **AI 管线**：`AiEngine` 组装提示词 + schema → Provider 调用 → 校验 / 修复 / 低置信兜底 → 剧透过滤；失败时回退本地规则，页面绝不无响应。
- **绝不伪造**：数据缺失统一以「暂无数据」占位，不编造评分 / 评价 / 观看顺序。

## <a id="tech-stack"></a>技术栈

- **语言 / UI**：Kotlin · Jetpack Compose · Material 3
- **异步**：Coroutines / Flow
- **依赖注入**：Hilt
- **持久化**：Room（含 schema 导出与迁移测试）· DataStore
- **网络**：Retrofit / OkHttp · kotlinx.serialization
- **安全**：EncryptedSharedPreferences（凭据本地加密）
- **构建**：Gradle（Kotlin DSL）· KSP · JDK 17

## 隐私与安全

- 不收集用户数据；所有凭据与同步 Token 仅存于本地加密存储。
- AI 请求仅发往你自行配置的服务商。

## 参与贡献

欢迎 Issue 与 PR：

1. Fork 并基于 `main` 创建特性分支：`git checkout -b feat/xxx`
2. 提交信息建议遵循 [Conventional Commits](https://www.conventionalcommits.org/)（如 `fix(rating): ...`）。
3. 提交前请确保 `./gradlew :app:compileDebugKotlin` 与单元测试通过。
4. 发起 PR 并简要描述动机与改动点。

## 致谢

感谢以下数据源与开源项目（数据、商标归各自平台所有；本项目仅用于个人 / 学习用途）：

- **数据源**：[Bangumi](https://bgm.tv/)、[AniList](https://anilist.co/)、[Jikan](https://jikan.moe/)（非官方 MyAnimeList API）、[MyAnimeList](https://myanimelist.net/) 官方 API、[VNDB](https://vndb.org/)。
- **核心开源技术**：Jetpack Compose · Material 3 · Hilt · Room · Retrofit / OkHttp · kotlinx.serialization · Kotlin Coroutines / Flow。
- **学习参考**：开发中参考了多个优秀的开源 Bangumi / 追番客户端（如 animeko、czy0729/Bangumi、xiaoyvyv/bangumi 等）的接口实现思路；其源码仅作本地研究、未包含在本仓库（见 `.gitignore`）。

## 许可证

本项目以 [MIT License](LICENSE) 开源。你可以自由使用、修改与分发，但需保留版权与许可声明。第三方数据 / 商标归各自平台所有。
