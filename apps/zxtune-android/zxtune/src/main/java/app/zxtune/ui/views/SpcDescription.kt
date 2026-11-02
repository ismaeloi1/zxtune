/**
 * @file
 * @brief S-DSP (SNES SPC700 sound) registers in human readable form
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import app.zxtune.playback.VoiceGauges
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Meaning of S-DSP registers, as emulated by snes_spc (see SPC_DSP.cpp), so that displayed times and levels are what
 * the emulator really does and not approximations from documentation tables.
 */
internal object SpcDescription {
    const val SAMPLE_RATE = 32000
    const val MAX_ENV = 0x7ff
    const val VOICES = 8

    // samples between envelope (and noise) steps per rate, 0 - never, see counter_mask in SPC_DSP.cpp
    private val RATE_PERIODS = intArrayOf(
        0, 2048, 1536, 1280, 1024, 768, 640, 512, 384, 320, 256, 192, 160, 128, 96, 80,
        64, 48, 40, 32, 24, 20, 16, 12, 10, 8, 6, 5, 4, 3, 2, 1
    )

    fun ratePeriod(rate: Int) = RATE_PERIODS[rate and 31]

    // native VoiceState::SPC_DSP envelope mode field
    const val MODE_RELEASE = 0
    const val MODE_ATTACK = 1
    const val MODE_DECAY = 2
    const val MODE_SUSTAIN = 3
    const val MODE_GAIN = 4

    enum class Stage(val short: String, val word: String) {
        RELEASE("Rel", "Release"),
        ATTACK("Atk", "Attack"),
        DECAY("Dec", "Decay"),
        SUSTAIN("Sus", "Sustain"),
        GAIN("Gain", "Gain");

        companion object {
            fun of(mode: Int) = values().getOrElse(mode) { RELEASE }
        }
    }

    fun isAdsr(adsr1: Int) = adsr1 and 0x80 != 0
    fun attackRate(adsr1: Int) = (adsr1 and 15) * 2 + 1
    fun decayRate(adsr1: Int) = ((adsr1 shr 4) and 7) * 2 + 16

    /** Decay ends when envelope reaches (SL + 1) / 8 */
    fun sustainLevel(adsr2: Int) = adsr2 shr 5
    fun sustainPercent(adsr2: Int) = (sustainLevel(adsr2) + 1) * 100 / 8
    fun sustainRate(adsr2: Int) = adsr2 and 31

    // one step of exponential decrease (decay, sustain and GAIN mode 5)
    fun expStep(env: Int) = (env - 1).let { it - (it shr 8) }

    /** Release (key off or end of sample) is fixed linear decrease by 8 per sample: 256 samples from full level */
    const val RELEASE_SAMPLES = (MAX_ENV + 1) / 8

    fun envelopePercent(envx: Int) = envx * 100 / 127

    // GAIN register
    fun isDirectGain(gain: Int) = gain and 0x80 == 0
    fun gainMode(gain: Int) = (gain shr 5) and 3
    fun gainRate(gain: Int) = gain and 31
    const val GAIN_LINEAR_DOWN = 0
    const val GAIN_EXP_DOWN = 1
    const val GAIN_LINEAR_UP = 2
    const val GAIN_BENT_UP = 3

    fun gainWord(gain: Int) = when {
        isDirectGain(gain) -> Words.DIRECT
        gainRate(gain) == 0 -> Words.HOLD
        else -> Words.GAIN_MODES[gainMode(gain)]
    }

    /** Pitch register: 0x1000 plays sample at 32kHz, i.e. at its own rate if it's recorded so */
    fun rateHz(pitch: Int) = pitch * SAMPLE_RATE / 4096f

    /** Transposition relative to sample's own rate, semitones */
    fun shiftSemitones(pitch: Int) = 12 * log2(pitch / 4096f)

    /** Frequency of voice gauge column value (log scale of 7 octaves from A0, see Sound::Scope::GetVoiceGauges) */
    fun gaugeHz(value: Int) = if (value == 0) 0f else 27.5f * Math.pow(2.0, value * 7.0 / 255).toFloat()

    fun noiseHz(flg: Int) = ratePeriod(flg and 31).let { if (it == 0) 0f else SAMPLE_RATE.toFloat() / it }

    fun volumePercent(signed: Int) = (signed * 100f / 128).roundToInt()

    /** EDL 0 still delays by 4 bytes buffer (1 stereo sample) */
    fun echoDelayMs(edl: Int) = if (edl and 15 == 0) 0f else (edl and 15) * 16f
    fun echoBufferBytes(edl: Int) = if (edl and 15 == 0) 4 else (edl and 15) * 2048

