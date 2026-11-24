package app.zxtune.ui.views

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

class WaveShapeTest {
    private fun table(f: (Int) -> Int) = IntArray(32) { f(it).coerceIn(0, 31) }

    @Test
    fun shapes() {
        assertEquals("Saw ↑", WaveShape.describe(table { it }))
        assertEquals("Saw ↓ (reversed)", WaveShape.describe(table { 31 - it }))
        // started from the middle of cycle, as drivers often do
        assertEquals("Saw ↑", WaveShape.describe(table { (it + 10) % 32 }))
        assertEquals("Square", WaveShape.describe(table { if (it < 16) 31 else 0 }))
        assertEquals("Pulse 25%", WaveShape.describe(table { if (it < 8) 31 else 0 }))
        assertEquals("Triangle", WaveShape.describe(table { if (it < 16) it * 2 else (31 - it) * 2 }))
        assertEquals("Sine", WaveShape.describe(table { (15.5 + 15.5 * sin(2 * PI * it / 32)).roundToInt() }))
        assertEquals("Flat", WaveShape.describe(table { 16 }))
        assertEquals("Custom", WaveShape.describe(table { (it * 7) % 32 }))
    }

    @Test
    fun `table ids in order of appearance`() {
        val ids = TableIds()
        assertEquals(1, ids.idOf(table { it }))
        assertEquals(2, ids.idOf(table { 31 - it }))
        assertEquals(1, ids.idOf(table { it }))
    }
}
