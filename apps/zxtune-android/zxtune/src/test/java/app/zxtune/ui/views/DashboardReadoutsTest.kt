package app.zxtune.ui.views

import app.zxtune.playback.ChipState
import app.zxtune.playback.VoiceGauges
import app.zxtune.playback.VoiceGauges.Pcm
import kotlin.math.PI
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dashboard texts of values written by players at each tick (vibrato, slides, sweeps) at display rate: they're
 * refreshed a few times per second at most, show the swept range while modulated and the exact value otherwise.
 */
class DashboardReadoutsTest {

    // Distinct consecutive texts of a cell
    private class History {
        val texts = ArrayList<String>()

        fun add(text: String) {
            if (texts.lastOrNull() != text) {
                texts.add(text)
            }
        }

        val changes
            get() = texts.size - 1

        override fun toString() = texts.toString()
    }

    // display frames of 60Hz with player ticks of 50Hz: registers are held between ticks
    private fun run(seconds: Double, fps: Int = 60, tickHz: Int = 50, onFrame: (nanos: Long, tick: Double) -> Unit) {
        val frames = (seconds * fps).toInt()
        for (frame in 0 until frames) {
            val time = frame.toDouble() / fps
            onFrame((time * 1e9).toLong() + START_NANOS, Math.floor(time * tickHz) / tickHz)
        }
    }

    // the first text, then at most 4 refreshes per second
    private fun assertReadable(name: String, history: History, seconds: Double) {
        assertTrue("$name: $history", history.changes <= 4 * seconds + 1)
    }

    @Test
    fun `sid voice with vibrato and pulse sweep`() {
        val text = SidVoiceText()
        val chip = SidDescription.Chip.DEFAULT
        val regs = ByteArray(ChipState.SIZE)
        val cells = Array(SidCells.VOICE.size) { History() }
        val titles = Array(SidRowText.TITLES) { History() }
        run(3.0) { nanos, tick ->
            fillJitteringRegs(regs, (tick * 1000).toLong())
            text.update(ChipState(regs, 0), 0, -1, chip, 0, nanos)
            cells.forEachIndexed { idx, history -> history.add(text.cells[idx]) }
            titles.forEachIndexed { idx, history -> history.add(text.titles[idx]) }
        }
        for (idx in SidCells.VOICE.indices) {
            assertReadable(SidCells.VOICE[idx].caption, cells[idx], 3.0)
        }
        for (idx in 0 until SidRowText.TITLES) {
            assertReadable("title $idx", titles[idx], 3.0)
        }
        // note is held through vibrato, pitch is shown as swept range
        assertEquals(listOf("C5"), cells[SidCells.NOTE].texts)
        val pitch = cells[SidCells.PITCH].texts.last()
        assertTrue(pitch, Regex("5\\d\\d…5\\d\\d Hz").matches(pitch))
        // pulse sweep is followed
        assertTrue(cells[SidCells.DUTY].toString(), cells[SidCells.DUTY].changes >= 3)
        // hard restart is displayed as is
        assertTrue(cells[SidCells.STAGE].toString(), cells[SidCells.STAGE].changes >= 1)
        // static settings are never rebuilt
        assertEquals(0, cells[SidCells.ATTACK].changes)
    }

    @Test
    fun `sid voice with portamento`() {
        val text = SidVoiceText()
        val chip = SidDescription.Chip.DEFAULT
        val regs = ByteArray(ChipState.SIZE)
        val notes = History()
        val pitches = History()
        // C4 to C5 in a second by semitones, then held
        val c4 = SID_C5 / 2
        run(2.0) { nanos, tick ->
            val freq = (c4 * 2.0.pow(minOf(tick, 1.0))).roundToInt()
            regs[0] = freq.toByte()
            regs[1] = (freq shr 8).toByte()
            regs[4] = 0x11
            text.update(ChipState(regs, 0), 0, -1, chip, 0, nanos)
            notes.add(text.cells[SidCells.NOTE])
            pitches.add(text.cells[SidCells.PITCH])
        }
        assertReadable("notes", notes, 2.0)
        assertReadable("pitches", pitches, 2.0)
        // notes go up without returns
        val semitones = notes.texts.map { ChipWidgets.NOTE_NAMES.indexOf(it.dropLast(1)) + 12 * it.last().digitToInt() }
        assertEquals(semitones.sorted(), semitones)
        assertEquals("C4", notes.texts.first())
        assertEquals("C5", notes.texts.last())
        // slide is shown as range, then the exact value
        assertTrue(pitches.toString(), pitches.texts.any { it.contains('…') })
        assertEquals("523 Hz", pitches.texts.last())
    }

