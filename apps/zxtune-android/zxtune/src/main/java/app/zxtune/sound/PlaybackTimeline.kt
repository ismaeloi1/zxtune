/**
 * @file
 * @brief Mapping between frames passed to output and frames rendered by sources
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.sound

/**
 * Output keeps FIFO order, so N-th frame played since output start is N-th frame rendered since that moment.
 * Each source frames are counted from the source's start to match player's own rendered samples index.
 */
class PlaybackTimeline {
    private class Segment(
        val source: SamplesSource,
        val sourceStart: Long,
        val globalStart: Long,
        val frames: Int
    )

    private val segments = ArrayDeque<Segment>()
    private var rendered = 0L
    private var currentSource: SamplesSource? = null
    private var currentSourceFrames = 0L

    /**
     * Called on output (re)start
     */
    @Synchronized
    fun reset() {
        segments.clear()
        rendered = 0
    }

    /**
     * @param committed whether frames are passed to output
     */
    @Synchronized
    fun onRendered(source: SamplesSource, frames: Int, committed: Boolean) {
        if (currentSource !== source) {
            currentSource = source
            currentSourceFrames = 0
        }
        if (committed) {
            segments.addLast(Segment(source, currentSourceFrames, rendered, frames))
            rendered += frames
            while (segments.size > MAX_SEGMENTS) {
                segments.removeFirst()
            }
        }
        currentSourceFrames += frames
    }

    /**
     * @param played frames played since output start
     * @return index of currently playing frame in source's frames or -1 if not playing now
     */
    @Synchronized
    fun positionOf(source: SamplesSource, played: Long): Long {
        while (segments.size > 1 && segments[1].globalStart <= played) {
            segments.removeFirst()
        }
        val seg = segments.firstOrNull() ?: return -1
        if (seg.source !== source || played < seg.globalStart) {
            return -1
        }
        return seg.sourceStart + minOf(played - seg.globalStart, seg.frames.toLong())
    }

    private companion object {
        const val MAX_SEGMENTS = 64
    }
}
