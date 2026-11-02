package app.zxtune.ui.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Envelope timings must be what snes_spc does (see SPC_DSP.cpp), not approximations from documentation tables
 */
class SpcDescriptionTest {

    private fun adsr(adsr1: Int, adsr2: Int) = SpcEnvelope().apply { update(adsr1, adsr2, 0, 0) }

    @Test
    fun attack() {
        // 64 steps of 0x20 at the slowest rate (2048 samples per step)
        assertEquals(64 * 2048, adsr(0x80, 0xe0).attackSamples)
        assertEquals("4.1 s", SpcDescription.durationWord(adsr(0x80, 0xe0).attackSamples))
        // rate 21: 20 samples per step
        assertEquals("40 ms", SpcDescription.durationWord(adsr(0x8a, 0xe0).attackSamples))
        // the fastest one adds 0x400 per sample
        assertEquals(2, adsr(0x8f, 0xe0).attackSamples)
        assertEquals("<1 ms", SpcDescription.durationWord(2))
    }

    @Test
    fun decayAndSustain() {
        // decay stops as soon as the next step reaches sustain level
        val env = adsr(0x80 or 0x0f, 0x00 or 0x1f)
        assertEquals(439 * 64, env.decaySamples)
        assertEquals(256, env.levels[env.stageStart[2]])
        // the highest sustain level is reached immediately
        assertEquals(0, adsr(0xff, 0xe0 or 0x10).decaySamples)
        // exponential fade from full level to silence, 1 sample per step at rate 31
        assertEquals(695, adsr(0xff, 0xe0 or 0x1f).fadeSamples)
        assertEquals(0, adsr(0xff, 0xff).levels[adsr(0xff, 0xff).count - 1])
        // sustain rate 0 keeps the level forever
        val endless = adsr(0x8f, 0xa0)
        assertTrue(endless.isEndless)
        assertEquals(-1, endless.fadeSamples)
        assertEquals(75, SpcDescription.sustainPercent(0xa0))
    }

    @Test
    fun position() {
        val env = adsr(0x80, 0xe0)
        // half of attack
        assertEquals(32 * 2048f, env.timeOf(0, 1024), 0.5f)
        assertEquals(1024f, env.levelAt(32 * 2048f), 0.5f)
        assertEquals(0, env.stageAt(100f))
    }

    @Test
    fun gain() {
        fun gain(value: Int) = SpcEnvelope().apply { update(0, 0, value, 0x400) }
        // linear 0x20 per step
        assertEquals(64, gain(0x80 or 0x1f).rampSamples)
        assertEquals(64, gain(0xc0 or 0x1f).rampSamples)
        // two slopes: 0x20 per step till 0x600, then 8
        assertEquals(48 + 64, gain(0xe0 or 0x1f).rampSamples)
        assertEquals(695, gain(0xa0 or 0x1f).rampSamples)
        val direct = gain(0x40)
        assertTrue(direct.isEndless)
        assertEquals(0x400, direct.levels[0])
        val hold = gain(0x80)
        assertTrue(hold.isEndless)
        assertEquals(0x400, hold.levels[0])
        assertFalse(direct.isAdsr)
        assertEquals("Bent ↑", SpcDescription.gainWord(0xef))
        assertEquals("Direct", SpcDescription.gainWord(0x50))
        assertEquals("Hold", SpcDescription.gainWord(0xa0))
    }

    @Test
    fun registers() {
        assertEquals(32000f, SpcDescription.rateHz(0x1000), 0.01f)
        assertEquals(-12f, SpcDescription.shiftSemitones(0x800), 0.001f)
        assertEquals(80f, SpcDescription.echoDelayMs(5), 0.001f)
        assertEquals(4, SpcDescription.echoBufferBytes(0))
        assertEquals(15.625f, SpcDescription.noiseHz(0x01 or 0x20), 0.001f)
        assertEquals(0f, SpcDescription.noiseHz(0), 0f)
        val flat = intArrayOf(127, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(127f / 128, SpcDescription.firGain(flat, 0f), 0.0001f)
        assertEquals(127f / 128, SpcDescription.firGain(flat, 12000f), 0.0001f)
        // low pass of Square games
        val lowPass = intArrayOf(0x0c, 0x21, 0x2b, 0x2b, 0x13, -2, -13, -7)
        assertEquals(1f, SpcDescription.firGain(lowPass, 0f), 0.0001f)
        assertTrue(SpcDescription.firGain(lowPass, 12000f) < 0.2f)
        assertEquals("−37%", SpcDescription.signedPercent(SpcDescription.volumePercent(-48)))
        assertEquals("+99%", SpcDescription.signedPercent(SpcDescription.volumePercent(127)))
        assertEquals("PMod V3", SpcVoiceText.modWord(2, 3))
        assertEquals("Noise", SpcVoiceText.modWord(4 or 2, 0))
        assertEquals("1.0 kHz", SpcDescription.hzWord(1000f))
    }
}
