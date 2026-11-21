# ADR-0010: Premium, store flavors and the Play source policy

Date: 2026-11-19 · Status: accepted

## Context
The owner wants to sell SDM Premium on Google Play as a subscription (with a 3-day free trial) and
as a one-time purchase. First decided as appearance-only, then (2026-11-21) changed by the owner to
include suitable advanced features. Play billing works only for installs from Google Play, and the APK published on
GitHub should stay complete. Google Play also removes apps that download YouTube videos.

## Decision
- Two product flavors in the `store` dimension, same application id:
  - `github`: `BuildConfig.PLAY_STORE = false`. Premium is included, nothing is sold, no source policy.
  - `play`: `BuildConfig.PLAY_STORE = true`. Premium is sold through Play Billing Library 9.1;
    `SourcePolicy.blockVideoPlatforms` is on.
- Premium unlocks (Play build; `PremiumFeature` in core:common lists them):
  torrents and magnet links; FTP/FTPS/SFTP; up to 16 connections per download (free: 8); more than
  3 downloads at once (up to 10); "Download files on this page"; automatic ZIP extraction; moving
  files into the private folder; sending links from a computer; all accent colors, wallpaper colors
  and AMOLED black.
- Free keeps the core: HTTP(S) downloads with up to 8 connections, resume and verification, the
  browser with video/stream detection (HLS/DASH, audio only, subtitles), scheduler, rules, Speed
  Intelligence, queue, player, manual ZIP extraction, widget, tile, import/export, brand theme.
- `Entitlements` (core:common) holds the state for code outside the UI: the coordinator caps
  connections and simultaneous downloads, skips automatic extraction, and fails torrent and
  FTP/SFTP downloads with `ErrorCode.REQUIRES_PREMIUM` when Premium is not active, so downloads
  queued earlier or lapsed subscriptions can't bypass it. Files already in the private folder stay
  reachable after Premium ends; only moving new files in is locked.
- Products (created in Play Console, not in code):
  - subscription `sdm_premium` with base plans for monthly and yearly; each base plan gets a
    free-trial offer of 3 days for new customers. Play only returns the trial offer to users who are
    eligible, so the app shows "3 days free" only when it applies.
  - one-time product `sdm_premium_lifetime`.
- `PremiumRepository` checks purchases with Play each time SDM opens, acknowledges new purchases
  (Play refunds unacknowledged purchases after 3 days), and keeps the last state on the device for
  offline use. A subscription that ended or was refunded is no longer returned by Play, so Premium
  ends at the next check. There is no server, so verification is on the device only; acceptable
  because Premium is cosmetic.
- Trial and renewal terms are stated under the purchase button before the user taps it.
- `SourcePolicy` (core:common) blocks youtube.com, youtu.be, youtube-nocookie.com, youtubekids.com
  and googlevideo.com in the Play build: the browser does not scan those pages, the add dialog
  explains why, imports and link lists skip them, and `DownloadRepository.enqueue` refuses them.

## Consequences
- CI builds `assembleGithubDebug assembleGithubRelease assemblePlayRelease bundlePlayRelease`;
  instrumented tests run on the github flavor.
- The release lists `SDM-release-N.apk` (GitHub version), `SDM-play-N.aab` (upload to Play) and
  `SDM-play-test-N.apk` (preview of the Play version; purchases fail outside Play).
- In the Play build some spec features are Premium-only; the GitHub build keeps all of them, so no
  requirement is removed, and the split is documented here and in DEVELOPMENT_STATUS.md.
