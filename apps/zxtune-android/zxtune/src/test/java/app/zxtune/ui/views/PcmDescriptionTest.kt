package app.zxtune.ui.views

import app.zxtune.playback.VoiceGauges
import app.zxtune.playback.VoiceGauges.Pcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmDescriptionTest {

    private fun gauges(chip: Int, fill: (IntArray) -> Unit): VoiceGauges {
        val fields = IntArray(VoiceGauges.FIELDS)
        fields[Pcm.CHIP] = chip
        fields[Pcm.STATE] = Pcm.ST_PLAYING or Pcm.ST_KEY or 2
        val rate = if (chip == Pcm.CHIP_SCSP) 44100 else 44744
        fields[Pcm.RATE] = rate and 0xff
        fields[Pcm.RATE + 1] = rate shr 8
        fill(fields)
        val data = ByteArray(VoiceGauges.SIZE)
        val state = VoiceGauges.GAUGES * VoiceGauges.COLUMNS * 2
        data[state + 9] = VoiceGauges.Kind.PCM_SLOT.toByte()
        fields.forEachIndexed { idx, value -> data[state + 10 + idx] = value.toByte() }
        return VoiceGauges(data, 0)
    }

    private fun ymw(fill: (IntArray) -> Unit) = gauges(Pcm.CHIP_YMW258, fill)
    private fun scsp(fill: (IntArray) -> Unit) = gauges(Pcm.CHIP_SCSP, fill)

    @Test
    fun pitch() {
        // YMW258 plays original rate at octave 1 (see update_step of multipcm.c), SCSP at octave 0
        assertEquals(44744f, PcmDescription.playbackHz(ymw { it[Pcm.OCT] = 1 }), 0.5f)
        assertEquals(0f, PcmDescription.shiftSemitones(ymw { it[Pcm.OCT] = 1 }), 1e-4f)
        assertEquals(44100f, PcmDescription.playbackHz(scsp { }), 0.5f)
        // octave -1, F-number 388: 44100 * (1 + 388 / 1024) / 2
        val g = scsp {
            it[Pcm.OCT] = 0xff
            it[Pcm.FNUM] = 388 and 0xff
            it[Pcm.FNUM + 1] = 388 shr 8
        }
        assertEquals(-1, PcmDescription.octave(g))
        assertEquals(30405f, PcmDescription.playbackHz(g), 1f)
        assertEquals("30.4 kHz", PcmDescription.hzWord(PcmDescription.playbackHz(g)))
        assertEquals("−6.4 st", PcmDescription.semitonesWord(PcmDescription.shiftSemitones(g)))
    }

    @Test
    fun levels() {
        assertEquals(-2.25f, PcmDescription.totalLevelDb(ymw { it[Pcm.TL] = 6 }), 1e-4f)
        // bits of 0.4, 0.8, 1.5, 3, 6, 12, 24, 48dB
        assertEquals(-4.5f, PcmDescription.totalLevelDb(scsp { it[Pcm.TL] = 12 }), 1e-4f)
        assertEquals(-95.7f, PcmDescription.totalLevelDb(scsp { it[Pcm.TL] = 255 }), 1e-3f)
        assertEquals(-24f, PcmDescription.sustainDb(ymw { it[Pcm.DL] = 4 }), 0f)
        assertEquals(-30f, PcmDescription.sustainDb(scsp { it[Pcm.DL] = 10 }), 0f)
        assertEquals("−6 dB", PcmDescription.sendWord(6))
        assertEquals("Off", PcmDescription.sendWord(0))
        assertEquals("−∞ dB", PcmDescription.dbWord(-96f))
    }

    @Test
    fun pan() {
        assertEquals("Center", PcmDescription.panWord(ymw { it[Pcm.PAN] = 0 }))
        // 1..7 attenuate left
        assertEquals("R 9 dB", PcmDescription.panWord(ymw { it[Pcm.PAN] = 3 }))
        assertEquals("R only", PcmDescription.panWord(ymw { it[Pcm.PAN] = 7 }))
        assertEquals("Muted", PcmDescription.panWord(ymw { it[Pcm.PAN] = 8 }))
        assertEquals("L only", PcmDescription.panWord(ymw { it[Pcm.PAN] = 9 }))
        assertEquals("L 6 dB", PcmDescription.panWord(ymw { it[Pcm.PAN] = 14 }))
        // SCSP: 0..15 attenuate left, 16..31 right
        assertEquals("Center", PcmDescription.panWord(scsp { it[Pcm.PAN] = 0x10 }))
        assertEquals("R 15 dB", PcmDescription.panWord(scsp { it[Pcm.PAN] = 0x05 }))
        assertEquals("L 27 dB", PcmDescription.panWord(scsp { it[Pcm.PAN] = 0x19 }))
        assertEquals("L only", PcmDescription.panWord(scsp { it[Pcm.PAN] = 0x1f }))
    }

    @Test
    fun envelope() {
        // YMW258 rate 0x3F attack is instant, decays are 14.33 times longer than BASE_TIMES
        val g = ymw {
            it[Pcm.RATES] = 63
            it[Pcm.RATES + 1] = 16
            it[Pcm.RATES + 2] = 0
            it[Pcm.RATES + 3] = 24
            it[Pcm.DL] = 4
        }
        val env = PcmEnvelope()
        env.update(g)
        assertEquals(0f, env.attackMs, 0f)
        // 777.87ms * 14.32833 for 96dB, 24dB of it, at 44744Hz
        assertEquals(777.87f * 14.32833f * 0.25f * 44100f / 44744f, env.decay1Ms, 0.5f)
        assertTrue(env.decay2Ms.isInfinite())
        assertTrue(PcmDescription.isSustained(g))
        assertEquals("instant", PcmDescription.msWord(env.attackMs))
        assertEquals(env.decay1Ms / 2, env.timeInStage(2, -12f), 0.5f)
        // SCSP attack starts at 0x17F of 0x3FF, linear in amplitude
        val s = scsp {
            it[Pcm.RATES] = 30
            it[Pcm.RATES + 1] = 2
            it[Pcm.RATES + 2] = 0
        }
        env.update(s)
        assertTrue(env.isLinearAttack)
        assertEquals(63f * (0x3FF - 0x17F) / 1023f, env.attackMs, 0.01f)
        assertEquals(-8.54f, env.attackDbAt(0f), 0.01f)
        assertEquals(0f, env.attackDbAt(1f), 1e-4f)
        assertEquals(env.attackMs, env.timeInStage(1, 0f), 1e-3f)
        // decay 1 to 0dB sustain takes no time even at infinite rate
        assertEquals(0f, env.decay1Ms, 0f)
    }

    @Test
    fun modulation() {
        val g = scsp {
            it[Pcm.MODE] = Pcm.MODE_FM
            it[Pcm.MDL] = 11
            it[Pcm.MDXSL] = 63
            it[Pcm.MDYSL] = 62
        }
        assertEquals("FM", PcmDescription.modeWord(g))
        // ring buffer offsets from slot 5 (index 4) are slots 4 and 3
        assertEquals("S4+S3 ±4π", PcmDescription.modulationWord(g, 4))
        assertEquals("±π/16", PcmDescription.modulationDepth(5))
        assertEquals("—", PcmDescription.modulationWord(ymw { }, 0))
    }
}
