package app.zxtune.ui

import app.zxtune.core.Identifier
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChipFamilyTest {

    @Test
    fun `by module type`() {
        assertEquals(ChipFamily.SID, ChipFamily.ofType("SID"))
        assertEquals(ChipFamily.AY, ChipFamily.ofType("PT3"))
        assertEquals(ChipFamily.AY, ChipFamily.ofType("YM"))
        assertEquals(ChipFamily.SPC, ChipFamily.ofType("SPC"))
        assertEquals(ChipFamily.NES, ChipFamily.ofType("NSFE"))
        assertEquals(ChipFamily.GB, ChipFamily.ofType("GBS"))
        assertEquals(ChipFamily.FM, ChipFamily.ofType("TFC"))
        assertEquals(ChipFamily.FM, ChipFamily.ofType("GYM"))
        assertEquals(ChipFamily.MOD, ChipFamily.ofType("XM"))
        assertEquals(ChipFamily.DAC, ChipFamily.ofType("CHI"))
        assertEquals(ChipFamily.SAA, ChipFamily.ofType("COP"))
        assertEquals(ChipFamily.MIDI, ChipFamily.ofType("MIDI"))
        // ambiguous id without playback details
        assertEquals(ChipFamily.MOD, ChipFamily.ofType("PSM"))
        // multichip formats are known only while playing
        assertEquals(null, ChipFamily.ofType("VGM"))
        assertEquals(null, ChipFamily.ofType(""))
        assertEquals(null, ChipFamily.ofType(null))
    }

    @Test
    fun `by filename`() {
        assertEquals(ChipFamily.SID, ChipFamily.ofFilename("Commando.sid"))
        assertEquals(ChipFamily.AY, ChipFamily.ofFilename("agent_x_ii.pt3"))
        assertEquals(ChipFamily.AY, ChipFamily.ofFilename("Song.STP"))
        assertEquals(ChipFamily.SPC, ChipFamily.ofFilename("01 Corridors of Time.spc"))
        assertEquals(ChipFamily.MOD, ChipFamily.ofFilename("guitar_slinger.mod"))
        assertEquals(ChipFamily.PSF, ChipFamily.ofFilename("track.minipsf"))
        assertEquals(ChipFamily.MIDI, ChipFamily.ofFilename("canyon.mid"))
        // Amiga style
        assertEquals(ChipFamily.MOD, ChipFamily.ofFilename("mod.the_last_ninja"))
        assertEquals(ChipFamily.MOD, ChipFamily.ofFilename("MOD.intro"))
        // generic and containers
        assertEquals(null, ChipFamily.ofFilename("readme.txt"))
        assertEquals(null, ChipFamily.ofFilename("it.was.a.dream.zip"))
        assertEquals(null, ChipFamily.ofFilename("collection.trd"))
        assertEquals(null, ChipFamily.ofFilename("noextension"))
        assertEquals(null, ChipFamily.ofFilename(""))
        assertEquals(null, ChipFamily.ofFilename(null))
    }

    @Test
    fun `tune filename`() {
        assertEquals("song.ay", Identifier.parse("file:///music/song.ay").tuneFilename)
        assertEquals("song.ay", Identifier.parse("file:///music/song.ay#%2F%232").tuneFilename)
        assertEquals("song.pt3", Identifier.parse("file:///music/pack.zip#%2BunZIP%2Fsong.pt3").tuneFilename)
    }

    @Test
    fun `fallback label`() {
        assertEquals("VGZ", fallbackBadgeLabel("Out Run.vgz"))
        assertEquals("", fallbackBadgeLabel("long.extension"))
        assertEquals("", fallbackBadgeLabel("noext"))
        assertEquals("", fallbackBadgeLabel("odd.e-x"))
    }
}
