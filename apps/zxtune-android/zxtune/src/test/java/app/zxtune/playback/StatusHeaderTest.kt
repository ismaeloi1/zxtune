package app.zxtune.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusHeaderTest {
    private val sidStatus = "PAL, MOS6581, reSIDfp, resample\n" +
        "timing: CIA 100.3 Hz (PAL), speed 2x, song 1/3\n" +
        "scope: 3/3 ch, trigger 1024us"

    @Test
    fun sid() = assertEquals(
        "PAL, MOS6581, reSIDfp, resample\n" +
            "Original driver: GoatTracker V2.x\n" +
            "Running on NebulaWeave3\n" +
            "timing: CIA 100.3 Hz (PAL), speed 2x, song 1/3\n" +
            "scope: 3/3 ch, trigger 1024us",
        StatusHeader.insert(sidStatus, "SID", "GoatTracker V2.x") { "unused" },
    )

    @Test
    fun sidUnknown() {
        assertEquals("unknown", StatusHeader.driverOf("SID", "") { "PlaySID format" })
    }

    @Test
    fun snes() {
        val status = StatusHeader.insert("S-DSP 32kHz, snes_spc\nscope: 8/8 ch", "SPC", "Chrono Trigger") { null }
        assertEquals(
            "S-DSP 32kHz, snes_spc\nOriginal driver: Chrono Trigger (in-game)\n" +
                "Running on NebulaWeaveSPC\nscope: 8/8 ch",
            status,
        )
        assertEquals("NebulaWeaveSPC", EngineBrands.brandOf("SNSF", "S-DSP 32kHz, 65816 + snes_spc"))
    }

    @Test
    fun noDescription() = assertEquals(
        "Original driver: ProTracker v3.x\nRunning on NebulaWeaveAY\nscope: 3/3 ch",
        StatusHeader.insert("scope: 3/3 ch", "PT3", "") { "ProTracker v3.x" },
    )

    @Test
    fun brands() {
        assertEquals("NebulaWeaveOPL", EngineBrands.brandOf("VGM", "YM2612 GPGX 7.67MHz, SN76489 MAME"))
        assertEquals("NebulaWeavePSG", EngineBrands.brandOf("VGM", "SN76489 MAME 3.58MHz"))
        assertEquals("NebulaWeaveOPL", EngineBrands.brandOf("TFC", ""))
        assertEquals("NebulaWeaveAY", EngineBrands.brandOf("PSM", "AY-3-8910 1.77MHz"))
        assertEquals("NebulaWeaveMOD", EngineBrands.brandOf("PSM", ""))
        assertEquals("NebulaWeaveNES", EngineBrands.brandOf("NSFE", "Nintendo NES, Game_Music_Emu"))
        assertEquals("NebulaWeavePCM", EngineBrands.brandOf("MP3", ""))
        // Sega Model 1 music is played by YMW258, its YM3438 is secondary
        assertEquals(
            "NebulaWeaveYMW",
            EngineBrands.brandOf("VGM", "YM3438 MAME 8.00MHz, YMW258/1 MAME 10.02MHz, YMW258/2 MAME 10.02MHz"),
        )
        assertEquals("NebulaWeaveSCSP", EngineBrands.brandOf("VGM", "SCSP MAME 22.58MHz"))
        assertEquals("NebulaWeaveSCSP", EngineBrands.brandOf("SSF", ""))
        // every system has a brand
        (EngineBrands.BY_TYPE.values + EngineBrands.BY_CHIP.map { it.second }).forEach {
            assert(EngineBrands.BRANDS.containsKey(it)) { it }
        }
    }

    @Test
    fun statistics() {
        assertEquals(
            listOf(false, false, false, true, true, true),
            listOf(
                "PAL, MOS6581, reSIDfp, resample",
                "Original driver: x",
                "timing: VBI 50 Hz",
                "scope: 3/3 ch",
                "output: 48000Hz buffer=160ms",
                "render: 152% realtime",
            ).map(StatusHeader::isStatistics),
        )
        assertEquals(44100, StatusHeader.outputRateOf("AY\nscope: 3/3\noutput: 44100Hz buffer=160ms\nrender: 1%"))
        assertEquals(0, StatusHeader.outputRateOf("AY\nrender: 1%"))
    }
}
