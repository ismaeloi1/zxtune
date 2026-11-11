package app.zxtune.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Presets are copies of native ones (src/module/players/external/fm_output.cpp), see also src/module/test/fm_cores
 */
class FmPresetsTest {

    @Test
    fun `families keys`() {
        assertEquals(listOf("opn2", "opm", "opn", "opl", "opll"), FmPresets.FAMILIES.map { it.id })
        assertEquals("zxtune.core.fm.opn2.preset", FmPresets.OPN2.presetKey)
        assertEquals("zxtune.core.fm.opl.core", FmPresets.OPL.coreKey)
        assertEquals(FmPresets.OPM, FmPresets.familyOf("zxtune.core.fm.opm.lowpass"))
        assertNull(FmPresets.familyOf("zxtune.core.fm.clockrate"))
    }

    @Test
    fun `default presets match native defaults`() {
        // Parameters::ZXTune::Core::FM::*::PRESET_DEFAULT and custom values defaults
        assertEquals(1, FmPresets.OPN2.presets.first().id)
        assertEquals(mapOf("chip" to 0, "ladder" to 1, "lowpass" to 3390, "lowpass_order" to 1, "highpass" to 5, "stereo" to 100), FmPresets.OPN2.defaults)
        assertEquals(1, FmPresets.OPM.presets.first().id)
        assertEquals(1, FmPresets.OPN.presets.first().id)
        assertEquals(4, FmPresets.OPL.presets.first().id)
        assertTrue(FmPresets.OPLL.presets.isEmpty())
    }

    @Test
    fun `preset detection`() {
        val md2 = FmPresets.OPN2.find(3)!!.values
        assertEquals(3, FmPresets.OPN2.detect(md2))
        assertEquals(0, FmPresets.OPN2.detect(md2 + ("lowpass" to 3000)))
        // model 1 VA7 and Genesis 3 share values, selected one is kept
        val va7 = FmPresets.OPN2.find(2)!!.values
        assertEquals(2, FmPresets.OPN2.detect(va7))
        assertEquals(4, FmPresets.OPN2.detect(va7, 4))
        assertEquals(2, FmPresets.OPL.detect(mapOf("lowpass" to 12000, "lowpass_order" to 1, "highpass" to 0, "stereo" to 100)))
        assertEquals(0, FmPresets.OPLL.detect(mapOf("lowpass" to 0)))
    }

    @Test
    fun `output stage response`() {
        val md1 = FmPresets.Stage(3390, 1, 5)
        assertEquals(-3.01, md1.responseDb(3390.0), 0.01)
        assertEquals(-3.01, md1.responseDb(5.0), 0.01)
        assertEquals(0.0, md1.responseDb(300.0), 0.05)
        val md2 = FmPresets.Stage(2840, 2, 0)
        assertEquals(-3.01, md2.responseDb(2840.0), 0.01)
        assertEquals(-12.30, md2.responseDb(5680.0), 0.01)
        assertEquals(0.0, FmPresets.Stage(0, 1, 0).responseDb(10000.0), 1e-9)
    }

    @Test
    fun `stage from preferences`() {
        val prefs = mapOf("zxtune.core.fm.opl.lowpass" to 8000, "zxtune.core.fm.opl.lowpass_order" to "2")
        val stage = FmPresets.stageOf(FmPresets.OPL) { prefs[it] }
        assertEquals(8000, stage.lowpass)
        assertEquals(2, stage.order)
        assertEquals(0, stage.highpass)
        // defaults
        val opn2 = FmPresets.stageOf(FmPresets.OPN2) { null }
        assertEquals(3390, opn2.lowpass)
        assertEquals(5, opn2.highpass)
    }
}
