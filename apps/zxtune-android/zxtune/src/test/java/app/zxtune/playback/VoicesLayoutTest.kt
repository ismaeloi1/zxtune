package app.zxtune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicesLayoutTest {

    @Test
    fun `master channel`() {
        assertSame(VoicesLayout.MASTER, VoicesLayout.parse(""))
        assertSame(VoicesLayout.MASTER, VoicesLayout.parse("audio"))
        assertEquals(0, VoicesLayout.MASTER.voicesCount)
    }

    @Test
    fun `sid chips`() {
        val layout = VoicesLayout.parse("registers\nSID 1\tVoice 1\tVoice 2\tVoice 3\nSID 2\tVoice 1\tVoice 2\tVoice 3")
        assertTrue(layout.hasRegisters)
        assertEquals(2, layout.groups.size)
        assertEquals("SID 2", layout.groups[1].name)
        assertEquals(listOf("Voice 1", "Voice 2", "Voice 3"), layout.groups[1].voices)
        assertEquals(6, layout.voicesCount)
    }

    @Test
    fun `mega drive chips`() {
        val layout = VoicesLayout.parse(
            "audio\nSEGA PSG\t1\t2\t3\tNoise\nYM2612\tFM 1\tFM 2\tFM 3\tFM 4\tFM 5\tFM 6\tDAC\n"
        )
        assertFalse(layout.hasRegisters)
        assertEquals(listOf("SEGA PSG", "YM2612"), layout.groups.map { it.name })
        assertEquals("DAC", layout.groups[1].voices.last())
        assertEquals(11, layout.voicesCount)
    }

    @Test
    fun `voice gauges state`() {
        val data = ByteArray(VoiceGauges.SIZE * 2)
        val offset = VoiceGauges.SIZE
        // column 5 of level gauge
        data[offset + (VoiceGauges.LEVEL * VoiceGauges.COLUMNS + 5) * 2] = 10
        data[offset + (VoiceGauges.LEVEL * VoiceGauges.COLUMNS + 5) * 2 + 1] = 200.toByte()
        val state = offset + VoiceGauges.GAUGES * VoiceGauges.COLUMNS * 2
        putFloat(data, state, 440f)
        putFloat(data, state + 4, -8f)
        data[state + 8] = (1 or 2 or 4).toByte()
        val gauges = VoiceGauges(data, offset)
        assertEquals(10, gauges.min(VoiceGauges.LEVEL, 5))
        assertEquals(200, gauges.max(VoiceGauges.LEVEL, 5))
        assertEquals(440f, gauges.frequency)
        assertEquals(-8f, gauges.level)
        assertTrue(gauges.isKeyOn && gauges.hasFrequency && gauges.hasLevel)
        assertFalse(gauges.isNoise)
    }

    private fun putFloat(data: ByteArray, at: Int, value: Float) {
        val bits = value.toRawBits()
        for (idx in 0 until 4) {
            data[at + idx] = (bits shr (idx * 8)).toByte()
        }
    }
}
