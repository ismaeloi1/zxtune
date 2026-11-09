/**
 * @file
 * @brief Human readable values of wavetable PCM chips slots (YMW258/MultiPCM, SCSP)
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import app.zxtune.playback.VoiceGauges
import app.zxtune.playback.VoiceGauges.Pcm
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Values of [VoiceGauges.Kind.PCM_SLOT] voice in units of hardware (Hz, ms, dB), computed the same way as emulation
 * cores of libvgm do (see multipcm.c and scsp.c).
 */
internal object PcmDescription {
    const val MAX_DB = 96f

    enum class Stage(val word: String, val short: String) {
        OFF("Off", "Off"),
        ATTACK("Attack", "Att"),
        DECAY1("Decay 1", "D1"),
        DECAY2("Decay 2", "D2"),
        RELEASE("Release", "Rel"),
    }

    fun isPcm(g: VoiceGauges) = g.kind == VoiceGauges.Kind.PCM_SLOT

    fun isScsp(g: VoiceGauges) = g.field(Pcm.CHIP) == Pcm.CHIP_SCSP

    fun chipName(g: VoiceGauges) = if (isScsp(g)) "SCSP" else "YMW258"

    fun isPlaying(g: VoiceGauges) = g.field(Pcm.STATE) and Pcm.ST_PLAYING != 0

    fun isKeyOn(g: VoiceGauges) = g.field(Pcm.STATE) and Pcm.ST_KEY != 0

    fun stage(g: VoiceGauges): Stage = if (isPlaying(g)) {
        Stage.entries.getOrElse(g.field(Pcm.STATE) and Pcm.STAGE_MASK) { Stage.OFF }
    } else {
        Stage.OFF
    }

    /** Envelope level in dB (0..-96) */
    fun envelopeDb(g: VoiceGauges) = if (isPlaying(g)) g.field(Pcm.EG) * MAX_DB / 255f - MAX_DB else -MAX_DB

    fun clockHz(g: VoiceGauges) = g.field16(Pcm.CLOCK).toLong() or (g.field16(Pcm.CLOCK + 2).toLong() shl 16)

    /** Output rate of chip, envelopes and LFO are clocked by it */
    fun outputRate(g: VoiceGauges) = g.field16(Pcm.RATE)

    fun octave(g: VoiceGauges) = g.signedField(Pcm.OCT)

    fun fnum(g: VoiceGauges) = g.field16(Pcm.FNUM) and 0x3ff

    /**
     * Playback rate of sample: octave and F-number scale output rate (YMW258 plays original rate at octave 1, see
     * update_step of multipcm.c; SCSP at octave 0)
     */
    fun playbackHz(g: VoiceGauges): Float {
        val exponent = octave(g) - if (isScsp(g)) 0 else 1
        return outputRate(g) * (1f + fnum(g) / 1024f) * 2f.pow(exponent)
    }

    /** Pitch relative to original rate of sample, semitones */
    fun shiftSemitones(g: VoiceGauges) = shiftSemitones(octave(g), fnum(g), isScsp(g))

    fun shiftSemitones(octave: Int, fnum: Int, scsp: Boolean): Float {
        val exponent = octave - if (scsp) 0 else 1
        return 12f * (exponent + log2(1f + fnum / 1024f))
    }

    /** Attenuation of total level, dB (0 or negative) */
    fun totalLevelDb(g: VoiceGauges): Float {
        val tl = g.field(Pcm.TL)
        return if (isScsp(g)) scspTlDb(tl) else -tl * 0.375f
    }

    fun scspTlDb(tl: Int): Float {
        var db = 0f
        for (bit in 0 until 8) {
            if (tl and (1 shl bit) != 0) {
                db -= SCSP_TL_BITS[bit]
            }
        }
        return db
    }