    /**
     * @param fir 8 signed coefficients (1.0 is 128)
     * @return magnitude of FIR response at [hz]
     */
    fun firGain(fir: IntArray, hz: Float): Float {
        val w = 2 * Math.PI * hz / SAMPLE_RATE
        var re = 0.0
        var im = 0.0
        for (k in fir.indices) {
            re += fir[k] * cos(w * k)
            im -= fir[k] * sin(w * k)
        }
        return (sqrt(re * re + im * im) / 128).toFloat()
    }

    fun db(gain: Float) = if (gain <= 0.00001f) -100f else 20 * log10(gain)

    fun samplesToMs(samples: Int) = samples * 1000f / SAMPLE_RATE

    /** Duration with precision enough to compare rate values, units are spaced as in SID cells */
    fun durationWord(samples: Int): String {
        val ms = samplesToMs(samples)
        return when {
            ms < 1f -> "<1 ms"
            ms < 10f -> "%.1f ms".format(java.util.Locale.US, ms)
            ms < 1000f -> "${ms.roundToInt()} ms"
            ms < 10000f -> "%.1f s".format(java.util.Locale.US, ms / 1000)
            else -> "${(ms / 1000).roundToInt()} s"
        }
    }

    fun hzWord(hz: Float) = when {
        hz <= 0f -> Words.OFF
        hz >= 10000f -> "${(hz / 1000).roundToInt()} kHz"
        hz >= 1000f -> "%.1f kHz".format(java.util.Locale.US, hz / 1000)
        else -> "${hz.roundToInt()} Hz"
    }

    fun signedPercent(value: Int) = when {
        value > 0 -> "+$value%"
        value < 0 -> "−${-value}%"
        else -> "0%"
    }

    fun dbWord(db: Float) = when {
        db <= -99f -> "−∞ dB"
        db >= 0.05f -> "+%.1f dB".format(java.util.Locale.US, db)
        db <= -0.05f -> "−%.1f dB".format(java.util.Locale.US, -db)
        else -> "0.0 dB"
    }

    fun hex2(value: Int) = HEX[(value shr 4) and 15].toString() + HEX[value and 15]

    private const val HEX = "0123456789ABCDEF"

    /**
     * Words displayed to user, collected in one place to be localized later
     */
    object Words {
        const val SAMPLE = "Sample"
        const val LEVEL = "Level"
        const val STAGE = "Stage"
        const val NOTE = "Note"
        const val RATE = "Rate"
        const val SHIFT = "Shift"
        const val VOL_L = "Vol L"
        const val VOL_R = "Vol R"
        const val ECHO = "Echo"
        const val MOD = "Mod"
        const val ATTACK = "Attack"
        const val DECAY = "Decay"
        const val SUSTAIN = "Sustain"
        const val FADE = "Fade"
        const val RAMP = "Ramp"
        const val TARGET = "Target"
        const val LENGTH = "Length"
        const val MAIN_L = "Main L"
        const val MAIN_R = "Main R"
        const val ECHO_L = "Echo L"
        const val ECHO_R = "Echo R"
        const val DELAY = "Delay"
        const val FEEDBACK = "Feedback"
        const val ECHO_WRITE = "Echo write"
        const val NOISE_CLOCK = "Noise clock"
        const val OUTPUT = "Output"
        const val FIR = "FIR"
        const val ECHO_RAM = "Echo RAM"
        const val DIR = "Samples dir"

        const val LOOP = "loop"
        const val ONCE = "once"
        const val ON = "On"
        const val OFF = "Off"
        const val NOISE = "Noise"
        const val PMOD = "PMod"
        const val MUTED = "Muted"
        const val RESET = "Reset"
        const val INSTANT = "instant"
        const val INFINITE = "∞"
        const val DIRECT = "Direct"
        const val HOLD = "Hold"
        const val DASH = "—"
        const val ESTIMATED = "≈"
        val GAIN_MODES = arrayOf("Linear ↓", "Exp ↓", "Linear ↑", "Bent ↑")
    }
}

/**
 * Envelope program of a voice: levels at each envelope step from key on, as S-DSP does them with current registers.
 * Rebuilt only on registers change. Used both to draw the curve on time axis and to place current level on it.
 */
internal class SpcEnvelope {
    val times = IntArray(MAX_POINTS)
    val levels = IntArray(MAX_POINTS)
    var count = 0
        private set

    /** First point of attack, decay, sustain stages and the end, for ADSR. Ramp of GAIN mode is the only stage */
    val stageStart = IntArray(4)
    var isAdsr = false
        private set

    /** Last level is kept forever (sustain rate 0, GAIN hold or direct) */
    var isEndless = false
        private set

    /** Displayed duration, samples */
    var duration = 1
        private set
    var attackSamples = 0
        private set
    var decaySamples = 0
        private set

