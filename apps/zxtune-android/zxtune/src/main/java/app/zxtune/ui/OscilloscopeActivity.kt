package app.zxtune.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.support.v4.media.session.PlaybackStateCompat
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import app.zxtune.device.media.MediaModel
import app.zxtune.playback.Visualizer
import app.zxtune.ui.utils.whenLifecycleStarted
import app.zxtune.ui.views.OscilloscopeView
import app.zxtune.ui.views.ScopePanelLayout
import app.zxtune.ui.views.SidDashboardView
import app.zxtune.utils.ifNotNulls
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Fullscreen oscilloscope, follows device orientation:
 * - portrait: oscilloscope with SID state dashboard below, as in now playing view
 * - landscape: oscilloscope only
 * Black background covers the whole screen (cutout mode is set by theme). Content avoids only side cutouts
 * (landscape notches): top/bottom ones (camera holes) overlap just a part of the first voice waveform, while
 * avoiding them wastes a band of screen height.
 */
class OscilloscopeActivity : AppCompatActivity() {
    private lateinit var oscilloscope: OscilloscopeView
    private lateinit var dashboard: SidDashboardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // window geometry should be final before content is laid out
        setupImmersiveMode()
        oscilloscope = OscilloscopeView(this).apply {
            setOnClickListener { finish() }
        }
        dashboard = SidDashboardView(this)
        val panel = ScopePanelLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(oscilloscope)
            addView(dashboard)
            setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            // avoid relayout (and oscilloscope surface repositioning) on repeated identical insets
            if (view.paddingLeft != cutout.left || view.paddingRight != cutout.right) {
                view.setPadding(cutout.left, 0, cutout.right, 0)
            }
            insets
        }
        setContentView(panel)
        updateLayout(resources.configuration)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        MediaModel.of(this).run {
            whenLifecycleStarted {
                launch {
                    OscilloscopeSettings.apply(this@OscilloscopeActivity, oscilloscope, dashboard)
                }
                visualizer.combine(playbackState) { visualizer, playbackState ->
                    ifNotNulls(visualizer, playbackState?.state) { src, state ->
                        src.takeIf { PlaybackStateCompat.STATE_PLAYING == state }
                    }
                }.distinctUntilChanged { old, new -> old === new }.collect {
                    oscilloscope.setSource(it)
                    updateDashboardSource()
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLayout(newConfig)
    }

    private var currentSource: Visualizer? = null

    private fun updateLayout(config: Configuration) {
        dashboard.isVisible = config.orientation != Configuration.ORIENTATION_LANDSCAPE
        updateDashboardSource()
    }

    private fun updateDashboardSource() {
        val src = oscilloscope.source.takeIf { dashboard.isVisible }
        if (src !== currentSource) {
            currentSource = src
            dashboard.setSource(src, oscilloscope::getStatistics)
        }
    }

    override fun onResume() {
        super.onResume()
        oscilloscope.onResume()
    }

    override fun onPause() {
        oscilloscope.onPause()
        super.onPause()
    }

    override fun onStop() {
        oscilloscope.setSource(null)
        updateDashboardSource()
        super.onStop()
    }

    // Display cutout mode is set by FullscreenTheme, changing it at runtime causes window relayout
    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, OscilloscopeActivity::class.java))
    }
}
