package app.zxtune.playback

/**
 * Original driver and engine lines of playback status, shown instead of tune format line.
 */
object StatusHeader {
    const val DRIVER_PREFIX = "Original driver: "
    const val ENGINE_PREFIX = "Running on "
    private const val UNKNOWN = "unknown"
    private const val SCOPE_PREFIX = "scope:"

    // Program property of these formats is a game name, sound driver is a part of game code
    private val GAME_DUMPS = setOf(
        "SPC", "SNSF", "NSF", "NSFE", "GBS", "HES", "KSS", "KSSX", "SAP", "VGM", "S98", "GYM",
        "PSF", "PSF2", "USF", "GSF", "2SF", "NCSF", "SSF", "DSF", "AY",
    )

    /**
     * @param type module type
     * @param program module program property (tracker, detected playroutine or game name)
     * @param formatName known format description, fallback for unknown program
     */
    fun driverOf(type: String, program: String, formatName: () -> String?): String = when {
        type in GAME_DUMPS -> if (program.isNotBlank()) "$program (in-game)" else "in-game"

        program.isNotBlank() -> program

        // playroutine is detected by signature, format name says nothing about it
        type == "SID" -> UNKNOWN

        else -> formatName()?.takeIf { it.isNotBlank() } ?: UNKNOWN
    }

    fun isHeader(line: String) = line.startsWith(DRIVER_PREFIX) || line.startsWith(ENGINE_PREFIX)

    /**
     * Inserts header right after emulation description line (if any), so the first line is still
     * a description.
     */
    fun insert(status: String, type: String, program: String, formatName: () -> String?): String {
        val lines = status.lines()
        val hasDescription = lines.isNotEmpty() && lines[0].isNotBlank() &&
            !lines[0].startsWith(SCOPE_PREFIX)
        val description = if (hasDescription) lines[0] else ""
        val header = listOf(
            DRIVER_PREFIX + driverOf(type, program, formatName),
            ENGINE_PREFIX + EngineBrands.brandOf(type, description),
        )
        val pos = if (hasDescription) 1 else 0
        return (lines.take(pos) + header + lines.drop(pos)).joinToString("\n")
    }
}