    /** Time from sustain start to silence, or -1 if never */
    var fadeSamples = -1
        private set

    /** Full scale ramp of GAIN mode, or -1 if level is not changed */
    var rampSamples = -1
        private set
    private var key = Long.MIN_VALUE

    /**
     * @param env current envelope (0..0x7FF) to keep for GAIN hold
     */
    fun update(adsr1: Int, adsr2: Int, gain: Int, env: Int) {
        val adsr = SpcDescription.isAdsr(adsr1)
        val hold = !adsr && !SpcDescription.isDirectGain(gain) && SpcDescription.gainRate(gain) == 0
        val newKey = if (adsr) {
            (1L shl 32) or (adsr1.toLong() shl 8) or adsr2.toLong()
        } else {
            (2L shl 32) or (gain.toLong() shl 12) or (if (hold) env.toLong() else 0L)
        }
        if (newKey == key) {
            return
        }
        key = newKey
        count = 0
        isAdsr = adsr
        isEndless = false
        fadeSamples = -1
        rampSamples = -1
        if (adsr) {
            buildAdsr(adsr1, adsr2)
        } else {
            buildGain(gain, env)
        }
        duration = maxOf(times[count - 1], 1)
    }

    private fun add(time: Int, level: Int) {
        if (count < MAX_POINTS) {
            times[count] = time
            levels[count] = level
            ++count
        }
    }

    private fun buildAdsr(adsr1: Int, adsr2: Int) {
        var t = 0
        var env = 0
        stageStart[0] = 0
        add(0, 0)
        // attack: linear increase, the fastest rate jumps by half of range per sample
        val attackRate = SpcDescription.attackRate(adsr1)
        val attackPeriod = SpcDescription.ratePeriod(attackRate)
        val attackStep = if (attackRate < 31) 0x20 else 0x400
        while (env < SpcDescription.MAX_ENV) {
            env = minOf(env + attackStep, SpcDescription.MAX_ENV)
            t += attackPeriod
            add(t, env)
        }
        attackSamples = t
        stageStart[1] = count - 1
        // decay: exponential, switches to sustain as soon as the next step reaches sustain level
        val sl = SpcDescription.sustainLevel(adsr2)
        val decayPeriod = SpcDescription.ratePeriod(SpcDescription.decayRate(adsr1))
        var guard = 0
        while (SpcDescription.expStep(env) shr 8 != sl && ++guard < MAX_STEPS) {
            env = SpcDescription.expStep(env)
            t += decayPeriod
            add(t, env)
        }
        decaySamples = t - attackSamples
        stageStart[2] = count - 1
        val sustainPeriod = SpcDescription.ratePeriod(SpcDescription.sustainRate(adsr2))
        if (sustainPeriod == 0) {
            isEndless = true
            // plateau is shown as long as attack and decay, but not shorter than 0.1s
            t += maxOf(t, SpcDescription.SAMPLE_RATE / 10)
            add(t, env)
        } else {
            val start = t
            while (env > 0) {
                env = maxOf(SpcDescription.expStep(env), 0)
                t += sustainPeriod
                add(t, env)
            }
            fadeSamples = t - start
        }
        stageStart[3] = count - 1
    }

    private fun buildGain(gain: Int, current: Int) {
        stageStart.fill(0)
        if (SpcDescription.isDirectGain(gain)) {
            // level is set immediately
            val level = (gain and 0x7f) * 0x10
            isEndless = true
            add(0, level)
            add(SpcDescription.SAMPLE_RATE / 10, level)
            rampSamples = 0
            stageStart[3] = count - 1
            return
        }
        val period = SpcDescription.ratePeriod(SpcDescription.gainRate(gain))
        if (period == 0) {
            isEndless = true
            add(0, current)
            add(SpcDescription.SAMPLE_RATE / 10, current)
            stageStart[3] = count - 1
            return
        }
        val mode = SpcDescription.gainMode(gain)
        var t = 0
        var env = if (mode == SpcDescription.GAIN_LINEAR_DOWN || mode == SpcDescription.GAIN_EXP_DOWN) SpcDescription.MAX_ENV else 0
        add(0, env)
        var guard = 0
        while (++guard < MAX_STEPS) {
            env = when (mode) {
                SpcDescription.GAIN_LINEAR_DOWN -> env - 0x20

                SpcDescription.GAIN_EXP_DOWN -> SpcDescription.expStep(env)

                // two slopes: slower above 3/4 of range
                SpcDescription.GAIN_BENT_UP -> env + if (env >= 0x600) 8 else 0x20

                else -> env + 0x20
            }.coerceIn(0, SpcDescription.MAX_ENV)
            t += period
            add(t, env)
            if (env == 0 || env == SpcDescription.MAX_ENV) {
                break
            }
        }
        rampSamples = t
        stageStart[3] = count - 1
    }

