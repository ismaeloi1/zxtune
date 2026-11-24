package app.zxtune.ui.views

import org.junit.Assert.assertEquals
import org.junit.Test

class SidModulationTest {
    private val tri = 0x10
    private val pulse = 0x40
    private val sync = 0x02
    private val ring = 0x04

    // source voice is shown, it matters when both effects are on
    @Test
    fun `source of effects`() {
        assertEquals("Sync+Ring V1", SidDescription.modulationWord(tri or sync or ring, 1))
        assertEquals("Sync V3", SidDescription.modulationWord(pulse or sync, 0))
        assertEquals("Ring V2", SidDescription.modulationWord(tri or ring, 2))
        // ring without triangle is ignored by chip
        assertEquals("Sync V2", SidDescription.modulationWord(pulse or sync or ring, 2))
        assertEquals("—", SidDescription.modulationWord(pulse, 1))
    }
}
