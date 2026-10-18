package app.zxtune.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerPresetsTest {

    @Test
    fun `not set preferences are default preset`() {
        assertEquals(TriggerPresets.DEFAULT, TriggerPresets.detect { null })
    }

    @Test
    fun `each preset is detected by its values`() {
        for (preset in TriggerPresets.PRESETS) {
            val prefs = TriggerPresets.preferencesOf(preset)
            assertEquals(preset.id, TriggerPresets.detect { prefs[it] })
        }
    }

    @Test
    fun `presets are different`() {
        val values = TriggerPresets.PRESETS.map { it.values }
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `changed parameter is custom preset`() {
        val prefs = TriggerPresets.preferencesOf(TriggerPresets.find("chiptune")!!).toMutableMap()
        prefs[TriggerPresets.PREFIX + "edge_strength"] = 170
        assertEquals(TriggerPresets.CUSTOM, TriggerPresets.detect { prefs[it] })
    }

    // types are the same as stored by preferences widgets, else the bridge to native options can't convert them
    @Test
    fun `values types`() {
        val prefs = TriggerPresets.preferencesOf(TriggerPresets.find("triangle")!!)
        assertEquals(0, prefs[TriggerPresets.PREFIX + "buffer_strength"])
        assertEquals(100, prefs[TriggerPresets.PREFIX + "sign_strength"])
        assertEquals(true, prefs[TriggerPresets.PREFIX + "pitch_tracking"])
        assertEquals(false, prefs[TriggerPresets.PREFIX + "falling"])
        assertEquals("0", prefs[TriggerPresets.PREFIX + "search_rate"])
        assertEquals("30", TriggerPresets.preferencesOf(TriggerPresets.find("power_saving")!!)[TriggerPresets.PREFIX + "search_rate"])
    }

    // the same as native defaults, see Parameters::ZXTune::Sound::Scope
    @Test
    fun `defaults match native ones`() {
        val defaults = TriggerPresets.PARAMS.associate { it.name to it.default }
        assertEquals(
            mapOf(
                "width" to 40, "edge_strength" to 100, "slope_width" to 25, "buffer_strength" to 100,
                "responsiveness" to 50, "buffer_falloff" to 50, "reset_below" to 30, "mean_responsiveness" to 0,
                "sign_strength" to 0, "diameter" to 50, "falling" to 0, "pitch_tracking" to 1, "post_radius" to 0,
                "search_rate" to 0, "subsampling" to 0
            ),
            defaults
        )
        assertTrue(TriggerPresets.PARAMS.all { it.key.startsWith("zxtune.sound.scope.trigger.") })
    }
}
