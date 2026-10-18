package app.zxtune.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulationContextTest {

    private fun chips(type: String, groups: List<String> = emptyList(), description: String = "") = EmulationContext.detect(type, groups, description).chips

    @Test
    fun `chips by module type`() {
        assertEquals(setOf("sid"), chips("SID"))
        assertEquals(setOf("aym"), chips("PT3"))
        assertEquals(setOf("aym", "z80"), chips("AY"))
        assertEquals(setOf("fm"), chips("TFE"))
        assertEquals(setOf("dac"), chips("CHI"))
        assertEquals(setOf("dac"), chips("XM"))
        assertEquals(setOf("saa"), chips("COP"))
        assertEquals(setOf("mt32"), chips("MIDI"))
        assertEquals(setOf("aym", "fm", "dac", "saa"), chips("MTC"))
        // no zxtune's emulation settings for these
        assertEquals(emptySet<String>(), chips("VGM"))
        assertEquals(emptySet<String>(), chips("SPC"))
        assertEquals(emptySet<String>(), chips("NSF"))
        assertEquals(emptySet<String>(), chips(""))
    }

    @Test
    fun `chips by playback state`() {
        assertEquals(setOf("sid"), chips("", listOf("SID 1", "SID 2"), "PAL, MOS8580, reSIDfp"))
        assertEquals(setOf("aym"), chips("", listOf("AY-3-8910"), "AY-3-8910 ABC"))
        assertEquals(setOf("aym"), chips("", listOf("YM2149F/1", "YM2149F/2"), "2xYM2149F ABC"))
        assertEquals(setOf("fm"), chips("", listOf("YM2203", "YM2203"), "TurboFM, 2xYM2203"))
        assertEquals(setOf("mt32"), chips("", listOf("Partials 1-8"), "MT-32 ROMs, Munt 2.8.3"))
        // libvgm names of the same chips
        assertEquals(emptySet<String>(), chips("VGM", listOf("AY8910", "YM2203"), "AY8910, YM2203"))
    }

    @Test
    fun `ambiguous types are resolved by playback state`() {
        assertEquals(setOf("aym", "dac"), chips("PSM"))
        assertEquals(setOf("aym"), chips("PSM", listOf("AY-3-8910"), "AY-3-8910 ABC"))
        assertEquals(setOf("dac"), chips("STP", emptyList(), "libopenmpt"))
        assertEquals(setOf("aym", "z80"), chips("AY", listOf("AY-3-8910"), "AY-3-8910 ABC"))
    }

    @Test
    fun `names of chips without settings`() {
        val vgm = EmulationContext.detect("VGM", listOf("YM2612", "SN76496", "SN76496/2"), "YM2612, SN76496")
        assertEquals(listOf("YM2612", "SN76496"), vgm.names)
        val spc = EmulationContext.detect("SPC", listOf("S-DSP"), "S-DSP 32kHz, snes_spc")
        assertEquals(listOf("S-DSP"), spc.names)
        val master = EmulationContext.detect("NSF", emptyList(), "NES, Game_Music_Emu")
        assertEquals(listOf("NES"), master.names)
        assertTrue(EmulationContext.detect("", emptyList(), "").isEmpty)
    }

    @Test
    fun `categories by keys`() {
        assertEquals("sid", EmulationContext.categoryOf(listOf("zxtune.core.sid.engine", "zxtune.core.sid.model")))
        assertEquals("aym", EmulationContext.categoryOf(listOf("zxtune.core.aym.clockrate", "zxtune.sound.mixer.3")))
        assertEquals(
            "general",
            EmulationContext.categoryOf(listOf("zxtune.core.plugins.default_duration", "zxtune.sound.gain"))
        )
        assertEquals(null, EmulationContext.categoryOf(emptyList()))
    }

    @Test
    fun `live and next track settings`() {
        for (key in listOf(
            "zxtune.sound.gain", "zxtune.sound.fadeout", "zxtune.sound.looplimit", "zxtune.sound.silencelimit",
            "zxtune.core.aym.clockrate", "zxtune.core.aym.type", "zxtune.core.z80.int_ticks",
            "zxtune.core.fm.clockrate", "zxtune.core.saa.clockrate", "zxtune.core.dac.interpolation",
            "zxtune.core.sid.engine", "zxtune.core.sid.model", "zxtune.core.sid.clock", "zxtune.core.sid.filter_bias",
            "zxtune.core.mt32.model",
        )) {
            assertTrue(key, EmulationContext.isAppliedLive(key))
        }
        assertFalse(EmulationContext.isAppliedLive("zxtune.core.plugins.default_duration"))
    }
}
