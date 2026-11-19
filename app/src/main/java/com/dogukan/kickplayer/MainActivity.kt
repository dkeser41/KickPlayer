package com.dogukan.kickplayer

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.concurrent.thread

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var playerView: PlayerView
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var qualityRow: HorizontalScrollView
    private lateinit var channelInput: EditText

    private val qualityButtons = mutableListOf<Pair<Int, Button>>()
    private val client = OkHttpClient()
    private val prefs by lazy { getSharedPreferences("kick", Context.MODE_PRIVATE) }
    private var inPip = false

    // Ekran uykuya girmesin: pencere bayrağı, uygulama açık olduğu sürece geçerli.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        buildUi()
        initPlayer()

        applyQuality(prefs.getInt("maxHeight", 0))
        updateChrome()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
            setText(prefs.getString("channel", ""))
        }
        val playBtn = Button(this).apply {
            text = "İzle"
            setOnClickListener { startChannel() }
        }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), 0)
            addView(channelInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(playBtn)
        }

        val qualityLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        val options = listOf("Auto" to 0, "1080p" to 1080, "720p" to 720, "480p" to 480, "360p" to 360)
        for ((label, height) in options) {
            val b = Button(this).apply {
                text = label
                setOnClickListener {
                    prefs.edit().putInt("maxHeight", height).apply()
                    applyQuality(height)
                }
            }
            qualityButtons.add(height to b)
            qualityLayout.addView(b)
        }
        qualityRow = HorizontalScrollView(this).apply { addView(qualityLayout) }

        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }

        playerView = PlayerView(this).apply {
            visibility = View.GONE
        }
        webView = WebView(this).apply {
            visibility = View.GONE
        }
        val content = FrameLayout(this).apply {
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
            addView(webView, FrameLayout.LayoutParams(-1, -1))
        }

        root.addView(topBar)
        root.addView(qualityRow)
        root.addView(status)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun initPlayer() {
        trackSelector = DefaultTrackSelector(this)
        player = ExoPlayer.Builder(this).setTrackSelector(trackSelector).build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                status.text = "Oynatma hatası: ${error.errorCodeName}"
            }
        })
        playerView.player = player
    }

    // Kalite tercihi kalıcı: her yayında ve uygulama yeniden açılınca aynı üst sınır uygulanır.
    // 0 = Auto (sınırsız). Ağ kötüleşirse oynatıcı yine de seçilen sınırın altına inebilir.
    private fun applyQuality(maxHeight: Int) {
        val h = if (maxHeight == 0) Int.MAX_VALUE else maxHeight
        val w = if (maxHeight == 0) Int.MAX_VALUE else maxHeight * 16 / 9 + 1
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setMaxVideoSize(w, h)
            .build()
        for ((height, b) in qualityButtons) {
            b.alpha = if (height == maxHeight) 1f else 0.5f
        }
    }

    private fun startChannel() {
        val slug = channelInput.text.toString().trim().lowercase()
            .substringAfterLast('/')
            .replace('_', '-')
        if (slug.isEmpty()) return
        prefs.edit().putString("channel", slug).apply()
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
        webView.visibility = View.GONE
        webView.loadUrl("about:blank")
        playerView.visibility = View.VISIBLE
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
        status.text = "Yayın: $slug"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun fallbackToWeb(slug: String, reason: String) {
        player.stop()
        playerView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webViewClient = WebViewClient()
        webView.loadUrl("https://kick.com/$slug")
        status.text = "Doğrudan akış alınamadı ($reason), web oynatıcıya geçildi"
    }

    // Yatayda ve PiP'te kontrolleri gizle, sadece video kalsın.
    private fun updateChrome() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val vis = if (inPip || landscape) View.GONE else View.VISIBLE
        topBar.visibility = vis
        qualityRow.visibility = vis
        status.visibility = vis
        playerView.useController = !inPip
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
    }
}
