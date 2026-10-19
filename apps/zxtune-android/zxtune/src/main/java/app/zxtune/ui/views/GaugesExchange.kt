/**
 * @file
 * @brief Dashboard gauges delivery from oscilloscope data thread
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import app.zxtune.playback.ChipGauges
import app.zxtune.playback.FrameResult
import app.zxtune.playback.VoiceGauges
import app.zxtune.playback.VoicesLayout

/**
 * Gauges are requested by [ScopeFeed] together with waveforms (see [app.zxtune.playback.Visualizer.getFrame]):
 * dashboard and oscilloscope display the same moment and single request per frame is made.
 * Triple buffering: producer (feed thread) and consumer (UI thread) never wait each other and no arrays are allocated
 * per frame.
 */
class GaugesExchange {
    class Data {
        var bytes = ByteArray(0)
            internal set

        /** [FrameResult.GAUGES_CHIPS] or [FrameResult.GAUGES_VOICES] */
        var kind = FrameResult.GAUGES_NONE
            internal set

        /** Count of chips or voices */
        var count = 0
            internal set
        var layout = VoicesLayout.MASTER
            internal set

        /** Incremented by each published data */
        var serial = 0L
            internal set
    }

    /**
     * Requested by consumer, e.g. dashboard is visible
     */
    @Volatile
    var isEnabled = false

    /**
     * See [app.zxtune.playback.Visualizer.getGauges]
     */
    @Volatile
    var waveWindowMs = SidDashboardView.DEFAULT_WAVE_WINDOW_MS

    /**
     * Called on producer thread after new data is published
     */
    @Volatile
    var listener: (() -> Unit)? = null

    private val lock = Any()
    private var write = Data()
    private var ready = Data()
    private var read = Data()
    private var serial = 0L

    /**
     * Producer side: buffer to fill
     */
    fun buffer(kind: Int, layout: VoicesLayout): ByteArray {
        val size = sizeOf(kind, layout)
        if (write.bytes.size != size) {
            write.bytes = ByteArray(size)
        }
        return write.bytes
    }

    /**
     * Producer side: filled buffer (see [buffer]) is ready
     */
    fun publish(kind: Int, count: Int, layout: VoicesLayout) {
        synchronized(lock) {
            write.kind = kind
            write.count = count
            write.layout = layout
            write.serial = ++serial
            val tmp = ready
            ready = write
            write = tmp
        }
        listener?.invoke()
    }

    /**
     * Consumer side: the latest published data, valid until the next call
     */
    fun acquire(): Data {
        synchronized(lock) {
            if (ready.serial > read.serial) {
                val tmp = read
                read = ready
                ready = tmp
            }
        }
        return read
    }

    companion object {
        const val MAX_CHIPS = 3

        fun kindOf(layout: VoicesLayout) = when {
            layout.hasRegisters -> FrameResult.GAUGES_CHIPS
            layout.voicesCount != 0 -> FrameResult.GAUGES_VOICES
            else -> FrameResult.GAUGES_NONE
        }

        // remote visualizer transfers whole array, so it's as small as possible
        fun sizeOf(kind: Int, layout: VoicesLayout) = when (kind) {
            FrameResult.GAUGES_CHIPS -> MAX_CHIPS * ChipGauges.SIZE
            FrameResult.GAUGES_VOICES -> minOf(layout.voicesCount, MAX_VOICES) * VoiceGauges.SIZE
            else -> 0
        }

        // limited by shared memory buffer of remote visualizer
        private const val MAX_VOICES = 40
    }
}
