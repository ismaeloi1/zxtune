/**
 * @file
 * @brief Recent SID filter states of displayed chip for time based plots
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

/**
 * Ring buffer filled at display rate (see [app.zxtune.ui.FilterCurveFeed]), so all the frames are preallocated and
 * reused: pushing and reading never allocate. Oldest frame is overwritten when [capacity] is reached.
 */
class FilterHistory(val capacity: Int = 512) {
    class Voice {
        var freqHz = 0f

        /** Envelope 0..1 */
        var level = 0f
        var routed = false

        /** Filter gain at [freqHz] if [routed], else 0 */
        var gainDb = 0f
        var noise = false

        /** Produces sound (gate or release in progress, waveform selected, not muted) */
        var active = false

        internal fun reset() {
            freqHz = 0f
            level = 0f
            routed = false
            gainDb = 0f
            noise = false
            active = false
        }
    }

    class Frame {
        var timeMs = 0L
        var cutoffHz = 0f

        /** 0..15 */
        var resonance = 0

        /** bit0 LP, bit1 BP, bit2 HP */
        var mode = 0
        var q = 0.707f
        val voices = Array(3) { Voice() }
        var extRouted = false

        internal fun reset() {
            timeMs = 0L
            cutoffHz = 0f
            resonance = 0
            mode = 0
            q = 0.707f
            voices.forEach { it.reset() }
            extRouted = false
        }
    }

    init {
        require(capacity > 0) { "Invalid capacity $capacity" }
    }

    private val frames = Array(capacity) { Frame() }

    // index of oldest frame
    private var head = 0

    var size = 0
        private set

    fun frame(indexFromOldest: Int): Frame {
        if (indexFromOldest !in 0 until size) {
            throw IndexOutOfBoundsException("$indexFromOldest of $size")
        }
        return frames[(head + indexFromOldest) % capacity]
    }

    fun latest(): Frame? = if (size != 0) frame(size - 1) else null

    fun push(timeMs: Long, fill: (Frame) -> Unit) {
        val slot = if (size < capacity) {
            (head + size++) % capacity
        } else {
            head.also { head = (head + 1) % capacity }
        }
        // reused frame keeps values of overwritten one, so filler doesn't depend on history depth
        val frame = frames[slot]
        frame.reset()
        frame.timeMs = timeMs
        fill(frame)
    }

    fun clear() {
        head = 0
        size = 0
    }

    fun spanMs(): Long = if (size < 2) 0L else frame(size - 1).timeMs - frame(0).timeMs
}
