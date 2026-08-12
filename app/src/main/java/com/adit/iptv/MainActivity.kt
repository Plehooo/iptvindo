package com.adit.iptv

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.io.BufferedReader
import java.util.zip.GZIPInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors

data class Channel(
    val name: String,
    val url: String,
    val group: String = "",
    val logo: String = "",
    val tvgId: String = "",
    val headers: Map<String, String> = emptyMap()
)

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var list: ListView
    private lateinit var search: EditText
    private lateinit var now: TextView
    private lateinit var status: TextView
    private lateinit var playlistLabel: TextView
    private lateinit var groupSpinner: Spinner
    private lateinit var stats: TextView
    private lateinit var retryButton: Button

    private val channels = mutableListOf<Channel>()
    private val visible = mutableListOf<Channel>()
    private val favorites = LinkedHashSet<String>()
    private val history = ArrayDeque<String>()

    private val io = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("iptv_v3", MODE_PRIVATE) }

    private var favoriteMode = false
    private var historyMode = false
    private var selectedGroup = "Semua"
    private var updatingGroups = false
    private var current: Channel? = null
    private var playlistUrl: String = ""
    private var playlistName = "Belum ada playlist"
    private var retryCount = 0
    private var destroyed = false
    private var lastFailure: PlaybackException? = null

    private val openPlaylistFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            io.execute {
                try {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("File tidak bisa dibaca")
                    val result = parseM3u(text)
                    runOnUiThread {
                        channels.clear()
                        channels.addAll(result)
                        playlistUrl = ""
                        playlistName = "File M3U • ${result.size} channel"
                        playlistLabel.text = playlistName
                        selectedGroup = "Semua"
                        favoriteMode = false
                        historyMode = false
                        persistState()
                        refresh()
                        status.text = "M3U berhasil dimuat • ${result.size} channel"
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        status.text = "Gagal membuka M3U: ${e.message ?: "file tidak valid"}"
                    }
                }
            }
        }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)
        list = findViewById(R.id.channelList)
        search = findViewById(R.id.searchInput)
        now = findViewById(R.id.nowPlaying)
        status = findViewById(R.id.statusText)
        playlistLabel = findViewById(R.id.playlistLabel)
        groupSpinner = findViewById(R.id.groupSpinner)
        stats = findViewById(R.id.statsText)
        retryButton = findViewById(R.id.retryButton)

        restoreState()
        player = createPlayer()
        playerView.player = player

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> status.text = "Buffering ${current?.name ?: ""}..."
                    Player.STATE_READY -> {
                        retryCount = 0
                        retryButton.visibility = View.GONE
                        status.text = if (player.isPlaying) "LIVE • Sedang diputar" else "Siap • tekan Play"
                    }
                    Player.STATE_ENDED -> {
                        status.text = "Stream selesai"
                        if (isProbablyLive(current)) scheduleRetry("Live stream selesai")
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) status.text = "LIVE • ${current?.name ?: "Sedang diputar"}"
            }

            override fun onPlayerError(error: PlaybackException) {
                lastFailure = error
                status.text = buildErrorText(error)
                retryButton.visibility = View.VISIBLE
                if (retryCount < MAX_AUTO_RETRY && isProbablyLive(current)) {
                    scheduleRetry("Retry otomatis ${retryCount + 1}/$MAX_AUTO_RETRY")
                }
            }
        })

        retryButton.setOnClickListener {
            current?.let { retryPlayback(it) }
        }

        findViewById<Button>(R.id.addPlaylistButton).setOnClickListener { playlistDialog() }
        findViewById<Button>(R.id.manualStreamButton).setOnClickListener { manualStreamDialog() }
        findViewById<Button>(R.id.openFileButton).setOnClickListener {
            openPlaylistFile.launch(arrayOf("audio/x-mpegurl", "application/x-mpegURL", "text/plain", "*/*"))
        }
        findViewById<Button>(R.id.homeButton).setOnClickListener {
            favoriteMode = false
            historyMode = false
            refresh()
        }
        findViewById<Button>(R.id.liveButton).setOnClickListener {
            favoriteMode = false
            historyMode = false
            refresh()
        }
        findViewById<Button>(R.id.favoritesButton).setOnClickListener {
            favoriteMode = true
            historyMode = false
            refresh()
        }
        findViewById<Button>(R.id.historyButton).setOnClickListener {
            favoriteMode = false
            historyMode = true
            refresh()
        }
        findViewById<Button>(R.id.playlistButton).setOnClickListener { playlistDialog() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { settingsDialog() }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
            override fun afterTextChanged(s: Editable?) = Unit
        })

        groupSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingGroups) return
                selectedGroup = parent?.getItemAtPosition(position)?.toString() ?: "Semua"
                refresh()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        list.setOnItemClickListener { _, _, position, _ ->
            if (position < visible.size) play(visible[position])
        }

        list.setOnItemLongClickListener { _, _, position, _ ->
            if (position < visible.size) {
                showChannelActions(visible[position])
                true
            } else false
        }

        playlistLabel.text = playlistName
        refresh()
        status.text = if (channels.isEmpty()) "Masukkan URL M3U/M3U8 untuk mulai" else "Siap"
    }

    private fun createPlayer(): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2_500,
                12_000,
                1_000,
                2_500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
            )
            .build()
    }

    private fun play(channel: Channel) {
        if (channel.url.isBlank()) return

        current = channel
        retryCount = 0
        lastFailure = null
        retryButton.visibility = View.GONE
        now.text = channel.name
        addHistory(channel.url)
        persistState()
        refresh()

        prepareStream(channel)
    }

    private fun prepareStream(channel: Channel) {
        try {
            val url = channel.url.trim()
            require(url.startsWith("http://") || url.startsWith("https://")) {
                "Hanya http/https yang didukung"
            }

            // Signed URL is intentionally passed as-is. We do not substringBefore("?")
            // and we do not rebuild its query string.
            val mediaItemBuilder = MediaItem.Builder().setUri(url.toUri())

            val lower = url.substringBefore('#').lowercase(Locale.US)
            when {
                lower.substringBefore('?').endsWith(".m3u8") -> {
                    mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                    mediaItemBuilder.setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(3_000)
                            .setMinPlaybackSpeed(0.97f)
                            .setMaxPlaybackSpeed(1.03f)
                            .build()
                    )
                }
                lower.substringBefore('?').endsWith(".mpd") -> {
                    mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
                }
            }

            val http = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
                .setUserAgent(
                    channel.headers["User-Agent"]
                        ?: channel.headers["user-agent"]
                        ?: DEFAULT_USER_AGENT
                )
                .setDefaultRequestProperties(buildRequestHeaders(channel))

            val factory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(http)

            player.setMediaSource(factory.createMediaSource(mediaItemBuilder.build()))
            player.prepare()
            player.playWhenReady = true
            status.text = "Menghubungkan • ${channel.name}"
        } catch (e: Exception) {
            status.text = "Gagal menyiapkan stream: ${e.message ?: "unknown error"}"
            retryButton.visibility = View.VISIBLE
        }
    }

    private fun buildRequestHeaders(channel: Channel): Map<String, String> {
        val result = linkedMapOf<String, String>()
        result["Icy-MetaData"] = "1"
        result["Accept"] = "*/*"
        result["Connection"] = "keep-alive"
        result.putAll(channel.headers)
        return result
    }

    private fun retryPlayback(channel: Channel) {
        retryCount++
        retryButton.visibility = View.GONE
        status.text = "Mencoba lagi ($retryCount)..."
        mainHandler.postDelayed({ if (!destroyed) prepareStream(channel) }, 350L)
    }

    private fun scheduleRetry(reason: String) {
        retryCount++
        status.text = reason
        mainHandler.postDelayed({
            if (!destroyed) current?.let { prepareStream(it) }
        }, 900L * retryCount)
    }

    private fun isProbablyLive(channel: Channel?): Boolean {
        val u = channel?.url?.lowercase(Locale.US) ?: return false
        return u.contains(".m3u8") || u.contains(".mpd") || u.contains("/live")
    }

    private fun buildErrorText(error: PlaybackException): String {
        val code = error.errorCodeName.ifBlank { "PLAYBACK_ERROR" }
        return "Gagal memutar • $code"
    }

    private fun addHistory(url: String) {
        history.remove(url)
        history.addFirst(url)
        while (history.size > 50) history.removeLast()
    }

    private fun toggleFavorite(channel: Channel) {
        if (!favorites.add(channel.url)) favorites.remove(channel.url)
        persistState()
        refresh()
    }

    private fun showChannelActions(channel: Channel) {
        val fav = favorites.contains(channel.url)
        AlertDialog.Builder(this)
            .setTitle(channel.name)
            .setItems(
                arrayOf(
                    if (fav) "Hapus dari Favorite" else "Tambah ke Favorite",
                    "Salin URL",
                    "Putar sekarang",
                    "Detail"
                )
            ) { _, which ->
                when (which) {
                    0 -> toggleFavorite(channel)
                    1 -> copyText(channel.url)
                    2 -> play(channel)
                    3 -> showChannelDetails(channel)
                }
            }
            .show()
    }

    private fun showChannelDetails(channel: Channel) {
        val headers = if (channel.headers.isEmpty()) "Tidak ada header khusus" else
            channel.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }

        AlertDialog.Builder(this)
            .setTitle("Detail channel")
            .setMessage(
                "Nama: ${channel.name}\n" +
                "Group: ${channel.group.ifBlank { "-" }}\n" +
                "TVG ID: ${channel.tvgId.ifBlank { "-" }}\n" +
                "Logo: ${channel.logo.ifBlank { "-" }}\n\n" +
                "Header:\n$headers\n\n" +
                "URL:\n${channel.url}"
            )
            .setPositiveButton("Tutup", null)
            .show()
    }

    private fun copyText(value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("IPTV URL", value))
        Toast.makeText(this, "URL disalin", Toast.LENGTH_SHORT).show()
    }

    private fun playlistDialog() {
        val input = EditText(this).apply {
            hint = "https://server.com/playlist.m3u / .m3u8"
            setSingleLine(true)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 8, 28, 0)
        }
        box.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Tambah M3U / M3U8")
            .setMessage(
                "Pilih salah satu:\n" +
                "• URL playlist M3U/M3U8\n" +
                "• File .m3u dari HP\n\n" +
                "Signed URL seperti ?hdnts=exp=...~hmac=... tetap utuh."
            )
            .setView(box)
            .setNegativeButton("Batal", null)
            .setNeutralButton("FILE M3U") { _, _ ->
                openPlaylistFile.launch(arrayOf(
                    "audio/x-mpegurl",
                    "application/x-mpegURL",
                    "text/plain",
                    "*/*"
                ))
            }
            .setPositiveButton("LOAD URL") { _, _ ->
                loadPlaylist(input.text.toString().trim())
            }
            .show()
    }

    private fun manualStreamDialog() {
        val input = EditText(this).apply {
            hint = "https://server.com/live.m3u8 atau .mpd"
            setSingleLine(false)
            minLines = 2
        }

        AlertDialog.Builder(this)
            .setTitle("Putar URL Manual")
            .setMessage(
                "Masukkan URL stream langsung.\n" +
                "Mendukung .m3u8 HLS dan .mpd DASH.\n" +
                "Jangan hapus query token seperti ?hdnts=..."
            )
            .setView(input)
            .setNegativeButton("Batal", null)
            .setPositiveButton("PUTAR") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) {
                    status.text = "URL kosong"
                } else {
                    val channel = Channel(
                        name = guessStreamName(url),
                        url = url
                    )
                    play(channel)
                }
            }
            .show()
    }

    private fun guessStreamName(url: String): String {
        val path = runCatching { URL(url).path }.getOrNull().orEmpty()
        val name = path.substringAfterLast('/').substringBeforeLast('.')
        return if (name.isBlank()) "Manual Stream" else name
    }

    private fun loadPlaylist(url: String) {
        if (url.isBlank()) return

        val lower = url.substringBefore('#').lowercase(Locale.US)
        val path = lower.substringBefore('?')

        if (path.endsWith(".mpd")) {
            play(Channel(guessStreamName(url), url))
            return
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            status.text = "URL playlist tidak valid"
            return
        }

        status.text = "Mengambil M3U/M3U8..."
        io.execute {
            try {
                val text = fetchText(url)
                val result = parseM3u(text)

                runOnUiThread {
                    if (result.isEmpty()) {
                        // A direct HLS manifest is technically an M3U8 too.
                        // It has no #EXTINF channels, so play it directly.
                        if (text.contains("#EXTM3U", true)) {
                            play(Channel(guessStreamName(url), url))
                            status.text = "M3U8 manifest langsung • ${guessStreamName(url)}"
                        } else {
                            status.text = "File bukan playlist M3U yang berisi channel"
                        }
                    } else {
                        channels.clear()
                        channels.addAll(result)
                        playlistUrl = url
                        playlistName = "Playlist • ${result.size} channel"
                        playlistLabel.text = playlistName
                        selectedGroup = "Semua"
                        favoriteMode = false
                        historyMode = false
                        persistState()
                        refresh()
                        status.text = "Playlist berhasil • ${result.size} channel"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Playlist error: ${e.message ?: "unknown error"}"
                }
            }
        }
    }

    private fun fetchText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.connect()

            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")

            val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE)
                .find(connection.contentType.orEmpty())
                ?.groupValues?.getOrNull(1)
                ?.trim()
                ?.let { runCatching { java.nio.charset.Charset.forName(it) }.getOrNull() }
                ?: StandardCharsets.UTF_8

            val raw = connection.inputStream
            val stream = if (connection.contentEncoding.equals("gzip", true)) {
                java.util.zip.GZIPInputStream(raw)
            } else raw

            return stream.use {
                BufferedReader(InputStreamReader(it, charset)).readText()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseM3u(text: String): List<Channel> {
        val result = mutableListOf<Channel>()

        var pendingName = "Unknown"
        var pendingGroup = ""
        var pendingLogo = ""
        var pendingTvgId = ""
        var pendingHeaders = linkedMapOf<String, String>()

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF", true)) {
                val comma = line.indexOf(',')
                pendingName = if (comma >= 0) line.substring(comma + 1).trim() else "Unknown"
                pendingGroup = attr(line, "group-title")
                pendingLogo = attr(line, "tvg-logo")
                pendingTvgId = attr(line, "tvg-id")
                pendingHeaders = linkedMapOf()
                parseExtInfHeaders(line, pendingHeaders)
                continue
            }

            if (line.startsWith("#EXTVLCOPT:", true)) {
                val pair = line.substringAfter(':')
                val key = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                when (key.lowercase(Locale.US)) {
                    "http-user-agent" -> pendingHeaders["User-Agent"] = value
                    "http-referrer", "http-referer" -> pendingHeaders["Referer"] = value
                    "http-origin" -> pendingHeaders["Origin"] = value
                    "http-header" -> parseHeaderLine(value, pendingHeaders)
                }
                continue
            }

            if (line.startsWith("#EXTHTTP:", true)) {
                parseJsonLikeHeaders(line.substringAfter(':'), pendingHeaders)
                continue
            }

            if (line.startsWith("#KODIPROP:", true)) {
                val key = line.substringBefore('=').lowercase(Locale.US)
                val value = line.substringAfter('=', "")
                if ("stream_headers" in key) parseAmpersandHeaders(value, pendingHeaders)
                continue
            }

            if (line.startsWith("http://", true) || line.startsWith("https://", true)) {
                result += Channel(
                    name = pendingName,
                    url = line,
                    group = pendingGroup,
                    logo = pendingLogo,
                    tvgId = pendingTvgId,
                    headers = pendingHeaders.toMap()
                )

                pendingName = "Unknown"
                pendingGroup = ""
                pendingLogo = ""
                pendingTvgId = ""
                pendingHeaders = linkedMapOf()
            }
        }

        return result.distinctBy { it.url }
    }

    private fun attr(line: String, key: String): String {
        val pattern = Regex(
            """$key\s*=\s*(?:"([^"]*)"|'([^']*)'|([^,\s>]+))""",
            RegexOption.IGNORE_CASE
        )
        val m = pattern.find(line) ?: return ""
        return listOfNotNull(
            m.groups[1]?.value,
            m.groups[2]?.value,
            m.groups[3]?.value
        ).firstOrNull().orEmpty()
    }

    private fun parseExtInfHeaders(line: String, headers: MutableMap<String, String>) {
        Regex("""(?:http-user-agent|user-agent)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.getOrNull(1)?.let { headers["User-Agent"] = it }

        Regex("""(?:http-referrer|http-referer|referer)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.getOrNull(1)?.let { headers["Referer"] = it }

        Regex("""origin\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.getOrNull(1)?.let { headers["Origin"] = it }
    }

    private fun parseHeaderLine(value: String, headers: MutableMap<String, String>) {
        value.split('&', '|').forEach { pair ->
            val k = pair.substringBefore('=').trim()
            val v = pair.substringAfter('=', "").trim()
            if (k.isNotBlank() && v.isNotBlank()) headers[normalizeHeader(k)] = v
        }
    }

    private fun parseAmpersandHeaders(value: String, headers: MutableMap<String, String>) {
        parseHeaderLine(java.net.URLDecoder.decode(value, "UTF-8"), headers)
    }

    private fun parseJsonLikeHeaders(value: String, headers: MutableMap<String, String>) {
        Regex(""""([^"]+)"\s*:\s*"([^"]*)"""").findAll(value).forEach { m ->
            headers[normalizeHeader(m.groupValues[1])] = m.groupValues[2]
        }
    }

    private fun normalizeHeader(key: String): String {
        return when (key.lowercase(Locale.US)) {
            "user-agent" -> "User-Agent"
            "referer", "referrer" -> "Referer"
            "origin" -> "Origin"
            else -> key.trim()
        }
    }

    private fun refresh() {
        val q = search.text.toString().trim().lowercase(Locale.US)

        val source = when {
            historyMode -> history.mapNotNull { url -> channels.find { it.url == url } }
            else -> channels
        }

        visible.clear()
        visible.addAll(
            source.filter { channel ->
                (!favoriteMode || favorites.contains(channel.url)) &&
                    (selectedGroup == "Semua" || channel.group == selectedGroup) &&
                    (
                        q.isBlank() ||
                            channel.name.lowercase(Locale.US).contains(q) ||
                            channel.group.lowercase(Locale.US).contains(q) ||
                            channel.tvgId.lowercase(Locale.US).contains(q)
                    )
            }
        )

        val rows = visible.map {
            val star = if (favorites.contains(it.url)) "★ " else ""
            val group = if (it.group.isBlank()) "" else " • ${it.group}"
            "$star${it.name}$group"
        }

        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        stats.text = "${visible.size}/${channels.size} channel • ${favorites.size} favorite"
        updateGroups()
    }

    private fun updateGroups() {
        val groups = mutableListOf("Semua")
        groups += channels.map { it.group.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })

        val currentIndex = groups.indexOf(selectedGroup).takeIf { it >= 0 } ?: 0
        updatingGroups = true
        groupSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            groups
        )
        groupSpinner.setSelection(currentIndex, false)
        updatingGroups = false
    }

    private fun MutableList<String>.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it }): List<String> =
        sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })

    private fun persistState() {
        prefs.edit()
            .putString("playlist_url", playlistUrl)
            .putString("playlist_name", playlistName)
            .putStringSet("favorites", favorites)
            .putString("history", history.joinToString("\n"))
            .apply()
    }

    private fun restoreState() {
        playlistUrl = prefs.getString("playlist_url", "").orEmpty()
        playlistName = prefs.getString("playlist_name", "Belum ada playlist").orEmpty()
        favorites.clear()
        favorites.addAll(prefs.getStringSet("favorites", emptySet()).orEmpty())
        history.clear()
        prefs.getString("history", "")
            ?.lines()
            ?.filter { it.isNotBlank() }
            ?.forEach { history.addLast(it) }

        if (playlistUrl.isNotBlank()) {
            io.execute {
                try {
                    val result = parseM3u(fetchText(playlistUrl))
                    runOnUiThread {
                        channels.clear()
                        channels.addAll(result)
                        playlistLabel.text = playlistName
                        refresh()
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        playlistLabel.text = "$playlistName • refresh gagal"
                    }
                }
            }
        }
    }

    private fun settingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Player V3")
            .setMessage(
                "HLS/M3U8 signed URL\n" +
                "Per-channel User-Agent / Referer / Origin\n" +
                "Auto retry live stream\n" +
                "Persistent playlist / favorites / history\n" +
                "Group filter + search\n" +
                "Exact signed query preservation\n" +
                "M3U / M3U8 / MPD\n\n" +
                "Catatan: token signed yang expired tetap ditolak oleh server."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun updateDialog() {
        AlertDialog.Builder(this)
            .setTitle("Update")
            .setMessage(
                "Versi V3 sudah memusatkan playback pada Media3 dan transport HTTP. " +
                "APK baru tetap harus ditandatangani dengan keystore yang sama saat upgrade."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onStop() {
        super.onStop()
        persistState()
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        player.release()
        super.onDestroy()
    }

    companion object {
        private const val MAX_AUTO_RETRY = 3
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    }
}
