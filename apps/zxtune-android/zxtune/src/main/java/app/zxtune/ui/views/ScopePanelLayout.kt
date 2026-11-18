/**
 * @file
 * @brief Vertical layout with first child of fixed aspect ratio
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Places first child at the top with 16:9 aspect ratio (limited by [maxTopFraction] of height),
 * the second one takes the rest of space below. Padding is respected (e.g. display cutout).
 */
class ScopePanelLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    var maxTopFraction = 0.7f

    /** Oscilloscope height relative to 16:9, user setting; the rest is for dashboard */
    var topScale = 1f
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fullWidth = MeasureSpec.getSize(widthMeasureSpec)
        val fullHeight = MeasureSpec.getSize(heightMeasureSpec)
        val width = maxOf(fullWidth - paddingLeft - paddingRight, 0)
        val height = maxOf(fullHeight - paddingTop - paddingBottom, 0)
        val bottom = getChildAt(1)
        val topHeight = if (bottom?.visibility == View.GONE) {
            height
        } else {
            // larger oscilloscope may take up to 85% of height, dashboard keeps the rest
            val limit = if (topScale > 1f) maxOf(maxTopFraction, MAX_SCALED_FRACTION) else maxTopFraction
            minOf((width * ASPECT_HEIGHT / ASPECT_WIDTH * topScale).toInt(), (height * limit).toInt())
        }
        getChildAt(0)?.measure(exactly(width), exactly(topHeight))
        bottom?.measure(exactly(width), exactly(maxOf(height - topHeight, 0)))
        setMeasuredDimension(fullWidth, fullHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val top = getChildAt(0) ?: return
        top.layout(paddingLeft, paddingTop, paddingLeft + top.measuredWidth, paddingTop + top.measuredHeight)
        // padding of this layout, not of the child
        val left = paddingLeft
        val y = paddingTop + top.measuredHeight
        getChildAt(1)?.run {
            layout(left, y, left + measuredWidth, y + measuredHeight)
        }
    }

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

    private companion object {
        const val ASPECT_WIDTH = 16
        const val ASPECT_HEIGHT = 9
        const val MAX_SCALED_FRACTION = 0.85f
    }
}
