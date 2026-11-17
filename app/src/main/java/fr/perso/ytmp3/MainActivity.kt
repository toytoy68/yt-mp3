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
import android.text.Editable
import android.text.TextWatcher
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
        private const val STALE_ENGINE_DAYS = 30L
        private const val AUTO_FETCH_DELAY_MS = 700L

        private val URL_REGEX = Regex("""https?://\S+""")
    }

    // Opérations rapides de l'écran (init, titre, mise à jour). L'extraction, elle, tourne dans ExtractService.
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var busyCount = 0
    /** Lien dont on a déjà lancé l'analyse automatique. */
    private var lastAnalyzedUrl: String? = null
    /** Nom rempli automatiquement (pour savoir si l'utilisateur l'a modifié). */
    private var autoFilledName: String? = null
    private val autoFetch = Runnable { onUrlChanged() }
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
        fetchTitleButton.setOnClickListener { fetchTitle(force = true) }
        extractButton.setOnClickListener { startExtraction() }
        cancelButton.setOnClickListener { ExtractService.cancel() }
        openButton.setOnClickListener { openLastFile() }
        updateButton.setOnClickListener { updateEngine() }

        // Dès qu'un lien est collé ou tapé, on analyse la vidéo en arrière-plan.
        urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                mainHandler.removeCallbacks(autoFetch)
                mainHandler.postDelayed(autoFetch, AUTO_FETCH_DELAY_MS)
            }
        })

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
        val lastUpdate = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_LAST_UPDATE, 0L)
        val days = (System.currentTimeMillis() - lastUpdate) / (24L * 60 * 60 * 1000)
        val hint = if (lastUpdate == 0L || days >= STALE_ENGINE_DAYS) {
            "\n⚠️ Pas mis à jour depuis longtemps : pense à « Mettre à jour le moteur »."
        } else ""
        engineVersionText.text = "Version du moteur yt-dlp : ${version ?: "inconnue"}$hint"
    }

    // ---------------------------------------------------------------- entrées

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = URL_REGEX.find(text)?.value ?: return
        filenameInput.setText("")
        autoFilledName = null
        urlInput.setText(url) // déclenche l'analyse automatique
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

    /** Le lien a changé : on lance l'analyse (titre + infos) sans bloquer l'écran. */
    private fun onUrlChanged() {
        val url = URL_REGEX.find(urlInput.text.toString())?.value ?: return
        if (url == lastAnalyzedUrl) return
        lastAnalyzedUrl = url
        // L'ancien titre automatique ne correspond plus à ce lien.
        if (filenameInput.text.toString() == autoFilledName) filenameInput.setText("")
        fetchTitle(force = false)
    }

    /**
     * Récupère le titre. force = bouton « Utiliser le titre » : remplace le nom.
     * Sinon on ne remplit le nom que s'il est vide ou rempli automatiquement.
     */
    private fun fetchTitle(force: Boolean) {
        val url = (if (force) currentUrl() else URL_REGEX.find(urlInput.text.toString())?.value) ?: return
        lastAnalyzedUrl = url
        if (!JobState.current.running) statusText.text = "Analyse de la vidéo…"
        InfoCache.prefetch(this, url)
        worker.execute {
            val message = try {
                val title = InfoCache.get(this, url).title
                if (title.isBlank()) {
                    "Cette vidéo n'a pas de titre : choisis un nom."
                } else {
                    val clean = ExtractService.sanitize(title)
                    ui {
                        val current = filenameInput.text.toString()
                        val stillSameUrl = URL_REGEX.find(urlInput.text.toString())?.value == url
                        if (stillSameUrl && (force || current.isBlank() || current == autoFilledName)) {
                            autoFilledName = clean
                            filenameInput.setText(clean)
                        }
                    }
                    "Titre récupéré. Tu peux modifier le nom avant d'extraire."
                }
            } catch (e: Exception) {
                "Impossible d'analyser la vidéo : ${ExtractService.shortError(e)}"
            }
            ui { if (!JobState.current.running) statusText.text = message }
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
        InfoCache.prefetch(this, url) // au cas où le lien vient d'être collé
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
        try {
            startActivity(ExtractService.listenChooser(uri))
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