    @Test
    fun `sid single picture is exact`() {
        val chip = SidDescription.Chip.DEFAULT
        val regs = ByteArray(ChipState.SIZE)
        val text = SidVoiceText()
        for (t in longArrayOf(0, 40, 80)) {
            fillJitteringRegs(regs, t)
            text.update(ChipState(regs, 0), 0, -1, chip, 0, 0L)
            val state = ChipState(regs, 0)
            val hz = SidDescription.frequencyHz(state.frequency(0), chip).roundToInt()
            assertEquals("$hz Hz", text.cells[SidCells.PITCH])
            assertEquals("${SidDescription.pulsePercent(state.pulseWidth(0))}%", text.cells[SidCells.DUTY])
        }
    }

    @Test
    fun `sid cutoff sweep`() {
        val text = SidGlobalText()
        val chip = SidDescription.Chip.DEFAULT
        val regs = ByteArray(ChipState.SIZE)
        val cutoffs = History()
        val titles = History()
        run(3.0) { nanos, tick ->
            val cutoff = (1024 + 900 * sin(2 * PI * 1.5 * tick)).toInt()
            regs[0x15] = (cutoff and 7).toByte()
            regs[0x16] = (cutoff shr 3).toByte()
            regs[0x17] = 0xf1.toByte()
            regs[0x18] = 0x1f
            text.update(ChipState(regs, 0), chip, nanos)
            cutoffs.add(text.cells[SidCells.CUTOFF])
            titles.add(text.titles[SidRowText.title(2)])
        }
        assertReadable("cutoff", cutoffs, 3.0)
        assertReadable("filter title", titles, 3.0)
        val last = cutoffs.texts.last()
        assertTrue(last, Regex("~\\d+( Hz)?…\\d+(\\.\\d)? k?Hz").matches(last))
    }

    @Test
    fun `spc voice with vibrato`() {
        val text = SpcVoiceText()
        val env = SpcEnvelope()
        val data = ByteArray(VoiceGauges.SIZE)
        val notes = History()
        val rates = History()
        val titles = History()
        val shifts = History()
        run(3.0) { nanos, tick ->
            // 70 cents vibrato at 6Hz around $1000 (sample's own rate)
            val pitch = (0x1000 * 2.0.pow(0.7 * sin(2 * PI * 6 * tick) / 12)).roundToInt()
            // output pitch of A4 moves with vibrato as well, by gauge steps
            val gauge = ((log2(440.0 / 27.5) + 0.5 / 12 * sin(2 * PI * 6 * tick)) * 255 / 7).roundToInt()
            fillVoice(data, intArrayOf(100, 3, 0xff, 0xe0, 0, 1, 64, 64, pitch and 0xff, pitch shr 8, 0), gauge)
            val g = VoiceGauges(data, 0)
            env.update(g.field(2), g.field(3), g.field(4), g.field(0) * 16)
            text.update(g, 0, env, nanos)
            notes.add(text.cells[SpcCells.NOTE])
            rates.add(text.cells[SpcCells.RATE])
            shifts.add(text.cells[SpcCells.SHIFT])
            titles.add(text.titles[SidRowText.title(2)])
        }
        assertReadable("note", notes, 3.0)
        assertReadable("rate", rates, 3.0)
        assertReadable("shift", shifts, 3.0)
        assertReadable("pitch title", titles, 3.0)
        assertEquals(listOf("≈A4"), notes.texts)
        val rate = rates.texts.last()
        assertTrue(rate, Regex("3\\d{4}…3\\d{4} Hz").matches(rate))
    }

