package app.zxtune.sound

import app.zxtune.TimeStamp
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimelineTest {

    private class Source : SamplesSource {
        override fun getSamples(buf: ShortArray) = true
        override var position: TimeStamp = TimeStamp.EMPTY
    }

    @Test
    fun `single source`() {
        val src = Source()
        val timeline = PlaybackTimeline().apply {
            reset()
            onRendered(src, 100, true)
            onRendered(src, 100, true)
        }
        assertEquals(0, timeline.positionOf(src, 0))
        assertEquals(150, timeline.positionOf(src, 150))
        // beyond rendered is limited by last segment end
        assertEquals(200, timeline.positionOf(src, 1000))
    }

    @Test
    fun `not committed buffers are skipped`() {
        val src = Source()
        val timeline = PlaybackTimeline().apply {
            reset()
            onRendered(src, 100, true)
            onRendered(src, 100, false)
            onRendered(src, 100, true)
        }
        assertEquals(50, timeline.positionOf(src, 50))
        // second played buffer is third rendered one
        assertEquals(250, timeline.positionOf(src, 150))
    }

    @Test
    fun `sources switching`() {
        val first = Source()
        val second = Source()
        val timeline = PlaybackTimeline().apply {
            reset()
            onRendered(first, 100, true)
            onRendered(second, 100, true)
        }
        assertEquals(-1, timeline.positionOf(second, 50))
        assertEquals(20, timeline.positionOf(second, 120))
        assertEquals(-1, timeline.positionOf(first, 120))
    }

    @Test
    fun `output restart`() {
        val src = Source()
        val timeline = PlaybackTimeline().apply {
            reset()
            onRendered(src, 100, true)
            reset()
            onRendered(src, 100, true)
        }
        // source frames index continues
        assertEquals(110, timeline.positionOf(src, 10))
    }
}
