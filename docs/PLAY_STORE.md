# Google Play preparation

Upload `SDM-play-buildN.aab` (the `play` flavor, ADR-0010). The GitHub APK is a different flavor and
is not for Play.

## 1. Upload key and CI secrets (once)
Google Play rejects bundles signed with a debug key. Create one upload key and keep a backup; with
Play App Signing, Google holds the final app signing key and this key only signs uploads.

```bash
keytool -genkeypair -v -keystore sdm-upload.jks -alias sdm-upload \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 sdm-upload.jks > sdm-upload.jks.b64     # macOS: base64 -i sdm-upload.jks
```

In GitHub: repository → Settings → Secrets and variables → Actions → New repository secret:

| Secret | Value |
| --- | --- |
| `SDM_KEYSTORE_BASE64` | contents of `sdm-upload.jks.b64` |
| `SDM_KEYSTORE_PASSWORD` | the keystore password |
| `SDM_KEY_ALIAS` | `sdm-upload` |
| `SDM_KEY_PASSWORD` | the key password |

The next CI build signs every release file with this key (the build summary says "Release builds
are signed with the SDM upload key"). From then on the GitHub APK also updates in place. Never commit
the keystore.

## 2. Premium products (Play Console → Monetize → Products)
Create these after the first bundle with the billing library is uploaded to a testing track.

- **Subscription** product id `sdm_premium`
  - Base plan `monthly`: auto-renewing, 1 month, your price.
  - Base plan `yearly`: auto-renewing, 1 year, your price.
  - For each base plan, add an offer: id `trial-3d`, eligibility "New customer acquisition — never
    had this subscription", phase **Free trial, 3 days**. Activate it.
- **One-time product** product id `sdm_premium_lifetime`, your price. Activate it.

The app reads names, prices and the trial from Play; nothing is hard-coded. Test with license
testers (Settings → License testing) on an internal testing track; test purchases are free and
subscriptions renew every few minutes.

## 3. Store listing (draft)
- **Title**: SDM – Smart Download Manager
- **Short description**: Fast, reliable downloads with resume, browser, scheduling and smart rules.
- **Full description**: SDM downloads files over HTTP, HTTPS, FTP and SFTP with up to 16 parallel
  connections when the server allows it, pauses and resumes safely, and checks every file before
  calling it complete. Its built-in browser finds downloadable files and video streams (HLS and
  DASH) on the pages you visit and keeps your sign-in for downloads, with an ad blocker and reader
  view. SDM also extracts ZIP archives and has schedules, rules, analytics, a home-screen widget and
  a Quick Settings tile. SDM does not download from YouTube and
  does not bypass logins, paywalls or DRM.
  SDM Premium (optional): torrents and magnet links, FTP/FTPS/SFTP, up to 16 connections and 10
  downloads at once, download all files on a page, automatic ZIP extraction, private folder, send
  links from a computer, and more colors with AMOLED black. Monthly or yearly subscription with a
  3-day free trial, or a one-time purchase.
- **Graphics**: icon `docs/brand/play-icon-512.png`, feature graphic `docs/brand/play-feature-1024x500.png`.
  Screenshots: Home, Downloads, Browser with a detected video, Add dialog, Files, Premium.
- **Category**: Tools. **Contains ads**: No. **In-app purchases**: Yes.

## 4. Permissions to declare
| Permission | Why |
| --- | --- |
| `FOREGROUND_SERVICE_DATA_SYNC` | Keeps user-started downloads running (Android 8–13 and as fallback). Declared use: user-initiated file transfer. |
| `RUN_USER_INITIATED_JOBS` | Android 14+ user-initiated data transfer jobs for downloads. |
| `REQUEST_INSTALL_PACKAGES` | Sensitive permission: needs the declaration form (core use: "file management / downloads"). If Play rejects it, remove the permission; downloaded APKs then open through the file manager. |
| `SCHEDULE_EXACT_ALARM` | Optional, user-granted: starts scheduled downloads on time. |
| `USE_BIOMETRIC` | Unlocking the private folder. |
| `com.android.vending.BILLING` | Added by Play Billing for Premium. |
| `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `INTERNET`, `ACCESS_NETWORK_STATE` | Progress notifications, resuming after reboot, networking. |

No storage permissions and no All Files Access are requested (scoped storage, ADR-0005).

## 5. Data safety form
- No data collected or shared with the developer or third parties. No accounts, analytics SDKs or ads.
- Purchases are handled by Google Play; SDM only asks Play whether Premium was bought.
- Cookies and headers for downloads stay on the device, encrypted, excluded from backups and exports.
- Torrents: other peers see the device's IP address while a torrent is active (inherent to BitTorrent);
  mention it in the privacy policy.
- Users can delete all data by clearing app storage.

## 6. Privacy policy
Play requires a public privacy policy URL. It must cover: no data collection, on-device storage of
download history and encrypted cookies, Google Play billing, BitTorrent peers seeing the IP address,
the local Wi‑Fi link receiver (only while the user opens it), and a contact email.

## 7. Release checklist
1. Signing secrets set (section 1); bump `versionName`; `versionCode` comes from the CI run number.
2. New personal developer accounts must run a closed test (currently 12 testers for 14 days) before
   production access.
3. Upload `SDM-play-buildN.aab` to internal testing, then create the products (section 2) and test a
   purchase, the free trial, restore, and cancelling.
4. Fill the content rating, target audience, data safety, permissions declarations and privacy policy.
5. Policy notes: the Play flavor blocks YouTube; site-specific extractors are not included; DRM is
   never bypassed.
