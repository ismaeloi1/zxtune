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
 * the second one takes the rest of space below.
 */
class ScopePanelLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    var maxTopFraction = 0.7f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val bottom = getChildAt(1)
        val topHeight = if (bottom?.visibility == View.GONE) {
            height
        } else {
            minOf(width * ASPECT_HEIGHT / ASPECT_WIDTH, (height * maxTopFraction).toInt())
        }
        getChildAt(0)?.measure(exactly(width), exactly(topHeight))
        bottom?.measure(exactly(width), exactly(maxOf(height - topHeight, 0)))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val top = getChildAt(0) ?: return
        top.layout(0, 0, top.measuredWidth, top.measuredHeight)
        getChildAt(1)?.run {
            layout(0, top.measuredHeight, measuredWidth, top.measuredHeight + measuredHeight)
        }
    }

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

    private companion object {
        const val ASPECT_WIDTH = 16
        const val ASPECT_HEIGHT = 9
    }
}
