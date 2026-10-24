# Android TV home integration — Prompt 12

Baseline: `b00e7f3` (committed Prompt 11), branch `feature/tv-home-integration`.
Application version remains 7.70 / 608. No database migration is required.

## Implementation

- Extends `WatchNextPublisher`, `TvHomeChannelPublisher`, and existing signed `LauncherLink` routing.
- Continue Watching carries saved positions and durations. Completed episodes can become NEXT cards using an episode index captured by normal provider detail requests. One card per series; replaying an older episode updates the selected episode.
- Episode index is account-bound, persists across process restarts, expires after seven days, and is capped at 20 series / 2,000 episodes each. Missing metadata produces no invented recommendation or background provider fetch.
- Favorites retain their existing channel. Recently Watched and Recent Completed Recordings use separate preview channels. Cards are reconciled in place; deleted files and completed playback entries are removed without deleting history or recordings.
- Recording links contain an ID and install-bound signature; the file path and capture time are validated privately before playback. Movie/episode links remain account-bound. Episode playback resolves the requested episode from the provider's current detail response.
- A single application-lifetime, conflated consumer observes relevant Room changes and credentials. Updates are separated by approximately 30 seconds. No additional periodic worker or provider stream is started.
- Settings → Quick Actions → Android TV Home Integration enables/disables publication. System row approval is requested only by the explicit “Show launcher rows” action. Hidden rows are not made browsable by synchronization; deleted rows are not automatically recreated.
- Only allowlisted public HTTPS artwork is shared; provider-hosted or credential-bearing artwork is omitted. Preview cards have an application-icon fallback.

## Compatibility and limits

- Requires Android TV API 26+ and the TV content provider. Phones and older TV versions keep MKTV's internal home screen.
- The Google TV launcher defaults off unless explicitly enabled. API availability does not establish Google certification or guarantee display. Google documents prior approval for Google TV Watch Next: https://developer.android.com/training/tv/discovery/watch-next-add-programs
- Movie/episode progress and live cards currently follow the primary-provider history supported by the existing launcher integration. Same-numbered items on another provider cannot validate old links. Completed recordings can originate from any provider.
- Next Episode relies on a recently fetched series list. A removed episode is rechecked when opening the card; no continuous catalog polling is performed.
- No physical Shield installation, push, merge, deployment, release, or application version change is part of this work.

## Verification

Pending final build and emulator results. The repository's Codex review command was attempted, but its configured `gpt-6.1-sol` model was rejected by the CLI's ChatGPT authentication; it did not produce a completed independent review.
