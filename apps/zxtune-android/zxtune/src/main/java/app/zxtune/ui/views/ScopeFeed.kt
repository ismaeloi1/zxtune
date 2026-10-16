/**
 * @file
 * @brief Oscilloscope data pump shared by rendering backends
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import app.zxtune.Logger
import app.zxtune.playback.ScopeLayout
import app.zxtune.playback.Visualizer
import app.zxtune.playback.VoicesLayout

private val LOG = Logger("ScopeFeed")

/**
 * Requests waveforms on own thread paced by vsync (no binder calls on UI thread), limits frame rate and skips
 * frames with the same data (e.g. paused or silent playback), so the picture is kept without any GPU work.
 */
internal class ScopeFeed(context: android.content.Context, private val sink: Sink) {

    /**
     * Called on feed thread with new data, frame is valid only during the call
     */
    fun interface Sink {
        fun onFrame(frame: Frame)
    }

    class Frame {
        val samples = ShortArray(MAX_CHANNELS * ScopeGrid.MAX_POINTS)
        var channels = 0
        var points = 0
        var grid: ScopeGrid? = null

        /** Incremented on grid or view size change, so static data should be updated */
        var generation = 0
        var width = 1
        var height = 1

        fun sameData(rh: Frame) = channels == rh.channels && points == rh.points && generation == rh.generation &&
            samples.contentEquals(rh.samples, channels * points)
    }

    val stats = ScopeStats()

    @Volatile
    var source: Visualizer? = null
        set(value) {
            field = value
            forceUpdate = true
        }

    @Volatile
    var windowMs = OscilloscopeView.DEFAULT_WINDOW_MS

    /**
     * Frames per second limit, 0 for display refresh rate
     */
    @Volatile
    var maxFps = 0

    @Volatile
    private var forceUpdate = true

    @Volatile
    private var width = 0

    @Volatile
    private var height = 0

    private var current = Frame()
    private var previous = Frame()
    private var layoutSource: Visualizer? = null
    private var layoutId = -1
    private var voicesLayout = VoicesLayout.MASTER
    private var lastFrameTime = 0L
    private var lastVsyncTime = 0L
    private var vsyncPeriod = DEFAULT_VSYNC_NS
    private var vsyncWindowMin = Long.MAX_VALUE
    private var vsyncWindowSize = 0
    private var isRunning = false
    private val hint = WorkHint(context)

    /**
     * Expected interval between frames, for performance hints
     */
    @Volatile
    var targetNanos = DEFAULT_VSYNC_NS
        private set

    private val thread = sharedThread
    private val handler = android.os.Handler(thread.looper)

