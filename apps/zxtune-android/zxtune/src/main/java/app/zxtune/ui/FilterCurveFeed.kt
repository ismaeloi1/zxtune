/**
 * @file
 * @brief Display rate delivery of SID chips state to filter response view
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Choreographer
import app.zxtune.Logger
import app.zxtune.playback.FrameResult
import app.zxtune.playback.Visualizer
import app.zxtune.playback.VoicesLayout
import app.zxtune.ui.views.FilterCurveView
import app.zxtune.ui.views.GaugesExchange
import app.zxtune.ui.views.SidDescription
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Paced by vsync on UI thread while playing, like the dashboard: each frame presents the latest gauges and requests
 * the next ones on [worker], so binder calls never block drawing and the view lags data by one frame at most.
 * Gauges are of currently heard moment (output latency is compensated by native side, see Sound::Scope), so the
 * response follows what is heard. No allocations per frame: buffers are swapped by [GaugesExchange].
 * Paused or stopped playback is shown by single request.
 */
internal class FilterCurveFeed(
    private val view: FilterCurveView,
    private val worker: Handler = sharedWorker,
    private val vsync: Vsync = ChoreographerVsync(),
) {
    /**
     * Source of display frames, replaceable by tests
     */
    interface Vsync {
        fun post(callback: Choreographer.FrameCallback)
        fun remove(callback: Choreographer.FrameCallback)
    }

    private class ChoreographerVsync : Vsync {
        private val choreographer = Choreographer.getInstance()
        override fun post(callback: Choreographer.FrameCallback) = choreographer.postFrameCallback(callback)
        override fun remove(callback: Choreographer.FrameCallback) = choreographer.removeFrameCallback(callback)
    }

    @Volatile
    var source: Visualizer? = null
        set(value) {
            if (field !== value) {
                field = value
                lastStatusTime = 0
                update()
            }
        }

    var isPlaying = false
        set(value) {
            if (field != value) {
                field = value
                update()
            }
        }

    var isAttached = false
        set(value) {
            if (field != value) {
                field = value
                update()
            }
        }

    /**
     * Emulation description of the last request, see [Visualizer.getStatus]. Empty for non-SID tunes
     */
    @Volatile
    var status = ""
        private set

    /** Presented frames with changed state, for statistics */
    var presentedFrames = 0L
        private set

    /** Vsync callbacks while running, for statistics */
    var vsyncFrames = 0L
        private set

    @Volatile
    var requests = 0L
        private set

    val isRunning
        get() = running

    private val main = Handler(Looper.getMainLooper())
    private val exchange = GaugesExchange()
    private val pending = AtomicBoolean(false)

    @Volatile
    private var running = false
    private var lastSerial = 0L

    @Volatile
    private var lastStatusTime = 0L
    private var clockStatus = ""

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) {
                return
            }
            vsync.post(this)
            ++vsyncFrames
            present()
            request()
        }
    }

    private val presentTask = Runnable { present() }

    private val fetchTask = Runnable { fetch() }

    init {
        // single requests of paused playback are presented as soon as ready, running feed picks them by vsync
        exchange.listener = {
            if (!running) {
                main.post(presentTask)
            }
        }
    }

    private fun update() {
        val active = isAttached && source != null
        val run = active && isPlaying
        if (run != running) {
            LOG.d { "running=$run" }
            running = run
            if (run) {
                vsync.post(frameCallback)
            } else {
                vsync.remove(frameCallback)
            }
        }
        if (active && !run) {
            request()
        } else if (!active) {
            clear()
        }
    }

    private fun clear() {
        if (view.live.count != 0) {
            view.live.count = 0
            view.invalidate()
        }
    }

    private fun request() {
        if (pending.compareAndSet(false, true)) {
            worker.post(fetchTask)
        }
    }

    private fun present() {
        if (source == null || !isAttached) {
            return
        }
        val data = exchange.acquire()
        if (data.serial == lastSerial) {
            return
        }
        lastSerial = data.serial
        val st = status
        if (st !== clockStatus) {
            clockStatus = st
            view.live.clock = SidDescription.Chip.fromStatus(st).clock
        }
        if (view.live.read(data.bytes, data.count)) {
            ++presentedFrames
            view.invalidate()
        }
    }

    // worker thread
    private fun fetch() {
        try {
            val src = source ?: return
            val now = System.currentTimeMillis()
            // chip model and clock are changed only with tune, so rarely requested text is enough
            if (lastStatusTime == 0L || now - lastStatusTime > STATUS_PERIOD_MS) {
                lastStatusTime = now
                status = runCatching { src.getStatus() }.getOrDefault("").takeIf { it.contains("MOS") } ?: ""
            }
            val buffer = exchange.buffer(FrameResult.GAUGES_CHIPS, VoicesLayout.MASTER)
            val chips = if (status.isEmpty()) 0 else src.getGauges(buffer, WAVE_WINDOW_MS)
            ++requests
            exchange.publish(FrameResult.GAUGES_CHIPS, chips, VoicesLayout.MASTER)
        } catch (e: Exception) {
            LOG.w(e) { "Failed to get live state" }
        } finally {
            pending.set(false)
        }
    }

    companion object {
        private val LOG = Logger(FilterCurveFeed::class.java.name)
        private const val STATUS_PERIOD_MS = 1000L

        // only registers snapshot is used, history columns are not displayed
        private const val WAVE_WINDOW_MS = 10

        private val sharedWorker by lazy {
            Handler(HandlerThread("FilterCurve", Process.THREAD_PRIORITY_DISPLAY).apply { start() }.looper)
        }
    }
}
