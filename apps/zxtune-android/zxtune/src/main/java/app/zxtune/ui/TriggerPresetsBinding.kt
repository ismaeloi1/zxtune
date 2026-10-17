/**
 * @file
 * @brief Trigger presets selection in preferences screens
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui

import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.TwoStatePreference

/**
 * Preset selection sets all the trigger parameters, while change of any parameter selects matching preset (or custom
 * one), so preset always describes the actual values.
 */
object TriggerPresetsBinding {
    fun bind(fragment: PreferenceFragmentCompat) {
        val preset = fragment.findPreference<ListPreference>(TriggerPresets.PRESET_KEY) ?: return
        val params = TriggerPresets.PARAMS.mapNotNull { param ->
            fragment.findPreference<Preference>(param.key)?.let { param to it }
        }
        var applying = false
        fun current(override: Pair<String, Any>? = null) = TriggerPresets.detect { key ->
            if (override?.first == key) {
                override.second
            } else {
                params.firstOrNull { it.first.key == key }?.second?.typedValue
            }
        }
        preset.value = current()
        preset.setOnPreferenceChangeListener { _, value ->
            TriggerPresets.find(value as? String)?.let {
                applying = true
                for ((param, pref) in params) {
                    pref.typedValue = param.toPreference(it.values.getValue(param.name))
                }
                applying = false
            }
            true
        }
        for ((_, pref) in params) {
            pref.setOnPreferenceChangeListener { changed, value ->
                if (!applying) {
                    preset.value = current(changed.key to value)
                }
                true
            }
        }
    }

    private var Preference.typedValue: Any?
        get() = when (this) {
            is SeekBarPreference -> value
            is TwoStatePreference -> isChecked
            is ListPreference -> value
            else -> null
        }
        set(value) {
            when (this) {
                is SeekBarPreference -> this.value = value as Int
                is TwoStatePreference -> isChecked = value as Boolean
                is ListPreference -> this.value = value as String
            }
        }
}
