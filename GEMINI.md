# Gemini rules for MKTV (Android)

Read `CLAUDE.md` too. These rules add to it. If anything here conflicts with what the owner tells
you in chat, ask the owner.

MKTV is a live app on real TVs and phones, released through GitHub OTA updates. Claude Code and
Codex check every change before it ships, and your work goes through the same checks.

## Where you work

- Work only on your own branch, named `gemini/<short-feature-name>`, created from the latest `main`:
  `git fetch && git switch -c gemini/<name> origin/main`
- Use your own folder, never the folders Claude and the owner use (`iptv-mj`,
  `iptv-mj-audio-sync`, `iptv-mj-fr`). Create it once and reuse it:
  `git worktree add "D:\Android studio apps\IPTVNative\iptv-mj-gemini" -b gemini/<name> origin/main`
- Before you start and before you finish, run `git status`. If you find changes you didn't make,
  stop and tell the owner. Don't commit them, revert them or stash them.

## Never do these

- Don't commit to `main`, merge into it, push it, or rewrite its history.
- Don't push any branch unless the owner asks.
- Don't change `versionCode`, `versionName`, `version.json`, `CHANGELOG.md`, the release scripts
  (`release*.ps1`, `bump.ps1`) or GitHub releases. Releasing is the owner's call, done by Claude.
- Don't install, use adb with, or connect to the owner's Shield or phone. Use emulators only, or
  give the owner steps.
- Don't put provider logins (server URL, username, password), M3U links or stream URLs on the
  network in readable form, in logs, in toasts or in error messages. A signed request
  (`X-MKTV-Sig`) is not encrypted, so anyone on the Wi-Fi can still read it. To tell another
  device "same provider", send `HandoffCrypto.providerTag(pairingToken,
  AppHandoffCatalog.identity(url, user))` and nothing else.
- Don't send video through Cloudflare or any proxy, because the provider blocks it. Casts stream
  directly to the TV.
- Don't build a parental PIN lock.

## Be careful here

These areas break real users when they go wrong. Make a change here only when the task needs it,
and call it out in your report:

- **Pairing, handoff, remote and cast:** `remote/`, `handoff/`, `cast/`, `sync/`. Pairing and
  encryption were built and reviewed carefully, so reuse `HandoffEnvelope` and `HandoffCrypto`
  rather than inventing new schemes. In `RemoteControlServer`, don't block request threads with
  `runBlocking` work, and don't raise body limits for every route.
- **Saved data:** Room entities and migrations, DataStore keys in `PreferencesManager`, and the
  saved Home layout and Top Favorites. Never drop or rename stored data without a migration.
- **More than one provider:** stream, category and movie ids belong to one provider (`serverIndex`,
  where -1 is the main provider). Never treat the same id from two providers as the same channel.
- **Providers and login:** don't overwrite a device's provider list or login from another device.
- **TV and phone:** a change must work with the Shield's D-pad (focus, Back, holding OK) and on a
  phone in portrait and landscape, because the car box is always landscape.

## Before you say a change is done

1. Build, and run every unit test. Report the real result, failures included:
   `.\gradlew.bat testDebugUnitTest assembleDebug`
2. Add tests for new behaviour (Robolectric with an in-memory Room database, like
   `SmartHomeLayoutTest` and `LocalSyncManagerTest`).
3. Commit to your `gemini/...` branch with a clear message. Keep each commit to one feature.
4. Run the Codex review of your branch, then fix what it finds and run it again until it's clean:
   `powershell -File .vscode/scripts/codex-review.ps1 -Base main`
5. Write a short report for the owner:
   - what changed
   - which files changed
   - the test and build results
   - the Codex result
   - what you could not test, such as a real Shield or a real provider
   - any of the "be careful here" areas you touched

   Don't say "fully verified" unless you ran it.

## What happens next (not your job)

Claude Code reviews your branch against `main`. Claude runs the tests and Codex again, fixes or
sends back problems, and tests on emulators. Only after the owner approves does Claude merge,
bump the version and release.
