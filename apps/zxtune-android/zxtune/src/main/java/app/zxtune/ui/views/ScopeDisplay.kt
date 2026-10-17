/**
 * @file
 * @brief Oscilloscope rendering backends common interface and helpers
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.os.Build
import android.view.Surface
import android.view.View
import androidx.core.view.ViewCompat
import app.zxtune.playback.Visualizer
import app.zxtune.ui.findWindow
import app.zxtune.ui.requestRefreshRate
import app.zxtune.ui.resetRefreshRate
import app.zxtune.ui.voteFrameRate
import kotlin.math.abs

/**
 * Oscilloscope view regardless of rendering backend
 */
interface ScopeDisplay {
    val source: Visualizer?

    /**
     * Displayed duration of waveforms, as corrscope's render_ms
     */
    var windowMs: Int

    /**
     * Visual amplification in percents, [OscilloscopeView.AUTO_GAIN] for automatic per-voice gain
     */
    var gainPercent: Int

    /**
     * Frames per second limit, 0 for display refresh rate
     */
    var maxFps: Int

    /**
     * Receiver of dashboard gauges requested together with waveforms
     */
    var gauges: GaugesExchange?

    /**
     * @param src source of data or null to stop updating (last picture is kept)
     * @param isLive false to display single picture from source (e.g. paused playback)
     */
    fun setSource(src: Visualizer?, isLive: Boolean = true)

    /**
     * @return rendering statistics: real fps, frame interval, drawing time, data request time
     */
    fun getStatistics(): String
}

/**
 * Animation is active only while there's data source and visible surface: then the highest allowed refresh rate
 * is voted for, else the vote is cleared to let display slow down.
 */
internal class ScopeActivity(private val view: View, private val feed: ScopeFeed) {
    var surface: Surface? = null
        set(value) {
            field = value
            update()
        }
    var isLive = true
    private var isActive = false

    fun update() {
        val visible = feed.source != null && surface != null && ViewCompat.isAttachedToWindow(view)
        val active = visible && isLive
        feed.setRunning(active)
        if (visible && !active) {
            feed.updateOnce()
        }
        if (active) {
            val fps = feed.maxFps
            // vote is not changed if already active with the same limit, each call may cause mode switch
            if (!isActive || fps != votedFps) {
                view.context.findWindow()?.requestRefreshRate(fps)
                surface?.voteFrameRate(if (fps > 0) fps.toFloat() else maxDisplayRate())
                votedFps = fps
            }
        } else if (isActive) {
            view.context.findWindow()?.resetRefreshRate()
            surface?.voteFrameRate(0f)
        }
        isActive = active
    }

    private var votedFps = -1

    fun detach() {
        feed.setRunning(false)
        if (isActive) {
            view.context.findWindow()?.resetRefreshRate()
            isActive = false
        }
    }

    private fun maxDisplayRate() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        runCatching { view.display?.supportedModes?.maxOfOrNull { it.refreshRate } }.getOrNull()
    } else {
        null
    } ?: 60f
}

/**
 * Automatic gain follows peak level: immediate attack to avoid clipping,
 * slow release to avoid visible pumping. Limited to not amplify noise of silent voices
 */
internal class AutoGain {
    private val smoothedPeaks = FloatArray(ScopeFeed.MAX_CHANNELS)

    fun of(chan: Int, samples: ShortArray, offset: Int, points: Int, fixedPercent: Int): Float {
        if (fixedPercent != OscilloscopeView.AUTO_GAIN) {
            return fixedPercent / 100f
        }
        var peak = 0
        for (idx in 0 until points) {
            peak = maxOf(peak, abs(samples[offset + idx].toInt()))
        }
        val level = peak / 32768f
        val prev = smoothedPeaks[chan]
        val smoothed = if (level > prev) level else prev + (level - prev) * RELEASE
        smoothedPeaks[chan] = smoothed
        return (TARGET / maxOf(smoothed, TARGET / MAX)).coerceAtLeast(1f)
    }

    private companion object {
        const val TARGET = 0.9f
        const val MAX = 8f

        // per frame, ~0.5s to rise at 120Hz
        const val RELEASE = 0.04f
    }
}
