package app.zxtune.sound

import app.zxtune.TimeStamp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Native seeking may take seconds (e.g. SID emulation from the very beginning), so it should not block track change,
// another seek or stop.
@RunWith(RobolectricTestRunner::class)
class AsyncPlayerTest {

    private class Target : SamplesTarget {
        override val sampleRate = 44100
        override val preferableBufferSize = 441

        override fun start() = Unit

        override fun writeSamples(buffer: ShortArray) = Thread.sleep(1)

        override fun stop() = Unit

        override fun release() = Unit
    }

    private class Events : PlayerEventsListener {
        val error = AtomicReference<Exception>()

        override fun onStart() = Unit

        override fun onSeeking() = Unit

        override fun onFinish() = Unit

        override fun onStop() = Unit

        override fun onError(e: Exception) = error.set(e)
    }

    // First seeks last till interruption, like long native ones
    private class Source(private val slowSeeks: Int = Int.MAX_VALUE) : SamplesSource {
        val rendered = AtomicInteger()
        val seekStarted = CountDownLatch(1)
        val seeksDone = AtomicInteger()

        @Volatile
        var released = false

        @Volatile
        private var interrupted = false

        @Volatile
        private var pos = TimeStamp.EMPTY

        override fun getSamples(buf: ShortArray): Boolean {
            check(!released) { "Invalid handle" }
            rendered.incrementAndGet()
            return true
        }

        override var position: TimeStamp
            get() = pos
            set(value) {
                interrupted = false
                seekStarted.countDown()
                val duration = if (seeksDone.get() < slowSeeks) SEEK_SECONDS else 0
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(duration)
                while (!interrupted && System.nanoTime() < deadline) {
                    Thread.sleep(1)
                }
                // emulates interrupted seeking stopped somewhere on the way
                pos = if (interrupted) TimeStamp.fromMilliseconds(value.toMilliseconds() / 2) else value
                seeksDone.incrementAndGet()
            }

        override fun interruptSeek() {
            interrupted = true
        }

        fun awaitRendered() = awaitCondition { rendered.get() > 0 }
    }

    private lateinit var events: Events
    private lateinit var player: AsyncPlayer

    @Before
    fun setUp() {
        events = Events()
        player = AsyncPlayer.create(Target(), events)
    }

    @After
    fun tearDown() {
        player.stopPlayback()
        player.release()
        assertNull(events.error.get())
    }

    @Test
    fun `track change during long seek`() {
        val first = Source()
        player.setSource(first)
        player.startPlayback()
        first.awaitRendered()
        player.position = TimeStamp.fromSeconds(100)
        assertTrue(first.seekStarted.await(1, TimeUnit.SECONDS))

        val second = Source()
        val start = System.nanoTime()
        player.setSource(second)
        // what playback service does with previous source on track change
        first.released = true
        player.startPlayback()
        second.awaitRendered()
        assertTrue(elapsedMs(start) < 1000)
        assertTrue(player.isStarted)
        // previous source is not touched after change
        val renderedBefore = first.rendered.get()
        Thread.sleep(50)
        assertEquals(renderedBefore, first.rendered.get())
    }

    @Test
    fun `stop during long seek`() {
        val src = Source()
        player.setSource(src)
        player.startPlayback()
        src.awaitRendered()
        val request = TimeStamp.fromSeconds(100)
        player.position = request
        assertTrue(src.seekStarted.await(1, TimeUnit.SECONDS))

        val start = System.nanoTime()
        player.stopPlayback()
        assertTrue(elapsedMs(start) < 1000)
        assertTrue(!player.isStarted)
        // interrupted seeking is completed on resume
        assertEquals(request, player.position)
    }

    @Test
    fun `new seek request interrupts previous one`() {
        val src = Source(slowSeeks = 1)
        player.setSource(src)
        player.startPlayback()
        src.awaitRendered()
        player.position = TimeStamp.fromSeconds(100)
        assertTrue(src.seekStarted.await(1, TimeUnit.SECONDS))

        val start = System.nanoTime()
        val request = TimeStamp.fromSeconds(5)
        player.position = request
        awaitCondition { src.seeksDone.get() == 2 }
        assertTrue(elapsedMs(start) < 1000)
        assertEquals(request, src.position)
    }

    companion object {
        private const val SEEK_SECONDS = 10L

        private fun elapsedMs(start: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        private fun awaitCondition(cond: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!cond()) {
                check(System.nanoTime() < deadline) { "Timeout" }
                Thread.sleep(1)
            }
        }
    }
}
