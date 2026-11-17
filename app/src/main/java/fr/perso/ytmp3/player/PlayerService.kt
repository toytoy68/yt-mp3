package fr.perso.ytmp3.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import fr.perso.ytmp3.R

/**
 * Service de lecture : la musique continue écran éteint ou dans une autre appli.
 *
 * Volontairement, aucun « récepteur de boutons média » n'est déclaré : une fois le
 * lecteur arrêté, Android ne peut PAS le relancer tout seul (contrairement à l'appli
 * Musique qui se relançait quand YouTube s'arrêtait).
 */
class PlayerService : Service() {

    companion object {
        private const val ACTION_START = "fr.perso.ytmp3.player.START"
        private const val ACTION_TOGGLE = "fr.perso.ytmp3.player.TOGGLE"
        private const val ACTION_NEXT = "fr.perso.ytmp3.player.NEXT"
        private const val ACTION_PREV = "fr.perso.ytmp3.player.PREV"
        private const val ACTION_STOP = "fr.perso.ytmp3.player.STOP"

        private const val CHANNEL = "player"
        private const val NOTIF_ID = 10
        private const val PREFS = "player"

        /** Le service en cours (même processus que l'écran) : accès direct, simple et rapide. */
        @Volatile
        var instance: PlayerService? = null
            private set

        /** Joue [queue] à partir de [index]. */
        fun play(context: Context, queue: List<Track>, index: Int) {
            PlayerState.update { it.copy(queue = queue, index = index) }
            val running = instance
            if (running != null) {
                running.playCurrent()
            } else {
                context.startForegroundService(
                    Intent(context, PlayerService::class.java).setAction(ACTION_START)
                )
            }
        }

        fun positionMs(): Long = instance?.safePosition() ?: 0L
    }

