/**
 * @file
 * @brief History lanes of SID filter cutoff and voices pitches (placeholder until the full painter is merged)
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.ln

class FilterLanesPainter(context: Context) {
    private val density = context.resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x0dffffff
    }
    private val gridPaint = Paint().apply {
        color = 0x1affffff
        strokeWidth = density
    }
    private val cutoffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffededed.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val voicePaint = Paint().apply {
        strokeWidth = 3 * density
    }
    private val path = Path()

    fun draw(canvas: Canvas, rect: RectF, history: FilterHistory, windowMs: Long = 4000) {
        val radius = 8 * density
        canvas.drawRoundRect(rect, radius, radius, backgroundPaint)
        for (hz in GRID) {
            val y = freqToY(rect, hz)
            canvas.drawLine(rect.left, y, rect.right, y, gridPaint)
        }
        val last = history.latest() ?: return
        val start = last.timeMs - windowMs
        fun timeToX(ms: Long) = rect.left + rect.width() * (ms - start) / windowMs
        for (voice in 0 until 3) {
            voicePaint.color = FilterCurveView.VOICE_COLORS[voice]
            for (idx in 1 until history.size) {
                val prev = history.frame(idx - 1)
                val cur = history.frame(idx)
                val v = prev.voices[voice]
                if (cur.timeMs < start || !v.active || v.freqHz <= 0f) continue
                voicePaint.alpha = if (v.routed) 255 else 110
                val y = freqToY(rect, v.freqHz)
                canvas.drawLine(maxOf(rect.left, timeToX(prev.timeMs)), y, timeToX(cur.timeMs), y, voicePaint)
            }
        }
        path.rewind()
        var first = true
        for (idx in 0 until history.size) {
            val frame = history.frame(idx)
            if (frame.timeMs < start) continue
            val x = timeToX(frame.timeMs)
            val y = freqToY(rect, frame.cutoffHz)
            if (first) path.moveTo(x, y) else path.lineTo(x, y)
            first = false
        }
        canvas.drawPath(path, cutoffPaint)
    }

    private fun freqToY(rect: RectF, hz: Float): Float {
        val pos = (ln(hz.coerceIn(MIN_HZ, MAX_HZ) / MIN_HZ) / ln(MAX_HZ / MIN_HZ))
        return rect.bottom - rect.height() * pos
    }

    private companion object {
        const val MIN_HZ = 40f
        const val MAX_HZ = 16000f
        val GRID = floatArrayOf(100f, 1000f, 10000f)
    }
}
