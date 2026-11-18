# ADR-0009: FTP/SFTP and BitTorrent

Status: accepted (2026-11-18).

## FTP, FTPS, SFTP
- `download-engine/remote/RemoteFileEngine`: Apache Commons Net (FTP/FTPS explicit TLS) and JSch
  (mwiede fork) for SFTP; one connection per file, resume (REST / offset), checksum verification,
  same checkpoint and result types as HTTP.
- Passwords in links (`ftp://user:pass@host/…`) are moved into the encrypted headers before
  anything is stored; links never keep them.
- SFTP host keys: trust on first use, stored in app storage; a changed key is refused before the
  password is sent.

## BitTorrent
- `core/data/torrent/TorrentEngine` on libtorrent4j 2.1 (MIT): magnet links (DHT/trackers) and
  .torrent files or links. Native code for arm64-v8a, armeabi-v7a, x86_64 (adds ~20 MB to the
  universal APK; Play delivers one ABI).
- Files are written to a private working folder and copied to Downloads/SDM (a folder per
  multi-file torrent) once complete; the working folder is then removed.
- Pause removes the torrent from the session and keeps the data; resume re-checks pieces.
- SDM uploads only while downloading (the protocol requires it) and stops at completion; no
  seeding afterwards. The add dialog says so and reminds users to download only content they have
  the right to.
- Not yet: choosing individual files of a torrent, fast-resume data, seeding.
