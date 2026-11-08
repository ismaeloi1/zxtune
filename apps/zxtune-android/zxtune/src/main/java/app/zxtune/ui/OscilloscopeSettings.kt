package app.zxtune.ui

import android.content.Context
import androidx.preference.PreferenceDataStore
import app.zxtune.preferences.Preferences
import app.zxtune.preferences.VulkanDriver
import app.zxtune.ui.views.FilterSettings
import app.zxtune.ui.views.OscilloscopeFrame
import app.zxtune.ui.views.OscilloscopeView
import app.zxtune.ui.views.ScopeBackend
import app.zxtune.ui.views.SidDashboardView
import app.zxtune.ui.views.SidFilterModel
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
    private const val FILTER_KEY = "ui.oscilloscope.filter"
    private const val FILTER_WAVES_KEY = "ui.oscilloscope.filter_waves"

    /** Native SID options affecting filter response view */
    const val SID_PREFIX = "zxtune.core.sid."

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
        oscilloscope.showFilter = prefs.getBoolean(FILTER_KEY, false)
        oscilloscope.filterSettings = readFilterSettings(prefs)
    }

    /**
     * Filter response instead of waveforms, the same for all the oscilloscope views
     */
    suspend fun setShowFilter(ctx: Context, show: Boolean) {
        withContext(Dispatchers.IO) {
            Preferences.getDataStore(ctx).putBoolean(FILTER_KEY, show)
        }
        notifyChanged()
    }

    /**
     * Drop-down lists store strings, seek bars store integers
     */
    fun readFilterSettings(store: PreferenceDataStore): FilterSettings {
        fun int(key: String, def: Int) = runCatching { store.getString(key, null) }.getOrNull()?.toIntOrNull()
            ?: runCatching { store.getInt(key, def) }.getOrDefault(def)
        return FilterSettings(
            SidFilterModel.Settings(
                residFp = int("${SID_PREFIX}engine", 1) == 1,
                preset6581 = int("${SID_PREFIX}filter_6581_preset", 0),
                preset8580 = int("${SID_PREFIX}filter_8580_preset", 0),
                curve6581 = int("${SID_PREFIX}filter_6581_curve", 50) / 100.0,
                range6581 = int("${SID_PREFIX}filter_6581_range", 50) / 100.0,
                curve8580 = int("${SID_PREFIX}filter_8580_curve", 50) / 100.0,
                biasMv = int("${SID_PREFIX}filter_bias", 0),
            ),
            is8580 = int("${SID_PREFIX}model", 0) == 1,
            withWaves = runCatching { store.getBoolean(FILTER_WAVES_KEY, true) }.getOrDefault(true),
        )
    }
}
