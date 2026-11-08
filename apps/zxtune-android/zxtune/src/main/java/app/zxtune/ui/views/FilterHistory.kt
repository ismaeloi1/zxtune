/**
 * @file
 * @brief Recent SID filter states for history lanes
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

/**
 * Ring buffer of preallocated frames, so pushing at display rate doesn't allocate.
 */
class FilterHistory(val capacity: Int = 512) {
    class Voice {
        var freqHz = 0f
        var level = 0f
        var routed = false
        var gainDb = 0f
        var noise = false
        var active = false
    }

    class Frame {
        var timeMs = 0L
        var cutoffHz = 0f
        var resonance = 0
        var mode = 0
        var q = 0.707f
        val voices = Array(3) { Voice() }
        var extRouted = false
    }

    private val frames = Array(capacity) { Frame() }
    private var head = 0

    var size = 0
        private set

    fun frame(indexFromOldest: Int): Frame = frames[(head - size + indexFromOldest + capacity) % capacity]

    fun latest(): Frame? = if (size != 0) frame(size - 1) else null

    fun push(timeMs: Long, fill: (Frame) -> Unit) {
        val frame = frames[head]
        frame.timeMs = timeMs
        fill(frame)
        head = (head + 1) % capacity
        size = minOf(size + 1, capacity)
    }

    fun clear() {
        size = 0
    }

    fun spanMs() = if (size > 1) frame(size - 1).timeMs - frame(0).timeMs else 0L
}
