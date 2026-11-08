/**
 * @file
 * @brief Slider value with unit
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.settings

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * SeekBarPreference updates its value label with plain number (also while dragging), so unit is added here.
 */
class ValueTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

    /**
     * [String.format] pattern with single integer argument, e.g. "%d ms"
     */
    var format: String? = null
        set(value) {
            field = value
            raw?.let { setText(it, BufferType.NORMAL) }
        }

    private var raw: CharSequence? = null

    override fun setText(text: CharSequence?, type: BufferType?) {
        raw = text
        // also called from base class constructor, before properties initialization
        val formatted = format?.let { pattern -> text?.toString()?.toIntOrNull()?.let { String.format(pattern, it) } }
        super.setText(formatted ?: text, type)
    }
}
