package app.zxtune.ui

import androidx.annotation.ColorRes
import app.zxtune.R
import app.zxtune.core.Identifier
import app.zxtune.playback.EngineBrands

/**
 * Sound chip family of a tune, displayed as a colored badge in lists, mini player and now playing title block.
 *
 * Families follow the system tags of [EngineBrands] so a format moved to another engine there gets the matching
 * badge here. Colors are taken from the voices palette: color is an information, so families sharing a color are
 * related (e.g. all ZX chips, all sample based trackers); rare or streamed formats stay neutral.
 *
 * @param label badge text, short enough to fit 40dp badge
 * @param color badge color, null for neutral
 */
enum class ChipFamily(val label: String, @param:ColorRes @get:ColorRes val color: Int?) {
    SID("SID", R.color.lhuna_voice_1),
    AY("AY", R.color.lhuna_voice_2),
    SAA("SAA", R.color.lhuna_voice_2),
    SPC("SPC", R.color.lhuna_voice_3),
    MOD("MOD", R.color.lhuna_voice_4),
    DAC("DAC", R.color.lhuna_voice_4),
    FM("FM", R.color.lhuna_voice_5),
    GB("GB", R.color.lhuna_voice_6),
    GBA("GBA", R.color.lhuna_voice_6),
    NES("NES", R.color.lhuna_voice_7),
    PSG("PSG", R.color.lhuna_voice_8),
    MSX("MSX", R.color.lhuna_voice_8),
    PCE("PCE", R.color.lhuna_voice_8),
    POKEY("SAP", R.color.lhuna_voice_8),
    NDS("NDS", null),
    PSF("PSF", null),
    USF("USF", null),
    SSF("SSF", null),
    DSF("DSF", null),
    MIDI("MIDI", null),
    V2("V2", null),
    ;

    companion object {
        private val BY_SYSTEM = mapOf(
            "C64" to SID,
            "SNES" to SPC,
            "AY" to AY,
            "OPL" to FM,
            "OPM" to FM,
            "PSG" to PSG,
            "SAA" to SAA,
            "NES" to NES,
            "GB" to GB,
            "GBA" to GBA,
            "NDS" to NDS,
            "PCE" to PCE,
            "MSX" to MSX,
            "POKEY" to POKEY,
            "PSX" to PSF,
            "N64" to USF,
            "SATURN" to SSF,
            "DREAMCAST" to DSF,
            "MT32" to MIDI,
            "DAC" to DAC,
            "MOD" to MOD,
            "V2" to V2,
        )

        // Filename extensions different from module type ids (see ModuleAttributes.TYPE)
        private val EXTENSIONS = mapOf(
            "MINIPSF" to "PSF",
            "MINIPSF2" to "PSF2",
            "MINIUSF" to "USF",
            "MINIGSF" to "GSF",
            "MINI2SF" to "2SF",
            "MINISSF" to "SSF",
            "MINIDSF" to "DSF",
            "MINISNSF" to "SNSF",
            "MININCSF" to "NCSF",
            "MID" to "MIDI",
        )

        // Ambiguous types resolved by EngineBrands only for playing tunes: SoundTracker Pro files are much more
        // common on ZX Spectrum than Amiga's formats with the same id
        private val AMBIGUOUS_EXTENSIONS = mapOf("STP" to AY)

        // Extensions of generic files which are formats ids too: badge would be misleading for e.g. readme.txt
        private val GENERIC_EXTENSIONS = setOf("TXT", "TS")

        // Containers do not say anything about content, and name before the dot is not a type then
        private val CONTAINER_EXTENSIONS = setOf(
            "ZIP", "RAR", "7Z", "LHA", "LZH", "GZ", "TGZ", "TAR", "BZ2", "XZ", "ZXZIP", "TRD", "SCL", "HOB", "DSK",
            "SCO", "M3U", "PLS", "XSPF", "AYL",
        )

        /**
         * @param type module type, see [app.zxtune.core.ModuleAttributes.TYPE]
         */
        fun ofType(type: String?): ChipFamily? = type?.takeIf { it.isNotEmpty() }?.let {
            BY_SYSTEM[EngineBrands.systemOf(it.uppercase(), "")]
        }

        /**
         * Best guess by filename for not yet opened files, both 'name.ext' and Amiga's 'ext.name' styles
         */
        fun ofFilename(name: String?): ChipFamily? {
            if (name.isNullOrEmpty()) {
                return null
            }
            val suffix = name.substringAfterLast('.', "").uppercase()
            if (suffix in CONTAINER_EXTENSIONS) {
                return null
            }
            return ofExtension(suffix) ?: ofExtension(name.substringBefore('.', "").uppercase())
        }

        private fun ofExtension(ext: String) = when {
            ext.isEmpty() || ext in GENERIC_EXTENSIONS -> null
            else -> AMBIGUOUS_EXTENSIONS[ext] ?: ofType(EXTENSIONS[ext] ?: ext)
        }
    }
}

/**
 * Name of the file the tune comes from: archive entry or the file itself, subtune is not a name
 */
val Identifier.tuneFilename: String
    get() = archiveEntryName?.takeIf { it.isNotEmpty() } ?: dataLocation.lastPathSegment.orEmpty()
