# IPTV App - Changelog

## v5.54 - 2026-08-03
- **Added**: Favorites drag-reorder is back — long-press a favorite, tap "Reorder Favorites,"
  drag the handles to arrange them, then tap Done. Works across primary and other-provider
  favorites mixed together in one list (only available from the unfiltered "All" genre view).
- **Added**: bulk-select now offers a Hide option alongside Favorite, on Live, the combined Live
  tab, and Providers channels — previously only single channels could be hidden one at a time.
  Hiding a Providers/other-provider channel is new; only your main provider's channels could be
  hidden before.
- **Added**: channel rows now show the category a merged/other-provider channel came from, next
  to the provider name (e.g. "LEPTIS · SPORTS"), on Live, Favorites, and Providers.
- **Changed**: TV Favorites goes straight to your full favorites list with genre filter chips at
  the top, matching the phone — no more picking a folder/genre tile first.
- **Fixed**: switching categories for a merged/other-provider channel list could lag noticeably
  for large providers — a missing database index meant every tap re-scanned the whole table.

## v5.53 - 2026-08-01
- **Fixed**: "USA Only Channels" could reduce a provider's entire Live category list to zero —
  the filter only recognized an exact "US" tag (e.g. "US | ESPN"), but some providers tag their
  USA content as "USA" instead (e.g. "AM | USA SPORTS"), so every one of that provider's
  categories was silently filtered out. Confirmed and fixed via live testing after a newly added
  provider's channels refreshed successfully but showed zero categories in the Providers tab.
- **Added**: global search now also matches EPG program titles/descriptions, not just channel/
  movie/show names — a result shows "Airing: <program>" and jumps straight to that channel.
- **Added**: a real HDR/Dolby Vision badge in the player, based on the actual decoded video
  stream rather than guessing from the channel's name (which the existing SD/HD/FHD/4K tag
  already did and still does for list views before playback starts).

## v5.52 - 2026-07-31
- **Fixed**: v5.51 fixed the launch crash on Android 7.1-8.1 devices but missed the same
  `getLongVersionCode()` API-28 crash inside Settings (phone and TV) — tapping Settings on an
  API 25-27 device (e.g. Fire TV Stick 4K) crashed immediately. Every remaining occurrence of the
  unguarded call across the app has been swept and fixed. Confirmed fixed via live install.

## v5.51 - 2026-07-31
- **Fixed**: a real crash on launch on Android 7.1-8.1 devices (API 25-27, including the Fire TV
  Stick 4K v5.50 was meant to fix) — `PackageInfo.getLongVersionCode()` requires API 28 and throws
  `NoSuchMethodError` on older devices, which a plain try/catch didn't catch since it's an Error,
  not an Exception. Confirmed fixed via live install on a Fire TV Stick 4K.

## v5.50 - 2026-07-31
- **Fixed**: lowered minimum Android version from 8.0 to 7.1 (API 26 → 25) — some older Fire TV
  Stick 4K units (Fire OS 6 / Android 7.1) couldn't install the app at all ("problem parsing the
  package"). Every API-26-specific code path was already guarded with a version check, so this
  only expands device support, no behavior changes on newer devices.
- **Fixed**: a favorited channel restored via cloud sync kept showing whatever provider nickname
  was cached at the time it was last refreshed, even after the provider was later renamed or
  swapped between primary/secondary — now resolved live so it always reflects the provider's
  current name.
- **Added**: the primary provider's nickname now shows underneath blue-star favorites in the
  Favorites tab, matching the tag other-provider (merged) favorites already showed.

## v5.49 - 2026-07-31
- **Fixed**: merged/secondary-provider EPG matching silently lost data for any channel that
  shared its network's EPG id with another channel (e.g. "USA Network HD/WEST/EAST" or
  "TNT HD/SD" all use one shared id) — only the last channel processed for that id ever got
  matched, so every other variant showed no program data even on an otherwise-successful guide
  fetch. Confirmed via live testing: raised the match rate from 104/213 to 134/213 favorited
  channels on one account.
- **Added**: a provider label (e.g. "Primary" or the merged provider's nickname) on each row in
  the phone's Grid/Timeline Guide view, so it's clear at a glance which provider's EPG populated
  that row.
- **Added**: a manual refresh button on the Guide tab's "What's On/Grid" row (phone, both
  portrait and landscape) that forces a fresh EPG fetch, matching the Grid/Timeline view's
  existing refresh button.
- **Added**: bulk-select on the Favorites tab — long-press a channel, choose "Select (bulk remove
  from favorites)", then tap more channels before removing them all at once. Known issue: removing
  a mix of primary and other-provider favorites in one batch can leave the other-provider channels
  still showing until the tab is reopened.

## v5.48 - 2026-07-30
- **Fixed**: a real crash (SQLiteException: too many SQL variables) when the Guide tab tried to
  load EPG for an account with a large number of favorited channels/categories — the query
  passed every stream ID into one unbounded database call instead of chunking it, which could
  exceed SQLite's parameter limit and crash the entire app. Confirmed via a real crash log.
- **Added** (TV): the Guide panel now includes favorited merged/secondary-provider channels and
  their program data, matching what the phone's full-screen Guide already did — previously TV's
  Guide only ever showed the primary provider.
- **Fixed**: a merged provider's own custom EPG URL (Settings → Providers → Edit → EPG URL) was
  never actually used when fetching that provider's guide data — it's now tried first, falling
  back to the provider's default guide path.
- **Fixed**: the merged-provider "invalid response" error message now explains that network
  interference or a flaky provider server is a common cause, not just an account problem.
- **Changed**: the merged-provider guide fetch's per-server time budget raised from 15 to 45
  seconds, giving a large catalog on a slower connection a real chance to finish.

## v5.47 - 2026-07-30
- **Fixed**: a real out-of-memory crash from parsing a large XMLTV EPG feed for a merged
  provider — when more than one provider's guide data refreshed at the same time, a shared
  (non-thread-safe) date parser could get corrupted by the concurrent access and spiral into a
  runaway allocation loop until the app ran out of memory. Each fetch now gets its own parser
  instance.
- **Added**: the mini player now respects the Subtitles setting (Settings → Stream) — it was
  never applied there before, so a channel with subtitle tracks played without them in the
  mini player regardless of your subtitle language/on-off preference, even though fullscreen
  playback already honored it.

## v5.46 - 2026-07-30
- **Fixed** (phone, portrait): the search field's clear (X) button was floating outside the
  search box, off to the left near the menu button, instead of inside it — same layout mistake
  just fixed in landscape. It now sits inside the search box, right-aligned, in both
  orientations.

## v5.45 - 2026-07-29
- **Changed** (phone, landscape): the search field's clear (X) button now sits inside the
  search box on the right edge, matching how it's positioned everywhere else — it was
  floating outside the box after being added in the previous release.

## v5.44 - 2026-07-29
- **Fixed** (phone, landscape): the search field's clear (X) button — already present in
  portrait — was a permanently-hidden placeholder in landscape, so there was no way to clear
  the search text and dismiss the keyboard/cursor in one tap. Now a real, working button next
  to the search field, matching portrait's behavior.

## v5.43 - 2026-07-29
- **Fixed**: a crash right after launch if a favorited channel belonged to a currently-disabled
  provider — the background favorites health-check tried to build a stream URL for it and
  crashed the whole app instead of just marking it unhealthy. This became newly reachable after
  v5.41 stopped deleting a disabled provider's favorites (which previously masked it).
- **Added** (Mosaic/Multi-View): a 2-tile (1×2) layout option, in addition to the existing 2×2
  and 2×3 grids — tap the grid-size button to cycle through all three.

## v5.42 - 2026-07-29
- **Fixed**: Backup/Restore (manual and automatic, phone and TV) didn't save whether a provider
  was enabled or disabled — restoring a backup silently re-enabled every previously-disabled
  provider. Backups now correctly preserve and restore that state.

## v5.41 - 2026-07-29
- **Fixed**: enabling/disabling ANY configured provider (Settings → Providers, phone and TV)
  was wiping merged favorites and folder assignments for EVERY configured provider, not just
  the one being toggled — a disabled provider's favorites could never come back even after
  re-enabling it, since the underlying data needed to restore them had already been deleted.
  Disabling a provider no longer touches any saved data at all; it's just hidden from
  Favorites/browsing until re-enabled, exactly as it was before.

## v5.40 - 2026-07-29
- **Fixed**: a merged provider with a large channel catalog could time out fetching channels on
  a slower connection (a VPN, or a weak signal) even though the login itself succeeded — the
  per-provider fetch budget was a fixed 15 seconds, confirmed too tight for this case on a real
  device. Raised to 45 seconds.
- **Changed**: the merged-provider "invalid response" error message now explains that this is
  often caused by network interference (a router/ISP blocking or filtering the connection, or a
  weak/unstable signal corrupting the response) rather than always being a provider account
  problem — confirmed via a real case where the same provider worked fine on cellular and on
  another device's network, but failed only on one specific Wi-Fi.

## v5.39 - 2026-07-29
- **Fixed**: a merged/secondary provider with a large channel catalog could get stuck refreshing
  forever with channels never appearing — the whole catalog was written to the database as one
  giant transaction, and on slower storage that single commit could take minutes, blocking every
  other database read (including the one populating the channel list) the entire time. Now
  written in chunks, same fix already applied to VOD/series sync for the same reason.
- **Fixed**: a merged-provider refresh failure (bad/expired login, provider returning an invalid
  response instead of channel data, a timeout) was silently swallowed everywhere except one
  specific Settings button — every refresh path now shows the real reason instead of leaving
  stale/empty channels with no explanation. A garbled/non-JSON provider response now reports
  clearly as a likely expired or disabled login instead of a raw parser error.

## v5.38 - 2026-07-29
- **Added**: a recording that fails now posts a system notification with the failure reason
  (e.g. provider connection-limit rejection) — previously a failed recording was completely
  silent unless you happened to open the Recordings screen. Tapping the notification opens
  Recordings.

## v5.37 - 2026-07-29
- **Added** (Settings → Display, phone): Auto-Clear Continue Watching — Never / 7 / 30 / 90
  days. An abandoned in-progress movie or show is automatically cleared from Continue Watching
  once it's been untouched that long, the same way recordings already auto-delete. It reappears
  automatically if you actually resume it later.

## v5.36 - 2026-07-28
- **Changed** (phone Settings): Subtitle Style (size, offset, bold, colors, outline) moved out
  of the Display section into Stream & EPG's "Audio, Subtitles & Language" dropdown, alongside
  the existing subtitle language setting — both are subtitle-related, so they now live in one
  place instead of two.

## v5.35 - 2026-07-28
- **Fixed**: Guide "Remind Me" notifications fired at the exact moment a program started —
  by the time you saw it, the show had already begun, giving zero time to actually switch
  over. Now fires ahead of time instead.
- **Added** (Settings → EPG, phone and TV): Remind Me Lead Time — how many minutes before a
  program starts the reminder notification fires (At start time / 1 / 5 / 10 / 15 min before).
  Defaults to 5 minutes.

## v5.34 - 2026-07-28
- **Added** (Settings → Stream, phone and TV): Live Reconnect Speed — Aggressive/Normal/Patient,
  controls how fast a live channel keeps retrying and how long it waits between attempts after a
  dropped or stalled connection. Normal (default) matches the previous fixed behavior exactly;
  Patient is useful for slower or less reliable providers.

## v5.33 - 2026-07-28
- **Added**: recordings can now be cast to Chromecast, same as live channels and movies — the
  local file is served over the phone/TV's own network to the receiver, with seeking support.
- **Added**: a finished recording can now be added to your Trakt collection (marks it "owned",
  separate from watched-history) — only shown once Trakt is connected, using the program title
  captured from the guide at record time.

## v5.32 - 2026-07-28
- **Added**: scheduling a recording now also warns if you're currently watching a live channel
  on the same provider — the same connection-limit collision risk as two overlapping
  recordings, previously only checked against other scheduled recordings.
- **Added**: home screen widget can now show Continue Watching (in-progress movies and shows)
  instead of live channels — pick the mode in the widget's configure screen (long-press it,
  tap Edit). Tapping a movie resumes it directly; tapping a show opens its episode picker.
- **Changed**: tapping Play on a finished recording now plays it in-app (same player used for
  live TV and movies) instead of always opening an external video player — this also means
  recordings can now scrobble to Trakt, using the program title captured from the guide at
  record time. Opening with an external player is still available via Share.

## v5.31 - 2026-07-28
- **Fixed**: a manually-triggered "Quick Backup Now" could get silently deleted by the next
  weekly automatic backup once you had more than 5 backups total — they now use separate
  filenames so each has its own retention and can never prune the other.
- **Added**: "Manage Backups on This Device" now shows each backup's file size, whether it's
  Manual or Auto, and a total count/size in the title. Restoring a backup now shows a summary
  of what's actually in it (favorites, extra providers, in-progress movies/shows) before you
  commit, instead of a blind generic warning.

## v5.30 - 2026-07-28
- **Added**: a FAILED recording now shows why it failed instead of just "FAILED" — including
  specifically identifying when your provider rejected the connection because another stream
  (live viewing or another overlapping recording) was already using your account's connection
  limit.

## v5.29 - 2026-07-28
- **Added**: "Remove Padding" action on finished recordings (phone and TV) — trims the ~20
  seconds of pre-roll/post-roll buffer every recording captures. Re-encodes and replaces the
  file, so it takes roughly as long as the automatic post-recording compression does.

## v5.28 - 2026-07-28
- **Added**: Guide search now also matches airing/upcoming program titles, not just channel
  names — searching "NFL" now finds any channel currently airing something with NFL in the
  title, not just a channel literally named that. Already covered every configured provider.

## v5.27 - 2026-07-27
- **Added** (TV Settings → Stream): Channel Change Speed — Instant (default, unchanged), Fast,
  Medium, or Slow. Adds a short delay after the last D-pad channel-up/down press before actually
  switching, so rapid button-mashing settles on one channel instead of flickering through several.

## v5.26 - 2026-07-27
- **Added**: home screen widget is now configurable — choose up to 10 specific favorite channels
  to show instead of always the 10 most recent favorites. Re-add the widget or long-press it and
  tap "Edit" to configure an existing one.

## v5.25 - 2026-07-27
- **Fixed** (internal): the home screen widget kept its own separate, hand-maintained copy of
  the database migration list, which had already fallen behind the main app's — it hadn't broken
  anything yet, but the next schema change would have crashed the widget outright. Both now share
  a single migration list that can't drift apart again.

## v5.24 - 2026-07-27
- **Fixed**: tapping a channel in the home screen widget didn't actually open that channel — it
  just launched the app generically due to a mismatched intent key. Now jumps straight to the
  tapped channel, same as "Play This Channel" elsewhere in the app.
- **Fixed**: Picture-in-Picture always forced a 16:9 window regardless of the actual video,
  letterboxing 4:3 SD channels and other non-16:9 content. Now matches the real video shape.
- **Fixed**: app launch did a small synchronous disk read (for the AMOLED black setting) before
  drawing the first frame, on every single launch — now reads it without blocking startup.

## v5.23 - 2026-07-27
- **Added**: "Skip to Next Episode" button appears during the last minute of a series episode —
  jump straight to the next episode without waiting through the credits for the end-of-episode
  Up Next prompt.

## v5.22 - 2026-07-27
- **Added** (Settings → Stream & EPG): Autoplay Next Episode toggle — on by default. The Up Next
  prompt (10s countdown, cancelable) that already existed now also works across season
  boundaries, instead of stopping dead at the last episode of a season.

