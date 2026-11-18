# Google Play preparation

## Store listing (draft)
- **Title**: SDM – Smart Download Manager
- **Short description**: Fast, reliable downloads with multi-connection, resume, scheduling and rules.
- **Full description**: SDM downloads files over HTTP and HTTPS with up to 16 parallel connections
  when the server allows it, pauses and resumes safely, and checks every file before calling it
  complete. A built-in browser passes your session to downloads, a file manager organizes them by
  type, and schedules, smart rules and analytics help you download on your terms. SDM does not
  bypass logins, paywalls or DRM.

## Permissions to declare
| Permission | Why |
| --- | --- |
| `FOREGROUND_SERVICE_DATA_SYNC` | Keeps user-started downloads running on Android 8–13 (and as fallback). Declared use: user-initiated file transfer. |
| `RUN_USER_INITIATED_JOBS` | Android 14+ user-initiated data transfer jobs for downloads. |
| `REQUEST_INSTALL_PACKAGES` | Lets users install APK files they downloaded (core file-management function). Play may reject it; if so, remove the permission and APKs open through the file manager. |
| `SCHEDULE_EXACT_ALARM` | Optional, user-granted: starts scheduled downloads on time. |
| `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `INTERNET`, `ACCESS_NETWORK_STATE` | Progress notifications, resuming after reboot, networking. |

No storage permissions and no All Files Access are requested (scoped storage, ADR-0005).

## Data safety form
- No data collected or shared. No accounts, analytics SDKs or ads.
- Cookies/headers for downloads stay on the device, encrypted, excluded from backups and exports.
- Users can delete all data by clearing app storage; analytics history can be cleared in the app.

## Release checklist
1. Create an upload key; add `SDM_KEYSTORE_BASE64`, `SDM_KEYSTORE_PASSWORD`, `SDM_KEY_ALIAS`,
   `SDM_KEY_PASSWORD` as repository secrets and decode the keystore in CI before `bundleRelease`.
2. Bump `versionName`; `versionCode` comes from the CI run number.
3. Run the Phase 6 device matrix (see DEVELOPMENT_STATUS.md).
4. Upload `SDM-release-buildN.aab` to an internal testing track first.
5. Policy review risks: downloads from streaming sites (SDM has no site-specific extractors),
   `REQUEST_INSTALL_PACKAGES` justification.
