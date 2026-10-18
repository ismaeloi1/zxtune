/**
 * @file
 * @brief Emulated chips of the playing tune and how their settings are applied
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui

/**
 * Chips of the playing tune, so live settings panel shows only the emulation settings affecting it.
 * Chip ids are the same as preferences keys groups (zxtune.core.<id>.*), so a new category of preferences is
 * matched without changes here.
 *
 * @param chips ids of categories with emulation settings
 * @param names human readable chips names, e.g. for chips without any settings
 */
class EmulationContext(val chips: Set<String>, val names: List<String>) {

    val isEmpty
        get() = chips.isEmpty() && names.isEmpty()

    companion object {
        const val AYM = "aym"
        const val Z80 = "z80"
        const val FM = "fm"
        const val DAC = "dac"
        const val SAA = "saa"
        const val MT32 = "mt32"
        const val SID = "sid"

        // Settings of playback, not of chips (gain, fading, durations): zxtune.sound.* and zxtune.core.plugins.*
        const val GENERAL = "general"

        val NONE = EmulationContext(emptySet(), emptyList())

        // Module types (core plugins ids), see src/core/plugins/players
        private val AY_TYPES = setOf(
            "AS0", "ASC", "AY", "AYC", "FTC", "GTR", "PSC", "PSG", "PSM", "PT1", "PT2", "PT3", "SQT", "ST1", "ST3",
            "STC", "STP", "TS", "TXT", "VTX", "YM",
        )

        // Only AY files with player code are emulated with Z80, others are registers dumps or trackers
        private val Z80_TYPES = setOf("AY")
        private val TFM_TYPES = setOf("TF0", "TFC", "TFD", "TFE")

        // ZX digital trackers and tracker modules played by libxmp/libopenmpt use the same interpolation option
        private val DAC_TYPES = setOf(
            "CHI", "DMM", "DST", "ET1", "PDT", "SQD", "STR",
            "667", "669", "AMF", "AMS", "C67", "CBA", "DBM", "DMF", "DSM", "DSYM", "DTM", "DTT", "EMOD", "ETX", "FAR",
            "FC", "FMT", "FNK", "FTM", "GDM", "GMC", "GTK", "IMF", "IMS", "IT", "J2B", "KRIS", "LIQ", "MDL", "MED",
            "MO3", "MOD", "MT2", "MTM", "NRU", "OKT", "PLM", "PSM", "PTM", "PUMA", "RTM", "S3M", "SFX", "SS", "STIM",
            "STM", "STP", "STX", "SYMMOD", "TCB", "ULT", "XM",
        )
        private val SAA_TYPES = setOf("COP")

        private val AMBIGUOUS_TYPES = AY_TYPES intersect DAC_TYPES

        // Multitrack ZX container: any of ZX chips is possible
        private const val MTC = "MTC"

        /**
         * @param type module type (ModuleAttributes.TYPE), empty if unknown
         * @param groups voices groups names of currently playing tune, see [app.zxtune.playback.VoicesLayout]
         * @param description emulation description, the first line of [app.zxtune.playback.Visualizer.getStatus]
         */
        fun detect(type: String, groups: List<String>, description: String): EmulationContext {
            val live = detectLive(groups, description)
            val byType = detectByType(type)
            // the same type id is used by different formats (e.g. PSM for ProSoundMaker and Epic MegaGames),
            // actual chips are known only when playback is started
            val known = groups.isNotEmpty() || description.isNotBlank()
            val chips = live + if (known && type in AMBIGUOUS_TYPES) {
                byType - if (AYM in live) DAC else AYM
            } else {
                byType
            }
            val names = groups.map { it.substringBefore('/') }.distinct().ifEmpty {
                listOfNotNull(description.substringBefore(',').takeIf { it.isNotBlank() })
            }
            return EmulationContext(chips, names)
        }

        private fun detectByType(type: String) = HashSet<String>().apply {
            if (type in AY_TYPES) add(AYM)
            if (type in Z80_TYPES) add(Z80)
            if (type in TFM_TYPES) add(FM)
            if (type in DAC_TYPES) add(DAC)
            if (type in SAA_TYPES) add(SAA)
            if (type == "SID") add(SID)
            if (type == "MIDI") add(MT32)
            if (type == MTC) addAll(listOf(AYM, FM, DAC, SAA))
        }

        // Names are given by zxtune's own chips emulation, libvgm/gme names differ (e.g. AY8910), so options of
        // zxtune's chips are not offered for them
        private fun detectLive(groups: List<String>, description: String) = HashSet<String>().apply {
            val all = groups + description
            if (all.any { it.contains("AY-3-8910") || it.contains("YM2149F") }) add(AYM)
            if (description.contains("TurboFM")) add(FM)
            if (groups.any { it.startsWith("SID") } || description.contains("MOS")) add(SID)
            if (description.contains("Munt")) add(MT32)
        }

        /**
         * Category id by preferences keys of category
         */
        fun categoryOf(keys: List<String>): String? {
            for (key in keys) {
                if (key.startsWith(SOUND_PREFIX) || key.startsWith(PLUGINS_PREFIX)) {
                    continue
                }
                if (key.startsWith(CORE_PREFIX)) {
                    return key.removePrefix(CORE_PREFIX).substringBefore('.')
                }
            }
            return GENERAL.takeIf { keys.any { it.startsWith(SOUND_PREFIX) || it.startsWith(PLUGINS_PREFIX) } }
        }

        private const val CORE_PREFIX = "zxtune.core."
        private const val SOUND_PREFIX = "zxtune.sound."
        private const val PLUGINS_PREFIX = "zxtune.core.plugins."

        /**
         * Settings read only when tune is opened, others are applied by chips emulation while playing (see
         * src/module/test/engine_params and src/module/test/sid_params host checks)
         */
        private val NEXT_TRACK_KEYS = setOf(
            // duration of tunes without own one is calculated by module detection
            "zxtune.core.plugins.default_duration",
        )

        fun isAppliedLive(key: String) = key !in NEXT_TRACK_KEYS
    }
}