## v5.21 - 2026-07-27
- **Changed**: removed the embedded in-app trailer player, which didn't reliably play trailers.
  "Watch Trailer" on Movie/Series detail screens now opens the trailer directly in the YouTube
  app (or browser) instead.

## v5.20 - 2026-07-27
- **Fixed**: the "Watch on YouTube" fallback from v5.19 only appeared for two specific trailer
  error codes — other error codes (a generic player error, invalid request, etc.) still showed
  just a toast with no way to actually watch it. Now every trailer playback error shows the
  Watch on YouTube fallback.

## v5.19 - 2026-07-27
- **Fixed**: some trailers failed with "Video unavailable" in the embedded player (the uploader
  disabled embedded playback for that specific video — a YouTube-side restriction, not a bug).
  Now shows a clear message with a "Watch on YouTube" button instead of a dead black screen.

## v5.18 - 2026-07-27
- **Added**: "Watch Trailer" button on Movie and Series detail screens — plays the provider's
  YouTube trailer in an embedded player, when one is available for that title.
- **Fixed**: the Guide (EPG) screen silently showed a blank list on a fresh install with no
  network connection yet — now shows a clear "No guide data available" message instead.

## v5.17 - 2026-07-27
- **Added** (TV Settings): Provider Speed Test — tests every configured provider's latency at
  once, same feature the phone app already had. TV previously only had Provider Health, not
  Speed Test.

## v5.16 - 2026-07-27
- **Fixed**: Settings → "Check for Update" showed an empty "What's new" popup — it only tried to
  read the changelog as a JSON array, but it's actually published as a plain string. Now falls
  back correctly, same as the automatic update popup already did.

## v5.15 - 2026-07-27
- **Added**: a quality badge (SD/HD/FHD/4K) on channel tiles across Live, Favorites, Providers,
  Continue Watching, and Search — parsed from the channel name itself, so duplicate channels
  from different providers are easier to tell apart at a glance.

## v5.14 - 2026-07-27
- **Added** (Settings → Stream & EPG): Audio Passthrough Fallback — forces stereo audio instead
  of Dolby/DTS passthrough for TV boxes that produce no sound at all on channels/movies with
  surround audio when no receiver is connected. Off by default.
- **Added**: the player now reconnects immediately when the network changes (e.g. WiFi to
  cellular on phone) instead of waiting for the stream to error out or stall on its own.

## v5.13 - 2026-07-27
- **Fixed**: backups (manual and automatic) were missing several real settings — accent color,
  AMOLED black, external player choice, tunneled playback, DV7 fallback, extra buffering, silent
  self-update, crash reporting toggle, and the recording folder name/auto-delete setting. All are
  now included and restored, so a restore actually gets your setup back exactly as it was.

## v5.12 - 2026-07-26
- **Added** (Settings → Backup): a "what to include" picker before every manual backup — choose
  whether to include Favorites & folders, Watch history & resume progress, Extra providers &
  their favorites, and Subtitle style. Login and core settings are always included.
- **Fixed**: weekly automatic backups were silently missing extra-provider favorites and all
  VOD/series watch progress that manual backups already included — auto-backup now includes
  the same full set of data.

## v5.11 - 2026-07-26
- **Fixed** (Mosaic multi-view): the channel picker's search only ever listed primary-provider
  favorites — a favorite from a secondary/extra provider couldn't be put in a Mosaic tile at all.
  It now searches and plays channels from every configured provider.

## v5.10 - 2026-07-25
- **Changed** (History tab): long-press a Continue Watching movie or series to select it,
  tap/long-press others to add to the selection, then use the bulk-select bar to Select All or
  clear everything selected in one go — replaces the old single "Clear All" confirmation button.

## v5.9 - 2026-07-25
- **Added**: "Clear All Continue Watching" button on the History tab, alongside the existing
  long-press-to-remove.
- **Added** (Recordings): storage folder name is now customizable (Settings icon on the
  Recordings screen), and recordings can be set to auto-delete after 7/14/30/60/90 days.
- **Added**: automatic crash reporting (on by default, toggle in Settings → Sync → Diagnostics)
  so bugs can be found and fixed without needing a manual debug report.
- **Added**: Search All Providers — a new search icon in the top bar searches every configured
  provider (primary + all extra providers) at once across Live/Movies/Series, tagging each
  result with which provider it came from.

## v5.8 - 2026-07-25
- **Added**: long-press a Continue Watching movie or series on the History tab to remove it from
  the list without losing your resume position — it reappears automatically once you actually
  resume watching it.
- **Added** (Settings → Stream & EPG): Preferred Audio Language and Preferred Subtitle Language —
  when a stream offers multiple language tracks, automatically picks the one you chose instead of
  the stream's default. Depends on your provider tagging tracks with language info.

## v5.7 - 2026-07-25
- **Changed** (phone): reordered Settings tabs — Providers now follows Display, Backup and Sync
  moved above Updates, and Updates is now the last tab before Logout.

## v5.6 - 2026-07-25
- **Fixed**: History tab was silently broken — it only ever showed recently-watched channels;
  a dead code path tried to show in-progress movies but never actually rendered them, and
  in-progress series weren't tracked at all. Replaced with a unified tab showing three labeled
  sections: Recently Watched (channels), Continue Watching Movies, and Continue Watching Series,
  each live-updating and tappable to resume.

## v5.5 - 2026-07-25
- **Added** (TV): finished Providers D-pad parity — Movies and Series now have D-pad-reachable
  favorite stars and bulk-select (long-press to start, tap to toggle, second long-press for
  Select All/Deselect All), matching Channels. Movies/Series bulk-select hides selected titles
  (same as the phone app), reachable via the shared bulk-select bar's "Hide Selected" button.

## v5.4 - 2026-07-25
- **Added**: "★ Favorites" section header for Series too (primary and merged/Providers), same
  treatment Movies got in v5.3.
- **Added** (TV, in progress): Providers Channels now has bulk-select — long-press a channel to
  start selecting, D-pad-reachable "Select All" bar, same as primary channels already had.

## v5.3 - 2026-07-24
- **Added**: pinned "★ Favorites" entry at the top of the primary Movies category list, and a
  "★ Favorites" section header above your favorited titles in both primary and merged/Providers
  Movies lists.
- **Added**: merged/Providers Movies now has a Sort button (Rating, Year Newest/Oldest, Recently
  Added), matching the primary Movies tab — previously missing entirely.
- **Added**: "English Movies & Series Only" now also filters merged/Providers Movies and Series
  (categories and titles), not just the primary provider's.
- **Fixed**: tapping the pinned Movies favorites entry (or any category) could silently snap back
  to the first category a moment later — a background re-emission of the category list was
  re-triggering the auto-select-first-category logic on every refresh, not just on first entry.
- **Fixed**: merged Movies/Series refresh was timing out after 15 seconds even while genuinely
  still downloading a large catalog — raised to 60 seconds so it isn't cut off mid-fetch.
- **Fixed**: the Movies/Series sort button was easy to miss (unlabeled, same icon as the main
  menu button, and could render partially behind a phone's front-camera cutout) — now has a
  distinct icon, a "Sort" label, and sits clear of the camera cutout.
- **Changed**: video zoom now cycles through 2 modes (Best Fit, Zoom In) instead of 13 — the
  extra steps were rarely used and made the on-screen cycle tedious; Zoom In always visibly
  crops in regardless of the stream's reported aspect ratio.
- **Fixed**: Trakt "Sync Watched History" now shows matched shows as a tappable list that opens
  straight into that show's detail page, instead of just a summary count.
- Various TV interface improvements to Providers (Channels/Movies/Series mode row, D-pad
  reachability for favorite stars, bulk-select bar, cold-boot resume) — still a work in progress.

## v5.2 - 2026-07-23
- **Fixed**: merged/secondary-provider favorites (channels, movies, shows) could silently
  disappear after an update, even with Cloud Sync — favorites/folders for a provider whose
  channel list hadn't refreshed on this device yet were dropped instead of being retried, so
  only category picks (which don't need that data) survived a Sync Down. They're now queued
  and applied automatically as soon as that provider's channels/movies/shows are cached.
- **Fixed**: two overlapping merged-provider refreshes (e.g. a cold-start auto-refresh
  overlapping a manual refresh tap) could race each other and silently drop favorite state.
  Refreshes now wait for any already-in-progress refresh instead of running concurrently.
- **Added**: Settings' Provider Speed Test now checks every active provider (primary and all
  enabled merged providers), not just the primary, showing TCP ping and HTTP response time
  for each.
- **Added**: accent color picker now includes four named two-color gradient presets (Sunset,
  Ocean, Berry, Aurora) alongside the existing flat colors — shows as a real gradient on the
  tab indicator bar, with the rest of the UI using the gradient's first color same as a plain
  accent pick.

## v5.1 - 2026-07-23
- **Added**: merged/secondary-provider channels now get the same "channel changer" as the
  primary provider in fullscreen — D-pad up/down (or the on-screen prev/next zones on touch)
  zap through that provider's channel list within the same category, wrapping around, with
  the same channel-name/EPG popup.
- **Fixed**: right after installing an update, resuming the last-played channel could
  occasionally revert to a primary-provider channel instead of the merged/secondary-provider
  channel that was actually playing — a cold-start refresh of the merged-channel cache could
  briefly wipe and re-add that provider's rows at the exact moment the resume logic looked
  for it. It now retries for a few seconds instead of giving up on the first miss.

## v5.0 - 2026-07-23
- **Fixed**: casting a raw live .ts channel could leave its background connection/thread
  running after you stopped casting or switched channels, since nothing ever told the
  in-flight network read to actually stop. It now cancels cleanly the moment casting ends.

## v4.99 - 2026-07-23
- **Fixed**: casting a channel (Chromecast) showed the channel name but a black screen, for
  both primary and merged/secondary providers on some channels — any channel served as a raw,
  unsegmented live .ts stream (common on IPTV panels, not just this app) can't be handed to
  the Cast receiver directly; its video pipeline only understands segmented media (HLS/DASH/
  progressive MP4). The proxy now repackages a raw live .ts stream into real, properly
  packet-aligned HLS segments on the fly so casting works for those channels too.
- **Fixed**: a stale background update could force-show the Favorites genre-filter chip row
  ("All/Sports/Entertainment/...") on other tabs, most noticeably Providers, after switching
  tabs a few times.

## v4.98 - 2026-07-22
- **Fixed**: merged/secondary provider favorites (and other app data) could get silently
  reverted after installing an update — the app had Android's whole-device Auto Backup
  enabled with no exclusions, which some phones use to restore a stale snapshot of the
  entire app database right after a reinstall/update. Disabled it; the app already has its
  own dedicated cross-device sync for continuity.
- **Added**: Providers refresh (Live/Movies/Series) now shows a real progress bar with a
  running count ("Loading movies… 2/3 providers, 4,200 movies") instead of a tiny, easy-to-
  miss spinner.
- **Added**: checkbox-based bulk-hide for Providers > Movies (merged VOD) — long-press to
  select, matching the same flow Series already got.
- **Added**: merged/secondary-provider movies now track watch progress and resume where you
  left off. Tapping one opens a new detail screen (poster, rating, category, provider,
  Play/Resume, Favorite, and Clear Progress) instead of playing immediately.

## v4.97 - 2026-07-22
- **Fixed**: exiting fullscreen (or rotating the phone) while watching a merged/secondary
  provider channel always kicked you back to Favorites instead of that channel's own
  Providers folder.
- **Fixed**: on a true cold start (app fully killed, not just backgrounded), the app now
  reopens straight into whichever channel/folder you last had playing — including merged
  providers, which previously always got silently overwritten back to your primary
  provider's last-watched channel by two separate startup code paths.
- **Added**: the currently-playing channel is now highlighted in the combined Live tab
  (portrait, landscape, and after returning from fullscreen), matching Favorites' existing
  highlight.
- **Added**: checkbox-based bulk-select for channels (Live tab, both primary and merged
  providers) and Providers > Movies/Series categories — long-press once to turn on
  checkboxes, tap more to select, then an 8-second idle prompt offers to commit (favorite/
  hide) or unselect everything, instead of a silent auto-commit.
- **Added**: hide individual shows in bulk (Series, both primary and merged providers) via
  the same checkbox flow — permanently removes them from the list.
- **Added**: a "Clear Progress" button on a movie's detail screen, so a partially-watched
  movie you don't want to finish can be dropped out of "Continue Watching" and the
  watched-first sort.
- **Fixed**: Providers > Live's guide text (now/next) used to fetch one network call per
  channel with a pacing delay, making the whole tab feel slow to finish loading — it now
  shows cached guide data instantly and does one bulk request per provider instead.
- **Fixed**: the "Show Hidden Categories" toggle in Providers > Movies/Series appeared at
  the top of the list, ahead of your actual categories — moved to the bottom.
- **Fixed**: the search box's clear (X) button removed the text but left the keyboard/
  cursor active — it now also dismisses the keyboard.

## v4.96 - 2026-07-22
- **Fixed**: on the car-box/head-unit (landscape), the Providers tab's refresh icon shared its
  touch area with the "PROVIDERS" tab button underneath it — on a less-precise touchscreen, a
  tap meant for refresh could easily land on the tab instead (a no-op if already on that tab),
  making refresh look like it did nothing. Both buttons now have clearly separated touch areas,
  and the refresh icon's tappable area is larger.

## v4.95 - 2026-07-22
- **Added**: hide categories in Providers > Movies and Providers > Series — long-press a
  category to select it (and more), then hide the selection with one tap. Hidden categories
  disappear from the list entirely; a "Show Hidden Categories" link appears at the top
  whenever a provider has any, letting you reveal (dimmed) and tap one to unhide it.
  Independent per mode and per provider, and survives Backup/Restore and cross-device Sync
  on both phone and TV.

## v4.94 - 2026-07-22
- **Added**: the floating PiP corner box (drag to move, pinch to resize, tap to restore) now
  works in landscape — it was built portrait-only and was an invisible placeholder in
  landscape (the mode car-box/head-unit devices are always in). Also added landscape's sync
  progress indicator (shown while a large Movies/Series catalog is downloading), which had
  the same gap.
- **Fixed**: on TV, system Picture-in-Picture (triggered by pressing Home while a live channel
  plays) could leave you stuck with no way back, since TV remotes have no swipe/tap gesture to
  exit it like phone does — TV no longer auto-enters PiP at all; phone is unaffected.

## v4.93 - 2026-07-22
- **Fixed**: in landscape (always the case on a car-box/head-unit device), the Providers tab's
  Live/Movies/Series row and the Guide tab's What's On/Grid row were both entirely missing —
  landscape uses its own layout file that never got these rows added when they shipped in
  portrait, so Movies/Series browsing inside Providers was unreachable there. Also fixed
  landscape's provider refresh button always refreshing Live channels regardless of which
  Providers mode was active.

## v4.92 - 2026-07-22
- **Fixed (TV)**: the provider enable/disable toggle added in v4.91 wasn't reachable by D-pad
  as its own row — it's now inline on the provider's own row (tap the row to enable/disable,
  a separate "Options" button on the same row opens Switch/Edit/Update/Remove), with both
  independently focusable.
- **Changed**: Guide's "What's On" and "Grid" buttons moved out of the top search bar into
  their own row under the main tabs (matching the Providers tab's Live/Movies/Series row),
  visible only while the Guide tab is active.

## v4.91 - 2026-07-22
- **Fixed**: the Guide tab's list view could show two "▶ NOW" entries for the same channel
  when a provider's EPG had overlapping or back-to-back program times — now only the program
  whose time window actually contains the current moment is marked NOW.
- **Fixed**: every movie/show star showed as favorited in Movies and Providers > Movies/Series,
  even ones never favorited — a leftover fixed blue tint on the star icon made an unfavorited
  star hard to tell apart from a favorited one against most poster art.
- **Fixed**: long-pressing a category in Providers > Movies/Series incorrectly favorited/pinned
  the whole category, a Live-only concept that Movies/Series were never meant to have.
- **Added**: per-provider enable/disable toggle in Settings (phone and TV) — disabling a
  provider hides it from the Providers tab and skips it in refreshes/health checks, but keeps
  its saved login so you can turn it back on anytime with nothing to re-enter.
- **Added**: a "★ Favorites" entry at the top of each Providers-tab mode's provider list (Live,
  Movies, Series independently) — aggregates that mode's favorited items across every
  configured secondary provider in one place.
- **Changed**: the Providers tab's Live/Movies/Series control is now three separate buttons in
  their own row under the main tabs, instead of one button that cycled through the three modes.

## v4.90 - 2026-07-21
- **Added**: the Providers tab can now browse Movies and Series from every secondary provider,
  not just Live channels — a new Live/Movies/Series toggle switches the whole browse tree.
  Merged movies play directly; merged series open the same season/episode picker as your
  primary provider's shows, fetched from that show's own server.
- **Added (TV)**: bulk-select-to-favorites, Hide Channel, and Channels Like This — previously
  phone-only, now reachable via the same D-pad long-press menu on TV.
- **Added (TV)**: "Move to Folder" for merged/Providers channels — previously TV's long-press
  menu was missing this option that phone already had.
- **Added (TV)**: sort options for Movies and Series (long-press-free — same sort button Live
  already had), and long-press "Guide" for What's On Now/Up Next, long-press "Series" for
  Continue Watching — all previously phone-only.

## v4.89 - 2026-07-21
- **Added**: "Backup to file" now includes VOD/series watch progress and per-episode watched
  state — previously only cross-device Sync carried this over, so restoring a backup file
  reset every movie/show back to the beginning even though favorites came back correctly.

## v4.88 - 2026-07-21
- **Fixed**: "Backup to file" crashed with a database-on-main-thread error before the file was
  ever written, since the system had already created an empty placeholder file at the chosen
  location — leaving a 0-byte file behind with no error shown, and causing v4.87's own
  "verify the write" safeguard to never actually run. The backup data is now built entirely off
  the main thread, so this can no longer crash past the error handling.

## v4.87 - 2026-07-21
- **Fixed**: "Backup to file" could silently write a 0-byte file on some Downloads/file-manager
  storage providers, with no error shown — any backup restored later from that file would fail
  with "End of input". The write now happens in explicit truncate mode and is verified by
  reading the file back before saying "Backup saved"; a bad write now shows "Backup failed"
  immediately instead of only surfacing on a later restore.

## v4.86 - 2026-07-21
- **Added**: the Live tab now merges the primary provider with every configured secondary
  provider into one combined, color-coded list — tapping a category shows that provider's
  channels, same as before but now covering all your providers at once.
- **Added**: the Categories tab now shows pinned/favorite categories from every provider (not
  just the primary), same color-coding as Live.
- **Fixed**: restoring a backup via "Restore from file" could crash the app if the picked file
  couldn't be read or parsed — it now shows a "Restore failed" message instead, matching the
  other restore path's error handling.

## v4.85 - 2026-07-21
- **Fixed**: switching your primary provider used to wipe every OTHER configured provider's
  favorites/folders too — now only clears the old primary's own data.
- **Added**: favorites, folder assignments, and pinned categories for other providers are now
  included in Backup/Restore (previously the backup kept your provider list but dropped all
  their favorites).
- **Fixed**: the Providers tab always reset to the top-level provider picker every time you
  tapped it, even mid-browse in a category. It now steps back one level per tap (channel list →
  category list → provider picker), and jumps straight to whatever's currently playing the
  first time you switch into the tab.
- **Fixed**: the currently-playing channel wasn't highlighted in the Providers tab's channel
  list, unlike Live and Favorites which already did this.

## v4.84 - 2026-07-20
- **Added**: a "Test Connection" button in the Add/Edit Provider dialogs (phone and TV) —
  checks the login right there before saving, instead of finding out later that a provider
  silently never worked.
- **Added**: an "All Providers" live reachability check in Settings > Diagnostics > Provider
  Health, showing Online/Unreachable + response time for every configured provider.
- **Improved**: connection test failures now explain the actual reason (account
  expired/disabled, max connections reached, etc.) instead of just a bare HTTP status code.
- **Fixed**: the app was sending a spoofed Chrome browser User-Agent on every provider request,
  which some providers' firewalls reject as bot traffic (while accepting known player apps like
  VLC) — switched to a VLC-style User-Agent, which is more broadly compatible.
