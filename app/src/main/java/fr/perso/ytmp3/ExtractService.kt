package fr.perso.ytmp3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import fr.perso.ytmp3.JobState.Phase
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Service de premier plan : l'extraction continue quand on change d'appli
 * ou qu'on éteint l'écran. Une notification affiche la progression.
 */
class ExtractService : Service() {

    companion object {
        private const val ACTION_START = "fr.perso.ytmp3.action.START"
        private const val ACTION_CANCEL = "fr.perso.ytmp3.action.CANCEL"
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_KBPS = "kbps"

        private const val CHANNEL_PROGRESS = "progress"
        private const val CHANNEL_DONE = "done"
        private const val NOTIF_PROGRESS = 1
        private const val NOTIF_DONE = 2

        const val PROCESS_ID = "ytmp3-download"
        const val SAVE_FOLDER = "YtMp3"

        private val FORBIDDEN_CHARS = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
        private val FFMPEG_TIME = Regex("""out_time_(?:us|ms)=(\d+)""")

        fun start(context: Context, url: String, name: String, kbps: Int) {
            val intent = Intent(context, ExtractService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_KBPS, kbps)
            context.startForegroundService(intent)
        }

        fun cancel() {
            YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        }

        fun sanitize(name: String): String =
            name.replace(FORBIDDEN_CHARS, "_")
                .trim()
                .removeSuffix(".mp3")
                .removeSuffix(".MP3")
                .trim()
                .trimEnd('.')
                .take(150)

        /** Garde la ligne utile d'un message d'erreur yt-dlp (souvent très long). */
        fun shortError(e: Exception): String {
            val lines = (e.message ?: e.javaClass.simpleName)
                .lines().map { it.trim() }.filter { it.isNotEmpty() }
            val line = lines.lastOrNull { it.startsWith("ERROR") } ?: lines.lastOrNull() ?: "erreur inconnue"
            return line.removePrefix("ERROR:").trim().take(300)
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifUpdate = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Extraction en cours", NotificationManager.IMPORTANCE_LOW)
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Extraction terminée", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancel()

            ACTION_START -> {
                // Obligatoire dans les 5 s après startForegroundService().
                startForeground(
                    NOTIF_PROGRESS,
                    progressNotification("Préparation…", 0, true),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                if (JobState.current.running) return START_NOT_STICKY

                val url = intent.getStringExtra(EXTRA_URL)
                if (url == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val name = intent.getStringExtra(EXTRA_NAME) ?: ""
                val kbps = intent.getIntExtra(EXTRA_KBPS, 192)

                JobState.update { JobState.Snapshot(phase = Phase.PREPARING, message = "Préparation…") }
                acquireWakeLock()
                worker.execute { runJob(url, name, kbps) }
            }

            else -> if (!JobState.current.running) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (JobState.current.running) cancel()
        worker.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- travail

    private fun runJob(url: String, requestedName: String, kbps: Int) {
        val tmpDir = File(cacheDir, "download")
        val progressFile = File(tmpDir, "ffmpeg-progress.txt")
        val poller = Executors.newSingleThreadScheduledExecutor()

        try {
            Engine.ensureInit(this)

            // Infos de la vidéo : titre (pour le nom) et durée (pour la barre de conversion).
            setState(Phase.PREPARING, "Récupération des infos de la vidéo…")
            val info = YoutubeDL.getInstance().getInfo(url)
            var name = sanitize(requestedName)
            if (name.isEmpty()) name = sanitize(info.title ?: "")
            if (name.isEmpty()) name = "audio_${System.currentTimeMillis()}"
            val durationSec = info.duration
            val finalName = name
            JobState.update { it.copy(fileName = finalName) }

            tmpDir.deleteRecursively()
            tmpDir.mkdirs()

            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "${kbps}K")
                addOption("--embed-metadata")
                // ffmpeg écrit son avancement dans ce fichier, qu'on lit en parallèle.
                addOption("--postprocessor-args", "ExtractAudio:-progress ${progressFile.absolutePath}")
                addOption("-o", File(tmpDir, "audio.%(ext)s").absolutePath)
            }

            poller.scheduleWithFixedDelay(
                { readConversionProgress(progressFile, durationSec) },
                500, 500, TimeUnit.MILLISECONDS
            )

            setState(Phase.DOWNLOADING, "Téléchargement…")
            YoutubeDL.getInstance().execute(request, PROCESS_ID) { progress, _, line ->
                onYtdlpOutput(progress, line, durationSec)
            }
            poller.shutdownNow()

            val mp3 = tmpDir.listFiles()?.firstOrNull { it.extension.equals("mp3", true) }
                ?: throw IOException("fichier MP3 introuvable après la conversion")

            JobState.update { it.copy(phase = Phase.SAVING, downloadPct = 100f, convertPct = 100f, message = "Enregistrement…") }
            notifyProgress(force = true)
            val (uri, savedName) = saveToMusic(mp3, name)

            JobState.update {
                it.copy(
                    phase = Phase.DONE, downloadPct = 100f, convertPct = 100f,
                    message = "✅ Enregistré : Musique/$SAVE_FOLDER/$savedName",
                    fileName = savedName, savedUri = uri
                )
            }
            notifyFinished("MP3 prêt ✅", savedName, uri)
        } catch (e: YoutubeDL.CanceledException) {
            JobState.update { it.copy(phase = Phase.CANCELED, message = "Extraction annulée.") }
        } catch (e: Exception) {
            val error = shortError(e)
            JobState.update {
                it.copy(
                    phase = Phase.FAILED,
                    message = "❌ Échec : $error\n\nSi ça persiste, essaie « Mettre à jour le moteur »."
                )
            }
            notifyFinished("Échec de l'extraction", error, null)
        } finally {
            poller.shutdownNow()
            tmpDir.deleteRecursively()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun onYtdlpOutput(progress: Float, line: String, durationSec: Int) {
        val phase = JobState.current.phase
        when {
            line.contains("[ExtractAudio]") && phase == Phase.DOWNLOADING -> {
                JobState.update {
                    it.copy(
                        phase = Phase.CONVERTING, downloadPct = 100f,
                        convertPct = if (durationSec > 0) 0f else -1f,
                        message = "Conversion en MP3…"
                    )
                }
                notifyProgress(force = true)
            }
            phase == Phase.DOWNLOADING && progress in 0f..100f -> {
                JobState.update { it.copy(downloadPct = progress, message = "Téléchargement…") }
                notifyProgress()
            }
        }
    }

    /** Lit le fichier -progress de ffmpeg (lignes clé=valeur) pour calculer le %. */
    private fun readConversionProgress(file: File, durationSec: Int) {
        if (durationSec <= 0 || !file.exists()) return
        val text = try {
            file.readText()
        } catch (_: IOException) {
            return
        }
        val micros = FFMPEG_TIME.findAll(text).lastOrNull()?.groupValues?.get(1)?.toLongOrNull() ?: return
        val pct = if (text.contains("progress=end")) 100f
        else (micros / 1_000_000.0 / durationSec * 100).toFloat().coerceIn(0f, 99.9f)

        JobState.update {
            if (it.phase == Phase.DOWNLOADING || it.phase == Phase.CONVERTING) {
                it.copy(phase = Phase.CONVERTING, downloadPct = 100f, convertPct = pct, message = "Conversion en MP3…")
            } else it
        }
        notifyProgress()
    }

    private fun setState(phase: Phase, message: String) {
        JobState.update { it.copy(phase = phase, message = message) }
        notifyProgress(force = true)
    }

    /** Copie le MP3 dans Musique/YtMp3 (visible par les lecteurs de musique). */
    private fun saveToMusic(source: File, baseName: String): Pair<Uri, String> {
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.mp3")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$SAVE_FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("impossible de créer le fichier dans Musique")

        try {
            val out = resolver.openOutputStream(uri)
                ?: throw IOException("impossible d'écrire le fichier")
            out.use { output -> source.inputStream().use { it.copyTo(output) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        // Android ajoute « (1) » si un fichier du même nom existe déjà.
        val finalName = resolver.query(
            uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: "$baseName.mp3"
        return uri to finalName
    }

    // ---------------------------------------------------------------- notifications

    private fun notifyProgress(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifUpdate < 700) return
        lastNotifUpdate = now

        val s = JobState.current
        val (text, pct) = when (s.phase) {
            Phase.DOWNLOADING -> "Téléchargement" to s.downloadPct
            Phase.CONVERTING -> "Conversion en MP3" to s.convertPct
            Phase.SAVING -> "Enregistrement" to -1f
            else -> s.message to -1f
        }
        val label = if (pct >= 0) "$text — ${pct.toInt()} %" else text
        notificationManager.notify(
            NOTIF_PROGRESS,
            progressNotification(label, (pct * 10).toInt(), pct < 0)
        )
    }

    private fun progressNotification(text: String, progress: Int, indeterminate: Boolean): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ExtractService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notification), "Annuler", cancelIntent
        ).build()

        return Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(JobState.current.fileName ?: "YT MP3")
            .setContentText(text)
            .setProgress(1000, progress.coerceIn(0, 1000), indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(cancelAction)
            .build()
    }

    private fun notifyFinished(title: String, text: String, fileUri: Uri?) {
        // Toucher la notification : écoute le MP3 si réussi, sinon ouvre l'appli.
        val tapIntent = if (fileUri != null) {
            PendingIntent.getActivity(
                this, 2,
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(fileUri, "audio/mpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else openAppIntent()

        val notification = Notification.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIF_DONE, notification)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    // ---------------------------------------------------------------- veille

    /** Empêche le téléphone de mettre le processeur en veille pendant le travail. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytmp3:extract")
            .apply { acquire(2 * 60 * 60 * 1000L) } // 2 h maximum, par sécurité
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
