/**
 * @file
 * @brief SID filter cutoff and voices pitches by time ("history lanes")
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Draws [FilterHistory] for the last window of time: X is time (oldest at left, now at right edge), Y is logarithmic
 * frequency. Cutoff is a light line with halo growing with resonance, rejected band is shaded, voices are lanes at
 * their pitches dimmed by filter attenuation. Unfiltered voices are dashed, noise ones are hatched.
 * Nothing is allocated while drawing: paths and paints are reused, lanes are drawn by runs of equal pitch and alpha.
 */
class FilterLanesPainter(context: Context) {
    var voiceColors = intArrayOf(0xffffb547.toInt(), 0xff5fd38d.toInt(), 0xffff6fa5.toInt())

    private val density = context.resources.displayMetrics.density
    private val scaledDensity = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, context.resources.displayMetrics)
    private val textColor = themeColor(context, android.R.attr.textColorPrimary, 0xffe0e0e0.toInt())
    private val secondaryColor = themeColor(context, android.R.attr.textColorSecondary, 0xff909090.toInt())
    private val backgroundColor = themeColor(context, android.R.attr.colorBackground, Color.BLACK)

    private val gridPaint = Paint().apply {
        color = secondaryColor
        alpha = 40
        strokeWidth = density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondaryColor
        textSize = 10 * scaledDensity
    }
    private val voiceLabelPaint = Paint(labelPaint).apply {
        textSize = 11 * scaledDensity
        textAlign = Paint.Align.RIGHT
    }

    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        alpha = 18
        style = Paint.Style.FILL
    }
    private val cutoffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    // opaque, so overlapping caps of adjacent resonance buckets are not visible
    private val haloPaint = Paint(cutoffPaint)
    private val lanePaint = Paint().apply {
        strokeWidth = LANE_WIDTH_DP * density
        strokeCap = Paint.Cap.BUTT
    }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        alpha = 120
        strokeWidth = density
        style = Paint.Style.FILL
    }

    private val plot = RectF()
    private val cutoffPath = Path()
    private val haloPaths = Array(HALO_BUCKETS) { Path() }
    private val shadePath = Path()
    private val nowPath = Path()

    // labels of current lanes, placed apart
    private val labelY = FloatArray(VOICES)
    private val labelVoice = IntArray(VOICES)

    private var windowLabelMs = Long.MIN_VALUE
    private var windowLabel = ""

    fun draw(canvas: Canvas, rect: RectF, history: FilterHistory, windowMs: Long = 4000) {
        val labelsHeight = labelPaint.fontSpacing
        val labelsWidth = labelPaint.measureText("10k") + 6 * density
        plot.set(rect.left + labelsWidth, rect.top, rect.right, rect.bottom - labelsHeight)
        if (plot.width() <= 0 || plot.height() <= 0 || windowMs <= 0) {
            return
        }
        drawGrid(canvas, rect, windowMs)
        val latest = history.latest() ?: return
        val now = latest.timeMs
        val first = firstVisible(history, now - windowMs)
        canvas.save()
        canvas.clipRect(plot)
        drawShading(canvas, history, first, now, windowMs)
        drawCutoff(canvas, history, first, now, windowMs)
        for (voice in 0 until VOICES) {
            drawLane(canvas, history, first, now, windowMs, voice)
        }
        canvas.restore()
        drawVoiceLabels(canvas, latest)
    }

    private fun drawGrid(canvas: Canvas, rect: RectF, windowMs: Long) {
        val baseline = -labelPaint.ascent() * 0.35f
        labelPaint.textAlign = Paint.Align.RIGHT
        for (idx in FREQ_GRID.indices) {
            val y = freqToY(FREQ_GRID[idx].toFloat())
            canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
            canvas.drawText(FREQ_LABELS[idx], plot.left - 4 * density, y + baseline, labelPaint)
        }
        val textY = plot.bottom - labelPaint.ascent() + 2 * density
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(windowText(windowMs), plot.left, textY, labelPaint)
        // now marker: edge line and small triangle under it
        canvas.drawLine(plot.right, plot.top, plot.right, plot.bottom, nowPaint)
        val size = 4 * density
        nowPath.rewind()
        nowPath.moveTo(plot.right, plot.bottom + 1 * density)
        nowPath.lineTo(plot.right - size, plot.bottom + 1 * density + size * 1.4f)
        nowPath.lineTo(plot.right, plot.bottom + 1 * density + size * 1.4f)
        nowPath.close()
        canvas.drawPath(nowPath, nowPaint)
    }

    private fun windowText(windowMs: Long): String {
        if (windowLabelMs != windowMs) {
            windowLabelMs = windowMs
            val seconds = windowMs / 1000f
            windowLabel = if (seconds == seconds.roundToInt().toFloat()) {
                "−${seconds.roundToInt()} s"
            } else {
                "−%.1f s".format(seconds)
            }
        }
        return windowLabel
    }

    // last frame before window start, so the lines enter from the left edge
    private fun firstVisible(history: FilterHistory, startMs: Long): Int {
        var idx = history.size - 1
        while (idx > 0 && history.frame(idx).timeMs > startMs) {
            --idx
        }
        return idx
    }

    private fun timeToX(timeMs: Long, now: Long, windowMs: Long) = plot.right - (now - timeMs) * plot.width() / windowMs

    private fun freqToY(hz: Float): Float {
        val pos = (ln(hz.coerceIn(MIN_HZ, MAX_HZ) / MIN_HZ) / FREQ_SPAN)
        return plot.bottom - plot.height() * pos
    }

    // end of the frame's time span: next frame or now
    private fun endX(history: FilterHistory, idx: Int, now: Long, windowMs: Long) = if (idx + 1 < history.size) {
        timeToX(history.frame(idx + 1).timeMs, now, windowMs)
    } else {
        plot.right
    }

    private fun drawShading(canvas: Canvas, history: FilterHistory, first: Int, now: Long, windowMs: Long) {
        shadePath.rewind()
        shadeEdge(history, first, now, windowMs, above = true)
        shadeEdge(history, first, now, windowMs, above = false)
        canvas.drawPath(shadePath, shadePaint)
    }

    // cutoff multiplier of rejected band edge above (lowpass) or below (highpass) passband, 0 if none
    private fun edgeFactor(mode: Int, above: Boolean) = when (mode and 7) {
        SidFilterModel.MODE_LOWPASS -> if (above) 1f else 0f
        SidFilterModel.MODE_HIGHPASS -> if (above) 0f else 1f
        SidFilterModel.MODE_BANDPASS -> if (above) BAND else 1 / BAND
        SidFilterModel.MODE_LOWPASS or SidFilterModel.MODE_BANDPASS -> if (above) BAND else 0f
        SidFilterModel.MODE_HIGHPASS or SidFilterModel.MODE_BANDPASS -> if (above) 0f else 1 / BAND
        else -> 0f
    }

    // polygons between band edge (polyline as cutoff one) and plot top or bottom for runs of frames with such an edge
    private fun shadeEdge(history: FilterHistory, first: Int, now: Long, windowMs: Long, above: Boolean) {
        val bound = if (above) plot.top else plot.bottom
        var open = false
        var lastY = 0f
        for (idx in first until history.size) {
            val frame = history.frame(idx)
            val x = timeToX(frame.timeMs, now, windowMs)
            val factor = edgeFactor(frame.mode, above)
            if (factor == 0f) {
                if (open) {
                    shadePath.lineTo(x, lastY)
                    shadePath.lineTo(x, bound)
                    shadePath.close()
                    open = false
                }
                continue
            }
            lastY = freqToY(frame.cutoffHz * factor)
            if (!open) {
                shadePath.moveTo(x, bound)
                open = true
            }
            shadePath.lineTo(x, lastY)
        }
        if (open) {
            shadePath.lineTo(plot.right, lastY)
            shadePath.lineTo(plot.right, bound)
            shadePath.close()
        }
    }

    private fun drawCutoff(canvas: Canvas, history: FilterHistory, first: Int, now: Long, windowMs: Long) {
        cutoffPath.rewind()
        haloPaths.forEach { it.rewind() }
        var prevX = 0f
        var prevY = 0f
        var prevBucket = -1
        for (idx in first until history.size) {
            val frame = history.frame(idx)
            val x = timeToX(frame.timeMs, now, windowMs)
            val y = freqToY(frame.cutoffHz)
            if (idx == first) {
                cutoffPath.moveTo(x, y)
            } else {
                cutoffPath.lineTo(x, y)
                // continuous polylines per resonance bucket for round joins inside
                val bucket = (frame.resonance.coerceIn(0, 15) * HALO_BUCKETS) / 16
                if (bucket != prevBucket) {
                    haloPaths[bucket].moveTo(prevX, prevY)
                    prevBucket = bucket
                }
                haloPaths[bucket].lineTo(x, y)
            }
            prevX = x
            prevY = y
        }
        // hold the latest value until now
        cutoffPath.lineTo(plot.right, prevY)
        if (prevBucket >= 0) {
            haloPaths[prevBucket].lineTo(plot.right, prevY)
        }
        for (bucket in 0 until HALO_BUCKETS) {
            val path = haloPaths[bucket]
            if (path.isEmpty) continue
            val strength = (bucket + 1f) / HALO_BUCKETS
            haloPaint.strokeWidth = (4 + 10 * strength) * density
            haloPaint.color = ColorUtils.blendARGB(backgroundColor, textColor, 0.1f + 0.2f * strength)
            canvas.drawPath(path, haloPaint)
        }
        canvas.drawPath(cutoffPath, cutoffPaint)
    }

    private fun voiceAlpha(voice: FilterHistory.Voice): Int {
        var alpha = 0.35f + 0.65f * voice.level.coerceIn(0f, 1f)
        if (voice.routed) {
            alpha *= (1 + voice.gainDb / 24).coerceIn(0.15f, 1f)
        }
        // quantized to merge runs
        return ((alpha * ALPHA_STEPS).roundToInt() * 255) / ALPHA_STEPS
    }

    private fun laneStyle(voice: FilterHistory.Voice) = when {
        !voice.active -> STYLE_NONE
        voice.noise -> STYLE_NOISE
        voice.routed -> STYLE_SOLID
        else -> STYLE_DASHED
    }

    private fun drawLane(canvas: Canvas, history: FilterHistory, first: Int, now: Long, windowMs: Long, voice: Int) {
        lanePaint.color = voiceColors[voice % voiceColors.size]
        var idx = first
        while (idx < history.size) {
            val v = history.frame(idx).voices[voice]
            val style = laneStyle(v)
            if (style == STYLE_NONE) {
                ++idx
                continue
            }
            val y = freqToY(v.freqHz).roundToInt()
            val alpha = voiceAlpha(v)
            val x0 = timeToX(history.frame(idx).timeMs, now, windowMs)
            var end = idx + 1
            while (end < history.size) {
                val n = history.frame(end).voices[voice]
                if (laneStyle(n) != style || freqToY(n.freqHz).roundToInt() != y || voiceAlpha(n) != alpha) break
                ++end
            }
            val x1 = endX(history, end - 1, now, windowMs)
            drawRun(canvas, style, x0, x1, y.toFloat(), alpha)
            idx = end
        }
    }

    private fun drawRun(canvas: Canvas, style: Int, x0: Float, x1: Float, y: Float, alpha: Int) {
        if (x1 <= x0) return
        lanePaint.alpha = alpha
        when (style) {
            STYLE_SOLID -> {
                lanePaint.strokeWidth = LANE_WIDTH_DP * density
                canvas.drawLine(x0, y, x1, y, lanePaint)
            }

            STYLE_DASHED -> {
                // dashes are aligned to the plot, not to the run, so they don't flicker with run boundaries
                lanePaint.strokeWidth = LANE_WIDTH_DP * density
                val period = DASH_PERIOD_DP * density
                val dash = period * 0.7f
                var x = plot.right - ((plot.right - x0) / period).toInt() * period
                if (x > x0) x -= period
                while (x < x1) {
                    val from = maxOf(x, x0)
                    val to = minOf(x + dash, x1)
                    if (to > from) canvas.drawLine(from, y, to, y, lanePaint)
                    x += period
                }
            }

            STYLE_NOISE -> {
                // thin band with slanted hatches
                val half = NOISE_HEIGHT_DP * density / 2
                lanePaint.strokeWidth = density
                lanePaint.alpha = alpha / 2
                canvas.drawLine(x0, y - half, x1, y - half, lanePaint)
                canvas.drawLine(x0, y + half, x1, y + half, lanePaint)
                lanePaint.alpha = alpha
                val period = HATCH_PERIOD_DP * density
                var x = plot.right - ((plot.right - x0) / period).toInt() * period
                while (x < x1 + half * 2) {
                    val from = maxOf(x - half * 2, x0)
                    val to = minOf(x, x1)
                    if (to > from) {
                        // 45 degrees line from (x - 2h, y + h) to (x, y - h), cut by run bounds
                        val start = x - half * 2
                        canvas.drawLine(from, y + half - (from - start), to, y + half - (to - start), lanePaint)
                    }
                    x += period
                }
            }
        }
    }

    private fun drawVoiceLabels(canvas: Canvas, latest: FilterHistory.Frame) {
        var count = 0
        for (voice in 0 until VOICES) {
            val v = latest.voices[voice]
            if (!v.active) continue
            labelY[count] = freqToY(v.freqHz) - LANE_WIDTH_DP * density / 2 - 3 * density
            labelVoice[count] = voice
            ++count
        }
        // sort by position (insertion sort of up to 3 items) and push apart from bottom to top
        for (i in 1 until count) {
            var j = i
            while (j > 0 && labelY[j - 1] < labelY[j]) {
                val y = labelY[j]
                labelY[j] = labelY[j - 1]
                labelY[j - 1] = y
                val idx = labelVoice[j]
                labelVoice[j] = labelVoice[j - 1]
                labelVoice[j - 1] = idx
                --j
            }
        }
        val spacing = voiceLabelPaint.fontSpacing * 0.9f
        for (i in 1 until count) {
            labelY[i] = minOf(labelY[i], labelY[i - 1] - spacing)
        }
        val minY = plot.top - voiceLabelPaint.ascent()
        for (i in 0 until count) {
            val voice = labelVoice[i]
            voiceLabelPaint.color = voiceColors[voice % voiceColors.size]
            canvas.drawText(VOICE_LABELS[voice], plot.right - 4 * density, maxOf(labelY[i], minY), voiceLabelPaint)
        }
    }

    companion object {
        private const val VOICES = 3
        private val VOICE_LABELS = arrayOf("V1", "V2", "V3")

        private const val MIN_HZ = 50f
        private const val MAX_HZ = 12000f
        private val FREQ_SPAN = ln(MAX_HZ / MIN_HZ)
        private val FREQ_GRID = intArrayOf(100, 1000, 10000)
        private val FREQ_LABELS = arrayOf("100", "1k", "10k")

        // shaded bandpass is outside of cutoff/BAND..cutoff*BAND
        private const val BAND = 2f

        private const val HALO_BUCKETS = 4
        private const val ALPHA_STEPS = 16
        private const val LANE_WIDTH_DP = 4f
        private const val DASH_PERIOD_DP = 10f
        private const val HATCH_PERIOD_DP = 5f
        private const val NOISE_HEIGHT_DP = 8f

        private const val STYLE_NONE = 0
        private const val STYLE_SOLID = 1
        private const val STYLE_DASHED = 2
        private const val STYLE_NOISE = 3

        private fun themeColor(context: Context, @AttrRes attr: Int, fallback: Int): Int {
            val value = TypedValue()
            if (!context.theme.resolveAttribute(attr, value, true)) {
                return fallback
            }
            return if (value.resourceId != 0) {
                runCatching { ContextCompat.getColor(context, value.resourceId) }.getOrDefault(fallback)
            } else {
                value.data
            }
        }
    }
}
