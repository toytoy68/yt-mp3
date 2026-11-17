package fr.perso.ytmp3.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import fr.perso.ytmp3.R
import kotlin.math.abs
import kotlin.math.roundToInt

/** Analyseur de spectre façon chaîne hi-fi : barres en segments + crêtes qui retombent. */
class SpectrumView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val bands = SpectrumAnalyzer.BANDS
    private val segments = 14
    private val shown = FloatArray(bands)
    private val peaks = FloatArray(bands)
    private val peakHold = IntArray(bands)
    private val paint = Paint()
    private val dimPaint = Paint().apply { color = Color.rgb(12, 34, 18) }
    private val peakPaint = Paint().apply { color = Color.rgb(200, 205, 215) }

    /** À appeler à chaque image avec les niveaux cibles (0..1). */
    fun setLevels(target: FloatArray) {
        for (i in 0 until bands) {
            val t = target.getOrElse(i) { 0f }
            // Montée immédiate, descente progressive (inertie des vrais VU-mètres).
            shown[i] = if (t > shown[i]) t else (shown[i] - 0.035f).coerceAtLeast(t)
            if (shown[i] >= peaks[i]) {
                peaks[i] = shown[i]
                peakHold[i] = 18
            } else if (peakHold[i] > 0) {
                peakHold[i]--
            } else {
                peaks[i] = (peaks[i] - 0.015f).coerceAtLeast(0f)
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = 2f
        val barW = (w - gap * (bands - 1)) / bands
        val segH = (h - gap * (segments - 1)) / segments

        for (b in 0 until bands) {
            val x = b * (barW + gap)
            val lit = (shown[b] * segments).roundToInt()
            for (s in 0 until segments) {
                val y = h - (s + 1) * segH - s * gap
                if (s < lit) {
                    paint.color = segmentColor(s)
                    canvas.drawRect(x, y, x + barW, y + segH, paint)
                } else {
                    canvas.drawRect(x, y, x + barW, y + segH, dimPaint)
                }
            }
            val peakSeg = (peaks[b] * segments).roundToInt().coerceAtMost(segments) - 1
            if (peakSeg >= 0 && peaks[b] > 0.02f) {
                val y = h - (peakSeg + 1) * segH - peakSeg * gap
                canvas.drawRect(x, y, x + barW, y + segH * 0.5f, peakPaint)
            }
        }
    }

    private fun segmentColor(s: Int): Int {
        val f = s.toFloat() / (segments - 1)
        return when {
            f < 0.55f -> Color.rgb(61, 255, 114)                       // vert
            f < 0.8f -> Color.rgb(255, (220 - (f - 0.55f) * 300).toInt(), 40) // jaune -> orange
            else -> Color.rgb(255, 60, 40)                               // rouge
        }
    }
}

/** Égaliseur à curseurs verticaux, dessiné à la main pour garder le style rétro. */
class EqView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Appelé quand l'utilisateur bouge un curseur : (bande, niveau en millibels). */
    var onChange: ((Int, Int) -> Unit)? = null

    private var centers = IntArray(0)
    private var levels = IntArray(0)
    private var minLevel = -1500
    private var maxLevel = 1500
    private var activeBand = -1

    private val density = resources.displayMetrics.density
    private val font: Typeface? = try {
        resources.getFont(R.font.vt323)
    } catch (_: Exception) {
        null
    }
    private val groovePaint = Paint().apply { color = Color.rgb(3, 10, 5) }
    private val fillPaint = Paint().apply { color = Color.rgb(20, 92, 40) }
    private val linePaint = Paint().apply { color = Color.rgb(60, 66, 86); strokeWidth = density }
    private val knobDark = Paint().apply { color = Color.rgb(11, 12, 17) }
    private val knobLight = Paint().apply { color = Color.rgb(140, 112, 32) }
    private val knobFace = Paint().apply { color = Color.rgb(255, 178, 36) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(174, 180, 200)
        textSize = 15 * density
        textAlign = Paint.Align.CENTER
        typeface = font
    }

    fun setBands(centerHz: IntArray, levels: IntArray, min: Int, max: Int) {
        centers = centerHz
        this.levels = levels.copyOf()
        minLevel = min
        maxLevel = max
        invalidate()
    }

    fun resetLevels() {
        for (i in levels.indices) {
            levels[i] = 0
            onChange?.invoke(i, 0)
        }
        invalidate()
    }

    private val labelSpace get() = 22 * density
    private val knobH get() = 12 * density

    private fun columnX(i: Int): Float {
        val left = 34 * density
        val usable = width - left - 8 * density
        return left + usable * (i + 0.5f) / centers.size
    }

    private fun levelToY(level: Int): Float {
        val top = knobH / 2 + 4 * density
        val bottom = height - labelSpace - knobH / 2
        val f = (level - minLevel).toFloat() / (maxLevel - minLevel)
        return bottom - f * (bottom - top)
    }

    private fun yToLevel(y: Float): Int {
        val top = knobH / 2 + 4 * density
        val bottom = height - labelSpace - knobH / 2
        val f = ((bottom - y) / (bottom - top)).coerceIn(0f, 1f)
        val level = (minLevel + f * (maxLevel - minLevel)).roundToInt()
        return if (abs(level) < (maxLevel - minLevel) / 30) 0 else level // aimantation sur 0 dB
    }

    override fun onDraw(canvas: Canvas) {
        if (centers.isEmpty()) return
        // Graduations +max / 0 / -max à gauche
        textPaint.textAlign = Paint.Align.LEFT
        val db = maxLevel / 100
        canvas.drawText("+$db", 2 * density, levelToY(maxLevel) + 5 * density, textPaint)
        canvas.drawText(" 0", 2 * density, levelToY(0) + 5 * density, textPaint)
        canvas.drawText("-$db", 2 * density, levelToY(minLevel) + 5 * density, textPaint)
        canvas.drawLine(30 * density, levelToY(0), width.toFloat(), levelToY(0), linePaint)
        textPaint.textAlign = Paint.Align.CENTER

        val grooveW = 6 * density
        val knobW = 26 * density
        for (i in centers.indices) {
            val x = columnX(i)
            val yTop = levelToY(maxLevel)
            val yBottom = levelToY(minLevel)
            canvas.drawRect(x - grooveW / 2, yTop, x + grooveW / 2, yBottom, groovePaint)
            val yLevel = levelToY(levels[i])
            val yZero = levelToY(0)
            canvas.drawRect(x - grooveW / 2 + density, minOf(yLevel, yZero), x + grooveW / 2 - density, maxOf(yLevel, yZero), fillPaint)

            // Bouton du curseur en relief
            val l = x - knobW / 2
            val t = yLevel - knobH / 2
            canvas.drawRect(l, t, l + knobW, t + knobH, knobDark)
            canvas.drawRect(l, t, l + knobW - density, t + knobH - density, knobLight)
            canvas.drawRect(l + density, t + density, l + knobW - density, t + knobH - density, knobFace)

            canvas.drawText(formatHz(centers[i]), x, height - 4 * density, textPaint)
        }
    }

    private fun formatHz(hz: Int): String =
        if (hz >= 1000) "${(hz / 100) / 10.0}K".replace(".0K", "K") else "$hz"

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (centers.isEmpty() || !isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeBand = centers.indices.minByOrNull { abs(columnX(it) - event.x) } ?: -1
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeBand = -1
                return true
            }
        }
        if (activeBand >= 0) {
            val level = yToLevel(event.y)
            if (level != levels[activeBand]) {
                levels[activeBand] = level
                onChange?.invoke(activeBand, level)
                invalidate()
            }
        }
        return true
    }
}
