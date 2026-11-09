/**
 * @file
 * @brief Live texts of S-DSP dashboard rows
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import app.zxtune.playback.VoiceGauges
import app.zxtune.ui.views.SpcDescription.Words
import kotlin.math.roundToInt

internal object SpcCells {
    const val SAMPLE = 0
    const val LEVEL = 1
    const val STAGE = 2
    const val NOTE = 3
    const val RATE = 4
    const val SHIFT = 5
    const val VOL_L = 6
    const val VOL_R = 7
    const val ECHO = 8
    const val MOD = 9
    const val ENV1 = 10
    const val ENV2 = 11
    const val ENV3 = 12
    const val ENV4 = 13
    const val LENGTH = 14

    // What is heard (sample, loudness, stage, note) is kept the longest, envelope settings are dropped first as
    // they're shown by the curve. Envelope cells have captions of ADSR or GAIN mode.
    val VOICE = arrayOf(
        CellSpec(Words.SAMPLE, arrayOf("#127 loop", "#255 once"), false, 0),
        CellSpec(Words.LEVEL, arrayOf("100%"), true, 1),
        CellSpec(Words.STAGE, arrayOf(Words.GAIN_MODES[0], Words.GAIN_MODES[3], SpcDescription.Stage.SUSTAIN.word, SpcDescription.Stage.RELEASE.word), false, 2),
        CellSpec(Words.NOTE, arrayOf("≈C#5"), false, 3),
        CellSpec(Words.RATE, arrayOf("32000 Hz", "127992 Hz"), true, 5),
        CellSpec(Words.SHIFT, arrayOf("−12.0 st"), true, 12),
        CellSpec(Words.VOL_L, arrayOf("−100%"), true, 4),
        CellSpec(Words.VOL_R, arrayOf("−100%"), true, 6),
        CellSpec(Words.ECHO, arrayOf(Words.ON, Words.OFF), false, 7),
        CellSpec(Words.MOD, arrayOf("Noise+PMod", "PMod V8"), false, 8),
        CellSpec(Words.ATTACK, arrayOf("888 ms", "6.0 ms", Words.INSTANT), true, 9, true),
        CellSpec(Words.TARGET, arrayOf("888 ms", "100%"), true, 10, true),
        CellSpec(Words.SUSTAIN, arrayOf("100%"), true, 11, true),
        CellSpec(Words.FADE, arrayOf("888 ms", "8.8 s"), true, 13, true),
        CellSpec(Words.LENGTH, arrayOf("65535 smp"), true, 14),
    )

    const val MAIN_L = 0
    const val MAIN_R = 1
    const val ECHO_L = 2
    const val ECHO_R = 3
    const val DELAY = 4
    const val FEEDBACK = 5
    const val ECHO_WRITE = 6
    const val NOISE = 7
    const val OUTPUT = 8
    const val FIR = 9
    const val ECHO_RAM = 10
    const val DIR = 11

    val GLOBAL = arrayOf(
        CellSpec(Words.MAIN_L, arrayOf("−100%"), true, 0),
        CellSpec(Words.MAIN_R, arrayOf("−100%"), true, 1),
        CellSpec(Words.ECHO_L, arrayOf("−100%"), true, 2),
        CellSpec(Words.ECHO_R, arrayOf("−100%"), true, 3),
        CellSpec(Words.DELAY, arrayOf("240 ms"), true, 4),
        CellSpec(Words.FEEDBACK, arrayOf("−100%"), true, 5),
        CellSpec(Words.ECHO_WRITE, arrayOf(Words.ON, Words.OFF), false, 7),
        CellSpec(Words.NOISE_CLOCK, arrayOf("16 kHz", "1.1 kHz"), true, 8),
        CellSpec(Words.OUTPUT, arrayOf(Words.MUTED, Words.RESET), false, 6),
        CellSpec(Words.FIR, arrayOf("+7F 00 00 00 00 00 00 00"), false, 10),
        CellSpec(Words.ECHO_RAM, arrayOf("\$FF00 30 KB", "\$C000 10 KB", "\$D800 28 KB"), false, 11),
        CellSpec(Words.DIR, arrayOf("\$FF00"), false, 12),
    )
}

/**
 * Texts of voice row: title bands of sample, envelope and pitch panels, cells and captions of envelope cells.
 * Values changing at each player tick (note, pitch register with vibrato, envelope level) are shown by [NoteReadout]
 * and [RealtimeReadout].
 */
