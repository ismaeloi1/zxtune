/**
 * @file
 * @brief Human readable description of SID registers state
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import app.zxtune.playback.ChipState
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Translates SID registers into musical terms, so the dashboard is understandable without datasheet:
 * - short values for gauges title bands (waveform, envelope level and phase, note, filter)
 * - plain words for cells grid under gauges (see [Words] and [SidCells]), one value per cell
 * Timings and frequencies depend on chip clock and model, see [Chip].
 */
internal object SidDescription {

    /**
     * Chip parameters affecting translation, guessed from emulation description
     */
    class Chip(val clock: Int, val is8580: Boolean) {
        companion object {
            val DEFAULT = Chip(PAL_CLOCK, false)

            // status starts with "PAL, MOS6581, ..." (see Sound::Scope::GetStatus)
            fun fromStatus(status: String) = Chip(
                if (status.contains("NTSC")) NTSC_CLOCK else PAL_CLOCK,
                status.contains("8580")
            )
        }
    }

    enum class Phase(val short: String, val word: String) {
        ATTACK("Atk", Words.ATTACK),
        DECAY("Dec", Words.DECAY),
        SUSTAIN("Sus", Words.SUSTAIN),
        RELEASE("Rel", Words.RELEASE),
        IDLE("Off", Words.SILENT)
    }

    const val PAL_CLOCK = 985248
    const val NTSC_CLOCK = 1022727

    // Rates are taken from 6581 datasheet, 8580 has the same timings
    private val ATTACK_MS = intArrayOf(2, 8, 16, 24, 38, 56, 68, 80, 100, 250, 500, 800, 1000, 3000, 5000, 8000)
    private val DECAY_MS = intArrayOf(6, 24, 48, 72, 114, 168, 204, 240, 300, 750, 1500, 2400, 3000, 9000, 15000, 24000)

    // Rough average of measured 6581 cutoff curves (they differ noticeably between chips), cutoff register to Hz
    private val CUTOFF_6581 = arrayOf(
        0 to 220f, 384 to 300f, 512 to 420f, 640 to 780f, 768 to 1600f, 896 to 3000f, 1024 to 4400f,
        1280 to 6000f, 1536 to 8500f, 1792 to 12500f, 2047 to 18000f
    )

    private const val GATE = 0x01
    private const val SYNC = 0x02
    private const val RING = 0x04
    private const val TEST = 0x08
    private const val TRIANGLE = 0x10
    private const val SAWTOOTH = 0x20
    private const val PULSE = 0x40
    private const val NOISE = 0x80

    private const val MODE_LOWPASS = 1
    private const val MODE_BANDPASS = 2
    private const val MODE_HIGHPASS = 4
    private const val MODE_VOICE3_OFF = 8

    fun attackMs(nibble: Int) = ATTACK_MS[nibble and 15]

    // release uses the same table as decay
    fun decayMs(nibble: Int) = DECAY_MS[nibble and 15]

    fun sustainPercent(nibble: Int) = (nibble and 15) * 100 / 15

    fun frequencyHz(reg: Int, chip: Chip) = reg.toFloat() * chip.clock / 16777216f

    fun pulsePercent(reg: Int) = ((reg and 0xfff) * 100f / 4096f).roundToInt()

    fun cutoffHz(reg: Int, chip: Chip): Float {
        val cutoff = reg.coerceIn(0, 2047)
        if (chip.is8580) {
            // almost linear, ~12kHz at maximum
            return 30f + cutoff * 5.8f
        }
        val upper = CUTOFF_6581.indexOfFirst { it.first >= cutoff }.coerceAtLeast(1)
        val (x0, y0) = CUTOFF_6581[upper - 1]
        val (x1, y1) = CUTOFF_6581[upper]
        return y0 + (y1 - y0) * (cutoff - x0) / (x1 - x0)
    }

    // Voice is synchronized/ring modulated by previous one cyclically: 1 by 3, 2 by 1, 3 by 2
    fun modulatorOf(voice: Int) = (voice + 2) % 3

    /**
     * @param control voice control register, waveform bits are in high nibble
     * @return short name for title band, e.g. "Saw", "Pulse 37%", "Saw+Tri"
     */
    fun waveShort(control: Int, pulseWidth: Int) = waveName(control, pulsePercent(pulseWidth))

    fun waveName(control: Int, pulsePercent: Int): String {
        val parts = buildList {
            // order matches common naming of combined waveforms (e.g. "Pulse+Saw")
            if (control and NOISE != 0) add("Noise")
            if (control and PULSE != 0) add("Pulse")
            if (control and SAWTOOTH != 0) add("Saw")
            if (control and TRIANGLE != 0) add("Tri")
        }
        return when {
            parts.isEmpty() -> "None"

            // pulse width is less important than combination itself in narrow title
            parts.size == 1 && parts[0] == "Pulse" -> "Pulse $pulsePercent%"

            else -> parts.joinToString("+")
        }
    }

