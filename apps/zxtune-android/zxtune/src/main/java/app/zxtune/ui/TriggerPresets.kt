/**
 * @file
 * @brief Oscilloscope trigger parameters presets
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui

/**
 * Named sets of correlation trigger parameters (native 'zxtune.sound.scope.trigger.*' options, see
 * Parameters::ZXTune::Sound::Scope). Values are based on corrscope's template config and recommendations of its help
 * page (https://corrscope.github.io/corrscope/), checked by traces stability benchmark
 * (src/module/test/scope_bench) on real tunes.
 */
object TriggerPresets {
    const val PREFIX = "zxtune.sound.scope.trigger."
    const val PRESET_KEY = "ui.oscilloscope.trigger_preset"
    const val CUSTOM = "custom"
    const val DEFAULT = "default"

    enum class Type {
        // SeekBarPreference
        INT,

        // SwitchPreferenceCompat
        BOOLEAN,

        // DropDownPreference with numeric values
        CHOICE,
    }

    /**
     * @param default the same as native default
     */
    class Param(val name: String, val type: Type, val default: Int) {
        val key
            get() = PREFIX + name

        /**
         * Value of preference type
         */
        fun toPreference(value: Int): Any = when (type) {
            Type.INT -> value
            Type.BOOLEAN -> value != 0
            Type.CHOICE -> value.toString()
        }

        fun fromPreference(value: Any?): Int = when (value) {
            null -> default
            is Int -> value
            is Long -> value.toInt()
            is Boolean -> if (value) 1 else 0
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    }

    val PARAMS = listOf(
        Param("width", Type.INT, 60),
        Param("edge_strength", Type.INT, 50),
        Param("slope_width", Type.INT, 25),
        Param("buffer_strength", Type.INT, 100),
        Param("responsiveness", Type.INT, 100),
        Param("buffer_falloff", Type.INT, 50),
        Param("reset_below", Type.INT, 70),
        Param("mean_responsiveness", Type.INT, 0),
        Param("sign_strength", Type.INT, 0),
        Param("diameter", Type.INT, 50),
        Param("falling", Type.BOOLEAN, 0),
        Param("pitch_tracking", Type.BOOLEAN, 1),
        Param("post_radius", Type.INT, 0),
        Param("search_rate", Type.CHOICE, 0),
        Param("subsampling", Type.CHOICE, 0),
    )

    /**
     * @param values differences from defaults
     */
    class Preset(val id: String, values: Map<String, Int>) {
        val values = PARAMS.associate { it.name to (values[it.name] ?: it.default) }
    }

    val PRESETS = listOf(
        // The most stable on benchmark for all the chips: 60ms width (corrscope's default for new projects since 0.6)
        // catches lower notes; help's advice for evolving notes: reduce edge strength to track sustained notes, reset
        // buffer on new notes, boost responsiveness. Searched at each frame as corrscope does.
        Preset(DEFAULT, emptyMap()),
        // corrscope's template_config (corrscope.py): trigger_ms=40, mean_responsiveness=0, edge_strength=1,
        // responsiveness=0.5, reset_below=0.3, pitch_tracking=on
        Preset("corrscope", mapOf("width" to 40, "edge_strength" to 100, "responsiveness" to 50, "reset_below" to 30)),
        // Help: increasing edge strength tracks new notes better, post trigger slides to exact edge
        Preset(
            "chiptune",
            mapOf("width" to 40, "edge_strength" to 150, "responsiveness" to 50, "reset_below" to 30, "post_radius" to 4)
        ),
        // Help: for low bass notes increase trigger width (period > 20ms requires > 40ms)
        Preset("bass", mapOf("width" to 100)),
        // Help: NES triangle waves - no DC removal, buffer strength 0, sign amplification 1
        Preset("triangle", mapOf("buffer_strength" to 0, "sign_strength" to 100, "mean_responsiveness" to 0)),
        // fewer full searches, phase between them is extrapolated
        Preset("power_saving", mapOf("search_rate" to 30)),
    )

    fun find(id: String?) = PRESETS.firstOrNull { it.id == id }

    /**
     * Preferences to store for preset (key to typed value)
     */
    fun preferencesOf(preset: Preset): Map<String, Any> = PARAMS.associate { it.key to it.toPreference(preset.values.getValue(it.name)) }

    /**
     * @param get current preference value by key, null if not set (native default is used)
     * @return id of matching preset or [CUSTOM]
     */
    fun detect(get: (String) -> Any?): String {
        val current = PARAMS.associate { it.name to it.fromPreference(get(it.key)) }
        return PRESETS.firstOrNull { it.values == current }?.id ?: CUSTOM
    }
}