    // Choreographer is per-thread, so vsync callbacks come to feed's thread
    private val choreographer by lazy { Choreographer.getInstance() }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) {
                return
            }
            choreographer.postFrameCallback(this)
            trackVsync(frameTimeNanos)
            if (isThrottled(frameTimeNanos)) {
                return
            }
            val start = System.nanoTime()
            update()
            hint.report(targetNanos, System.nanoTime() - start)
        }
    }

    fun setSize(w: Int, h: Int) {
        width = w
        height = h
        forceUpdate = true
    }

    fun setRunning(running: Boolean) = handler.post {
        if (running != isRunning) {
            LOG.d { "running=$running" }
            isRunning = running
            if (running) {
                lastFrameTime = 0
                choreographer.postFrameCallback(frameCallback)
            } else {
                choreographer.removeFrameCallback(frameCallback)
                hint.release()
                lastVsyncTime = 0
            }
        }
    }

    /**
     * Run [action] on feed thread and wait for completion (e.g. to release surface synchronously)
     */
    fun runSync(action: () -> Unit) {
        if (Thread.currentThread() === thread) {
            action()
            return
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try {
                action()
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    fun post(action: () -> Unit) = handler.post(action)

    /**
     * Single frame update, e.g. picture for paused playback
     */
    fun updateOnce() = handler.post {
        if (!isRunning) {
            update()
        }
    }

    // refresh rate may change at any moment (e.g. by power saving or adaptive refresh rate)
    private fun trackVsync(frameTimeNanos: Long) {
        if (lastVsyncTime != 0L) {
            // minimal interval among several ones, so skipped vsyncs are ignored
            val interval = frameTimeNanos - lastVsyncTime
            if (interval >= MIN_VSYNC_NS) {
                vsyncWindowMin = minOf(vsyncWindowMin, interval)
            }
            if (++vsyncWindowSize == VSYNC_WINDOW && vsyncWindowMin != Long.MAX_VALUE) {
                vsyncPeriod = vsyncWindowMin
                vsyncWindowMin = Long.MAX_VALUE
                vsyncWindowSize = 0
            }
        }
        lastVsyncTime = frameTimeNanos
        val fps = maxFps
        targetNanos = if (fps > 0) maxOf(vsyncPeriod, 1_000_000_000L / fps) else vsyncPeriod
    }

    // vsync timestamps are jittered, so some slack is allowed to not skip frames at exact limit
    private fun isThrottled(frameTimeNanos: Long): Boolean {
        val fps = maxFps
        if (fps <= 0 || lastFrameTime == 0L) {
            lastFrameTime = frameTimeNanos
            return false
        }
        val minInterval = 1_000_000_000L / fps
        if (frameTimeNanos - lastFrameTime < minInterval - THROTTLE_SLACK_NS) {
            return true
        }
        lastFrameTime = frameTimeNanos
        return false
    }

    private fun update() {
        val src = source ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) {
            return
        }
        val frame = previous
        val force = forceUpdate
        forceUpdate = false
        val points = current.grid?.points(w) ?: ScopeGrid.MAX_POINTS
        val dataStart = System.nanoTime()
        val layout = try {
            ScopeLayout(src.getScope(frame.samples, points, windowMs))
        } catch (e: Exception) {
            LOG.w(e) { "Failed to get scope data" }
            return
        }
        stats.data(System.nanoTime() - dataStart)
        val channels = layout.channels.coerceAtMost(MAX_CHANNELS)
        frame.channels = channels
        frame.points = points
        frame.width = w
        frame.height = h
        frame.generation = current.generation
        frame.grid = current.grid
        if (channels == 0) {
            stats.skip()
            return
        }
        if (src !== layoutSource || layout.id != layoutId) {
            voicesLayout = try {
                VoicesLayout.parse(src.getLayout())
            } catch (e: Exception) {
                LOG.w(e) { "Failed to get voices layout" }
                return
            }
            layoutSource = src
            layoutId = layout.id
            frame.grid = null
        }
        if (frame.grid?.channels != channels || w != current.width || h != current.height || force) {
            frame.grid = frame.grid?.takeIf { it.channels == channels } ?: ScopeGrid.of(voicesLayout, channels)
            frame.generation = current.generation + 1
        }
        if (frame.sameData(current)) {
            stats.skip()
            return
        }
        previous = current
        current = frame
        sink.onFrame(frame)
    }

    companion object {
        const val MAX_CHANNELS = 32

        // Single thread for all the views (usually only one is visible), so nothing to release
        private val sharedThread by lazy {
            HandlerThread("ScopeFeed", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        }
        private const val THROTTLE_SLACK_NS = 2_000_000L
        private const val DEFAULT_VSYNC_NS = 16_666_667L

        // 240Hz
        private const val MIN_VSYNC_NS = 4_000_000L
        private const val VSYNC_WINDOW = 32

        private fun ShortArray.contentEquals(rh: ShortArray, size: Int): Boolean {
            for (idx in 0 until size) {
                if (this[idx] != rh[idx]) {
                    return false
                }
            }
            return true
        }
    }
}

/**
 * Rendering statistics, presented frames only
 */
internal class ScopeStats {
    private var frames = 0
    private var skipped = 0
    private var framesStart = 0L
    private var drawNanos = 0L
    private var dataNanos = 0L
    private var dataCalls = 0
    private var maxDrawNanos = 0L
    private var maxIntervalNanos = 0L
    private var lastFrameStart = 0L

    @Volatile
    var text = ""
        private set

    @Synchronized
    fun data(nanos: Long) {
        dataNanos += nanos
        ++dataCalls
    }

    @Synchronized
    fun skip() {
        ++skipped
    }

    @Synchronized
    fun frame(backend: String, start: Long, end: Long) {
        if (lastFrameStart != 0L) {
            maxIntervalNanos = maxOf(maxIntervalNanos, start - lastFrameStart)
        }
        lastFrameStart = start
        if (framesStart == 0L) {
            framesStart = start
        }
        ++frames
        drawNanos += end - start
        maxDrawNanos = maxOf(maxDrawNanos, end - start)
        val period = end - framesStart
        if (period >= STATS_PERIOD_NS) {
            val fps = frames * 1e9 / period
            text = String.format(
                java.util.Locale.US,
                "%s: %.1f fps (%d same), frame %.2f ms (max %.1f), draw %.2f ms (max %.1f), data %.2f ms",
                backend,
                fps,
                skipped,
                1000 / fps,
                maxIntervalNanos / 1e6,
                drawNanos / 1e6 / frames,
                maxDrawNanos / 1e6,
                dataNanos / 1e6 / maxOf(dataCalls, 1)
            )
            frames = 0
            skipped = 0
            framesStart = 0
            drawNanos = 0
            dataNanos = 0
            dataCalls = 0
            maxDrawNanos = 0
            maxIntervalNanos = 0
        }
    }

    private companion object {
        const val STATS_PERIOD_NS = 1_000_000_000L
    }
}
