/**
 *
 * @file
 *
 * @brief Visual data provider interface
 *
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.playback

interface Visualizer {
    /**
     * Get currently playing spectrum
     * @param levels array of current spectrum levels playing (96 max)
     * @return count of actually stored values in bands/levels (less or equal to levels.length)
     */
    @Throws(Exception::class)
    fun getSpectrum(levels: ByteArray): Int

    /**
     * Get currently playing waveforms, triggered for stable oscilloscope view
     * @param data array to store [channels][points] samples
     * @param points samples per channel
     * @return layout of stored data, see [ScopeLayout]: separate voices if supported, else single master channel
     */
    @Throws(Exception::class)
    fun getScope(data: ShortArray, points: Int): Int

    /**
     * Get chip state snapshots for currently playing moment (SID registers, see [ChipState])
     * @param data array to store [chips][records][ChipState.SIZE] bytes
     * @param records records per chip, last one is for currently heard moment, period is [ChipState.PERIOD_MS]
     * @return count of stored chips
     */
    @Throws(Exception::class)
    fun getChipStates(data: ByteArray, records: Int): Int

    /**
     * @return Multiline human readable emulation and performance information
     */
    @Throws(Exception::class)
    fun getStatus(): String
}

/**
 * Decoded result of [Visualizer.getScope]
 */
@JvmInline
value class ScopeLayout(private val packed: Int) {
    /** Count of stored channels */
    val channels
        get() = packed and 0xffff

    /** Channels count of each chip (voices are enumerated chip by chip), 0 for single master channel */
    val channelsPerChip
        get() = packed ushr 16

    companion object {
        @JvmStatic
        fun channelsOf(packed: Int) = ScopeLayout(packed).channels
    }
}

/**
 * Accessor to single record of [Visualizer.getChipStates] (SID registers snapshot)
 */
class ChipState(private val data: ByteArray, private val offset: Int) {
    private fun reg(idx: Int) = data[offset + idx].toInt() and 0xff

    fun frequency(voice: Int) = reg(voice * 7) or (reg(voice * 7 + 1) shl 8)
    fun pulseWidth(voice: Int) = reg(voice * 7 + 2) or ((reg(voice * 7 + 3) and 0x0f) shl 8)
    fun control(voice: Int) = reg(voice * 7 + 4)
    fun oscillator(voice: Int) = reg(OSC_OFFSET + voice)
    fun envelope(voice: Int) = reg(ENV_OFFSET + voice)
    val cutoff
        get() = (reg(0x15) and 7) or (reg(0x16) shl 3)
    val resonance
        get() = reg(0x17) shr 4
    val routing
        get() = reg(0x17) and 0x0f
    val mode
        get() = reg(0x18) shr 4
    val volume
        get() = reg(0x18) and 0x0f

    companion object {
        const val SIZE = 32
        const val PERIOD_MS = 4
        private const val OSC_OFFSET = 0x19
        private const val ENV_OFFSET = 0x1c
    }
}
