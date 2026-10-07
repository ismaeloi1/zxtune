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
     * @param windowMs displayed duration of waveforms (corrscope's render_ms)
     * @return layout of stored data, see [ScopeLayout]: separate voices if supported, else single master channel
     */
    @Throws(Exception::class)
    fun getScope(data: ShortArray, points: Int, windowMs: Int): Int

    /**
     * Get JSIDPlay2-like gauges of chips state history ending at currently heard moment
     * @param data array to store [chips][ChipGauges.SIZE] bytes
     * @param waveWindowMs displayed duration of oscillators and volume gauges
     * @return count of stored chips
     */
    @Throws(Exception::class)
    fun getGauges(data: ByteArray, waveWindowMs: Int): Int

    /**
     * Get gauges of voices state history ending at currently heard moment, for chips without SID-like registers
     * @param data array to store [voices][VoiceGauges.SIZE] bytes
     * @param waveWindowMs displayed duration of wave gauge
     * @return count of stored voices
     */
    @Throws(Exception::class)
    fun getVoiceGauges(data: ByteArray, waveWindowMs: Int): Int

    /**
     * @return voices layout description, see [VoicesLayout]. Changes are signalled via [ScopeLayout.id]
     */
    @Throws(Exception::class)
    fun getLayout(): String

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

    /** Voices layout version, see [Visualizer.getLayout] */
    val id
        get() = packed ushr 16

    companion object {
        @JvmStatic
        fun channelsOf(packed: Int) = ScopeLayout(packed).channels
    }
}

/**
 * Decoded result of [Visualizer.getLayout]
 */
class VoicesLayout private constructor(
    /** SID-like chips with registers state, see [Visualizer.getGauges], else [Visualizer.getVoiceGauges] */
    val hasRegisters: Boolean,
    val groups: List<Group>,
) {
    class Group(val name: String, val voices: List<String>)

    val voicesCount
        get() = groups.sumOf { it.voices.size }

    companion object {
        /** Single master channel */
        val MASTER = VoicesLayout(false, emptyList())

        fun parse(description: String): VoicesLayout {
            val lines = description.lines().filter { it.isNotEmpty() }
            if (lines.size < 2) {
                return MASTER
            }
            val groups = lines.drop(1).map { line ->
                val fields = line.split('\t')
                Group(fields.first(), fields.drop(1))
            }
            return VoicesLayout(lines.first() == "registers", groups)
        }
    }
}

/**
 * History of values by columns
 */
interface GaugesData {
    /**
     * @return min/max level (0..255) of gauge column
     */
    fun min(gauge: Int, column: Int): Int
    fun max(gauge: Int, column: Int): Int
}

/**
 * Accessor to single voice data of [Visualizer.getVoiceGauges]
 */
class VoiceGauges(private val data: ByteArray, private val offset: Int) : GaugesData {
    override fun min(gauge: Int, column: Int) = data[offset + (gauge * COLUMNS + column) * 2].toInt() and 0xff
    override fun max(gauge: Int, column: Int) = data[offset + (gauge * COLUMNS + column) * 2 + 1].toInt() and 0xff

    private fun float(at: Int) = Float.fromBits(
        (data[at].toInt() and 0xff) or ((data[at + 1].toInt() and 0xff) shl 8) or
            ((data[at + 2].toInt() and 0xff) shl 16) or ((data[at + 3].toInt() and 0xff) shl 24)
    )

    private val stateOffset
        get() = offset + GAUGES * COLUMNS * 2

    /** Hz, valid if [hasFrequency] */
    val frequency
        get() = float(stateOffset)

    /** dB, 0 or negative, valid if [hasLevel] */
    val level
        get() = float(stateOffset + 4)

    private val flags
        get() = data[stateOffset + 8].toInt()

    val isKeyOn
        get() = 0 != (flags and 1)
    val hasFrequency
        get() = 0 != (flags and 2)
    val hasLevel
        get() = 0 != (flags and 4)
    val isNoise
        get() = 0 != (flags and 8)

