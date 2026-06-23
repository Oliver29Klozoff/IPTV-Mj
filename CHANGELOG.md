# IPTV App - Changelog

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
