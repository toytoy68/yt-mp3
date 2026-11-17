package fr.perso.ytmp3.player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pré-calcule le spectre d'un morceau en décodant le fichier lui-même
 * (pas besoin de l'autorisation micro qu'exigerait le visualiseur d'Android).
 * Résultat : pour chaque tranche de [FRAME_MS] ms, [BANDS] niveaux de 0 à 255.
 */
object SpectrumAnalyzer {

    const val BANDS = 20
    const val FRAME_MS = 40

    private const val FFT_SIZE = 1024
    private const val MIN_FREQ = 50.0
    private const val MAX_FREQ = 16000.0
    private const val FLOOR_DB = -70.0
    private const val CACHE_SIZE = 6

    class Data(val frames: Int, val levels: ByteArray, val sampleRate: Int) {
        /** Niveaux (0..1) de la tranche correspondant à [positionMs]. */
        fun frameAt(positionMs: Long, out: FloatArray) {
            val frame = (positionMs / FRAME_MS).toInt()
            if (frame < 0 || frame >= frames) {
                out.fill(0f)
                return
            }
            val base = frame * BANDS
            for (b in 0 until BANDS) out[b] = (levels[base + b].toInt() and 0xFF) / 255f
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = ConcurrentHashMap<Uri, Data>()
    private val order = ArrayDeque<Uri>()
    private val pending = ConcurrentHashMap.newKeySet<Uri>()

    fun cached(uri: Uri): Data? = cache[uri]

    /** Lance l'analyse si besoin ; [onReady] est appelé sur le fil principal. */
    fun request(context: Context, uri: Uri, onReady: (Uri, Data) -> Unit) {
        cache[uri]?.let { data -> mainHandler.post { onReady(uri, data) }; return }
        if (!pending.add(uri)) return
        val appContext = context.applicationContext
        executor.execute {
            try {
                val data = analyze(appContext, uri)
                synchronized(order) {
                    cache[uri] = data
                    order.addLast(uri)
                    while (order.size > CACHE_SIZE) cache.remove(order.removeFirst())
                }
                mainHandler.post { onReady(uri, data) }
            } catch (_: Exception) {
                // Pas de spectre pour ce fichier : le lecteur fonctionne quand même.
            } finally {
                pending.remove(uri)
            }
        }
    }

    // ---------------------------------------------------------------- décodage

    private fun analyze(context: Context, uri: Uri): Data {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException("pas de piste audio")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val estimatedFrames = (durationUs / 1000 / FRAME_MS + 16).toInt().coerceAtLeast(64)
            var out = ByteArray(estimatedFrames * BANDS)
            var frames = 0

            val ring = FloatArray(FFT_SIZE)    // tampon circulaire des derniers échantillons (mono)
            val window = FloatArray(FFT_SIZE)  // même contenu, remis dans l'ordre pour la FFT
            var ringPos = 0
            var filled = 0
            var chunk = FloatArray(0)
            var sinceLastFrame = 0
            var hop = sampleRate * FRAME_MS / 1000
            var bandEdges = bandEdges(sampleRate)
            val analyzer = Fft(FFT_SIZE)
            val bandOut = FloatArray(BANDS)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        hop = sampleRate * FRAME_MS / 1000
                        bandEdges = bandEdges(sampleRate)
                    }
                    outIndex >= 0 -> {
                        val buffer = decoder.getOutputBuffer(outIndex)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.order(ByteOrder.nativeOrder())

                        // Copie du bloc PCM dans un tableau (float -1..1)
                        val samples: Int
                        if (floatPcm) {
                            val fb = buffer.asFloatBuffer()
                            samples = fb.remaining()
                            if (chunk.size < samples) chunk = FloatArray(samples)
                            fb.get(chunk, 0, samples)
                        } else {
                            val sb = buffer.asShortBuffer()
                            samples = sb.remaining()
                            if (chunk.size < samples) chunk = FloatArray(samples)
                            for (k in 0 until samples) chunk[k] = sb.get(k) / 32768f
                        }

                        var i = 0
                        while (i + channels <= samples) {
                            var mono = 0f
                            for (c in 0 until channels) mono += chunk[i + c]
                            mono /= channels
                            i += channels

                            ring[ringPos] = mono
                            ringPos = (ringPos + 1) % FFT_SIZE
                            if (filled < FFT_SIZE) filled++

                            if (++sinceLastFrame >= hop) {
                                sinceLastFrame = 0
                                // Remet le tampon dans l'ordre chronologique.
                                val oldest = if (filled < FFT_SIZE) 0 else ringPos
                                for (k in 0 until filled) window[k] = ring[(oldest + k) % FFT_SIZE]
                                analyzer.bands(window, filled, bandEdges, bandOut)
                                if ((frames + 1) * BANDS > out.size) out = out.copyOf(out.size * 2)
                                for (b in 0 until BANDS) {
                                    out[frames * BANDS + b] = (bandOut[b] * 255).toInt().coerceIn(0, 255).toByte()
                                }
                                frames++
                            }
                        }

                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            return Data(frames, out.copyOf(frames * BANDS), sampleRate)
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor.release()
        }
    }

    /** Limites des bandes en indices FFT, réparties de façon logarithmique (comme l'oreille). */
    private fun bandEdges(sampleRate: Int): IntArray {
        val binHz = sampleRate.toDouble() / FFT_SIZE
        val maxFreq = MAX_FREQ.coerceAtMost(sampleRate / 2.0)
        return IntArray(BANDS + 1) { i ->
            val freq = MIN_FREQ * (maxFreq / MIN_FREQ).pow(i.toDouble() / BANDS)
            (freq / binHz).toInt().coerceIn(1, FFT_SIZE / 2)
        }
    }

    /** Petite FFT radix-2 (fenêtre de Hann). */
    private class Fft(private val n: Int) {
        private val re = FloatArray(n)
        private val im = FloatArray(n)
        private val hann = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }
        private val cosTable = FloatArray(n / 2) { cos(-2 * PI * it / n).toFloat() }
        private val sinTable = FloatArray(n / 2) { sin(-2 * PI * it / n).toFloat() }

        fun bands(samples: FloatArray, count: Int, edges: IntArray, out: FloatArray) {
            for (i in 0 until n) {
                re[i] = if (i < count) samples[i] * hann[i] else 0f
                im[i] = 0f
            }
            transform()
            for (b in 0 until BANDS) {
                val from = edges[b]
                val to = maxOf(edges[b + 1], from + 1)
                var peak = 0f
                for (k in from until to.coerceAtMost(n / 2)) {
                    val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                    if (mag > peak) peak = mag
                }
                // Amplitude normalisée -> dB -> 0..1
                val db = 20 * log10((peak / (n / 4f)).toDouble().coerceAtLeast(1e-9))
                out[b] = ((db - FLOOR_DB) / -FLOOR_DB).toFloat().coerceIn(0f, 1f)
            }
        }

        private fun transform() {
            // Permutation bit-reverse
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            // Papillons
            var len = 2
            while (len <= n) {
                val half = len / 2
                val step = n / len
                var start = 0
                while (start < n) {
                    for (k in 0 until half) {
                        val wr = cosTable[k * step]
                        val wi = sinTable[k * step]
                        val a = start + k
                        val b = a + half
                        val xr = re[b] * wr - im[b] * wi
                        val xi = re[b] * wi + im[b] * wr
                        re[b] = re[a] - xr
                        im[b] = im[a] - xi
                        re[a] += xr
                        im[a] += xi
                    }
                    start += len
                }
                len = len shl 1
            }
        }
    }
}
