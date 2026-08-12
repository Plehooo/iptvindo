# IPTV Player V2 — HLS/M3U8 Signed URL

Versi ini difokuskan untuk HLS/M3U8 yang menggunakan signed query string, misalnya:

`https://example.com/live.m3u8?hdnts=exp=...~hmac=...`

## Perubahan utama

- URL signed diteruskan utuh ke Media3.
- Query string tidak dipotong atau dibangun ulang.
- Media3 HLS module digunakan secara eksplisit.
- Cross-protocol redirect diizinkan.
- Timeout koneksi/read diperpanjang untuk live stream.
- User-Agent Android/Chrome digunakan.
- HLS load error policy mencoba ulang sampai 5 kali.
- `application/x-mpegURL` dipaksa untuk URL yang diputar melalui helper HLS.

## Catatan

Signed URL tetap harus valid. Jika token `hdnts` sudah kedaluwarsa atau server membutuhkan
header autentikasi tertentu, player tidak dapat menghidupkannya tanpa token/header yang sah.

Build:

1. Buka folder project pada Android Studio/AndroidIDE.
2. Sync Gradle.
3. Build APK.

