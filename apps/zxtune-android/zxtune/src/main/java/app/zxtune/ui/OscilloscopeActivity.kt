package app.zxtune.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.support.v4.media.session.PlaybackStateCompat
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.zxtune.device.media.MediaModel
import app.zxtune.ui.utils.whenLifecycleStarted
import app.zxtune.ui.views.OscilloscopeView
import app.zxtune.utils.ifNotNulls
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Fullscreen oscilloscope, follows device orientation
 */
class OscilloscopeActivity : AppCompatActivity() {
    private lateinit var oscilloscope: OscilloscopeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        oscilloscope = OscilloscopeView(this).apply {
            isTranslucent = false
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { finish() }
        }
        setContentView(oscilloscope)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupImmersiveMode()
        requestHighestRefreshRate()

        MediaModel.of(this).run {
            whenLifecycleStarted {
                visualizer.combine(playbackState) { visualizer, playbackState ->
                    ifNotNulls(visualizer, playbackState?.state) { src, state ->
                        src.takeIf { PlaybackStateCompat.STATE_PLAYING == state }
                    }
                }.distinctUntilChanged { old, new -> old === new }.collect {
                    oscilloscope.setSource(it)
                }
            }
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
        super.onStop()
    }

    private fun setupImmersiveMode() {
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
