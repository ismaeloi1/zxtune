/**
 * @file
 * @brief Availability of SID settings by selected engine
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui

import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

/**
 * Filter parameters are specific to engine (see src/module/players/external/sid.cpp), the other engine ignores
 * them, so changing them looked like not working. Such settings are disabled for the other engine.
 */
object SidEngineBinding {
    const val ENGINE_KEY = "zxtune.core.sid.engine"
    private const val ENGINE_RESID = "0"
    private const val PREFIX = "zxtune.core.sid."
    val RESID_ONLY = listOf("filter_bias").map { PREFIX + it }
    val RESIDFP_ONLY = listOf("filter_6581_curve", "filter_6581_range", "filter_8580_curve", "combined_waveforms").map { PREFIX + it }

    fun bind(fragment: PreferenceFragmentCompat) {
        val engine = fragment.findPreference<ListPreference>(ENGINE_KEY) ?: return
        fun update(value: String?) {
            val resid = value == ENGINE_RESID
            for (key in RESID_ONLY) {
                fragment.findPreference<Preference>(key)?.isEnabled = resid
            }
            for (key in RESIDFP_ONLY) {
                fragment.findPreference<Preference>(key)?.isEnabled = !resid
            }
        }
        update(engine.value)
        engine.setOnPreferenceChangeListener { _, value ->
            update(value as? String)
            true
        }
    }
}
