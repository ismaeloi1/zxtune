package app.zxtune.ui.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FilterHistoryTest {

    @Test
    fun `empty history`() {
        val history = FilterHistory(4)
        assertEquals(0, history.size)
        assertNull(history.latest())
        assertEquals(0L, history.spanMs())
    }

    @Test
    fun `ring wrap keeps newest frames in order`() {
        val history = FilterHistory(4)
        for (idx in 0 until 10) {
            history.push(idx * 10L) { it.resonance = idx }
            assertEquals(minOf(idx + 1, 4), history.size)
            assertEquals(idx, history.latest()!!.resonance)
        }
        for (idx in 0 until 4) {
            val frame = history.frame(idx)
            assertEquals(6 + idx, frame.resonance)
            assertEquals((6 + idx) * 10L, frame.timeMs)
        }
        assertEquals(30L, history.spanMs())
    }

    @Test
    fun `frames are reused`() {
        val history = FilterHistory(3)
        val frames = (0 until 3).map { idx ->
            history.push(idx.toLong()) {}
            history.latest()!!
        }
        val voices = frames.map { it.voices[0] }
        for (idx in 3 until 9) {
            var filled: FilterHistory.Frame? = null
            history.push(idx.toLong()) { filled = it }
            // overwritten oldest one
            assertSame(frames[idx % 3], filled)
            assertSame(voices[idx % 3], filled!!.voices[0])
            assertSame(filled, history.latest())
        }
    }

    @Test
    fun `reused frames are reset`() {
        val history = FilterHistory(1)
        history.push(1) {
            it.mode = 7
            it.extRouted = true
            it.voices[1].routed = true
            it.voices[1].gainDb = -12f
        }
        history.push(2) {}
        val frame = history.latest()!!
        assertEquals(2L, frame.timeMs)
        assertEquals(0, frame.mode)
        assertFalse(frame.extRouted)
        assertFalse(frame.voices[1].routed)
        assertEquals(0f, frame.voices[1].gainDb)
    }

    @Test
    fun `clear and refill`() {
        val history = FilterHistory(2)
        history.push(5) {}
        history.push(8) {}
        history.push(20) {}
        history.clear()
        assertEquals(0, history.size)
        assertNull(history.latest())
        history.push(100) {}
        history.push(140) {}
        assertEquals(100L, history.frame(0).timeMs)
        assertEquals(40L, history.spanMs())
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun `out of range`() {
        val history = FilterHistory(2)
        history.push(1) {}
        history.frame(1)
    }
}
