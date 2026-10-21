# Playback handoff (phone ↔ TV) — audit and design

Feature branch `feature/playback-handoff`, based on `1395b69` (v7.63, versionCode 601).

## 1. What already exists (audit, 2026-10-21)

| Area | Finding |
|---|---|
| Remote control | `remote/RemoteControlServer` — TV only, started by `TvHomeActivity`, plain HTTP on port 9481, announced over NSD as `_mktvremote._tcp.` (still accurate). The phone side is `ui/remote/RemoteActivity`. |
| Pairing | ECDH P-256 with a hash commitment from the phone, then a 6-digit numeric comparison accepted on the TV with its own remote (Bluetooth-style numeric comparison). 2-minute window, one pairing at a time, hour-long lockout after 10 refusals. Both sides derive `token = SHA-256(ECDH secret ‖ "MKTV remote")`; the token never crosses the network. |
| Request auth | Each command carries `X-MKTV-Time` and `X-MKTV-Sig = HMAC-SHA256(token, method\npath\ntime\nbody)`; ±2 min window; seen signatures are refused (replay). Responses are not authenticated. Bodies are not encrypted. |
| Identity | The TV keeps an anonymous set of tokens (`SharedPreferences "remote_control"`): no device names, no per-device revocation. The phone keeps one TV (`"remote_phone"`: name, host, port, token). Tokens are stored in plain SharedPreferences. |
| One-way | Only the TV listens. A phone cannot receive anything. |
| Cloud "handoff" | `sync/PlaybackHandoffManager` writes the live channel to Firestore for a cold-launch "continue watching" banner. Untouched; this feature does not use the cloud. |
| Cast | Chromecast proxy (`cast/*`) and the QR-keyed CastBox relay (`sync/CastBox`). Untouched. |
| Player | One `PlayerActivity` for phone and TV (standard launch mode, no `onNewIntent`). It is opened with `stream_url` + identity extras by `HomeActivity`/`TvHomeActivity.openPlayer`, `VodDetailActivity`, `MergedVodDetailActivity`, `SeriesDetailActivity`. Provider slot: `server_index` (-1 primary, 0.. extra providers), `stream_id` / `merged_stream_id`. No playback-speed control. |
| Progress | Local Room rows (`vod_streams`, `merged_vod`, `episode_watched`). The opt-in favorites sync (`SyncManager`) merges progress by keeping the further-along value, so an older position from the source can't overwrite a newer one. |
| SDK | targetSdk 35, minSdk 25. `ACCESS_LOCAL_NETWORK` (API 37 targets) is not needed and is not requested. NSD works without extra permissions at this target. `POST_NOTIFICATIONS` is already declared. |

## 2. Design

**Not casting.** Only a structured descriptor crosses the network. Each device resolves the content in its own catalog and plays it with its own provider and its own `PlayerActivity`.

**Pairing (reused, extended).** The existing ECDH + numeric-comparison pairing is kept unchanged. After pairing (or for any phone already paired), the phone sends an authenticated `hello` with its device id, name, type and receive port. The TV attaches that identity to the token that authenticated it. Both sides keep a peer registry (id, name, type, last address, last seen). Each peer's secret is sealed with an Android Keystore AES key. Either side can forget a peer; the TV then also drops the remote-control token. The legacy plaintext token stores stay as they are, so the existing remote keeps working.

**Transport.** Handoff messages use paths under `/handoff/` on the existing TV server, and on a small phone-side receiver (port 9482, no NSD advertising). Bodies are an AEAD envelope:

- key = HKDF-SHA256(token, salt "MKTV handoff v1", info "key <from>><to>"), one per direction;
- AES-256-GCM, random 96-bit nonce;
- AAD = protocol label, method, path, from, to, time, and for a reply, the request's nonce.

This gives confidentiality, integrity and sender authentication with standard JCA primitives (no custom cipher), and binds each reply to its request. The receiver rejects messages that are:
- outside a ±2 min time window,
- carrying a nonce it has already seen,
- over 8 KB,
- from an unknown or forgotten sender.

