/**
 * @file
 * @brief Live texts of SID dashboard rows
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import app.zxtune.playback.ChipState
import app.zxtune.ui.views.SidDescription.Words
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Fixed set of texts, each one is rebuilt only when the quantized value it's made from is changed. Texts are drawn
 * every frame (60..120fps) while registers mostly stay the same, so this avoids allocations per frame without
 * delaying any change.
 */
internal class LiveTexts(size: Int) {
    private val keys = LongArray(size) { UNSET }
    private val texts = Array(size) { "" }

    operator fun get(idx: Int) = texts[idx]

    /**
     * @return true if text should be rebuilt for the new [key] (see [set])
     */
    fun changed(idx: Int, key: Long): Boolean {
        if (keys[idx] == key) {
            return false
        }
        keys[idx] = key
        return true
    }

    fun changed(idx: Int, key: Int) = changed(idx, key.toLong())

    operator fun set(idx: Int, text: String) {
        texts[idx] = text
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE
    }
}

/**
 * Description of a single cell of values grid under gauges row
 * @param caption small label above the value
 * @param templates widest typical values, to reserve space regardless of the current one; rarer longer values are cut
 * @param numeric numbers are right-aligned to keep digits in place (as in trackers), words are left-aligned
 * @param priority cells with greater values are dropped first if there's not enough space
 */
internal class CellSpec(val caption: String, val templates: Array<String>, val numeric: Boolean, val priority: Int)

internal object SidCells {
    const val WAVE = 0
    const val DUTY = 1
    const val NOTE = 2
    const val PITCH = 3
    const val MOD = 4
    const val FILTER = 5
    const val LEVEL = 6
    const val STAGE = 7
    const val ATTACK = 8
    const val DECAY = 9
    const val SUSTAIN = 10
    const val RELEASE = 11

    // order of cells is order of display; what is heard (waveform, note, loudness) is kept the longest,
    // envelope timings are static settings and dropped first
    val VOICE = arrayOf(
        CellSpec(Words.WAVE, arrayOf("Pulse+Saw", Words.TRIANGLE, Words.RESET), false, 0),
        CellSpec(Words.DUTY, arrayOf("100%"), true, 6),
        CellSpec(Words.NOTE, arrayOf("C#5"), false, 1),
        CellSpec(Words.PITCH, arrayOf("8888 Hz"), true, 5),
        CellSpec(Words.MOD, arrayOf("Sync V3", "Ring V3", Words.SYNC_RING), false, 7),
        CellSpec(Words.FILTER, arrayOf("Low+Band", "Band+High", Words.MUTED), false, 4),
        CellSpec(Words.LEVEL, arrayOf("100%"), true, 2),
        CellSpec(Words.STAGE, SidDescription.Phase.values().map { it.word }.toTypedArray(), false, 3),
        CellSpec(Words.ATTACK, arrayOf("250 ms", "8 s"), true, 10),
        CellSpec(Words.DECAY, arrayOf("750 ms", "24 s"), true, 11),
        CellSpec(Words.SUSTAIN, arrayOf("100%"), true, 8),
        CellSpec(Words.RELEASE, arrayOf("750 ms", "24 s"), true, 9),
    )

    const val VOLUME = 0
    const val MODE = 1
    const val CUTOFF = 2
    const val RESONANCE = 3
    const val ROUTED = 4

    val GLOBAL = arrayOf(
        CellSpec(Words.VOLUME, arrayOf("100%"), true, 0),
        CellSpec(Words.FILTER, arrayOf("Low+Band", "Band+High"), false, 1),
        CellSpec(Words.CUTOFF, arrayOf("~8.8 kHz", "~18 kHz"), true, 2),
        CellSpec(Words.RESONANCE, arrayOf("100%"), true, 3),
        CellSpec(Words.ROUTED, arrayOf("V1+V2+V3"), false, 4),
    )
}