    /** Chip specific voice kind, see [Kind] */
    val kind
        get() = data[stateOffset + 9].toInt() and 0xff

    /** Chip specific field, meaning depends on [kind] (see Module::VoiceState) */
    fun field(idx: Int) = data[stateOffset + 10 + idx].toInt() and 0xff

    /** Signed chip specific field */
    fun signedField(idx: Int) = data[stateOffset + 10 + idx].toInt()

    /** Text of voice (e.g. instrument name), empty if not provided */
    val text: String
        get() {
            val start = stateOffset + 10 + FIELDS
            var end = start
            while (end < start + TEXT && data[end].toInt() != 0) {
                ++end
            }
            return String(data, start, end - start, Charsets.ISO_8859_1)
        }

    /**
     * Mirrors Module::VoiceState::Kind
     */
    object Kind {
        const val GENERIC = 0
        const val OPN_FM = 1
        const val OPN_DAC = 2
        const val PSG_TONE = 3
        const val PSG_NOISE = 4
        const val OPL_2OP = 5
        const val OPL_4OP = 6
        const val OPL_RHYTHM = 7
        const val SPC_DSP = 8
        const val MT32_PART = 9
        const val MT32_PARTIAL = 10
        const val NES_PULSE = 11
        const val NES_TRIANGLE = 12
        const val NES_NOISE = 13
        const val NES_DMC = 14
    }

    companion object {
        // see Sound::Scope::VOICE_GAUGES_SIZE
        const val GAUGES = 3
        const val COLUMNS = 256
        private const val FIELDS = 12
        private const val TEXT = 16
        const val SIZE = GAUGES * COLUMNS * 2 + 48

        const val WAVE = 0
        const val LEVEL = 1
        const val FREQUENCY = 2
    }
}

/**
 * Accessor to single chip data of [Visualizer.getGauges]
 */
class ChipGauges(private val data: ByteArray, private val offset: Int) : GaugesData {
    override fun min(gauge: Int, column: Int) = data[offset + (gauge * COLUMNS + column) * 2].toInt() and 0xff
    override fun max(gauge: Int, column: Int) = data[offset + (gauge * COLUMNS + column) * 2 + 1].toInt() and 0xff

    /**
     * Last registers snapshot
     */
    val state
        get() = ChipState(data, offset + GAUGES * COLUMNS * 2)

    companion object {
        // see Sound::Scope::GAUGES_SIZE
        const val GAUGES = 12
        const val COLUMNS = 256
        const val SIZE = GAUGES * COLUMNS * 2 + ChipState.SIZE

        // wave and volume columns are 128 CPU cycles, others are 16384 cycles
        fun wave(voice: Int) = voice
        fun envelope(voice: Int) = 3 + voice
        fun frequency(voice: Int) = 6 + voice
        const val VOLUME = 9
        const val RESONANCE = 10
        const val CUTOFF = 11
    }
}

/**
 * Accessor to SID registers snapshot
 */
class ChipState(private val data: ByteArray, private val offset: Int) {
    private fun reg(idx: Int) = data[offset + idx].toInt() and 0xff

    fun frequency(voice: Int) = reg(voice * 7) or (reg(voice * 7 + 1) shl 8)
    fun pulseWidth(voice: Int) = reg(voice * 7 + 2) or ((reg(voice * 7 + 3) and 0x0f) shl 8)
    fun control(voice: Int) = reg(voice * 7 + 4)

    /**
     * Attack/Decay and Sustain/Release registers as ADSR hex digits
     */
    fun envelopeRegs(voice: Int) = String.format(
        java.util.Locale.US,
        "%02X%02X",
        reg(voice * 7 + 5),
        reg(voice * 7 + 6)
    )
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
        private const val OSC_OFFSET = 0x19
        private const val ENV_OFFSET = 0x1c
    }
}