    /**
     * @return Sync, Ring and Test flags as words with leading spaces
     */
    fun waveFlags(control: Int) = buildString {
        if (control and SYNC != 0) append(" Sync")
        // ring bit without triangle is silently ignored by chip, so don't claim it (matches Mod cell)
        if (hasRing(control)) append(" Ring")
        if (control and TEST != 0) append(" Test")
    }

    const val GATE_MARK = "\u25CF"

    fun isGate(control: Int) = control and GATE != 0

    /**
     * @return short title band value for waveform gauge: name and flags (Gate as dot, Sync, Ring, Test)
     */
    fun waveSummary(state: ChipState, voice: Int): String {
        val ctrl = state.control(voice)
        val gate = if (isGate(ctrl)) " $GATE_MARK" else ""
        return waveShort(ctrl, state.pulseWidth(voice)) + waveFlags(ctrl) + gate
    }

    /**
     * @param trend envelope tendency from history: positive for rising, negative for falling, 0 if unknown
     */
    fun phase(state: ChipState, voice: Int, trend: Int): Phase {
        val env = state.envelope(voice)
        val gate = isGate(state.control(voice))
        val sustain = (sustainNibble(state, voice)) * 0x11
        return when {
            !gate -> if (env == 0) Phase.IDLE else Phase.RELEASE
            env == 0xff -> Phase.ATTACK
            env < sustain -> Phase.ATTACK
            env == sustain -> Phase.SUSTAIN
            trend > 0 -> Phase.ATTACK
            else -> Phase.DECAY
        }
    }

    fun envelopePercent(state: ChipState, voice: Int) = state.envelope(voice) * 100 / 255

    fun envelopeSummary(state: ChipState, voice: Int, trend: Int) = "${envelopePercent(state, voice)}% ${phase(state, voice, trend).short}"

    fun frequencySummary(state: ChipState, voice: Int, chip: Chip): String {
        val hz = frequencyHz(state.frequency(voice), chip)
        if (hz < 1f) {
            return "0Hz"
        }
        val note = ChipWidgets.noteName(hz)
        return if (note.isEmpty()) formatHz(hz) else "$note ${formatHz(hz)}"
    }

    fun volumeSummary(state: ChipState) = percentOf15(state.volume)

    fun resonanceSummary(state: ChipState) = percentOf15(state.resonance)

    fun percentOf15(value: Int) = "${value * 100 / 15}%"

    fun filterSummary(state: ChipState, chip: Chip) = "${modeShort(state.mode)} ${cutoffText(cutoffHz(state.cutoff, chip))}"

    fun cutoffText(hz: Float) = "~${formatHz(hz)}"

    // human readable values for cells, see [SidCells]
    fun waveWord(control: Int) = when {
        control and TEST != 0 -> Words.RESET

        control and 0xf0 == 0 -> Words.NONE

        // single waveforms are spelled fully, mixes are abbreviated to fit
        control and 0xf0 == NOISE -> Words.NOISE

        control and 0xf0 == PULSE -> Words.PULSE

        control and 0xf0 == SAWTOOTH -> Words.SAW

        control and 0xf0 == TRIANGLE -> Words.TRIANGLE

        else -> waveName(control, 0)
    }

    fun hasPulse(control: Int) = control and PULSE != 0

    fun hasSync(control: Int) = control and SYNC != 0

    // ring modulation affects only triangle waveform
    fun hasRing(control: Int) = control and RING != 0 && control and TRIANGLE != 0

    /**
     * @return name of modulation applied to voice from its modulator (see [modulatorOf])
     */
    fun modulationWord(control: Int, voice: Int): String {
        val sync = hasSync(control)
        val ring = hasRing(control)
        val source = modulatorOf(voice) + 1
        return when {
            sync && ring -> Words.SYNC_RING
            sync -> "${Words.SYNC} V$source"
            ring -> "${Words.RING} V$source"
            else -> Words.DASH
        }
    }

    fun filterWord(filtered: Boolean, mode: Int, muted: Boolean) = when {
        // filtered voice is heard only through enabled filter outputs
        filtered -> if (mode and 7 == 0) Words.MUTED else modeWord(mode)

        // typical trick to use voice 3 as modulation source only
        muted -> Words.MUTED

        else -> Words.DASH
    }

    // voice 3 is disconnected from output only if it's not filtered
    fun isMuted(voice: Int, mode: Int, routing: Int) = voice == 2 && mode and MODE_VOICE3_OFF != 0 && routing and (1 shl voice) == 0