- **Fixed**: the Live tab's "↻ Refresh" button was silently also re-fetching all of Movies and
  Series every time — it now only refreshes live channels, since those have their own dedicated
  refresh buttons.
- **Added**: a per-provider "↻ Refresh Channels" button in Settings > Providers, and a "Force TV
  Mode" toggle in Settings > Display for devices like a car head unit that aren't automatically
  detected as a TV.

## v4.83 - 2026-07-20
- **Fixed**: turning on Force TV Mode had no way back — the toggle only existed in phone
  Settings, which becomes unreachable once the device is routed into the TV interface. Added
  the same toggle to TV Settings > Display so it can be turned back off.

## v4.82 - 2026-07-20
- **Added**: a per-provider "↻ Refresh Channels" button in Settings > Providers — refreshes just
  that provider's live channels/categories, separate from the Movies/Series refresh and from
  Home's "Refresh All Providers".
- **Added**: a "Force TV Mode" toggle in Settings > Display — switches this device to the TV
  interface and D-pad navigation even if it isn't detected as one (for a car head unit/box or
  other non-standard screen).

## v4.81 - 2026-07-20
- **Added**: long-press a category folder on the Providers tab to pin it as a favorite, same as
  Live — pinned categories show a star and sort to the top.
- **Added**: favorites, folder assignments, and pinned categories from other providers now sync
  across devices, matched by provider URL so it still works correctly even if your provider list
  is configured in a different order on each device.
- **Fixed**: the favorite star on Movies and Series rows rendered correctly but did nothing when
  tapped — now actually toggles the favorite.
- **Added**: the Guide tab (both the list and the Grid/Timeline view) now shows channels from
  other configured providers alongside the primary one, color-coded by provider so you can tell
  them apart at a glance.

## v4.80 - 2026-07-19
- **Added**: watched/watching movies and series now automatically float to the top of the
  Movies and Series lists, most-recently-progressed first.
- **Fixed**: resuming a series episode where you left off never actually worked — episode
  progress was silently saving into the wrong table. Now saves and resumes correctly, including
  across devices via Sync.
- **Added**: a real interactive Feature Tour — replaces the old static text carousel with a
  spotlight that highlights the actual tab/button being explained, on both phone and TV.
- **Added**: "Find in Settings" search on both phone and TV — jump straight to any setting
  instead of hunting through sections.
- **Added**: inline "↻ Refresh" buttons next to Show Movies/Series Tab on TV Settings, instead
  of separate rows.
- **Added**: a real "What's On" button on the Guide tab (was previously non-functional) — shows
  what's airing now across your favorites; long-press for a single upcoming-programs feed.
- **Added**: favorited channels from other providers now appear in the Guide tab with full
  program data, not just the primary provider.
- **Fixed**: the Favorites tab's search box (and folder views) used to silently drop favorites
  from other providers — now includes them everywhere primary favorites already do.
- **Added**: a "Continue Watching" feed for series — long-press the Series tab to see the next
  unwatched/in-progress episode across every show you've started, without opening each one.
- **Removed**: an unused, unreachable recording code path left over from earlier work.

## v4.79 - 2026-07-19
- **Fixed**: favorite channel health dots could all turn red at once, even though the channels
  played fine — the health check fired one request per favorite all at once with no pacing,
  which could trip a provider's rate limit and get connections dropped/reset. Now paced the same
  way the EPG-fetch code already was.
- **Fixed**: a single failed refresh of a Providers-tab server (timeout, bad response) used to
  permanently wipe that provider's cached channels and favorites from the merged favorites list.
  Now only servers that actually succeeded get cleared/replaced, so a transient hiccup no longer
  loses favorites for that provider.
- **Fixed**: Favorites' health dots (both primary and other-provider) could stay permanently
  unpopulated on a cold app launch or after returning from the player, since several entry points
  into the tab never triggered the health check.

## v4.78 - 2026-07-19
- **Added**: recording now works for channels from other configured providers, not just the
  primary server — schedule from a favorited channel, or hit Record while watching live.
- **Fixed**: recordings from providers whose CDN redirects the stream URL used to hang and fail
  silently; a dropped connection mid-recording could also corrupt the file, making it play back
  much shorter than the actual recorded length. Both are now handled correctly.
- **Added**: every recording now includes a 20-second buffer before and after the requested time,
  in case a show starts a little early or runs a little late.
- **Added**: a custom duration option when starting a recording from the fullscreen Record button.
- **Added**: a calendar-style Schedule view for recordings — a day grid with hour lines showing
  when each recording is scheduled, with day-paging and a "now" indicator. Toggle it from the
  recordings screen (phone: icon in the top bar; TV: "SCHEDULE VIEW" button).
- **Added**: deleting a recording now asks whether to also remove the file from device storage,
  or just remove it from the list.

## v4.77 - 2026-07-17
- **Fixed**: clicking a favorited channel from another provider in the combined Favorites tab
  didn't highlight/focus it in the list — the "now playing" tracking only ever recognized
  primary-server channels.
- **Added**: favorite stars in the combined Favorites tab are now color-coded by provider —
  primary stays blue, each other configured provider gets its own color (yellow, red, green,
  purple, orange, cyan), so you can tell at a glance where a favorite came from.

## v4.76 - 2026-07-17
- **Fixed**: Favorites' genre tabs could fail to show up on a cold app launch (fixed by
  themselves after switching tabs and back), and tapping a genre chip could silently fail to
  filter the list — both were the same underlying race between the genre-classification data
  and a stale unfiltered re-render.
- **Changed**: favorites from other configured providers (Settings → Providers) now show up
  in the main Favorites tab too, combined with your primary favorites and sorted into the same
  genre tabs, tagged with their provider's name. The Providers tab no longer has its own
  separate "★ Favorites" screen — favoriting and folder assignment per channel still work from
  there, just viewed from the main tab now. Drag-to-reorder in Favorites is gone, since channel
  order isn't meaningful across different providers.
- **Added**: on TV (Live and Favorites), pressing D-pad right on a focused channel now moves
  focus onto its star so OK favorites/unfavorites it directly — previously the only way was a
  held-OK long-press to reach the actions menu.

## v4.75 - 2026-07-17
- **Changed**: Favorites (phone and TV) no longer use manual folders — every favorite is now
  automatically sorted into genre tabs (Sports, News, Movies, Kids, Entertainment, All) based
  on its own provider category, the same keyword classification Live already uses. Nothing
  lands in an "Unsorted" bucket anymore. Providers/merged favorites still use folders, unchanged.

## v4.74 - 2026-07-17
- **Fixed**: backups (manual JSON, QR, auto-backup) never included extra providers (the
  "Providers" merged-browse feature) or subtitle style customization — restoring a backup
  silently dropped every non-primary provider and reset subtitle appearance to defaults.
  Trakt connection is deliberately still excluded from backups since it's a live credential,
  not user data — reconnect Trakt manually after a restore as before.
- **Added**: a search box in Mosaic's channel picker — selecting a channel for a tile was a
  single unfiltered list, unusable with a large favorites list.

## v4.73 - 2026-07-17
- **Added**: Picture-in-Picture now has a Play/Pause button in its floating window chrome —
  previously the only way to control playback in PiP was to exit it first.
- **Added**: the EPG guide can now page to other days (◀/▶ next to the day label) and has a
  search box to filter channels, instead of being stuck on a fixed 12-hour window with no
  way to find a channel by name. Whether another day actually has program data depends on
  your configured EPG sources, same as any guide.
- **Added**: when a movie/episode fails to play after exhausting retries, the player now
  tries one alternate stream format (the .m3u8 wrapper most providers also support) before
  giving up, since a provider's catalog-reported container extension can be wrong or stale.

## v4.72 - 2026-07-17
- **Added**: a one-tap Retry button on failed recordings (phone and TV) — previously the only
  way to try again was rescheduling from scratch. Retry starts a fresh recording right away
  for the same duration on the same channel, since the original scheduled time has already
  passed by the time a failure is noticed.

## v4.71 - 2026-07-17
- **Added**: a clear "can't connect — a recording is using your only connection" message when
  playback fails in a way that looks like your provider rejected a second simultaneous
  stream, instead of an unexplained endless "Reconnecting…" loop.
- **Added**: scheduling a recording that overlaps another already-scheduled one now warns you
  first, since most single-connection provider plans would silently fail one of them.
- **Added**: long-press a movie to see a new detail screen — plot, cast, director, rating,
  and resume progress — matching what Series already had. Tapping a movie still plays it
  immediately, same as before.
- **Changed**: consolidated several places on TV that focused the first item in a list after
  it loaded, using the same reliable retry-based approach already proven for the channel
  list, instead of a fragile one-shot attempt that could occasionally leave focus stuck on
  the wrong control.

## v4.70 - 2026-07-17
- **Fixed**: on TV, the Favorites picker only ever showed "All Favorites" and "+ New Folder"
  — the folders you'd actually created (Movies, Sports, etc.) never appeared. The picker was
  built once from a snapshot taken before your folders had finished loading from the
  database, and nothing ever re-rendered it once they arrived. It now updates live.

## v4.69 - 2026-07-17
- **Fixed**: on TV, returning to a favorite folder (after the panel auto-collapsed, or just
  navigating back to it) always jumped back to the top of the list instead of scrolling to
  whatever channel you were actually watching. It now scrolls/focuses straight to the current
  channel if it's in that folder, matching how the phone's Favorites tab already behaved.

## v4.68 - 2026-07-16
- **Fixed**: VOD and Series refresh (auto-refresh, pull-to-refresh, stale-cache reload) was
  silently un-favoriting every movie/show and resetting all watch progress back to zero on
  every refresh — same bug class already fixed for live and Providers channels, just missed
  in these two spots. Favorites and watch progress now survive a refresh.
- **Added**: TV now has favorite folders — a folder picker (All Favorites / Unsorted / named
  folders / + New Folder) when you open Favorites from the sidebar, with the same
  create/rename/delete options as phone. Previously TV had no folder support at all and
  showed every favorite flattened together, even if you'd organized them into folders on
  another device via Sync.
- **Fixed**: several places (a merged-channel play log, the Cast proxy's request/response
  logging including m3u8 playlist previews) were logging full stream URLs — and therefore
  plaintext account credentials, which Xtream embeds directly in stream URLs — to Logcat
  unredacted, bypassing the credential redaction already used for crash logs.
- **Changed**: refreshing Providers channels no longer lets one slow or dead extra provider
  stall the whole refresh for up to 2 minutes — each server now gets a 15s budget.
- **Fixed**: a rare edge case where the update installer's broadcast receiver could leak for
  the rest of the app's process if the OS ever dropped its callback silently.
- Removed several always-on diagnostic log lines from VOD/live-category/Providers refreshes
  that were left in from recent debugging (now debug-build-only).

