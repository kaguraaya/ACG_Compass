# References

本 Worker 代码为 ACG Compass 项目编写，没有复制第三方开源反代项目代码。以下为实现时参考的公开资料与设计依据。

## Cloudflare Workers

1. Cloudflare Workers Limits  
   https://developers.cloudflare.com/workers/platform/limits/

   用途：确认免费版 Workers 的请求数、CPU、内存、子请求等限制。当前免费版为 `100,000 requests/day`，具体以官方文档为准。

2. Cloudflare Workers Fetch API  
   https://developers.cloudflare.com/workers/runtime-apis/fetch/

   用途：Worker 内通过 `fetch()` 请求固定上游地址，实现反代。

3. Cloudflare Workers Custom Domains  
   https://developers.cloudflare.com/workers/configuration/routing/custom-domains/

   用途：后续如果要把 Worker 绑定到 `bgm.kaguraaya.xyz`，需要通过 Cloudflare Custom Domain 或 Routes 完成。

4. Cloudflare Workers GitHub Actions  
   https://developers.cloudflare.com/workers/ci-cd/external-cicd/github-actions/

   用途：通过 GitHub Actions + Wrangler 自动部署 Worker。

## Bangumi

1. Bangumi API 文档与 OpenAPI 说明  
   https://github.com/bangumi/api

   用途：公开 API 上游为 `https://api.bgm.tv`，App 的条目、搜索、收藏同步等主要依赖该地址。

2. Bangumi Dev Docs  
   https://github.com/bangumi/dev-docs

   用途：区分公开 API 与新站私有 API；p1 新站私有 API 对应 `https://next.bgm.tv/p1`，不保证长期稳定。

## 设计说明

- 本 Worker 是固定上游反代，不是开放代理。
- `/api/*` 与根路径兼容模式走 `api.bgm.tv`。
- `/p1/*` 走 `next.bgm.tv/p1/*`。
- `/img/*` 走 `lain.bgm.tv/*`。
- API 文本响应会改写图片域名，避免客户端继续访问 `lain.bgm.tv`。
- p1 不转发 Cookie，降低用户登录态泄露风险。
