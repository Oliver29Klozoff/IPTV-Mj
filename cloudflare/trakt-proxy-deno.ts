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

Deno.serve(async (req: Request) => {
  const url = new URL(req.url);

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
    }), { headers: { "Content-Type": "application/json" } });
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
