package fr.perso.ytmp3.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import fr.perso.ytmp3.ExtractService
import java.util.concurrent.CopyOnWriteArraySet

data class Track(
    val uri: Uri,
    val title: String,
    val durationMs: Long,
)

/** État du lecteur, partagé entre le service (qui joue) et l'écran (qui affiche). */
object PlayerState {

    data class Snapshot(
        val queue: List<Track> = emptyList(),
        val index: Int = -1,
        val playing: Boolean = false,
        val durationMs: Long = 0,
        val shuffle: Boolean = false,
        val repeat: Boolean = false,
    ) {
        val track: Track? get() = queue.getOrNull(index)
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

/** Les MP3 extraits par l'appli (dossier Musique/YtMp3), du plus récent au plus ancien. */
object Library {

    fun load(context: Context): List<Track> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
        )
        val tracks = mutableListOf<Track>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("%/${ExtractService.SAVE_FOLDER}/%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                tracks += Track(
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)),
                    title = name.substringBeforeLast('.'),
                    durationMs = cursor.getLong(durationCol),
                )
            }
        }
        return tracks
    }
}
