package app.zxtune.ui.views

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Texts of values written by player at each tick are stable and readable at display rate
 */
class RealtimeReadoutTest {

    private fun cutoffReadout() = RealtimeReadout(deadband = 1.0 / 24, modulationSpread = 1.0 / 6, isLogarithmic = true)

    // display frames of 120Hz with player ticks of 50Hz: register value is held between ticks
    private fun run(seconds: Double, fps: Int = 120, tickHz: Int = 50, value: (Double) -> Double, onFrame: (Long, Double) -> Unit) {
        val frames = (seconds * fps).toInt()
        for (frame in 0 until frames) {
            val time = frame.toDouble() / fps
            val tick = Math.floor(time * tickHz) / tickHz
            onFrame((time * 1e9).toLong() + 1, value(tick))
        }
    }

    @Test
    fun `sweeping cutoff is shown as range refreshed a few times per second`() {
        val readout = cutoffReadout()
        var changes = 0
        // 2Hz LFO between 220 and 880Hz
        run(4.0, value = { t -> 440 * 2.0.pow(sin(2 * PI * 2 * t)) }) { nanos, hz ->
            if (readout.push(hz, nanos)) {
                ++changes
            }
        }
        // the first value, then at most 4 times per second
        assertTrue("changes=$changes", changes <= 1 + 4 * 4)
        assertTrue(readout.isModulated)
        assertTrue("low=${readout.low}", readout.low in 200.0..330.0)
        assertTrue("high=${readout.high}", readout.high in 580.0..900.0)
    }

    @Test
    fun `small jitter doesn't change text`() {
        val readout = cutoffReadout()
        var changes = 0
        run(3.0, value = { t -> 1000.0 * (1 + 0.01 * sin(2 * PI * 7 * t)) }) { nanos, hz ->
            if (readout.push(hz, nanos)) {
                ++changes
            }
        }
        assertEquals(1, changes)
        assertFalse(readout.isModulated)
        assertEquals(1000.0, readout.value, 15.0)
    }

    @Test
    fun `step is shown after window`() {
        val readout = cutoffReadout()
        // refreshes by 0.25s with window of 0.5s: shown as range while window covers the step, then as new value
        run(1.3, value = { t -> if (t < 0.5) 300.0 else 1200.0 }) { nanos, hz ->
            readout.push(hz, nanos)
        }
        assertEquals(1200.0, readout.value, 1.0)
        assertFalse(readout.isModulated)
    }

    @Test
    fun `stopped modulation returns to single value`() {
        val readout = cutoffReadout()
        run(2.0, value = { t -> if (t < 1.0) 440 * 2.0.pow(sin(2 * PI * 3 * t)) else 700.0 }) { nanos, hz ->
            readout.push(hz, nanos)
        }
        assertFalse(readout.isModulated)
        assertEquals(700.0, readout.value, 1.0)
    }

    @Test
    fun `vibrato keeps note`() {
        val note = NoteReadout()
        var changes = 0
        // A4 with 6Hz vibrato of 45 cents depth
        run(3.0, value = { t -> 440 * 2.0.pow(0.45 / 12 * sin(2 * PI * 6 * t)) }) { nanos, hz ->
            if (note.push(hz, nanos)) {
                ++changes
            }
        }
        assertEquals(1, changes)
        assertEquals(57, note.note)
    }

    @Test
    fun `note change is shown after hold time`() {
        val note = NoteReadout()
        var switchedAt = 0L
        run(1.0, value = { t -> if (t < 0.5) 440.0 else 493.88 }) { nanos, hz ->
            if (note.push(hz, nanos) && note.note == 59) {
                switchedAt = nanos
            }
        }
        assertEquals(59, note.note)
        val delay = (switchedAt - 500_000_000L) / 1e6
        assertTrue("delay=$delay ms", delay in 140.0..200.0)
    }

    @Test
    fun `modulation range holds extent and decays`() {
        val range = ModulationRange(1)
        val value = DoubleArray(1)
        var nanos = 1L
        for (frame in 0 until 120) {
            value[0] = if (frame < 60) sin(frame * 0.5) * 10 else 0.0
            range.update(value, 1, nanos)
            if (frame == 59) {
                assertTrue(range.high[0] > 9 && range.low[0] < -9)
            }
            nanos += 1_000_000_000L / 120
        }
        // 0.5s after modulation is stopped: held for 0.3s, then decayed
        assertTrue("${range.low[0]}..${range.high[0]}", range.high[0] < 6 && range.low[0] > -6)
        assertTrue(range.isVisible(0, 0.5))
    }
}