## v4.67 - 2026-07-16
- **Fixed**: the player's Record dot is a brighter red.
- **Added**: favorite folders (and which channels are in them) are now included in backups
  (manual JSON export, QR backup, and auto-backup) — previously only Sync carried folder
  organization across devices, so restoring from a backup brought favorites back but dumped
  every one of them into Unsorted.

## v4.66 - 2026-07-16
- **Changed**: the fullscreen player's Record button is now a small red dot next to the
  buffer-health dot (top right), instead of a large camera-icon button in the bottom
  transport row — tap it the same way to start a recording. It blinks while a recording of
  the current channel is actually in progress, and sits dim when idle.

## v4.65 - 2026-07-16
- **Fixed**: no way to refresh Providers channels in landscape on phone — the only mechanism
  was an undiscoverable long-press on the sidebar entry, with no visible button at all (unlike
  portrait, which has a refresh icon next to search). Landscape now has the same visible
  refresh icon next to the Providers sidebar entry; the long-press still works too.

## v4.64 - 2026-07-16
- **Changed**: the player's zoom button now steps +10% per press after the three aspect modes
  (Best Fit / Zoom / Stretch), from 110% up to 200%, then wraps back to Best Fit — replacing
  the old fixed 15%/30% jumps.

## v4.63 - 2026-07-16
- **Added**: Providers channels now show the same extras as the primary channel list — a
  now/next guide line (fetched from each channel's own provider), a health dot on favorited
  Providers channels (grey while checking, then green/red, refreshed when opening the
  ★ Favorites view), double-tap to go straight to fullscreen, and a long-press action menu
  (Play Fullscreen / Add-Remove Favorite / Move to Folder). On TV, long-press (holding OK)
  is how you favorite a Providers channel, since the star icon isn't reachable by D-pad.
- **Changed**: the Providers tab/sidebar entry now sits directly next to Favorites on phone
  (portrait + landscape) and TV.
- **Changed**: moving a Providers favorite into a folder now updates the visible list
  immediately instead of waiting for the next re-entry.
- **Fixed**: the Settings toggles for hiding the Movies/Series/History tabs had drifted after
  an old tab reorder and were hiding the wrong tabs (hiding "Movies" actually hid Guide).

## v4.62 - 2026-07-16
- **Changed**: app updates no longer leave APK files on the device. Both update paths (the
  automatic popup and Settings > Check for Updates) now use the same flow: download to the
  app's private cache with a progress dialog, verify the checksum, install, and delete the
  file — plus a launch-time sweep that also removes the old per-version APKs the previous
  Settings flow had been leaving behind forever.
- **Fixed**: "Download failed" from Settings > Check for Updates — the old flow used Android's
  DownloadManager, which choked on GitHub's redirect chains and put a download notification in
  the system tray. Manual updates now use the same reliable downloader as the automatic popup.
- **Added**: the automatic update popup now verifies the download's SHA-256 checksum before
  installing (previously only the manual Settings flow did).

## v4.61 - 2026-07-16
- **Fixed**: favorite folders could get reset to "Unsorted" after an app update — the channel
  refresh that runs on every launch never preserved which folder a favorite was filed under
  (only isFavorite/watch-history/etc. were preserved), silently un-filing every folder on the
  next refresh.
- **Added**: favorites for Providers (formerly "All Providers") channels — a ★ Favorites entry
  now sits alongside your other providers. On phone, it opens the same folder-picker style
  browsing as your primary Favorites (shared folder list — a folder you create in one place
  shows up in both). On TV, it's a flat favorited-channels list, matching how TV's primary
  Favorites already works. Tap the star on any Providers channel to favorite it; survives
  every provider channel refresh the same way primary favorites do.
- **Changed**: renamed the "All Providers" tab/section to "Providers" (shorter, same meaning).

## v4.60 - 2026-07-16
- **Fixed**: All Providers channels endlessly spinning instead of playing — confirmed against
  a real provider that some Xtream panels ignore the requested stream extension and always
  serve raw MPEG-TS regardless. Requesting the URL as ".m3u8" made ExoPlayer try to parse
  binary video as a text playlist and fail forever; merged channels now request ".ts" instead.
- **Added**: subtitle styling (size, vertical offset, bold, text/background/outline color) is
  now available in phone Settings > Display, matching what TV Settings already had.

## v4.59 - 2026-07-16
- **Changed**: on TV, tapping Live or Categories in the sidebar now always shows the category
  list first, instead of jumping straight into whatever category the currently-playing channel
  belonged to. Picking a category still opens its channel list the same as before.

## v4.58 - 2026-07-16
- **Added**: D-pad Left now jumps straight from deep in a channel/category list to the header
  above it (genre chips if shown, else search, else Back) — no more repeatedly pressing Up to
  climb all the way back.
- **Fixed**: D-pad Up at the top of a channel list now reliably lands on that header instead of
  sometimes doing nothing, by explicitly handing off focus instead of relying on default
  Android focus search.
- **Fixed**: on Movies/Series, D-pad Left/Right while a genre chip (All/Drama/etc.) was focused
  jumped straight to the mini player or was swallowed instead of moving between chips.

## v4.57 - 2026-07-15
- **Fixed**: TV D-pad Up couldn't escape the top row of a channel list to reach the Back
  button, search box, or genre chips above it — it silently swallowed the key press instead.
- **Fixed**: TV's auto-collapse-to-sidebar timer still fired while typing via an on-screen
  keyboard — many TV keyboards commit characters without generating key events, which the
  previous fix relied on. The search box's own text-change events now also reset the timer.
- **Fixed**: switching to the phone's Live or Categories tab could briefly show a stale
  channel list left over from a previously-viewed Favorites folder.
- **Fixed**: the phone's Live tab could get stuck applying an old genre-chip filter (e.g.
  "Movies") indefinitely — once tapped, it silently kept filtering the category list down
  to just that genre on every future visit instead of resetting to the full list.
- **Removed**: the active/primary provider no longer appears in the All Providers list — its
  channels are already fully browsable via the normal Live tab.

## v4.56 - 2026-07-15
- **Added**: TV Settings > Providers now has an "Update Channels" action per provider (and an
  "Update All Provider Channels" action) to manually refresh the merged All Providers cache.
- **Added**: the merged All Providers channel cache now auto-refreshes once on cold app start
  (phone and TV) when at least one extra provider is configured — previously it stayed empty
  until you manually tapped Refresh, even after adding providers.

## v4.55 - 2026-07-15
- **Added**: app update downloads now show a real progress dialog (MB downloaded / total, with
  a progress bar) instead of a single static "Downloading update…" toast with no feedback.
- **Added**: Edit button on the Settings > Providers primary provider row — previously only
  extra providers could be edited after being added.
- **Added**: "Show password" checkbox in the Add/Edit Provider dialogs (phone and TV).
- **Fixed**: TV sidebar (Favorites/Live/Categories/Movies/Series/Guide/All Providers/
  Recordings/Settings) could run off the bottom of the screen on shorter TV panel heights —
  it now scrolls instead of overflowing, with Settings pinned at the bottom.
- **Fixed**: TV sidebar's accent color reverted to the default blue as soon as you selected a
  section — it only stuck at launch. The chosen accent now stays applied through navigation.
- **Fixed**: on the phone, Favorites could show stale/empty folder counts if opened before the
  initial channel sync finished, with no way to refresh short of switching tabs and back. The
  folder picker now updates live as sync data arrives.
- **Fixed**: on TV, D-pad Down from the search box in Movies/Series was hardcoded to jump
  straight into the channel list, skipping the "All/Drama/…" genre filter chips entirely —
  they were effectively unreachable by D-pad.

## v4.54 - 2026-07-15
- **Fixed**: TV Series search wasn't wired up at all — searching while in the Series section
  searched the live channel list instead, showing nothing relevant.
- **Fixed**: TV's 10-second auto-collapse-to-sidebar timer only reset on D-pad navigation
  keys — typing into a search box (letter/number keys) never reset it, so the panel could
  collapse mid-search 10 seconds after your last D-pad press even while you were still typing.
  Now any key activity resets the idle timer.

## v4.53 - 2026-07-15
- **Added**: sync pairing codes are now remembered (up to 10, most recent first) — a "Saved
  Codes" option in Settings > Sync (phone and TV) lets you quickly re-pair with a device
  you've used before instead of re-typing its code every time.

## v4.52 - 2026-07-15
- **Added**: bulk-selected channels (for moving into a favorite folder) now show a visible
  highlight and checkmark — previously there was no indication a tap had registered at all.
- **Changed**: in the "Move to Folder" picker, only the explicit Cancel button clears your
  selection — tapping outside the dialog just dismisses it so you can keep selecting more
  channels.
- **Added**: the Primary Provider row in Settings > Providers now shows its actual URL
  underneath the nickname, matching the other provider rows, so you can verify what's really
  configured as primary.

## v4.51 - 2026-07-15
- **Fixed**: the EPG URL field on Add/Edit Provider was being silently dropped on save — the
  underlying storage function never actually wrote or read that field, so it always came back
  empty when reopening Edit, no matter what you typed.

## v4.49 - 2026-07-15
- **Added**: cloud sync (Settings > Sync > Push/Pull to Cloud) now carries over favorite
  folders and their contents (matched by folder name, created if missing on the other device),
  and your drag-reordered favorite order — previously only which channels were favorited and
  favorite categories synced, silently dropping folders and manual reordering.

## v4.48 - 2026-07-15
- **Fixed**: switching to another saved server (Settings > Servers > Switch) wasn't carrying
  over that server's nickname — it kept showing whatever nickname the previous primary server
  had, making a freshly-edited nickname look like it had reverted/vanished after switching away
  and back.
- **Changed**: bulk-selecting channels to move into a favorite folder no longer requires
  long-pressing every single one — long-press once to start, then just tap additional channels
  to add/remove them from the selection. Stop tapping for 3 seconds and the "Move to Folder"
  picker opens automatically.

## v4.47 - 2026-07-15
- **Added**: multi-select when moving channels into a favorite folder — long-press to select
  multiple channels (same "bulk select" used for adding to favorites), then "Move N selected
  to folder" to assign them all at once. Moving a channel into a folder now also favorites it
  automatically if it wasn't already, so bulk-selected channels from Live/Categories work too.

## v4.46 - 2026-07-15
- **Fixed**: long-pressing a favorite channel to move it into a folder wasn't showing the menu
  at all — the drag-to-reorder gesture (which defaults to starting on a long-press anywhere on
  the row) was swallowing it first. Dragging now only starts from its dedicated handle icon,
  so long-press reliably opens the "Move to Folder" menu.

## v4.45 - 2026-07-15
- **Fixed**: a real bug found while extending EPG — any manually-added EPG source (Settings >
  EPG "Add EPG Source", or the "Default US Guide" toggle) was saved but never actually
  fetched from; the EPG refresh only ever pulled from the primary server's own built-in guide.
  Now every configured EPG source is fetched and merged, matched against your channels the
  same way as before — so multiple/custom EPG sources actually work now, not just display in
  Settings.
- **Added**: an EPG URL field when adding or editing a saved server (Settings > Servers),
  for providers where you have a separate guide URL from the login itself.

## v4.44 - 2026-07-15
- **Added**: custom favorite folders (phone) — organize favorites into named groups (e.g.
  "Sports", "News") instead of one flat list, same drill-down style as Movies. The Favorites
  tab now opens to a picker: "All Favorites", "Unsorted", and any folders you've created.
  Long-press a favorite channel → "Move to Folder" to assign it (create a new folder inline);
  long-press a folder in the picker to rename or delete it. Works identically in landscape.
  TV keeps the existing flat Favorites list for now.

## v4.43 - 2026-07-15
- **Fixed**: landscape mode (used by car-dock/head-unit devices) had no way to refresh
  channels at all for any tab, including the new All Providers view — long-press the "ALL
  PROVIDERS" sidebar entry in landscape to trigger a refresh.

## v4.42 - 2026-07-15
- **Added**: "ALL PROVIDERS" — browse and watch channels from every configured server
  (Settings > Servers) together, not just your primary one. Drill down by server, then by
  category (a single provider can have tens of thousands of channels, so both levels are
  grouped), with its own search box and respecting "USA Only" across every provider's own
  category-naming convention. Tapping a channel plays it in the mini player first, same as
  every other channel list — fullscreen only if you tap through.
- **Fixed**: a real playback bug, not just a merged-providers issue — the mini player (and
  Mosaic) used ExoPlayer's bare default User-Agent instead of a proper browser-style one, which
  some providers' CDNs silently reject on the actual video-stream endpoint (even while their
  API endpoint accepts it fine). This only showed up as broken because a stricter provider was
  added, but it could affect any provider with that CDN behavior — now fixed to match what
  fullscreen playback already does.
- **Fixed**: "USA Only" channel filtering now recognizes both `US|...` and `US | ...`
  (spaced) category-naming conventions, instead of only the first format.
- **Added**: an "Edit" option for saved servers (Settings > Servers) to view/fix the full
  nickname, URL, username, and password — the server list now also shows each server's URL
  directly instead of only its nickname.

## v4.41 - 2026-07-14
- **Changed**: the default US EPG guide option (added in v4.40) is now a checkbox/toggle
  instead of a hint that only appeared when the EPG URL field was empty — it's always visible
  on both phone (Stream tab) and TV (Settings > EPG) so it's easy to find and toggle off again.

## v4.40 - 2026-07-14
- **Fixed**: added a spoofed browser User-Agent to all Xtream API calls — some providers'
  Cloudflare/WAF protection was silently rejecting requests (401/429) that carried OkHttp's
  default library user-agent, even with correct credentials, while any real player app
  connected fine with the same login.
- **Fixed**: the per-channel EPG prefetch (up to 50 channels on every list load) now paces
  each request 150ms apart instead of firing them all back-to-back — the previous unpaced
  burst was, on its own, enough to trip some providers' rate limiting.
- **Added**: a one-tap "Use Default US Guide" option in EPG settings (phone: Stream tab; TV:
  Settings > EPG) for providers that don't supply their own program guide data, pre-filled
  with a public US XMLTV source (iptv-epg.org).

## v4.39 - 2026-07-14
- **Changed**: on phone, the Favorites tab (and sidebar entry in landscape) now sits first,
  matching TV's tab order — Favorites, Live, Categories, Movies, Series, Guide, History.
- **Changed**: the Record button in fullscreen live playback now sits directly next to
  Play/Pause instead of two spots over, on both phone and TV.

## v4.38 - 2026-07-14
- **Changed**: on phone, Trakt is now part of the Sync tab instead of its own separate tab
  (Trakt is itself a form of watch-history sync).
- **Changed**: Mosaic/Multi-View moved from a top-bar button into Settings → Display, under a
  new "Multi-View" card — since it opens several simultaneous streams, it only works on
  provider plans that allow more than one connection at a time.

## v4.37 - 2026-07-13
- **Fixed**: a silent buffering stall (spinner spins forever, no error/reconnect message shown)
  now forces a reconnect after 20 seconds instead of just logging the stall — previously this
  spinner had no error to trigger a retry, so it could spin indefinitely.
