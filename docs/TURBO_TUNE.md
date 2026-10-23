# Turbo Tune

Baseline: `a91e69d13bc9742d2fec95a81876ebb4bb269cec` (Prompt 8 / v7.68).
Media3 remains 1.11.1. Release v7.69 uses version code 607; dependencies are unchanged.

## Behavior

- Off keeps the configured zap delay and original startup buffer. It opens no speculative feeds.
- Automatic coalesces presses within 100 ms (or the user's longer delay), reuses the existing
  player, and caps the ordinary direct-live initial buffer at 500 ms. Rebuffer thresholds,
  Extra Buffering, Sports Mode, VOD and Timeshift profiles remain unchanged.
- Performance adds at most one of the two neighboring channels on the playing provider.
  It requires a fresh, known spare connection, HTTP(S) TS/HLS, adequate memory and direct live
  playback. It retains the existing two-second media preload, 8 MiB preload allocation target,
  30-second expiry, account-busy protection and shared player/preload thread. The allocation
  target is not a whole-process RAM ceiling. Provider counts can change concurrently.
- Neighbor selection reads two resident rows, rather than allocating a copy of the whole
  playlist. Frequently watched channels are deliberately not opened speculatively: favorites,
  Smart Channel preference and recall continue to resolve through the existing repository.
- Selection name and cached logo appear before resolution. An uncached logo is omitted to
  avoid network requests during surfing. EPG is not needed to start the stream.
- URL resolution finishes before playback identity changes. Cancellation is rechecked even
  when a resolver ignores it. Existing retries and approved Smart Channel failover remain.

These startup buffer values are configuration changes, **not measured tune-time improvements**.
TS still needs PAT/PMT and a decodable keyframe. HLS still needs its manifest, segments and
keyframe. A source returned by DefaultPreloadManager may not contain loaded samples yet.
No prepared MediaItem is counted as first video or usable audio.

## Device measurements

Actual before/after tune latency, failed-tune rate and RAM/CPU/network impact have not yet
been measured. A Pixel 11 Pro XL was discovered; no test installation or playback run has
been performed. Shield, car box and low-memory hardware have not been exercised.

The local `TurboTune` log tag emits monotonic selection-to-first-video, selection-to-audio
position advancement, READY, buffering and errors, keyed by tune number. It excludes URLs,
channel names and credentials. Audio advancement is an output-timing proxy, not a measurement
of sound from the speaker. READY is distinct from first video. Cancelled selections must be
excluded from the failed-tune denominator. Treat a selection without video after a predefined
timeout as a failed tune, even if no player error was raised.

For a controlled run, use the same channel list, provider, network and device; restart playback
after each mode change. Compare Off and Automatic, then Performance on a spare-slot account.
Capture `adb logcat -v monotonic -s TurboTune:D`, process PSS with `adb shell dumpsys meminfo`,
CPU with Perfetto/top and per-UID network bytes. Measure idle playback separately from surfing.
Run at least 30 switches per stream type/provider and report median/p95 plus failures, including
slow/unavailable feeds, repeated CH+/CH−, direction reversal, recall, 10,000+ channel lists,
Timeshift, background/resume, one-slot/unknown-slot accounts and simulated memory pressure.
Check final channel identity and recording continuity, not only timing logs.

No Shield installation is authorized by this document.

## Local verification after takeover

- `assembleDebug assembleRelease testDebugUnitTest` completed successfully against the
  settled source tree. See `build/turbo-release-evidence/turbo-takeover-build.log` for the local run.
- Recall now uses the same cancellation guard as channel up/down, and merged recall
  matches both provider and stream ID.
- Timing logs explicitly mark selections superseded or cancelled before resolution.
  The regression test includes a resolver that catches cancellation and returns anyway.
- The required review script was attempted, but its configured CLI model was rejected
  as unsupported for the signed-in account. There is no completed automated review.
- Device tune latency and resource measurements remain pending; successful builds and
  unit tests do not establish a playback speed improvement.

## Media3 references

- https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager
- https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager/manage-play

The checked-in ZapPreloader already uses the installed DefaultPreloadManager builder and
specifiedRangeLoaded API; no second ExoPlayer is created for preparation.