/**
 * Texts of gauges row: title bands of 3 gauges (raw registers with greyed human readable value split to main part
 * and tail, e.g. "60%" and "Sus") and cells grid below, see [SidDashboardView].
 * Values are displayed as is, without any delay; only quantization with hysteresis is applied where the last digit
 * would flicker without any meaning.
 */
internal abstract class SidRowText(cells: Int) {
    val titles = LiveTexts(TITLES)
    val cells = LiveTexts(cells)

    companion object {
        // vibrato often exceeds half of semitone, so the note is kept a bit more than its bounds
        const val NOTE_MARGIN = 0.3f
        const val NONE = Int.MIN_VALUE

        const val TITLES = 9
        fun title(gauge: Int) = gauge
        fun main(gauge: Int) = 3 + gauge * 2
        fun tail(gauge: Int) = 4 + gauge * 2

        fun semitones(hz: Float) = 12 * log2(hz / 440f) + 57

        /**
         * Quantizes [semitones] to note with hysteresis: [shown] note is kept while input is within [NOTE_MARGIN]
         * beyond its half-step bounds. Doesn't delay real note changes.
         */
        fun note(semitones: Float, shown: Int) = if (shown != NONE && abs(semitones - shown) <= 0.5f + NOTE_MARGIN) shown else semitones.roundToInt()
    }
}

internal class SidVoiceText : SidRowText(SidCells.VOICE.size) {
    private var note = NONE

    /**
     * @param chipLabel 1-based index of chip in multichip setup or 0
     */
    fun update(state: ChipState, voice: Int, trend: Int, chip: SidDescription.Chip, chipLabel: Int) {
        val ctrl = state.control(voice)
        val routing = state.routing
        val mode = state.mode
        val filtered = routing and (1 shl voice) != 0
        val ad = state.attackDecay(voice)
        val sr = state.sustainRelease(voice)
        val hz = SidDescription.frequencyHz(state.frequency(voice), chip)
        note = if (hz < 1f) NONE else note(semitones(hz), note)
        val pulse = SidDescription.pulsePercent(state.pulseWidth(voice))
        val level = SidDescription.envelopePercent(state, voice)
        val phase = SidDescription.phase(state, voice, trend)
        val roundedHz = hz.roundToInt()
        val noteName = if (note == NONE) "" else ChipWidgets.noteName(note)

        // titles: raw registers
        val wave = (ctrl and 0xfe) or (if (filtered) 1 else 0)
        if (titles.changed(title(0), (chipLabel shl 8) or wave)) {
            val prefix = if (chipLabel != 0) "S$chipLabel" else ""
            titles[title(0)] = "${prefix}V${voice + 1} ${SidDashboardView.waveTitle(wave, filtered)}"
        }
        if (titles.changed(title(1), (ad shl 8) or sr)) {
            titles[title(1)] = "Env ${hex4((ad shl 8) or sr)}"
        }
        if (titles.changed(title(2), state.frequency(voice))) {
            titles[title(2)] = "Freq ${hex4(state.frequency(voice))}"
        }
        // titles: values
        if (titles.changed(main(0), (ctrl and 0xfe) or (pulse shl 8))) {
            titles[main(0)] = SidDescription.waveName(ctrl, pulse) + SidDescription.waveFlags(ctrl)
        }
        if (titles.changed(tail(0), ctrl and 1)) {
            titles[tail(0)] = if (SidDescription.isGate(ctrl)) SidDescription.GATE_MARK else ""
        }
        if (titles.changed(main(1), level)) {
            titles[main(1)] = SidDescription.percent(level)
        }
        if (titles.changed(tail(1), phase.ordinal)) {
            titles[tail(1)] = phase.short
        }
        if (titles.changed(main(2), note)) {
            titles[main(2)] = noteName
        }
        if (titles.changed(tail(2), roundedHz)) {
            titles[tail(2)] = "${roundedHz}Hz"
        }

        // cells
        if (cells.changed(SidCells.WAVE, ctrl and 0xf8)) {
            cells[SidCells.WAVE] = SidDescription.waveWord(ctrl)
        }
        val duty = if (SidDescription.hasPulse(ctrl)) pulse else NONE
        if (cells.changed(SidCells.DUTY, duty)) {
            cells[SidCells.DUTY] = if (duty == NONE) "" else SidDescription.percent(duty)
        }
        if (cells.changed(SidCells.NOTE, note)) {
            cells[SidCells.NOTE] = noteName.ifEmpty { Words.DASH }
        }
        if (cells.changed(SidCells.PITCH, roundedHz)) {
            cells[SidCells.PITCH] = if (roundedHz == 0) Words.DASH else SidDescription.hzWord(roundedHz)
        }
        if (cells.changed(SidCells.MOD, ctrl and 0x16)) {
            cells[SidCells.MOD] = SidDescription.modulationWord(ctrl, voice)
        }
        val muted = SidDescription.isMuted(voice, mode, routing)
        if (cells.changed(SidCells.FILTER, (if (filtered) 1 else 0) or (if (muted) 2 else 0) or ((mode and 7) shl 2))) {
            cells[SidCells.FILTER] = SidDescription.filterWord(filtered, mode, muted)
        }
        if (cells.changed(SidCells.LEVEL, level)) {
            cells[SidCells.LEVEL] = SidDescription.percent(level)
        }
        if (cells.changed(SidCells.STAGE, phase.ordinal)) {
            cells[SidCells.STAGE] = phase.word
        }
        if (cells.changed(SidCells.ATTACK, ad shr 4)) {
            cells[SidCells.ATTACK] = SidDescription.durationWord(SidDescription.attackMs(ad shr 4))
        }
        if (cells.changed(SidCells.DECAY, ad and 15)) {
            cells[SidCells.DECAY] = SidDescription.durationWord(SidDescription.decayMs(ad))
        }
        if (cells.changed(SidCells.SUSTAIN, sr shr 4)) {
            cells[SidCells.SUSTAIN] = SidDescription.percent(SidDescription.sustainPercent(sr shr 4))
        }
        // release uses the same table as decay
        if (cells.changed(SidCells.RELEASE, sr and 15)) {
            cells[SidCells.RELEASE] = SidDescription.durationWord(SidDescription.decayMs(sr))
        }
    }
}

