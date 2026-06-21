# Bangumi Worker Proxy

这是 ACG Compass 使用的 Bangumi 反代 Worker。它把一个 Cloudflare Worker 地址封装成一个统一入口，同时覆盖：

- Bangumi 公开 API：`https://api.bgm.tv`
- Bangumi 新站 p1 API：`https://next.bgm.tv/p1`
- Bangumi 图片资源：`https://lain.bgm.tv`

## 为什么做成一个地址

ACG Compass 现有设置页只有一个“Bangumi API 地址”。为了兼容这个 UI，本 Worker 支持“单地址兼容模式”：

```text
https://your-worker.workers.dev/v0/subjects/1
```

会被反代到：

```text
https://api.bgm.tv/v0/subjects/1
```

也就是说，App 设置页里只需要填一个地址：

```text
https://your-worker.workers.dev
```

不需要让普通用户分别填写 API、p1、图片三个地址。

## 路由规则

| 用户访问 | 上游地址 | 用途 |
|---|---|---|
| `/v0/...` | `https://api.bgm.tv/v0/...` | 兼容现有 App 的公开 API 调用 |
| `/api/v0/...` | `https://api.bgm.tv/v0/...` | 显式公开 API 调用 |
| `/p1/...` | `https://next.bgm.tv/p1/...` | 新站 p1 API，例如评论等 |
| `/img/...` | `https://lain.bgm.tv/...` | 图片、封面、头像 |

Worker 会自动把 API JSON 里的 `lain.bgm.tv` 图片地址改写成当前 Worker 的 `/img/...` 地址。

## 本地开发

```bash
cd backend/bangumi-worker-proxy
npm install
npm run dev
```

## 部署到 Cloudflare Workers

```bash
cd backend/bangumi-worker-proxy
npm install
npx wrangler login
npm run deploy
```

部署成功后会得到一个类似：

```text
https://bangumi-worker-proxy.<your-subdomain>.workers.dev
```

的地址。把它填到 ACG Compass 的 Bangumi API 地址里即可。

## 连接测试

```bash
curl https://your-worker.workers.dev/__health
curl https://your-worker.workers.dev/v0/subjects/1
curl https://your-worker.workers.dev/api/v0/subjects/1
curl https://your-worker.workers.dev/img/r/200/pic/cover/l/c4/ca/1_d2tF2.jpg -I
```

p1 路径不要硬猜，要使用 App 抓到的原始 `https://next.bgm.tv/p1/...` 路径，然后替换域名：

```text
https://next.bgm.tv/p1/xxxx
↓
https://your-worker.workers.dev/p1/xxxx
```

## GitHub Actions 自动部署

仓库里已经提供：

```text
.github/workflows/deploy-bangumi-worker.yml
```

需要在 GitHub 仓库 Settings → Secrets and variables → Actions 里添加：

```text
CLOUDFLARE_ACCOUNT_ID
CLOUDFLARE_API_TOKEN
```

之后 push 到 `main` 且 Worker 文件发生变化时，会自动部署。

## 安全边界

- 这不是通用代理，只会访问固定的 Bangumi 上游。
- `/p1/` 默认只允许 `GET` 和 `HEAD`。
- `/img/` 默认只允许 `GET` 和 `HEAD`。
- p1 不转发 Cookie，避免用户登录态经过代理。
- `Authorization` 会继续传递给公开 API，用于 Bangumi Token 同步。

## App 侧建议

设置页仍然保留一个地址即可，命名可以从“Bangumi API 地址”改成：

```text
Bangumi API / 反代地址
```

内部代码建议这样派生：

```kotlin
val base = userConfiguredBangumiBase.trimEnd('/')
val publicApiBase = base
val p1ApiBase = "$base/p1"
val imageBase = "$base/img"
```

如果用户填的是官方地址：

```text
https://api.bgm.tv
```

那 App 需要识别官方模式，p1 仍然走 `https://next.bgm.tv/p1`，图片仍然使用 API 返回地址；如果用户填的是 Worker 地址，就走上面的统一反代模式。

## 参考与来源说明

本目录代码为本项目编写，没有复制第三方开源反代项目代码。实现参考了 Cloudflare Workers 官方文档中的 `fetch()`、Custom Domains、Wrangler 与 GitHub Actions 部署说明，详见 `docs/references.md`。
