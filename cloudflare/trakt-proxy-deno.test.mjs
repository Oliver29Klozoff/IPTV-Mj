// Tests the debug-report route of trakt-proxy-deno.ts under Node, with a stub Deno global and a
// fake Discord. Run before deploying a change to the proxy (needs Node 22.6+):
//   node --experimental-strip-types cloudflare/trakt-proxy-deno.test.mjs
import { readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const env = { TRAKT_CLIENT_ID: "id", TRAKT_CLIENT_SECRET: "secret", DISCORD_WEBHOOK: "https://discord.test/api/webhooks/1/abc" };
let handler;
globalThis.Deno = { env: { get: (k) => env[k] }, serve: (h) => { handler = h; } };
const posted = [];
globalThis.fetch = async (url, init) => {
  posted.push({ url, init });
  return new Response(null, { status: 204 });
};

const src = join(tmpdir(), `proxy-${Date.now()}.ts`);
writeFileSync(src, readFileSync(new URL("./trakt-proxy-deno.ts", import.meta.url), "utf8"));
await import(pathToFileURL(src).href);

const call = (path, body, ip = "1.2.3.4", method = "POST") =>
  handler(new Request(`https://proxy.test${path}`, { method, body }), { remoteAddr: { hostname: ip } });
let failed = 0;
const check = (name, cond) => { console.log(`${cond ? "ok  " : "FAIL"} ${name}`); if (!cond) failed++; };

let r = await call("/report", JSON.stringify({ title: "Debug Report — v7.26 — Pixel @everyone", report: "line1\nline2" }));
check("a report is forwarded (200)", r.status === 200 && posted.length === 1);
check("it goes to the webhook from the environment", posted[0]?.url === env.DISCORD_WEBHOOK);
const form = posted[0]?.init?.body;
const payload = JSON.parse(form.get("payload_json"));
check("mentions are disabled", Array.isArray(payload.allowed_mentions?.parse) && payload.allowed_mentions.parse.length === 0);
check("the report is attached as a file", (await form.get("files[0]").text()) === "line1\nline2");
check("the webhook is never echoed back", !(await r.text()).includes("discord"));

check("bad JSON is 400", (await call("/report", "{nope", "5.5.5.5")).status === 400);
check("a JSON null body is 400", (await call("/report", "null", "5.5.5.8")).status === 400);
check("a JSON number body is 400", (await call("/report", "42", "5.5.5.9")).status === 400);
check("missing report is 400", (await call("/report", JSON.stringify({ title: "x" }), "5.5.5.6")).status === 400);
check("oversized report is 413", (await call("/report", JSON.stringify({ title: "x", report: "a".repeat(1_100_000) }), "5.5.5.7")).status === 413);

for (let i = 0; i < 4; i++) await call("/report", JSON.stringify({ title: "t", report: "r" }), "9.9.9.9");
check("5th report from one address in 10 min is accepted", (await call("/report", JSON.stringify({ title: "t", report: "r" }), "9.9.9.9")).status === 200);
check("6th is refused (429)", (await call("/report", JSON.stringify({ title: "t", report: "r" }), "9.9.9.9")).status === 429);
check("another address is unaffected", (await call("/report", JSON.stringify({ title: "t", report: "r" }), "8.8.8.8")).status === 200);

{
  // Twenty at once from one address: only the first five get through.
  const before = posted.length;
  const results = await Promise.all(Array.from({ length: 20 }, () =>
    call("/report", JSON.stringify({ title: "t", report: "r" }), "6.6.6.6")));
  check("simultaneous reports: 5 forwarded", posted.length - before === 5);
  check("simultaneous reports: 15 refused (429)", results.filter((x) => x.status === 429).length === 15);
}
{
  // A large body with no Content-Length is cut off once it passes the cap, not read to the end.
  let pulled = 0;
  const chunk = new Uint8Array(64 * 1024).fill(97);
  const stream = new ReadableStream({
    pull(controller) {
      if (pulled >= 4 * 1024 * 1024) { controller.close(); return; }
      pulled += chunk.byteLength;
      controller.enqueue(chunk);
    },
  });
  const res = await handler(
    new Request("https://proxy.test/report", { method: "POST", body: stream, duplex: "half" }),
    { remoteAddr: { hostname: "4.4.4.4" } });
  check("a streamed oversized body is 413", res.status === 413);
  check("and is not read to the end", pulled < 2 * 1024 * 1024);
  const declared = await handler(
    new Request("https://proxy.test/report", { method: "POST", body: "{}", headers: { "content-length": "50000000" } }),
    { remoteAddr: { hostname: "4.4.4.5" } });
  check("a declared oversized body is 413", declared.status === 413);
}

delete env.DISCORD_WEBHOOK;
check("no webhook configured is 503", (await call("/report", JSON.stringify({ title: "t", report: "r" }), "7.7.7.7")).status === 503);
const dbg = await (await call("/debug", undefined, "1.1.1.1", "GET")).json();
check("/debug reports configuration without the value", dbg.reportsConfigured === false && !JSON.stringify(dbg).includes("discord"));
check("trakt routes still 404 for unknown paths", (await call("/nope", "{}")).status === 404);

console.log(failed ? `${failed} failed` : "proxy tests passed");
process.exitCode = failed ? 1 : 0;
