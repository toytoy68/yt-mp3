package fr.perso.ytmp3

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Analyse une vidéo UNE seule fois (c'est l'étape lente de yt-dlp) et garde le résultat :
 * le titre s'affiche dès que le lien est collé, et l'extraction réutilise la même analyse
 * via --load-info-json au lieu de tout recommencer.
 */
object InfoCache {

    data class VideoMeta(
        val title: String,
        val durationSec: Int,
        val jsonFile: File,
        val fetchedAt: Long,
    )

    /** Les liens de téléchargement fournis par YouTube expirent : on réanalyse après 1 h. */
    private const val MAX_AGE_MS = 60L * 60 * 1000

    private val executor = Executors.newCachedThreadPool()
    private val futures = ConcurrentHashMap<String, Future<VideoMeta>>()

    /** Lance l'analyse en arrière-plan si elle n'est pas déjà faite ou en cours. */
    @Synchronized
    fun prefetch(context: Context, url: String): Future<VideoMeta> {
        val existing = futures[url]
        if (existing != null && isUsable(existing)) return existing
        val appContext = context.applicationContext
        val future = executor.submit(Callable { fetch(appContext, url) })
        futures[url] = future
        return future
    }

    /** Renvoie l'analyse (attend la fin si elle est en cours). */
    fun get(context: Context, url: String): VideoMeta {
        try {
            return prefetch(context, url).get()
        } catch (e: ExecutionException) {
            throw (e.cause as? Exception) ?: e
        }
    }

    /** Oublie une analyse (par ex. si ses liens de téléchargement ont expiré). */
    fun invalidate(url: String) {
        futures.remove(url)
    }

    private fun isUsable(future: Future<VideoMeta>): Boolean {
        if (!future.isDone) return true // en cours : on attend celle-là
        return try {
            val meta = future.get()
            meta.jsonFile.exists() && System.currentTimeMillis() - meta.fetchedAt < MAX_AGE_MS
        } catch (_: Exception) {
            false // l'analyse précédente a échoué : on réessaie
        }
    }

    private fun fetch(context: Context, url: String): VideoMeta {
        Engine.ensureInit(context)

        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--dump-single-json")
        }
        val json = YoutubeDL.getInstance().execute(request).out
            ?.trim()
            ?.takeIf { it.startsWith("{") }
            ?: throw IllegalStateException("analyse de la vidéo impossible")

        val dir = File(context.cacheDir, "info").apply { mkdirs() }
        cleanOldFiles(dir)
        val file = File(dir, sha1(url) + ".json")
        file.writeText(json)

        val obj = JSONObject(json)
        return VideoMeta(
            title = obj.optString("title", ""),
            durationSec = obj.optDouble("duration", 0.0).toInt(),
            jsonFile = file,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private fun cleanOldFiles(dir: File) {
        val limit = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