    /**
     * Pan as attenuations of left and right sides (dB, positive), [Float.POSITIVE_INFINITY] for silent side
     */
    fun panAttenuation(g: VoiceGauges, out: FloatArray) {
        val pan = g.field(Pcm.PAN)
        var left = 0f
        var right = 0f
        if (isScsp(g)) {
            // 0..15 attenuate left, 16..31 right; bits are 3, 6, 12, 24dB, 0x0f/0x1f is silence
            var db = 0f
            for (bit in 0 until 4) {
                if (pan and (1 shl bit) != 0) {
                    db += SCSP_PAN_BITS[bit]
                }
            }
            if (pan and 0x0f == 0x0f) {
                db = Float.POSITIVE_INFINITY
            }
            if (pan < 0x10) left = db else right = db
        } else {
            // 0 is center, 8 is muted, 1..7 attenuate left, 9..15 right by 3dB per step, 7 and 9 silence a side
            when {
                pan == 8 -> {
                    left = Float.POSITIVE_INFINITY
                    right = Float.POSITIVE_INFINITY
                }

                pan in 1..7 -> left = if (pan == 7) Float.POSITIVE_INFINITY else pan * 3f

                pan in 9..15 -> right = if (pan == 9) Float.POSITIVE_INFINITY else (16 - pan) * 3f
            }
        }
        out[0] = left
        out[1] = right
    }

    /** "Center", "L 9 dB" (left is louder by 9dB), "R only", "Muted" */
    fun panWord(g: VoiceGauges): String {
        val att = FloatArray(2)
        panAttenuation(g, att)
        val (left, right) = att[0] to att[1]
        return when {
            left.isInfinite() && right.isInfinite() -> Words.MUTED
            left == right -> Words.CENTER
            left.isInfinite() -> "R ${Words.ONLY}"
            right.isInfinite() -> "L ${Words.ONLY}"
            left > right -> "R ${dbNumber(left - right)} dB"
            else -> "L ${dbNumber(right - left)} dB"
        }
    }

    /** Direct output send level, dB (YMW258 has no send, it's always 0) */
    fun sendDb(g: VoiceGauges): Float {
        val level = g.field(Pcm.SEND) and 7
        return if (level == 0) Float.NEGATIVE_INFINITY else SEND_DB[level]
    }

    fun sendWord(level: Int) = if (level and 7 == 0) Words.OFF else "${dbNumber(SEND_DB[level and 7])} dB"

    /** Sample format: 8, 12 or 16 bits */
    fun bits(g: VoiceGauges) = when (g.field(Pcm.FORMAT) and 3) {
        1 -> 12
        2 -> 16
        else -> 8
    }

    fun loopMode(g: VoiceGauges) = (g.field(Pcm.FORMAT) shr 2) and 3

    fun source(g: VoiceGauges) = (g.field(Pcm.FORMAT) shr 4) and 3

    fun loopWord(g: VoiceGauges) = LOOP_WORDS[loopMode(g)]

    fun startAddress(g: VoiceGauges) = g.field16(Pcm.START) or (g.field(Pcm.START + 2) shl 16)

    fun loopStart(g: VoiceGauges) = g.field16(Pcm.LOOP)

    fun loopEnd(g: VoiceGauges) = g.field16(Pcm.END)

    fun position(g: VoiceGauges) = g.field16(Pcm.POS)

    fun tone(g: VoiceGauges) = g.field16(Pcm.TONE)

    /** Sample in words: "#123" tone of YMW258, start address of SCSP, noise or silence source */
    fun sampleWord(g: VoiceGauges) = when {
        !isScsp(g) -> "#${tone(g)}"
        source(g) == 1 -> Words.NOISE
        source(g) >= 2 -> Words.SILENCE
        else -> "$" + hex(startAddress(g), 5)
    }

    /** Mode in words: PCM, FM (phase modulated by other slots) or noise */
    fun modeWord(g: VoiceGauges) = when {
        isScsp(g) && source(g) == 1 -> Words.NOISE
        isScsp(g) && g.field(Pcm.MODE) and Pcm.MODE_FM != 0 && g.field(Pcm.MDL) >= 5 -> "FM"
        else -> "PCM"
    }