internal class SidGlobalText : SidRowText(SidCells.GLOBAL.size) {
    fun update(state: ChipState, chip: SidDescription.Chip) {
        val mode = state.mode
        val cutoff = SidDescription.roundCutoff(SidDescription.cutoffHz(state.cutoff, chip))
        if (titles.changed(title(0), state.volume)) {
            titles[title(0)] = "Vol ${state.volume}"
            titles[tail(0)] = SidDescription.percentOf15(state.volume)
            cells[SidCells.VOLUME] = titles[tail(0)]
        }
        if (titles.changed(title(1), state.resonance)) {
            titles[title(1)] = "Res ${state.resonance}"
            titles[tail(1)] = SidDescription.percentOf15(state.resonance)
            cells[SidCells.RESONANCE] = titles[tail(1)]
        }
        if (titles.changed(title(2), ((mode and 7) shl 11) or state.cutoff)) {
            titles[title(2)] = SidDashboardView.filterTitle(mode, state.cutoff)
        }
        if (titles.changed(main(2), mode and 7)) {
            titles[main(2)] = SidDescription.modeShort(mode)
            cells[SidCells.MODE] = SidDescription.modeWord(mode)
        }
        if (titles.changed(tail(2), cutoff)) {
            titles[tail(2)] = SidDescription.cutoffWord(cutoff).replace(" ", "")
            cells[SidCells.CUTOFF] = SidDescription.cutoffWord(cutoff)
        }
        if (cells.changed(SidCells.ROUTED, state.routing and 15)) {
            cells[SidCells.ROUTED] = SidDescription.routingWord(state.routing)
        }
    }
}

private fun hex4(value: Int) = String.format(java.util.Locale.US, "%04X", value)
