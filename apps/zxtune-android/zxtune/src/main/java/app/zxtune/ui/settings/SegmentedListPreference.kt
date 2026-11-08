/**
 * @file
 * @brief List preference displayed as segmented control
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.settings

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import app.zxtune.R

/**
 * For 2-4 short choices: all of them are visible and selected with a single tap instead of dropdown.
 *
 * Remarks in parentheses (e.g. "Quality (high CPU usage)") are not shown on segments, but as summary of selected
 * one, so labels stay short in all languages.
 */
class SegmentedListPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ListPreference(context, attrs) {

    init {
        layoutResource = R.layout.lhuna_preference_segmented
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        // segments handle touches, the row itself does nothing. Not made unselectable: library paints titles of such
        // rows with summary color
        holder.itemView.isClickable = false
        holder.itemView.isFocusable = false
        val control = holder.findViewById(R.id.segmented) as SegmentedControl
        control.contentDescription = title
        control.setSegments(entries.map { labelOf(it) }, findIndexOfValue(value))
        control.isEnabled = isEnabled
        control.onSegmentSelected = { idx ->
            val selected = entryValues[idx].toString()
            if (callChangeListener(selected)) {
                value = selected
            } else {
                // listener may reject or replace the value
                control.select(findIndexOfValue(value))
            }
        }
    }

    // no dialog
    override fun onClick() = Unit

    override fun getSummary(): CharSequence? = super.getSummary()?.takeIf { it.isNotEmpty() } ?: entry?.let { remarkOf(it) }

    companion object {
        internal fun labelOf(entry: CharSequence) = entry.toString().substringBefore('(').trim()

        internal fun remarkOf(entry: CharSequence) = entry.toString().substringAfter('(', "").substringBefore(')').trim().takeIf { it.isNotEmpty() }
    }
}
