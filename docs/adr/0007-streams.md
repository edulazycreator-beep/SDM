# ADR-0007: Video stream (HLS) downloads and in-browser video detection

Status: accepted (2026-11-18). Supersedes the "no HLS" part of ADR-0006.

## Context
Most video on the web is not a single file: players fetch an HLS playlist (`.m3u8`) or a DASH
manifest (`.mpd`) and play it through Media Source Extensions, so the `<video>` element shows a
`blob:` URL that cannot be downloaded. Users expect "a video is playing → offer to download it".

## Decision
- **Detection (browser).** Three observers, none of which block or change requests:
  1. `shouldInterceptRequest` classifies resource URLs: direct media files by extension, HLS
     playlists, DASH manifests. Segment files (`.ts`, `.m4s`) are ignored.
  2. A document-start script (`WebViewCompat.addDocumentStartJavaScript`, falling back to
     injection on page start/finish) listens in the capture phase for `loadedmetadata`, `play` and
     `playing` on every `<video>`/`<audio>`, and reports `fetch`/XHR responses whose content type is
     an HLS playlist (catches playlists without a `.m3u8` name).
  3. The existing `<video>/<audio>` source scan after the page loads.
  When a video plays, SDM matches it to its source (the element URL, or for `blob:` players the
  stream the page fetched) and shows a "Video detected — Download" snackbar once per video. A
  download button with a badge lists everything detected with qualities, duration, estimated size
  and, when it cannot be saved, the reason.
- **Inspection.** Only playlists are read (never media) to list qualities. A master playlist hides
  its variant playlists so one video appears once. Size is an estimate (bandwidth × duration) and
  is labelled as such.
- **Download (engine).** RFC 8216 media playlists, segments fetched in parallel (1–8) and written in
  order to private files, per-segment retries, checkpoints (segments + bytes) for resume, standard
  AES-128 decryption, `EXT-X-MAP` (fMP4) and byte ranges. A separate audio rendition is downloaded
  as a second track. Cookies/authorization are sent only to the playlist's host and its subdomains,
  never to third-party CDNs.
- **Output.** Tracks are remuxed into one MP4 with `MediaExtractor`/`MediaMuxer` (no re-encoding).
  If remuxing fails the original `.ts`/`.mp4` stream is saved instead and the event log says so.
- **Refused, with a reason shown:** DRM (`SAMPLE-AES`, Widevine/PlayReady/FairPlay key formats),
  live streams (no `EXT-X-ENDLIST`), DASH (not implemented). SDM never decrypts DRM.

## Consequences
- Progress for streams counts segments; total size is an estimate until the download finishes.
- Database v2 adds `streamType`, `streamAudioUrl`, `streamLabel` to `downloads` (migration 1→2).
- Sites whose players need request signing done in JavaScript, or that use DASH only, are listed as
  "cannot be downloaded" rather than silently failing.
