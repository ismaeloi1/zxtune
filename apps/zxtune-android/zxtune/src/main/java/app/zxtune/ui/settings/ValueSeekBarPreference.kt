/**
 * @file
 * @brief Slider preference with unit
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.settings

import android.content.Context
import android.util.AttributeSet
import androidx.core.content.res.use
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference
import app.zxtune.R

/**
 * Value is displayed with unit (app:valueFormat) instead of having it in title, e.g. "Waveforms window ... 40 ms"
 */
class ValueSeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SeekBarPreference(context, attrs) {

    var valueFormat: String? = context.obtainStyledAttributes(attrs, R.styleable.ValueSeekBarPreference).use {
        it.getString(R.styleable.ValueSeekBarPreference_valueFormat)
    }

    override fun onBindViewHolder(view: PreferenceViewHolder) {
        // before base class sets the value text
        (view.findViewById(androidx.preference.R.id.seekbar_value) as? ValueTextView)?.format = valueFormat
        super.onBindViewHolder(view)
    }
}
