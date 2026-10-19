package app.zxtune.playback

/**
 * Central table of engine brands displayed as "Running on <brand>" line of playback status.
 *
 * Edit [BRANDS] to rename an engine, [BY_TYPE] or [BY_CHIP] to move format/chip to another engine.
 */
object EngineBrands {
    /**
     * System tag to displayed engine brand
     */
    val BRANDS = mapOf(
        "C64" to "NebulaWeave3",
        "SNES" to "NebulaWeaveSPC",
        "AY" to "NebulaWeaveAY",
        // both OPN (YM2203/YM2608/YM2610/YM2612, TurboFM) and OPL families
        "OPL" to "NebulaWeaveOPL",
        "OPM" to "NebulaWeaveOPM",
        "PSG" to "NebulaWeavePSG",
        "SAA" to "NebulaWeaveSAA",
        "NES" to "NebulaWeaveNES",
        "GB" to "NebulaWeaveGB",
        "GBA" to "NebulaWeaveGBA",
        "NDS" to "NebulaWeaveNDS",
        "PCE" to "NebulaWeavePCE",
        "MSX" to "NebulaWeaveMSX",
        "POKEY" to "NebulaWeavePOKEY",
        "PSX" to "NebulaWeavePSX",
        "N64" to "NebulaWeaveN64",
        "SATURN" to "NebulaWeaveSCSP",
        "DREAMCAST" to "NebulaWeaveAICA",
        "MT32" to "NebulaWeaveMT32",
        "DAC" to "NebulaWeaveDAC",
        "MOD" to "NebulaWeaveMOD",
        "V2" to "NebulaWeaveV2",
        "PCM" to "NebulaWeavePCM",
    )

    private const val DEFAULT_SYSTEM = "PCM"

    /**
     * Module type (see [app.zxtune.core.ModuleAttributes.TYPE]) to system tag.
     * Multichip formats (VGM, S98) and ambiguous types (PSM, STP) are resolved by [BY_CHIP].
     */
    val BY_TYPE: Map<String, String> = buildMap {
        fun add(system: String, vararg types: String) = types.forEach { put(it, system) }
        add("C64", "SID")
        add("SNES", "SPC", "SNSF")
        add(
            "AY", "AS0", "ASC", "AY", "AYC", "FTC", "GTR", "PSC", "PSG", "PT1", "PT2", "PT3", "SQT",
            "ST1", "ST3", "STC", "TS", "TXT", "VTX", "YM", "MTC",
        )
        add("OPL", "TF0", "TFC", "TFD", "TFE", "GYM")
        add("SAA", "COP")
        add("NES", "NSF", "NSFE")
        add("GB", "GBS")
        add("GBA", "GSF")
        add("NDS", "2SF", "NCSF")
        add("PCE", "HES")
        add("MSX", "KSS", "KSSX")
        add("POKEY", "SAP", "RMT")
        add("PSX", "PSF", "PSF2")
        add("N64", "USF")
        add("SATURN", "SSF")
        add("DREAMCAST", "DSF")
        add("MT32", "MIDI")
        add("DAC", "CHI", "DMM", "DST", "ET1", "PDT", "SQD", "STR")
        add("V2", "V2M")
        add(
            "MOD", "667", "669", "AHX", "AMF", "AMS", "C67", "CBA", "DBM", "DMF", "DSM", "DSYM", "DTM",
            "DTT", "EMOD", "ETX", "FAR", "FC", "FMT", "FNK", "FTM", "GDM", "GMC", "GTK", "HVL", "IMF",
            "IMS", "IT", "J2B", "KRIS", "LIQ", "MDL", "MED", "MO3", "MOD", "MT2", "MTM", "NRU", "OKT",
            "PLM", "PTM", "PUMA", "RTM", "S3M", "SFX", "SS", "STIM", "STM", "STX", "SYMMOD", "TCB",
            "ULT", "XM",
        )
    }

    /**
     * Chip name in emulation description (first status line) to system tag, first match wins
     */
    val BY_CHIP = listOf(
        "MOS6581" to "C64",
        "MOS8580" to "C64",
        "S-DSP" to "SNES",
        "AY-3-8910" to "AY",
        "YM2149" to "AY",
        "TurboFM" to "OPL",
        "YM2203" to "OPL",
        "YM2608" to "OPL",
        "YM2610" to "OPL",
        "YM2612" to "OPL",
        "YM3438" to "OPL",
        "YM2413" to "OPL",
        "YM3526" to "OPL",
        "YM3812" to "OPL",
        "Y8950" to "OPL",
        "YMF262" to "OPL",
        "YMF278" to "OPL",
        "YM2151" to "OPM",
        "SN76489" to "PSG",
        "SAA1099" to "SAA",
        "2A03" to "NES",
        "NES APU" to "NES",
        "GB DMG" to "GB",
        "HuC6280" to "PCE",
        "POKEY" to "POKEY",
    )

    /**
     * @param type module type
     * @param description emulation description, first line of scope status
     */
    fun systemOf(type: String, description: String): String = BY_TYPE[type]
        ?: BY_CHIP.firstOrNull { description.contains(it.first, ignoreCase = true) }?.second
        ?: AMBIGUOUS_TYPES[type]
        ?: DEFAULT_SYSTEM

    fun brandOf(type: String, description: String) = BRANDS.getValue(systemOf(type, description))

    // the same ids for AY and openmpt formats, AY variant always has description
    private val AMBIGUOUS_TYPES = mapOf("PSM" to "MOD", "STP" to "MOD")
}
