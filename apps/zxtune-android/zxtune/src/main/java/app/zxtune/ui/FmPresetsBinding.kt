/**
 * @file
 * @brief Platform presets selection of Yamaha FM chips in preferences screens
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui

import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.TwoStatePreference

/**
 * Preset selection sets all the output stage parameters of the family, while change of any parameter selects matching
 * preset (or custom one), so preset always describes the actual values (see [FmPresets]).
 */
object FmPresetsBinding {
    fun bind(fragment: PreferenceFragmentCompat) {
        for (family in FmPresets.FAMILIES) {
            bind(fragment, family)
        }
    }

    private fun bind(fragment: PreferenceFragmentCompat, family: FmPresets.Family) {
        val preset = fragment.findPreference<ListPreference>(family.presetKey) ?: return
        val params = family.params.mapNotNull { param ->
            fragment.findPreference<Preference>(family.key(param))?.let { param to it }
        }
        var applying = false
        fun current(override: Pair<String, Any>? = null) = params.associate { (param, pref) ->
            val value = if (override?.first == pref.key) override.second else pref.typedValue
            param.name to param.fromPreference(value, family.defaults[param.name] ?: 0)
        }

        fun selected() = preset.value?.toIntOrNull() ?: FmPresets.CUSTOM
        family.detect(current(), selected()).toString().takeIf { it != preset.value }?.let { preset.value = it }
        preset.setOnPreferenceChangeListener { _, value ->
            family.find((value as? String)?.toIntOrNull() ?: FmPresets.CUSTOM)?.let {
                applying = true
                for ((param, pref) in params) {
                    it.values[param.name]?.let { v -> pref.typedValue = param.toPreference(v) }
                }
                applying = false
            }
            true
        }
        for ((_, pref) in params) {
            pref.setOnPreferenceChangeListener { changed, value ->
                if (!applying) {
                    preset.value = family.detect(current(changed.key to value), selected()).toString()
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
