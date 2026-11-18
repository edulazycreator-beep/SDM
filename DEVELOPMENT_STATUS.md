# SDM — Development status

Last updated: 2026-11-18 · Branch: `develop` · CI: GitHub Actions (`.github/workflows/ci.yml`)

## Current phase
All seven phases of the master spec have been implemented to a first complete version. Phase 6
(hardening) is partially done: automated tests, R8 release smoke test and an end-to-end download
test pass in CI, but the multi-device matrix and long-running/stress tests on real hardware have not
been run yet (see "Not yet verified").

## Build status (CI runs for commits 425e0e5 and 298d8c4, builds 9–10)

| Check | Result |
| --- | --- |
| Compile all modules (debug + release) | Pass |
| Module dependency rules (`checkModuleDependencies`) | Pass |
| Android Lint (`:app:lintDebug`, errors abort) | Pass |
| JVM unit tests | 54 run, 0 failed |
| Instrumented tests, emulator API 34 | 9 run, 0 failed |
| Release APK (R8) installs, launches and handles a share intent without crash | Pass |
| Release APK / AAB / debug APK published to GitHub Releases | Pass (latest: [Releases › Latest](https://github.com/edulazycreator-beep/SDM/releases/latest)) |

### Unit tests (JVM)
- `core:common` — Redactor (headers, query tokens, user-info, free text), file-name sanitizing and
  path traversal, RFC 6266/5987 file names, URL validation/extraction, categories, formatting.
- `domain` — exhaustive state machine (10 states × 14 events), rule engine (priorities, first match
  per action, environment rules, serialization), schedule policy (midnight windows, days, manual
  start/stop, Wi-Fi only), Speed Intelligence (best measured count, user cap, instability back-off),
  queue ordering and estimates (no estimate without measurements), import/export validation, TXT
  import, duplicate detection (never by name alone).
- `download-engine` (against the programmable test server) — single connection, multi-connection
  with ranges, server without range support, lying Content-Range detected with no corruption,
  pause/resume from checkpoint with a new engine run (process death), mid-body disconnects,
  resource changed between runs, 403 (and expired link on resume), cookie forwarding, 404, 429 with
  Retry-After, 5xx, throttled extra connections dropped, zero-byte file (416), redirect pinning,
  chunked unknown length, speed limit enforcement, checksum pass/fail, storage full, simultaneous
  downloads, header inspection, > 4 GB segment arithmetic, live limiter changes.

### Instrumented tests (Android 14 emulator)
- `EndToEndDownloadTest` — real download through the UI: add dialog → link inspection → rules →
  queue → engine (GitHub redirect to CDN, ranges, several connections) → MediaStore → verification
  → completed.
- `NavigationTest` — all five sections, a sub-screen and back; activity recreation.
- `StorageAndSecretsTest` — out-of-order positional writes to MediaStore pending items and app
  storage read back identical; cookies encrypted at rest and decrypted correctly.
- `DatabaseTest` — insert/read/count, compare-and-set rejects stale writers, segment cascade,
  daily statistics accumulate.

## Completed features (by spec section)
- §4 Navigation: 5 sections + details, queue, analytics, scheduler, rules, network settings,
  bookmarks, history, diagnostics, storage analysis, about, search, import/export, appearance,
  download settings; bottom bar on phones, rail on ≥ 600 dp.
- §5 Dashboard with customizable, reorderable widgets and all listed quick actions.
- §6 Engine: everything listed, including recovery and the persisted state machine.
- §7 Download management: all listed actions and item fields.
- §8–§9 Link detection, confirmation dialog, clipboard monitor (focus-only, hash-only).
- §10–§11 Browser (tabs, private tabs, bookmarks, history, desktop mode, custom UA, cookies/cache,
  find, share, external browser, interception with session cookies, blob/data) and media detection.
- §12 File manager (categories, grid/list, search, sort, size filter, multi-select, batch
  rename/delete/move/share, folders, preview/open, details, checksums, duplicates, storage analysis).
- §13 Scheduler (all five profiles, hours, days, charging, battery, exact alarms, scheduled
  pause/resume); §14 rule engine with CRUD, enable/disable, priority; §15 Speed Intelligence;
  §16 queue intelligence with drag-and-drop and labelled estimates; §17 analytics with clear;
  §18 speed limiter (global/per-download/profiles/custom); §19 network settings incl. proxy and
  DNS over HTTPS; §20 duplicates; §21 import/export/backup; §22 notifications; §23 universal
  search; §24 customization; §25 diagnostics; §26 performance monitoring; §27 storage management
  incl. confirmed cleanup of leftover partial files; §28 onboarding and UX; §29 security.
- §32 Phase 7: icon, splash, R8 release config, APK/AAB generation, README, user guide,
  Play Store notes (`docs/PLAY_STORE.md`).

## Deviations from the spec (documented, not silent)
- Module layout: 8 modules instead of ~25 (ADR-0001).
- detekt not used (incompatible with Kotlin 2.4); Android Lint enforced instead (ADR-0002).
- Test server uses the JDK HTTP server instead of MockWebServer, for byte-level control of
  disconnects and lying headers.
- Universal search uses SQL `LIKE` rather than FTS4 (sufficient at expected data sizes).
- One user-initiated transfer job holds the lease for all running downloads (not one per download).
- Platform limits handled per the technical plan: background start limits, boot restrictions,
  clipboard focus rule, scoped storage (no All Files Access), private tabs need WebView
  multi-profile, POST-only downloads unsupported, no DASH or DRM (HLS supported, ADR-0007).

## Video streams and browser video detection (added 2026-11-18, ADR-0007)
- Engine: HLS parser and downloader (`download-engine/.../hls`): master/media playlists, parallel
  segments written in order, AES-128, fMP4 init segments, byte ranges, resume from checkpoints,
  segment retries, live/DRM refusal, credentials scoped to the playlist host. Unit tests: `HlsTest`.
- App: `DownloadCoordinator.runStream` (video + separate audio track, progress, pause/resume,
  cancel cleanup), remux to MP4 (`core/data/media/Remuxer`), fallback to the original format.
- Browser: detects playlists (URL, fetch/XHR content type), media elements that load or play, and
  offers "Video detected — Download" when a video starts playing; detected-media button and sheet
  with qualities, duration, estimated size and reasons when blocked; page videos can go fullscreen.
- Add dialog: quality chips for streams; pasted `.m3u8` links and playlist content types are
  recognised.
- Not yet verified on a device: remuxing of real-world streams, detection on major video sites.

## UI fixes (2026-11-18)
- Top bars drew under the status bar: the outer app Scaffold consumed the system-bar insets
  without applying them. It now reserves only the bottom-navigation height; each screen handles
  status/navigation bars itself. Browser content also respects the keyboard (IME) inset.

## Known issues and limits
- MediaStore removes pending items after about 7 days; a download paused that long restarts into a
  new file (detected and logged).
- Release builds are signed with the CI debug key unless signing secrets are added; such an APK
  cannot be updated by a differently signed build (uninstall first).
- Dependencies are pinned to versions verified together (AndroidX mid-2025 line); updating them is
  pending.
- Stream sizes are estimates until the download finishes; DASH-only sites cannot be downloaded.
- Browser interception, scheduling wake-ups, boot recovery and notification actions have no
  automated tests yet (they need device-level automation).

## Not yet verified (needs a real device)
- Long downloads (> 1 hour) in the background on Android 14+ (UIDT) and 8–13 (foreground service).
- Behaviour on OEM builds with aggressive battery management (Samsung, Xiaomi).
- SD-card destinations (FAT32 4 GB limit message), cloud SAF folders (copy-on-complete path).
- Files larger than 4 GB end to end.
- Large-screen layouts and Android 17 behaviour changes.

## Next tasks
1. Run the device matrix above and record results here.
2. Add release signing secrets; upload the AAB to an internal Play track.
3. UI automation for notification actions and browser interception.
4. Update AndroidX dependencies and re-run the full test suite.
