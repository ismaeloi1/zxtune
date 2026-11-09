/**
 * @file
 * @brief Live texts of wavetable PCM chips (YMW258/MultiPCM, SCSP) dashboard
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import app.zxtune.playback.VoiceGauges
import app.zxtune.playback.VoiceGauges.Pcm
import app.zxtune.ui.views.PcmDescription.Words
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal object PcmCells {
    // SCSP start addresses, hex letters are wider than digits
    private val ADDRESS_TEMPLATES = arrayOf("\$CCCCC", "\$DDDDD", "\$1BCDE")

    // all the values of slot, see [PcmSlotText]
    const val SAMPLE = 0
    const val FORMAT = 1
    const val MODE = 2
    const val LOOP = 3
    const val LENGTH = 4
    const val LOOP_START = 5
    const val POSITION = 6
    const val NOTE = 7
    const val RATE = 8
    const val SHIFT = 9
    const val PITCH = 10
    const val LEVEL = 11
    const val TL = 12
    const val PAN = 13
    const val SEND = 14
    const val STAGE = 15
    const val ATTACK = 16
    const val DECAY1 = 17
    const val SUSTAIN = 18
    const val DECAY2 = 19
    const val RELEASE = 20
    const val KRS = 21
    const val LFO = 22
    const val VIBRATO = 23
    const val TREMOLO = 24
    const val FM = 25
    const val DSP = 26
    const val EFFECT = 27

    // What is heard (sample, loudness, stage, note) is kept the longest, envelope settings are dropped first as
    // they're shown by the curve
    val SLOT = arrayOf(
        CellSpec(Words.SAMPLE, arrayOf("#511", Words.NOISE) + ADDRESS_TEMPLATES, false, 0),
        CellSpec(Words.FORMAT, arrayOf("12-bit"), false, 14),
        CellSpec(Words.MODE, arrayOf("PCM", "FM", Words.NOISE), false, 4),
        CellSpec(Words.LOOP, arrayOf("ping-pong", "forward"), false, 15),
        CellSpec(Words.LENGTH, arrayOf("65535 smp"), true, 16),
        CellSpec(Words.LOOP_START, arrayOf("65535"), true, 17),
        CellSpec(Words.POSITION, arrayOf("65535"), true, 18),
        CellSpec(Words.NOTE, arrayOf("≈C#5"), false, 3),
        CellSpec(Words.RATE, arrayOf("44.74 kHz", "357.9 kHz"), true, 5),
        CellSpec(Words.SHIFT, arrayOf("−96.0 st", "+24.0 st"), true, 13),
        CellSpec(Words.PITCH, arrayOf("−8/1023"), true, 19),
        CellSpec(Words.LEVEL, arrayOf("−95.6 dB", "−∞ dB"), true, 1),
        CellSpec(Words.TL, arrayOf("−95.6 dB"), true, 9),
        CellSpec(Words.PAN, arrayOf(Words.CENTER, "R 21 dB", "L only"), false, 6),
        CellSpec(Words.SEND, arrayOf("−36 dB", Words.OFF), true, 12),
        CellSpec(Words.STAGE, arrayOf(PcmDescription.Stage.RELEASE.word, PcmDescription.Stage.DECAY2.word), false, 2),
        CellSpec(Words.ATTACK, arrayOf("8.10 s", "888 ms", Words.INSTANT), true, 20),
        CellSpec(Words.DECAY1, arrayOf("88.9 s", "888 ms"), true, 21),
        CellSpec(Words.SUSTAIN, arrayOf("−93 dB"), true, 22),
        CellSpec(Words.DECAY2, arrayOf("88.9 s", "888 ms"), true, 23),
        CellSpec(Words.RELEASE, arrayOf("88.9 s", "888 ms"), true, 24),
        CellSpec(Words.KRS, arrayOf("15", Words.OFF), true, 27),
        CellSpec(Words.LFO, arrayOf("172.3 Hz", Words.DASH), true, 10),
        CellSpec(Words.VIBRATO, arrayOf("±494 ¢ triangle"), false, 25),
        CellSpec(Words.TREMOLO, arrayOf("24 dB triangle"), false, 26),
        CellSpec(Words.FM, arrayOf("S32+S31 ±64π"), false, 7),
        CellSpec(Words.DSP, arrayOf("MIXS 15 −36 dB"), false, 8),
        CellSpec(Words.EFFECT, arrayOf("−36 dB"), true, 11),
    )

    // SCSP only cells
    val SCSP_ONLY = intArrayOf(MODE, SEND, FM, DSP, EFFECT)

    // Row of slots table, columns are cells of [SLOT]; captions are displayed once in table header
    val ROW_SOURCE = intArrayOf(SAMPLE, NOTE, RATE, LEVEL, STAGE, PAN, MODE, LOOP, LFO, TL)
    val ROW = arrayOf(
        CellSpec(Words.SAMPLE, arrayOf("#511") + ADDRESS_TEMPLATES, false, 0),
        CellSpec(Words.NOTE, arrayOf("≈C#5"), false, 2),
        CellSpec(Words.RATE, arrayOf("44.74 kHz", "357.9 kHz"), true, 3),
        CellSpec(Words.LEVEL, arrayOf("−95.6 dB", "−∞ dB"), true, 1),
        CellSpec(Words.STAGE, arrayOf(PcmDescription.Stage.RELEASE.word, PcmDescription.Stage.DECAY2.word), false, 4),
        CellSpec(Words.PAN, arrayOf(Words.CENTER, "R 21 dB", "L only"), false, 5),
        CellSpec(Words.MODE, arrayOf("PCM", "FM", Words.NOISE), false, 6),
        CellSpec(Words.LOOP, arrayOf("ping-pong", "forward"), false, 8),
        CellSpec(Words.LFO, arrayOf("172.3 Hz", Words.DASH), true, 7),
        CellSpec("TL", arrayOf("−95.6 dB"), true, 9),
    )
    val ROW_SCSP_ONLY = intArrayOf(6)

    const val CHIP = 0
    const val CLOCK = 1
    const val OUTPUT = 2
    const val PLAYING = 3
    const val VOLUME = 4
    const val EFFECTS = 5
    const val DAC = 6
    const val MEMORY = 7
    const val BANKS = 8

    val GLOBAL = arrayOf(
        CellSpec(Words.CHIP, arrayOf("YMW258/2"), false, 0),
        CellSpec(Words.CLOCK, arrayOf("22.58 MHz"), true, 1),
        CellSpec(Words.OUTPUT, arrayOf("44.74 kHz"), true, 2),
        CellSpec(Words.PLAYING, arrayOf("32/32"), true, 3),
        CellSpec(Words.VOLUME, arrayOf("15/15"), true, 4),
        CellSpec(Words.EFFECTS, arrayOf(Words.BYPASSED, Words.ON), false, 5),
        CellSpec(Words.DAC, arrayOf("18-bit"), false, 6),
        CellSpec(Words.MEMORY, arrayOf("4 Mbit"), false, 7),
        CellSpec(Words.BANKS, arrayOf("\$FF0000 \$FF8000", Words.OFF), false, 8),
    )
    val GLOBAL_SCSP_ONLY = intArrayOf(VOLUME, EFFECTS, DAC, MEMORY)
    val GLOBAL_YMW_ONLY = intArrayOf(BANKS)
}

/**
 * Texts of slot: title bands of output, envelope and pitch panels (see [SidRowText]) and all the cells (see
 * [PcmCells.SLOT]). Values changing at each player tick (note, rate, level) are shown by [NoteReadout] and
 * [RealtimeReadout].
 */