internal class SpcVoiceText : SidRowText(SpcCells.VOICE.size) {
    val captions = LiveTexts(SpcCells.VOICE.size)
    private val noteReadout = NoteReadout()
    private val rateReadout = RealtimeReadout(deadband = 1.0 / 48, modulationSpread = 1.0 / 12, isLogarithmic = true)
    private val pitchRegister = RealtimeReadout.ofRegister()
    private val levelReadout = RealtimeReadout.ofRegister()

    init {
        for (idx in SpcCells.VOICE.indices) {
            captions[idx] = SpcCells.VOICE[idx].caption
        }
    }

    /**
     * @param nanos monotonic time of frame, 0 for single picture (values are shown at once)
     */
    fun update(g: VoiceGauges, voice: Int, env: SpcEnvelope, nanos: Long = 0L) {
        val envx = g.field(0)
        val mode = g.field(1)
        val adsr1 = g.field(2)
        val adsr2 = g.field(3)
        val gain = g.field(4)
        val srcn = g.field(5)
        val volL = g.signedField(6)
        val volR = g.signedField(7)
        val written = g.field(8) or ((g.field(9) and 0x3f) shl 8)
        val flags = g.field(10)
        val adsr = SpcDescription.isAdsr(adsr1)
        val hasSample = SpcSample.hasInfo(g)
        val loops = hasSample && SpcSample.loops(g)
        levelReadout.update(SpcDescription.envelopePercent(envx).toDouble(), nanos)
        val level = levelReadout.register()
        val stage = SpcDescription.Stage.of(mode)
        // pitch of output is estimated from audio, as the note depends on sample contents
        val hz = SpcDescription.gaugeHz(g.max(VoiceGauges.FREQUENCY, VoiceGauges.COLUMNS - 1))
        noteReadout.update(if (envx == 0) 0.0 else hz.toDouble(), nanos)
        rateReadout.update(if (written == 0) Double.NaN else SpcDescription.rateHz(written).toDouble(), nanos)
        pitchRegister.update(written.toDouble(), nanos)
        val note = noteReadout.note
        val noteText = if (note == NoteReadout.NONE) "" else Words.ESTIMATED + ChipWidgets.noteName(note)
        val pitch = pitchRegister.register()
        val rate = if (rateReadout.value.isNaN()) 0 else rateReadout.value.roundToInt()

        val sampleKind = if (!hasSample) {
            0
        } else if (loops) {
            1
        } else {
            2
        }
        val sampleWord = when (sampleKind) {
            1 -> " ${Words.LOOP}"
            2 -> " ${Words.ONCE}"
            else -> ""
        }
        // released voice at zero level is silent
        val silent = mode == SpcDescription.MODE_RELEASE && envx == 0

        // titles: meaning in words, raw registers are secondary
        val keyOn = if (g.isKeyOn) 1 else 0
        if (titles.changed(title(0), (voice shl 12) or (srcn shl 4) or (sampleKind shl 1) or keyOn)) {
            titles[title(0)] = "V${voice + 1} #$srcn$sampleWord" + if (keyOn != 0) " ${SidDescription.GATE_MARK}" else ""
            titles[tail(0)] = "SRCN ${SpcDescription.hex2(srcn)}"
        }
        val stageKey = if (mode == SpcDescription.MODE_GAIN) {
            0x100 or gain
        } else if (silent) {
            -1
        } else {
            mode
        }
        if (titles.changed(title(1), (stageKey.toLong() shl 8) or level.toLong())) {
            val word = when {
                mode == SpcDescription.MODE_GAIN -> SpcDescription.gainWord(gain)
                silent -> Words.OFF
                else -> stage.word
            }
            titles[title(1)] = "$word ${SidDescription.percent(level)}"
            cells[SpcCells.STAGE] = word
            cells[SpcCells.LEVEL] = SidDescription.percent(level)
        }
        val envRegs = if (adsr) (1 shl 16) or (adsr1 shl 8) or adsr2 else gain
        if (titles.changed(tail(1), envRegs)) {
            titles[tail(1)] = if (adsr) {
                "ADSR ${SpcDescription.hex2(adsr1)} ${SpcDescription.hex2(adsr2)}"
            } else {
                "GAIN ${SpcDescription.hex2(gain)}"
            }
        }
        if (titles.changed(title(2), (note.toLong() shl 32) or rateReadout.version.toLong())) {
            val rateText = if (rate == 0) Words.OFF else "%.1f kHz".format(java.util.Locale.US, rate / 1000f)
            titles[title(2)] = if (noteText.isEmpty()) rateText else "$noteText $rateText"
        }
        if (titles.changed(tail(2), pitch)) {
            titles[tail(2)] = "Pitch ${SpcDescription.hex2(pitch shr 8)}${SpcDescription.hex2(pitch)}"
        }

        // cells
        if (cells.changed(SpcCells.SAMPLE, (srcn shl 2) or sampleKind)) {
            cells[SpcCells.SAMPLE] = "#$srcn$sampleWord"
        }
        if (cells.changed(SpcCells.NOTE, note)) {
            cells[SpcCells.NOTE] = noteText.ifEmpty { Words.DASH }
        }
        if (cells.changed(SpcCells.RATE, rateReadout.version)) {
            if (rate == 0) {
                cells[SpcCells.RATE] = Words.DASH
            } else {
                cells.set(SpcCells.RATE, rateReadout) { SidDescription.hzWord(it.roundToInt()) }
            }
        }
        if (cells.changed(SpcCells.SHIFT, pitch)) {
            cells[SpcCells.SHIFT] = if (pitch == 0) {
                Words.DASH
            } else {
                val st = SpcDescription.shiftSemitones(pitch)
                (if (st < -0.05f) {
                    "−"
                } else if (st > 0.05f) {
                    "+"
                } else {
                    ""
                }) + "%.1f st".format(java.util.Locale.US, kotlin.math.abs(st))
            }
        }
        if (cells.changed(SpcCells.VOL_L, volL)) {
            cells[SpcCells.VOL_L] = SpcDescription.signedPercent(SpcDescription.volumePercent(volL))
        }
        if (cells.changed(SpcCells.VOL_R, volR)) {
            cells[SpcCells.VOL_R] = SpcDescription.signedPercent(SpcDescription.volumePercent(volR))
        }
        if (cells.changed(SpcCells.ECHO, flags and 1)) {
            cells[SpcCells.ECHO] = if (flags and 1 != 0) Words.ON else Words.OFF
        }
        if (cells.changed(SpcCells.MOD, ((flags shr 1) and 3) or (voice shl 2))) {
            cells[SpcCells.MOD] = modWord(flags, voice)
        }
        if (cells.changed(SpcCells.LENGTH, if (hasSample) SpcSample.blocks(g) else -1)) {
            cells[SpcCells.LENGTH] = if (hasSample) "${SpcSample.blocks(g) * SpcSample.BLOCK_SAMPLES} smp" else Words.DASH
        }
        updateEnvelopeCells(adsr, adsr2, gain, env)
    }

