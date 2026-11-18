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
import app.zxtune.ui.views.OscilloscopeFrame
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
 * Black background covers the whole screen (cutout mode is set by theme). Content avoids cutouts and rounded
 * corners on every side, in both orientations (camera hole of portrait top edge, notch of landscape side).
 */
class OscilloscopeActivity : AppCompatActivity() {
    private lateinit var oscilloscope: OscilloscopeFrame
    private lateinit var dashboard: SidDashboardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // window geometry should be final before content is laid out
        setupImmersiveMode()
        oscilloscope = OscilloscopeFrame(this).apply {
            setOnClickListener { finish() }
            // live settings panel over oscilloscope
            setOnLongClickListener {
                ScopeSettingsDialog.show(supportFragmentManager)
                true
            }
        }
        dashboard = SidDashboardView(this).also {
            it.attachTo(oscilloscope)
        }
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
            val corners = roundedCornersInsets(view)
            val left = maxOf(cutout.left, corners[0])
            val top = maxOf(cutout.top, corners[1])
            val right = maxOf(cutout.right, corners[2])
            val bottom = maxOf(cutout.bottom, corners[3])
            // avoid relayout (and oscilloscope surface repositioning) on repeated identical insets
            if (view.paddingLeft != left || view.paddingTop != top || view.paddingRight != right ||
                view.paddingBottom != bottom
            ) {
                view.setPadding(left, top, right, bottom)
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
                    OscilloscopeSettings.changes.collect {
                        OscilloscopeSettings.apply(this@OscilloscopeActivity, oscilloscope, dashboard)
                    }
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

    // Content inside the rounded corners: the arc leaves radius * (1 - 1/sqrt(2)) at the diagonal, so text and
    // frames of panel corners stay visible. Edges are left/top/right/bottom of the current orientation.
    private fun roundedCornersInsets(view: android.view.View): IntArray {
        val result = IntArray(4)
        if (Build.VERSION.SDK_INT < 31) {
            return result
        }
        val insets = view.rootWindowInsets ?: return result
        fun radius(pos: Int) = insets.getRoundedCorner(pos)?.radius ?: 0
        val share = 1 - 1 / Math.sqrt(2.0)
        val tl = radius(android.view.RoundedCorner.POSITION_TOP_LEFT)
        val tr = radius(android.view.RoundedCorner.POSITION_TOP_RIGHT)
        val bl = radius(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)
        val br = radius(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT)
        result[0] = (maxOf(tl, bl) * share).toInt()
        result[1] = (maxOf(tl, tr) * share).toInt()
        result[2] = (maxOf(tr, br) * share).toInt()
        result[3] = (maxOf(bl, br) * share).toInt()
        return result
    }

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, OscilloscopeActivity::class.java))
    }
}
