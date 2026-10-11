package app.zxtune.ui.views

import app.zxtune.playback.ChipState
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableValueTest {

    @Test
    fun `first value is shown immediately without fade`() {
        val value = StableValue()
        assertTrue(value.update(5, 1000))
        assertEquals(5, value.value)
        assertEquals(1f, value.fade(1000), 0f)
    }

    @Test
    fun `discrete value is switched after confirmation`() {
        val value = StableValue(confirmMs = 80, holdMs = 250)
        value.update(1, 0)
        assertFalse(value.update(2, 1000))
        assertFalse(value.update(2, 1079))
        assertTrue(value.update(2, 1080))
        assertEquals(2, value.value)
        assertEquals(1080, value.changedAt)
        // fades in after change
        assertEquals(0f, value.fade(1080), 0f)
        assertEquals(0.5f, value.fade(1155), 0.01f)
        assertEquals(1f, value.fade(1300), 0f)
    }

    @Test
    fun `short glitches are not shown`() {
        val value = StableValue(confirmMs = 80, holdMs = 250, staleMs = 1000)
        value.update(1, 0)
        // e.g. one frame of hard restart every 100ms
        for (t in 1000L until 3000L step 20) {
            val input = if (t % 100 == 0L) 0 else 1
            assertFalse("at $t", value.update(input, t))
        }
        assertEquals(1, value.value)
    }

    @Test
    fun `changes are rate limited by hold time`() {
        val value = StableValue(confirmMs = 0, holdMs = 200)
        value.update(0, 0)
        var changes = 0
        // new value every frame for one second
        for (t in 1000L until 2000L step 10) {
            if (value.update(t.toInt(), t)) {
                ++changes
            }
        }
        assertEquals(5, changes)
    }

    @Test
    fun `continuous value follows ramp at limited rate`() {
        val value = StableValue(confirmMs = 80, holdMs = 250, deadband = 2)
        value.update(100, 0)
        val shown = ArrayList<Int>()
        // linear decay 100..0 in one second, 60fps
        for (frame in 0..60) {
            val t = 1000L + frame * 1000 / 60
            if (value.update(100 - frame * 100 / 60, t)) {
                shown.add(value.value)
            }
        }
        // ~4 updates per second
        assertTrue(shown.toString(), shown.size in 3..5)
        assertTrue(shown.toString(), shown.zipWithNext().all { it.first > it.second })
        // final value is reached after it stops changing
        value.update(0, 3000)
        assertEquals(0, value.value)
    }

    @Test
    fun `small changes are settled when input stops`() {
        val value = StableValue(confirmMs = 80, holdMs = 250, deadband = 2, settleMs = 400)
        value.update(10, 0)
        assertFalse(value.update(9, 1000))
        assertFalse(value.update(9, 1399))
        assertTrue(value.update(9, 1400))
        assertEquals(9, value.value)
    }

    @Test
    fun `jitter inside dead band is shown not more often than stale period`() {
        val value = StableValue(confirmMs = 80, holdMs = 250, deadband = 2, settleMs = 400, staleMs = 1000)
        value.update(50, 0)
        var changes = 0
        for (t in 1000L until 5000L step 10) {
            // never settles, never leaves dead band
            if (value.update(if ((t / 10) % 2 == 0L) 51 else 49, t)) {
                ++changes
            }
        }
        assertTrue("$changes", changes <= 4)
    }

    @Test
    fun `alternating discrete values without shown one are shown eventually`() {
        val value = StableValue(confirmMs = 80, holdMs = 250, staleMs = 1000)
        value.update(0, 0)
        var changes = 0
        // arpeggio of 3 notes, 20ms each
        for (t in 1000L until 3000L step 20) {
            if (value.update(1 + ((t / 20) % 3).toInt(), t)) {
                ++changes
            }
        }
        assertTrue(value.value in 1..3)
        assertTrue("$changes", changes in 1..2)
    }

    @Test
    fun `note with vibrato is kept`() {
        val value = StableValue()
        val center = 60f
        var changes = 0
        for (frame in 0 until 600) {
            val t = frame * 1000L / 60
            // 6Hz vibrato of 70 cents is wider than the half of semitone
            val input = center + 0.7f * sin(2 * PI * 6 * t / 1000).toFloat()
            if (value.update(input, SidRowText.NOTE_MARGIN, t) && frame != 0) {
                ++changes
            }
        }
        assertEquals(0, changes)
        assertEquals(60, value.value)
        // but real note change is followed
        for (t in 10000L until 10200L step 16) {
            value.update(62.1f, SidRowText.NOTE_MARGIN, t)
        }
        assertEquals(62, value.value)
    }

    @Test
    fun `texts change time is tracked per field`() {
        val texts = StableTexts(2)
        texts.set(0, "a", 100)
        texts.set(1, "b", 100)
        assertEquals(1f, texts.fade(0, 100), 0f)
        texts.set(0, "c", 200)
        texts.set(1, "b", 200)
        assertEquals(0f, texts.fade(0, 200), 0f)
        assertEquals(1f, texts.fade(1, 200), 0f)
        assertEquals("c", texts[0])
    }

    // SID voice with vibrato, pulse width modulation and decaying envelope at 60fps for 3 seconds
    @Test
    fun `sid voice texts are calm`() {
        val text = SidVoiceText()
        val chip = SidDescription.Chip.DEFAULT
        val regs = ByteArray(ChipState.SIZE)
        val history = HashMap<Int, ArrayList<String>>()
        for (frame in 0 until 180) {
            val t = frame * 1000L / 60
            fillJitteringRegs(regs, t)
            text.update(ChipState(regs, 0), 0, -1, chip, 0, t)
            for (idx in 0 until SidRowText.SIZE) {
                val list = history.getOrPut(idx) { arrayListOf() }
                if (list.lastOrNull() != text.texts[idx]) {
                    list.add(text.texts[idx])
                }
            }
        }
        // note is held through vibrato
        assertEquals(listOf("C5"), history[SidRowText.main(2)])
        assertEquals(listOf("at C5"), history[SidRowText.slot(SidDescription.SLOT_PITCH)]?.distinct())
        // gate glitches are not shown
        assertEquals(listOf(SidDescription.GATE_MARK), history[SidRowText.tail(0)])
        // vibrato around the note doesn't change frequency too
        assertEquals(listOf("523Hz"), history[SidRowText.tail(2)])
        // continuous values are changing, but not faster than 5 times per second (raw registers)
        for (idx in intArrayOf(SidRowText.main(0), SidRowText.main(1), SidRowText.title(2))) {
            val changes = history[idx]!!.size - 1
            assertTrue("$idx: ${history[idx]}", changes in 1..15)
        }
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