    private fun updateEnvelopeCells(adsr: Boolean, adsr2: Int, gain: Int, env: SpcEnvelope) {
        // program is already updated for current registers, so its key is enough
        val key = if (adsr) (1L shl 40) or (env.attackSamples.toLong() shl 20) or env.decaySamples.toLong() xor (adsr2.toLong() shl 48) else gain.toLong()
        if (!cells.changed(SpcCells.ENV1, key)) {
            return
        }
        if (adsr) {
            captions[SpcCells.ENV1] = Words.ATTACK
            captions[SpcCells.ENV2] = Words.DECAY
            captions[SpcCells.ENV3] = Words.SUSTAIN
            captions[SpcCells.ENV4] = Words.FADE
            cells[SpcCells.ENV1] = SpcDescription.durationWord(env.attackSamples)
            cells[SpcCells.ENV2] = if (env.decaySamples == 0) "0 ms" else SpcDescription.durationWord(env.decaySamples)
            cells[SpcCells.ENV3] = SidDescription.percent(SpcDescription.sustainPercent(adsr2))
            cells[SpcCells.ENV4] = if (env.fadeSamples < 0) Words.INFINITE else SpcDescription.durationWord(env.fadeSamples)
        } else {
            captions[SpcCells.ENV1] = Words.RAMP
            captions[SpcCells.ENV2] = Words.TARGET
            captions[SpcCells.ENV3] = ""
            captions[SpcCells.ENV4] = ""
            val direct = SpcDescription.isDirectGain(gain)
            val hold = !direct && SpcDescription.gainRate(gain) == 0
            cells[SpcCells.ENV1] = when {
                direct -> Words.INSTANT
                hold -> Words.INFINITE
                else -> SpcDescription.durationWord(env.rampSamples)
            }
            cells[SpcCells.ENV2] = when {
                direct -> SidDescription.percent((gain and 0x7f) * 100 / 127)
                hold -> Words.DASH
                SpcDescription.gainMode(gain) <= SpcDescription.GAIN_EXP_DOWN -> "0%"
                else -> "100%"
            }
            cells[SpcCells.ENV3] = ""
            cells[SpcCells.ENV4] = ""
        }
    }

