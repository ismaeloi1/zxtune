/**
 * @file
 * @brief Preference displaying SID filter cutoff curve of current settings
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.core.view.ViewCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import app.zxtune.R
import app.zxtune.ui.views.FilterCurveView

/**
 * Placed right under filter presets, so the effect of any SID filter setting is visible immediately. Settings are
 * polled from preferences data store (cheap, cached), so no change listeners are required for all the related
 * preferences. Live filter response of playing tune is displayed by oscilloscope (see [app.zxtune.ui.views.FilterScene]).
 */
class FilterCurvePreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.preference_filter_curve
        isSelectable = false
    }

    private var view: FilterCurveView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, SETTINGS_PERIOD_MS)
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val curve = holder.findViewById(R.id.filter_curve) as FilterCurveView
        if (view !== curve) {
            stop()
            view = curve
            curve.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = start()
                override fun onViewDetachedFromWindow(v: View) = stop()
            })
            if (ViewCompat.isAttachedToWindow(curve)) {
                start()
            }
        }
        update()
    }

    // Preference outlives its views while hosting fragment is in back stack, so all the references are dropped here
    override fun onDetached() {
        stop()
        view = null
        super.onDetached()
    }

    private fun start() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun stop() = handler.removeCallbacks(tick)

    private fun update() {
        val curve = view ?: return
        val store = preferenceDataStore ?: preferenceManager?.preferenceDataStore
        val settings = store?.let { OscilloscopeSettings.readFilterSettings(it) } ?: readSharedSettings()
        val is8580 = settings.is8580
        val model = settings.model
        val preset = if (is8580) model.preset8580 else model.preset6581
        val names = context.resources.getStringArray(
            if (is8580) R.array.pref_zxtune_sid_filter_8580_preset_entries else R.array.pref_zxtune_sid_filter_6581_preset_entries
        )
        curve.state = FilterCurveView.State(
            settings = model,
            is8580 = is8580,
            presetName = names.getOrNull(preset)?.takeIf { preset != 0 } ?: "",
        )
    }

    // e.g. preferences screen without data store
    private fun readSharedSettings() = OscilloscopeSettings.readFilterSettings(object : androidx.preference.PreferenceDataStore() {
        private val prefs = sharedPreferences
        override fun getString(key: String, defValue: String?) = runCatching { prefs?.getString(key, defValue) }.getOrNull() ?: defValue
        override fun getInt(key: String, defValue: Int) = runCatching { prefs?.getInt(key, defValue) }.getOrNull() ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = runCatching { prefs?.getBoolean(key, defValue) }.getOrNull() ?: defValue
    })

    companion object {
        // settings are changed by user, so it's enough to follow seek bars
        private const val SETTINGS_PERIOD_MS = 250L
    }
}
