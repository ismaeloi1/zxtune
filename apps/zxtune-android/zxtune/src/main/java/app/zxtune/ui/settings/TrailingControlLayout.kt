/**
 * @file
 * @brief Settings row with control at the end or below
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.settings

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import kotlin.math.max

/**
 * Two children: texts block and control. Control is placed at the end of the row when it leaves enough space for
 * texts, otherwise below them across the whole row (long translations, narrow panel over oscilloscope).
 */
class TrailingControlLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private val density = resources.displayMetrics.density
    private val gap = (16 * density).toInt()
    private val spacing = (10 * density).toInt()
    private val minTextWidth = (120 * density).toInt()

    var isStacked = false
        private set

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = max(width - paddingLeft - paddingRight, 0)
        val text = getChildAt(0)
        val control = getChildAt(1)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        control.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), unspecified)
        val textMin = max((available * MIN_TEXT_FRACTION).toInt(), minTextWidth)
        isStacked = control.measuredWidth > available - gap - textMin
        val content = if (isStacked) {
            text.measure(exactly(available), unspecified)
            control.measure(exactly(available), unspecified)
            text.measuredHeight + spacing + control.measuredHeight
        } else {
            text.measure(exactly(available - gap - control.measuredWidth), unspecified)
            max(text.measuredHeight, control.measuredHeight)
        }
        setMeasuredDimension(width, max(content + paddingTop + paddingBottom, suggestedMinimumHeight))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val text = getChildAt(0)
        val control = getChildAt(1)
        if (isStacked) {
            text.layout(paddingLeft, paddingTop, paddingLeft + text.measuredWidth, paddingTop + text.measuredHeight)
            val y = paddingTop + text.measuredHeight + spacing
            control.layout(paddingLeft, y, paddingLeft + control.measuredWidth, y + control.measuredHeight)
        } else {
            val inner = b - t - paddingTop - paddingBottom
            val textY = paddingTop + (inner - text.measuredHeight) / 2
            text.layout(paddingLeft, textY, paddingLeft + text.measuredWidth, textY + text.measuredHeight)
            val right = r - l - paddingRight
            val controlY = paddingTop + (inner - control.measuredHeight) / 2
            control.layout(right - control.measuredWidth, controlY, right, controlY + control.measuredHeight)
        }
    }

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

    private companion object {
        const val MIN_TEXT_FRACTION = 0.4f
    }
}
