package com.dogukan.kickplayer

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.concurrent.thread

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var webView: WebView   // yedek: kick.com sayfası
    private lateinit var chatView: WebView
    private lateinit var videoBox: FrameLayout
    private lateinit var status: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var recentRow: HorizontalScrollView
    private lateinit var recentLayout: LinearLayout
    private lateinit var qualityRow: HorizontalScrollView
    private lateinit var qualityLayout: LinearLayout
    private lateinit var channelInput: EditText

    private val qualityButtons = mutableListOf<Pair<Int, Button>>()
    private val client = OkHttpClient()
    private val prefs by lazy { getSharedPreferences("kick", Context.MODE_PRIVATE) }

    private var inPip = false
    private var fallback = false
    private var chatOn = true
    private var currentSlug = ""
    private var currentHeight = 0
    private var availableHeights: List<Int> = emptyList()
    private var trackChoices: Map<Int, Triple<Int, Tracks.Group, Int>> = emptyMap()
    private var selectedHeight = 0 // 0 = Auto

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ekran uykuya girmesin.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        chatOn = prefs.getBoolean("chatOn", true)

        buildUi()
        initPlayer()
        rebuildRecent()
        rebuildQualityButtons()
        updateChrome()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun chip(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb(w: WebView) {
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webViewClient = WebViewClient()
        w.setBackgroundColor(Color.BLACK)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        channelInput = EditText(this).apply {
            hint = "kanal adı (örn: xqc)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
            setText(prefs.getString("channel", ""))
            setOnEditorActionListener { _, _, _ -> startChannel(); true }
        }
        val playBtn = chip("İzle") { startChannel() }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), 0)
            addView(channelInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(playBtn)
        }

        recentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        recentRow = HorizontalScrollView(this).apply { addView(recentLayout) }

        qualityLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        qualityRow = HorizontalScrollView(this).apply { addView(qualityLayout) }

        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }

        playerView = PlayerView(this).apply {
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShowRewindButton(false)
            setShowFastForwardButton(false)
            setFullscreenButtonClickListener { isFull -> toggleFullscreen(isFull) }
            visibility = View.GONE
        }
        webView = WebView(this).apply { visibility = View.GONE }
        setupWeb(webView)
        videoBox = FrameLayout(this).apply {
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
            addView(webView, FrameLayout.LayoutParams(-1, -1))
        }

        chatView = WebView(this)
        setupWeb(chatView)

        root.addView(topBar)
        root.addView(recentRow)
        root.addView(qualityRow)
        root.addView(status)
        root.addView(videoBox, LinearLayout.LayoutParams(-1, resources.displayMetrics.widthPixels * 9 / 16))
        root.addView(chatView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                status.text = "Oynatma hatası: ${error.errorCodeName}"
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                currentHeight = videoSize.height
                updateStatus()
            }

            override fun onTracksChanged(tracks: Tracks) {
                onTracks(tracks)
            }
        })
        playerView.player = player
    }

    private fun updateStatus() {
        if (fallback || currentSlug.isEmpty()) return
        val q = if (currentHeight > 0) " · ${currentHeight}p" else ""
        status.text = "Yayın: $currentSlug$q"
    }

    // Yayının gerçekten sunduğu kaliteleri bul (her yükseklik için en yüksek bitrate'li olanı).
    private fun onTracks(tracks: Tracks) {
        val best = HashMap<Int, Triple<Int, Tracks.Group, Int>>()
        for (g in tracks.groups) {
            if (g.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val h = g.getTrackFormat(i).height
                if (h <= 0) continue
                val old = best[h]
                if (old == null ||
                    g.getTrackFormat(i).bitrate > old.second.getTrackFormat(old.third).bitrate
                ) {
                    best[h] = Triple(h, g, i)
                }
            }
        }
        val heights = best.keys.sortedDescending()
        if (heights == availableHeights) return
        availableHeights = heights
        trackChoices = best
        applyQualityPreference()
        rebuildQualityButtons()
    }

    private fun applyQualityPreference() {
        selectQuality(prefs.getInt("maxHeight", 0), false)
    }

    // pref 0 = Auto. Aksi halde o kaliteye (ya da en yakın düşüğüne) sabitlenir.
    private fun selectQuality(pref: Int, save: Boolean) {
        if (save) prefs.edit().putInt("maxHeight", pref).apply()
        val params = player.trackSelectionParameters.buildUpon()
        val target = if (pref == 0 || availableHeights.isEmpty()) {
            0
        } else {
            availableHeights.firstOrNull { it <= pref } ?: availableHeights.last()
        }
        val choice = trackChoices[target]
        if (target == 0 || choice == null) {
            params.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            selectedHeight = 0
        } else {
            params.setOverrideForType(TrackSelectionOverride(choice.second.mediaTrackGroup, choice.third))
            selectedHeight = target
        }
        player.trackSelectionParameters = params.build()
        refreshQualityHighlight()
    }

    private fun refreshQualityHighlight() {
        for ((h, b) in qualityButtons) {
            b.alpha = if (h == selectedHeight) 1f else 0.5f
        }
    }

    private fun rebuildQualityButtons() {
        qualityLayout.removeAllViews()
        qualityButtons.clear()
        qualityLayout.addView(chip(if (chatOn) "Sohbet: açık" else "Sohbet: kapalı") { toggleChat() })
        val auto = chip("Auto") { selectQuality(0, true) }
        qualityButtons.add(0 to auto)
        qualityLayout.addView(auto)
        for (h in availableHeights) {
            val b = chip("${h}p") { selectQuality(h, true) }
            qualityButtons.add(h to b)
            qualityLayout.addView(b)
        }
        refreshQualityHighlight()
    }

    private fun toggleChat() {
        chatOn = !chatOn
        prefs.edit().putBoolean("chatOn", chatOn).apply()
        if (chatOn) loadChat()
        rebuildQualityButtons()
        updateChrome()
    }

    private fun loadChat() {
        if (currentSlug.isEmpty() || !chatOn) return
        if (chatView.url?.contains("/$currentSlug/") == true) return
        chatView.loadUrl("https://kick.com/popout/$currentSlug/chat")
    }

    private fun recentList(): List<String> =
        (prefs.getString("recent", "") ?: "").split(",").filter { it.isNotBlank() }

    private fun addRecent(slug: String) {
        val list = (listOf(slug) + recentList().filter { it != slug }).take(8)
        prefs.edit().putString("recent", list.joinToString(",")).apply()
        rebuildRecent()
        updateChrome()
    }

    private fun rebuildRecent() {
        recentLayout.removeAllViews()
        for (s in recentList()) {
            recentLayout.addView(chip(s) {
                channelInput.setText(s)
                startChannel()
            })
        }
    }

    private fun startChannel() {
        val slug = channelInput.text.toString().trim().lowercase()
            .substringAfterLast('/')
            .replace('_', '-')
        if (slug.isEmpty()) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(channelInput.windowToken, 0)
        prefs.edit().putString("channel", slug).apply()
        currentSlug = slug
        currentHeight = 0
        status.text = "Bağlanıyor…"
        thread {
            try {
                val url = fetchPlaybackUrl(slug)
                runOnUiThread { playHls(slug, url) }
            } catch (e: Exception) {
                runOnUiThread { fallbackToWeb(slug, e.message ?: "bilinmeyen hata") }
            }
        }
    }

    // Kick'in resmi olmayan kanal endpoint'i. Cloudflare engelleyebilir ya da değişebilir.
    private fun fetchPlaybackUrl(slug: String): String {
        val req = Request.Builder()
            .url("https://kick.com/api/v2/channels/$slug")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            )
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
            val body = r.body?.string() ?: throw RuntimeException("boş cevap")
            val url = JSONObject(body).optString("playback_url")
            if (url.isEmpty()) throw RuntimeException("playback_url yok (yayın kapalı olabilir)")
            return url
        }
    }

    private fun playHls(slug: String, url: String) {
        fallback = false
        webView.visibility = View.GONE
        webView.loadUrl("about:blank")
        playerView.visibility = View.VISIBLE
        availableHeights = emptyList()
        trackChoices = emptyMap()
        selectedHeight = 0
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .build()
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
        addRecent(slug)
        loadChat()
        rebuildQualityButtons()
        updateStatus()
        updateChrome()
    }

    private fun fallbackToWeb(slug: String, reason: String) {
        fallback = true
        player.stop()
        playerView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl("https://kick.com/$slug")
        status.text = "Doğrudan akış alınamadı ($reason), web oynatıcıya geçildi"
        updateChrome()
    }

    private fun toggleFullscreen(full: Boolean) {
        requestedOrientation = if (full) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun setImmersive(on: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (on) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
    }

    // Dikeyde: üstte kontroller, 16:9 video, altta sohbet. Yatayda ve PiP'te sadece video.
    private fun updateChrome() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val compact = landscape || inPip
        val chromeVis = if (compact) View.GONE else View.VISIBLE
        topBar.visibility = chromeVis
        status.visibility = chromeVis
        recentRow.visibility = if (!compact && recentLayout.childCount > 0) View.VISIBLE else View.GONE
        qualityRow.visibility = if (!compact && !fallback) View.VISIBLE else View.GONE

        val fill = compact || fallback
        val lp = videoBox.layoutParams as LinearLayout.LayoutParams
        if (fill) {
            lp.height = 0
            lp.weight = 1f
        } else {
            lp.height = resources.displayMetrics.widthPixels * 9 / 16
            lp.weight = 0f
        }
        videoBox.layoutParams = lp
        chatView.visibility = if (!fill && chatOn) View.VISIBLE else View.GONE

        playerView.useController = !inPip
        setImmersive(landscape && !inPip)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateChrome()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && player.isPlaying) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        updateChrome()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
        webView.destroy()
        chatView.destroy()
    }
}