internal class PcmSlotText : SidRowText(PcmCells.SLOT.size) {
    private val noteReadout = NoteReadout()
    private val rateReadout = RealtimeReadout(deadband = 1.0 / 48, modulationSpread = 1.0 / 12, isLogarithmic = true)
    private val levelReadout = RealtimeReadout(deadband = 1.0, modulationSpread = 6.0)
    private val att = FloatArray(2)

    /**
     * @param slot index of slot in chip
     * @param nanos monotonic time of frame, 0 for single picture (values are shown at once)
     */
    fun update(g: VoiceGauges, slot: Int, env: PcmEnvelope, nanos: Long) {
        val scsp = PcmDescription.isScsp(g)
        val playing = PcmDescription.isPlaying(g)
        val stage = PcmDescription.stage(g)
        // the heard note depends on sample's tuning, so it's estimated from output as for S-DSP
        val heardHz = SpcDescription.gaugeHz(g.max(VoiceGauges.FREQUENCY, VoiceGauges.COLUMNS - 1)).toDouble()
        val rate = if (playing) PcmDescription.playbackHz(g).toDouble() else Double.NaN
        val level = if (playing && g.hasLevel) g.level.toDouble() else -PcmDescription.MAX_DB.toDouble()
        if (nanos == 0L) {
            noteReadout.snap(if (playing) heardHz else 0.0)
            if (!rate.isNaN()) rateReadout.snap(rate)
            levelReadout.snap(level)
        } else {
            noteReadout.push(if (playing) heardHz else 0.0, nanos)
            rateReadout.push(rate, nanos)
            levelReadout.push(level, nanos)
        }
        val note = noteReadout.note
        val noteText = if (note == NoteReadout.NONE) "" else Words.ESTIMATED + ChipWidgets.noteName(note)
        // readouts are refreshed a few times per second, texts are rebuilt only then
        val playingBit = if (playing) 1L shl 40 else 0L
        if (cells.changed(PcmCells.RATE, playingBit or rateReadout.version.toLong())) {
            cells[PcmCells.RATE] = if (!playing || rateReadout.value.isNaN()) {
                Words.DASH
            } else {
                rangeWord(rateReadout) { PcmDescription.hzWord(it.toFloat()) }
            }
        }
        if (cells.changed(PcmCells.LEVEL, playingBit or levelReadout.version.toLong())) {
            cells[PcmCells.LEVEL] = if (!playing) Words.DASH else rangeWord(levelReadout) { PcmDescription.dbWord(it.toFloat()) }
        }
        val rateText = cells[PcmCells.RATE]
        val keyOn = PcmDescription.isKeyOn(g)

        // titles: meaning in words, raw registers are secondary
        val sampleKey = (slot.toLong() shl 40) or (PcmDescription.startAddress(g).toLong() shl 8) or
            (g.field(Pcm.FORMAT).toLong() shl 1) or (if (keyOn) 1L else 0L) or (PcmDescription.tone(g).toLong() shl 48)
        if (titles.changed(title(0), sampleKey)) {
            titles[title(0)] = "S${slot + 1} ${PcmDescription.sampleWord(g)}" + if (keyOn) " ${SidDescription.GATE_MARK}" else ""
            // address of SCSP sample is already in title
            titles[tail(0)] = when {
                PcmDescription.source(g) != 0 -> ""
                scsp -> "${PcmDescription.bits(g)}-bit ${PcmDescription.loopWord(g)}"
                else -> "#${PcmDescription.hex(PcmDescription.tone(g), 3)} ${PcmDescription.bits(g)}-bit"
            }
        }
        val egDb = PcmDescription.envelopeDb(g)
        if (titles.changed(title(1), (stage.ordinal.toLong() shl 16) or (egDb * 10).roundToLong().and(0xffff))) {
            titles[title(1)] = if (stage == PcmDescription.Stage.OFF) Words.OFF else "${stage.word} ${PcmDescription.dbWord(egDb)}"
        }
        val envRegs = (g.field(Pcm.AR).toLong() shl 40) or (g.field(Pcm.D1R).toLong() shl 32) or
            (g.field(Pcm.DL).toLong() shl 24) or (g.field(Pcm.D2R).toLong() shl 16) or (g.field(Pcm.RR).toLong() shl 8)
        if (titles.changed(tail(1), envRegs)) {
            titles[tail(1)] = "AR ${g.field(Pcm.AR)} D1 ${g.field(Pcm.D1R)} DL ${g.field(Pcm.DL)} " +
                "D2 ${g.field(Pcm.D2R)} RR ${g.field(Pcm.RR)}"
        }
        if (titles.changed(title(2), (note.toLong() shl 42) or playingBit or rateReadout.version.toLong())) {
            titles[title(2)] = if (noteText.isEmpty()) rateText else "$noteText $rateText"
        }
        val pitchKey = (PcmDescription.octave(g) shl 12) or PcmDescription.fnum(g)
        if (titles.changed(tail(2), pitchKey)) {
            titles[tail(2)] = "OCT ${PcmDescription.octave(g)} FNS ${PcmDescription.hex(PcmDescription.fnum(g), 3)}"
            cells[PcmCells.PITCH] = "${PcmDescription.octave(g)}/${PcmDescription.fnum(g)}"
            cells[PcmCells.SHIFT] = PcmDescription.semitonesWord(PcmDescription.shiftSemitones(g))
        }

        // cells
        if (cells.changed(PcmCells.SAMPLE, sampleKey shr 1)) {
            cells[PcmCells.SAMPLE] = PcmDescription.sampleWord(g)
            cells[PcmCells.FORMAT] = if (PcmDescription.source(g) != 0) Words.DASH else "${PcmDescription.bits(g)}-bit"
        }
        if (cells.changed(PcmCells.MODE, g.field(Pcm.MODE) or (g.field(Pcm.MDL) shl 8) or (g.field(Pcm.FORMAT) shl 16))) {
            cells[PcmCells.MODE] = PcmDescription.modeWord(g)
        }
        if (cells.changed(PcmCells.LOOP, PcmDescription.loopMode(g))) {
            cells[PcmCells.LOOP] = PcmDescription.loopWord(g)
        }
        if (cells.changed(PcmCells.LENGTH, PcmDescription.loopEnd(g))) {
            cells[PcmCells.LENGTH] = "${PcmDescription.loopEnd(g)} smp"
        }
        if (cells.changed(PcmCells.LOOP_START, PcmDescription.loopStart(g))) {
            cells[PcmCells.LOOP_START] = "${PcmDescription.loopStart(g)}"
        }
        val pos = if (playing) PcmDescription.position(g) else -1
        if (cells.changed(PcmCells.POSITION, pos)) {
            cells[PcmCells.POSITION] = if (pos < 0) Words.DASH else "$pos"
        }
        if (cells.changed(PcmCells.NOTE, note)) {
            cells[PcmCells.NOTE] = noteText.ifEmpty { Words.DASH }
        }
        if (cells.changed(PcmCells.TL, g.field(Pcm.TL) or (g.field(Pcm.CHIP) shl 8))) {
            cells[PcmCells.TL] = PcmDescription.dbWord(PcmDescription.totalLevelDb(g))
        }
        if (cells.changed(PcmCells.PAN, g.field(Pcm.PAN) or (g.field(Pcm.CHIP) shl 8))) {
            cells[PcmCells.PAN] = PcmDescription.panWord(g)
        }
        if (cells.changed(PcmCells.SEND, g.field(Pcm.SEND))) {
            cells[PcmCells.SEND] = PcmDescription.sendWord(g.field(Pcm.SEND))
        }
        if (cells.changed(PcmCells.STAGE, stage.ordinal)) {
            cells[PcmCells.STAGE] = stage.word
        }
        val rates = g.field(Pcm.RATES).toLong() or (g.field(Pcm.RATES + 1).toLong() shl 8) or
            (g.field(Pcm.RATES + 2).toLong() shl 16) or (g.field(Pcm.RATES + 3).toLong() shl 24) or
            (g.field(Pcm.DL).toLong() shl 32) or (g.field16(Pcm.RATE).toLong() shl 40)
        if (cells.changed(PcmCells.ATTACK, rates)) {
            cells[PcmCells.ATTACK] = PcmDescription.msWord(env.attackMs)
            cells[PcmCells.DECAY1] = PcmDescription.msWord(env.decay1Ms)
            cells[PcmCells.SUSTAIN] = PcmDescription.dbWord(env.sustainDb)
            cells[PcmCells.DECAY2] = PcmDescription.msWord(env.decay2Ms)
            cells[PcmCells.RELEASE] = PcmDescription.msWord(env.releaseFullMs)
        }
        val krs = g.field(Pcm.KRS)
        if (cells.changed(PcmCells.KRS, krs)) {
            // KRS 15 disables key scaling
            cells[PcmCells.KRS] = if (krs == 15) Words.OFF else "$krs"
        }
        val lfoKey = g.field(Pcm.LFOF) or (g.field(Pcm.PLFO) shl 8) or (g.field(Pcm.ALFO) shl 16) or (g.field(Pcm.CHIP) shl 24)
        if (cells.changed(PcmCells.LFO, lfoKey)) {
            val pm = g.field(Pcm.PLFO) and 7
            val am = g.field(Pcm.ALFO) and 7
            cells[PcmCells.LFO] = if (pm == 0 && am == 0) Words.DASH else PcmDescription.hzWord(PcmDescription.lfoHz(g))
            val wave = { field: Int -> if (scsp) " ${PcmDescription.lfoWave(g.field(field))}" else "" }
            cells[PcmCells.VIBRATO] = if (pm == 0) Words.OFF else "±${centsWord(PcmDescription.vibratoCents(g))} ¢${wave(Pcm.PLFO)}"
            cells[PcmCells.TREMOLO] = if (am == 0) Words.OFF else "${PcmDescription.dbNumber(PcmDescription.tremoloDb(g))} dB${wave(Pcm.ALFO)}"
        }
        val fmKey = g.field(Pcm.MDL) or (g.field(Pcm.MDXSL) shl 4) or (g.field(Pcm.MDYSL) shl 10) or (g.field(Pcm.MODE) shl 16) or (slot shl 24)
        if (cells.changed(PcmCells.FM, fmKey)) {
            cells[PcmCells.FM] = PcmDescription.modulationWord(g, slot)
        }
        if (cells.changed(PcmCells.DSP, g.field(Pcm.ISEL) or (g.field(Pcm.IMXL) shl 4))) {
            cells[PcmCells.DSP] = PcmDescription.dspSendWord(g)
        }
        if (cells.changed(PcmCells.EFFECT, g.field(Pcm.EFSDL) or (slot shl 4))) {
            cells[PcmCells.EFFECT] = PcmDescription.effectWord(g, slot)
        }
    }

