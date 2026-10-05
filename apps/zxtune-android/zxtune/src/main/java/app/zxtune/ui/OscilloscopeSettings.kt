package app.zxtune.ui

import android.content.Context
import app.zxtune.preferences.Preferences
import app.zxtune.ui.views.OscilloscopeView
import app.zxtune.ui.views.SidDashboardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Oscilloscope view settings stored in preferences, see 'ui.oscilloscope.*' keys
 */
internal object OscilloscopeSettings {
    private const val WINDOW_KEY = "ui.oscilloscope.window_ms"
    private const val GAUGE_WINDOW_KEY = "ui.oscilloscope.gauge_window_ms"

    suspend fun apply(ctx: Context, oscilloscope: OscilloscopeView, dashboard: SidDashboardView) {
        val (window, gaugeWindow) = withContext(Dispatchers.IO) {
            val prefs = Preferences.getDataStore(ctx)
            prefs.getInt(WINDOW_KEY, OscilloscopeView.DEFAULT_WINDOW_MS) to
                prefs.getInt(GAUGE_WINDOW_KEY, SidDashboardView.DEFAULT_WAVE_WINDOW_MS)
        }
        oscilloscope.windowMs = window
        dashboard.waveWindowMs = gaugeWindow
    }
}
