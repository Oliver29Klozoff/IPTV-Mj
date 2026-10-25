# MKTV device test checklist (Stage 3B)

Phone = Pixel (touch, portrait + landscape). Shield = NVIDIA Shield (D-pad). Mark each item
pass/fail with build, device and date. Leave measurements blank unless actually measured.

## Custom Home — phone (touch)
- [ ] Open Customize Home; reorder, hide and restore a section; changes survive app restart.
- [ ] Same in landscape (car box is always landscape).
- [ ] Tap a card in each section opens the right channel/movie/episode.

## Custom Home — Shield (D-pad)
- [ ] Every section and the Customize screen reachable with D-pad only; focus always visible.
- [ ] Move a section up/down with D-pad; BACK leaves without losing focus position.

## Launcher shortcuts and Watch Next (Shield)
- [ ] Settings → Quick Actions → Android TV Home Integration on; "Show launcher rows" prompts.
- [ ] Part-watch a movie → appears in Play Next within ~30 s → resumes near saved position.
- [ ] Finish an episode → next episode card appears once per series → opens that S/E.
- [ ] Favorites, Recently Watched, Recent Recordings rows open the right channel/recording.
- [ ] Delete a recording / unfavorite a channel → card disappears; no duplicates after restart.
- [ ] Integration off → MKTV cards removed; MKTV home unchanged.
- [ ] Known: cards show the MKTV icon when artwork is provider-hosted (by design).

## Live / VOD playback and rapid switching
- [ ] Live channel starts; time to first frame: ____ ms (unverified).
- [ ] 10 rapid channel-ups: no crash, no wrong channel left playing; zap time: ____ ms.
- [ ] VOD movie start, seek, resume.

## Multi-View
- [ ] 1×2 opens; mixed-provider tiles; exclusive audio follows focus; saved layout restores.
- [ ] Kill one tile's stream → tile recovers without affecting the other.

## Thumbnail scrubbing
- [ ] Scrub a provider MKV and an MP4: previews appear; seek lands on the previewed frame.

## Quick Peek
- [ ] Open mini guide during playback (MENU on Shield); select a channel; playback switches.

## Phone ↔ Shield handoff
- [ ] Pair; send phone → Shield and Shield → phone (phone app open); position within a few seconds.

## Recording and EPG recovery
- [ ] Series rule (new episodes only) schedules the right airings after a guide refresh.
- [ ] Reboot device before a scheduled recording → it still starts on time.
- [ ] Force-stop during a recording → recovery resumes or marks it correctly.
- [ ] EPG Health: refresh guide; stale/missing counts sensible.
