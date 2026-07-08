// Trakt OAuth proxy — holds the client_secret server-side so it never ships inside the app.
//
// Deploy this as a Cloudflare Worker (free tier is plenty for personal use):
//   1. https://dash.cloudflare.com -> Workers & Pages -> Create -> "Create Worker"
//   2. Paste this file's contents as the worker's code, deploy.
//   3. Worker -> Settings -> Variables -> add two "Secret" (encrypted) variables:
//        TRAKT_CLIENT_ID     = your Trakt app's client ID
//        TRAKT_CLIENT_SECRET = your Trakt app's client secret
//   4. Copy the worker's URL (e.g. https://trakt-proxy.YOURNAME.workers.dev)
//      and put it in local.properties as:
//        TRAKT_PROXY_URL=https://trakt-proxy.YOURNAME.workers.dev
//
// The app only ever sends this worker a device code or a refresh token — never the
// secret — and this worker is the only thing that ever talks to Trakt with the secret
// attached. Nothing sensitive is ever compiled into the APK.

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return new Response("Method not allowed", { status: 405 });
    }

    const url = new URL(request.url);
    let traktPath;
    if (url.pathname === "/device/token") {
      traktPath = "/oauth/device/token";
    } else if (url.pathname === "/refresh") {
      traktPath = "/oauth/token";
    } else {
      return new Response("Not found", { status: 404 });
    }

    const body = await request.json().catch(() => null);
    if (!body) return new Response("Bad request", { status: 400 });

    const traktBody = {
      ...body,
      client_id: env.TRAKT_CLIENT_ID,
      client_secret: env.TRAKT_CLIENT_SECRET,
    };
    if (traktPath === "/oauth/token") {
      traktBody.grant_type = "refresh_token";
    }

    const resp = await fetch(`https://api.trakt.tv${traktPath}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(traktBody),
    });

    const text = await resp.text();
    return new Response(text, {
      status: resp.status,
      headers: { "Content-Type": "application/json" },
    });
  },
};
