package app.zxtune.ui

import android.content.Context
import app.zxtune.preferences.Preferences
import app.zxtune.preferences.VulkanDriver
import app.zxtune.ui.views.OscilloscopeFrame
import app.zxtune.ui.views.OscilloscopeView
import app.zxtune.ui.views.ScopeBackend
import app.zxtune.ui.views.SidDashboardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/**
 * Oscilloscope view settings stored in preferences, see 'ui.oscilloscope.*' keys
 */
internal object OscilloscopeSettings {
    const val PREFIX = "ui.oscilloscope."
    private const val WINDOW_KEY = "ui.oscilloscope.window_ms"
    private const val GAUGE_WINDOW_KEY = "ui.oscilloscope.gauge_window_ms"
    private const val GAIN_KEY = "ui.oscilloscope.gain"
    private const val MAX_FPS_KEY = "ui.oscilloscope.max_fps"
    private const val BACKEND_KEY = "ui.oscilloscope.backend"

    private val changesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Settings changed by live settings panel, to be applied immediately
     */
    val changes = changesFlow.asSharedFlow()

    fun notifyChanged() {
        changesFlow.tryEmit(Unit)
    }

    suspend fun apply(ctx: Context, oscilloscope: OscilloscopeFrame, dashboard: SidDashboardView) {
        val prefs = withContext(Dispatchers.IO) {
            Preferences.getDataStore(ctx).also {
                // load cache in background
                it.getInt(WINDOW_KEY, 0)
            }
        }
        val driver = withContext(Dispatchers.IO) {
            VulkanDriver.getInstalled(ctx)?.takeIf {
                prefs.getBoolean(VulkanDriver.ENABLED_KEY, false) && VulkanDriver.isSupported(ctx)
            }
        }
        oscilloscope.setBackend(prefs.getString(BACKEND_KEY, null) ?: ScopeBackend.PREF_AUTO, driver)
        oscilloscope.windowMs = prefs.getInt(WINDOW_KEY, OscilloscopeView.DEFAULT_WINDOW_MS)
        oscilloscope.gainPercent = prefs.getString(GAIN_KEY, null)?.toIntOrNull() ?: OscilloscopeView.AUTO_GAIN
        oscilloscope.maxFps = prefs.getString(MAX_FPS_KEY, null)?.toIntOrNull() ?: 0
        dashboard.waveWindowMs = prefs.getInt(GAUGE_WINDOW_KEY, SidDashboardView.DEFAULT_WAVE_WINDOW_MS)
    }
}
