// Trakt OAuth proxy for Deno Deploy — holds the client_secret server-side so it never ships
// inside the app. Use this instead of the Cloudflare Worker version if Trakt blocks Cloudflare's
// network (it does, as of testing — Cloudflare's own anti-bot page rejects Worker traffic to
// trakt.tv). Deno Deploy runs on different infrastructure.
//
// Deploy:
//   1. https://dash.deno.com -> New Project -> "Playground" (no GitHub needed)
//   2. Paste this file's contents into the editor, click "Save and deploy" / "Deploy"
//   3. Project -> Settings -> Environment Variables -> add:
//        TRAKT_CLIENT_ID     = your Trakt app's client ID
//        TRAKT_CLIENT_SECRET = your Trakt app's client secret
//      (redeploy after adding — Deno Deploy requires a redeploy to pick up new env vars)
//   4. Copy the project's URL (e.g. https://your-project-name.deno.dev)
//      and put it in local.properties as:
//        TRAKT_PROXY_URL=https://your-project-name.deno.dev
//
// Debug reports (Settings -> Send debug report, v7.26+) also come here, at POST /report:
//   - add a third environment variable, DISCORD_WEBHOOK = the Discord webhook URL, and redeploy.
//   - the webhook lives only here. Never put it in the app, local.properties, version.json or
//     this repository: anything in the APK is public.

// Debug reports: what one may contain, and how many one address may send.
const REPORT_MAX_CHARS = 1_000_000;
const REPORT_MAX_BYTES = 1_100_000; // the whole request body, read no further than this
const REPORT_TITLE_MAX = 200;
const REPORTS_PER_WINDOW = 5;
const REPORT_WINDOW_MS = 10 * 60 * 1000;
const recentReports = new Map<string, number[]>();

/** Takes one of [ip]'s report slots, or returns false when it has none left. Synchronous — no
 * await between the check and the update — so simultaneous requests can't all pass the check. */
function takeReportSlot(ip: string, now: number): boolean {
  if (recentReports.size > 10_000) {
    for (const [key, times] of recentReports) {
      if (times.every((t) => now - t >= REPORT_WINDOW_MS)) recentReports.delete(key);
    }
  }
  const sent = (recentReports.get(ip) ?? []).filter((t) => now - t < REPORT_WINDOW_MS);
  if (sent.length >= REPORTS_PER_WINDOW) return false;
  sent.push(now);
  recentReports.set(ip, sent);
  return true;
}

/** The body as text, or null once it passes [maxBytes] (the rest is never read). */
async function readLimited(req: Request, maxBytes: number): Promise<string | null> {
  const declared = Number(req.headers.get("content-length") ?? "0");
  if (declared > maxBytes) return null;
  if (!req.body) return "";
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel().catch(() => {});
      return null;
    }
    chunks.push(value);
  }
  const all = new Uint8Array(total);
  let at = 0;
  for (const c of chunks) { all.set(c, at); at += c.byteLength; }
  return new TextDecoder().decode(all);
}

async function handleReport(req: Request, ip: string): Promise<Response> {
  const webhook = Deno.env.get("DISCORD_WEBHOOK") ?? "";
  if (!webhook) return new Response("Reports are not configured", { status: 503 });
  // Counted before anything is read: a rejected or malformed report still uses a slot.
  if (!takeReportSlot(ip, Date.now())) return new Response("Too many reports", { status: 429 });

  const text = await readLimited(req, REPORT_MAX_BYTES).catch(() => null);
  if (text === null) return new Response("Report too large", { status: 413 });
  let body: { title?: unknown; report?: unknown };
  try { body = JSON.parse(text); } catch { return new Response("Bad request", { status: 400 }); }
  if (body === null || typeof body !== "object") return new Response("Bad request", { status: 400 });
  const title = typeof body.title === "string" ? body.title.slice(0, REPORT_TITLE_MAX) : "";
  const report = typeof body.report === "string" ? body.report : "";
  if (!title || !report || report.length > REPORT_MAX_CHARS) return new Response("Bad request", { status: 400 });

  // Sent as a text attachment so the whole report arrives (message text is capped at 2000).
  // allowed_mentions: a report can't ping @everyone or anyone else.
  const form = new FormData();
  form.append("payload_json", JSON.stringify({
    username: "Captain Hook",
    content: `**${title.replace(/[*_`~|]/g, "")}**`,
    allowed_mentions: { parse: [] },
  }));
  form.append("files[0]", new Blob([report], { type: "text/plain" }), "debug_report.txt");
  const resp = await fetch(webhook, { method: "POST", body: form });
  return new Response(resp.ok ? "ok" : "Discord rejected the report", { status: resp.ok ? 200 : 502 });
}

Deno.serve(async (req: Request, info: Deno.ServeHandlerInfo) => {
  const url = new URL(req.url);

  if (req.method === "POST" && url.pathname === "/report") {
    return handleReport(req, (info.remoteAddr as Deno.NetAddr).hostname ?? "unknown");
  }

  // Self-check: GET /debug shows (without revealing the values) whether the two
  // required env vars are actually set, and a length/prefix hint to catch typos/whitespace.
  if (req.method === "GET" && url.pathname === "/debug") {
    const id = Deno.env.get("TRAKT_CLIENT_ID") ?? "";
    const secret = Deno.env.get("TRAKT_CLIENT_SECRET") ?? "";
    return new Response(JSON.stringify({
      clientIdSet: id.length > 0,
      clientIdLength: id.length,
      clientIdPrefix: id.slice(0, 4),
      clientSecretSet: secret.length > 0,
      clientSecretLength: secret.length,
      reportsConfigured: (Deno.env.get("DISCORD_WEBHOOK") ?? "").length > 0,
    }),{ headers: { "Content-Type": "application/json" } });
  }

  if (req.method !== "POST") {
    return new Response("Method not allowed", { status: 405 });
  }
  let traktPath: string;
  if (url.pathname === "/device/token") {
    traktPath = "/oauth/device/token";
  } else if (url.pathname === "/refresh") {
    traktPath = "/oauth/token";
  } else {
    return new Response("Not found", { status: 404 });
  }

  const body = await req.json().catch(() => null);
  if (!body) return new Response("Bad request", { status: 400 });

  const clientId = Deno.env.get("TRAKT_CLIENT_ID") ?? "";
  const clientSecret = Deno.env.get("TRAKT_CLIENT_SECRET") ?? "";

  const traktBody: Record<string, unknown> = {
    ...body,
    client_id: clientId,
    client_secret: clientSecret,
  };
  if (traktPath === "/oauth/token") {
    traktBody.grant_type = "refresh_token";
  }

  const resp = await fetch(`https://api.trakt.tv${traktPath}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "User-Agent": "Mozilla/5.0 (compatible; MKTV-TraktProxy/1.0)",
      "trakt-api-version": "2",
      "trakt-api-key": clientId,
    },
    body: JSON.stringify(traktBody),
  });

  const text = await resp.text();
  return new Response(text, {
    status: resp.status,
    headers: { "Content-Type": "application/json" },
  });
});
