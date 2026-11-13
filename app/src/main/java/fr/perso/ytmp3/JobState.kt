package fr.perso.ytmp3

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.CopyOnWriteArraySet

/** Initialisation unique de yt-dlp + ffmpeg, partagée par l'écran et le service. */
object Engine {
    @Volatile
    var ready = false
        private set

    @Synchronized
    fun ensureInit(context: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(context.applicationContext)
        FFmpeg.getInstance().init(context.applicationContext)
        ready = true
    }
}

/**
 * État de l'extraction en cours, partagé entre le service (qui travaille)
 * et l'écran (qui affiche). Survit quand l'écran est fermé puis rouvert.
 */
object JobState {

    enum class Phase { IDLE, PREPARING, DOWNLOADING, CONVERTING, SAVING, DONE, FAILED, CANCELED }

    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        /** 0..100, ou négatif si inconnu */
        val downloadPct: Float = 0f,
        /** 0..100, ou négatif si inconnu */
        val convertPct: Float = 0f,
        val message: String = "",
        val fileName: String? = null,
        val savedUri: Uri? = null,
    ) {
        val running: Boolean
            get() = phase == Phase.PREPARING || phase == Phase.DOWNLOADING ||
                phase == Phase.CONVERTING || phase == Phase.SAVING
    }

    @Volatile
    var current = Snapshot()
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(Snapshot) -> Unit>()

    fun update(transform: (Snapshot) -> Snapshot) {
        val snapshot = synchronized(this) {
            current = transform(current)
            current
        }
        mainHandler.post { listeners.forEach { it(snapshot) } }
    }

    fun addListener(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }
}