- **Added**: Multi-View/Mosaic is now fully reachable — a real button on both phone layouts,
  a "Multi-View" entry in TV Settings, D-pad focus support, a focus-following highlight ring
  on TV, and error logging for playback failures (mirrors the rest of the app's crash log).

## v4.36 - 2026-07-13
- **Added**: a Record button in the fullscreen live player — starts an immediate recording of
  what you're watching (choose 30 min / 1 hour / 2 hours / 4 hours) without leaving the
  player or navigating to the recordings screen first.

## v4.35 - 2026-07-13
- **Added**: recordings can now be renamed (long-press a recording, both platforms) instead
  of being stuck with the auto-generated channel+timestamp name.
- **Added**: Trakt watched-history sync now reports which movies/shows it couldn't match
  against your local library, instead of silently skipping them — tap the sync status
  (phone) or "View Unmatched Titles" (TV) to see the list.
- **Added**: the search box now shows your current server's nickname in its hint text (e.g.
  "Search (Rich)…"), so which account you're on is visible at a glance without opening
  Settings — useful if you've added more than one server.

## v4.34 - 2026-07-13
- **Added**: Trakt watched-history sync-back — previously the app only sent scrobble data
  *to* Trakt, with no way to pull existing watched history back in. A new "Sync Watched
  History from Trakt" button (Settings, both platforms, once connected) fetches your
  watched movies and shows from Trakt and matches them against your local library by title
  (and year, for movies). Matched movies are marked watched; matched shows have their
  watched episodes recorded against a new local table (episodes aren't otherwise stored
  locally at all) and now show a ✓ and dim in a series' episode list.
- Confirmed Multi-View/Mosaic/TV channel-list improvements from the last two releases are
  holding up well in day-to-day use; Cast and the recordings library were reviewed and found
  to already be fully working, no changes needed there.

## v4.33 - 2026-07-13
- **Changed (phone, landscape)**: the inline category/channel list's auto-collapse now
  matches TV's behavior — it arms as soon as the list opens (not only after picking a
  channel to play), and resets on scroll, so actively browsing a long list for more than
  10s no longer gets it yanked away mid-browse. Previously it only ever collapsed after
  playing something, and never while just browsing.

## v4.32 - 2026-07-13
- **Fixed**: long-pressing a channel to open reminder/actions sometimes just didn't register.
  A periodic EPG/progress refresh was rebinding list rows — including whichever one you
  happened to be mid-long-press on — which can disrupt Android's pending long-press callback.
  Rows you're actively pressing (touch or a held D-pad OK/Enter) are no longer rebound until
  released.
- **Fixed**: Multi-View and Mosaic could show a stream as solid white instead of video —
  Multi-View's cause was an unset shutter background falling back to the theme's white
  surface color; Mosaic's was a legacy system "selection ring" drawable that's mostly opaque
  white, not a thin outline, covering the video whenever a tile was tapped (the video was
  actually still playing underneath the whole time).
- **Fixed**: Mosaic played every tile's audio simultaneously on open — nothing was focused by
  default, and the mute logic only ever muted a tile relative to whichever one *was* focused.
  The first tile is now focused (and the rest muted) as soon as Mosaic opens.
- **Fixed (TV)**: the channel-number entry (type digits on the remote to jump to a channel)
  treated the typed number as a position in whatever list happened to be on screen rather
  than the provider's actual channel number, and only worked from specific lists. It now
  works from anywhere and looks up the real channel number, tuning directly to it.
- **Fixed (TV)**: the sidebar's auto-collapse (returns to the sidebar 10s after picking a
  channel) only ever armed once when a panel opened — actively scrolling a long list for
  more than 10s could still get yanked back to the sidebar mid-browse. It now resets on every
  D-pad press, so it only collapses after genuine idle time.
- **Changed (TV)**: re-selecting a sidebar tab (Live, Categories, or Favorites) after its
  panel auto-collapsed now jumps straight back to whatever channel is currently playing,
  scrolled and focused on it, instead of restarting at the top of the list.
- **Added**: the Guide tab's long-press (reminder/record) now works consistently — TV's Guide
  had no long-press at all before; phone's Guide only offered a plain reminder. Both now use
  the same "Remind Me" / "Record This" choice as every other channel list.
- **Added**: Multi-View — tapping a tile now also switches audio to it in one motion, instead
  of needing the separate Audio button.

## v4.31 - 2026-07-12
- **Fixed (major)**: live TV could get stuck reconnecting in fullscreen on some providers,
  while the mini player played the exact same channel fine. Root cause: the disk cache used
  for live-TV rewind assumed segment URLs never change content, but some providers reuse the
  same segment URL while overwriting it server-side — the cache kept serving stale bytes
  instead of refetching, which looked exactly like a stuck/looping stream. The disk cache is
  now removed from live playback entirely; rewind on live TV is limited to the ~2 minute
  in-memory buffer instead of a disk-backed multi-minute window.
- **Fixed**: DNS-over-HTTPS was creating a brand-new resolver (with its own cold connection)
  on every single network request instead of reusing one, and ran two DNS lookups back-to-back
  instead of in parallel, with no caching at all — adding real, avoidable latency to every
  request while DoH was enabled.
- **Fixed**: a corrupted/oversized `plot` field from some providers' series catalog could crash
  the whole Series tab (`SQLiteBlobTooBigException`). Plot text is now capped at insert time.
- **Fixed**: the phone's Favorites tab could fail to scroll/focus to the currently-playing
  channel when returning from Live, Categories, or Guide — a stale-data race in how the list
  was refreshed.
- **Fixed**: hiding Movies/Series from Settings could silently hide Favorites/Guide instead,
  left over from the phone's tab reorder.
- **Fixed**: Android's autofill could silently substitute your saved login into the "Add
  Server" dialog before it was saved, making it look like a new server's credentials reverted
  to your existing one.
- **Changed (phone)**: tab order is now Live, Categories, Favorites, Guide, History, Movies,
  Series. Favorites tab search now works (previously disabled).
- **Added**: genre-folder chips on both the Series and Movies tabs, grouping the often messy
  raw genre/category tags providers send into a handful of broad folders (Comedy, Drama,
  Action & Adventure, Documentary, ...).
- **Added**: a "Most Reliable" channel sort option, ranking channels by their tracked
  success-rate history.
- **Added**: a Provider Health dashboard in Settings — tracked-channel reliability average,
  playback errors/retries in the last 24 hours, and your least-reliable channels, all pulled
  from the on-device log without needing to dig through it manually.
- **Added**: an in-app camera QR scanner on the phone's login screen ("Scan QR from TV") that
  reads the TV's backup QR code directly, instead of relying on the OS to recognize a custom
  app link.
- **Added (TV)**: full file-based Backup/Restore, entirely on the TV — no phone required.
  Both platforms also gained a "Manage Backups on This Device" screen to list, restore,
  delete, or (phone only) share previous backups.
- **Added**: "Auto Sync to Cloud" now actually schedules a recurring background sync instead
  of only running when manually triggered.
- **Added**: "Allow Picture-in-Picture" setting on the phone; the floating PiP corner window
  can now be dragged, pinch-resized, and tapped to restore the normal mini player.
- **Added (TV)**: the channel/category list now auto-collapses back to the sidebar a few
  seconds after picking a channel, matching the phone's landscape behavior. Selecting LIVE
  from the sidebar now jumps straight to whatever channel is currently playing instead of
  always starting at the category list.
- **Added (TV)**: an "English Movies & Series Only" toggle in Settings (the filter already
  applied automatically — the toggle to control it was missing).

## v4.30 - 2026-07-11
- **Changed (Shield/TV)**: the live EPG guide is now its own "GUIDE" section in the sidebar
  instead of a permanent list under the mini player — the mini player now fills the whole
  right side of the screen. The FULL SCREEN button was removed: press Right on the D-pad
  from the sidebar or any list to reach the mini player itself, then OK to go fullscreen.

## v4.29 - 2026-07-11
- No functional change — version bump to test Silent Self-Update logging live.

## v4.28 - 2026-07-10
- **Fixed**: Silent Self-Update was effectively doing nothing for most people. The
  automatic "Update available" popup shown on every app launch used a completely separate,
  older install path that always showed the visible confirmation screen and never checked
  the setting — only the manual "Check for Updates" button in Settings respected it. Both
  paths now behave the same way and log the same diagnostics.

## v4.27 - 2026-07-10
- **Diagnostic**: Silent Self-Update now logs exactly what happened (succeeded, OS still
  required confirmation, or an actual error) to the same log "Send Debug Report" uses — so
  "it isn't working" can be told apart from the expected Android restriction that silent
  installs only take effect once the app is already its own installer of record.

## v4.26 - 2026-07-10
- **Fixed**: a movie could loop/reconnect forever after finishing, found from a debug
  report. A "hero" watch button (separate from the mini player's fullscreen button) still
  opened fullscreen without passing whether the content was VOD, defaulting to live — so a
  finished movie got treated as a disconnected live channel and endlessly retried. Fixed
  the same gap in the external-player-not-installed fallback path too.

## v4.25 - 2026-07-10
- **Updated**: refreshed the first-launch feature tour to cover everything added recently —
  channel reliability, the Up Next ticker, landscape sidebar navigation, Movies/Series sort,
  auto buffering, Trakt, and the new Settings help button. It'll show once more even if
  you already dismissed the old one, since the content genuinely changed.

## v4.24 - 2026-07-10
- **New**: help button ("?") in Settings' top bar — tap it for a plain-language explanation
  of every control in whichever section you're currently viewing (Stream, Display, Updates,
  Backup, Servers, Sync, Trakt), so things like Tunneled Playback, DoH, or Extra Buffering
  aren't a mystery.

## v4.23 - 2026-07-10
- **New**: unified "Up Next" ticker across all favorite channels. Long-press the "What's On"
  button for a single chronological feed of what's airing next on every favorite, instead
  of checking channel-by-channel.

## v4.22 - 2026-07-10
- **New**: bandwidth-aware auto buffering. If a stream stalls or retries 3+ times within
  2 minutes in the same viewing session, Extra Buffering turns on automatically for future
  streams — no need to notice the pattern yourself and go find the setting.

## v4.21 - 2026-07-10
- **New**: channel reliability tracking. Every "Check Favorites Health" ping and every real
  mini-player playback attempt now records a success/fail outcome per channel (last 10
  kept). Long-press a channel to see it in the actions menu title, e.g. "7/10 succeeded
  recently" — a flaky channel now surfaces itself instead of you rediscovering the same
  dead one over and over.

## v4.20 - 2026-07-10
- **Changed (phone landscape)**: startup now lands on the plain sidebar + mini player view
  with the last-playing channel already loaded, instead of immediately opening Favorites'
  channel list on every launch. Tap a sidebar item to open its list as usual.

## v4.19 - 2026-07-10
- **New (phone landscape)**: the inline channel list auto-collapses 10 seconds after
  picking a channel to play, giving the mini player the full row width. Tap the sidebar
  tab again to bring the channel list straight back, scrolled to whatever's currently
  playing (instead of going back to the category list).

## v4.18 - 2026-07-10
- **Tweaked (phone landscape)**: narrowed the inline channel list column so it doesn't take
  space from the mini player, which keeps its full size.

## v4.17 - 2026-07-10
- **Changed (phone landscape)**: removed the "Channels" button and bottom-sheet popup.
  Tapping Live, Categories, or Movies in the sidebar now shows that tab's category list in
  place; picking a category collapses it and shows the channels/movies within it in the
  same spot instead. Tap the sidebar item again to go back to the category list. Series,
  History, Favorites, and Guide (no categories) show their list directly.

## v4.16 - 2026-07-10
- **Changed (phone landscape)**: the middle categories column no longer takes a fixed
  share of the screen regardless of content — it now shrinks to fit the longest visible
  category name, freeing up space for the channel list/mini player.
- **Changed (phone landscape)**: the genre filter chips (e.g. "All Sports") on the Live tab
  moved from a horizontal row above the categories list to a vertical column to the right
  of the channel list.

## v4.15 - 2026-07-10
- **Fixed**: the "Channels" button actually rendered as a solid blue bar, not the intended
  dark background with accent-colored text from v4.14. This app's Material theme
  auto-upgrades plain buttons to MaterialButton, which ignores a plain `android:background`
  color and paints its own `colorPrimary` tint over the entire button instead — now pinned
  to the intended gray via an explicit `backgroundTint`.
- **Fixed**: AMOLED Black only ever painted the outermost screen background pure black,
  leaving nearly every card/panel/sidebar showing its own separate dark-gray color on top
  (since those paint their own background independently). Enabling it now walks the whole
  screen and flattens all of that gray chrome to pure black too.

## v4.14 - 2026-07-10
- **Tweaked**: restyled the "Channels" button under the mini player in landscape to match
  the left sidebar — dark/gray background with accent-colored text (follows your chosen
  accent color in Settings) instead of a plain white-on-dark look.

## v4.13 - 2026-07-10
- **New**: Series now has the same sort options Movies already had — Default, Rating,
  Year (Newest/Oldest First), Recently Added. Tap the sort button while on the Series tab.
- **Cleanup**: removed a leftover debug toast from the Movies sort dialog.

## v4.12 - 2026-07-10
- **Fixed**: the season tabs on the Series episode picker were drawn underneath the status
  bar/notification area on some devices, making them untappable. Targeting Android 15+
  makes edge-to-edge content mandatory, and this screen had no padding to compensate for
  it. The season tab row now grows by the actual system bar inset instead of assuming a
  fixed guessed height.

## v4.11 - 2026-07-10
- **Tweaked**: the experimental English-only filter also matches a US/USA category tag now,
  not just EN/ENG/ENGLISH — matching only EN found nothing on providers whose categories
  are labeled by country instead of language.

## v4.10 - 2026-07-09
- **New**: extended the experimental English-only filter to Series too (toggle renamed to
  "Show English Movies & Series Only"). Series has no category-browsing UI like Movies
  does, so this now also fetches series categories in the background and filters the
  series list directly by looking up each show's own category for an EN/ENG/ENGLISH tag.

## v4.9 - 2026-07-09
- **Fixed**: the "What's new" dialog (shown after updating, and the update-available
  prompt beforehand) always showed a blank message. It parsed `version.json`'s changelog
  field as a JSON array, but every release has always published it as a single string —
  so the actual release notes never displayed, ever, this whole time.

## v4.8 - 2026-07-09
- **New (phone)**: "Show English Movie Categories Only" toggle in Settings → Stream.
  Experimental — Xtream has no real language field for VOD, so this only works if your
  provider tags movie category names with an EN/ENG/ENGLISH label. Off by default.

## v4.7 - 2026-07-09
- **New**: movies/series now auto-retry on a playback error instead of dead-ending with a
  static "Playback error" message. A transient network blip recovers on its own (backoff,
  up to 5 attempts) the same way live TV already did, and resumes from where it left off
  instead of restarting from the beginning.

## v4.6 - 2026-07-09
- **Fixed**: movies gave a "Playback error: Source error" in fullscreen while playing fine
  in the mini player. The fullscreen player's disk cache (built for live TV timeshift/DVR
  rewind) was being applied to VOD content too — its eviction policy is sized for live TV's
  small rolling segment window, not multi-gigabyte progressive movie files, a likely cause
  of the mismatch. VOD no longer goes through that cache at all — it doesn't need it, since
  it already has its own resume-position mechanism.

