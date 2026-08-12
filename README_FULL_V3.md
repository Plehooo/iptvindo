# IPTV Player FULL V3 — HLS/M3U8 Signed URL

Versi ini memperdalam V2 pada jalur playback dan playlist.

## Playback
- Signed URL `?hdnts=...~hmac=...` dipertahankan apa adanya.
- Tidak memakai `substringBefore("?")` untuk media URL.
- Per-channel `User-Agent`, `Referer`, dan `Origin`.
- Cross-protocol redirect.
- Timeout koneksi/read terkontrol.
- Live configuration untuk HLS.
- Buffer tuning untuk live stream.
- Auto-retry saat error / stream live berakhir.
- Tombol RETRY manual.
- Status error lebih jelas.

## Playlist
- M3U/M3U8 fetch memakai HttpURLConnection, timeout, redirect, User-Agent.
- Parsing `group-title`, `tvg-logo`, `tvg-id`.
- Parsing beberapa bentuk header:
  - `#EXTVLCOPT:http-user-agent`
  - `#EXTVLCOPT:http-referrer`
  - `#EXTVLCOPT:http-referer`
  - `#EXTVLCOPT:http-origin`
  - `#EXTVLCOPT:http-header`
  - `#EXTHTTP:{...}`
  - `#KODIPROP:...stream_headers=...`
- URL channel tidak diubah setelah diparse.
- Duplikat URL dibuang.

## State
- Playlist URL tersimpan.
- Playlist dicoba dimuat ulang saat aplikasi dibuka.
- Favorites tersimpan.
- History tersimpan hingga 50 item.
- Filter group + search.

## Catatan keamanan / kompatibilitas
Player tidak membypass token, DRM, atau autentikasi server.
Jika signed URL expired atau server mensyaratkan kredensial/header yang tidak diberikan playlist,
server tetap dapat menolak request.

Build target:
- Android SDK 37
- Media3 1.10.1
