# IPTV Player — Full Edition

Native Android IPTV player foundation inspired by modern IPTV app UX.

Included:
- Native Media3 player
- HLS / M3U8
- MPEG-DASH / MPD
- M3U playlist parser
- tvg-id / tvg-logo / group-title
- Search
- Favorites
- History
- Playlist management entry point
- Home/Live/Favorites/History navigation
- Player status and playback error reporting
- Update entry point/foundation
- GitHub Actions build

Current limitations:
- XMLTV EPG is not yet wired into the UI.
- Persistent Room storage is reserved for the next module.
- APK auto-update requires a signed release APK and a hosted update manifest.
- The app does not bypass DRM, authentication, geo restrictions, ACLs, or other access controls.

Build:
1. Upload this whole folder to GitHub.
2. Open Actions.
3. Run "Build IPTV APK".
4. Download the IPTVPlayer-debug artifact.
