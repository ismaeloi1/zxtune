/**
 * @file
 * @brief Platform presets of Yamaha FM chips families
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui

import kotlin.math.log10

/**
 * Native 'zxtune.core.fm.<family>.*' options (see Parameters::ZXTune::Core::FM). Presets are the same as native ones,
 * see src/module/players/external/fm_output.cpp for references. Native side uses custom values only with custom
 * preset, here preset selection sets them as well, so they always describe the actual output stage (as
 * [TriggerPresets] do).
 */
object FmPresets {
    const val PREFIX = "zxtune.core.fm."
    const val CUSTOM = 0

    const val CHIP = "chip"
    const val LADDER = "ladder"
    const val LOWPASS = "lowpass"
    const val LOWPASS_ORDER = "lowpass_order"
    const val HIGHPASS = "highpass"
    const val STEREO = "stereo"

    enum class Type {
        // SeekBarPreference
        INT,

        // SwitchPreferenceCompat
        BOOLEAN,

        // list preference with numeric values
        CHOICE,
    }

    class Param(val name: String, val type: Type) {
        fun toPreference(value: Int): Any = when (type) {
            Type.INT -> value
            Type.BOOLEAN -> value != 0
            Type.CHOICE -> value.toString()
        }

        fun fromPreference(value: Any?, default: Int): Int = when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Boolean -> if (value) 1 else 0
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    }

    class Preset(val id: Int, val values: Map<String, Int>)

    /**
     * @param presets the first one is default, custom values defaults are the same
     */
    class Family(val id: String, val params: List<Param>, val presets: List<Preset>) {
        val prefix = "$PREFIX$id."
        val presetKey = prefix + "preset"
        val coreKey = prefix + "core"
        val responseKey = "ui.fm.$id.response"

        val defaults: Map<String, Int>
            get() = presets.firstOrNull()?.values ?: DEFAULT_STAGE

        fun key(param: Param) = prefix + param.name

        fun find(id: Int) = presets.firstOrNull { it.id == id }

        /**
         * Preset with the same values (preferably the current one, as some platforms share values) or custom one
         */
        fun detect(values: Map<String, Int>, current: Int = CUSTOM): Int {
            fun matches(preset: Preset) = preset.values.all { values[it.key] == it.value }
            return find(current)?.takeIf { matches(it) }?.id ?: presets.firstOrNull { matches(it) }?.id ?: CUSTOM
        }
    }

    private val STAGE_PARAMS = listOf(
        Param(LOWPASS, Type.INT),
        Param(LOWPASS_ORDER, Type.CHOICE),
        Param(HIGHPASS, Type.INT),
    )
    private val STEREO_PARAM = Param(STEREO, Type.INT)

    private fun stage(lowpass: Int, order: Int, highpass: Int, stereo: Int) = mapOf(LOWPASS to lowpass, LOWPASS_ORDER to order, HIGHPASS to highpass, STEREO to stereo)

    private fun opn2(chip: Int, ladder: Boolean, lowpass: Int, order: Int, highpass: Int) = stage(lowpass, order, highpass, 100) + mapOf(CHIP to chip, LADDER to if (ladder) 1 else 0)

    private val DEFAULT_STAGE = stage(0, 1, 0, 100)

    // chip variants
    private const val YM2612 = 0
    private const val YM3438 = 1
    private const val YMF276 = 2

    val OPN2 = Family(
        "opn2",
        listOf(Param(CHIP, Type.CHOICE), Param(LADDER, Type.BOOLEAN)) + STAGE_PARAMS + STEREO_PARAM,
        listOf(
            // Mega Drive model 1 VA0..VA6
            Preset(1, opn2(YM2612, true, 3390, 1, 5)),
            // Mega Drive model 1 VA7
            Preset(2, opn2(YM3438, false, 3390, 1, 5)),
            // Mega Drive model 2
            Preset(3, opn2(YM3438, false, 2840, 2, 5)),
            // Genesis 3
            Preset(4, opn2(YM3438, false, 3390, 1, 5)),
            Preset(5, opn2(YM3438, false, 0, 1, 0)),
            Preset(6, opn2(YMF276, false, 0, 1, 0)),
        ),
    )

    val OPM = Family(
        "opm",
        STAGE_PARAMS + STEREO_PARAM,
        listOf(
            // YM3012 (X68000, arcade)
            Preset(1, stage(0, 2, 0, 100)),
            // YM3014 (mono)
            Preset(2, stage(0, 2, 0, 0)),
        ),
    )

    val OPN = Family(
        "opn",
        STAGE_PARAMS + STEREO_PARAM,
        listOf(
            // PC-88/PC-98
            Preset(1, stage(0, 2, 0, 100)),
        ),
    )

    val OPL = Family(
        "opl",
        STAGE_PARAMS + STEREO_PARAM,
        listOf(
            // SB16 is default
            Preset(4, stage(0, 1, 0, 100)),
            // AdLib
            Preset(1, stage(0, 1, 0, 0)),
            // SB 1.x/2.0
            Preset(2, stage(12000, 1, 0, 100)),
            // SB Pro 1/2
            Preset(3, stage(8000, 1, 0, 100)),
        ),
    )

    // mono chip, no presets
    val OPLL = Family("opll", STAGE_PARAMS, emptyList())

    val FAMILIES = listOf(OPN2, OPM, OPN, OPL, OPLL)

    fun familyOf(key: String) = FAMILIES.firstOrNull { key.startsWith(it.prefix) }

    /**
     * Analog prototype of the output stage: RC (6dB/oct) or Butterworth (12dB/oct) low-pass and RC high-pass, as
     * emulated (discretization differs only near Nyquist frequency)
     */
    class Stage(val lowpass: Int, val order: Int, val highpass: Int) {
        fun responseDb(freq: Double): Double {
            var power = 1.0
            if (lowpass > 0) {
                val ratio = freq / lowpass
                power /= 1 + if (order >= 2) ratio * ratio * ratio * ratio else ratio * ratio
            }
            if (highpass > 0) {
                val ratio = freq / highpass
                power *= ratio * ratio / (1 + ratio * ratio)
            }
            return 10 * log10(power.coerceAtLeast(1e-12))
        }

        val isFlat
            get() = lowpass <= 0 && highpass <= 0
    }

    fun stageOf(family: Family, read: (String) -> Any?): Stage {
        val defaults = family.defaults
        fun value(name: String) = Param(name, Type.INT).fromPreference(read(family.prefix + name), defaults[name] ?: 0)
        return Stage(value(LOWPASS), value(LOWPASS_ORDER), value(HIGHPASS))
    }
}