Discovery is never authorization: an NSD answer only gives an address, and trust comes only from opening the envelope.

**Descriptor.** It carries:
- schema version, transaction id, content type (`movie`, `episode`, `live`);
- a provider tag = HMAC(token, normalized server ‖ username): only the paired peer can compare it, and it reveals nothing else;
- the provider's display nickname, ids, title, season and episode numbers, container extension;
- position, duration and playing state;
- optional speed, audio and subtitle languages.

It never carries URLs, credentials or hosts. Every string is checked against strict patterns, and anything that looks like a URL is refused.

**Resolution on the receiver.**
1. The provider is matched by tag.
2. If none matches, the user can map the sender's provider to a local one. The mapping is remembered per peer.
3. With an exact provider match, ids are authoritative (episodes are checked against the series' episode list).
4. With a mapped provider, an id is accepted only if the title also agrees. Otherwise the receiver searches by title: one match plays, several make the user choose, none fails with a clear message.

**Transaction.** On the source the states are IDLE → REQUESTING → AWAITING_APPROVAL → PREPARING → TRANSFERRED, or FAILED / CANCELLED / TIMED_OUT.
- The source keeps playing throughout. It polls the receiver about once a second, sending its current position.
- It pauses only after the receiver reports that its player is really playing.
- Failure at any point leaves the source playing, so there is no state to restore.

The receiver deduplicates by transaction id and handles one incoming handoff at a time. It refuses an incoming offer while it is itself sending.

**Approval.** Approval is always asked; there is no auto-accept.
- **TV:** the prompt appears if MKTV is in front; otherwise the sender is told to open MKTV on the TV.
- **Phone:** the prompt appears in the app if it is in front; otherwise a notification opens it (no background activity starts). If notifications are off, the sender is told to open MKTV on the phone.
  - In practice a phone receives only while MKTV is open or was just put away. On current Android a cached (backgrounded) app gets no network traffic, so after a short while the TV sees the phone as "Not reachable (open MKTV on the phone)". Receiving in the background would need a foreground service (deferred).
- **Already playing:** if something is already playing, the prompt says it will be replaced.

**Position.** The receiver starts at the last reported position plus the time since that report (only if the source was playing, capped), clamped inside the receiver's own duration. Live always starts at the live edge.

## 3. Out of scope (deferred)

- Recordings and local files: they stay on the device that has them.
- Catch-up replays and behind-live timeshift offsets: a live handoff always starts at the live edge.
- Handoff while MKTV is closed on the receiver. This would need a foreground service or a push channel.
- Cloud handoff across networks.
- Cross-device progress sync beyond the existing opt-in sync.

## 4. Verification (2026-10-21)

All tests ran on emulators: a TV (Android TV, API 25) and a phone (API 35). They were joined through host port redirects, and both used a fake Xtream panel. The physical Shield was not touched. The Pixel was not used for handoff.

| Test | Result |
|---|---|
| Pairing (existing ECDH + 6-digit comparison), then hello on both sides | Both registries hold each other's id. Secrets are stored as Keystore ciphertext. |
| A: movie, phone → TV | The TV prompt resolved the title in its own catalog. It started at the phone's live position (42:27 shown, 42:50 when the screenshot was taken). The phone paused about 2.9 s after acceptance. |
| B: episode, TV → phone | The exact episode was resolved through the series' episode list. Both sides were within a few seconds (30:35 on the phone vs 30:3x on the TV). The TV paused about 5.7 s after acceptance. No second resume question. |
| C: live channel, phone → TV | The same channel opened at the live edge. The phone paused about 2.7 s after acceptance. |
| D: movie missing on the TV | The phone shows "This movie isn't in this device's catalog. Still playing here." No prompt appeared on the TV. |
| E: unpaired / forgotten | A forged envelope gets `{"error":"refused"}`. Forget on the phone cleared both registries, the phone remote's TV, and the TV's remote token. |
| Release build (R8) | Paired, sent a movie, TV played, phone paused. |
