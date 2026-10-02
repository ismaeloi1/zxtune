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
