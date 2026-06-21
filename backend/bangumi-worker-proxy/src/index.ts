export interface Env {
  DEFAULT_USER_AGENT?: string;
}

type RouteType = "api" | "p1" | "img";

interface TargetRoute {
  type: RouteType;
  url: URL;
}

const UPSTREAM = {
  api: "https://api.bgm.tv",
  p1: "https://next.bgm.tv",
  img: "https://lain.bgm.tv",
} as const;

const DEFAULT_USER_AGENT =
  "ACGCompass/1.0 (Bangumi proxy; contact: kaguraaya10@gmail.com)";

const INTERNAL_PATHS = new Set(["/", "/__health", "/robots.txt"]);

function corsHeaders(): HeadersInit {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET,HEAD,POST,PUT,PATCH,DELETE,OPTIONS",
    "Access-Control-Allow-Headers": "Authorization,Content-Type,User-Agent,Accept",
    "Access-Control-Max-Age": "86400",
  };
}

function plainText(body: string, status = 200): Response {
  return new Response(body, {
    status,
    headers: {
      "Content-Type": "text/plain; charset=utf-8",
      ...corsHeaders(),
    },
  });
}

function normalizeOrigin(origin: string): string {
  return origin.replace(/\/$/, "");
}

function buildTargetUrl(requestUrl: string): TargetRoute | null {
  const incoming = new URL(requestUrl);
  const path = incoming.pathname;

  if (INTERNAL_PATHS.has(path)) return null;

  if (path.startsWith("/img/")) {
    const target = new URL(UPSTREAM.img);
    target.pathname = path.replace(/^\/img/, "") || "/";
    target.search = incoming.search;
    return { type: "img", url: target };
  }

  if (path.startsWith("/p1/")) {
    const target = new URL(UPSTREAM.p1);
    target.pathname = path;
    target.search = incoming.search;
    return { type: "p1", url: target };
  }

  if (path.startsWith("/api/")) {
    const target = new URL(UPSTREAM.api);
    target.pathname = path.replace(/^\/api/, "") || "/";
    target.search = incoming.search;
    return { type: "api", url: target };
  }

  // Compatibility mode: existing app code can set this Worker as the only
  // Bangumi API base URL and continue calling /v0/subjects/1, /me, etc.
  // Unknown non-internal paths default to api.bgm.tv, not to an arbitrary host.
  const target = new URL(UPSTREAM.api);
  target.pathname = path;
  target.search = incoming.search;
  return { type: "api", url: target };
}

function rewriteTextBody(text: string, origin: string): string {
  const base = normalizeOrigin(origin);
  const host = new URL(base).host;

  return text
    .replaceAll("https://lain.bgm.tv", `${base}/img`)
    .replaceAll("http://lain.bgm.tv", `${base}/img`)
    .replaceAll("//lain.bgm.tv", `//${host}/img`)
    .replaceAll("lain.bgm.tv", `${host}/img`)
    .replaceAll("https://api.bgm.tv", `${base}`)
    .replaceAll("http://api.bgm.tv", `${base}`)
    .replaceAll("https://next.bgm.tv/p1", `${base}/p1`)
    .replaceAll("http://next.bgm.tv/p1", `${base}/p1`);
}

function shouldRewrite(contentType: string | null): boolean {
  if (!contentType) return false;
  const lower = contentType.toLowerCase();
  return (
    lower.includes("application/json") ||
    lower.includes("text/") ||
    lower.includes("javascript") ||
    lower.includes("application/problem+json")
  );
}

function copyRequestHeaders(request: Request, route: TargetRoute, env: Env): Headers {
  const headers = new Headers(request.headers);

  // Avoid leaking or confusing hop-by-hop/origin-specific headers.
  headers.delete("host");
  headers.delete("cf-connecting-ip");
  headers.delete("cf-ipcountry");
  headers.delete("cf-ray");
  headers.delete("x-forwarded-for");
  headers.delete("x-real-ip");

  if (!headers.get("user-agent")) {
    headers.set("user-agent", env.DEFAULT_USER_AGENT || DEFAULT_USER_AGENT);
  }

  // p1 is treated as read-only public data here. Do not proxy browser login
  // cookies through a third-party Worker.
  if (route.type === "p1") {
    headers.delete("cookie");
  }

  return headers;
}

function isMethodAllowed(method: string, routeType: RouteType): boolean {
  if (method === "OPTIONS") return true;
  if (routeType === "img") return method === "GET" || method === "HEAD";
  if (routeType === "p1") return method === "GET" || method === "HEAD";
  return ["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"].includes(method);
}

async function proxy(request: Request, route: TargetRoute, env: Env): Promise<Response> {
  if (!isMethodAllowed(request.method, route.type)) {
    return plainText("Method not allowed for this route.", 405);
  }

  const init: RequestInit = {
    method: request.method,
    headers: copyRequestHeaders(request, route, env),
    redirect: "follow",
    body: request.method === "GET" || request.method === "HEAD" ? undefined : request.body,
  };

  if (route.type === "img") {
    init.cf = {
      cacheEverything: true,
      cacheTtl: 60 * 60 * 24 * 30,
    } as RequestInitCfProperties;
  }

  const upstreamResponse = await fetch(route.url.toString(), init);
  const responseHeaders = new Headers(upstreamResponse.headers);

  responseHeaders.delete("access-control-allow-origin");
  responseHeaders.delete("access-control-allow-methods");
  responseHeaders.delete("access-control-allow-headers");
  responseHeaders.delete("access-control-allow-credentials");
  responseHeaders.delete("content-length");
  responseHeaders.delete("set-cookie");

  for (const [key, value] of Object.entries(corsHeaders())) {
    responseHeaders.set(key, value);
  }

  if (route.type === "img") {
    responseHeaders.set("Cache-Control", "public, max-age=2592000, immutable");
    return new Response(upstreamResponse.body, {
      status: upstreamResponse.status,
      statusText: upstreamResponse.statusText,
      headers: responseHeaders,
    });
  }

  if (shouldRewrite(responseHeaders.get("content-type"))) {
    const text = await upstreamResponse.text();
    const origin = new URL(request.url).origin;
    return new Response(rewriteTextBody(text, origin), {
      status: upstreamResponse.status,
      statusText: upstreamResponse.statusText,
      headers: responseHeaders,
    });
  }

  return new Response(upstreamResponse.body, {
    status: upstreamResponse.status,
    statusText: upstreamResponse.statusText,
    headers: responseHeaders,
  });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const incoming = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    if (incoming.pathname === "/robots.txt") {
      return plainText("User-agent: *\nDisallow: /\n");
    }

    if (incoming.pathname === "/" || incoming.pathname === "/__health") {
      return plainText(
        [
          "Bangumi Worker Proxy is running.",
          "",
          "Compatibility mode:",
          "  /v0/...       -> https://api.bgm.tv/v0/...",
          "  /api/v0/...   -> https://api.bgm.tv/v0/...",
          "  /p1/...       -> https://next.bgm.tv/p1/...",
          "  /img/...      -> https://lain.bgm.tv/...",
          "",
          "Recommended app base URL: this Worker origin, for example https://xxxx.workers.dev",
        ].join("\n"),
      );
    }

    const route = buildTargetUrl(request.url);
    if (!route) return plainText("Not found.", 404);

    try {
      return await proxy(request, route, env);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      return plainText(`Proxy error: ${message}`, 502);
    }
  },
};