    /**
     * Modulation sources of SCSP slot as slot numbers (1-based) read from ring buffer (MDXSL/MDYSL are offsets from
     * the current slot), e.g. "S3+S4 ±4π"
     */
    fun modulationWord(g: VoiceGauges, slot: Int): String {
        val mdl = g.field(Pcm.MDL)
        if (!isScsp(g) || g.field(Pcm.MODE) and Pcm.MODE_FM == 0 || mdl < 5) {
            return Words.DASH
        }
        val x = (slot + g.field(Pcm.MDXSL)) % 32 + 1
        val y = (slot + g.field(Pcm.MDYSL)) % 32 + 1
        val sources = if (x == y) "S$x" else "S$x+S$y"
        return "$sources ${modulationDepth(mdl)}"
    }

    // MDL 5..15 is +-pi/16..+-64pi of phase
    fun modulationDepth(mdl: Int): String {
        val power = mdl - 9
        return if (power >= 0) "±${1 shl power}π" else "±π/${1 shl -power}"
    }

    /** DSP send of SCSP slot: "MIXS 3 −6 dB" */
    fun dspSendWord(g: VoiceGauges): String {
        val level = g.field(Pcm.IMXL) and 7
        return if (level == 0) Words.OFF else "MIXS ${g.field(Pcm.ISEL)} ${dbNumber(SEND_DB[level])} dB"
    }

    /** Effect return of DSP output with slot's number */
    fun effectWord(g: VoiceGauges, slot: Int): String {
        val level = g.field(Pcm.EFSDL) and 7
        return if (level == 0 || slot >= 18) Words.OFF else "${dbNumber(SEND_DB[level])} dB"
    }

    // --- envelope ---

    /** Rates of AR, D1R, D2R, RR after key scaling (0..63) */
    fun effectiveRate(g: VoiceGauges, idx: Int) = g.field(Pcm.RATES + idx) and 63

    /** Time of full attack (silence to maximum), ms, infinite for zero rate */
    fun attackMs(g: VoiceGauges) = rateMs(g, effectiveRate(g, 0), true)

    /** Time of full decay (96dB), ms, infinite for zero rate */
    fun decayMs(g: VoiceGauges, rate: Int) = rateMs(g, rate, false)

    private fun rateMs(g: VoiceGauges, rate: Int, attack: Boolean): Float {
        val rateHz = outputRate(g).takeIf { it > 0 } ?: 44100
        // tables are for 44100Hz, steps are per output sample
        val scale = 44100f / rateHz
        val ms = if (isScsp(g)) {
            if (attack) SCSP_AR_MS[rate] else SCSP_DR_MS[rate]
        } else if (rate < 4) {
            Float.POSITIVE_INFINITY
        } else if (attack) {
            if (rate == 63) 0f else YMW_TIMES_MS[rate]
        } else {
            YMW_TIMES_MS[rate] * YMW_DECAY_RATIO
        }
        return ms * scale
    }

    /** Sustain level (end of decay 1), dB (0 or negative) */
    fun sustainDb(g: VoiceGauges): Float {
        val dl = g.field(Pcm.DL)
        // YMW258 compares 4 high bits of 10 bits level, SCSP 5 high bits (see envelope_generator_update, EG_Update)
        return if (isScsp(g)) -dl * 3f else -dl * 6f
    }

    /**
     * Level at start of attack, dB: SCSP starts attack at 0x17F of 0x3FF in linear amplitude
     */
    fun attackStartDb(g: VoiceGauges) = if (isScsp(g)) SCSP_ATTACK_START_DB else -MAX_DB

    /** Decay 2 is endless at the sustain level */
    fun isSustained(g: VoiceGauges) = decayMs(g, effectiveRate(g, 2)).isInfinite()

    // --- LFO ---

    fun lfoHz(g: VoiceGauges): Float {
        val f = g.field(Pcm.LFOF)
        return if (isScsp(g)) SCSP_LFO_HZ[f and 31] else YMW_LFO_HZ[f and 7]
    }

    /** Vibrato depth, cents (peak) */
    fun vibratoCents(g: VoiceGauges): Float {
        val depth = g.field(Pcm.PLFO) and 7
        return if (isScsp(g)) SCSP_PM_CENTS[depth] else YMW_PM_CENTS[depth]
    }

