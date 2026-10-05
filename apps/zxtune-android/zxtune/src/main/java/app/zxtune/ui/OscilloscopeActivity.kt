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
 * Black background covers the whole screen, while content avoids display cutouts (camera holes, notches).
 */
class OscilloscopeActivity : AppCompatActivity() {
    private lateinit var oscilloscope: OscilloscopeView
    private lateinit var dashboard: SidDashboardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            view.setPadding(cutout.left, cutout.top, cutout.right, cutout.bottom)
            insets
        }
        setContentView(panel)
        updateLayout(resources.configuration)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupImmersiveMode()
        requestHighestRefreshRate()

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

    private fun setupImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // draw black background under cutout area in both orientations, see insets listener
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // Use maximal refresh rate of display for current resolution (e.g. 120Hz)
    private fun requestHighestRefreshRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay ?: return
        val current = display.mode
        val best = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }.maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = best.modeId
        }
    }

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, OscilloscopeActivity::class.java))
    }
}
