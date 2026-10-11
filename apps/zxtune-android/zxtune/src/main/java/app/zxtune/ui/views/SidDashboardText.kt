/**
 * @file
 * @brief Stabilized texts of SID dashboard rows
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import app.zxtune.playback.ChipState
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Texts of gauges row (3 gauges with title bands and explanation below), see [SidDashboardView].
 * Registers are changed by players up to several hundred times per second (vibrato, pulse width and filter sweeps,
 * arpeggios, digis), so texts are produced from [StableValue]s and rebuilt only when those are changed.
 */
internal abstract class SidRowText {
    val texts = StableTexts(SIZE)

    companion object {
        // raw registers in titles are data, so they are just rate limited
        const val RAW_HOLD_MS = 200L

        // vibrato often exceeds half of semitone, so the note is kept a bit more than its bounds
        const val NOTE_MARGIN = 0.3f

        // ~1.5%, less than vibrato depth, but noticeable for slides
        const val PITCH_DEADBAND_CENTS = 25

        // filter sweeps are slow and wide, so cutoff is coarser
        const val CUTOFF_DEADBAND_CENTS = 50
        const val PERCENT_DEADBAND = 2

        // modulations (vibrato, pulse width and filter sweeps) are periodic, so their exact current values are
        // less important than envelope level and are updated more calmly
        const val SLOW_HOLD_MS = 500L
        const val NONE = Int.MIN_VALUE

        const val SIZE = 3 + 3 * 2 + SidDescription.SLOTS
        fun title(gauge: Int) = gauge
        fun main(gauge: Int) = 3 + gauge * 2
        fun tail(gauge: Int) = 4 + gauge * 2
        fun slot(idx: Int) = 9 + idx

        fun cents(hz: Float) = if (hz < 1f) NONE else (1200 * log2(hz / 440f)).roundToInt()

        fun semitones(hz: Float) = 12 * log2(hz / 440f) + 57
    }
}

internal class SidVoiceText : SidRowText() {
    private val rawControl = StableValue(0, RAW_HOLD_MS)
    private val rawEnvelope = StableValue(0, RAW_HOLD_MS)
    private val rawFrequency = StableValue(0, RAW_HOLD_MS)
    private val wave = StableValue()
    private val gate = StableValue()
    private val pulse = StableValue(holdMs = SLOW_HOLD_MS, deadband = PERCENT_DEADBAND)
    private val level = StableValue(deadband = PERCENT_DEADBAND)
    private val phase = StableValue()
    private val note = StableValue()
    private val pitch = StableValue(holdMs = SLOW_HOLD_MS, deadband = PITCH_DEADBAND_CENTS)
    private val filter = StableValue()
    private val cutoff = StableValue(holdMs = SLOW_HOLD_MS, deadband = CUTOFF_DEADBAND_CENTS)
    private var hz = 0f
    private var cutoffHz = 0f
    private var label = -1

    /**
     * @param chipLabel 1-based index of chip in multichip setup or 0
     */
    fun update(
        state: ChipState,
        voice: Int,
        trend: Int,
        chip: SidDescription.Chip,
        chipLabel: Int,
        nowMs: Long
    ) {
        val ctrl = state.control(voice)
        val routing = state.routing
        val mode = state.mode
        val filtered = routing and (1 shl voice) != 0
        val currentHz = SidDescription.frequencyHz(state.frequency(voice), chip)
        var titles = rawControl.update((ctrl and 0xfe) or (if (filtered) 1 else 0), nowMs)
        titles = rawEnvelope.update((state.attackDecay(voice) shl 8) or state.sustainRelease(voice), nowMs) or titles
        titles = rawFrequency.update(state.frequency(voice), nowMs) or titles
        if (label != chipLabel) {
            label = chipLabel
            titles = true
        }
        // gate is excluded from waveform to not confirm waveform again after hard restart
        var values = wave.update(ctrl and 0xfe, nowMs)
        values = gate.update(ctrl and 1, nowMs) or values
        values = pulse.update(SidDescription.pulsePercent(state.pulseWidth(voice)), nowMs) or values
        values = level.update(SidDescription.envelopePercent(state, voice), nowMs) or values
        values = phase.update(SidDescription.phase(state, voice, trend).ordinal, nowMs) or values
        values = if (currentHz < 1f) {
            note.update(NONE, nowMs)
        } else {
            note.update(semitones(currentHz), NOTE_MARGIN, nowMs)
        } or values
        if (pitch.update(cents(currentHz), nowMs)) {
            hz = currentHz
            values = true
        }
        val muted = SidDescription.isMuted(voice, mode, routing)
        values = filter.update((if (filtered) 1 else 0) or (if (muted) 2 else 0) or ((mode and 7) shl 2), nowMs) or values
        val currentCutoff = SidDescription.cutoffHz(state.cutoff, chip)
        if (cutoff.update(cents(currentCutoff), nowMs)) {
            cutoffHz = currentCutoff
            values = true
        }
        if (titles) {
            updateTitles(voice, nowMs)
        }
        if (values || titles) {
            updateValues(voice, nowMs)
        }
    }

