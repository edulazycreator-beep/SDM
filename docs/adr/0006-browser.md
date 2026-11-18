# ADR-0006: Browser, link capture and media detection

Status: accepted (2026-11-18)

- WebView tabs are held by an activity-scoped ViewModel so they survive navigation.
- Download interception uses `DownloadListener`; the request carries the page's cookies, the
  WebView user agent and the Referer, encrypted at rest (Android Keystore AES-GCM).
- `blob:` and `data:` downloads are read inside the page through a JavaScript bridge (50 MB cap)
  and saved directly. POST-only downloads cannot be replayed and are not supported.
- Private tabs require the WebView multi-profile feature; without it they are disabled rather than
  offering weaker isolation.
- Media detection lists direct media files (resource requests and `<video>/<audio>` sources). It
  does not extract streams (HLS/DASH) and never handles DRM.
- Clipboard detection runs only when SDM gains window focus (Android 10+ rule), checks the clip
  description first and stores only a hash of the last suggested link.