    companion object {
        // noise replaces sample output, pitch modulation is by output of the previous voice
        fun modWord(flags: Int, voice: Int): String {
            val noise = flags and 4 != 0
            val pmod = flags and 2 != 0 && voice != 0
            return when {
                noise && pmod -> "${Words.NOISE}+${Words.PMOD}"
                noise -> Words.NOISE
                pmod -> "${Words.PMOD} V$voice"
                else -> Words.DASH
            }
        }
    }
}

/**
 * Texts of global row: volumes, echo and FIR panels and cells
 */
internal class SpcGlobalText : SidRowText(SpcCells.GLOBAL.size) {
    fun update(gl: SpcGlobals) {
        val mainL = gl.mainLeft
        val mainR = gl.mainRight
        val echoL = gl.echoLeft
        val echoR = gl.echoRight
        if (titles.changed(title(0), 0)) {
            titles[title(0)] = Words.VOLUME
        }
        if (titles.changed(tail(0), ((mainL and 0xff) shl 8) or (mainR and 0xff))) {
            titles[tail(0)] = "MVOL ${SpcDescription.hex2(mainL)} ${SpcDescription.hex2(mainR)}"
        }
        val delay = gl.delay
        val feedback = gl.feedback
        if (titles.changed(title(1), (delay shl 8) or (feedback and 0xff))) {
            cells[SpcCells.DELAY] = delayWord(delay)
            cells[SpcCells.FEEDBACK] = SpcDescription.signedPercent(SpcDescription.volumePercent(feedback))
            titles[title(1)] = "${Words.ECHO} ${cells[SpcCells.DELAY]} ${cells[SpcCells.FEEDBACK]}"
            titles[tail(1)] = "EDL $delay EFB ${SpcDescription.hex2(feedback)}"
        }
        var firKey = 0L
        for (c in gl.fir) {
            firKey = (firKey shl 8) or (c.toLong() and 0xff)
        }
        if (titles.changed(title(2), firKey)) {
            titles[title(2)] = Words.ECHO_FILTER
            titles[tail(2)] = Words.FIR
            cells[SpcCells.FIR] = gl.fir.joinToString(" ") { SpcDescription.hex2(it) }
        }
        if (cells.changed(SpcCells.MAIN_L, mainL)) {
            cells[SpcCells.MAIN_L] = SpcDescription.signedPercent(SpcDescription.volumePercent(mainL))
        }
        if (cells.changed(SpcCells.MAIN_R, mainR)) {
            cells[SpcCells.MAIN_R] = SpcDescription.signedPercent(SpcDescription.volumePercent(mainR))
        }
        if (cells.changed(SpcCells.ECHO_L, echoL)) {
            cells[SpcCells.ECHO_L] = SpcDescription.signedPercent(SpcDescription.volumePercent(echoL))
        }
        if (cells.changed(SpcCells.ECHO_R, echoR)) {
            cells[SpcCells.ECHO_R] = SpcDescription.signedPercent(SpcDescription.volumePercent(echoR))
        }
        val flags = gl.flags
        if (cells.changed(SpcCells.ECHO_WRITE, flags and 0x20)) {
            cells[SpcCells.ECHO_WRITE] = if (gl.isEchoWriteEnabled) Words.ON else Words.OFF
        }
        if (cells.changed(SpcCells.NOISE, flags and 31)) {
            cells[SpcCells.NOISE] = SpcDescription.hzWord(SpcDescription.noiseHz(flags))
        }
        if (cells.changed(SpcCells.OUTPUT, flags and 0xc0)) {
            cells[SpcCells.OUTPUT] = when {
                gl.isReset -> Words.RESET
                gl.isMuted -> Words.MUTED
                else -> Words.ON
            }
        }
        if (cells.changed(SpcCells.ECHO_RAM, (gl.echoStart shl 8) or delay)) {
            val bytes = SpcDescription.echoBufferBytes(delay)
            val size = if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB"
            cells[SpcCells.ECHO_RAM] = "\$${SpcDescription.hex2(gl.echoStart)}00 $size"
        }
        if (cells.changed(SpcCells.DIR, gl.dir)) {
            cells[SpcCells.DIR] = "\$${SpcDescription.hex2(gl.dir)}00"
        }
    }

    companion object {
        fun delayWord(edl: Int) = if (edl == 0) "<1 ms" else "${SpcDescription.echoDelayMs(edl).roundToInt()} ms"
    }
}
