package fr.perso.ytmp3

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.yausername.youtubedl_android.YoutubeDL
import fr.perso.ytmp3.JobState.Phase
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val PREFS = "ytmp3"
        private const val KEY_LAST_UPDATE = "last_engine_update"
        private const val KEY_ASKED_NOTIF = "asked_notifications"
        private const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000 // 1 jour

        private val URL_REGEX = Regex("""https?://\S+""")
    }

    // Opérations rapides de l'écran (init, titre, mise à jour). L'extraction, elle, tourne dans ExtractService.
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var busyCount = 0
    private val jobListener: (JobState.Snapshot) -> Unit = { render(it) }

    private lateinit var urlInput: EditText
    private lateinit var filenameInput: EditText
    private lateinit var qualityGroup: RadioGroup
    private lateinit var pasteButton: Button
    private lateinit var fetchTitleButton: Button
    private lateinit var extractButton: Button
    private lateinit var cancelButton: Button
    private lateinit var openButton: Button
    private lateinit var updateButton: Button
    private lateinit var statusText: TextView
    private lateinit var engineVersionText: TextView
    private lateinit var progressPanel: View
    private lateinit var downloadBar: ProgressBar
    private lateinit var downloadPct: TextView
    private lateinit var convertBar: ProgressBar
    private lateinit var convertPct: TextView

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
        statusText = findViewById(R.id.status)
        engineVersionText = findViewById(R.id.engineVersion)
        progressPanel = findViewById(R.id.progressPanel)
        downloadBar = findViewById(R.id.downloadBar)
        downloadPct = findViewById(R.id.downloadPct)
        convertBar = findViewById(R.id.convertBar)
        convertPct = findViewById(R.id.convertPct)

        pasteButton.setOnClickListener { pasteFromClipboard() }
        fetchTitleButton.setOnClickListener { fetchTitle() }
        extractButton.setOnClickListener { startExtraction() }
        cancelButton.setOnClickListener { ExtractService.cancel() }
        openButton.setOnClickListener { openLastFile() }
        updateButton.setOnClickListener { updateEngine() }

        initEngine()
        handleShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        JobState.addListener(jobListener)
        render(JobState.current) // retour sur l'écran : on réaffiche l'état actuel
    }

    override fun onStop() {
        JobState.removeListener(jobListener)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        // On n'arrête PAS l'extraction : le service continue sans l'écran.
        worker.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- moteur

    private fun initEngine() {
        if (JobState.current.running) {
            showEngineVersion()
            return
        }
        begin("Initialisation du moteur… (plus long au premier lancement)")
        worker.execute {
            val message = try {
                Engine.ensureInit(this)
                autoUpdateIfNeeded()
                "Prêt."
            } catch (e: Exception) {
                "Erreur d'initialisation : ${ExtractService.shortError(e)}"
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
                Engine.ensureInit(this)
                val status = YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
                when (status) {
                    YoutubeDL.UpdateStatus.DONE -> "Moteur mis à jour ✅"
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Le moteur est déjà à jour."
                    else -> "Mise à jour terminée."
                }
            } catch (e: Exception) {
                "Échec de la mise à jour : ${ExtractService.shortError(e)}"
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

    // ---------------------------------------------------------------- actions

    private fun fetchTitle() {
        val url = currentUrl() ?: return
        begin("Récupération du titre…")
        worker.execute {
            val message = try {
                Engine.ensureInit(this)
                val title = YoutubeDL.getInstance().getInfo(url).title
                if (title.isNullOrBlank()) {
                    "Cette vidéo n'a pas de titre : choisis un nom."
                } else {
                    ui { filenameInput.setText(ExtractService.sanitize(title)) }
                    "Titre récupéré. Tu peux modifier le nom avant d'extraire."
                }
            } catch (e: Exception) {
                "Impossible de récupérer le titre : ${ExtractService.shortError(e)}"
            }
            ui { end(message) }
        }
    }

    private fun startExtraction() {
        val url = currentUrl() ?: return
        if (JobState.current.running) return
        val kbps = when (qualityGroup.checkedRadioButtonId) {
            R.id.q128 -> 128
            R.id.q320 -> 320
            else -> 192
        }
        askNotificationPermissionOnce()
        ExtractService.start(this, url, ExtractService.sanitize(filenameInput.text.toString()), kbps)
    }

    /** Android 13+ : autorisation d'afficher la notification de progression. */
    private fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_NOTIF, false)) return
        prefs.edit().putBoolean(KEY_ASKED_NOTIF, true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun openLastFile() {
        val uri: Uri = JobState.current.savedUri ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "audio/mpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("Aucune appli pour lire les MP3.")
        }
    }

    // ---------------------------------------------------------------- affichage

    /** Met l'écran à jour selon l'état de l'extraction (appelé à chaque progrès). */
    private fun render(s: JobState.Snapshot) {
        if (s.phase == Phase.IDLE) {
            progressPanel.visibility = View.GONE
            cancelButton.visibility = View.GONE
            openButton.visibility = View.GONE
            refreshButtons()
            return
        }

        statusText.text = s.message
        progressPanel.visibility = View.VISIBLE
        cancelButton.visibility = if (s.running) View.VISIBLE else View.GONE
        openButton.visibility = if (s.phase == Phase.DONE && s.savedUri != null) View.VISIBLE else View.GONE

        // Le service a choisi le titre de la vidéo comme nom : on l'affiche.
        if (s.running && s.fileName != null && filenameInput.text.isBlank()) {
            filenameInput.setText(s.fileName)
        }

        val downloadValue = when (s.phase) {
            Phase.PREPARING -> 0f
            Phase.DOWNLOADING -> s.downloadPct
            Phase.CONVERTING, Phase.SAVING, Phase.DONE -> 100f
            else -> s.downloadPct // échec / annulé : on fige où on en était
        }
        val convertValue = when (s.phase) {
            Phase.PREPARING, Phase.DOWNLOADING -> 0f
            Phase.CONVERTING -> s.convertPct
            Phase.SAVING, Phase.DONE -> 100f
            else -> s.convertPct
        }
        showBar(downloadBar, downloadPct, downloadValue, animate = s.phase == Phase.PREPARING)
        showBar(convertBar, convertPct, convertValue, animate = false)

        refreshButtons()
    }

    /** pct négatif = durée inconnue : barre animée sans pourcentage. */
    private fun showBar(bar: ProgressBar, label: TextView, pct: Float, animate: Boolean) {
        if (pct < 0 || animate) {
            bar.isIndeterminate = true
            label.text = ""
        } else {
            bar.isIndeterminate = false
            bar.progress = (pct * 10).toInt().coerceIn(0, 1000)
            label.text = String.format("%.0f %%", pct)
        }
    }

    // ---------------------------------------------------------------- utilitaires

    /** Une opération de l'écran démarre : on bloque les boutons. */
    private fun begin(status: String) {
        busyCount++
        statusText.text = status
        refreshButtons()
    }

    /** Une opération de l'écran se termine. */
    private fun end(status: String) {
        busyCount = (busyCount - 1).coerceAtLeast(0)
        if (!JobState.current.running) statusText.text = status
        refreshButtons()
    }

    private fun refreshButtons() {
        val idle = busyCount == 0 && !JobState.current.running
        listOf(pasteButton, fetchTitleButton, extractButton, updateButton)
            .forEach { it.isEnabled = idle }
    }

    /** Exécute sur le fil principal (l'interface), si l'écran existe encore. */
    private fun ui(block: () -> Unit) {
        mainHandler.post { if (!isDestroyed) block() }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
