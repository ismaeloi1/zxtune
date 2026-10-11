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
 * - plain language sentence describing what is heard from each voice
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

    enum class Phase(val short: String) {
        ATTACK("Atk"),
        DECAY("Dec"),
        SUSTAIN("Sus"),
        RELEASE("Rel"),
        IDLE("Off")
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
    fun waveShort(control: Int, pulseWidth: Int) = waveName(control, pulsePercent(pulseWidth), false)

    fun waveLong(control: Int, pulseWidth: Int) = waveName(control, pulsePercent(pulseWidth), true)

    fun waveName(control: Int, pulsePercent: Int, long: Boolean): String {
        val parts = buildList {
            // order matches common naming of combined waveforms (e.g. "Pulse+Saw")
            if (control and NOISE != 0) add("Noise")
            if (control and PULSE != 0) {
                add(if (long) "pulse $pulsePercent% duty" else "Pulse $pulsePercent%")
            }
            if (control and SAWTOOTH != 0) add(if (long) "sawtooth" else "Saw")
            if (control and TRIANGLE != 0) add(if (long) "triangle" else "Tri")
        }
        return when {
            parts.isEmpty() -> if (long) "no waveform" else "None"

            parts.size == 1 -> parts[0].replaceFirstChar { it.uppercase() }

            long -> parts.joinToString("+").replaceFirstChar { it.uppercase() } + " mix"

            // pulse width is less important than combination itself in narrow title
            else -> parts.joinToString("+") { it.substringBefore(' ') }
        }
    }

    /**
     * @return Sync, Ring and Test flags as words with leading spaces
     */
    fun waveFlags(control: Int) = buildString {
        if (control and SYNC != 0) append(" Sync")
        if (control and RING != 0) append(" Ring")
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

    private fun modeLong(mode: Int) = when (mode and 7) {
        0 -> "no filter output"
        MODE_LOWPASS -> "low-pass"
        MODE_BANDPASS -> "band-pass"
        MODE_HIGHPASS -> "high-pass"
        MODE_LOWPASS or MODE_HIGHPASS -> "notch (band-reject)"
        MODE_LOWPASS or MODE_BANDPASS -> "low+band-pass"
        MODE_BANDPASS or MODE_HIGHPASS -> "band+high-pass"
        else -> "all-pass"
    }

    /*
     * Plain language explanation is split to fixed slots (see [SLOTS]), so each part is drawn at the same place
     * and changes of one part don't move others.
     */
    const val SLOT_WAVE = 0
    const val SLOT_PITCH = 1
    const val SLOT_FILTER = 2
    const val SLOT_ENVELOPE = 3

    // global row uses the same slots
    const val SLOT_VOLUME = 0
    const val SLOT_ROUTING = 1
    const val SLOT_RESONANCE = 2
    const val SLOT_VOICE3 = 3
    const val SLOTS = 4

    /**
     * Widest typical texts of slots (both voice and global rows) to reserve space, rarer longer ones are cut
     */
    val SLOT_TEMPLATES = arrayOf(
        arrayOf("Sawtooth+triangle mix", "Pulse 100% duty", "Volume 100%"),
        arrayOf("at C#5, hard-synced to V1", "at C#5, ring-modulated by V3", "low+band-pass ~12.5kHz on V1+V3"),
        arrayOf("filtered low-pass ~12.5kHz", "filtered band-pass ~12.5kHz", "resonance 100%"),
        arrayOf("attack 250ms, decay 2.4s to sustain 100%", "releasing 100% over 750ms", "V3 disconnected"),
    )

    fun waveSlot(control: Int, pulsePercent: Int) = if (control and TEST != 0) "Oscillator reset (test bit)" else waveName(control, pulsePercent, true)

    /**
     * @param note name of note or empty if out of range
     */
    fun pitchSlot(control: Int, hz: Float, note: String, voice: Int): String {
        if (control and TEST != 0) {
            return ""
        }
        val modulator = "V${modulatorOf(voice) + 1}"
        val parts = ArrayList<String>(3)
        when {
            hz < 1f -> Unit
            control and NOISE != 0 -> parts.add("${formatHz(hz)} rate")
            note.isNotEmpty() -> parts.add("at $note")
            else -> parts.add("at ${formatHz(hz)}")
        }
        if (control and SYNC != 0) parts.add("hard-synced to $modulator")
        // ring modulation affects only triangle waveform
        if (control and RING != 0 && control and TRIANGLE != 0) parts.add("ring-modulated by $modulator")
        return parts.joinToString(", ")
    }

    fun filterSlot(filtered: Boolean, mode: Int, cutoffHz: Float, muted: Boolean) = when {
        filtered -> "filtered ${modeLong(mode)} ${cutoffText(cutoffHz)}"

        // typical trick to use voice 3 as modulation source only
        muted -> "muted (3OFF)"

        else -> ""
    }

    fun isMuted(voice: Int, mode: Int, routing: Int) = voice == 2 && mode and MODE_VOICE3_OFF != 0 && routing and (1 shl voice) == 0

    fun envelopeSlot(phase: Phase, attackDecay: Int, sustainRelease: Int, envelopePercent: Int): String {
        val release = formatMs(decayMs(sustainRelease))
        val sustain = "sustain ${sustainPercent(sustainRelease shr 4)}%"
        return when (phase) {
            Phase.ATTACK -> "attack ${formatMs(attackMs(attackDecay shr 4))}, decay ${formatMs(decayMs(attackDecay))} to $sustain"
            Phase.DECAY -> "decay ${formatMs(decayMs(attackDecay))} to $sustain"
            Phase.SUSTAIN -> "$sustain, release $release"
            Phase.RELEASE -> "releasing $envelopePercent% over $release"
            Phase.IDLE -> "silent"
        }
    }

    /**
     * Plain language description of what is heard from the voice, e.g.
     * "Noise 523Hz rate, filtered low-pass ~1.2kHz, sustain 60%, release 750ms"
     */
    fun voiceExplanation(state: ChipState, voice: Int, trend: Int, chip: Chip): String {
        val ctrl = state.control(voice)
        val hz = frequencyHz(state.frequency(voice), chip)
        val slots = arrayOf(
            waveSlot(ctrl, pulsePercent(state.pulseWidth(voice))),
            pitchSlot(ctrl, hz, ChipWidgets.noteName(hz), voice),
            filterSlot(
                state.routing and (1 shl voice) != 0,
                state.mode,
                cutoffHz(state.cutoff, chip),
                isMuted(voice, state.mode, state.routing)
            ),
            envelopeSlot(phase(state, voice, trend), adReg(state, voice), srReg(state, voice), envelopePercent(state, voice))
        )
        return joinSlots(slots, true)
    }

    // pitch continues waveform name (e.g. "Saw at C4"), other slots are separate clauses
    private fun joinSlots(slots: Array<String>, continuePitch: Boolean) = buildString {
        slots.forEachIndexed { idx, slot ->
            if (slot.isNotEmpty()) {
                val continues = continuePitch && idx == SLOT_PITCH && !slot.startsWith("hard-") &&
                    !slot.startsWith("ring-")
                if (isNotEmpty()) append(if (continues) " " else ", ")
                append(slot)
            }
        }
    }

    fun volumeSlot(volume: Int) = "Volume ${percentOf15(volume)}"

    fun routingSlot(routing: Int, mode: Int, cutoffHz: Float): String {
        if (routing == 0) {
            return "filter unused"
        }
        val voices = (0 until 4).filter { routing and (1 shl it) != 0 }
            .joinToString("+") { if (it == 3) "Ext" else "V${it + 1}" }
        return "${modeLong(mode)} ${cutoffText(cutoffHz)} on $voices"
    }

    fun resonanceSlot(routing: Int, resonance: Int) = if (routing == 0) "" else "resonance ${percentOf15(resonance)}"

    fun voice3Slot(mode: Int) = if (mode and MODE_VOICE3_OFF != 0) "V3 disconnected" else ""

    /**
     * Global filter and volume description, e.g. "Volume 100%, low-pass ~1.2kHz on V1+V3, resonance 47%"
     */
    fun filterExplanation(state: ChipState, chip: Chip) = joinSlots(
        arrayOf(
            volumeSlot(state.volume),
            routingSlot(state.routing, state.mode, cutoffHz(state.cutoff, chip)),
            resonanceSlot(state.routing, state.resonance),
            voice3Slot(state.mode)
        ),
        false
    )

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