## v4.5 - 2026-07-09
- **Fixed**: fullscreen going to a black screen with an endless reconnect loop for content
  that plays fine in the mini player. The fullscreen player's HTTP requests had no
  User-Agent set (defaulting to raw OkHttp's own), while the mini player uses ExoPlayer's
  built-in HTTP stack with its own default User-Agent instead — some Cloudflare-fronted
  IPTV CDNs allow the latter but reject the former, which was the only real pipeline
  difference between the two players. The fullscreen player now sends an ExoPlayer-style
  User-Agent too.

## v4.4 - 2026-07-09
- **Changed**: replaced the landscape channel list's broken auto-expand/collapse behavior
  (a hardcoded height that could overflow on some screens and only ever showed ~4 channels)
  with an on-demand "Channels ▾" button below the mini player. Tap it to open the full
  scrollable list in a bottom sheet; picking anything plays it and closes the sheet
  automatically. The mini player now gets the full column height by default instead of
  permanently sharing it with the list.

## v4.3 - 2026-07-09
- **Fixed**: rotating the phone made all channels disappear and the landscape channel-list
  pop-up stop working entirely. Root cause: an earlier fix disabled activity recreation on
  rotation to stop the mini player resetting to a live channel — but Android only switches
  to the landscape-specific layout (the whole 3-column UI including the channel pop-up) by
  recreating the activity. With that disabled, rotating just squished the portrait layout
  into the landscape window instead of loading the real landscape layout. Recreation on
  rotation is restored, and the mini player's state (what's playing, playback position) is
  now preserved across it through the ViewModel instead, so both bugs are fixed together.

## v4.2 - 2026-07-09
- **Fixed**: on the phone in landscape, the expandable channel strip under the mini player
  collapses automatically after picking a live channel, but selecting a movie left it stuck
  open. Now it collapses after picking a movie too, matching live TV.

## v4.1 - 2026-07-09
- **Fixed**: movies/series showed a black screen in fullscreen but played fine in the mini
  player (which never resumes). The saved resume position was applied as a seek after the
  player had already buffered and become ready at position 0 — for progressively-served
  files (.mkv movies), that forces a mid-stream HTTP range renegotiation, and some providers
  hang on that instead of erroring, leaving playback stuck buffering forever with nothing
  visibly wrong. The resume position is now applied as the initial start position at load
  time instead of a follow-up seek.

## v4.0 - 2026-07-09
- **Fixed**: TV mini player had no error recovery — any transient network blip left it
  permanently frozen/black, unlike the phone which retries automatically. TV now retries
  the same way (up to 5 attempts, 3s backoff), and logs the failure for debug reports.
- **Fixed**: TV mini player didn't re-fetch to the live edge when resuming from background
  — it could keep playing a stale buffered position instead of jumping to "now," unlike
  the phone which already did this correctly.
- **Fixed**: a `runBlocking` DataStore read ran on every single network request's DNS
  lookup (EPG polls, VOD/series sync, Trakt, update checks, ...), which under concurrent
  load could exhaust OkHttp's dispatcher thread pool and stall unrelated requests in a way
  that looked like a network problem but wasn't. The relevant prefs are now cached in the
  background instead of read synchronously on every lookup.
- **Fixed**: a blocking, untimed database write during recording-service teardown could
  hang the main thread indefinitely if the database was briefly locked — now bounded to 3
  seconds.
- **Cleanup**: consolidated duplicated play/share-recording logic between phone and TV into
  one shared helper, removing a source of future phone/TV drift bugs.

## v3.99 - 2026-07-09
- **New**: Global Extra Buffering setting (Settings → Stream on phone; Settings → Stream →
  Decoder on TV). On by default. Raises the fullscreen player's buffer targets — trades a
  slightly slower start/seek for fewer mid-playback stalls on slow or inconsistent IPTV
  providers. Applies globally to every server, not per-provider.

## v3.98 - 2026-07-09
- **Fixed**: TV had the same VOD/live-detection bug fixed on phone in v3.95 — the fullscreen
  button, tapping the mini player, and the OK-button shortcut all used a URL regex
  ("movie|vod") that missed series episode URLs, so those opened as live TV instead of VOD
  (no seek bar, no resume). Now tracked explicitly instead of guessed from the URL.

## v3.97 - 2026-07-09
- **Diagnostic**: v3.96's playback logging never actually captured anything on a repro —
  most likely because the OTA install doesn't take effect in an already-running process
  until it's restarted, so the old code kept running. Added: a startup log line recording
  exactly which build/versionCode is running (so a future report can confirm this), logging
  for the mini player's own separate reconnect-retry loop (distinct from the fullscreen
  player's), and a 20-second buffering-stall watchdog log for stalls that never trigger an
  error or STATE_ENDED event at all. Still no user-facing behavior change.

## v3.96 - 2026-07-09
- **Diagnostic**: player errors, reconnect-retry attempts, and give-up events are now logged
  to the same crash log that "Send Debug Report" uploads (error code, cause, stream URL,
  retry count). Previously these were silently handled and left zero trace when a stream
  got stuck in a reconnect loop with a black screen — no user-facing behavior change.

## v3.95 - 2026-07-09
- **Fixed**: live TV on the Shield (and full screen generally) would play a few minutes then
  repeat the same content — caused by the HLS playlist itself being cached alongside media
  segments; live playlists must always be fetched fresh, so they're now routed around the
  disk cache while segments still cache normally for DVR rewind.
- **Fixed**: D-pad up/down on TV stopped changing channels after the first press, since the
  channel-change overlay was popping open and eating the next key press.
- **Fixed**: D-pad Right from the TV sidebar couldn't reach the FULL SCREEN/REFRESH header
  buttons.
- **Fixed**: the focused row in the TV channel list would visibly flicker on every EPG/health
  refresh tick, even when that row's own data hadn't changed.
- **Fixed**: the movie/series timeline (progress bar + elapsed/remaining time) wasn't visible
  during playback — it was nested inside the auto-hiding overlay instead of staying
  persistently on screen like a normal video player's scrub bar.
- **Fixed**: resuming a movie or series episode from the mini player (via the fullscreen
  button, tapping the mini player itself, or the PiP corner) could restart from the
  beginning instead of resuming — a URL-pattern check used to tell VOD from live content
  missed series episode URLs and dropped the saved resume position.
- **Fixed**: the "● LIVE" button (jump to live edge) could land back at the start of the
  buffered window instead of "now," since it relied on live-edge detection some providers'
  playlists don't signal correctly.

## v3.94 - 2026-07-08
- **Fixed**: Movies and Series were never actually loading their content (only categories) —
  the sync logic now fetches the real catalogs automatically on first run.
- **Fixed**: "USA Channels Only" was incorrectly wiping out all Movie/Series categories,
  since that filter only makes sense for live TV. Movies/Series categories are no longer
  affected by it.
- **Fixed**: a real out-of-memory crash on large catalogs (100k+ items) — movie/series sync
  now processes in chunks instead of holding the entire catalog in memory at once, runs
  sequentially instead of concurrently with other fetches, and the app now requests a
  larger heap.
- **Fixed**: rotating the phone during playback could reset the mini-player back to a live
  channel, losing what was playing.
- **Fixed**: a crash when navigating the TV channel list with the D-pad if focus landed on
  a nested element (like the favorite-star button) instead of the row itself.
- **Fixed**: a crash when reordering Favorites (drag-and-drop) if a background update
  touched the shared channel list mid-drag.
- **New**: a progress bar shows real save-progress ("Loading movies… 45000/175327") during
  first-time or manual Movies/Series sync.
- **New**: Movies can now be sorted by rating, year (newest/oldest), or recently added
  (Movies tab, new sort icon in the top bar).
- **New**: while playing a movie/episode, elapsed and remaining time now show next to the
  seek bar.
- **New**: rewind/fast-forward on movies and episodes now accelerates from 10s to 30s per
  tap if you tap more than 10 times in a row — applies to both touch and TV remote D-pad.

