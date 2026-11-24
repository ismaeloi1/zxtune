package app.zxtune.ui.views

import app.zxtune.playback.VoicesLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChipColumnsLayoutTest {
    @After
    fun tearDown() {
        ChipColumns.showOplRhythm = false
    }

    @Test
    fun `sid samples lane is under voices of its chip`() {
        val single = ChipColumns(VoicesLayout.parse("registers\nSID 1\tVoice 1\tVoice 2\tVoice 3\nSamples\t\$D418").groups)
        assertEquals(1, single.columns)
        assertEquals(4, single.maxRows)
        assertEquals(0, single.column(1, 0))
        assertEquals(3, single.row(1, 0))
        val dual = ChipColumns(
            VoicesLayout.parse("registers\nSID 1\tVoice 1\tVoice 2\tVoice 3\nSID 2\tVoice 1\tVoice 2\tVoice 3\nSamples\ta\tb").groups
        )
        assertEquals(2, dual.columns)
        assertEquals(1, dual.column(2, 1))
        assertEquals(3, dual.row(2, 1))
    }

    private val adlib = "voices\nYM3812\t1\t2\t3\t4\t5\t6\t7\t8\t9\tBass Drum\tSnare Drum\tTom Tom\tCymbal\tHi-Hat"

    @Test
    fun `adlib melodic voices in 3x3 without rhythm`() {
        val columns = ChipColumns(VoicesLayout.parse(adlib).groups)
        assertEquals(3, columns.columns)
        assertEquals(3, columns.maxRows)
        assertEquals(2, columns.column(0, 8))
        assertEquals(2, columns.row(0, 8))
        assertTrue(columns.isHidden(0, 9))
        assertFalse(columns.isHidden(0, 0))
    }

    @Test
    fun `adlib rhythm lanes on demand`() {
        ChipColumns.showOplRhythm = true
        val columns = ChipColumns(VoicesLayout.parse(adlib).groups)
        assertFalse(columns.isHidden(0, 13))
        assertEquals(2, columns.columns)
    }
}
