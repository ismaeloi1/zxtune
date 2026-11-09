/**
 * @file
 * @brief Segmented control for short choices
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.settings

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.TextViewCompat
import app.zxtune.R
import kotlin.math.max
import kotlin.math.min

/**
 * Row of equal segments on tonal pill, selected one is filled with accent container.
 *
 * Takes natural width (widest label times segments count) if possible. When squeezed (e.g. placed below the title
 * of narrow row), labels are wrapped to two lines and reduced down to [MIN_TEXT_SP] to keep long translated words.
 */
class SegmentedControl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    var onSegmentSelected: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val inset = (4 * density).toInt()
    private val padding = (14 * density).toInt()
    private val compactPadding = (6 * density).toInt()
    private val segmentHeight = resources.getDimensionPixelSize(R.dimen.lhuna_segment_touch_height)
    private val maxTextPx = sp(MAX_TEXT_SP)
    private val minTextPx = sp(MIN_TEXT_SP)
    private val measurePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
    private var labels = emptyList<CharSequence>()
    private var selected = -1

    init {
        background = pill(ContextCompat.getColor(context, R.color.lhuna_surface_2))
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = RadioGroup::class.java.name
            }
        })
    }

    fun setSegments(values: List<CharSequence>, selection: Int) {
        if (values != labels) {
            labels = values
            removeAllViews()
            values.forEachIndexed { idx, label -> addView(makeSegment(idx, label)) }
        }
        select(selection)
    }

    fun select(idx: Int) {
        selected = idx
        for (pos in 0 until childCount) {
            getChildAt(pos).run {
                isSelected = pos == idx
                background = if (pos == idx) pill(ContextCompat.getColor(context, R.color.lhuna_accent_container)) else null
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        for (pos in 0 until childCount) {
            getChildAt(pos).isEnabled = enabled
        }
        alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    private fun makeSegment(idx: Int, label: CharSequence) = AppCompatTextView(context).apply {
        TextViewCompat.setTextAppearance(this, R.style.Lhuna_Settings_Segment)
        setTextColor(ContextCompat.getColorStateList(context, R.color.lhuna_segment_text))
        text = label
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setTextSize(TypedValue.COMPLEX_UNIT_PX, maxTextPx)
        setPadding(padding, 0, padding, 0)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            if (idx != selected) {
                onSegmentSelected?.invoke(idx)
            }
        }
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = RadioButton::class.java.name
                info.isCheckable = true
                info.isChecked = host.isSelected
            }
        })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val count = childCount
        if (count == 0) {
            setMeasuredDimension(0, 0)
            return
        }
        // computed from text, without measuring children with temporary sizes that would request new layout
        val natural = labels.maxOf { textWidth(it, maxTextPx) } + 2 * padding
        val naturalTotal = natural * count + 2 * inset
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val total = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> size
            MeasureSpec.AT_MOST -> min(naturalTotal, size)
            else -> naturalTotal
        }
        val segment = (total - 2 * inset) / count
        val squeezed = segment < natural
        var height = segmentHeight
        for (idx in 0 until count) {
            val child = getChildAt(idx) as AppCompatTextView
            val pad = if (squeezed) compactPadding else padding
            if (child.paddingLeft != pad) {
                child.setPadding(pad, 0, pad, 0)
            }
            val textSize = if (squeezed) fitTextSize(labels[idx], segment - 2 * pad) else maxTextPx
            if (child.textSize != textSize) {
                child.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            }
            child.measure(MeasureSpec.makeMeasureSpec(segment, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            height = max(height, child.measuredHeight)
        }
        for (idx in 0 until count) {
            getChildAt(idx).measure(
                MeasureSpec.makeMeasureSpec(segment, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(total, height + 2 * inset)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = inset
        for (idx in 0 until childCount) {
            val child = getChildAt(idx)
            child.layout(x, inset, x + child.measuredWidth, inset + child.measuredHeight)
            x += child.measuredWidth
        }
    }

    // the longest word must fit the line, the whole label must fit two lines
    private fun fitTextSize(label: CharSequence, width: Int): Float {
        val words = label.split(' ', ' ').filter { it.isNotEmpty() }
        var size = maxTextPx
        while (size > minTextPx) {
            val longest = words.maxOfOrNull { textWidth(it, size) } ?: 0
            if (longest <= width && textWidth(label, size) <= width * 2) {
                break
            }
            size -= density / 2
        }
        return max(size, minTextPx)
    }

    private fun textWidth(text: CharSequence, size: Float): Int {
        measurePaint.textSize = size
        measurePaint.typeface = (getChildAt(0) as? AppCompatTextView)?.typeface
        return kotlin.math.ceil(measurePaint.measureText(text, 0, text.length)).toInt()
    }

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun pill(color: Int) = GradientDrawable().apply {
        setColor(color)
        // clamped to half of height, so any size is a pill
        cornerRadius = 1000 * density
    }

    private companion object {
        const val MAX_TEXT_SP = 14f
        const val MIN_TEXT_SP = 11f
        const val DISABLED_ALPHA = 0.5f
    }
}
