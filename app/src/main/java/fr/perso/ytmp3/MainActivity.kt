package fr.perso.ytmp3

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val PROCESS_ID = "ytmp3-download"
        private const val PREFS = "ytmp3"
        private const val KEY_LAST_UPDATE = "last_engine_update"
        private const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000 // 1 jour
        private const val SAVE_FOLDER = "YtMp3"

        private val URL_REGEX = Regex("""https?://\S+""")
        private val FORBIDDEN_CHARS = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

        @Volatile
        private var engineReady = false
    }

    // Toutes les opérations longues passent par ce fil unique, donc dans l'ordre.
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var busyCount = 0
    private var downloading = false
    private var lastSavedUri: Uri? = null

    private lateinit var urlInput: EditText
    private lateinit var filenameInput: EditText
    private lateinit var qualityGroup: RadioGroup
    private lateinit var pasteButton: Button
    private lateinit var fetchTitleButton: Button
    private lateinit var extractButton: Button
    private lateinit var cancelButton: Button
    private lateinit var openButton: Button
    private lateinit var updateButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var engineVersionText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.url)
        filenameInput = findViewById(R.id.filename)
        qualityGroup = findViewById(R.id.quality)
        pasteButton = findViewById(R.id.paste)
        fetchTitleButton = findViewById(R.id.fetchTitle)
        extractButton = findViewById(R.id.extract)
        cancelButton = findViewById(R.id.cancel)
        openButton = findViewById(R.id.open)
        updateButton = findViewById(R.id.update)
        progressBar = findViewById(R.id.progress)
        statusText = findViewById(R.id.status)
        engineVersionText = findViewById(R.id.engineVersion)

        pasteButton.setOnClickListener { pasteFromClipboard() }
        fetchTitleButton.setOnClickListener { fetchTitle() }
        extractButton.setOnClickListener { startExtraction() }
        cancelButton.setOnClickListener {
            YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        }
        openButton.setOnClickListener { openLastFile() }
        updateButton.setOnClickListener { updateEngine() }

        initEngine()
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        if (downloading) YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        worker.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- moteur

    private fun initEngine() {
        begin("Initialisation du moteur… (plus long au premier lancement)")
        worker.execute {
            val message = try {
                if (!engineReady) {
                    YoutubeDL.getInstance().init(applicationContext)
                    FFmpeg.getInstance().init(applicationContext)
                    engineReady = true
                }
                autoUpdateIfNeeded()
                "Prêt."
            } catch (e: Exception) {
                "Erreur d'initialisation : ${shortError(e)}"
            }
            ui {
                showEngineVersion()
                end(message)
            }
        }
    }

    /** YouTube change souvent : on met yt-dlp à jour au plus une fois par jour. */
    private fun autoUpdateIfNeeded() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_UPDATE, 0L) < UPDATE_INTERVAL_MS) return
        ui { statusText.text = "Recherche de mise à jour du moteur…" }
        try {
            YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
            prefs.edit().putLong(KEY_LAST_UPDATE, now).apply()
        } catch (_: Exception) {
            // Pas grave (pas de réseau…) : on réessaiera au prochain lancement.
        }
    }

    private fun updateEngine() {
        begin("Mise à jour du moteur…")
        worker.execute {
            val message = try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
                when (status) {
                    YoutubeDL.UpdateStatus.DONE -> "Moteur mis à jour ✅"
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Le moteur est déjà à jour."
                    else -> "Mise à jour terminée."
                }
            } catch (e: Exception) {
                "Échec de la mise à jour : ${shortError(e)}"
            }
            ui {
                showEngineVersion()
                end(message)
            }
        }
    }

    private fun showEngineVersion() {
        val version = try {
            YoutubeDL.getInstance().versionName(applicationContext)
        } catch (_: Exception) {
            null
        }
        engineVersionText.text = "Version du moteur yt-dlp : ${version ?: "inconnue"}"
    }

    // ---------------------------------------------------------------- entrées

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = URL_REGEX.find(text)?.value ?: return
        urlInput.setText(url)
        filenameInput.setText("")
        fetchTitle()
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
        if (text.isNullOrBlank()) {
            toast("Le presse-papiers est vide.")
            return
        }
        urlInput.setText(URL_REGEX.find(text)?.value ?: text.trim())
    }

    /** Renvoie le lien saisi, ou null (avec un message) s'il n'est pas valide. */
    private fun currentUrl(): String? {
        val url = URL_REGEX.find(urlInput.text.toString())?.value
        if (url == null) toast("Entre d'abord un lien valide (https://…).")
        return url
    }

    private fun sanitize(name: String): String =
        name.replace(FORBIDDEN_CHARS, "_")
            .trim()
            .removeSuffix(".mp3")
            .removeSuffix(".MP3")
            .trim()
            .trimEnd('.')
            .take(150)

    // ---------------------------------------------------------------- actions

    private fun fetchTitle() {
        val url = currentUrl() ?: return
        begin("Récupération du titre…")
        worker.execute {
            val message = try {
                val title = YoutubeDL.getInstance().getInfo(url).title
                if (title.isNullOrBlank()) {
                    "Cette vidéo n'a pas de titre : choisis un nom."
                } else {
                    ui { filenameInput.setText(sanitize(title)) }
                    "Titre récupéré. Tu peux modifier le nom avant d'extraire."
                }
            } catch (e: Exception) {
                "Impossible de récupérer le titre : ${shortError(e)}"
            }
            ui { end(message) }
        }
    }

    private fun startExtraction() {
        val url = currentUrl() ?: return
        var name = sanitize(filenameInput.text.toString())
        val kbps = when (qualityGroup.checkedRadioButtonId) {
            R.id.q128 -> 128
            R.id.q320 -> 320
            else -> 192
        }

        lastSavedUri = null
        openButton.visibility = View.GONE
        cancelButton.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        progressBar.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        downloading = true
        begin("Préparation…")

        worker.execute {
            val tmpDir = File(cacheDir, "download")
            var savedUri: Uri? = null
            val message = try {
                // Nom vide : on prend le titre de la vidéo.
                if (name.isEmpty()) {
                    ui { statusText.text = "Récupération du titre…" }
                    name = sanitize(YoutubeDL.getInstance().getInfo(url).title ?: "")
                    if (name.isEmpty()) name = "audio_${System.currentTimeMillis()}"
                    val shown = name
                    ui { filenameInput.setText(shown) }
                }

                tmpDir.deleteRecursively()
                tmpDir.mkdirs()

                val request = YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "${kbps}K")
                    addOption("--embed-metadata")
                    addOption("-o", File(tmpDir, "audio.%(ext)s").absolutePath)
                }
                YoutubeDL.getInstance().execute(request, PROCESS_ID) { progress, _, line ->
                    ui { onProgress(progress, line) }
                }

                val mp3 = tmpDir.listFiles()?.firstOrNull { it.extension.equals("mp3", true) }
                    ?: throw IOException("fichier MP3 introuvable après la conversion")

                ui {
                    progressBar.isIndeterminate = true
                    statusText.text = "Enregistrement…"
                }
                val (uri, finalName) = saveToMusic(mp3, name)
                savedUri = uri
                "✅ Enregistré : Musique/$SAVE_FOLDER/$finalName"
            } catch (e: YoutubeDL.CanceledException) {
                "Extraction annulée."
            } catch (e: Exception) {
                "❌ Échec : ${shortError(e)}\n\nSi ça persiste, essaie « Mettre à jour le moteur »."
            } finally {
                tmpDir.deleteRecursively()
            }

            ui {
                downloading = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                cancelButton.visibility = View.GONE
                progressBar.visibility = View.GONE
                lastSavedUri = savedUri
                if (savedUri != null) openButton.visibility = View.VISIBLE
                end(message)
            }
        }
    }

    private fun onProgress(progress: Float, line: String) {
        if (!downloading) return
        when {
            line.contains("[ExtractAudio]") -> {
                progressBar.isIndeterminate = true
                statusText.text = "Conversion en MP3…"
            }
            progress in 0f..100f -> {
                progressBar.isIndeterminate = false
                progressBar.progress = (progress * 10).toInt()
                statusText.text = String.format("Téléchargement… %.1f %%", progress)
            }
        }
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

    private fun openLastFile() {
        val uri = lastSavedUri ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "audio/mpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("Aucune appli pour lire les MP3.")
        }
    }

    // ---------------------------------------------------------------- utilitaires

    /** Une opération démarre : on bloque les boutons. */
    private fun begin(status: String) {
        busyCount++
        statusText.text = status
        refreshButtons()
    }

    /** Une opération se termine. */
    private fun end(status: String) {
        busyCount = (busyCount - 1).coerceAtLeast(0)
        statusText.text = status
        refreshButtons()
    }

    private fun refreshButtons() {
        val idle = busyCount == 0
        listOf(pasteButton, fetchTitleButton, extractButton, updateButton)
            .forEach { it.isEnabled = idle }
    }

    /** Exécute sur le fil principal (l'interface), si l'écran existe encore. */
    private fun ui(block: () -> Unit) {
        mainHandler.post { if (!isDestroyed) block() }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /** Garde la ligne utile d'un message d'erreur yt-dlp (souvent très long). */
    private fun shortError(e: Exception): String {
        val lines = (e.message ?: e.javaClass.simpleName)
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        val line = lines.lastOrNull { it.startsWith("ERROR") } ?: lines.lastOrNull() ?: "erreur inconnue"
        return line.removePrefix("ERROR:").trim().take(300)
    }
}
