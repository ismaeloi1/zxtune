/**
 * @file
 * @brief SID filter cutoff curve of current settings, for presets tuning
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
 * Static chart under filter presets: cutoff frequency by cutoff register value as emulated with current settings
 * (see [SidFilterModel]), with reference curve of selected preset and curve of another chip model, so the effect of
 * any filter setting is seen immediately. Live filter response of playing tune is shown by oscilloscope instead
 * (see [FilterScene]).
 */
class FilterCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    data class State(
        val settings: SidFilterModel.Settings = SidFilterModel.Settings(),
        val is8580: Boolean = false,
        /** Name of selected preset, empty for custom filter */
        val presetName: String = "",
    )

    var state = State()
        set(value) {
            if (field != value) {
                val curvesChanged = field.settings != value.settings
                field = value
                if (curvesChanged) {
                    curves = null
                }
                caption = null
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
    private var caption: String? = null

    private val density = resources.displayMetrics.density
    private val textColor = themeColor(android.R.attr.textColorPrimary, 0xffe0e0e0.toInt())
    private val secondaryColor = themeColor(android.R.attr.textColorSecondary, 0xff909090.toInt())

    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondaryColor
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, CAPTION_SP, resources.displayMetrics)
    }
    private val plotPaint = Paint().apply {
        color = textColor
        alpha = 12
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondaryColor
        alpha = 60
        strokeWidth = density
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val otherCurvePaint = Paint(curvePaint).apply {
        color = secondaryColor
        alpha = 110
        strokeWidth = 1.2f * density
    }
    private val referencePaint = Paint(curvePaint).apply {
        alpha = 170
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
    }

    private val plot = RectF()
    private val path = Path()

    private val legendTexts = arrayOf(
        context.getString(R.string.pref_zxtune_sid_filter_curve_legend_reference),
        context.getString(R.string.pref_zxtune_sid_filter_curve_legend_emulated),
    )

    private fun plotHeight(width: Int) = (width * 0.5f).coerceIn(120 * density, 240 * density)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desired = paddingTop + paddingBottom + captionPaint.fontSpacing * 2 + GAP_DP * density + plotHeight(width)
        setMeasuredDimension(width, resolveSize(desired.roundToInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val curves = curves ?: makeCurves().also { curves = it }
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        var y = paddingTop.toFloat()
        val text = caption ?: makeCaption().also { caption = it }
        canvas.drawText(ellipsize(text, right - left), left, y - captionPaint.ascent(), captionPaint)
        y += captionPaint.fontSpacing + GAP_DP * density
        val labelWidth = captionPaint.measureText("10k") + 6 * density
        plot.set(left + labelWidth, y, right, y + plotHeight(width))
        canvas.drawRect(plot, plotPaint)
        for (freq in FREQ_GRID) {
            val gy = freqToY(freq.toDouble())
            canvas.drawLine(plot.left, gy, plot.right, gy, gridPaint)
            val label = if (freq >= 1000) "${freq / 1000}k" else freq.toString()
            canvas.drawText(label, left, gy + captionPaint.textSize / 3, captionPaint)
        }
        val bottom = plot.bottom + 2 * density - captionPaint.ascent()
        for (fc in CUTOFF_GRID) {
            val x = cutoffToX(fc)
            canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
            val label = fc.toString()
            val w = captionPaint.measureText(label)
            canvas.drawText(label, (x - w / 2).coerceIn(plot.left, plot.right - w), bottom, captionPaint)
        }
        val active = if (state.is8580) curves.emulated8580 else curves.emulated6581
        val other = if (state.is8580) curves.emulated6581 else curves.emulated8580
        drawCurve(canvas, other, otherCurvePaint)
        val reference = if (state.is8580) curves.reference8580 else curves.reference6581
        reference?.let {
            drawCurve(canvas, it, referencePaint)
        }
        drawCurve(canvas, active, curvePaint)
        if (reference != null) {
            drawLegend(canvas)
        }
    }

    private fun makeCurves() = with(SidFilterModel) {
        Curves(
            cutoff6581(state.settings),
            cutoff8580(state.settings),
            reference6581(state.settings),
            reference8580(state.settings)
        )
    }

    private fun makeCaption() = buildString {
        append(if (state.is8580) "MOS8580" else "MOS6581")
        append(if (state.settings.residFp) " · reSIDfp · " else " · reSID · ")
        append(state.presetName.ifEmpty { context.getString(R.string.pref_zxtune_sid_filter_curve_custom) })
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

    // Bottom right corner of plot is free for any curve
    private fun drawLegend(canvas: Canvas) {
        val sample = 18 * density
        val gap = 6 * density
        var y = plot.bottom - gap - captionPaint.descent()
        for (idx in 0 until 2) {
            val text = legendTexts[idx]
            val paint = if (idx == 0) referencePaint else curvePaint
            val textWidth = captionPaint.measureText(text)
            val x = plot.right - gap - textWidth
            canvas.drawText(text, x, y, captionPaint)
            val lineY = y - captionPaint.textSize / 3
            canvas.drawLine(x - gap - sample, lineY, x - gap, lineY, paint)
            y -= captionPaint.fontSpacing
        }
    }

    private fun ellipsize(text: String, maxWidth: Float): String {
        if (captionPaint.measureText(text) <= maxWidth) {
            return text
        }
        val count = captionPaint.breakText(text, true, maxWidth - captionPaint.measureText("…"), null)
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

    private companion object {
        const val CAPTION_SP = 11f
        const val GAP_DP = 8f
        const val MIN_FREQ = 30.0
        const val MAX_FREQ = 24000.0
        val FREQ_GRID = intArrayOf(100, 1000, 10000)
        val CUTOFF_GRID = intArrayOf(0, 1024, 2047)
    }
}
