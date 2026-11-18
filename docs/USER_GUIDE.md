# SDM user guide

## Add a download
- Tap **+** on Downloads, or **Quick download / Paste URL / Add multiple URLs** on Home.
- Copy a link anywhere, then open SDM: it offers to download it.
- Share a link to SDM from any app ("Download with SDM").
- In the SDM browser, tap a download link, or long-press a link or image.
- Play a video in the SDM browser: SDM shows "Video detected — Download". The download button at the
  bottom right lists every video found on the page, with qualities and estimated sizes. Streams
  (HLS) are saved as MP4. DRM-protected videos, live streams and DASH streams can't be downloaded;
  SDM tells you which applies.
- Paste an `.m3u8` or `.mpd` link to pick a quality, an audio language, subtitles, or "audio only".
- In the browser menu, "Download files on this page" lists every file link on the page.
- Magnet links and .torrent files download the torrent's files (folder per torrent).
- `ftp://`, `ftps://` and `sftp://` links work; a password in the link is stored encrypted.
- Home › More actions › "Send links from a computer" shows an address and code to use on a PC on
  the same Wi-Fi.
- Mirror links (in the add dialog) are used in order if the main link keeps failing.

## Other features
- Built-in player for videos and music (subtitles saved next to a video are offered).
- ZIP files: "Extract here" in the download details, or turn on automatic extraction in Settings.
- Private folder (Files › lock icon): opens with fingerprint, face or screen lock.
- Home-screen widget and a Quick Settings tile ("Download copied link").
- Browser: ad blocking, site settings, reader view.
- Language: Settings › Appearance › Language.

The confirmation dialog shows name, size, type, source and destination, and lets you choose
connections (1–16), network (any / unmetered / Wi-Fi), priority, start time, speed limit and an
expected checksum.

## While downloading
Swipe right to pause/resume, left to delete. Long-press to select several. The details screen
shows segments, speed, remaining time, events and a copyable diagnostic report. If a link expires,
use **Source URL → Edit link** with a fresh link; SDM keeps the downloaded part only if it is the
same file.

## Where files go
`Download/SDM/<Videos|Music|Images|Documents|Archives|Applications|Other>` by default, or any folder
you pick. The Files tab manages these files plus folders you grant access to.

## Automation
- **Settings › Scheduler**: choose Anytime, Wi-Fi only, Night (01:00–06:00), Manual (start/pause
  times) or Custom (hours, days, charging, minimum battery). Allow exact alarms for on-time starts.
- **Settings › Smart rules**: IF … THEN … rules, run top to bottom.
- **Settings › Downloads**: connections, Speed Intelligence, speed limits, retries.

## Background downloads
Android may stop background work. On Android 14+ SDM uses system download jobs that keep running
after you leave the app. If your phone still stops downloads, exempt SDM from battery optimization
(Settings › Downloads › Battery optimization settings).