    private lateinit var player: MediaPlayer
    private lateinit var session: MediaSession
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private var equalizer: Equalizer? = null
    private var prepared = false
    private var resumeOnFocusGain = false
    private var volume = 1f
    private var errorStreak = 0

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Casque débranché / Bluetooth déconnecté : pause (sinon le son sort du haut-parleur).
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Lecteur", NotificationManager.IMPORTANCE_LOW)
        )

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        player = MediaPlayer().apply {
            setAudioAttributes(attributes)
            setOnPreparedListener { onPrepared() }
            setOnCompletionListener { onCompleted() }
            setOnErrorListener { _, _, _ ->
                onTrackError()
                true
            }
        }

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> onFocusChange(change) }
            .build()

        session = MediaSession(this, "YtMp3Player").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onStop() = stopPlayback()
                override fun onSeekTo(pos: Long) = seekTo(pos)
            })
            isActive = true
        }

        setupEqualizer()
        volume = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat("volume", 1f)

        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(noisyReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                playCurrent()
            }
            ACTION_TOGGLE -> togglePause()
            ACTION_NEXT -> next()
            ACTION_PREV -> previous()
            ACTION_STOP -> stopPlayback()
            else -> if (PlayerState.current.track == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        unregisterReceiver(noisyReceiver)
        audioManager.abandonAudioFocusRequest(focusRequest)
        equalizer?.release()
        session.release()
        player.release()
        PlayerState.update { it.copy(playing = false) }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- commandes

    fun playCurrent() {
        val track = PlayerState.current.track ?: return stopPlayback()
        prepared = false
        try {
            player.reset()
            player.setDataSource(this, track.uri)
            player.prepareAsync()
        } catch (_: Exception) {
            onTrackError()
            return
        }
        refresh()
    }

    fun togglePause() {
        if (player.isPlayingSafe()) pause() else resume()
    }

    fun resume() {
        if (!prepared) {
            playCurrent()
            return
        }
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        player.start()
        PlayerState.update { it.copy(playing = true) }
        refresh()
    }

    fun pause() {
        if (prepared && player.isPlayingSafe()) player.pause()
        PlayerState.update { it.copy(playing = false) }
        refresh()
    }

    fun next() {
        val s = PlayerState.current
        if (s.queue.isEmpty()) return stopPlayback()
        val nextIndex = when {
            s.shuffle && s.queue.size > 1 ->
                generateSequence { s.queue.indices.random() }.first { it != s.index }
            s.index + 1 < s.queue.size -> s.index + 1
            else -> 0
        }
        PlayerState.update { it.copy(index = nextIndex) }
        playCurrent()
    }

    fun previous() {
        // Comme les lecteurs classiques : après 3 s, « précédent » revient au début du morceau.
        if (safePosition() > 3000) {
            seekTo(0)
            return
        }
        val s = PlayerState.current
        if (s.queue.isEmpty()) return
        PlayerState.update { it.copy(index = if (s.index > 0) s.index - 1 else s.queue.size - 1) }
        playCurrent()
    }

    fun seekTo(positionMs: Long) {
        if (prepared) player.seekTo(positionMs.toInt())
        refresh()
    }

    fun stopPlayback() {
        if (prepared) player.stop()
        prepared = false
        PlayerState.update { it.copy(playing = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        player.setVolume(volume, volume)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat("volume", volume).apply()
    }

    fun getVolume(): Float = volume

    fun safePosition(): Long = try {
        if (prepared) player.currentPosition.toLong() else 0L
    } catch (_: IllegalStateException) {
        0L
    }

    // ---------------------------------------------------------------- égaliseur

    /** Bandes de l'égaliseur du téléphone : fréquences centrales (Hz) et plage (millibels). */
    data class EqInfo(val centerHz: IntArray, val minLevel: Int, val maxLevel: Int)

    fun eqInfo(): EqInfo? {
        val eq = equalizer ?: return null
        return try {
            val bands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            EqInfo(IntArray(bands) { eq.getCenterFreq(it.toShort()) / 1000 }, range[0].toInt(), range[1].toInt())
        } catch (_: Exception) {
            null
        }
    }

    fun eqLevel(band: Int): Int = try {
        equalizer?.getBandLevel(band.toShort())?.toInt() ?: 0
    } catch (_: Exception) {
        0
    }

    fun setEqLevel(band: Int, level: Int) {
        try {
            equalizer?.setBandLevel(band.toShort(), level.toShort())
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("eq_$band", level).apply()
        } catch (_: Exception) {
        }
    }

    var eqEnabled: Boolean
        get() = equalizer?.enabled == true
        set(value) {
            try {
                equalizer?.enabled = value
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("eq_on", value).apply()
            } catch (_: Exception) {
            }
        }

    private fun setupEqualizer() {
        equalizer = try {
            Equalizer(0, player.audioSessionId).apply {
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                for (b in 0 until numberOfBands) {
                    setBandLevel(b.toShort(), prefs.getInt("eq_$b", 0).toShort())
                }
                enabled = prefs.getBoolean("eq_on", false)
            }
        } catch (_: Exception) {
            null // certains téléphones n'ont pas d'égaliseur logiciel
        }
    }

    // ---------------------------------------------------------------- événements

    /** Fichier illisible : on passe au suivant, sauf si aucun ne marche. */
    private fun onTrackError() {
        prepared = false
        errorStreak++
        if (errorStreak >= PlayerState.current.queue.size) {
            errorStreak = 0
            stopPlayback()
        } else {
            next()
        }
    }

    private fun onPrepared() {
        prepared = true
        errorStreak = 0
        player.setVolume(volume, volume)
        PlayerState.update { it.copy(durationMs = player.duration.toLong()) }
        resume()
    }

    private fun onCompleted() {
        val s = PlayerState.current
        when {
            s.repeat -> {
                player.seekTo(0)
                player.start()
            }
            s.shuffle || s.index + 1 < s.queue.size -> next()
            else -> {
                // Fin de la liste : on s'arrête sur le premier morceau, en pause.
                PlayerState.update { it.copy(index = 0, playing = false) }
                playCurrentPaused()
            }
        }
    }

    private fun playCurrentPaused() {
        val track = PlayerState.current.track ?: return
        prepared = false
        player.reset()
        player.setDataSource(this, track.uri)
        player.setOnPreparedListener {
            prepared = true
            player.setOnPreparedListener { onPrepared() }
            refresh()
        }
        player.prepareAsync()
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Appel, alarme… : pause, puis reprise automatique ensuite.
                resumeOnFocusGain = player.isPlayingSafe()
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player.setVolume(volume * 0.3f, volume * 0.3f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                player.setVolume(volume, volume)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    resume()
                }
            }
        }
    }

    private fun MediaPlayer.isPlayingSafe(): Boolean = try {
        prepared && isPlaying
    } catch (_: IllegalStateException) {
        false
    }

    // ---------------------------------------------------------------- notification

    private fun refresh() {
        val s = PlayerState.current
        val track = s.track
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track?.title ?: "")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "YT MP3")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, s.durationMs)
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SEEK_TO
                )
                .setState(
                    if (s.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    safePosition(),
                    if (s.playing) 1f else 0f
                )
                .build()
        )
        if (instance != null) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val s = PlayerState.current
        fun action(icon: Int, title: String, action: String, code: Int) = Notification.Action.Builder(
            Icon.createWithResource(this, icon), title,
            PendingIntent.getService(
                this, code, Intent(this, PlayerService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ).build()

        val openPlayer = PendingIntent.getActivity(
            this, 20,
            Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.track?.title ?: "YT MP3")
            .setContentText(if (s.playing) "Lecture" else "En pause")
            .setContentIntent(openPlayer)
            .setOngoing(s.playing)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_prev, "Précédent", ACTION_PREV, 21))
            .addAction(
                if (s.playing) action(R.drawable.ic_pause, "Pause", ACTION_TOGGLE, 22)
                else action(R.drawable.ic_play, "Lecture", ACTION_TOGGLE, 22)
            )
            .addAction(action(R.drawable.ic_next, "Suivant", ACTION_NEXT, 23))
            .addAction(action(R.drawable.ic_stop, "Arrêter", ACTION_STOP, 24))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }
}
