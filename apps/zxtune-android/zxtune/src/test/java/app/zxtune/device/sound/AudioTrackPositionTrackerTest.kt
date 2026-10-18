package app.zxtune.device.sound

import android.media.AudioTimestamp
import android.media.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioTrackPositionTrackerTest {

    private val rate = 48000

    // presented frame of timestamps runs slower than system clock, as audio and system clocks drift
    private var clockRatio = 1.0
    private var nowNanos = 1_000_000_000L

    private val track = mock<AudioTrack> {
        on { playbackHeadPosition } doAnswer { framesAt(nowNanos).toInt() }
        on { getTimestamp(any()) } doAnswer {
            val ts = it.arguments[0] as AudioTimestamp
            ts.nanoTime = nowNanos
            ts.framePosition = framesAt(nowNanos)
            true
        }
    }

    private fun framesAt(nanos: Long) = ((nanos - START_NANOS) * clockRatio * rate / 1e9).toLong().coerceAtLeast(0)

    // Each timestamp poll corrects extrapolated position back, but position keeps moving at each frame
    @Test
    fun `backward corrections do not stop position`() {
        val tracker = AudioTrackPositionTracker(track, rate, rate / 10)
        nowNanos = START_NANOS
        tracker.reset()
        clockRatio = 0.98
        var prev = -1L
        var same = 0
        var maxError = 0L
        // 5 seconds of 120Hz frames
        repeat(600) {
            nowNanos += 8_333_333L
            val pos = tracker.playedFrames(Long.MAX_VALUE, nowNanos)
            if (pos == prev) {
                ++same
            }
            prev = pos
            if (it > 240) {
                maxError = maxOf(maxError, kotlin.math.abs(pos - framesAt(nowNanos)))
            }
        }
        assertEquals(0, same)
        // follows actual position anyway (extrapolation between polls drifts by 2%)
        assertTrue("error=$maxError", maxError < rate * 3 / 100)
    }

    private companion object {
        const val START_NANOS = 1_000_000_000L
    }
}
