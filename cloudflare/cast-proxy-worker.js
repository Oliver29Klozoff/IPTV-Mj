// Cast-to-device stream proxy — lets MKTV cast a live channel to a device with no IPTV
// account (see CastRelayManager.kt) WITHOUT ever handing that device your real Xtream
// URL, which normally has your username/password embedded directly in its path.
//
// Instead of sharing the real URL, the sender asks this worker to "wrap" it into an
// opaque, time-limited, encrypted token. The receiving device only ever sees
// `<worker url>/stream/<token>` — decrypting that token (to find the real upstream URL)
// requires CAST_PROXY_KEY, which lives only in this worker's environment and is never
// shipped inside the app. Even someone who captures the shared link cannot recover your
// real credentials from it, and the link stops working once TOKEN_TTL_MS elapses.
//
// Deploy this as a Cloudflare Worker (free tier is plenty for personal use):
//   1. https://dash.cloudflare.com -> Workers & Pages -> Create -> "Create Worker"
//   2. Paste this file's contents as the worker's code, deploy.
//   3. Worker -> Settings -> Variables -> add two "Secret" (encrypted) variables:
//        CAST_PROXY_KEY = any long random string (e.g. generate 32+ random bytes,
//                         base64-encode them) — this is what encrypts/decrypts tokens.
//        CAST_APP_KEY   = any random string of your choosing — the app sends this back
//                         as a header on /wrap so randos who find the worker's URL can't
//                         mint tokens for arbitrary URLs using your worker for free.
//   4. Copy the worker's URL (e.g. https://cast-proxy.YOURNAME.workers.dev) and put it
//      in local.properties as:
//        CAST_PROXY_URL=https://cast-proxy.YOURNAME.workers.dev
//        CAST_APP_KEY=<the same random string you set as CAST_APP_KEY above>

const TOKEN_TTL_MS = 12 * 60 * 60 * 1000; // 12 hours — plenty for a one-off cast, short
                                            // enough that a leaked link doesn't linger.

async function deriveKey(env) {
  const raw = new TextEncoder().encode(env.CAST_PROXY_KEY);
  const hash = await crypto.subtle.digest("SHA-256", raw); // normalizes any-length secret to 256 bits
  return crypto.subtle.importKey("raw", hash, { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
}

function toBase64Url(bytes) {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromBase64Url(s) {
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4);
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes;
}

async function wrapUrl(url, key, ttlMs) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const payload = JSON.stringify({ u: url, exp: Date.now() + ttlMs });
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv },
    key,
    new TextEncoder().encode(payload)
  );
  return `${toBase64Url(iv)}.${toBase64Url(new Uint8Array(ciphertext))}`;
}

async function unwrapToken(token, key) {
  const [ivPart, ctPart] = token.split(".");
  if (!ivPart || !ctPart) return null;
  try {
    const iv = fromBase64Url(ivPart);
    const ciphertext = fromBase64Url(ctPart);
    const plaintextBuf = await crypto.subtle.decrypt({ name: "AES-GCM", iv }, key, ciphertext);
    const payload = JSON.parse(new TextDecoder().decode(plaintextBuf));
    if (typeof payload.u !== "string" || typeof payload.exp !== "number") return null;
    if (Date.now() > payload.exp) return null;
    return payload.u;
  } catch (_e) {
    return null; // malformed / tampered / wrong key — never leak WHY, just refuse
  }
}

/** HLS playlists reference their segments as their own URLs (sometimes absolute, sometimes
 * relative to the playlist's own path) — for an absolute reference back to the same
 * credential-bearing host, forwarding it unrewritten would hand the receiver the real
 * upstream URL. Every absolute http(s) line gets re-wrapped as another proxied /stream/
 * link (same key, freshly minted here — no extra round trip to /wrap needed since the
 * worker already holds the key); relative references are left alone since they never
 * carried credentials in the first place and resolving them is the player's own job. */
async function rewritePlaylist(text, key, origin) {
  const lines = text.split("\n");
  const out = [];
  for (const line of lines) {
    const trimmed = line.trim();
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
      const token = await wrapUrl(trimmed, key, TOKEN_TTL_MS);
      out.push(`${origin}/stream/${token}`);
    } else {
      out.push(line);
    }
  }
  return out.join("\n");
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const key = await deriveKey(env);

    if (url.pathname === "/wrap" && request.method === "POST") {
      if (request.headers.get("X-App-Key") !== env.CAST_APP_KEY) {
        return new Response("Forbidden", { status: 403 });
      }
      const body = await request.json().catch(() => null);
      if (!body || typeof body.url !== "string" || !body.url) {
        return new Response("Bad request", { status: 400 });
      }
      const token = await wrapUrl(body.url, key, TOKEN_TTL_MS);
      return new Response(JSON.stringify({ token }), {
        headers: { "Content-Type": "application/json" },
      });
    }

    if (url.pathname.startsWith("/stream/") && request.method === "GET") {
      const token = url.pathname.slice("/stream/".length);
      const target = await unwrapToken(token, key);
      if (!target) return new Response("Expired or invalid cast link", { status: 410 });

      const upstream = await fetch(target, {
        headers: { "User-Agent": "Mozilla/5.0 (compatible; MKTV-CastProxy/1.0)" },
      });
      if (!upstream.ok) {
        return new Response("Upstream error", { status: 502 });
      }

      const contentType = upstream.headers.get("content-type") || "";
      const isPlaylist = target.includes(".m3u8") ||
        contentType.includes("mpegurl") || contentType.includes("vnd.apple.mpegurl");

      if (isPlaylist) {
        const text = await upstream.text();
        const rewritten = await rewritePlaylist(text, key, url.origin);
        return new Response(rewritten, {
          headers: { "Content-Type": "application/vnd.apple.mpegurl" },
        });
      }

      // Raw segment/TS stream — pipe the body straight through without buffering it, so a
      // continuous live stream isn't held fully in worker memory before forwarding.
      return new Response(upstream.body, {
        headers: { "Content-Type": contentType || "video/mp2t" },
      });
    }

    return new Response("Not found", { status: 404 });
  },
};
