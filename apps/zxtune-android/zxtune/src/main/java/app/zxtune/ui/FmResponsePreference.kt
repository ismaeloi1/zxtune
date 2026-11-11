/**
 * @file
 * @brief Preference displaying output stage response of FM chips family
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
import app.zxtune.ui.views.FmResponseView

/**
 * Placed under the output stage settings of a family (identified by key 'ui.fm.<family>.response'). Settings are
 * polled from preferences data store (cheap, cached), as [FilterCurvePreference] does, so no change listeners are
 * required for all the related preferences.
 */
class FmResponsePreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.preference_fm_response
        isSelectable = false
    }

    private var view: FmResponseView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, SETTINGS_PERIOD_MS)
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val chart = holder.findViewById(R.id.fm_response) as FmResponseView
        if (view !== chart) {
            stop()
            view = chart
            chart.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = start()
                override fun onViewDetachedFromWindow(v: View) = stop()
            })
            if (ViewCompat.isAttachedToWindow(chart)) {
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
        val chart = view ?: return
        val family = FmPresets.FAMILIES.firstOrNull { it.responseKey == key } ?: return
        val store = preferenceDataStore ?: preferenceManager?.preferenceDataStore
        val prefs = sharedPreferences
        // seek bars store integers, lists store strings
        chart.stage = FmPresets.stageOf(family) { name ->
            store?.let { runCatching { it.getString(name, null) }.getOrNull() ?: runCatching { it.getInt(name, Int.MIN_VALUE) }.getOrNull()?.takeIf { v -> v != Int.MIN_VALUE } }
                ?: prefs?.all?.get(name)
        }
    }

    companion object {
        // settings are changed by user, so it's enough to follow seek bars
        private const val SETTINGS_PERIOD_MS = 250L
    }
}
