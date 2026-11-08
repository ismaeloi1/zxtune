package app.zxtune.ui.views

/**
 * Ring buffer of SID filter states by time, frames are preallocated and reused by [push]
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
    private var start = 0

    var size = 0
        private set

    fun frame(indexFromOldest: Int): Frame = frames[(start + indexFromOldest) % capacity]

    fun latest(): Frame? = if (size > 0) frame(size - 1) else null

    fun push(timeMs: Long, fill: (Frame) -> Unit) {
        val slot = if (size < capacity) {
            frame(size++)
        } else {
            frames[start].also { start = (start + 1) % capacity }
        }
        slot.timeMs = timeMs
        fill(slot)
    }

    fun clear() {
        start = 0
        size = 0
    }

    fun spanMs(): Long = if (size > 1) frame(size - 1).timeMs - frame(0).timeMs else 0L
}