    fun modeWord(mode: Int) = Words.MODES[mode and 7]

    fun routingWord(routing: Int) = if (routing and 15 == 0) {
        Words.NONE
    } else {
        (0 until 4).filter { routing and (1 shl it) != 0 }.joinToString("+") { if (it == 3) Words.EXT else "V${it + 1}" }
    }

    fun percent(value: Int) = "$value%"

    // Spaced units are easier to read in narrow cells, digits are tabular so values don't move
    fun hzWord(hz: Int) = "$hz Hz"

    /**
     * Cutoff is rough estimation, so it's rounded to 2 significant digits
     * @return value to compare and format, in Hz
     */
    fun roundCutoff(hz: Float) = when {
        hz >= 10000f -> (hz / 1000f).roundToInt() * 1000
        hz >= 1000f -> (hz / 100f).roundToInt() * 100
        hz >= 100f -> (hz / 10f).roundToInt() * 10
        else -> hz.roundToInt()
    }

    fun cutoffWord(roundedHz: Int) = when {
        roundedHz >= 10000 -> "~${roundedHz / 1000} kHz"
        roundedHz >= 1000 -> "~${roundedHz / 1000}.${roundedHz / 100 % 10} kHz"
        else -> "~$roundedHz Hz"
    }

    fun durationWord(ms: Int) = when {
        ms >= 1000 && ms % 1000 == 0 -> "${ms / 1000} s"
        ms >= 1000 -> "${ms / 1000}.${ms / 100 % 10} s"
        else -> "$ms ms"
    }

    /**
     * Words displayed to user, collected in one place to be localized later
     */
    object Words {
        const val WAVE = "Wave"
        const val DUTY = "Duty"
        const val NOTE = "Note"
        const val PITCH = "Pitch"
        const val MOD = "Mod"
        const val FILTER = "Filter"
        const val LEVEL = "Level"
        const val STAGE = "Stage"
        const val ATTACK = "Attack"
        const val DECAY = "Decay"
        const val SUSTAIN = "Sustain"
        const val RELEASE = "Release"
        const val VOLUME = "Volume"
        const val CUTOFF = "Cutoff"
        const val RESONANCE = "Resonance"
        const val ROUTED = "Filtered"

        const val NOISE = "Noise"
        const val PULSE = "Pulse"
        const val SAW = "Saw"
        const val TRIANGLE = "Triangle"
        const val NONE = "None"
        const val RESET = "Reset"
        const val SYNC = "Sync"
        const val RING = "Ring"
        const val SYNC_RING = "Sync+Ring"
        const val MUTED = "Muted"
        const val SILENT = "Off"
        const val EXT = "Ext"
        const val DASH = "\u2014"
        val MODES = arrayOf("Off", "Low", "Band", "Low+Band", "High", "Notch", "Band+High", "All")
    }

    fun modeShort(mode: Int) = when (mode and 7) {
        0 -> "Off"
        MODE_LOWPASS -> "LP"
        MODE_BANDPASS -> "BP"
        MODE_HIGHPASS -> "HP"
        MODE_LOWPASS or MODE_HIGHPASS -> "Notch"
        MODE_LOWPASS or MODE_BANDPASS -> "LP+BP"
        MODE_BANDPASS or MODE_HIGHPASS -> "BP+HP"
        else -> "All"
    }

    private fun adReg(state: ChipState, voice: Int) = state.attackDecay(voice)

    private fun srReg(state: ChipState, voice: Int) = state.sustainRelease(voice)

    private fun sustainNibble(state: ChipState, voice: Int) = srReg(state, voice) shr 4

    fun formatHz(hz: Float) = when {
        hz >= 9950f -> "${(hz / 1000).roundToInt()}kHz"
        hz >= 1000f -> String.format(java.util.Locale.US, "%.1fkHz", hz / 1000)
        else -> "${hz.roundToInt()}Hz"
    }

    fun formatMs(ms: Int) = when {
        ms >= 1000 && ms % 1000 == 0 -> "${ms / 1000}s"
        ms >= 1000 -> String.format(java.util.Locale.US, "%.1fs", ms / 1000f)
        else -> "${ms}ms"
    }

    /**
     * Same scale as envelope gauges (see EnvelopeLevel at src/sound/impl/scope.cpp), to compare current value
     * with history
     */
    fun envelopeGaugeLevel(env: Int): Int {
        if (env == 0) {
            return 0
        }
        val level = 1f + 20f * log10(env / 255f) / 48f
        return (level.coerceIn(0f, 1f) * 255).toInt()
    }
}