    private fun updateTitles(voice: Int, nowMs: Long) {
        val ctrl = rawControl.value
        val prefix = if (label != 0) "S$label" else ""
        texts.set(title(0), "${prefix}V${voice + 1} ${SidDashboardView.waveTitle(ctrl, ctrl and 1 != 0)}", nowMs)
        texts.set(title(1), "Env ${hex4(rawEnvelope.value)}", nowMs)
        texts.set(title(2), "Freq ${hex4(rawFrequency.value)}", nowMs)
    }

    private fun updateValues(voice: Int, nowMs: Long) {
        val ctrl = wave.value
        val noteName = if (note.value == NONE) "" else ChipWidgets.noteName(note.value)
        val currentPhase = SidDescription.Phase.values()[phase.value]
        texts.set(main(0), SidDescription.waveName(ctrl, pulse.value, false) + SidDescription.waveFlags(ctrl), nowMs)
        texts.set(tail(0), if (gate.value != 0) SidDescription.GATE_MARK else "", nowMs)
        texts.set(main(1), "${level.value}%", nowMs)
        texts.set(tail(1), currentPhase.short, nowMs)
        texts.set(main(2), noteName, nowMs)
        texts.set(tail(2), if (pitch.value == NONE) "0Hz" else SidDescription.formatHz(hz), nowMs)
        val filterState = filter.value
        val regs = rawEnvelope.value
        texts.set(slot(SidDescription.SLOT_WAVE), SidDescription.waveSlot(ctrl, pulse.value), nowMs)
        texts.set(
            slot(SidDescription.SLOT_PITCH),
            SidDescription.pitchSlot(ctrl, if (pitch.value == NONE) 0f else hz, noteName, voice),
            nowMs
        )
        texts.set(
            slot(SidDescription.SLOT_FILTER),
            SidDescription.filterSlot(filterState and 1 != 0, filterState shr 2, cutoffHz, filterState and 2 != 0),
            nowMs
        )
        texts.set(
            slot(SidDescription.SLOT_ENVELOPE),
            SidDescription.envelopeSlot(currentPhase, regs shr 8, regs and 0xff, level.value),
            nowMs
        )
    }
}

internal class SidGlobalText : SidRowText() {
    private val rawFilter = StableValue(0, RAW_HOLD_MS)
    private val volume = StableValue()
    private val resonance = StableValue()
    private val routing = StableValue()
    private val cutoff = StableValue(holdMs = SLOW_HOLD_MS, deadband = CUTOFF_DEADBAND_CENTS)
    private var cutoffHz = 0f

    fun update(state: ChipState, chip: SidDescription.Chip, nowMs: Long) {
        val titles = rawFilter.update(((state.mode and 7) shl 11) or state.cutoff, nowMs)
        // volume and resonance values are shown in titles too, so not rate limited separately
        var values = volume.update(state.volume, nowMs)
        values = resonance.update(state.resonance, nowMs) or values
        values = routing.update(state.routing or (state.mode shl 4), nowMs) or values
        val currentCutoff = SidDescription.cutoffHz(state.cutoff, chip)
        if (cutoff.update(cents(currentCutoff), nowMs)) {
            cutoffHz = currentCutoff
            values = true
        }
        if (titles || values) {
            val raw = rawFilter.value
            texts.set(title(0), "Vol ${volume.value}", nowMs)
            texts.set(title(1), "Res ${resonance.value}", nowMs)
            texts.set(title(2), SidDashboardView.filterTitle(raw shr 11, raw and 0x7ff), nowMs)
            val route = routing.value and 15
            val mode = routing.value shr 4
            texts.set(tail(0), SidDescription.percentOf15(volume.value), nowMs)
            texts.set(tail(1), SidDescription.percentOf15(resonance.value), nowMs)
            texts.set(main(2), SidDescription.modeShort(mode), nowMs)
            texts.set(tail(2), SidDescription.cutoffText(cutoffHz), nowMs)
            texts.set(slot(SidDescription.SLOT_VOLUME), SidDescription.volumeSlot(volume.value), nowMs)
            texts.set(slot(SidDescription.SLOT_ROUTING), SidDescription.routingSlot(route, mode, cutoffHz), nowMs)
            texts.set(slot(SidDescription.SLOT_RESONANCE), SidDescription.resonanceSlot(route, resonance.value), nowMs)
            texts.set(slot(SidDescription.SLOT_VOICE3), SidDescription.voice3Slot(mode), nowMs)
        }
    }
}

private fun hex4(value: Int) = String.format(java.util.Locale.US, "%04X", value)