    /** Tremolo depth, dB */
    fun tremoloDb(g: VoiceGauges): Float {
        val depth = g.field(Pcm.ALFO) and 7
        return if (isScsp(g)) SCSP_AM_DB[depth] else YMW_AM_DB[depth]
    }

    fun lfoWave(field: Int) = LFO_WAVES[(field shr 4) and 3]

    // --- words ---

    fun hzWord(hz: Float) = when {
        hz <= 0f -> Words.DASH
        hz >= 10000f -> String.format(Locale.US, "%.1f kHz", hz / 1000f)
        hz >= 1000f -> String.format(Locale.US, "%.2f kHz", hz / 1000f)
        hz >= 10f -> String.format(Locale.US, "%.1f Hz", hz)
        else -> String.format(Locale.US, "%.2f Hz", hz)
    }

    fun msWord(ms: Float) = when {
        ms.isInfinite() -> Words.INFINITE
        ms < 0.05f -> Words.INSTANT
        ms < 10f -> String.format(Locale.US, "%.1f ms", ms)
        ms < 1000f -> "${ms.roundToInt()} ms"
        ms < 10000f -> String.format(Locale.US, "%.2f s", ms / 1000f)
        else -> String.format(Locale.US, "%.1f s", ms / 1000f)
    }

    fun dbWord(db: Float) = when {
        db.isInfinite() || db <= -MAX_DB -> "−∞ dB"
        else -> "${dbNumber(db)} dB"
    }

    /** Number of dB with typographic minus, without trailing zero */
    fun dbNumber(db: Float): String {
        val rounded = (db * 10).roundToInt()
        val value = abs(rounded)
        val sign = if (rounded < 0) "−" else ""
        return if (value % 10 == 0) "$sign${value / 10}" else "$sign${value / 10}.${value % 10}"
    }

    fun semitonesWord(st: Float): String {
        val tenths = (st * 10).roundToInt()
        val sign = when {
            tenths < 0 -> "−"
            tenths > 0 -> "+"
            else -> ""
        }
        val value = abs(tenths)
        return "$sign${value / 10}.${value % 10} st"
    }

    fun hex(value: Int, digits: Int) = Integer.toHexString(value).uppercase(Locale.US).padStart(digits, '0')

    object Words {
        const val DASH = "—"
        const val ESTIMATED = "≈"
        const val OFF = "Off"
        const val ON = "On"
        const val CENTER = "Center"
        const val ONLY = "only"
        const val MUTED = "Muted"
        const val NOISE = "Noise"
        const val SILENCE = "Zero"
        const val INFINITE = "∞"
        const val INSTANT = "instant"
        const val SLOT = "Slot"
        const val SAMPLE = "Sample"
        const val NOTE = "Note"
        const val RATE = "Rate"
        const val SHIFT = "Shift"
        const val PITCH = "Oct/F-num"
        const val LEVEL = "Level"
        const val TL = "Total level"
        const val PAN = "Pan"
        const val STAGE = "Stage"
        const val MODE = "Mode"
        const val LOOP = "Loop"
        const val LOOP_START = "Loop start"
        const val FORMAT = "Format"
        const val LENGTH = "Length"
        const val POSITION = "Position"
        const val ATTACK = "Attack"
        const val DECAY1 = "Decay 1"
        const val SUSTAIN = "Sustain"
        const val DECAY2 = "Decay 2"
        const val RELEASE = "Release"
        const val KRS = "Key scale"
        const val LFO = "LFO"
        const val VIBRATO = "Vibrato"
        const val TREMOLO = "Tremolo"
        const val FM = "FM from"
        const val SEND = "Direct"
        const val DSP = "DSP send"
        const val EFFECT = "Effect out"
        const val CHIP = "Chip"
        const val CLOCK = "Clock"
        const val OUTPUT = "Output"
        const val PLAYING = "Playing"
        const val VOLUME = "Volume"
        const val EFFECTS = "Effects DSP"
        const val DAC = "DAC"
        const val MEMORY = "Memory"
        const val BANKS = "Banks"
        const val BYPASSED = "bypassed"
    }

