# APKLIV TV V4 — functional IPTV player

V4 memperbaiki entry point yang hilang pada V3.

## Input yang sekarang tersedia
- URL playlist M3U/M3U8
- File `.m3u` dari penyimpanan HP
- URL stream langsung `.m3u8`
- URL stream langsung `.mpd`
- URL M3U8 yang ternyata merupakan manifest HLS langsung akan dimainkan sebagai stream jika tidak memiliki `#EXTINF`.

## HLS signed URL
Query signed seperti `?hdnts=exp=...~hmac=...` tidak dipotong atau dibangun ulang.

## Tetap ada
- Search
- Group filter
- Favorites
- History
- Playlist persistence
- Per-channel User-Agent / Referer / Origin
- Retry playback
- HLS live configuration
- M3U parser
- MPD playback

Ini adalah source project Android. APK belum diklaim sudah ter-compile.
