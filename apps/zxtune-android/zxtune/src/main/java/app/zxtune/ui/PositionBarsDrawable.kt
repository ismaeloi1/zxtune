package app.zxtune.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.zxtune.R
import kotlin.math.max

/**
 * Scrubber track made of bars: played part in accent, the rest muted, looped part tinted by the secondary accent
 * and started by a marker. All bars have the same height since there's no energy data for the whole tune, and a
 * made up waveform would pretend to be one.
 *
 * Used as [android.widget.SeekBar.setProgressDrawable], progress comes as [getLevel] in 0..10000.
 */
class PositionBarsDrawable(ctx: Context) : Drawable() {
    private val density = ctx.resources.displayMetrics.density
    private val barWidth = BAR_WIDTH_DP * density
    private val barStep = barWidth + BAR_GAP_DP * density
    private val markerWidth = MARKER_WIDTH_DP * density

    private val played = paint(ContextCompat.getColor(ctx, R.color.lhuna_accent), barWidth)
    private val remaining = paint(
        ColorUtils.setAlphaComponent(ContextCompat.getColor(ctx, R.color.lhuna_text_3), MUTED_ALPHA),
        barWidth
    )
    private val looped = paint(
        ColorUtils.setAlphaComponent(ContextCompat.getColor(ctx, R.color.lhuna_accent_2), LOOPED_ALPHA),
        barWidth
    )
    private val marker = paint(ContextCompat.getColor(ctx, R.color.lhuna_accent_2), markerWidth)
    private val paints = arrayOf(played, remaining, looped, marker)

    // own alpha of each color, combined with the one set by the owner (e.g. disabled SeekBar)
    private val baseAlphas = IntArray(paints.size) { paints[it].alpha }

    /**
     * Start of the looped part in 0..1 of the whole duration, null if there's no loop inside
     */
    var loopStart: Float? = null
        set(value) {
            field = value?.takeIf { it > 0f && it < 1f }
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) {
            return
        }
        val width = bounds.width().toFloat()
        val count = max(1, ((width + barStep - barWidth) / barStep).toInt())
        // spread the rest evenly to have the first and the last bars at the ends
        val step = if (count > 1) (width - barWidth) / (count - 1) else 0f
        val barHalf = bounds.height() * BAR_HEIGHT / 2
        val centerY = bounds.exactCenterY()
        val position = level / MAX_LEVEL.toFloat()
        val loopX = loopStart?.let { bounds.left + width * it }
        for (idx in 0 until count) {
            val x = bounds.left + barWidth / 2 + idx * step
            val paint = when {
                x <= bounds.left + width * position -> played
                loopX != null && x >= loopX -> looped
                else -> remaining
            }
            canvas.drawLine(x, centerY - barHalf, x, centerY + barHalf, paint)
        }
        loopX?.let { x ->
            val half = bounds.height() / 2f - markerWidth / 2
            canvas.drawLine(x, centerY - half, x, centerY + half, marker)
        }
    }

    override fun onLevelChange(level: Int) = true.also { invalidateSelf() }

    override fun setAlpha(alpha: Int) {
        paints.forEachIndexed { idx, paint ->
            paint.alpha = baseAlphas[idx] * alpha / 255
        }
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        for (paint in paints) {
            paint.colorFilter = colorFilter
        }
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private const val MAX_LEVEL = 10000
        private const val BAR_WIDTH_DP = 3f
        private const val BAR_GAP_DP = 2.5f
        private const val MARKER_WIDTH_DP = 2.5f
        private const val BAR_HEIGHT = 0.6f
        private const val MUTED_ALPHA = 0x80

        // violet is more saturated than the muted gray, so less alpha keeps the same weight
        private const val LOOPED_ALPHA = 0x66

        private fun paint(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
        }
    }
}