    // multipcm.c BASE_TIMES: attack time of rate (ms at 44100Hz), decays are longer by ratio
    private val YMW_TIMES_MS = floatArrayOf(
        0f, 0f, 0f, 0f,
        6222.95f, 4978.37f, 4148.66f, 3556.01f, 3111.47f, 2489.21f, 2074.33f, 1778.00f,
        1555.74f, 1244.63f, 1037.19f, 889.02f, 777.87f, 622.31f, 518.59f, 444.54f,
        388.93f, 311.16f, 259.32f, 222.27f, 194.47f, 155.60f, 129.66f, 111.16f,
        97.23f, 77.82f, 64.85f, 55.60f, 48.62f, 38.91f, 32.43f, 27.80f,
        24.31f, 19.46f, 16.24f, 13.92f, 12.15f, 9.75f, 8.12f, 6.98f,
        6.08f, 4.90f, 4.08f, 3.49f, 3.04f, 2.49f, 2.13f, 1.90f,
        1.72f, 1.41f, 1.18f, 1.04f, 0.91f, 0.73f, 0.59f, 0.50f,
        0.45f, 0.45f, 0.45f, 0.45f,
    )
    private const val YMW_DECAY_RATIO = 14.32833f

    // scsp.c ARTimes/DRTimes in ms at 44100Hz, rates 0 and 1 are infinite
    private val SCSP_AR_MS = floatArrayOf(
        Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, 8100f, 6900f, 6000f, 4800f, 4000f, 3400f, 3000f, 2400f,
        2000f, 1700f, 1500f, 1200f, 1000f, 860f, 760f, 600f, 500f, 430f, 380f, 300f, 250f, 220f, 190f, 150f, 130f,
        110f, 95f, 76f, 63f, 55f, 47f, 38f, 31f, 27f, 24f, 19f, 15f, 13f, 12f, 9.4f, 7.9f, 6.8f, 6.0f, 4.7f, 3.8f,
        3.4f, 3.0f, 2.4f, 2.0f, 1.8f, 1.6f, 1.3f, 1.1f, 0.93f, 0.85f, 0.65f, 0.53f, 0.44f, 0.40f, 0.35f, 0f, 0f,
    )
    private val SCSP_DR_MS = floatArrayOf(
        Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, 118200f, 101300f, 88600f, 70900f, 59100f, 50700f, 44300f,
        35500f, 29600f, 25300f, 22200f, 17700f, 14800f, 12700f, 11100f, 8900f, 7400f, 6300f, 5500f, 4400f, 3700f,
        3200f, 2800f, 2200f, 1800f, 1600f, 1400f, 1100f, 920f, 790f, 690f, 550f, 460f, 390f, 340f, 270f, 230f, 200f,
        170f, 140f, 110f, 98f, 85f, 68f, 57f, 49f, 43f, 34f, 28f, 25f, 22f, 18f, 14f, 12f, 11f, 8.5f, 7.1f, 6.1f,
        5.4f, 4.3f, 3.6f, 3.1f,
    )

    // 20 * log10(0x17F / 0x3FF)
    private const val SCSP_ATTACK_START_DB = -8.54f

    private val SCSP_TL_BITS = floatArrayOf(0.4f, 0.8f, 1.5f, 3f, 6f, 12f, 24f, 48f)
    private val SCSP_PAN_BITS = floatArrayOf(3f, 6f, 12f, 24f)

    // SDLT of scsp.c
    private val SEND_DB = floatArrayOf(Float.NEGATIVE_INFINITY, -36f, -30f, -24f, -18f, -12f, -6f, 0f)