    @Test
    fun `pcm slot with software vibrato`() {
        val text = PcmSlotText()
        val env = PcmEnvelope()
        val data = ByteArray(VoiceGauges.SIZE)
        val rates = History()
        val pitches = History()
        val shifts = History()
        val titles = History()
        run(3.0) { nanos, tick ->
            // driver writes F-number of 60 cents vibrato at 5Hz each tick
            val fnum = (1024 * (2.0.pow(0.5 + 0.6 * sin(2 * PI * 5 * tick) / 12) - 1)).roundToInt()
            val f = IntArray(VoiceGauges.FIELDS)
            f[Pcm.CHIP] = Pcm.CHIP_SCSP
            f[Pcm.STATE] = 3 or Pcm.ST_PLAYING or Pcm.ST_KEY
            f[Pcm.EG] = 200
            f[Pcm.FNUM] = fnum and 0xff
            f[Pcm.FNUM + 1] = fnum shr 8
            f[Pcm.RATE] = 44100 and 0xff
            f[Pcm.RATE + 1] = 44100 shr 8
            fillVoice(data, f, 128, VoiceGauges.Kind.PCM_SLOT)
            val g = VoiceGauges(data, 0)
            env.update(g)
            text.update(g, 0, env, nanos)
            rates.add(text.cells[PcmCells.RATE])
            pitches.add(text.cells[PcmCells.PITCH])
            shifts.add(text.cells[PcmCells.SHIFT])
            titles.add(text.titles[SidRowText.tail(2)])
        }
        assertReadable("rate", rates, 3.0)
        assertReadable("oct/f-num", pitches, 3.0)
        assertReadable("shift", shifts, 3.0)
        assertReadable("registers title", titles, 3.0)
        assertTrue(rates.toString(), rates.texts.last().contains('…'))
    }

    @Test
    fun `card summary with vibrato and tremolo`() {
        val summary = ChipWidgets.LiveSummary()
        val data = ByteArray(VoiceGauges.SIZE)
        val texts = History()
        run(3.0) { nanos, tick ->
            fillVoice(data, IntArray(0), 0, VoiceGauges.Kind.GENERIC)
            val hz = 440 * 2.0.pow(0.6 * sin(2 * PI * 6 * tick) / 12)
            putFloat(data, STATE_OFFSET, hz.toFloat())
            putFloat(data, STATE_OFFSET + 4, (-12 + 3 * sin(2 * PI * 4 * tick)).toFloat())
            summary.update(VoiceGauges(data, 0), nanos)
            texts.add(summary.text)
        }
        assertReadable("summary", texts, 3.0)
        assertTrue(texts.toString(), texts.texts.all { it.startsWith("A4 ") })
    }

    companion object {
        private const val START_NANOS = 1_000_000_000L
        private const val SID_C5 = 0x22D0
        private const val STATE_OFFSET = VoiceGauges.GAUGES * VoiceGauges.COLUMNS * 2

        // C5 at PAL clock with 70 cents vibrato at 6Hz, pulse width sweep, decaying envelope and hard restart
        fun fillJitteringRegs(regs: ByteArray, t: Long) {
            val sec = t / 1000.0
            val freq = (SID_C5 * 2.0.pow(0.7 * sin(2 * PI * 6 * sec) / 12)).roundToInt()
            regs[0] = freq.toByte()
            regs[1] = (freq shr 8).toByte()
            val pulse = (0x800 + 0x600 * sin(2 * PI * 0.5 * sec)).roundToInt()
            regs[2] = pulse.toByte()
            regs[3] = (pulse shr 8).toByte()
            // gate is off for one frame each 500ms, but not at start
            val gate = if (t % 500 in 250 until 266) 0 else 1
            regs[4] = (0x40 or gate).toByte()
            regs[5] = 0x09
            regs[6] = 0x0a
            regs[0x1c] = (240 - (sec * 70).toInt()).coerceAtLeast(0).toByte()
            regs[0x18] = 0x1f
        }

        // voice state with all the frequency gauge columns at [pitchGauge]
        private fun fillVoice(data: ByteArray, fields: IntArray, pitchGauge: Int, kind: Int = VoiceGauges.Kind.SPC_DSP) {
            for (col in 0 until VoiceGauges.COLUMNS) {
                data[(VoiceGauges.FREQUENCY * VoiceGauges.COLUMNS + col) * 2] = pitchGauge.toByte()
                data[(VoiceGauges.FREQUENCY * VoiceGauges.COLUMNS + col) * 2 + 1] = pitchGauge.toByte()
            }
            putFloat(data, STATE_OFFSET, 0f)
            putFloat(data, STATE_OFFSET + 4, -12f)
            data[STATE_OFFSET + 8] = (1 or 2 or 4).toByte()
            data[STATE_OFFSET + 9] = kind.toByte()
            fields.forEachIndexed { idx, value -> data[STATE_OFFSET + 10 + idx] = value.toByte() }
        }

        private fun putFloat(data: ByteArray, at: Int, value: Float) {
            val bits = value.toRawBits()
            for (idx in 0 until 4) {
                data[at + idx] = (bits shr (idx * 8)).toByte()
            }
        }
    }
}
