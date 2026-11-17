package fr.perso.ytmp3.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import fr.perso.ytmp3.R
import java.util.concurrent.Executors

/** Lecteur rétro : afficheur LCD, spectre, égaliseur et liste de lecture. */
class PlayerActivity : Activity() {

    companion object {
        private const val EXTRA_PLAY_URI = "play_uri"

        /** Ouvre le lecteur ; si [playUri] est donné, lance ce morceau. */
        fun open(context: Context, playUri: Uri? = null) {
            context.startActivity(intent(context, playUri))
        }

        fun intent(context: Context, playUri: Uri? = null): Intent =
            Intent(context, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (playUri != null) putExtra(EXTRA_PLAY_URI, playUri.toString()) }

        private fun formatTime(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0)
            return "%02d:%02d".format(total / 60, total % 60)
        }
    }

    private val background = Executors.newSingleThreadExecutor()
    private var library: List<Track> = emptyList()
    private var pendingPlayUri: Uri? = null
    private var showRemaining = false
    private var seeking = false
    private var infoForUri: Uri? = null
    private val levels = FloatArray(SpectrumAnalyzer.BANDS)
    private val stateListener: (PlayerState.Snapshot) -> Unit = { render(it) }

    private lateinit var timeText: TextView
    private lateinit var stateIcon: TextView
    private lateinit var marquee: TextView
    private lateinit var infoText: TextView
    private lateinit var spectrum: SpectrumView
    private lateinit var seekBar: SeekBar
    private lateinit var volumeBar: SeekBar
    private lateinit var shuffleButton: ImageButton
    private lateinit var repeatButton: ImageButton
    private lateinit var eqToggle: TextView
    private lateinit var listToggle: TextView
    private lateinit var eqPanel: View
    private lateinit var eqView: EqView
    private lateinit var eqOn: TextView
    private lateinit var eqMessage: TextView
    private lateinit var listPanel: View
    private lateinit var listView: ListView
    private lateinit var listEmpty: TextView
    private lateinit var listHeader: TextView
    private val adapter = TrackAdapter()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            onFrame()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        timeText = findViewById(R.id.time)
        stateIcon = findViewById(R.id.stateIcon)
        marquee = findViewById(R.id.marquee)
        infoText = findViewById(R.id.info)
        spectrum = findViewById(R.id.spectrum)
        seekBar = findViewById(R.id.seek)
        volumeBar = findViewById(R.id.volume)
        shuffleButton = findViewById(R.id.shuffle)
        repeatButton = findViewById(R.id.repeat)
        eqToggle = findViewById(R.id.eqToggle)
        listToggle = findViewById(R.id.listToggle)
        eqPanel = findViewById(R.id.eqPanel)
        eqView = findViewById(R.id.eq)
        eqOn = findViewById(R.id.eqOn)
        eqMessage = findViewById(R.id.eqMessage)
        listPanel = findViewById(R.id.listPanel)
        listView = findViewById(R.id.list)
        listEmpty = findViewById(R.id.listEmpty)
        listHeader = findViewById(R.id.listHeader)

        marquee.isSelected = true // nécessaire pour que le titre défile
        listView.adapter = adapter

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.play).setOnClickListener { onPlayPressed() }
        findViewById<View>(R.id.pause).setOnClickListener { PlayerService.instance?.togglePause() }
        findViewById<View>(R.id.stop).setOnClickListener { PlayerService.instance?.stopPlayback() }
        findViewById<View>(R.id.next).setOnClickListener { skip(+1) }
        findViewById<View>(R.id.prev).setOnClickListener { skip(-1) }
        timeText.setOnClickListener { showRemaining = !showRemaining } // temps écoulé / restant

        shuffleButton.setOnClickListener { PlayerState.update { it.copy(shuffle = !it.shuffle) } }
        repeatButton.setOnClickListener { PlayerState.update { it.copy(repeat = !it.repeat) } }

        listView.setOnItemClickListener { _, _, position, _ ->
            PlayerService.play(this, library, position)
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = PlayerState.current.durationMs
                    timeText.text = formatTime(duration * progress / 1000)
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                val duration = PlayerState.current.durationMs
                PlayerService.instance?.seekTo(duration * bar.progress / 1000)
            }
        })

        val prefs = getSharedPreferences("player", MODE_PRIVATE)
        volumeBar.progress = ((PlayerService.instance?.getVolume() ?: prefs.getFloat("volume", 1f)) * 100).toInt()
        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val value = progress / 100f
                val service = PlayerService.instance
                if (service != null) service.setVolume(value)
                else prefs.edit().putFloat("volume", value).apply()
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        eqToggle.setOnClickListener {
            eqPanel.visibility = if (eqPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            setupEqualizer()
            renderToggles()
        }
        listToggle.setOnClickListener {
            listPanel.visibility = if (listPanel.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
            renderToggles()
        }
        eqOn.setOnClickListener {
            val service = PlayerService.instance ?: return@setOnClickListener
            service.eqEnabled = !service.eqEnabled
            renderToggles()
        }
        findViewById<View>(R.id.eqReset).setOnClickListener { eqView.resetLevels() }
        eqView.onChange = { band, level -> PlayerService.instance?.setEqLevel(band, level) }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        PlayerState.addListener(stateListener)
        render(PlayerState.current)
        loadLibrary()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onStop() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        PlayerState.removeListener(stateListener)
        super.onStop()
    }

    override fun onDestroy() {
        background.shutdown()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        pendingPlayUri = intent?.getStringExtra(EXTRA_PLAY_URI)?.let { Uri.parse(it) }
        intent?.removeExtra(EXTRA_PLAY_URI)
        if (library.isNotEmpty()) playPendingIfAny()
    }

    private fun loadLibrary() {
        background.execute {
            val tracks = try {
                Library.load(this)
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                library = tracks
                adapter.notifyDataSetChanged()
                val empty = tracks.isEmpty()
                listView.visibility = if (empty) View.GONE else View.VISIBLE
                listEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                val total = tracks.sumOf { it.durationMs }
                listHeader.text = "LISTE DE LECTURE · ${tracks.size} morceau${if (tracks.size > 1) "x" else ""} · ${formatTime(total)}"
                playPendingIfAny()
                render(PlayerState.current)
            }
        }
    }

    private fun playPendingIfAny() {
        val uri = pendingPlayUri ?: return
        pendingPlayUri = null
        val index = library.indexOfFirst { it.uri == uri }
        if (index >= 0) PlayerService.play(this, library, index)
    }

    // ---------------------------------------------------------------- commandes

    private fun onPlayPressed() {
        val service = PlayerService.instance
        val s = PlayerState.current
        when {
            service != null && s.track != null -> service.resume()
            library.isNotEmpty() -> {
                // Lecteur arrêté : on reprend le morceau sélectionné (ou le premier).
                val index = s.track?.let { t -> library.indexOfFirst { it.uri == t.uri } }?.takeIf { it >= 0 } ?: 0
                PlayerService.play(this, library, index)
            }
        }
    }

    private fun skip(direction: Int) {
        val service = PlayerService.instance
        if (service != null) {
            if (direction > 0) service.next() else service.previous()
            return
        }
        if (library.isEmpty()) return
        val current = PlayerState.current.track?.let { t -> library.indexOfFirst { it.uri == t.uri } } ?: -1
        val index = (current + direction).mod(library.size)
        PlayerService.play(this, library, index)
    }

    // ---------------------------------------------------------------- affichage

    private fun render(s: PlayerState.Snapshot) {
        val track = s.track
        val number = if (track != null) "${s.index + 1}. " else ""
        val title = track?.title ?: "YT MP3 · prêt"
        val newText = "$number$title  ***  "
        if (marquee.text.toString() != newText) marquee.text = newText

        stateIcon.text = when {
            s.playing -> "▶ LECTURE"
            track != null && PlayerService.instance != null -> "❚❚ PAUSE"
            else -> "■ ARRÊT"
        }

        if (track != null && infoForUri != track.uri) {
            infoForUri = track.uri
            infoText.text = ""
            loadTrackInfo(track.uri)
            // Une fois le spectre calculé, on connaît aussi la fréquence (kHz).
            SpectrumAnalyzer.request(this, track.uri) { uri, _ -> if (uri == infoForUri) loadTrackInfo(uri) }
        }
        if (eqPanel.visibility == View.VISIBLE && eqView.visibility != View.VISIBLE) setupEqualizer()
        renderToggles()
        adapter.notifyDataSetChanged()
    }

    private fun renderToggles() {
        val s = PlayerState.current
        val on = ColorStateList.valueOf(getColor(R.color.lcd_green))
        val off = ColorStateList.valueOf(getColor(R.color.lcd_dim))
        shuffleButton.imageTintList = if (s.shuffle) on else off
        repeatButton.imageTintList = if (s.repeat) on else off
        eqToggle.setTextColor(getColor(if (eqPanel.visibility == View.VISIBLE) R.color.lcd_green else R.color.lcd_dim))
        listToggle.setTextColor(getColor(if (listPanel.visibility == View.VISIBLE) R.color.lcd_green else R.color.lcd_dim))
        eqOn.setTextColor(getColor(if (PlayerService.instance?.eqEnabled == true) R.color.lcd_green else R.color.lcd_dim))
    }

    /** Débit et fréquence d'échantillonnage, lus dans le fichier. */
    private fun loadTrackInfo(uri: Uri) {
        background.execute {
            val text = try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, uri)
                    val kbps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        ?.toIntOrNull()?.div(1000)
                    val khz = SpectrumAnalyzer.cached(uri)?.sampleRate?.div(1000)
                    listOfNotNull(
                        kbps?.let { "$it KBPS" },
                        khz?.let { "$it KHZ" },
                        "MP3",
                    ).joinToString("   ")
                } finally {
                    retriever.release()
                }
            } catch (_: Exception) {
                "MP3"
            }
            runOnUiThread { if (infoForUri == uri) infoText.text = text }
        }
    }

    private fun setupEqualizer() {
        if (eqPanel.visibility != View.VISIBLE) return
        val service = PlayerService.instance
        val info = service?.eqInfo()
        if (service == null || info == null) {
            eqView.visibility = View.GONE
            eqMessage.visibility = View.VISIBLE
            eqMessage.text = if (service == null) "Lance un morceau pour régler l'égaliseur."
            else "Ce téléphone ne propose pas d'égaliseur."
            return
        }
        eqView.visibility = View.VISIBLE
        eqMessage.visibility = View.GONE
        eqView.setBands(
            info.centerHz,
            IntArray(info.centerHz.size) { service.eqLevel(it) },
            info.minLevel,
            info.maxLevel,
        )
    }

    /** Appelé à chaque image (~60/s) : temps, barre de position, spectre. */
    private fun onFrame() {
        val s = PlayerState.current
        val position = PlayerService.positionMs()
        val duration = s.durationMs

        if (!seeking) {
            timeText.text = if (showRemaining && duration > 0) "-" + formatTime(duration - position) else formatTime(position)
            seekBar.progress = if (duration > 0) (position * 1000 / duration).toInt() else 0
        }

        val data = s.track?.let { SpectrumAnalyzer.cached(it.uri) }
        if (s.playing && data != null) {
            data.frameAt(position, levels)
        } else {
            levels.fill(0f)
        }
        spectrum.setLevels(levels)

        // Clignotement du temps en pause, comme sur les vieux lecteurs.
        timeText.alpha = if (!s.playing && s.track != null && PlayerService.instance != null &&
            (System.currentTimeMillis() / 500) % 2 == 0L
        ) 0.25f else 1f
    }

    // ---------------------------------------------------------------- liste

    private inner class TrackAdapter : BaseAdapter() {
        override fun getCount() = library.size
        override fun getItem(position: Int) = library[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_track, parent, false)
            val track = library[position]
            val current = PlayerState.current.track?.uri == track.uri
            val title = view.findViewById<TextView>(R.id.trackTitle)
            val duration = view.findViewById<TextView>(R.id.trackDuration)
            title.text = "${position + 1}. ${track.title}"
            duration.text = if (track.durationMs > 0) formatTime(track.durationMs) else ""
            val color = getColor(if (current) android.R.color.white else R.color.lcd_green)
            title.setTextColor(color)
            duration.setTextColor(color)
            view.setBackgroundColor(if (current) getColor(R.color.select_bg) else 0)
            return view
        }
    }
}