## v3.93 - 2026-07-08
- Trakt login/scrobbling is now fully working end-to-end — the secret-holding proxy moved
  from Cloudflare Workers (which Trakt's own anti-bot protection blocks outright) to Deno
  Deploy, which isn't blocked. If you already deployed the Cloudflare version, redeploy
  using cloudflare/trakt-proxy-deno.ts on Deno Deploy instead.

## v3.92 - 2026-07-08
- Trakt is now available on the phone too (Settings > Trakt) — same device-code login and
  scrobbling as TV. Previously it was only wired up in TV Settings.

## v3.91 - 2026-07-08
- Version bump only — v3.90 was tagged but never had a working release published (blocked
  mid-ship while fixing a Trakt credential exposure issue). This is the first real release
  containing v3.90's changes: the Silent Self-Update setting and the Trakt proxy fix below.

## v3.90 - 2026-07-08
- New optional setting (Settings > Updates): "Silent Self-Update" — off by default. When
  enabled on Android 12+, in-app updates install via PackageInstaller.Session instead of
  the classic system installer intent, which can skip the visible scan/confirmation
  screen when the OS recognizes MKTV as its own installer of record. Leave it off to keep
  the standard visible install confirmation for every update.
- **Security**: Trakt's client_secret is no longer compiled into the app at all — token
  exchange/refresh now goes through a small self-hosted Cloudflare Worker proxy
  (cloudflare/trakt-proxy-worker.js) that holds the secret server-side, so nothing
  sensitive can be extracted from the public APK. Requires setting TRAKT_PROXY_URL in
  local.properties after deploying the worker.

## v3.89 - 2026-07-08
- **New**: Trakt.tv scrobbling — connect via device-code login (Settings > Trakt) and your
  watch activity on movies/episodes is automatically tracked. Requires a free Trakt API app;
  see Settings > Trakt for status if not yet configured.
- **New**: AMOLED Black display option — pure black backgrounds for OLED screens (Settings >
  Display).
- **New**: Subtitle customization — size, vertical offset, bold, text/background/outline
  color (Settings > Subtitles).
- **New**: Tunneled Playback and DV7→HEVC Fallback toggles (Settings > Stream) for
  device-specific playback tuning.

## v3.88 - 2026-07-08
- **New**: Live TV DVR — pause and rewind live channels using a local disk buffer (no
  provider support needed). Rewind button jumps back 60s; "● LIVE" button jumps back to
  the live edge and dims once you're caught up.
- Fixed the mini-player preview (name/EPG/progress) staying stuck on the last channel you
  scrolled past instead of resetting to what's actually playing when you back out to the
  sidebar or Categories panel
- TV Settings: EPG and Sync sections now have collapsible sub-groups (Sources/Refresh,
  Pairing/Actions) to keep related settings together
- Phone Settings: Display, Updates, and Sync panels now use the same collapsible card
  style as the Stream panel

## v3.87 - 2026-07-07
- Release APKs are now signed with both the v1 and v2 (and v3) signature schemes instead of
  v2 only, which can reduce Play Protect/antivirus warnings on sideload installs. Same
  signing key — updates still install over previous versions with no reinstall needed.

## v3.86 - 2026-07-07
- Fixed a silent XMLTV parsing failure: gzip detection now sniffs the actual magic bytes
  instead of trusting the server's Content-Encoding header/URL suffix, so a provider that
  serves gzip without setting the header no longer causes EPG refresh to quietly return 0
  programs
- Backup & Restore now includes watch history (last-watched time, view count) alongside
  favorites — restores merge against your current channel list the same safe way favorites
  already do
- **Security**: in-app updates now verify the downloaded APK's SHA-256 checksum before
  installing (when the update manifest provides one) — a corrupted or tampered download is
  discarded instead of silently installed
- Fixed a latent race where a fast download could trigger the update installer twice

## v3.85 - 2026-07-07
- Fixed the in-app "What's New" changelog viewer always showing "not available" — it reads
  CHANGELOG.md from the app's assets folder, but that folder was empty; the changelog was
  only ever being maintained at the project root for GitHub release notes. Build now
  auto-copies it into assets every time, so this can't silently drift out of sync again.

## v3.84 - 2026-07-07
- **Security**: fixed credential exposure through crash reports — Xtream stream URLs embed
  the account's plaintext username/password in their path, and network/player exceptions
  routinely include the failing URL; the crash handler now redacts these before they're
  ever written to disk, with a second redaction pass at the "Send Debug Report" upload
  boundary for defense in depth
- **Security**: `AutoBackupWorker`'s periodic backup no longer writes plaintext credentials
  to a public MediaStore Downloads folder any app with storage/media permissions could
  read — moved to app-private storage
- **Security**: the manual Backup/Restore feature (phone Settings) now uses Android's
  Storage Access Framework instead of auto-writing to public storage — the user explicitly
  picks the save/open location, keeping full portability without the exposure
- TV guide: replaced the scrollable proportional-width timeline grid with a plain NOW/NEXT
  text list — the grid had recurring alignment/navigation bugs from keeping a header ruler,
  N independently-scrolling rows, and live background refreshes all in sync; the simple
  list can't desync because there's no shared timeline to keep in sync in the first place
  (the grid version remains available in git history)
- TV guide: fixed only showing the first ~50 channels of a list (alphabetically) even
  though the rest already had EPG data cached — the fetch-triggering window and the
  displayed-text computation were wrongly capped together; fetching stays bounded (to
  avoid flooding the server for huge categories) but display now covers every channel
- TV: auto-refresh EPG job wasn't being re-asserted on app launch the way the phone always
  has — if Android (or a TV-box "clear background apps" tool) force-stopped the app, which
  silently cancels all its scheduled jobs, auto-refresh would just stop forever until the
  user happened to reopen Settings and re-touch the option. TV now re-asserts it on every
  launch, same as the phone always did
- TV recordings: a recording whose service got killed mid-capture left an orphaned,
  truncated `.pending` file that never showed up in Gallery and just sat there consuming
  storage forever; the stale-recording sweep now deletes it instead of just marking it failed
- TV home: D-pad Right no longer opens sidebar sections/categories (only OK does); Right/
  Left now only move focus, matching how Left already worked
- TV home: fixed the channel list jumping focus to the back button while scrolling —
  Android's default focus search can fail mid-scroll when new items aren't laid out yet
  and escapes the list entirely; now uses the same deterministic position-based focus
  movement already used for the guide
- TV Settings: Back button now steps back one level instead of exiting immediately
- Phone: fixed the Series tab's search silently searching channels instead of series
  (the DAO query existed but was never wired up)

## v3.83 - 2026-07-06
- Recordings now auto-compress after capture: the raw file is recorded exactly as before (no live-transcode risk), then re-encoded in the background at a "medium" bitrate tier (2.8 Mbps 1080p / 1.6 Mbps 720p / 900 Kbps SD) once the capture finishes safely. The raw original is deleted only after the compressed file is verified — a failed compression just leaves the recording at its original size, never loses it
- New "COMPRESSING" status shown in the recordings list (purple) between RECORDING and DONE; stale-recording cleanup now tells a genuinely failed capture apart from one that was safely captured but never finished compressing
- TV recordings list: added file size, a working Play button (the whole row), and a Share button for finished recordings — matching the phone
- Phone recordings list: added file size; fixed Play using a hardcoded .ts mime type, which broke playback for compressed .mp4 recordings
- TV Settings: replaced the single ~30-row scrolling list with a two-level menu — a short list of sections (Stream, Display, EPG, Updates, Backup, Servers, Account, Sync), drill into one to see just its settings
- TV Settings: added an Accent Color picker (Display section) — same 7 colors as the phone
- TV home: applies the chosen accent color to the sidebar, header buttons, progress bars, and focus rings (previously hardcoded blue everywhere)

## v3.82 - 2026-07-06
- TV guide: fixed D-pad LEFT/RIGHT getting stuck deep in a row (Android's default focus search can't find off-screen items) — now uses deterministic position-based focus movement
- TV guide: extended to include ~3 hours of history so the timeline can actually be scrolled back, with cross-row time alignment preserved
- TV guide: added a NOW button (next to FULL SCREEN) to jump back to the current time, and a REFRESH GUIDE button to force-reload EPG data
- TV home: D-pad LEFT no longer drills back a screen (sidebar/categories/channels) — only the Back button does; double-press Back at the top level to exit, with a confirmation toast
- TV home: fixed selecting a channel in Live/Categories/Favorites knocking focus up to the back button — ChannelAdapter was using notifyDataSetChanged() which detaches/reattaches views; switched to targeted notifyItemRangeChanged
- TV home: selecting a channel in Favorites/Live/Categories now scrolls the EPG guide to that channel's row automatically
- TV home: fixed mini player showing a different "now playing" title than the guide — it was fetching EPG independently instead of using the same shared data source
- TV home: fixed double-click-to-fullscreen not working — the double-click timer was being reset on every EPG refresh rebind
- TV recording: fixed recordings stuck showing "RECORDING" forever if the service was killed mid-recording (added the same stale-recording sweep the phone scheduler already had)
- TV recording: fixed two recordings scheduled at the same time corrupting each other's wakelock, which could cut a recording short — now tracked per-recording instead of in shared fields
- TV recording: added a ★ FAVORITES folder pinned to the top of the category list, containing your favorite channels, for quick access when scheduling
- TV recording: fixed duration entry text being invisible — the app's DayNight theme was following the system's light/dark setting for stock dialogs while every custom screen stayed hardcoded dark; now forced dark everywhere
- Player: gesture volume/brightness controls now show a live percentage readout, icons that change with the level (mute/low/high), a small dead-zone so a stray tap doesn't nudge the level, and a haptic tick when you hit 0% or 100%

## v3.81 - 2026-07-06
- TV guide: replaced fixed hourly columns with a proportional-width grid, like the phone's grid feature — each program block's width reflects its actual duration, current program shrinks as it plays, D-pad scrolls smoothly through upcoming shows, all channel rows stay time-aligned

## v3.80 - 2026-07-06
- TV guide: hourly view — each channel row now shows NOW + next 3 full-hour slots (e.g. 8 PM, 9 PM, 10 PM) with time labels in the header

## v3.79 - 2026-07-06
- TV guide: fix channels with no EPG data appearing in guide list (were showing "No guide data" which bypassed the filter)

## v3.78 - 2026-07-06
- TV: dedicated D-pad native recording scheduler — full-screen step-by-step flow: category → channel → date/time (NumberPickers) → duration → schedule; replaces the phone-style dialog on TV

## v3.77 - 2026-07-06
- Recording scheduler: only US| categories and channels shown (phone and TV)

## v3.76 - 2026-07-06
- Fix crash opening recording scheduler on large channel lists (SQLite variable limit exceeded with 55k+ channel IDs)

## v3.75 - 2026-07-06
- TV sidebar: app icon + MKTV wordmark with live clock, properly sized for Shield remote navigation

## v3.74 - 2026-07-06
- TV sidebar: replaced MKTV text with app logo (initial attempt)

## v3.73 - 2026-07-06
- TV player: press Yellow button (or X/F key) on remote to instantly cycle aspect ratio (Best Fit → Zoom → Stretch) without opening the control overlay

## v3.72 - 2026-07-06
- Fix EPG auto-refresh: was silently skipping all channels due to US-only category filter; now refreshes all favorited channels
- Fix recordings channel list: same filter bug — recordings now shows all live channels and categories
- Fix backup QR code: was too large/dense to scan; now encodes only login credentials as a compact URL — always scannable with phone camera
- Scanning the QR with phone camera opens MKTV app and pre-fills the login form
- TV sidebar: replaced "MKTV" text with app logo image

## v3.71 - 2026-07-06
- TV EPG guide: DPad right from channel list moves focus into the EPG guide; DPad left or Back returns to channel list
- TV EPG guide: timeline header above guide shows NOW and NEXT (+2h) times, updated every 30s
- TV home: FULL SCREEN button relocated to header row (was inside mini player info panel)
- TV home: EPG progress bar moved to full-width strip directly under mini player (matches video width)
- TV EPG guide: filter improved to exclude blank and placeholder entries

## v3.70 - 2026-07-06
- TV home: remove icon from RECORDINGS sidebar button

## v3.69 - 2026-07-06
- TV home: added RECORDINGS button to sidebar — opens recording scheduler directly from Shield remote
- TV home: EPG guide only shows channels that have EPG data

## v3.68 - 2026-07-06
- TV home: left panel drill-down nav — sidebar → categories → channels; back button goes up one level
- TV home: DPad up/down stays within sidebar (never drifts right)
- TV home: right panel replaced with live EPG guide (channel + now/next programs)
- TV home: removed Guide button from sidebar

## v3.67 - 2026-07-06
- Settings → Display: accent color picker — choose from 7 colors (Blue, Red, Green, Purple, Orange, Pink, Teal); applies immediately to tab indicator, sidebar buttons, mini player progress, and EPG text; persists across restarts

## v3.66 - 2026-07-05
- Mini player: stop retrying after 5 errors on a bad stream

## v3.65 - 2026-07-05
- Fix home screen mini player: remove stale retained-player logic, add reactive auto-play from recentChannels observer

## v3.64 - 2026-07-05
- Fix home screen mini player: cancel racing load jobs, skip re-prepare when player already active after rotation

## v3.63
- player: live streams now retry indefinitely on connection loss (backoff ramps to 30s then holds) — no more "stream unavailable" dead ends on spotty car/WiFi connections; VOD still stops at 5 attempts since VOD URLs can expire

## v3.62
- player: reverted fullSensor orientation (was breaking playback on phones and car boxes) — back to landscape lock which is stable on all devices; home screen rotation is handled separately by retained mini player

## v3.61
- player: if VLC or MX Player is selected but not installed, automatically switch back to built-in player and save the preference — no more system chooser popup

## v3.60 - 2026-07-05
- Update to v3.60

## v3.59
- recording: fix foreground service type mismatch (was mediaPlayback, now dataSync) — on Android 14 this caused startForeground to fail silently and recordings never started
- recording: acquire wake lock for the duration of the recording so the CPU stays awake and the network connection isn't dropped
- recording: switch to START_REDELIVER_INTENT so the service restarts with the original recording intent if the OS kills it mid-recording

## v3.58
- home: mini player video continues through screen rotation — player instance is retained across activity recreation instead of being destroyed and rebuilt

## v3.57
- player: reverted TextureView (caused blank video on many devices) — back to SurfaceView; rotation handled by lifecycle guard instead

## v3.56
- player: player is no longer released during config changes — video keeps playing seamlessly through rotation
- player: expanded configChanges coverage to handle Samsung/LG OEM rotation edge cases

## v3.55
- player: video now plays through screen rotation — portrait and landscape both work; no more video stopping when you rotate the phone

## v3.54
- player: auto-reconnect capped at 5 attempts instead of 20 — stops retrying sooner on dead streams
- TV: channel number jumping — type digits on the remote (e.g. 5, 12, 100) to jump to that channel position in the list
- EPG: tap a future show to get "Record" or "Remind Me" options — Record opens the scheduler and auto-schedules the recording

## v3.53
- TV: Pre-warm Streams on Focus — starts resolving a stream URL when a channel tile receives focus, before you press play; speeds up channel start on TV remotes
- TV Settings: new toggle for Pre-warm Streams on Focus (default ON); turn OFF to disable background stream checks on metered or slow connections

## v3.52 - 2026-07-05
- TV home: Guide section now shows full channel list instead of opening a separate EPG screen
- TV home: grid view toggle (⊞) appears next to the Guide tab when Guide is selected
- TV home: grid view shows channel logos in a 4-column tile layout; long-press Guide to open the EPG timeline
- player: overlay background tint removed — controls float over video without dimming the screen
- fix: bump script no longer adds duplicate changelog entries when notes are pre-written

## v3.51 - 2026-07-05
- fix: zoom/resize button moved into bottom control bar — now fully reachable via D-pad and reliable on Android TV
- fix: proper changelog notes added to all recent versions

## v3.50 - 2026-07-05
- fix: update no longer requires pressing twice — app auto-resumes download on return after granting install permission
- player: back button while overlay is open dismisses overlay only; double-tap back exits to home

## v3.49 - 2026-07-05
- player: D-pad up = next channel (higher), D-pad down = previous — matches standard TV remote direction
- fix: zoom/resize button click handling improved; requestLayout forces video surface to redraw

## v3.48 - 2026-07-05
- player overlay: all buttons (Guide, Back, Sleep, Play/Pause, CC, Stats, Resize) show blue focus ring on D-pad selection
- TV home: app now opens on Favorites tab by default
- TV home: fix D-pad drifting back to Live section — channel list observer now guards against wrong-section updates
- TV home: exiting fullscreen resumes mini-player on the correct channel (was always reverting to previous)

## v3.47 - 2026-07-05
- TV home: removed mic/voice search button — Google Assistant handles voice at system level
- player: D-pad navigates overlay buttons when overlay is open (up/down/left/right move focus between controls)
- player: overlay auto-hides 5 seconds after last D-pad press, not from when it first opened

## v3.46 - 2026-07-05
- removed voice search mic button from phone and TV home (Google Assistant already built into SHIELD)

## v3.45 - 2026-07-05
- XMLTV EPG: automatically fetches xmltv.php from your provider during every EPG refresh for richer guide data

## v3.43 - 2026-07-04
- fix: recordings stuck as "RECORDING" — service kill now writes FAILED status synchronously in onDestroy
- fix: opening Recordings screen auto-clears any entry still showing RECORDING past its expected end time

## v3.42 - 2026-07-04
- TV guide: pressing Guide now opens the full-screen EPG timeline (same as phone) instead of crashing
- TV fullscreen player: D-pad up/down changes channel immediately without needing overlay open first
- TV focus: hovering a channel row updates the info panel (name + EPG) without loading video; press OK to play
- removed: grid logo toggle and hints bar

## v3.41 - 2026-07-04
- TV home revamp: clock in sidebar, Now & Next EPG row expands on focus, auto-preview info on D-pad hover
- TV remote shortcuts: D-pad left returns to sidebar, Guide key handled, up/down channel nav in player

## v3.40 - 2026-07-04
- fix: SHIELD Guide button no longer backgrounds the app; shows overlay in player

## v3.39 - 2026-07-04
- fix: crash in getEpgForStreams - migrate epg_entries schema to add missing nowPlaying/hasArchive columns

## v3.38 - 2026-07-04
- settings nav: Refresh EPG + Check Update buttons below Logout

## v3.37 - 2026-07-04
- recordings: cloud export via share sheet; TV settings: EPG refresh + update check under Logout

## v3.36 - 2026-07-04
- recording channel picker: category folders + search across all channels

## v3.34 - 2026-07-04
- add EPG now-playing info to recording channel picker

## v3.33 - 2026-07-04
- fix: recording scheduler top bar no longer hidden under status bar

## v3.32 - 2026-07-04
- fix: rebuild with v3.30 rotation and fullscreen-exit Favorites fixes included

## v3.31 - 2026-07-04
- fix: rebuild with v3.30 rotation and fullscreen-exit Favorites fixes included

## v3.22 - 2026-07-03
- fix landscape video not showing - remove hardcoded height override on mini player container

## v3.21 - 2026-07-03
- store last selected tab in ViewModel so rotation always restores correct tab and category

## v3.20 - 2026-07-03
- fix rotation resetting tab/category; playing channel scrolls into view when list collapses

## v3.19 - 2026-07-03
- currently playing channel always highlighted in list (on load, resume, guide tap, and channel select)

## v3.18 - 2026-07-03
- landscape channel list expands to 6 rows on scroll, collapses after 5s idle or channel tap

## v3.17 - 2026-07-03
- v3.16: fix return from fullscreen - instant scroll to channel, preserve tab and category

## v3.16 - 2026-07-03
- v3.15: remove playback speed button from player overlay

## v3.15 - 2026-07-03
- v3.14: returning from fullscreen restores the active tab and scrolls to the playing channel

## v3.14 - 2026-07-03
- v3.13: health badge background transparent; revert accidental EPG bar change

## v3.13 - 2026-07-03
- v3.13: EPG progress bar on channel rows made transparent

## v3.12 - 2026-07-03
- v3.12: landscape expanded channel list fixed to 4 rows (300dp)

## v3.11 - 2026-07-03
- v3.11: landscape expanded channels take 2/3 height (4 rows), mini player shrinks to 1/3

## v3.10 - 2026-07-03
- v3.10: landscape toggle button moves into left black bar of mini player; channel list auto-collapses after 5s idle

## v3.9 - 2026-07-03
- v3.9: landscape mode auto-collapses channel list when playing; tap toggle to expand

## v3.8 - 2026-07-03
- Update to v3.8

## v3.7 - 2026-07-03
- Update to v3.7

## v3.6 - 2026-07-03
- Update to v3.6

## v3.5 - 2026-07-03
- Update to v3.5

## v3.4 - 2026-07-03
- Update to v3.4

## v3.3 - 2026-07-02
- Update to v3.3

## v3.2 - 2026-07-02
- Update to v3.2

## v3.0 - 2026-07-02
- fix sync pairing code resolution via lookup table; short code now correctly maps to full Firebase UID

## v2.100 - 2026-07-02
- replace GitHub Gist sync with Firebase Firestore; pairing code to sync between devices

## v2.98 - 2026-07-02
- debug reports now sent to Discord via Captain Hook; no token required

## v2.96 - 2026-07-02
- sync via public Gist; debug copies to clipboard when no token set

## v2.95 - 2026-07-02
- add GitHub token entry to Sync settings (phone + TV); classic token required for gist sync

## v2.94 - 2026-07-02
- fix genre chips hiding on Favorites/Guide; add genre chips to landscape categories column

## v2.93 - 2026-07-01
- landscape: hide categories column + bigger mini player when no categories shown

## v2.92 - 2026-07-01
- Update to v2.92

## v2.91 - 2026-07-01
- remove fullscreen zoom button from portrait layout; tap mini player to go fullscreen instead

## v2.90 - 2026-07-01
- top bar layout: settings and refresh anchored left, search and mic pinned right

## v2.88 - 2026-07-01
- fix tab/button hiding on both phone and car box (repeatOnLifecycle + width=0); fix fullscreen retry loop with 400ms delay before PlayerActivity launch

## v2.86 - 2026-07-01
- car box: hide Movies/Series sidebar when unchecked in settings; fix full-screen playback retry by stopping mini player before launch

## v2.85 - 2026-07-01
- fix Movies/Series/History tabs not hiding on car box; fix full-screen playback retry loop by releasing mini player stream before launch

## v2.84 - 2026-07-01
- fix channel list on car box: proportional 3-column layout, correct tab mapping, auto-select first category on load

## v2.83 - 2026-07-01
- mini player larger on tablet/landscape; genre filter chips in Live tab; best fit/zoom/stretch resize modes with toast; restore picker opens at storage root; M3U channels now visible on home screen; mini player stays on full-screen channel when returning; token no longer compiled into APK

## v2.81 - 2026-06-30
- **Splash screen**: version label now reads from BuildConfig instead of a hardcoded string, so it never goes stale again

## v2.80 - 2026-06-30
- **Auto Backup**: weekly backups now save to Downloads/MKTV (public storage) instead of the app's hidden Android/data folder, so they're visible in file managers and the in-app Restore picker
- **Release build**: fixed missing dataSync foregroundServiceType causing lint failures

## v2.79 - 2026-06-30

## v2.78 - 2026-06-30
- **Recording Scheduler**: Schedule recordings by channel, start time, and duration Ã¢â‚¬â€ saved as .ts files to Movies/MKTV/ on device
- **Ã¢ÂÂº REC button**: Added to top bar on all screen variants (phone portrait, landscape, TV, tablet)
- **PiP corner**: Mini player collapses to bottom-right corner so you can browse channels while a stream keeps playing
- **Channel change OSD**: D-pad up/down flips channels with a 2.5s overlay showing channel name + current EPG program and progress bar

## v2.77 - 2026-06-29
- **Chromecast**: Removed cast debug toasts; cast is now production-ready

## v2.76 - 2026-06-29
- **Chromecast**: Fetch EPG before casting so program info appears on TV; proxy rewrites subtitle track URIs in m3u8 tags

## v2.75 - 2026-06-29
- **Chromecast**: Send current EPG program title as subtitle track in cast metadata

## v2.74 - 2026-06-29
- **Chromecast**: Switching channels while casting now reloads stream on the Chromecast

## v2.73 - 2026-06-29
- **Chromecast**: Resolve segment URLs from the final redirect URL, not the original m3u8 Ã¢â‚¬â€ fixes streams that redirect before serving

## v2.72 - 2026-06-29
- **Chromecast**: Forward Chromecast user-agent to IPTV server so segments aren't blocked

## v2.71 - 2026-06-28
- **Chromecast**: CORS proxy now forwards Cloudflare session cookies from m3u8 to segment requests

## v2.70 - 2026-06-28
- **Chromecast**: Proxy logs full URL, content-type, and m3u8 preview to logcat; detects playlists by content peek

## v2.68 - 2026-06-28
- **Chromecast**: Proxy adds CORS headers to m3u8 only; segments go direct to IPTV server (native HLS skips CORS)

## v2.66 - 2026-06-28
- **Auto-play next episode**: 10-second countdown card at end of each series episode then auto-advances

## v2.65 - 2026-06-28
- **Notifications**: Request POST_NOTIFICATIONS permission at runtime so EPG reminders actually fire on Android 13+

## v2.64 - 2026-06-28
- **Chromecast**: Live streams now use STREAM_TYPE_LIVE Ã¢â‚¬â€ fixes stall caused by missing EXT-X-ENDLIST on live HLS

## v2.60 - 2026-06-27
- **Chromecast**: Local CORS proxy fixes IDLE_REASON_ERROR; proxy detects device IP via NetworkInterface scan

## v2.58 - 2026-06-27
- **Chromecast**: Cast status check added; buffer health badge hides while casting

## v2.57 - 2026-06-27
- **Chromecast**: Fix cast session missed when media route picker caused onPause

## v2.56 - 2026-06-27
- **TV Settings**: Fix D-pad sidebar navigation Ã¢â‚¬â€ focus chain was broken after Settings redesign

## v2.54 - 2026-06-27
- **Sync**: GitHub token input added to TV Settings sync panel

## v2.53 - 2026-06-27
- **Chromecast**: Fix playback Ã¢â‚¬â€ contentId and session wiring corrected; cast button now hides with player controls

## v2.52 - 2026-06-27
- **Sync**: Auto-discovers existing Gist on a second device Ã¢â‚¬â€ no need to manually enter Gist ID

## v2.51 - 2026-06-27
- **EPG**: Fix wrong program showing in player overlay and mini player info bar

## v2.50 - 2026-06-26
- **Security**: GitHub token removed from compiled APK; stored in DataStore at runtime only

## v2.49 - 2026-06-26
- **UI**: Settings headers use wrap_content; split view renamed; login and mosaic layout cleanup

## v2.48 - 2026-06-26
- **Guide**: Fix grid always opening same channel Ã¢â‚¬â€ onResume was racing the ActivityResult callback; fixed with suppressMiniAutoResume flag

## v2.47 - 2026-06-26
- **Guide**: Tapping a program in the grid now opens it in the mini player instead of full player

## v2.46 - 2026-06-26
- **Guide**: Grid view supports fullscreen tap-to-play for any channel or program slot

## v2.45 - 2026-06-26
- **Guide**: Fix history tab showing stale data and channels jumping position; fix guide EPG not reloading after refresh

## v2.43 - 2026-06-26
- **Guide**: No longer re-fetches EPG on every visit Ã¢â‚¬â€ uses cached data, much faster to open

## v2.42 - 2026-06-26
- **Guide**: Timeline scrolls to current time on open; past programs filtered out

## v2.41 - 2026-06-26
- **Channels**: Instant load from local cache with refresh button; background sync updates silently

## v2.40 - 2026-06-26
- **Guide**: Channels without EPG data are hidden from the guide grid

## v2.39 - 2026-06-26
- **Auto-backup**: Weekly automatic backup runs in background via WorkManager
- **Onboarding**: First-run feature tour dialog highlights key features for new users

## v2.38 - 2026-06-26
- **Performance**: Instant channel load from cache; network sync runs in parallel background thread

## v2.37 - 2026-06-26
- **Bulk favorites**: Long-press to select multiple channels and favorite/hide them at once
- **Channel hide**: Hide channels you never watch Ã¢â‚¬â€ accessible via filter toggle
- **History tab**: New tab showing recently watched channels
- **Similar channels**: Player suggests similar channels when stream ends
- **Buffer health badge**: Live indicator in player shows buffering quality (Good / Weak / Poor)
- **TV home screen**: Favorite channels published to Android TV home screen row

## v2.36 - 2026-06-26
- **TV Settings**: Full redesign Ã¢â‚¬â€ blue left-bar active indicator, D-pad focus chain on all 8 nav items, Enter key jumps to content panel

## v2.35 - 2026-06-25
- **UI**: Top bar flush to screen edge; status bar hidden edge-to-edge

## v2.34 - 2026-06-25
- **Fullscreen**: Status bar and nav bar hidden; swipe down from top to peek at system UI

## v2.33 - 2026-06-25
- **Landscape**: Landscape layout added for phones Ã¢â‚¬â€ vertical sidebar + smaller mini player

## v2.32 - 2026-06-25
- **Speed test**: Built-in speed test in settings
- **Reconnect**: Improved reconnect logic with exponential backoff
- **Reminders**: Set EPG reminder for upcoming programs
- **What's On Now**: Quick-access panel showing what's currently airing across favorites

## v2.31 - 2026-06-25
- **Settings**: Fixed all settings bugs Ã¢â‚¬â€ status messages restored, changelog accessible, backup scrollable

## v2.28 - 2026-06-25
- **Channel Mosaic**: Multi-stream grid view Ã¢â‚¬â€ watch up to 4 channels simultaneously in a 2Ãƒâ€”2 grid

## v2.27 - 2026-06-25
- **EPG Timeline**: Full grid guide showing current + upcoming programs across all channels with horizontal scrolling
- **Channel Timers**: Set a timer to switch to a channel when a specific program starts

## v2.26 - 2026-06-25
- **Hero banner**: Channel logo and full EPG description shown when a channel is selected
- **Stream stats overlay**: Tap info button in player to see bitrate, resolution, dropped frames, buffer level

## v2.25 - 2026-06-25
- **External player**: Option to open streams in VLC or other installed players
- **Voice search**: Tap mic button to search channels by voice
- **Release signing**: Dedicated keystore for consistent signed builds across devices

## v2.24 - 2026-06-25
- **Android TV UI**: Dedicated leanback home screen with D-pad-optimised channel grid
- **Home screen widget**: Current EPG info widget for Android home screen
- **Favorites drag reorder**: Long-press drag to reorder favorite channels
- **Timeshift replay**: Replay last N minutes of a live channel (where provider supports it)
- **Player retry countdown**: Visual countdown before auto-retry on stream failure

## v2.23 - 2026-06-25
- **Stream health checker**: Background monitor detects dead streams and flags them
- **Chromecast**: Cast live TV to any Chromecast on the same network
- **Mini player EPG**: Current program name and progress bar shown below mini player

## v2.22 - 2026-06-25
- **M3U Playlist import**: Load any M3U/M3U8 playlist by URL or local file Ã¢â‚¬â€ works alongside Xtream Codes
- **Player: Audio & subtitle track selection**: Tap CC button to pick audio language or subtitle track
- **Player: Playback speed control**: 0.25Ãƒâ€” to 2Ãƒâ€” speed selector (great for VOD)
- **Player: Sleep timer**: Auto-stops playback after 15/30/60/90/120 minutes
- **Player: Brightness/volume gestures**: Swipe left side vertically to adjust brightness, right side for volume
- **Player: Buffering indicator**: Clear spinner replaces invisible wait state
- **Player: DASH & SmoothStreaming**: Added MPEG-DASH and Smooth Streaming codec support
- **Series detail view**: Tapping a series now opens a full episode browser organized by season
- **WATCHING tab**: New tab showing all in-progress VOD with resume progress bars
- **VOD search**: Search bar now filters movies when on the Movies tab
- **VOD progress bars**: Watch progress visible on every movie card
- **Dependencies**: Media3 1.4.1, Material 1.12.0, Room 2.7.1, Lifecycle 2.8.7

## v2.21 - 2026-06-25 17:49
- Add Multi-view, Smart EPG progress bars, Cross-device sync

## v2.20 - 2026-06-25 16:40
- Redesigned TV settings with full phone feature parity: EPG refresh with live progress, auto-refresh schedule, format toggle, version display, full server management, improved visual layout

## v2.19 - 2026-06-25 15:42
- Search bar moved to top right with X clear button; large screens (600dp+) treated as TV

## v2.18 - 2026-06-25 11:26
- Fix Android TV settings - proper layout, D-pad navigation, QR backup, Updates section

## v2.17 - 2026-06-25 10:13
- Fix server switching - servers now save, display, and switch correctly

## v2.16 - 2026-06-25 09:47
- Fix servers section missing from layout, add sectionServers with rvServers and btnAddServer

## v2.15 - 2026-06-25 09:28
- Fix server switch - clear DB and swap credentials on toggle

## v2.14 - 2026-06-25 09:23
- Add Switch button for server switching, fix crash on server swap

## v2.13 - 2026-06-24 19:56
- Phone backup uses file picker, TV boxes use QR code

## v2.12 - 2026-06-24 19:50
- Fix restore to use file picker on phone instead of hardcoded filename

## v2.11 - 2026-06-24 19:44
- Backup uses file picker on phone, QR code on TV boxes

## v2.10 - 2026-06-24 19:42
- Backup uses file picker on phone, QR code on TV boxes

## v2.9 - 2026-06-24 19:04
- Fix restore file picker, favorites sort to top, global search with US filter, guide hides channels without EPG, dpad and swipe up/down channel switching, version number in settings

## v2.7 - 2026-06-23 17:09
- Disable VOD/Series fetch to prevent OOM crash, fix debug report token, search debounce, restore favorites fix

## v2.6 - 2026-06-23 16:34
- Fix backup/restore with favorites and categories, live search with debounce, channel highlight on home screen

## v2.4 - 2026-06-23 11:53
- Release v2.4

## v2.3 - 2026-06-23 11:49
- Guide shows favorited category channels, currently playing channel highlighted, debug report token fixed

## v2.2 - 2026-06-23 09:38
- Fix update download, debug logging for URL resolution

## v2.1 - 2026-06-23 02:28
- Switched to semantic versioning

## v1.1 (build 37) - 2026-06-23 02:26
- Version 2.0 - MKTV rebrand, portrait UI, mini player, VOD progress, collapsible settings, backup/restore, debug reports, Android TV support

## v1.1 (build 35) - 2026-06-23 02:02
- Collapsible settings sections, crash log in debug report, QR backup with MKTV logo, timestamped backup files

## v1.1 (build 34) - 2026-06-23 01:02
- VOD progress tracking and resume, seek bar, QR backup, backup/restore settings with credentials and favorites, Android TV/Shield support, D-pad focus highlight

## v1.1 (build 33) - 2026-06-22 17:20
- Play/pause button in player, single tap channel switching, no double tap needed

## v1.1 (build 32) - 2026-06-22 17:07
- Movies/Series tab toggles in settings, category long-press restored, mini player keeps playing when going to settings, EPG skips errored channels

## v1.1 (build 29) - 2026-06-22 15:32
- Portrait mode home/login, mini player on home screen, single tap plays in mini player, double tap opens fullscreen

## v1.1 (build 28) - 2026-06-22 14:18
- MKTV rebrand: app name and icon updated

## v1.1 (build 27) - 2026-06-22 13:52
- Port rkinnc fixes: playback retry, ExoPlayer lifecycle, UpdateChecker hardening, EPG retry, response checks, observer dedup

## v1.1 (build 26) - 2026-06-19 17:37
- Long-press or star tap to favorite channels; favorites tab shows flat channel list

## v1.1 (build 25) - 2026-06-19 02:01
- Fix in-app install trigger using progress polling instead of broadcast receiver

## v1.1 (build 24) - 2026-06-19 01:55
- New CRT retro app icon

## v1.1 (build 23) - 2026-06-19 01:22
- Fix GitHub redirect issue for in-app APK download

## v1.1 (build 22) - 2026-06-19 01:14
- Fix OOM crash caused by OkHttp BODY logging on large VOD/series responses

## v1.1 (build 21) - 2026-06-19 01:03
- Fix VOD and series loading; fetch in background to keep live channels fast

## v1.1 (build 20) - 2026-06-19 00:43
- Fix in-app install on Android 15

## v1.1 (build 19) - 2026-06-19 00:38
- USA filter now applies instantly without restarting app

## v1.1 (build 18) - 2026-06-19 00:28
- Fix in-app install permission for Android 15

## v1.1 (build 17) - 2026-06-19 00:23
- Add download progress bar for in-app updates

## v1.1 (build 16) - 2026-06-19 00:15
- Version display fix, in-app updater improvements

## v1.1 (build 15) - 2026-06-19 00:04
- Test in-app update download

## v1.1 (build 14) - 2026-06-18 23:33
- Add check for updates in settings

## v1.1 (build 13) - 2026-06-18 23:04
- Player UI overhaul: touch zones for channel change, resize button in overlay, favorites drawer with close button, buttons show on tap

## v1.1 (build 12) - 2026-06-17 20:04
- Add resize mode button to player; add |US| category filter support

## v1.1 (build 11) - 2026-06-16 00:34
- Confirmed provider has no catch-up archive flags; REPLAY label dormant; updater working end-to-end

## v1.1 (build 10) - 2026-06-16 00:09
- Test update pipeline end to end

## v1.1 (build 9) - 2026-06-15 19:24
- Added What's New changelog viewer in Settings

## v1.1 (build 8) - 2026-06-15 19:17
- Guide tab now shows favorite channels; increased player buffer for car box; restored US filter/favorites after external edits; category favorite stars; app-wide fullscreen

## v1.1 (build 7) - 2026-06-13 10:06
- Reverted EPG refresh to favorited channels only (US| full set too slow)

## v1.1 (build 6) - 2026-06-13 09:54
- Fixed US-only filter (Arabic channels removed), fixed favorites bleeding into Live, EPG now displays NOW/NEXT, home data loads on launch

## v1.1 (build 5) - 2026-06-11 01:37
- EPG refresh now only loads favorited channels; auto-login keeps user signed in

## v1.1 (build 4) - 2026-06-11 01:28
- Added Android TV support

## v1.0 (build 3) - 2026-06-11 01:28
- Fixed channel logo loading

## v1.0 (build 2) - 2026-06-11 01:27
- Scoped EPG refresh to US| categories, fixed Hilt worker crash

## v1.0 (build 1)
- Initial working build
- Xtream Codes login + auth
- Live TV with categories, channels, search, favorites
- VOD movies with playback
- Series list
- ExoPlayer with HLS support
- EPG database + worker
- Background EPG refresh (Hilt worker)
- Scoped EPG refresh to US| categories only
- App icon
