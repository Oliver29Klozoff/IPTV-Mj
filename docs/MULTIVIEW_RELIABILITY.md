# Multi-View reliability (Prompt 10)

## Using different providers

Each tile independently stores its provider index and stream ID. Choose a primary-provider
channel for one tile and a secondary-provider channel for another. The picker identifies the
provider beside each result. Search includes enabled providers; an empty search shows favorites
in the grid. The PiP picker includes primary channels and up to 100 initial secondary results;
search finds additional secondary channels.

The Home Multi-View action opens a standard **1×2** grid (one row, two equal columns),
including on phones. Four tiles and named saved layouts remain selectable. Returning from
the background restores the current arrangement; a fresh Home launch starts in 1×2 rather
than automatically opening the last named layout. Device limits can still reduce this to one tile.

The existing two-channel PiP screen keeps its remote shortcuts and adds a **Layouts** button
(also remote Menu). This opens the existing grid with the selected channels. In the grid,
use **Tile** or remote Menu to change a channel, choose audio, enlarge, swap positions, retry,
or save/open a named layout. OK on the selected tile enlarges it; Back restores the grid.
Enlarging suspends hidden tiles and resumes them when the grid returns, avoiding hidden
decoders being mistaken for frozen pictures. Swapping positions keeps the same players.

Layouts preserve arrangement, provider/channel pairs, active tile, and mute state. Missing or
disabled providers leave an unavailable tile rather than silently selecting an unrelated feed.
Existing two/four/six-tile saved data remains readable; restoration clamps to current limits.

## Recovery and limits

- Each tile owns one cancellable source-resolution/recovery operation. Late obsolete results
  cannot reopen an old channel. Channel changes reuse the tile's player.
- Errors, ended streams, 12 seconds of buffering, or eight seconds of frozen samples trigger
  recovery for that tile. Delays are 1, 3, 6, 12, 20, and 30 seconds, then recovery stops.
  Thirty seconds of healthy playback resets the retry count. Manual Retry starts a new attempt.
- Source resolution has a 15-second timeout. Known provider admission refusals stop immediately.
- Automatic backups require the Smart Channels setting and an eligible approved group member.
  Provider admission applies to backups as well as explicit selections.
- Audio handoff mutes all players before unmuting one. Backgrounding cancels pending recovery;
  the grid releases all players on stop and restores its selections on return.
- Conservative device ceilings are one, two, or four tiles using low-RAM status, memory class,
  and advertised AVC/HEVC decoder instance limits. Grid decoder initialization failures or low
  memory reduce four tiles to two, then one. Capability values are only upper-bound hints:
  https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#getMaxSupportedInstances()
- Video selection is limited to 1080p / 8 Mbps per tile; tracks exceeding those constraints are
  unsupported for Multi-View. There is no claim of four simultaneous 4K streams.
- Each player targets 8 MiB of compressed buffers, with a 3–10 second buffer window. This is
  a buffer target, **not a measured total RAM ceiling**; decoders, surfaces, audio, and app state
  add memory. Two/four players imply 16/32 MiB of target compressed buffers alone.
- Admission is serialized per screen and counted separately per provider. Remote active counts
  and local reservations constrain slots. Unknown capacity permits at most one local feed per
  provider. Provider-reported counts may lag and do not guarantee available capacity; there are
  no speed probes or speculative feeds.

## Verification and remaining validation

Focused tests cover stale non-cooperative URL responses, player reuse, isolated recovery,
retry exhaustion, manual retry, pause/resume/release, known capacity refusal, audio overlap,
mixed-provider persistence, simultaneous admissions, and obsolete layout admission responses.
Existing freeze detection and provider-removal layout tests remain in place.

Core implementation validation on October 23, 2026 (before the subsequent 1×2 default-layout adjustment):

- `:app:testDebugUnitTest`: 719 tests, zero failures/errors/skips, including 10 new tests.
- `:app:assembleDebug` and `:app:assembleRelease`: successful for that implementation.
- Release vital lint and signing validation completed as build dependencies.
- `git diff --check`: clean. Log: `build/multiview-final-validation.log`.
- Branch `feature/multiview-reliability`, baseline HEAD
  `35cfebca3529218b71ec70c9dc5ce81edcc7e8bd`.
  The implementation initially retained application version 7.69 (607); Media3 remains 1.11.1.

The subsequent standard 1×2 layout adjustment passed `:app:assembleDebug` in 1m49s;
log: `build/multiview-standard-layout.log`.

Changed files: `HomeActivity.kt`, `MosaicActivity.kt`, `MosaicLayouts.kt`, `MultiViewResources.kt`,
`MultiViewActivity.kt`, `TileHealer.kt`, `activity_mosaic.xml`, `activity_multi_view.xml`,
`TileHealerTest.kt`, and this report.

No Android device was connected during this work (`adb devices -l` returned an empty list).
TV/car-box emulator profiles exist but were not installed or used for playback. Actual two/four
stream playback, RAM/CPU/network consumption, codec occupancy, and recovery latency remain
unmeasured. Test-scheduler timings establish policy behavior, not real stream recovery speed.
Shield, phone, car-box, HLS/MPEG-TS, and low-memory device verification remain required.

The required Codex CLI review was attempted. It failed because the configured `gpt-6.1-sol`
model is unsupported with the CLI's ChatGPT authentication; no completed reviewer findings
were produced. Logs: `build/multiview-review-run.log` (ignored local build output).

No provider credentials or stream URLs are stored in layouts or newly added recovery logs.
Favorites, recording, Timeshift, and EPG data were not changed.
No physical-device installation was performed.

## Authorized release

The user subsequently requested publishing and release. The release target is v7.70 (608),
including the standard 1×2 default. Release metadata and changelog updates are included under
that authorization. Final test/build evidence is in `build/multiview-release-validation.log`;
the production signature, APK identity, checksum, and clean-commit binding are checked through
the repository's release verification and publication scripts before publication.
