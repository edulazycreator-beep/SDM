# ADR-0005: Storage and the file manager under scoped storage

Status: accepted (2026-11-18)

- Default destination: MediaStore `Download/SDM/<Category>/` (Android 10+), created with
  `IS_PENDING=1` so other apps do not see partial files, written with positional `pwrite` on the
  file descriptor, and published when verified. Android 8-9 use app-specific external storage so no
  storage permission is needed.
- Custom folders: chosen with the system folder picker (persisted SAF permission). If the provider
  cannot seek (cloud folders), the download is staged privately and copied in when complete.
- The file manager covers files SDM saved plus folders the user granted. It does not request
  All Files Access, which Google Play restricts; whole-device browsing would need a separate,
  non-Play build.
- MediaStore deletes pending items older than about 7 days. A download paused that long restarts
  into a new file; SDM detects the missing partial file and logs it.
