/**
 * @file
 * @brief Precise playback position of AudioTrack
 * @author vitamin.caig@gmail.com
 *
 * Approach is similar to ExoPlayer's AudioTrackPositionTracker and VLC's AudioTrack output:
 * - AudioTrack.getTimestamp gives frame actually presented at specified time, extrapolated between polls
 * - fallback is smoothed playback head position minus hardware latency
 */
package app.zxtune.device.sound

import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import java.lang.reflect.Method
import kotlin.math.abs

internal class AudioTrackPositionTracker(
    private val track: AudioTrack,
    private val sampleRate: Int,
    bufferSizeFrames: Int,
) {
    private val bufferDurationUs = bufferSizeFrames * 1_000_000L / sampleRate
    private val timestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) AudioTimestamp() else null
    private val getLatency: Method? = runCatching {
        AudioTrack::class.java.getMethod("getLatency")
    }.getOrNull()

    // playback head is unsigned 32-bit and wraps
    private var lastRawHead = 0L
    private var headWraps = 0L
    private var baseFrames = 0L

    // timestamp state
    private var timestampFrames = -1L
    private var timestampNanos = 0L
    private var timestampPollNanos = 0L
    private var timestampValid = false

    // smoothed head position fallback
    private val headOffsets = LongArray(HEAD_SAMPLES)
    private var headOffsetsCount = 0
    private var headOffsetsIndex = 0
    private var headPollNanos = 0L
    private var latencyFrames = 0L
    private var latencyPollNanos = 0L

    private var lastResult = 0L

    /**
     * Statistics for debug purposes
     */
    var lastLatencyMs = 0L
        private set
    val isTimestampUsed
        get() = timestampValid

    /**
     * Should be called after track started
     */
    @Synchronized
    fun reset() {
        lastRawHead = 0
        headWraps = 0
        baseFrames = 0
        baseFrames = headFrames()
        timestampFrames = -1
        timestampPollNanos = 0
        timestampValid = false
        headOffsetsCount = 0
        headOffsetsIndex = 0
        headPollNanos = 0
        latencyPollNanos = 0
        lastResult = 0
    }

    /**
     * @param written frames written to track since reset
     * @return frames presented to user since reset
     */
    @Synchronized
    fun playedFrames(written: Long, nowNanos: Long = System.nanoTime()): Long {
        val head = headFrames()
        val estimated = timestampPosition(nowNanos, head) ?: headPosition(nowNanos, head)
        // never go back or beyond written data
        val result = estimated.coerceIn(lastResult, maxOf(written, lastResult))
        lastResult = result
        return result
    }

    private fun headFrames(): Long {
        val raw = track.playbackHeadPosition.toLong() and 0xffffffffL
        if (raw < lastRawHead) {
            ++headWraps
        }
        lastRawHead = raw
        return (headWraps shl 32) + raw - baseFrames
    }

    private fun timestampPosition(nowNanos: Long, head: Long): Long? {
        val ts = timestamp ?: return null
        val interval = if (timestampValid) TIMESTAMP_POLL_VALID_NS else TIMESTAMP_POLL_INITIAL_NS
        if (nowNanos - timestampPollNanos >= interval) {
            timestampPollNanos = nowNanos
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && track.getTimestamp(ts)) {
                val frames = ts.framePosition - baseFrames
                // timestamp is useful only when it's advancing
                timestampValid = timestampFrames in 0 until frames || (timestampValid && frames == timestampFrames)
                timestampFrames = frames
                timestampNanos = ts.nanoTime
            } else {
                timestampValid = false
            }
        }
        if (!timestampValid) {
            return null
        }
        val position = timestampFrames + (nowNanos - timestampNanos) * sampleRate / 1_000_000_000L
        // sanity check against playback head, see ExoPlayer's MAX_AUDIO_TIMESTAMP_OFFSET_US
        if (abs(position - head) > sampleRate * MAX_TIMESTAMP_OFFSET_SEC) {
            timestampValid = false
            return null
        }
        lastLatencyMs = 0
        return position
    }

    private fun headPosition(nowNanos: Long, head: Long): Long {
        val nowFrames = nowNanos / 1000 * sampleRate / 1_000_000L
        if (nowNanos - headPollNanos >= HEAD_POLL_NS || headOffsetsCount == 0) {
            headPollNanos = nowNanos
            headOffsets[headOffsetsIndex] = head - nowFrames
            headOffsetsIndex = (headOffsetsIndex + 1) % HEAD_SAMPLES
            headOffsetsCount = minOf(headOffsetsCount + 1, HEAD_SAMPLES)
        }
        var sum = 0L
        for (idx in 0 until headOffsetsCount) {
            sum += headOffsets[idx]
        }
        updateLatency(nowNanos)
        return nowFrames + sum / headOffsetsCount - latencyFrames
    }

    // AudioTrack.getLatency() includes the track buffer itself, so subtract it
    private fun updateLatency(nowNanos: Long) {
        val method = getLatency ?: return
        if (nowNanos - latencyPollNanos < LATENCY_POLL_NS) {
            return
        }
        latencyPollNanos = nowNanos
        runCatching {
            val latencyUs = (method.invoke(track) as Int) * 1000L - bufferDurationUs
            val sane = if (latencyUs in 0..MAX_LATENCY_US) latencyUs else 0L
            latencyFrames = sane * sampleRate / 1_000_000L
            lastLatencyMs = sane / 1000
        }
    }

    companion object {
        private const val TIMESTAMP_POLL_INITIAL_NS = 10_000_000L
        private const val TIMESTAMP_POLL_VALID_NS = 1_000_000_000L
        private const val MAX_TIMESTAMP_OFFSET_SEC = 1
        private const val HEAD_SAMPLES = 10
        private const val HEAD_POLL_NS = 30_000_000L
        private const val LATENCY_POLL_NS = 500_000_000L
        private const val MAX_LATENCY_US = 5_000_000L
    }
}