    // value or range of modulated value, e.g. "−12…−6 dB", the latter is formatted with unit
    private inline fun rangeWord(readout: RealtimeReadout, format: (Double) -> String): String {
        if (!readout.isModulated) {
            return format(readout.value)
        }
        val low = format(readout.low)
        val high = format(readout.high)
        val unit = high.substringAfterLast(' ', "")
        return if (unit.isNotEmpty() && low.endsWith(" $unit")) "${low.removeSuffix(" $unit")}…$high" else "$low…$high"
    }

    private fun centsWord(cents: Float) = if (cents < 10f) String.format(java.util.Locale.US, "%.1f", cents) else "${cents.roundToInt()}"
}

/**
 * Texts of chip-wide cells, see [PcmCells.GLOBAL]
 */
internal class PcmGlobalText {
    val cells = LiveTexts(PcmCells.GLOBAL.size)

    fun update(g: VoiceGauges, chipName: String, slots: Int) {
        if (cells.changed(PcmCells.CHIP, chipName.hashCode())) {
            cells[PcmCells.CHIP] = chipName
        }
        val clock = PcmDescription.clockHz(g)
        if (cells.changed(PcmCells.CLOCK, clock)) {
            cells[PcmCells.CLOCK] = String.format(java.util.Locale.US, "%.2f MHz", clock / 1e6)
        }
        if (cells.changed(PcmCells.OUTPUT, PcmDescription.outputRate(g))) {
            cells[PcmCells.OUTPUT] = PcmDescription.hzWord(PcmDescription.outputRate(g).toFloat())
        }
        val active = g.field(Pcm.ACTIVE)
        if (cells.changed(PcmCells.PLAYING, (active shl 8) or slots)) {
            cells[PcmCells.PLAYING] = "$active/$slots"
        }
        if (cells.changed(PcmCells.VOLUME, g.field(Pcm.MVOL))) {
            cells[PcmCells.VOLUME] = "${g.field(Pcm.MVOL)}/15"
        }
        val global = g.field(Pcm.GLOBAL)
        if (cells.changed(PcmCells.EFFECTS, global)) {
            cells[PcmCells.EFFECTS] = if (global and Pcm.GL_DSP != 0) Words.ON else Words.BYPASSED
            cells[PcmCells.DAC] = if (global and Pcm.GL_DAC18B != 0) "18-bit" else "16-bit"
            cells[PcmCells.MEMORY] = if (global and Pcm.GL_MEM4MB != 0) "4 Mbit" else "1 Mbit"
        }
        val banks = g.field16(Pcm.BANK0).toLong() or (g.field16(Pcm.BANK1).toLong() shl 16) or ((global and Pcm.GL_BANKING).toLong() shl 32)
        if (cells.changed(PcmCells.BANKS, banks)) {
            cells[PcmCells.BANKS] = if (global and Pcm.GL_BANKING == 0) {
                Words.OFF
            } else {
                // addresses of banks are stored without 16 lower bits
                val bank0 = PcmDescription.hex(g.field16(Pcm.BANK0) shl 16, 6)
                val bank1 = PcmDescription.hex(g.field16(Pcm.BANK1) shl 16, 6)
                "\$$bank0 \$$bank1"
            }
        }
    }
}
