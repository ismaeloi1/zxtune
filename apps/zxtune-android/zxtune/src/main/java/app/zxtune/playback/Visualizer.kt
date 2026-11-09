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
     * Waveforms (see [getScope]) and gauges (see [getGauges] and [getVoiceGauges]) of the same moment by single
     * request, so all the views display the same moment and remote visualizer is called once per frame
     * @param gauges array for gauges data, not used if [gaugesKind] is [FrameResult.GAUGES_NONE]
     * @param gaugesKind one of FrameResult.GAUGES_* values
     * @return packed result, see [FrameResult]
     */
    @Throws(Exception::class)
    fun getFrame(data: ShortArray, points: Int, windowMs: Int, gauges: ByteArray, gaugesKind: Int, waveWindowMs: Int): Long

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
 * Decoded result of [Visualizer.getFrame]
 */
@JvmInline
value class FrameResult(private val packed: Long) {
    val scope
        get() = ScopeLayout(packed.toInt())

    /** Count of stored chips or voices */
    val gauges
        get() = (packed ushr 32).toInt()

    companion object {
        const val GAUGES_NONE = 0
        const val GAUGES_CHIPS = 1
        const val GAUGES_VOICES = 2

        @JvmStatic
        fun pack(scope: Int, gauges: Int) = (scope.toLong() and 0xffffffffL) or (gauges.toLong() shl 32)

        /**
         * Implementation by separate requests, e.g. for visualizers without shared memory
         */
        @JvmStatic
        fun request(
            src: Visualizer,
            data: ShortArray,
            points: Int,
            windowMs: Int,
            gauges: ByteArray,
            gaugesKind: Int,
            waveWindowMs: Int,
        ): Long {
            val scope = src.getScope(data, points, windowMs)
            val count = when (gaugesKind) {
                GAUGES_CHIPS -> src.getGauges(gauges, waveWindowMs)
                GAUGES_VOICES -> src.getVoiceGauges(gauges, waveWindowMs)
                else -> 0
            }
            return pack(scope, count)
        }
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

    /** Little endian 16 bit chip specific field */
    fun field16(idx: Int) = field(idx) or (field(idx + 1) shl 8)

    /** Progress (0..1) of the last level and frequency column, see [ChipGauges.phase] */
    val phase
        get() = (data[offset + SIZE - 1].toInt() and 0xff) / 256f

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
        const val PCM_SLOT = 15
    }

    /**
     * Fields of [Kind.PCM_SLOT] (YMW258/MultiPCM and SCSP slots), mirrors Module::VoiceState::PCM_*.
     * Raw registers are in units of [CHIP], multibyte values are little endian.
     */
    object Pcm {
        /** [CHIP_YMW258] or [CHIP_SCSP] */
        const val CHIP = 0

        /** envelope stage (see [STAGE_MASK]: 0 off, 1 attack, 2 decay 1, 3 decay 2, 4 release) | ST_* flags */
        const val STATE = 1

        /** envelope level 0..255 of 96dB */
        const val EG = 2

        /** 16 bit tone (sample header) number, YMW258 only */
        const val TONE = 3

        /** 24 bit start address in bytes */
        const val START = 5

        /** 16 bit loop start, loop end (sample length) and position in samples */
        const val LOOP = 8
        const val END = 10
        const val POS = 12

        /** bits 0..1: 0 8 bit, 1 12 bit, 2 16 bit; bits 2..3 loop mode; bits 4..5 source (memory, noise, silence) */
        const val FORMAT = 14

        /** signed octave */
        const val OCT = 15

        /** 16 bit F-number (10 bits) */
        const val FNUM = 16

        /** YMW258 0..127 (0.375dB), SCSP 0..255 (bits of 0.4..48dB) */
        const val TL = 18

        /** YMW258 0..15 (0 center, 1..7 right louder, 9..15 left louder, 8 muted), SCSP DIPAN 0..31 */
        const val PAN = 19

        /** direct send level 0..7 */
        const val SEND = 20
        const val AR = 21
        const val D1R = 22
        const val DL = 23
        const val D2R = 24
        const val RR = 25
        const val KRS = 26

        /** 4 bytes of effective rates 0..63 of AR, D1R, D2R, RR (0 is infinite) */
        const val RATES = 27
        const val LFOF = 31

        /** depth 0..7 | waveform 0..3 shl 4 */
        const val PLFO = 32
        const val ALFO = 33

        /** MODE_* flags */
        const val MODE = 34
        const val MDL = 35
        const val MDXSL = 36
        const val MDYSL = 37
        const val ISEL = 38
        const val IMXL = 39
        const val EFSDL = 40
        const val EFPAN = 41
        const val SBCTL = 42

        /** chip-wide: 32 bit clock, 16 bit output rate, master volume 0..15, GL_* flags, banks, playing slots */
        const val CLOCK = 48
        const val RATE = 52
        const val MVOL = 54
        const val GLOBAL = 55
        const val BANK0 = 56
        const val BANK1 = 58
        const val ACTIVE = 60

        const val CHIP_YMW258 = 1
        const val CHIP_SCSP = 2

        const val STAGE_MASK = 0x07
        const val ST_KEY = 0x08
        const val ST_PLAYING = 0x10
        const val ST_REVERSE = 0x20
        const val ST_LOOPED = 0x40

        const val MODE_FM = 0x01
        const val MODE_NOTRING = 0x02
        const val MODE_DIRECT = 0x04
        const val MODE_EGHOLD = 0x08
        const val MODE_LPSLNK = 0x10
        const val MODE_INTERP = 0x20

        const val GL_DSP = 0x01
        const val GL_DAC18B = 0x02
        const val GL_MEM4MB = 0x04
        const val GL_BANKING = 0x10
    }

    companion object {
        // see Sound::Scope::VOICE_GAUGES_SIZE
        const val GAUGES = 3
        const val COLUMNS = 256
        const val FIELDS = 64
        private const val TEXT = 16
        const val SIZE = GAUGES * COLUMNS * 2 + 96

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

    /**
     * Progress (0..1) of the last column of slow gauges (envelope, frequency, resonance, cutoff). Columns are aligned
     * to absolute time, so history is scrolled by this part of column for smooth movement.
     */
    val phase
        get() = (data[offset + SIZE - 1].toInt() and 0xff) / 256f

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

        fun isSlow(gauge: Int) = gauge in 3..8 || gauge == RESONANCE || gauge == CUTOFF
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
    fun attackDecay(voice: Int) = reg(voice * 7 + 5)
    fun sustainRelease(voice: Int) = reg(voice * 7 + 6)

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
