# IPTV App - Changelog

## v2.85 - 2026-07-01
- fix Movies/Series/History tabs not hiding on car box; fix full-screen playback retry loop by releasing mini player stream before launch

## v2.84 - 2026-07-01
- fix channel list on car box: proportional 3-column layout, correct tab mapping, auto-select first category on load

## v2.83 - 2026-07-01
- mini player larger on tablet/landscape; genre filter chips in Live tab; best fit/zoom/stretch resize modes with toast; restore picker opens at storage root; M3U channels now visible on home screen; mini player stays on full-screen channel when returning; token no longer compiled into APK

# IPTV App - Changelog

## v2.81 - 2026-06-30
- **Splash screen**: version label now reads from BuildConfig instead of a hardcoded string, so it never goes stale again

## v2.80 - 2026-06-30
- **Auto Backup**: weekly backups now save to Downloads/MKTV (public storage) instead of the app's hidden Android/data folder, so they're visible in file managers and the in-app Restore picker
- **Release build**: fixed missing dataSync foregroundServiceType causing lint failures

## v2.79 - 2026-06-30

## v2.78 - 2026-06-30
- **Recording Scheduler**: Schedule recordings by channel, start time, and duration — saved as .ts files to Movies/MKTV/ on device
- **⏺ REC button**: Added to top bar on all screen variants (phone portrait, landscape, TV, tablet)
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
- **Chromecast**: Resolve segment URLs from the final redirect URL, not the original m3u8 — fixes streams that redirect before serving

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
- **Chromecast**: Live streams now use STREAM_TYPE_LIVE — fixes stall caused by missing EXT-X-ENDLIST on live HLS

## v2.60 - 2026-06-27
- **Chromecast**: Local CORS proxy fixes IDLE_REASON_ERROR; proxy detects device IP via NetworkInterface scan

## v2.58 - 2026-06-27
- **Chromecast**: Cast status check added; buffer health badge hides while casting

## v2.57 - 2026-06-27
- **Chromecast**: Fix cast session missed when media route picker caused onPause

## v2.56 - 2026-06-27
- **TV Settings**: Fix D-pad sidebar navigation — focus chain was broken after Settings redesign

## v2.54 - 2026-06-27
- **Sync**: GitHub token input added to TV Settings sync panel

## v2.53 - 2026-06-27
- **Chromecast**: Fix playback — contentId and session wiring corrected; cast button now hides with player controls

## v2.52 - 2026-06-27
- **Sync**: Auto-discovers existing Gist on a second device — no need to manually enter Gist ID

## v2.51 - 2026-06-27
- **EPG**: Fix wrong program showing in player overlay and mini player info bar

## v2.50 - 2026-06-26
- **Security**: GitHub token removed from compiled APK; stored in DataStore at runtime only

## v2.49 - 2026-06-26
- **UI**: Settings headers use wrap_content; split view renamed; login and mosaic layout cleanup

## v2.48 - 2026-06-26
- **Guide**: Fix grid always opening same channel — onResume was racing the ActivityResult callback; fixed with suppressMiniAutoResume flag

## v2.47 - 2026-06-26
- **Guide**: Tapping a program in the grid now opens it in the mini player instead of full player

## v2.46 - 2026-06-26
- **Guide**: Grid view supports fullscreen tap-to-play for any channel or program slot

## v2.45 - 2026-06-26
- **Guide**: Fix history tab showing stale data and channels jumping position; fix guide EPG not reloading after refresh

## v2.43 - 2026-06-26
- **Guide**: No longer re-fetches EPG on every visit — uses cached data, much faster to open

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
- **Channel hide**: Hide channels you never watch — accessible via filter toggle
- **History tab**: New tab showing recently watched channels
- **Similar channels**: Player suggests similar channels when stream ends
- **Buffer health badge**: Live indicator in player shows buffering quality (Good / Weak / Poor)
- **TV home screen**: Favorite channels published to Android TV home screen row

## v2.36 - 2026-06-26
- **TV Settings**: Full redesign — blue left-bar active indicator, D-pad focus chain on all 8 nav items, Enter key jumps to content panel

## v2.35 - 2026-06-25
- **UI**: Top bar flush to screen edge; status bar hidden edge-to-edge

## v2.34 - 2026-06-25
- **Fullscreen**: Status bar and nav bar hidden; swipe down from top to peek at system UI

## v2.33 - 2026-06-25
- **Landscape**: Landscape layout added for phones — vertical sidebar + smaller mini player

## v2.32 - 2026-06-25
- **Speed test**: Built-in speed test in settings
- **Reconnect**: Improved reconnect logic with exponential backoff
- **Reminders**: Set EPG reminder for upcoming programs
- **What's On Now**: Quick-access panel showing what's currently airing across favorites

## v2.31 - 2026-06-25
- **Settings**: Fixed all settings bugs — status messages restored, changelog accessible, backup scrollable

## v2.28 - 2026-06-25
- **Channel Mosaic**: Multi-stream grid view — watch up to 4 channels simultaneously in a 2×2 grid

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
- **M3U Playlist import**: Load any M3U/M3U8 playlist by URL or local file — works alongside Xtream Codes
- **Player: Audio & subtitle track selection**: Tap CC button to pick audio language or subtitle track
- **Player: Playback speed control**: 0.25× to 2× speed selector (great for VOD)
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