    /**
     * Time from the stage start where current [env] is on the program, assuming registers weren't changed since key on.
     * @param stage index in [stageStart] (0 for GAIN ramp)
     * @return samples from key on or -1 if it's not on the program
     */
    fun timeOf(stage: Int, env: Int): Float {
        if (count < 2 || stage !in 0..2) {
            return -1f
        }
        val from = stageStart[stage]
        val to = if (isAdsr) stageStart[stage + 1] else count - 1
        if (from >= to) {
            return times[from].toFloat()
        }
        val rising = levels[to] > levels[from]
        for (idx in from + 1..to) {
            val prev = levels[idx - 1]
            val cur = levels[idx]
            if (if (rising) cur >= env else cur <= env) {
                val part = if (cur == prev) 0f else ((env - prev).toFloat() / (cur - prev)).coerceIn(0f, 1f)
                return times[idx - 1] + (times[idx] - times[idx - 1]) * part
            }
        }
        return times[to].toFloat()
    }

    /** Level (0..0x7FF) at [time] samples from key on, linear between steps */
    fun levelAt(time: Float): Float {
        if (count == 0) {
            return 0f
        }
        if (time >= times[count - 1]) {
            return levels[count - 1].toFloat()
        }
        // binary search of the first point after time
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (times[mid] <= time) lo = mid + 1 else hi = mid
        }
        if (lo == 0) {
            return levels[0].toFloat()
        }
        val t0 = times[lo - 1]
        val t1 = times[lo]
        val part = if (t1 == t0) 1f else (time - t0) / (t1 - t0)
        return levels[lo - 1] + (levels[lo] - levels[lo - 1]) * part
    }

    /** Stage index (see [stageStart]) at [time] */
    fun stageAt(time: Float): Int {
        if (!isAdsr) {
            return 0
        }
        for (stage in 0 until 3) {
            if (time < times[stageStart[stage + 1]]) {
                return stage
            }
        }
        return 2
    }

    private companion object {
        // attack 64 + decay <600 + sustain <800 steps
        const val MAX_POINTS = 2048
        const val MAX_STEPS = 4096
    }
}

/**
 * Global registers of S-DSP, carried by binary text of the first voices (see Module::VoiceState::SPC_DSP)
 */
internal class SpcGlobals {
    private val regs = IntArray(24)
    val fir = IntArray(8)

    fun update(data: ByteArray, voices: Int) {
        for (idx in regs.indices) {
            val voice = idx / 8
            regs[idx] = if (voice < voices) VoiceGauges(data, voice * VoiceGauges.SIZE).textByte(8 + idx % 8) else 0
        }
        for (idx in 0 until 8) {
            fir[idx] = regs[8 + idx].toByte().toInt()
        }
    }

    private fun signed(idx: Int) = regs[idx].toByte().toInt()

    val mainLeft get() = signed(0)
    val mainRight get() = signed(1)
    val echoLeft get() = signed(2)
    val echoRight get() = signed(3)
    val feedback get() = signed(4)
    val delay get() = regs[5] and 15
    val flags get() = regs[6]
    val echoStart get() = regs[7]
    val dir get() = regs[16]
    val echoVoices get() = regs[18]

    val isReset get() = flags and 0x80 != 0
    val isMuted get() = flags and 0x40 != 0
    val isEchoWriteEnabled get() = flags and 0x20 == 0

    /** Gain of FIR at 0Hz, defines loudness of each echo repeat together with feedback */
    val firDcGain: Float
        get() = abs(fir.sum()) / 128f
}

/**
 * Sample of voice: BRR blocks of 16 samples, see Module::VoiceState::SPC_DSP text
 */
internal object SpcSample {
    private const val INFO = 1
    private const val LOOP = 2
    private const val POSITION = 8
    const val BLOCK_SAMPLES = 16

    fun hasInfo(g: VoiceGauges) = g.textByte(0) and INFO != 0
    fun loops(g: VoiceGauges) = g.textByte(0) and LOOP != 0
    fun hasPosition(g: VoiceGauges) = g.textByte(0) and POSITION != 0
    fun blocks(g: VoiceGauges) = word(g, 1)

    /** Loop start block or -1 if it's outside of sample */
    fun loopBlock(g: VoiceGauges) = word(g, 3).let { if (it == 0xffff) -1 else it }
    fun position(g: VoiceGauges) = word(g, 5)

    private fun word(g: VoiceGauges, at: Int) = g.textByte(at) or (g.textByte(at + 1) shl 8)
}
