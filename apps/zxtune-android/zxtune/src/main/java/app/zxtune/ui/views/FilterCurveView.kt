/**
 * @file
 * @brief SID filter cutoff curve with live cutoff marker
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import app.zxtune.R
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Plots cutoff frequency (logarithmic scale) by cutoff register value as emulated with current settings
 * (see [SidFilterModel]), reference curve of selected preset and live cutoff of playing tune.
 */
class FilterCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /**
     * Filter state of single chip from SID registers
     */
    data class LiveState(val cutoff: Int, val mode: Int, val resonance: Int, val routing: Int)

    data class State(
        val settings: SidFilterModel.Settings = SidFilterModel.Settings(),
        val is8580: Boolean = false,
        /** Name of selected preset, empty for custom filter */
        val presetName: String = "",
        val live: List<LiveState> = emptyList(),
        /** Shown if no live state */
        val hint: String = "",
    )

    var state = State()
        set(value) {
            if (field != value) {
                val curvesChanged = field.settings != value.settings
                field = value
                if (curvesChanged) {
                    curves = null
                }
                invalidate()
            }
        }

    private class Curves(
        val emulated6581: DoubleArray,
        val emulated8580: DoubleArray,
        val reference6581: DoubleArray?,
        val reference8580: DoubleArray?,
    )

    private var curves: Curves? = null

    private val density = resources.displayMetrics.density
    private val textColor = themeColor(android.R.attr.textColorPrimary, 0xffe0e0e0.toInt())
    private val secondaryColor = themeColor(android.R.attr.textColorSecondary, 0xff909090.toInt())
    private val accentColor = themeColor(androidx.appcompat.R.attr.colorAccent, 0xff4fc3f7.toInt())

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondaryColor
        alpha = 60
        strokeWidth = density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondaryColor
        textSize = sp(10f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = sp(12f)
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val otherCurvePaint = Paint(curvePaint).apply {
        color = secondaryColor
        alpha = 110
        strokeWidth = 1.2f * density
    }
    private val referencePaint = Paint(curvePaint).apply {
        color = textColor
        alpha = 170
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffff7043.toInt()
        strokeWidth = 1.5f * density
    }
    private val plot = RectF()
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desired = (width * 0.55f).roundToInt().coerceIn((160 * density).roundToInt(), (320 * density).roundToInt())
        setMeasuredDimension(width, resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val curves = curves ?: makeCurves().also { curves = it }
        val lineHeight = textPaint.fontSpacing
        val labelWidth = labelPaint.measureText("20k") + 4 * density
        plot.set(
            paddingLeft + labelWidth,
            paddingTop + lineHeight * 1.3f,
            (width - paddingRight).toFloat() - 4 * density,
            height - paddingBottom - labelPaint.fontSpacing - 2 * density - lineHeight * 1.2f
        )
        if (plot.width() <= 0 || plot.height() <= 0) {
            return
        }
        drawGrid(canvas)
        val (active, other) = if (state.is8580) {
            curves.emulated8580 to curves.emulated6581
        } else {
            curves.emulated6581 to curves.emulated8580
        }
        drawCurve(canvas, other, otherCurvePaint)
        (if (state.is8580) curves.reference8580 else curves.reference6581)?.let {
            drawCurve(canvas, it, referencePaint)
        }
        drawCurve(canvas, active, curvePaint)
        drawHeader(canvas)
        if ((if (state.is8580) curves.reference8580 else curves.reference6581) != null) {
            drawLegend(canvas)
        }
        drawLive(canvas, active, lineHeight)
    }

    private fun makeCurves() = with(SidFilterModel) {
        Curves(
            cutoff6581(state.settings),
            cutoff8580(state.settings),
            reference6581(state.settings),
            reference8580(state.settings)
        )
    }

    private fun drawGrid(canvas: Canvas) {
        for (freq in FREQ_GRID) {
            val y = freqToY(freq.toDouble())
            canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
            val label = if (freq >= 1000) "${freq / 1000}k" else freq.toString()
            canvas.drawText(label, paddingLeft.toFloat(), y + labelPaint.textSize / 3, labelPaint)
        }
        val bottom = plot.bottom + labelPaint.fontSpacing
        for (fc in CUTOFF_GRID) {
            val x = cutoffToX(fc)
            canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
            val label = fc.toString()
            val w = labelPaint.measureText(label)
            canvas.drawText(label, (x - w / 2).coerceIn(plot.left, plot.right - w), bottom, labelPaint)
        }
    }

    private fun drawCurve(canvas: Canvas, values: DoubleArray, paint: Paint) {
        path.rewind()
        val step = max(1, values.size / max(1, plot.width().toInt()))
        var idx = 0
        while (idx < values.size) {
            val x = cutoffToX(idx)
            val y = freqToY(values[idx])
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            idx += step
        }
        path.lineTo(cutoffToX(values.size - 1), freqToY(values.last()))
        canvas.drawPath(path, paint)
    }

    private fun drawHeader(canvas: Canvas) {
        val s = state.settings
        val header = buildString {
            append(if (state.is8580) "MOS8580" else "MOS6581")
            append(if (s.residFp) " · reSIDfp" else " · reSID")
            append(" · ")
            append(state.presetName.ifEmpty { "Custom" })
        }
        canvas.drawText(ellipsize(header, plot.right - paddingLeft), paddingLeft.toFloat(), paddingTop - textPaint.ascent(), textPaint)
    }

    // Bottom right corner of plot is free for any curve
    private fun drawLegend(canvas: Canvas) {
        val sample = 18 * density
        val gap = 6 * density
        var y = plot.bottom - gap - labelPaint.descent()
        for ((text, paint) in listOf(
            context.getString(R.string.pref_zxtune_sid_filter_curve_legend_reference) to referencePaint,
            context.getString(R.string.pref_zxtune_sid_filter_curve_legend_emulated) to curvePaint,
        )) {
            val textWidth = labelPaint.measureText(text)
            val x = plot.right - gap - textWidth
            canvas.drawText(text, x, y, labelPaint)
            val lineY = y - labelPaint.textSize / 3
            canvas.drawLine(x - gap - sample, lineY, x - gap, lineY, paint)
            y -= labelPaint.fontSpacing
        }
    }

    private fun drawLive(canvas: Canvas, curve: DoubleArray, lineHeight: Float) {
        val baseline = (height - paddingBottom).toFloat() - textPaint.descent()
        if (state.live.isEmpty()) {
            canvas.drawText(ellipsize(state.hint, (width - paddingLeft - paddingRight).toFloat()), paddingLeft.toFloat(), baseline, labelPaint)
            return
        }
        val texts = ArrayList<String>()
        state.live.forEachIndexed { chip, live ->
            val fc = live.cutoff.coerceIn(0, SidFilterModel.CUTOFF_VALUES - 1)
            val hz = curve[fc]
            val x = cutoffToX(fc)
            val y = freqToY(hz)
            markerPaint.alpha = if (live.routing and 0x0f != 0) 255 else 110
            canvas.drawLine(x, plot.top, x, plot.bottom, markerPaint)
            canvas.drawCircle(x, y, 4 * density, markerPaint)
            texts.add(describe(chip, live, hz))
        }
        canvas.drawText(ellipsize(texts.joinToString("  "), (width - paddingLeft - paddingRight).toFloat()), paddingLeft.toFloat(), baseline, textPaint)
    }

    private fun describe(chip: Int, live: LiveState, hz: Double) = buildString {
        if (state.live.size > 1) {
            append("#${chip + 1} ")
        }
        append("FC ${live.cutoff} ≈ ${formatHz(hz)}")
        val modes = listOfNotNull(
            "LP".takeIf { live.mode and 1 != 0 },
            "BP".takeIf { live.mode and 2 != 0 },
            "HP".takeIf { live.mode and 4 != 0 },
        )
        append(' ')
        append(modes.joinToString("+").ifEmpty { "off" })
        append(" Res ${live.resonance}")
        val voices = (0 until 4).filter { live.routing and (1 shl it) != 0 }
            .joinToString("") { if (it == 3) "E" else "${it + 1}" }
        append(if (voices.isEmpty()) " no voices" else " V$voices")
    }

    private fun ellipsize(text: String, maxWidth: Float): String {
        if (textPaint.measureText(text) <= maxWidth) {
            return text
        }
        val count = textPaint.breakText(text, true, maxWidth - textPaint.measureText("…"), null)
        return text.take(count) + "…"
    }

    private fun cutoffToX(fc: Int) = plot.left + plot.width() * fc / (SidFilterModel.CUTOFF_VALUES - 1)

    private fun freqToY(hz: Double): Float {
        val pos = (ln(hz.coerceIn(MIN_FREQ, MAX_FREQ) / MIN_FREQ) / ln(MAX_FREQ / MIN_FREQ)).toFloat()
        return plot.bottom - plot.height() * pos
    }

    private fun themeColor(@AttrRes attr: Int, fallback: Int): Int {
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

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    companion object {
        private const val MIN_FREQ = 30.0
        private const val MAX_FREQ = 24000.0
        private val FREQ_GRID = intArrayOf(50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000)
        private val CUTOFF_GRID = intArrayOf(0, 512, 1024, 1536, 2047)

        fun formatHz(hz: Double) = if (hz >= 1000) {
            String.format(java.util.Locale.US, "%.1f kHz", hz / 1000)
        } else {
            "${hz.roundToInt()} Hz"
        }
    }
}
