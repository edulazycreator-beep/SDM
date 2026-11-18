# SDM — Smart Download Manager

**Download Smarter. Faster. Better.**

SDM is a native Android download manager (Kotlin, Jetpack Compose, Material 3). It downloads over
HTTP/HTTPS with up to 16 parallel connections when the server supports range requests, pauses and
resumes safely, survives app restarts, and verifies every file before marking it complete.

## Get the APK

Every successful build of `develop` publishes a pre-release on the
[Releases page](https://github.com/edulazycreator-beep/SDM/releases):

| File | Use |
| --- | --- |
| `SDM-release-buildN.apk` | Optimized build (R8). Install this on your phone. |
| `SDM-debug-buildN.apk` | Debug build, for troubleshooting. |
| `SDM-release-buildN.aab` | Google Play bundle (needs the real signing key, see below). |

Without signing secrets the release APK is signed with the CI debug key: it installs for testing,
but a later build signed with a different key cannot update it (uninstall first).

## Features

- **Engine**: segmented downloads (1/2/4/8/16 connections) with range validation, If-Range, work
  stealing, automatic fallback to one connection, retry with backoff and Retry-After, checkpointed
  recovery after process death, size and optional MD5/SHA-1/SHA-256/SHA-512 verification, live
  global and per-download speed limits.
- **Downloads**: add by URL, paste, clipboard, share sheet or the browser; multi-select and batch
  actions; pause/resume/cancel/restart/retry; rename, change destination, refresh an expired link
  without losing progress; duplicate detection; queue with priorities, starred items and
  drag-and-drop ordering.
- **Browser**: tabs, private tabs (WebView multi-profile), bookmarks, history, desktop mode, find in
  page, download interception with session cookies, `blob:`/`data:` saves, media detection.
- **Files**: categories, grid/list, search, sort and size filter, batch rename/move/share/delete,
  folders via the system picker, checksums, content-based duplicate detection, storage analysis.
- **Automation**: scheduler (Anytime, Wi-Fi only, Night, Manual, Custom with days and conditions),
  smart rules (IF size/extension/type/host/network/battery/storage THEN confirm/folder/network/
  limit/connections/pause/resume/notify/retry), Speed Intelligence recommendations from measured
  results, queue estimates from measured throughput.
- **Insights**: analytics with daily/weekly/monthly totals, success rate, speed history and category
  charts; diagnostics with error explanations, copyable redacted reports and live performance data.
- **Data**: import TXT/JSON, export JSON/CSV, settings backup and restore.

## Build

Requirements: JDK 17 and the Android SDK (platform 37). Then:

```bash
./gradlew test                 # JVM unit tests (domain, engine with test server, common)
./gradlew connectedDebugAndroidTest   # instrumented tests on a device/emulator
./gradlew assembleRelease bundleRelease
```

Release signing reads `SDM_KEYSTORE_PATH`, `SDM_KEYSTORE_PASSWORD`, `SDM_KEY_ALIAS` and
`SDM_KEY_PASSWORD` from the environment. In CI, store the keystore as a base64 secret and decode it
to a file before the build.

## Project layout

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and the decision records in [docs/adr](docs/adr).
Progress and test evidence: [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md).
User guide: [docs/USER_GUIDE.md](docs/USER_GUIDE.md).

## Limits

SDM downloads what your browser session can access. It can save unprotected HLS video streams
(saved as MP4) and detects videos playing in its browser, but it does not bypass logins, paywalls or
DRM, does not record live streams and does not support DASH yet (ADR-0007). Android limits background work; see ADR-0003 for how SDM keeps
long downloads running within those rules.
