package app.zxtune.ui.views

import androidx.test.core.app.ApplicationProvider
import app.zxtune.playback.ChipGauges
import app.zxtune.playback.FrameResult
import app.zxtune.playback.Visualizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Filter response mode of oscilloscope gets chips registers of the same moment as waveforms, even without dashboard
 */
@RunWith(RobolectricTestRunner::class)
class ScopeFeedFilterTest {

    private class SidVisualizer(private val layout: String) : Visualizer {
        var gaugesKind = -1
        var statusCalls = 0

        override fun getSpectrum(levels: ByteArray) = 0
        override fun getScope(data: ShortArray, points: Int, windowMs: Int) = 0
        override fun getGauges(data: ByteArray, waveWindowMs: Int) = 0
        override fun getVoiceGauges(data: ByteArray, waveWindowMs: Int) = 0
        override fun getFrame(data: ShortArray, points: Int, windowMs: Int, gauges: ByteArray, gaugesKind: Int, waveWindowMs: Int): Long {
            this.gaugesKind = gaugesKind
            if (gaugesKind == FrameResult.GAUGES_CHIPS) {
                // cutoff register of the first chip
                gauges[ChipGauges.GAUGES * ChipGauges.COLUMNS * 2 + 0x16] = 100
            }
            // 3 channels, layout id 1
            return FrameResult.pack(3 or (1 shl 16), if (gaugesKind == FrameResult.GAUGES_CHIPS) 1 else 0)
        }

        override fun getLayout() = layout
        override fun getStatus() = "PAL, MOS8580".also { ++statusCalls }
    }

    private class Received {
        var isFilter = false
        var gaugesCount = 0
        var cutoffHigh = 0
        var status = ""
        var generation = -1
    }

    private fun feed(source: Visualizer, received: Received, support: MutableList<Boolean>) = ScopeFeed(ApplicationProvider.getApplicationContext()) { frame ->
        received.isFilter = frame.isFilter
        received.gaugesCount = frame.gaugesCount
        received.cutoffHigh = frame.gauges.getOrElse(ChipGauges.GAUGES * ChipGauges.COLUMNS * 2 + 0x16) { 0 }.toInt()
        received.status = frame.status
        received.generation = frame.generation
        true
    }.apply {
        onFilterSupport = { support.add(it) }
        setSize(320, 180)
        this.source = source
    }

    private fun ScopeFeed.frame() {
        updateOnce()
        runSync {}
    }

    @Test
    fun `chips gauges are requested for filter response`() {
        val source = SidVisualizer("registers\nSID 1\tVoice 1\tVoice 2\tVoice 3")
        val received = Received()
        val support = ArrayList<Boolean>()
        val feed = feed(source, received, support)
        feed.showFilter = true
        feed.frame()
        assertEquals(listOf(true), support)
        assertTrue(received.isFilter)
        // layout is just parsed, gauges of the next frame are valid
        feed.frame()
        assertEquals(FrameResult.GAUGES_CHIPS, source.gaugesKind)
        assertEquals(1, received.gaugesCount)
        assertEquals(100, received.cutoffHigh)
        assertTrue(received.status.contains("8580"))
        val generation = received.generation
        // status is requested rarely
        feed.frame()
        assertEquals(1, source.statusCalls)

        feed.showFilter = false
        feed.frame()
        assertFalse(received.isFilter)
        assertEquals(FrameResult.GAUGES_NONE, source.gaugesKind)
        assertTrue(received.generation > generation)
    }

    @Test
    fun `no filter response for chips without registers`() {
        val source = SidVisualizer("voices\nAY\tA\tB\tC")
        val received = Received()
        val support = ArrayList<Boolean>()
        val feed = feed(source, received, support)
        feed.showFilter = true
        feed.frame()
        feed.frame()
        assertTrue(support.isEmpty())
        assertFalse(received.isFilter)
        assertEquals(FrameResult.GAUGES_NONE, source.gaugesKind)
    }
}
