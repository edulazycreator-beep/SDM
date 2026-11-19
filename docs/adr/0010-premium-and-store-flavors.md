# ADR-0010: Premium, store flavors and the Play source policy

Date: 2026-11-19 · Status: accepted

## Context
The owner wants to sell SDM Premium on Google Play as a subscription (with a 3-day free trial) and
as a one-time purchase, and chose that Premium is **appearance and support only**: every download
feature stays free. Play billing works only for installs from Google Play, and the APK published on
GitHub should stay complete. Google Play also removes apps that download YouTube videos.

## Decision
- Two product flavors in the `store` dimension, same application id:
  - `github`: `BuildConfig.PLAY_STORE = false`. Premium is included, nothing is sold, no source policy.
  - `play`: `BuildConfig.PLAY_STORE = true`. Premium is sold through Play Billing Library 9.1;
    `SourcePolicy.blockVideoPlatforms` is on.
- Premium unlocks: all accent colors, wallpaper (dynamic) colors, the AMOLED black theme, and a
  supporter status. Without it SDM uses the brand blue and System/Light/Dark.
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
- Spec requirements are unchanged: no download capability was removed or locked.
