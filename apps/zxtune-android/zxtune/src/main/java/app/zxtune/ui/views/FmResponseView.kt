/**
 * @file
 * @brief Frequency response of FM chips output stage
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import app.zxtune.R
import app.zxtune.ui.FmPresets
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Magnitude response of the analog output stage (board filters) of the current settings, 20Hz..20kHz, 0..-24dB, so
 * the effect of preset and filter settings is seen immediately.
 */
class FmResponseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var stage = FmPresets.Stage(0, 1, 0)
        set(value) {
            if (field.lowpass != value.lowpass || field.order != value.order || field.highpass != value.highpass) {
                field = value
                invalidate()
            }
        }

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
    private val path = Path()

    private fun plotHeight(width: Int) = (width * 0.3f).coerceIn(80 * density, 160 * density)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desired = paddingTop + paddingBottom + captionPaint.fontSpacing * 2 + plotHeight(width)
        setMeasuredDimension(width, resolveSize(desired.roundToInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = height - paddingBottom - captionPaint.fontSpacing * 2
        if (right <= left || bottom <= top) {
            return
        }
        canvas.drawRect(left, top, right, bottom, plotPaint)
        fun x(freq: Double) = left + ((ln(freq) - LN_MIN) / (LN_MAX - LN_MIN) * (right - left)).toFloat()
        fun y(db: Double) = top + (-db.coerceIn(MIN_DB, 0.0) / -MIN_DB * (bottom - top)).toFloat()
        for (freq in GRID_FREQS) {
            canvas.drawLine(x(freq), top, x(freq), bottom, gridPaint)
        }
        for (db in GRID_DBS) {
            canvas.drawLine(left, y(db), right, y(db), gridPaint)
        }
        path.rewind()
        for (step in 0..STEPS) {
            val freq = MIN_FREQ * (MAX_FREQ / MIN_FREQ).pow(step.toDouble() / STEPS)
            val px = x(freq)
            val py = y(stage.responseDb(freq))
            if (step == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, curvePaint)
        val baseline = bottom + captionPaint.fontSpacing
        for ((freq, label) in listOf(100.0 to "100", 1000.0 to "1k", 10000.0 to "10k")) {
            canvas.drawText(label, x(freq) - captionPaint.measureText(label) / 2, baseline, captionPaint)
        }
        canvas.drawText(caption(), left, baseline + captionPaint.fontSpacing, captionPaint)
    }

    private fun caption(): String {
        val parts = ArrayList<String>()
        if (stage.lowpass > 0) {
            parts.add(context.getString(R.string.pref_zxtune_fm_response_lowpass, stage.lowpass, if (stage.order >= 2) 12 else 6))
        }
        if (stage.highpass > 0) {
            parts.add(context.getString(R.string.pref_zxtune_fm_response_highpass, stage.highpass))
        }
        return parts.joinToString(", ").ifEmpty { context.getString(R.string.pref_zxtune_fm_response_flat) }
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

    companion object {
        private const val CAPTION_SP = 12f
        private const val MIN_FREQ = 20.0
        private const val MAX_FREQ = 20000.0
        private val LN_MIN = ln(MIN_FREQ)
        private val LN_MAX = ln(MAX_FREQ)
        private const val MIN_DB = -24.0
        private const val STEPS = 96
        private val GRID_FREQS = doubleArrayOf(100.0, 1000.0, 10000.0)
        private val GRID_DBS = doubleArrayOf(-3.0, -12.0)
    }
}
