package app.zxtune.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import app.zxtune.R
import app.zxtune.playback.ChipGauges
import app.zxtune.playback.Visualizer
import app.zxtune.ui.views.FilterCurveView
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Live filter state is requested and presented at display rate while playing only
 */
@RunWith(RobolectricTestRunner::class)
class FilterCurveFeedTest {

    // cutoff register is changed by each request like filter sweep
    private class SweepVisualizer : Visualizer {
        var calls = 0

        override fun getSpectrum(levels: ByteArray) = 0
        override fun getScope(data: ShortArray, points: Int, windowMs: Int) = 0
        override fun getGauges(data: ByteArray, waveWindowMs: Int): Int {
            ++calls
            val regs = ChipGauges.GAUGES * ChipGauges.COLUMNS * 2
            data[regs + 0x15] = (calls and 7).toByte()
            data[regs + 0x16] = (calls shr 3).toByte()
            return 1
        }
        override fun getVoiceGauges(data: ByteArray, waveWindowMs: Int) = 0
        override fun getFrame(data: ShortArray, points: Int, windowMs: Int, gauges: ByteArray, gaugesKind: Int, waveWindowMs: Int) = 0L
        override fun getLayout() = ""
        override fun getStatus() = "PAL, MOS6581"
    }

    private val looper = shadowOf(Looper.getMainLooper())
    private lateinit var view: FilterCurveView
    private lateinit var feed: FilterCurveFeed
    private val source = SweepVisualizer()

    // Robolectric's Choreographer delivers vsync synchronously, so 60Hz display is simulated by delayed messages
    private val vsync = object : FilterCurveFeed.Vsync {
        private val handler = Handler(Looper.getMainLooper())
        private val callbacks = HashMap<Choreographer.FrameCallback, Runnable>()

        override fun post(callback: Choreographer.FrameCallback) {
            val task = Runnable {
                callbacks.remove(callback)
                callback.doFrame(System.nanoTime())
            }
            callbacks[callback] = task
            handler.postDelayed(task, nextFrameDelay())
        }

        override fun remove(callback: Choreographer.FrameCallback) {
            callbacks.remove(callback)?.let { handler.removeCallbacks(it) }
        }

        // vsync grid of 60Hz display: 16.667ms period in millisecond clock
        private fun nextFrameDelay(): Long {
            val now = SystemClock.uptimeMillis()
            val frame = now * 60 / 1000 + 1
            return (frame * 1000 + 59) / 60 - now
        }
    }

    @Before
    fun setUp() {
        val ctx = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.CustomTheme)
        view = FilterCurveView(ctx)
        // worker is on the same looper to be deterministic
        feed = FilterCurveFeed(view, Handler(Looper.getMainLooper()), vsync)
    }

    @After
    fun tearDown() {
        feed.isAttached = false
    }

    @Test
    fun `follows display rate while playing`() {
        feed.isAttached = true
        feed.source = source
        feed.isPlaying = true
        looper.idleFor(Duration.ofSeconds(1))
        val frames = feed.presentedFrames
        println("vsync=${feed.vsyncFrames} presented=$frames requests=${feed.requests} per 1s")
        assertTrue("presented $frames", frames in 58..61)
        assertEquals(1, view.live.count)
        assertTrue(view.live.chips[0].cutoff > 50)
    }

    @Test
    fun `idle while paused or detached`() {
        feed.isAttached = true
        feed.source = source
        looper.idleFor(Duration.ofSeconds(1))
        // single snapshot of paused playback
        assertEquals(1, source.calls)
        assertEquals(1, view.live.count)
        assertFalse(feed.isRunning)

        feed.isPlaying = true
        looper.idleFor(Duration.ofMillis(500))
        assertTrue(source.calls > 25)
        feed.isAttached = false
        val calls = source.calls
        looper.idleFor(Duration.ofSeconds(1))
        assertTrue(source.calls - calls <= 1)
        assertFalse(feed.isRunning)
        assertEquals(0, view.live.count)
    }
}
