package app.zxtune.ui.views

import app.zxtune.playback.ChipState
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTextsTest {

    @Test
    fun `text is rebuilt only on key change`() {
        val texts = LiveTexts(1)
        assertTrue(texts.changed(0, 5))
        texts[0] = "5"
        val first = texts[0]
        assertFalse(texts.changed(0, 5))
        assertSame(first, texts[0])
        assertTrue(texts.changed(0, 6))
    }

    @Test
    fun `note with vibrato is kept without delaying real changes`() {
        var note = SidRowText.NONE
        for (frame in 0 until 600) {
            val t = frame / 60.0
            // 6Hz vibrato of 70 cents is wider than the half of semitone
            note = SidRowText.note(60f + 0.7f * sin(2 * PI * 6 * t).toFloat(), note)
            assertEquals(60, note)
        }
        // the very first frame of new note is displayed
        assertEquals(62, SidRowText.note(62.1f, note))
        assertEquals(61, SidRowText.note(60.9f, note))
    }

    // SID voice with vibrato, pulse width modulation and decaying envelope at 60fps
    @Test
    fun `sid voice texts are live and calm`() {
        val text = SidVoiceText()
        val chip = SidDescription.Chip.DEFAULT
        val regs = ByteArray(ChipState.SIZE)
        val history = HashMap<Int, ArrayList<String>>()
        var lastDuty = ""
        for (frame in 0 until 180) {
            val t = frame * 1000L / 60
            fillJitteringRegs(regs, t)
            text.update(ChipState(regs, 0), 0, -1, chip, 0)
            for (idx in SidCells.VOICE.indices) {
                val list = history.getOrPut(idx) { arrayListOf() }
                if (list.lastOrNull() != text.cells[idx]) {
                    list.add(text.cells[idx])
                }
            }
            // value follows registers in the same frame
            val duty = "${SidDescription.pulsePercent((regs[2].toInt() and 0xff) or ((regs[3].toInt() and 0x0f) shl 8))}%"
            assertEquals(duty, text.cells[SidCells.DUTY])
            if (frame > 0 && duty != lastDuty) {
                assertNotEquals(lastDuty, text.cells[SidCells.DUTY])
            }
            lastDuty = duty
        }
        // note is held through vibrato
        assertEquals(listOf("C5"), history[SidCells.NOTE])
        // hard restart is displayed as is
        assertTrue(history[SidCells.STAGE].toString(), history[SidCells.STAGE]!!.size > 1)
        assertTrue(history[SidCells.PITCH]!!.size > 10)
        // static settings are never rebuilt
        assertEquals(1, history[SidCells.ATTACK]!!.size)
    }

    companion object {
        // C5 at PAL clock with 70 cents vibrato at 6Hz, pulse width sweep, decaying envelope and hard restart
        fun fillJitteringRegs(regs: ByteArray, t: Long) {
            val sec = t / 1000.0
            val freq = (0x22D0 * 2.0.pow(0.7 * sin(2 * PI * 6 * sec) / 12)).roundToInt()
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
    }
}