    private val YMW_LFO_HZ = floatArrayOf(0.168f, 2.019f, 3.196f, 4.206f, 5.215f, 5.888f, 6.224f, 7.066f)
    private val YMW_PM_CENTS = floatArrayOf(0f, 3.378f, 5.065f, 6.750f, 10.114f, 20.170f, 40.180f, 79.307f)
    private val YMW_AM_DB = floatArrayOf(0f, 0.4f, 0.8f, 1.5f, 3f, 6f, 12f, 24f)
    private val SCSP_LFO_HZ = floatArrayOf(
        0.17f, 0.19f, 0.23f, 0.27f, 0.34f, 0.39f, 0.45f, 0.55f, 0.68f, 0.78f, 0.92f, 1.10f, 1.39f, 1.60f, 1.87f, 2.27f,
        2.87f, 3.31f, 3.92f, 4.79f, 6.15f, 7.18f, 8.60f, 10.8f, 14.4f, 17.2f, 21.5f, 28.7f, 43.1f, 57.4f, 86.1f, 172.3f,
    )
    private val SCSP_PM_CENTS = floatArrayOf(0f, 7f, 13.5f, 27f, 55f, 112f, 230f, 494f)
    private val SCSP_AM_DB = YMW_AM_DB
    private val LFO_WAVES = arrayOf("saw", "square", "triangle", "noise")
    private val LOOP_WORDS = arrayOf("once", "forward", "reverse", "ping-pong")
}

/**
 * Envelope program of slot as levels (dB) at stage boundaries and durations (ms) of stages, the same way as
 * emulation computes it: attack to 0dB, decay 1 to sustain level, decay 2 to silence (or endless), release from the
 * current level to silence.
 */
internal class PcmEnvelope {
    var startDb = -PcmDescription.MAX_DB
        private set
    var sustainDb = 0f
        private set
    var attackMs = 0f
        private set
    var decay1Ms = 0f
        private set
    var decay2Ms = 0f
        private set

    /** Time of full release (96dB) */
    var releaseFullMs = 0f
        private set

    /** SCSP attack is linear in amplitude, so it's a curve in dB */
    var isLinearAttack = false
        private set

    fun update(g: VoiceGauges) {
        isLinearAttack = PcmDescription.isScsp(g)
        startDb = PcmDescription.attackStartDb(g)
        sustainDb = PcmDescription.sustainDb(g)
        // SCSP starts attack from 0x17F of 0x3FF envelope steps
        attackMs = part(PcmDescription.attackMs(g), if (PcmDescription.isScsp(g)) (0x3FF - 0x17F) / 1023f else 1f)
        decay1Ms = part(PcmDescription.decayMs(g, PcmDescription.effectiveRate(g, 1)), -sustainDb / PcmDescription.MAX_DB)
        decay2Ms = part(
            PcmDescription.decayMs(g, PcmDescription.effectiveRate(g, 2)),
            (PcmDescription.MAX_DB + sustainDb) / PcmDescription.MAX_DB,
        )
        releaseFullMs = PcmDescription.decayMs(g, PcmDescription.effectiveRate(g, 3))
    }

    // no time is needed to pass zero distance even at infinite rate
    private fun part(fullMs: Float, part: Float) = if (part <= 0f) 0f else fullMs * part

    /** Time to reach [db] from start of [stage] (1 attack, 2 decay 1, 3 decay 2) */
    fun timeInStage(stage: Int, db: Float): Float {
        fun along(from: Float, to: Float, ms: Float) = if (ms.isInfinite() || from == to) 0f else ms * ((db - from) / (to - from)).coerceIn(0f, 1f)
        return when (stage) {
            1 -> if (isLinearAttack) {
                val from = amplitude(startDb)
                if (attackMs.isInfinite()) 0f else attackMs * ((amplitude(db) - from) / (1f - from)).coerceIn(0f, 1f)
            } else {
                along(startDb, 0f, attackMs)
            }

            2 -> along(0f, sustainDb, decay1Ms)

            3 -> along(sustainDb, -PcmDescription.MAX_DB, decay2Ms)

            else -> 0f
        }
    }

    /** Level of attack at [part] (0..1) of its time */
    fun attackDbAt(part: Float): Float = if (isLinearAttack) {
        val from = amplitude(startDb)
        decibels(from + (1f - from) * part)
    } else {
        startDb * (1f - part)
    }

    private companion object {
        fun amplitude(db: Float) = 10f.pow(db / 20f)
        fun decibels(amplitude: Float) = maxOf(20f * log10(amplitude), -PcmDescription.MAX_DB)
    }
}
