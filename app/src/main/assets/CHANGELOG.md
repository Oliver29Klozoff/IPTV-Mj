# IPTV App - Changelog

## v2.32 - 2026-06-28
- Landscape mode: left sidebar with vertical nav, mini player above channel list
- Camera cutout: status bar hidden, content padded below camera on all phones
- Favorites tab loads by default instead of first live category
- Changelog now shows full history from local file

## v2.31 - 2026-06-28
- Fix rotation crash: btnMenu view type mismatch between portrait and landscape layouts

## v2.30 - 2026-06-28
- Hide system status bar edge-to-edge; push content below camera cutout using window insets

## v2.29 - 2026-06-28
- Fix landscape layout: mini player and vertical nav sidebar; fix btnMenu type mismatch crash on rotation

## v2.28 - 2026-06-28
- Landscape layout: vertical nav sidebar and mini player on top; fix favorites loading on startup

## v2.27 - 2026-06-27
- Add landscape layout for phones: left sidebar (TV-style) and smaller mini player

## v2.26 - 2026-06-27
- Camera cutout fix: removed windowFullscreen from base theme so content sits below camera

## v2.25 - 2026-06-27
- DNS over HTTPS: toggle in settings with Cloudflare/Google/NextDNS providers; bypass ISP throttling
- Always open to Favorites tab on launch
- GitHub token moved to local.properties/BuildConfig to prevent auto-revocation

## v2.24 - 2026-06-26
- Channel popularity sort (view count tracking)
- What's On Now dialog showing current EPG for all channels
- Reminder notifications on channel long-press
- Auto-reconnect with retry countdown in player
- Speed test in settings
- EPG refresh foreground service fix for Android 14
- Widget crash fix (main thread DB access)

## v2.23 - 2026-06-25
- Stream health checker, Chromecast support, recording playback, mini player EPG progress bar

## v2.22 - 2026-06-25
- Android TV UI, home screen widget, favorites drag reorder, timeshift replay, player retry countdown

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
